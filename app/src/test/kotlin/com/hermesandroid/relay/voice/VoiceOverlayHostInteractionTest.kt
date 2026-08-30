package com.hermesandroid.relay.voice

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
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
class VoiceOverlayHostInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun expandedOverlay_holdSemanticActionTogglesStartAndStopWithoutTouch() {
        assertHoldSemanticToggle(VOICE_OVERLAY_MIC_CONTROL_TEST_TAG) { state, start, stop ->
            MicControlButton(
                uiState = state,
                onStartListening = start,
                onStopListening = stop,
                onInterrupt = {},
                onPauseAutoMode = {},
            )
        }
    }

    @Test
    fun minimizedOverlay_holdSemanticActionTogglesStartAndStopWithoutTouch() {
        assertHoldSemanticToggle(VOICE_FLOATING_OVERLAY_MIC_TEST_TAG) { state, start, stop ->
            VoiceFloatingOverlayBubble(
                uiState = state,
                stateText = state.state.name,
                onExpand = {},
                onStartListening = start,
                onStopListening = stop,
                onInterrupt = {},
                onPauseAutoMode = {},
                onDragBy = { _, _ -> },
            )
        }
    }

    private fun assertHoldSemanticToggle(
        tag: String,
        content: @Composable (VoiceUiState, () -> Unit, () -> Unit) -> Unit,
    ) {
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
                content(
                    uiState,
                    {
                        starts += 1
                        uiState = uiState.copy(state = VoiceState.Listening)
                    },
                    {
                        stops += 1
                        uiState = uiState.copy(state = VoiceState.Idle)
                    },
                )
            }
        }

        compose.onNodeWithTag(tag)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(0, stops)
            assertEquals(VoiceState.Listening, uiState.state)
        }

        compose.onNodeWithTag(tag)
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, stops)
            assertEquals(VoiceState.Idle, uiState.state)
        }
    }
}
