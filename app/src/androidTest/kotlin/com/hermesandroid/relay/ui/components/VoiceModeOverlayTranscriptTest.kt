package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.viewmodel.InteractionMode
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the voice overlay renders exactly one transcript row per turn
 * even when [VoiceUiState.transcribedText] and [VoiceUiState.responseText]
 * are populated alongside the same content in [ChatMessage]s.
 *
 * Pre-fix bug: the overlay rendered from THREE sources — `transcribedText`
 * (a top "YOU" row), `responseText` (a `StreamingResponseRow`), and
 * `transcriptMessages` (the scrolling chat-history list). During a voice
 * turn ChatViewModel committed the user's send and streamed the assistant
 * reply into its own message flow, so the same content ended up in both
 * `transcribedText`/`responseText` AND in `transcriptMessages` — every
 * turn appeared twice on screen.
 *
 * Fix: the overlay consumes only `transcriptMessages` now. This test asserts
 * that even when the other two fields are set, the on-screen count of each
 * turn's text is exactly one.
 */
class VoiceModeOverlayTranscriptTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun userAndAgentTurns_renderExactlyOnce_evenWithLegacyFieldsSet() {
        val userText = "Hello agent"
        val agentText = "Hi there"

        val messages = listOf(
            ChatMessage(
                id = "u-1",
                role = MessageRole.USER,
                content = userText,
                timestamp = 1L,
                isStreaming = false,
            ),
            ChatMessage(
                id = "a-1",
                role = MessageRole.ASSISTANT,
                content = agentText,
                timestamp = 2L,
                isStreaming = true,
            ),
        )

        composeTestRule.setContent {
            MaterialTheme {
                VoiceModeOverlay(
                    uiState = VoiceUiState(
                        voiceMode = true,
                        state = VoiceState.Speaking,
                        // Legacy fields — if the overlay still read these,
                        // each turn's text would appear twice.
                        transcribedText = userText,
                        responseText = agentText,
                        interactionMode = InteractionMode.TapToTalk,
                    ),
                    onMicTap = {},
                    onMicRelease = {},
                    onInterrupt = {},
                    onDismiss = {},
                    onModeChange = {},
                    onClearError = {},
                    transcriptMessages = messages,
                )
            }
        }

        composeTestRule.waitForIdle()

        val userOccurrences = composeTestRule
            .onAllNodesWithText(userText, substring = false)
            .fetchSemanticsNodes()
            .size
        val agentOccurrences = composeTestRule
            .onAllNodesWithText(agentText, substring = false)
            .fetchSemanticsNodes()
            .size

        assertEquals(
            "user turn must render exactly once (no double-entry from transcribedText)",
            1,
            userOccurrences,
        )
        assertEquals(
            "agent turn must render exactly once (no double-entry from responseText)",
            1,
            agentOccurrences,
        )
    }
}
