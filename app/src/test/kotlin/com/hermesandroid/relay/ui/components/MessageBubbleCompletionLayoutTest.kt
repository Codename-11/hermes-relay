package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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

        compose.onNodeWithContentDescription("assistant message: Still working…").assertExists()

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
    fun `completion does not resize a retained live tail`() {
        val streaming = mutableStateOf(true)
        val message = ChatMessage(
            id = "assistant-live",
            role = MessageRole.ASSISTANT,
            content = "A reply whose final text keeps the live renderer.",
            timestamp = 1_700_000_000_000L,
        )

        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message.copy(isStreaming = streaming.value),
                    retainStreamingLayout = true,
                    modifier = Modifier.testTag("retained-live-tail"),
                )
            }
        }
        compose.waitForIdle()
        val streamingHeight = compose.onNodeWithTag("retained-live-tail")
            .fetchSemanticsNode().boundsInRoot.height

        compose.runOnIdle { streaming.value = false }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        val completedHeight = compose.onNodeWithTag("retained-live-tail")
            .fetchSemanticsNode().boundsInRoot.height

        assertEquals(streamingHeight, completedHeight, 0.01f)
    }

    @Test
    fun `releasing a completed live tail renders markdown without navigation`() {
        val streaming = mutableStateOf(true)
        val retainStreamingLayout = mutableStateOf(true)
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
                    retainStreamingLayout = retainStreamingLayout.value,
                    modifier = Modifier.testTag("completion-tail"),
                )
            }
        }

        compose.onNodeWithText("**bold**").assertExists()
        compose.runOnIdle {
            streaming.value = false
            retainStreamingLayout.value = false
        }
        compose.waitForIdle()

        compose.onNodeWithText("**bold**").assertDoesNotExist()
        compose.onNodeWithTag("completion-tail").assertExists()
    }
}
