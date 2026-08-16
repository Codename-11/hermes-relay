package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.viewmodel.InteractionMode
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xxhdpi")
class VoiceModeOverlayInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun focusMode_childControlsReceiveRealPointerClicks() {
        var micTaps = 0
        var dismissals = 0
        val modeChanges = mutableListOf<InteractionMode>()
        compose.mainClock.autoAdvance = false

        compose.setContent {
            MaterialTheme {
                VoiceModeOverlay(
                    uiState = idleVoiceState(),
                    onMicTap = { micTaps += 1 },
                    onMicRelease = {},
                    onInterrupt = {},
                    onDismiss = { dismissals += 1 },
                    onModeChange = { modeChanges += it },
                    onClearError = {},
                )
            }
        }

        compose.onNodeWithContentDescription("Voice mic")
            .performTouchInput { click() }
        compose.onNodeWithContentDescription("Expand voice controls")
            .performTouchInput { click() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Hold")
            .performTouchInput { click() }
        compose.onNodeWithContentDescription("Collapse voice controls")
            .performTouchInput { click() }
        compose.onNodeWithContentDescription("Exit voice mode")
            .performTouchInput { click() }

        compose.runOnIdle {
            assertEquals(1, micTaps)
            assertEquals(1, dismissals)
            assertEquals(listOf(InteractionMode.HoldToTalk), modeChanges)
        }
    }

    @Test
    fun focusMode_emptySpaceDoesNotClickThroughToChat() {
        var backgroundTaps = 0
        compose.mainClock.autoAdvance = false

        compose.setContent {
            MaterialTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("backgroundChat")
                        .clickable { backgroundTaps += 1 },
                )
                VoiceModeOverlay(
                    uiState = idleVoiceState(),
                    onMicTap = {},
                    onMicRelease = {},
                    onInterrupt = {},
                    onDismiss = {},
                    onModeChange = {},
                    onClearError = {},
                )
            }
        }

        compose.onNodeWithTag(FOCUS_INPUT_SCRIM_TAG)
            .performTouchInput { click(Offset(1f, center.y)) }

        compose.runOnIdle { assertEquals(0, backgroundTaps) }
    }

    private fun idleVoiceState() = VoiceUiState(
        voiceMode = true,
        state = VoiceState.Idle,
        interactionMode = InteractionMode.TapToTalk,
    )

    private companion object {
        const val FOCUS_INPUT_SCRIM_TAG = "voiceFocusInputScrim"
    }
}
