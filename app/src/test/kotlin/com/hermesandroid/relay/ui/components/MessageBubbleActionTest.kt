package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
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
}
