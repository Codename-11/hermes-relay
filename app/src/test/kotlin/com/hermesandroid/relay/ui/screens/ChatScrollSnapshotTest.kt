package com.hermesandroid.relay.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollSnapshotTest {
    @Test
    fun `completion releases only the retained live tail`() {
        assertNull(releaseRetainedLiveTail("assistant-live", "assistant-live"))
        assertEquals(
            "assistant-live",
            releaseRetainedLiveTail("assistant-live", "different-message"),
        )
        assertNull(releaseRetainedLiveTail(null, "assistant-live"))
    }

    @Test
    fun `tall markdown tail is positioned by its trailing edge`() {
        assertEquals(
            1_208,
            tailEndScrollOffset(
                tailSizePx = 2_400,
                footerSizePx = 8,
                viewportSizePx = 1_200,
            ),
        )
        assertEquals(
            0,
            tailEndScrollOffset(
                tailSizePx = 600,
                footerSizePx = 8,
                viewportSizePx = 1_200,
            ),
        )
    }

    @Test
    fun `same-tail stream completion requests an atomic bottom anchor`() {
        val streaming = snapshot(isStreaming = true)
        val complete = snapshot(isStreaming = false)

        assertTrue(complete.isCompletionAfter(streaming))
    }

    @Test
    fun `tail replacement is not mistaken for stream completion`() {
        val streaming = snapshot(isStreaming = true)
        val replaced = snapshot(isStreaming = false, lastMessageUiKey = "replacement-tail")

        assertFalse(replaced.isCompletionAfter(streaming))
    }

    @Test
    fun `message list rebuild is not mistaken for stream completion`() {
        val streaming = snapshot(isStreaming = true)
        val rebuilt = snapshot(isStreaming = false, messageCount = 10)

        assertFalse(rebuilt.isCompletionAfter(streaming))
    }

    @Test
    fun `ordinary streaming growth is not a completion`() {
        val before = snapshot(contentLength = 4_000, isStreaming = true)
        val after = snapshot(contentLength = 4_500, isStreaming = true)

        assertFalse(after.isCompletionAfter(before))
    }

    @Test
    fun `starting a stream is not a completion`() {
        val idle = snapshot(isStreaming = false)
        val streaming = snapshot(isStreaming = true)

        assertFalse(streaming.isCompletionAfter(idle))
    }

    @Test
    fun `server id adoption remains an observable tail change`() {
        val local = snapshot(isStreaming = false)
        val reconciled = local.copy(lastMessageId = "assistant-server-id")

        assertNotEquals(local, reconciled)
    }

    private fun snapshot(
        contentLength: Int = 12_000,
        isStreaming: Boolean,
        messageCount: Int = 8,
        lastMessageUiKey: String = "assistant-ui-key",
    ) = ChatScrollSnapshot(
        messageCount = messageCount,
        lastMessageId = "assistant-live-id",
        lastMessageUiKey = lastMessageUiKey,
        lastContentLength = contentLength,
        lastThinkingLength = 1_200,
        lastToolCallCount = 2,
        isStreaming = isStreaming,
    )
}
