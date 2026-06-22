package com.hermesandroid.relay.util

import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import java.io.IOException
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two new shared pieces:
 *  - [IssueReport.buildGithubIssueUrl] produces a stable, properly-encoded URL.
 *  - [classifyError] records the classified failure into [DiagnosticsLog] as a
 *    side effect (Error severity, clean title, redacted full stacktrace).
 *
 * Both run on pure JVM — no Android framework / Robolectric needed, since
 * [IssueReport.buildGithubIssueUrl], the classifier, and the log are all plain
 * Kotlin/Java.
 */
class IssueReportAndDiagnosticsTest {

    @Test
    fun buildGithubIssueUrlEncodesTitleBodyAndLabels() {
        val url = IssueReport.buildGithubIssueUrl(
            title = "[Bug]: Crash — NullPointerException",
            bodyMarkdown = "line one\nline two & more",
            labels = "bug",
        )

        assertTrue(url.startsWith("https://github.com/Codename-11/hermes-relay/issues/new?"))
        // Stable param order: title, labels, body.
        assertTrue(url.indexOf("title=") < url.indexOf("labels="))
        assertTrue(url.indexOf("labels=") < url.indexOf("body="))
        // Spaces encoded as %20 (not '+'), so the URL works in a browser bar.
        assertFalse(url.contains("+"))
        assertTrue(url.contains("%20"))

        val body = url.substringAfter("body=")
        assertEquals("line one\nline two & more", URLDecoder.decode(body, "UTF-8"))
    }

    @Test
    fun buildGithubIssueUrlOmitsBlankLabels() {
        val url = IssueReport.buildGithubIssueUrl(
            title = "t",
            bodyMarkdown = "b",
            labels = "",
        )
        assertFalse(url.contains("labels="))
    }

    @Test
    fun classifyErrorRecordsAnErrorEntryWithCleanTitleAndTrace() {
        DiagnosticsLog.clear()

        val human = classifyError(
            IOException("List sessions unauthorized - check your API key"),
            context = "send_message",
        )

        val entry = DiagnosticsLog.recent().single()
        assertEquals(DiagnosticSeverity.Error, entry.severity)
        assertEquals(DiagnosticCategory.Api, entry.category)
        // Clean human title is what lands in the list — not the raw exception text.
        assertEquals(human.title, entry.title)
        assertEquals("API key rejected", entry.title)
        // Full stacktrace is captured for the detail page.
        assertNotNull(entry.stacktrace)
        assertTrue(entry.stacktrace!!.contains("IOException"))
    }

    @Test
    fun classifyErrorMapsVoiceContextToVoiceCategory() {
        DiagnosticsLog.clear()

        classifyError(IOException("404 not found"), context = "voice_config")

        val entry = DiagnosticsLog.recent().single()
        assertEquals(DiagnosticCategory.Voice, entry.category)
    }

    @Test
    fun classifyErrorWithNullThrowableRecordsNothing() {
        DiagnosticsLog.clear()

        classifyError(null, context = "send_message")

        assertTrue(DiagnosticsLog.recent().isEmpty())
    }

    @Test
    fun recordErrorRedactsSecretsInTheStacktrace() {
        DiagnosticsLog.clear()

        DiagnosticsLog.recordError(
            category = DiagnosticCategory.Relay,
            title = "Boom",
            throwable = RuntimeException("rejected token=super-secret-token-value end"),
        )

        val entry = DiagnosticsLog.recent().single()
        assertNotNull(entry.stacktrace)
        assertFalse(entry.stacktrace!!.contains("super-secret-token-value"))
        assertTrue(entry.stacktrace!!.contains("token=[hidden]"))
    }
}
