package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyPreviewBoundsTest {
    @Test
    fun `child preview drops system rows results and oversized live content`() {
        val handler = ChatHandler()
        handler.addPlaceholderMessage(
            ChatMessage("system", MessageRole.SYSTEM, "private system context", 1L),
        )
        handler.addPlaceholderMessage(
            ChatMessage(
                id = "child",
                role = MessageRole.ASSISTANT,
                content = "x".repeat(20_000),
                timestamp = 2L,
                thinkingContent = "y".repeat(20_000),
                toolCalls = listOf(
                    ToolCall(
                        name = "read_file",
                        args = "a".repeat(5_000),
                        result = "secret result",
                        success = true,
                    ),
                ),
            ),
        )

        assertTrue(handler.boundReadOnlyPreview(maxTotalChars = 4_000, maxFieldChars = 2_000))

        val messages = handler.messages.value
        assertEquals(listOf("child"), messages.map(ChatMessage::id))
        assertTrue(messages.sumOf { it.content.length + it.thinkingContent.length } <= 4_000)
        assertTrue(messages.single().toolCalls.single().args.orEmpty().length <= 1_000)
        assertEquals(null, messages.single().toolCalls.single().result)
        assertFalse(messages.any { it.role == MessageRole.SYSTEM })
    }
}
