package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageBubbleActionTest {
    @Test
    fun speakResponse_requiresHandlerAndCompletedAssistantText() {
        val assistant = ChatMessage(
            id = "assistant",
            role = MessageRole.ASSISTANT,
            content = "A completed response.",
            timestamp = 1L,
        )

        assertTrue(shouldShowSpeakResponseAction(assistant, handlerAvailable = true))
        assertFalse(shouldShowSpeakResponseAction(assistant, handlerAvailable = false))
        assertFalse(
            shouldShowSpeakResponseAction(
                assistant.copy(isStreaming = true),
                handlerAvailable = true,
            ),
        )
        assertFalse(
            shouldShowSpeakResponseAction(
                assistant.copy(role = MessageRole.USER),
                handlerAvailable = true,
            ),
        )
        assertFalse(
            shouldShowSpeakResponseAction(
                assistant.copy(content = "  "),
                handlerAvailable = true,
            ),
        )
    }

    @Test
    fun stopSpeaking_requiresHandlerAndCompletedAssistantText() {
        val assistant = ChatMessage(
            id = "assistant",
            role = MessageRole.ASSISTANT,
            content = "A response being narrated.",
            timestamp = 1L,
        )

        assertTrue(shouldShowStopSpeakingAction(assistant, handlerAvailable = true))
        assertFalse(shouldShowStopSpeakingAction(assistant, handlerAvailable = false))
        assertFalse(
            shouldShowStopSpeakingAction(
                assistant.copy(isStreaming = true),
                handlerAvailable = true,
            ),
        )
        assertFalse(
            shouldShowStopSpeakingAction(
                assistant.copy(role = MessageRole.USER),
                handlerAvailable = true,
            ),
        )
    }

    @Test
    fun partialSelection_keepsOneTopologyAcrossStreamingAndFinalization() {
        val initialLive = messageSelectionTopologyKey(
            isPlainText = false,
            isStreaming = true,
            retainStreamingLayout = false,
            markdownBody = "First",
        )
        val updatedLive = messageSelectionTopologyKey(
            isPlainText = false,
            isStreaming = true,
            retainStreamingLayout = false,
            markdownBody = "First paragraph\n\nSecond",
        )
        val retainedLive = messageSelectionTopologyKey(
            isPlainText = false,
            isStreaming = false,
            retainStreamingLayout = true,
            markdownBody = "First paragraph\n\nSecond",
        )
        val settledMarkdown = messageSelectionTopologyKey(
            isPlainText = false,
            isStreaming = false,
            retainStreamingLayout = false,
            markdownBody = "First paragraph\n\nSecond",
        )
        val revisedMarkdown = messageSelectionTopologyKey(
            isPlainText = false,
            isStreaming = false,
            retainStreamingLayout = false,
            markdownBody = "First paragraph\n\nSecond\n\nThird",
        )

        assertEquals(initialLive, updatedLive)
        assertEquals(initialLive, retainedLive)
        assertEquals(initialLive, settledMarkdown)
        assertEquals(settledMarkdown, revisedMarkdown)
    }

    @Test
    fun completedAssistantSyntax_usesStableStreamingMarkdownRenderer() {
        val completedBodies = listOf(
            "```kotlin\nval answer = 42\n```",
            "- first\n- second",
            "**bold** and *emphasis*",
            "[Hermes](https://example.com)",
        )

        completedBodies.forEach { body ->
            assertEquals(
                MessageSelectionTopologyKey(renderer = "streaming-markdown", markdownBody = null),
                messageSelectionTopologyKey(
                    isPlainText = false,
                    isStreaming = false,
                    retainStreamingLayout = false,
                    markdownBody = body,
                ),
            )
        }
    }

    @Test
    fun interruptedPartialReply_keepsStableStreamingMarkdownRenderer() {
        val partialFence = "```kotlin\nval partial ="

        assertEquals(
            MessageSelectionTopologyKey(renderer = "streaming-markdown", markdownBody = null),
            messageSelectionTopologyKey(
                isPlainText = false,
                isStreaming = false,
                retainStreamingLayout = false,
                markdownBody = partialFence,
            ),
        )
    }
}
