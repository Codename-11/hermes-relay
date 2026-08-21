package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.components.avatar.AgentAvatar
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import com.hermesandroid.relay.ui.components.avatar.AvatarSource
import com.hermesandroid.relay.ui.components.avatar.LocalAgentAvatar
import com.hermesandroid.relay.ui.components.avatar.LocalBackgroundVisualizationEnabled
import com.hermesandroid.relay.viewmodel.InteractionMode
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceUiState
import org.junit.Rule
import org.junit.Test

class AmbientVisualizationVisibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun cleanMode_backgroundOff_hidesSphereAndKeepsComposer() {
        composeTestRule.setContent {
            AmbientTestProviders(enabled = false) {
                CleanChatMode(
                    messages = emptyList(),
                    isStreaming = false,
                    sphereState = SphereState.Idle,
                    streamingIntensity = 0f,
                    toolCallBurst = 0f,
                    animationEnabled = true,
                    enabled = true,
                    onSend = {},
                    onExit = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(AMBIENT_RENDERER_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(targetString(R.string.agent_text_send_cd))
            .assertExists()
    }

    @Test
    fun cleanMode_backgroundOn_rendersSphere() {
        composeTestRule.setContent {
            AmbientTestProviders(enabled = true) {
                CleanChatMode(
                    messages = emptyList(),
                    isStreaming = false,
                    sphereState = SphereState.Idle,
                    streamingIntensity = 0f,
                    toolCallBurst = 0f,
                    animationEnabled = false,
                    enabled = true,
                    onSend = {},
                    onExit = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(AMBIENT_RENDERER_TAG).assertExists()
    }

    @Test
    fun voiceMode_backgroundOff_hidesSphereAndKeepsVoiceUi() {
        composeTestRule.setContent {
            AmbientTestProviders(enabled = false) {
                TestVoiceOverlay()
            }
        }

        composeTestRule.onNodeWithTag(AMBIENT_RENDERER_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText(targetString(R.string.voice_overlay_tap_mic)).assertExists()
    }

    @Test
    fun voiceMode_backgroundOn_rendersSphere() {
        composeTestRule.setContent {
            AmbientTestProviders(enabled = true) {
                TestVoiceOverlay()
            }
        }

        composeTestRule.onNodeWithTag(AMBIENT_RENDERER_TAG).assertExists()
    }

    @Composable
    private fun AmbientTestProviders(enabled: Boolean, content: @Composable () -> Unit) {
        MaterialTheme {
            CompositionLocalProvider(
                LocalAgentAvatar provides TaggedAmbientRenderer,
                LocalBackgroundVisualizationEnabled provides enabled,
                content = content,
            )
        }
    }

    @Composable
    private fun TestVoiceOverlay() {
        VoiceModeOverlay(
            uiState = VoiceUiState(
                voiceMode = true,
                state = VoiceState.Idle,
                interactionMode = InteractionMode.TapToTalk,
            ),
            onMicTap = {},
            onMicRelease = {},
            onInterrupt = {},
            onDismiss = {},
            onModeChange = {},
            onClearError = {},
        )
    }

    private fun targetString(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private object TaggedAmbientRenderer : AgentAvatar {
        override val id = "ambient-test"
        override val label = "Ambient test"
        override val description = "Test renderer"
        override val source = AvatarSource.BUILT_IN
        override val reactivity = SphereReactivity()

        @Composable
        override fun Render(state: AvatarRenderState, modifier: Modifier) {
            Box(modifier = modifier.testTag(AMBIENT_RENDERER_TAG))
        }
    }

    private companion object {
        const val AMBIENT_RENDERER_TAG = "ambientVisualizationRenderer"
    }
}
