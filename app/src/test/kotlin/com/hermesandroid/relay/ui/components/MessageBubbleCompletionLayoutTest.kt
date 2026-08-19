package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class MessageBubbleCompletionLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `empty streaming reply exposes one stable working status until text arrives`() {
        val content = mutableStateOf("")
        val message = ChatMessage(
            id = "assistant-working",
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true,
            timestamp = 1_700_000_000_000L,
        )

        compose.setContent {
            MaterialTheme {
                MessageBubble(message = message.copy(content = content.value))
            }
        }

        compose.onNodeWithContentDescription("assistant message: Still working…")
            .assertExists()
            .assertHasNoClickAction()

        compose.runOnIdle { content.value = "The first answer token" }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("assistant message: Still working…").assertDoesNotExist()
        compose.onNodeWithText("The first answer token").assertExists()
    }

    @Test
    fun `stream recovery uses an honest reconnecting status`() {
        compose.setContent {
            MaterialTheme {
                MessageBubble(
                    message = ChatMessage(
                        id = "assistant-recovering",
                        role = MessageRole.ASSISTANT,
                        content = "",
                        isStreaming = true,
                        timestamp = 1_700_000_000_000L,
                    ),
                    recoveringAnswer = true,
                )
            }
        }

        compose.onNodeWithContentDescription(
            "assistant message: Reconnecting to your answer…",
        ).assertExists()
        compose.onNodeWithContentDescription("assistant message: Still working…").assertDoesNotExist()
    }

    @Test
    fun `completion keeps the incremental renderer height stable`() {
        val streaming = mutableStateOf(true)
        val message = ChatMessage(
            id = "assistant-live",
            role = MessageRole.ASSISTANT,
            content = "A reply whose final text keeps the live renderer.",
            timestamp = 1_700_000_000_000L,
        )

        compose.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message.copy(
                        isStreaming = streaming.value,
                        inputTokens = if (streaming.value) null else 120,
                        outputTokens = if (streaming.value) null else 42,
                    ),
                    onSpeakMessage = {},
                    modifier = Modifier.testTag("retained-live-tail"),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("↑120 ↓42 tokens").assertDoesNotExist()
        val streamingHeight = compose.onNodeWithTag("retained-live-tail")
            .fetchSemanticsNode().boundsInRoot.height

        compose.runOnIdle { streaming.value = false }
        compose.waitForIdle()
        val completedHeight = compose.onNodeWithTag("retained-live-tail")
            .fetchSemanticsNode().boundsInRoot.height

        compose.onNodeWithText("↑120 ↓42 tokens").assertExists()
        assertEquals(streamingHeight, completedHeight, 0.01f)
    }

    @Test
    fun `streaming markdown remains rendered across completion`() {
        val streaming = mutableStateOf(true)
        val message = ChatMessage(
            id = "assistant-live",
            role = MessageRole.ASSISTANT,
            content = "**bold**",
            timestamp = 1_700_000_000_000L,
        )

        compose.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message.copy(isStreaming = streaming.value),
                    modifier = Modifier.testTag("completion-tail"),
                )
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("**bold**").assertDoesNotExist()
        compose.runOnIdle {
            streaming.value = false
        }
        compose.waitForIdle()

        compose.onNodeWithText("**bold**").assertDoesNotExist()
        compose.onNodeWithTag("completion-tail").assertExists()
    }

    @Test
    fun `stable and provisional inline syntax share one renderer`() {
        val content = mutableStateOf("**bold**\n\nunfinished *tail")
        val streaming = mutableStateOf(true)
        val message = ChatMessage(
            id = "assistant-segmented-live",
            role = MessageRole.ASSISTANT,
            content = "",
            timestamp = 1_700_000_000_000L,
        )

        compose.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message.copy(
                        content = content.value,
                        isStreaming = streaming.value,
                    ),
                    modifier = Modifier.testTag("segmented-live-tail"),
                )
            }
        }

        compose.onNodeWithText("**bold**").assertDoesNotExist()
        compose.onNodeWithText("unfinished *tail").assertExists()

        compose.runOnIdle {
            content.value = "**bold**\n\nunfinished *tail*"
        }
        compose.waitForIdle()

        compose.onNodeWithText("unfinished *tail*").assertDoesNotExist()
        compose.runOnIdle { streaming.value = false }
        compose.waitForIdle()
        compose.onNodeWithText("unfinished *tail*").assertDoesNotExist()
        compose.onNodeWithTag("segmented-live-tail").assertExists()
    }

    @Test
    fun `native streaming renderer keeps provisional structures formatted`() {
        val content = mutableStateOf(
            "- first\n- second\n\n" +
                "| Name | Value |\n| --- | --- |\n| one | two |\n\n" +
                "```kotlin\nval answer =",
        )
        val message = ChatMessage(
            id = "assistant-native-stream",
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true,
            timestamp = 1_700_000_000_000L,
        )

        compose.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message.copy(content = content.value),
                    modifier = Modifier.testTag("native-streaming-markdown"),
                )
            }
        }

        compose.waitForIdle()
        compose.onNodeWithText("first").assertExists()
        compose.onNodeWithText("val answer =").assertExists()
        compose.onNodeWithText(content.value).assertDoesNotExist()

        compose.runOnIdle { content.value += " 42\n```" }
        compose.waitForIdle()

        compose.onNodeWithText(content.value).assertDoesNotExist()
        compose.onNodeWithTag("native-streaming-markdown").assertExists()
    }
}
