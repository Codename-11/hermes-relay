package com.hermesandroid.relay.reliability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionResetEvidenceTest {
    @Test
    fun `checkpoint contains only bounded structural evidence`() {
        val text = SessionResetEvidence(
            reason = "New Chat / user",
            transport = "gateway",
            messageCount = 12,
            toolCount = 4,
            queuedCount = 1,
            pendingAttachmentCount = 2,
            hadStoredSession = true,
            turnActive = false,
            askPending = true,
        ).technicalDetail()

        assertTrue(text.contains("reason=new_chat___user"))
        assertTrue(text.contains("messages=12"))
        assertTrue(text.contains("pending_attachments=2"))
        assertFalse(text.contains("prompt"))
        assertFalse(text.contains("session_id"))
        assertFalse(text.contains("profile"))
    }
}
