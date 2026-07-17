package com.hermesandroid.relay.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollSnapshotTest {
    @Test
    fun `stream completion requires a bottom settle even when content is unchanged`() {
        val streaming = snapshot(isStreaming = true)
        val complete = snapshot(isStreaming = false)

        assertTrue(complete.isCompletionAfter(streaming))
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
    ) = ChatScrollSnapshot(
        messageCount = 8,
        lastMessageId = "assistant-live-id",
        lastContentLength = contentLength,
        lastThinkingLength = 1_200,
        lastToolCallCount = 2,
        isStreaming = isStreaming,
    )
}
