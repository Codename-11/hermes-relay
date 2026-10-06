package com.hermesandroid.relay.screenshots

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.ui.components.ChatInputBar
import com.hermesandroid.relay.ui.components.ChatInputPickerControl
import com.hermesandroid.relay.ui.components.ChatInputTrailing
import com.hermesandroid.relay.ui.components.MessageBubble
import com.hermesandroid.relay.ui.screens.AppearanceLivePreview
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.ui.theme.LayoutStyle
import com.hermesandroid.relay.ui.theme.LocalBrand
import com.hermesandroid.relay.ui.theme.appearanceShapeScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Visual regression captures for the Clean layout's chat surfaces. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class CleanLayoutScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val user = ChatMessage(
        id = "u1",
        role = MessageRole.USER,
        content = "Nice! Can you write code too?",
        timestamp = 1_700_000_000_000L,
    )
    private val assistant = ChatMessage(
        id = "a1",
        role = MessageRole.ASSISTANT,
        content = "Absolutely. Code blocks render with syntax-aware styling.",
        timestamp = 1_700_000_060_000L,
        agentName = "Hermes",
        badges = listOf("Demo"),
    )

    private fun setChat(themePreference: String) {
        compose.setContent {
            HermesRelayTheme(
                appThemeId = "clean",
                themePreference = themePreference,
                layoutStyleId = LayoutStyle.CLEAN.id,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MessageBubble(message = user)
                    MessageBubble(message = assistant, maxBubbleWidth = 336.dp)
                    ChatInputBar(
                        value = "",
                        onValueChange = {},
                        placeholder = "Message…",
                        trailing = ChatInputTrailing.VOICE,
                        onSend = {},
                        onVoice = {},
                        onStop = {},
                        onAttachPhotos = {},
                        onAttachFiles = {},
                        onAttachCamera = {},
                        onPasteImage = {},
                        onLongPressAttach = {},
                        charLimit = 4_000,
                        caption = null,
                        voiceReady = false,
                        showVoiceHint = false,
                        onVoiceHintShown = {},
                        isDarkTheme = themePreference == "dark",
                        modelControl = ChatInputPickerControl(
                            value = "Server default",
                            contentDescription = "Model",
                            options = emptyList(),
                        ),
                    )
                    ChatInputBar(
                        value = "Draft reply",
                        onValueChange = {},
                        placeholder = "Message…",
                        trailing = ChatInputTrailing.SEND,
                        onSend = {},
                        onVoice = {},
                        onStop = {},
                        onAttachPhotos = {},
                        onAttachFiles = {},
                        onAttachCamera = {},
                        onPasteImage = {},
                        onLongPressAttach = {},
                        charLimit = 4_000,
                        caption = null,
                        voiceReady = true,
                        showVoiceHint = false,
                        onVoiceHintShown = {},
                        isDarkTheme = themePreference == "dark",
                    )
                }
            }
        }
    }

    @Test
    fun `clean chat surfaces in light`() {
        setChat("light")
        compose.onRoot().captureRoboImage("build/ui-regression/clean-chat-light.png")
    }

    @Test
    fun `clean chat surfaces in dark`() {
        setChat("dark")
        compose.onRoot().captureRoboImage("build/ui-regression/clean-chat-dark.png")
    }

    @Test
    fun `clean hides route badges and unusable pickers until needed`() {
        setChat("light")
        compose.onAllNodesWithText("Demo").assertCountEquals(0)
        compose.onAllNodesWithText("Server default").assertCountEquals(0)
        compose.onNodeWithText(assistant.content).performClick()
        compose.onAllNodesWithText("Demo").assertCountEquals(1)
        compose.onRoot().captureRoboImage("build/ui-regression/clean-chat-revealed.png")
    }

    @Test
    fun `appearance preview renders the clean layout`() {
        compose.setContent {
            HermesRelayTheme(appThemeId = "clean", themePreference = "dark", layoutStyleId = LayoutStyle.CLEAN.id) {
                Column(Modifier.background(MaterialTheme.colorScheme.background).padding(14.dp)) {
                    AppearanceLivePreview(
                        palette = LocalBrand.current,
                        shapeScale = appearanceShapeScale(null),
                        layoutStyle = LayoutStyle.CLEAN,
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("build/ui-regression/clean-appearance-preview.png")
    }
}
