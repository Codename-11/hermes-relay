package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTurnSessionFenceTest {
    @Test
    fun `pending new chat rejects switch to unrelated existing session`() {
        val fence = VoiceTurnSessionFence(initialSessionId = null)
        fence.bindSubmittedUser("voice-user")

        assertTrue(fence.accepts(sessionId = null, messages = emptyList()))
        assertFalse(
            fence.accepts(
                sessionId = "existing-session",
                messages = listOf(user("other-user")),
            ),
        )
    }

    @Test
    fun `pending new chat adopts only session containing submitted turn`() {
        val fence = VoiceTurnSessionFence(initialSessionId = null)
        fence.bindSubmittedUser("voice-user")
        val messages = listOf(user("voice-user"))

        assertTrue(fence.accepts(sessionId = "new-session", messages = messages))
        assertTrue(fence.accepts(sessionId = "new-session", messages = emptyList()))
        assertFalse(fence.accepts(sessionId = "different-session", messages = messages))
    }

    @Test
    fun `existing session is fixed for complete voice turn`() {
        val fence = VoiceTurnSessionFence(initialSessionId = "active")
        fence.bindSubmittedUser("voice-user")

        assertTrue(fence.accepts(sessionId = "active", messages = emptyList()))
        assertFalse(fence.accepts(sessionId = null, messages = emptyList()))
        assertFalse(fence.accepts(sessionId = "other", messages = emptyList()))
    }

    private fun user(uiKey: String) = ChatMessage(
        id = "id-$uiKey",
        role = MessageRole.USER,
        content = "Voice request",
        timestamp = 1L,
        uiKey = uiKey,
    )
}
