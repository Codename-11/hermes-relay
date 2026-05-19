package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceModeOverlayStateTest {

    @Test
    fun pendingTranscript_showsWhileThinkingBeforeChatHistoryCatchesUp() {
        val text = pendingVoiceTranscriptText(
            uiState = VoiceUiState(
                state = VoiceState.Thinking,
                transcribedText = "What did you hear?",
            ),
            visibleTranscriptMessages = emptyList(),
        )

        assertEquals("What did you hear?", text)
    }

    @Test
    fun pendingTranscript_hidesWhenSameUserMessageIsAlreadyInChatHistory() {
        val text = pendingVoiceTranscriptText(
            uiState = VoiceUiState(
                state = VoiceState.Thinking,
                transcribedText = "Open settings",
            ),
            visibleTranscriptMessages = listOf(
                ChatMessage(
                    id = "user-1",
                    role = MessageRole.USER,
                    content = "Open settings",
                    timestamp = 1L,
                ),
            ),
        )

        assertNull(text)
    }

    @Test
    fun pendingTranscript_hidesWhenAssistantPlaceholderFollowsSameUserMessage() {
        val text = pendingVoiceTranscriptText(
            uiState = VoiceUiState(
                state = VoiceState.Thinking,
                transcribedText = "Open settings",
            ),
            visibleTranscriptMessages = listOf(
                ChatMessage(
                    id = "user-1",
                    role = MessageRole.USER,
                    content = "Open settings",
                    timestamp = 1L,
                ),
                ChatMessage(
                    id = "assistant-1",
                    role = MessageRole.ASSISTANT,
                    content = "",
                    timestamp = 2L,
                    isStreaming = true,
                ),
            ),
        )

        assertNull(text)
    }

    @Test
    fun pendingTranscript_doesNotDeduplicateAgainstOlderMatchingTurn() {
        val text = pendingVoiceTranscriptText(
            uiState = VoiceUiState(
                state = VoiceState.Thinking,
                transcribedText = "Open settings",
            ),
            visibleTranscriptMessages = listOf(
                ChatMessage(
                    id = "user-1",
                    role = MessageRole.USER,
                    content = "Open settings",
                    timestamp = 1L,
                ),
                ChatMessage(
                    id = "assistant-1",
                    role = MessageRole.ASSISTANT,
                    content = "Settings are open.",
                    timestamp = 2L,
                ),
            ),
        )

        assertEquals("Open settings", text)
    }

    @Test
    fun pendingTranscript_hidesOutsideWaitingState() {
        val text = pendingVoiceTranscriptText(
            uiState = VoiceUiState(
                state = VoiceState.Speaking,
                transcribedText = "Tell me a joke",
            ),
            visibleTranscriptMessages = emptyList(),
        )

        assertNull(text)
    }
}
