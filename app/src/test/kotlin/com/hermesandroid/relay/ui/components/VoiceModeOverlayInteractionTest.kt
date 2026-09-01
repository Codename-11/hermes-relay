package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
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

        compose.onNodeWithContentDescription("start listening")
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
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun expandedLandscape_usesSplitLayoutAndKeepsMicSemanticAction() {
        var micTaps = 0
        compose.mainClock.autoAdvance = false

        compose.setContent {
            MaterialTheme {
                VoiceModeOverlay(
                    uiState = idleVoiceState(),
                    onMicTap = { micTaps += 1 },
                    onMicRelease = {},
                    onInterrupt = {},
                    onDismiss = {},
                    onModeChange = {},
                    onClearError = {},
                )
            }
        }

        compose.onNodeWithTag(VOICE_FOCUS_SPLIT_LAYOUT_TEST_TAG).assertExists()
        compose.onNodeWithTag(VOICE_FOCUS_STACKED_LAYOUT_TEST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(VOICE_MODE_MIC_TEST_TAG)
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)

        compose.runOnIdle { assertEquals(1, micTaps) }
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

    @Test
    fun unavailableSystemOverlayActionIsNotOffered() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                VoiceModeOverlay(
                    uiState = idleVoiceState(),
                    onMicTap = {},
                    onMicRelease = {},
                    onInterrupt = {},
                    onDismiss = {},
                    onModeChange = {},
                    onClearError = {},
                    systemOverlayAvailable = false,
                )
            }
        }

        compose.onNodeWithContentDescription("Expand voice controls")
            .performTouchInput { click() }
        compose.mainClock.advanceTimeBy(500)

        compose.onNodeWithText("Overlay").assertDoesNotExist()
    }

    @Test
    fun focusMode_holdSemanticActionTogglesStartAndStopWithoutTouch() {
        var uiState by mutableStateOf(
            VoiceUiState(
                voiceMode = true,
                state = VoiceState.Idle,
                interactionMode = InteractionMode.HoldToTalk,
            ),
        )
        var starts = 0
        var stops = 0
        compose.mainClock.autoAdvance = false

        compose.setContent {
            MaterialTheme {
                VoiceModeOverlay(
                    uiState = uiState,
                    onMicTap = {
                        starts += 1
                        uiState = uiState.copy(state = VoiceState.Listening)
                    },
                    onMicRelease = {
                        stops += 1
                        uiState = uiState.copy(state = VoiceState.Idle)
                    },
                    onInterrupt = {},
                    onDismiss = {},
                    onModeChange = {},
                    onClearError = {},
                )
            }
        }

        compose.onNodeWithTag(VOICE_MODE_MIC_TEST_TAG)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(0, stops)
            assertEquals(VoiceState.Listening, uiState.state)
        }

        compose.onNodeWithTag(VOICE_MODE_MIC_TEST_TAG)
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, stops)
            assertEquals(VoiceState.Idle, uiState.state)
        }
    }

    @Test
    fun focusMode_holdSemanticActionStopsCaptureStillWaitingForMicrophone() {
        val uiState = VoiceUiState(
            voiceMode = true,
            state = VoiceState.Idle,
            interactionMode = InteractionMode.HoldToTalk,
        )
        var starts = 0
        var stops = 0
        compose.mainClock.autoAdvance = false

        compose.setContent {
            MaterialTheme {
                VoiceModeOverlay(
                    uiState = uiState,
                    onMicTap = { starts += 1 },
                    onMicRelease = { stops += 1 },
                    onInterrupt = {},
                    onDismiss = {},
                    onModeChange = {},
                    onClearError = {},
                )
            }
        }

        val mic = compose.onNodeWithTag(VOICE_MODE_MIC_TEST_TAG)
            .assertHasClickAction()
            .assertContentDescriptionEquals("start listening")
        mic.performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(0, stops)
        }
        mic.assertContentDescriptionEquals("stop listening")

        mic.performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, stops)
        }
        mic.assertContentDescriptionEquals("start listening")
    }

    @Test
    fun focusMode_holdKeyboardEnterAndSpaceToggleStartAndStop() {
        var uiState by mutableStateOf(
            VoiceUiState(
                voiceMode = true,
                state = VoiceState.Idle,
                interactionMode = InteractionMode.HoldToTalk,
            ),
        )
        var starts = 0
        var stops = 0
        compose.mainClock.autoAdvance = false

        compose.setContent {
            MaterialTheme {
                VoiceModeOverlay(
                    uiState = uiState,
                    onMicTap = {
                        starts += 1
                        uiState = uiState.copy(state = VoiceState.Listening)
                    },
                    onMicRelease = {
                        stops += 1
                        uiState = uiState.copy(state = VoiceState.Idle)
                    },
                    onInterrupt = {},
                    onDismiss = {},
                    onModeChange = {},
                    onClearError = {},
                )
            }
        }

        val mic = compose.onNodeWithTag(VOICE_MODE_MIC_TEST_TAG)
        mic.performSemanticsAction(SemanticsActions.RequestFocus)
        mic.performKeyInput { pressKey(Key.Enter) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(0, stops)
        }

        mic.performKeyInput { pressKey(Key.Spacebar) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, stops)
        }
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
