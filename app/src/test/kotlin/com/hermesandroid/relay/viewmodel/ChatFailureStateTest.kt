package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFailureStateTest {
    private val failure = ChatFailureNotice(
        sessionId = "session-a",
        turnId = "turn-a",
        rawError = "provider failed",
        route = ChatFailureRoute.GATEWAY,
    )

    @Test
    fun `failure is visible only in its owning session`() {
        assertEquals(failure, scopedChatFailure(failure, "session-a"))
        assertNull(scopedChatFailure(failure, "session-b"))
        assertNull(scopedChatFailure(failure, null))
    }

    @Test
    fun `draft failure stays scoped to the draft`() {
        val draft = failure.copy(sessionId = null)
        assertEquals(draft, scopedChatFailure(draft, null))
        assertNull(scopedChatFailure(draft, "created-session"))
    }

    @Test
    fun `structured failure records reviewable redacted route identity`() {
        DiagnosticsLog.clear()
        recordChatFailureDiagnostic(
            failure.copy(
                rawError = "HTTP 404 from endpoint api_key=secret-value",
                model = "agnes-2",
                provider = "openrouter",
            ),
            liveSessionId = "live-a",
        )

        val entry = DiagnosticsLog.recent(setOf(DiagnosticCategory.Session), 1).single()
        assertEquals(DiagnosticSeverity.Error, entry.severity)
        assertEquals("gateway", entry.endpointRole)
        assertTrue(entry.detail.orEmpty().contains("model=agnes-2"))
        assertTrue(entry.detail.orEmpty().contains("provider=openrouter"))
        assertTrue(entry.detail.orEmpty().contains("stored_session=session-a"))
        assertTrue(entry.detail.orEmpty().contains("live_session=live-a"))
        assertTrue(entry.detail.orEmpty().contains("HTTP 404"))
        assertFalse(entry.detail.orEmpty().contains("secret-value"))
    }
}
