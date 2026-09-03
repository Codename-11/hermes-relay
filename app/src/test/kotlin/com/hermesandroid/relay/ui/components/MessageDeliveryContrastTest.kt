package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageDeliveryStatus
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class MessageDeliveryContrastTest {
    @get:Rule val compose = createComposeRule()

    @Test fun darkUserBubblesUseContrastingDeliveryText() = verifyDeliveryColors("dark")
    @Test fun lightUserBubblesUseContrastingDeliveryText() = verifyDeliveryColors("light")

    private fun verifyDeliveryColors(theme: String) {
        var foreground = Color.Unspecified
        var background = Color.Unspecified
        compose.setContent {
            HermesRelayTheme(themePreference = theme) {
                foreground = MaterialTheme.colorScheme.onPrimary
                background = MaterialTheme.colorScheme.primary
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MessageDeliveryStatus.entries.forEach { status ->
                            MessageBubble(
                                message = ChatMessage(
                                    id = status.name,
                                    role = MessageRole.USER,
                                    content = "Test",
                                    timestamp = 1_700_000_000_000L,
                                    deliveryStatus = status,
                                ),
                                animationEnabled = false,
                            )
                        }
                    }
                }
            }
        }

        assertNotEquals(background, foreground)
        listOf("Sending…", "Queued", "Correction sent", "Delivered", "Not sent").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("Delivery label must use the user bubble foreground: $label", foreground, layouts.single().layoutInput.style.color)
        }
        compose.onRoot().captureRoboImage("build/ui-evidence/message-delivery-$theme.png")
    }
}
