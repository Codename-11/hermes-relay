package com.hermesandroid.relay.util

import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticLogEntry
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

    // --- DiagnosticIssuePrefill: severity-dependent title / labels / body ---

    private fun sampleEntry(
        severity: DiagnosticSeverity,
        title: String = "Testing API connection",
        endpointRole: String? = null,
        url: String? = null,
        detail: String? = null,
    ) = DiagnosticLogEntry(
        timestampMs = 0L,
        category = DiagnosticCategory.Api,
        severity = severity,
        title = title,
        detail = detail,
        endpointRole = endpointRole,
        url = url,
    )

    @Test
    fun errorEntriesKeepBugTitleAndLabel() {
        val entry = sampleEntry(DiagnosticSeverity.Error, title = "API key rejected")
        assertEquals("[Bug]: API key rejected", DiagnosticIssuePrefill.issueTitle(entry))
        assertEquals("bug", DiagnosticIssuePrefill.issueLabels(entry))
    }

    @Test
    fun infoAndWarningEntriesRetitleAsDiagnosticWithQuestionLabel() {
        for (severity in listOf(DiagnosticSeverity.Info, DiagnosticSeverity.Warning)) {
            val entry = sampleEntry(severity)
            assertEquals("[Diagnostic]: Testing API connection", DiagnosticIssuePrefill.issueTitle(entry))
            // "question" already exists on the repo — the prefill must not invent labels.
            assertEquals("question", DiagnosticIssuePrefill.issueLabels(entry))
        }
    }

    @Test
    fun bodyUsesTheExpectationAnswerAsWhatHappened() {
        val body = DiagnosticIssuePrefill.issueBody(
            sampleEntry(DiagnosticSeverity.Info),
            expectation = "I expected the app to connect to my server",
        )
        assertTrue(body.contains("### What happened?\nI expected the app to connect to my server\n"))
        assertFalse(body.contains("Captured diagnostic from the in-app activity log."))
    }

    @Test
    fun bodyFallsBackToBoilerplateWithoutAnExpectation() {
        val body = DiagnosticIssuePrefill.issueBody(sampleEntry(DiagnosticSeverity.Error))
        assertTrue(body.contains("### What happened?\nCaptured diagnostic from the in-app activity log.\n"))
    }

    @Test
    fun expectationAnswerIsSecretRedactedBeforeEmbedding() {
        val body = DiagnosticIssuePrefill.issueBody(
            sampleEntry(DiagnosticSeverity.Info),
            expectation = "it failed with token=super-secret-value somehow",
        )
        assertFalse(body.contains("super-secret-value"))
        assertTrue(body.contains("token=[hidden]"))
    }

    @Test
    fun connectionModeUsesTheEntryRoleWhenPresent() {
        val body = DiagnosticIssuePrefill.issueBody(
            sampleEntry(DiagnosticSeverity.Error, endpointRole = "tailscale", url = "http://10.0.0.5:8642"),
        )
        assertTrue(body.contains("- Connection mode: tailscale"))
        assertFalse(body.contains("LAN / Tailscale / public TLS / other"))
    }

    @Test
    fun connectionModeIsInferredFromTheEntryUrl() {
        val lan = DiagnosticIssuePrefill.issueBody(
            sampleEntry(DiagnosticSeverity.Info, url = "http://localhost:8642"),
        )
        assertTrue(lan.contains("- Connection mode: lan"))

        val tailscale = DiagnosticIssuePrefill.issueBody(
            sampleEntry(DiagnosticSeverity.Info, url = "http://100.79.214.107:8642"),
        )
        assertTrue(tailscale.contains("- Connection mode: tailscale"))
    }

    @Test
    fun connectionModeIsUnknownWithoutRouteOrUrl() {
        val body = DiagnosticIssuePrefill.issueBody(sampleEntry(DiagnosticSeverity.Warning))
        assertTrue(body.contains("- Connection mode: unknown"))
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
