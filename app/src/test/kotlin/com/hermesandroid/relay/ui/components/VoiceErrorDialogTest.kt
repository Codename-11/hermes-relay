package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.VoicePresentationMode
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.InteractionMode
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xhdpi")
class VoiceErrorDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun conversationError_isModalAndDismissesWithoutStartingMicrophone() {
        val actions = mutableListOf<String>()
        renderError(onClear = { actions += "clear" }, onMicTap = { actions += "mic" })

        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("Voice error").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("build/ui-evidence/voice-error-conversation-dark.png")
        compose.onNodeWithText("Dismiss").performClick()
        compose.mainClock.advanceTimeByFrame()

        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("clear"), actions) }
    }

    @Test
    fun focusError_retryClearsErrorBeforeStartingMicrophone() {
        val actions = mutableListOf<String>()
        renderError(
            mode = VoicePresentationMode.Focus,
            onClear = { actions += "clear" },
            onMicTap = { actions += "mic" },
        )

        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.mainClock.advanceTimeByFrame()

        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("clear", "mic"), actions) }
    }

    @Test
    @Config(qualifiers = "w780dp-h360dp-xhdpi")
    fun landscapeLargeText_longErrorScrollsWithoutHidingActions() {
        val longError = List(8) { PROVIDER_ERROR }.joinToString("\n\n")
        renderError(error = longError, theme = "light", fontScale = 1.5f)

        compose.onNodeWithText("Dismiss").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        val detail = compose.onNodeWithText(longError)
        val scrollRange = detail.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(scrollRange.maxValue() > 0f)
        detail.performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue(scrollRange.value() > 0f) }
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("build/ui-evidence/voice-error-landscape-light-large-text.png")
    }

    @Test
    fun noError_doesNotShowDialog() {
        renderError(error = null)
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    private fun renderError(
        error: String? = PROVIDER_ERROR,
        mode: VoicePresentationMode = VoicePresentationMode.Conversation,
        theme: String = "dark",
        fontScale: Float = 1f,
        onClear: () -> Unit = {},
        onMicTap: () -> Unit = {},
    ) {
        var state by mutableStateOf(
            VoiceUiState(
                voiceMode = true,
                state = if (error == null) VoiceState.Idle else VoiceState.Error,
                interactionMode = InteractionMode.TapToTalk,
                error = error,
            ),
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesRelayTheme(themePreference = theme, fontScale = fontScale) {
                Surface(Modifier.fillMaxSize()) {
                    Box {
                        Text("Chat remains behind the modal")
                        VoiceModeOverlay(
                            uiState = state,
                            presentationMode = mode,
                            onMicTap = onMicTap,
                            onMicRelease = {},
                            onInterrupt = {},
                            onDismiss = {},
                            onModeChange = {},
                            onClearError = {
                                onClear()
                                state = state.copy(error = null, state = VoiceState.Idle)
                            },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
    }

    private companion object {
        const val PROVIDER_ERROR = "Hermes audio transcribe rejected that input - " +
            "{\"detail\":\"No STT provider available. Install faster-whisper for free local " +
            "transcription, configure HERMES_LOCAL_STT_COMMAND or install a local whisper CLI, " +
            "set GROQ_API_KEY for free Groq Whisper, set MISTRAL_API_KEY for Mistral Voxtral " +
            "Transcribe, configure xAI OAuth or set XAI_API_KEY for xAI Grok STT, set " +
            "ELEVENLABS_API_KEY for ElevenLabs Scribe, or set VOICE_TOOLS_OPENAI_KEY or " +
            "OPENAI_API_KEY for the OpenAI Whisper API.\"}"
    }
}
