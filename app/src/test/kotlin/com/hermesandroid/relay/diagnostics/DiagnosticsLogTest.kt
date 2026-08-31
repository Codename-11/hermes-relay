package com.hermesandroid.relay.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogTest {
    @Test
    fun sanitizeUrlDropsSecretsAndHostWhileKeepingPath() {
        assertEquals(
            "https://[host]/health",
            DiagnosticsLog.sanitizeUrl(
                "https://user:secret@relay.example.test:8767/health?token=abc#frag",
            ),
        )
    }

    @Test
    fun recordHidesTokenLikeDetails() {
        DiagnosticsLog.clear()

        DiagnosticsLog.record(
            category = DiagnosticCategory.Session,
            severity = DiagnosticSeverity.Warning,
            title = "Auth failed",
            detail = "session_token=abc123 should not leak",
        )

        val entry = DiagnosticsLog.recent().single()
        assertEquals("Auth failed", entry.title)
        assertEquals("session_token=[hidden] should not leak", entry.detail)
        assertFalse(entry.detail.orEmpty().contains("abc123"))
    }

    @Test
    fun recentFiltersByCategory() {
        DiagnosticsLog.clear()

        DiagnosticsLog.record(DiagnosticCategory.Api, title = "API")
        DiagnosticsLog.record(DiagnosticCategory.Relay, title = "Relay")

        val relay = DiagnosticsLog.recent(setOf(DiagnosticCategory.Relay))
        assertEquals(1, relay.size)
        assertEquals("Relay", relay.first().title)
        assertTrue(DiagnosticsLog.recent(setOf(DiagnosticCategory.Voice)).isEmpty())
    }

    @Test
    fun sanitizeUrlHandlesBlank() {
        assertNull(DiagnosticsLog.sanitizeUrl("   "))
    }

    @Test
    fun supportTextIsBoundedAndReappliesSecretRedaction() {
        val entries = (1..85).map { index ->
            DiagnosticLogEntry(
                timestampMs = index.toLong(),
                category = DiagnosticCategory.Auth,
                severity = DiagnosticSeverity.Info,
                title = "Auth $index",
                detail = "token=secret-$index",
                operation = "dashboard_native_pkce",
                configuredUrl = "https://private.example.test/path?token=secret-$index",
            )
        }

        val text = DiagnosticsLog.supportText(entries)

        assertEquals(80, Regex("===== Diagnostic ").findAll(text).count())
        assertFalse(text.contains("secret-"))
        assertFalse(text.contains("private.example.test"))
        assertFalse(text.contains("Auth 1\n"))
        assertTrue(text.contains("Auth 85"))
    }
}
