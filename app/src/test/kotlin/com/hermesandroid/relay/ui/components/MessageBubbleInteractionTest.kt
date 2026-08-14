package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.ChatQuoteReference
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.MessageReaction
import com.hermesandroid.relay.data.buildChatQuotedPrompt
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [27], qualifiers = "w360dp-h720dp-xhdpi")
class MessageBubbleInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun firstAssistantIdentityDoesNotNarrowMessageContent() {
        val content = "A deliberately long assistant response that must use the full compact-phone bubble width."
        val message = ChatMessage(
            id = "assistant-with-identity",
            role = MessageRole.ASSISTANT,
            content = content,
            timestamp = 1_700_000_000_000L,
            agentName = "Hermes",
        )

        compose.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message,
                    maxBubbleWidth = 340.dp,
                    isFirstInGroup = true,
                )
            }
        }

        compose.onNodeWithContentDescription("assistant message: $content")
            .assertWidthIsEqualTo(340.dp)
    }

    @Test
    fun tapRevealsAccessibleActionsAndInvokesExistingCallbacks() {
        val invocations = mutableListOf<String>()
        val message = ChatMessage(
            id = "assistant-actions",
            role = MessageRole.ASSISTANT,
            content = "A completed response.",
            timestamp = 1_700_000_000_000L,
        )

        compose.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message,
                    onCopyMessage = { invocations += "copy:$it" },
                    onQuoteMessage = { invocations += "quote:${it.content}" },
                    onSpeakMessage = { invocations += "speak:$it" },
                )
            }
        }

        compose.onNodeWithContentDescription("assistant message: ${message.content}").performClick()
        val copyAction = compose.onNodeWithContentDescription("Copy")
            .assertIsDisplayed()
            .assertHasClickAction()
        compose.onNodeWithContentDescription("Quote in reply").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithContentDescription("Speak response").assertIsDisplayed().assertHasClickAction()
        copyAction.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)

        compose.onNodeWithContentDescription("Quote in reply").performClick()

        assertEquals(listOf("quote:${message.content}"), invocations)
        compose.onNodeWithContentDescription("Copy").assertDoesNotExist()
    }

    @Test
    fun longPressStillOpensTheExistingActionMenu() {
        val message = ChatMessage(
            id = "assistant-long-press",
            role = MessageRole.ASSISTANT,
            content = "Long press still works.",
            timestamp = 1_700_000_000_000L,
        )

        compose.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message,
                    onQuoteMessage = {},
                )
            }
        }

        compose.onNodeWithContentDescription("assistant message: ${message.content}")
            .performTouchInput { longClick() }

        compose.onNodeWithText("Copy").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Quote in reply").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun reactionsStayOutsideBubbleAndOpenAsFloatingTapbacks() {
        val reactions = mutableListOf<String?>()
        val message = ChatMessage(
            id = "assistant-reactions",
            role = MessageRole.ASSISTANT,
            content = "React to this response.",
            timestamp = 1_700_000_000_000L,
        )

        compose.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message,
                    onReact = { reactions += it },
                )
            }
        }

        compose.onNodeWithContentDescription("React with 👍").assertDoesNotExist()
        compose.onNodeWithText("Remove reaction").assertDoesNotExist()

        compose.onNodeWithContentDescription("assistant message: ${message.content}")
            .performTouchInput { longClick() }

        compose.onNodeWithContentDescription("React with 👍")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        compose.runOnIdle { assertEquals(listOf("👍"), reactions) }
        compose.onNodeWithContentDescription("React with 👍").assertDoesNotExist()
    }

    @Test
    fun landedReactionStaysPinnedToTheBubbleAndReopensPicker() {
        val message = ChatMessage(
            id = "assistant-landed-reaction",
            role = MessageRole.ASSISTANT,
            content = "A reacted response.",
            timestamp = 1_700_000_000_000L,
            reactions = listOf(MessageReaction("❤️", "user", 1_700_000_000.0)),
        )

        compose.setContent {
            MaterialTheme {
                MessageBubble(message = message, onReact = {})
            }
        }

        compose.onNodeWithContentDescription("Reactions: ❤️")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        compose.onNodeWithContentDescription("React with ❤️").assertIsDisplayed()
        compose.onNodeWithText("Remove reaction").assertIsDisplayed()
    }

    @Test
    fun quotedReplyRendersStructuredReferenceAndKeepsMarkupOutOfActions() {
        var quotedContent: String? = null
        val message = ChatMessage(
            id = "quoted-reply",
            role = MessageRole.USER,
            content = buildChatQuotedPrompt(
                body = "This is my reply.",
                reference = ChatQuoteReference(
                    messageId = "original-message",
                    authorLabel = "Hermes",
                    excerpt = "The original answer.",
                ),
            ),
            timestamp = 1_700_000_000_000L,
        )

        compose.setContent {
            MaterialTheme {
                MessageBubble(
                    message = message,
                    onQuoteMessage = { quotedContent = it.content },
                )
            }
        }

        compose.onNodeWithText("@Hermes").assertIsDisplayed()
        compose.onNodeWithText("The original answer.").assertIsDisplayed()
        compose.onNodeWithText("This is my reply.").assertIsDisplayed()
        compose.onNodeWithText("Replying to", substring = true).assertDoesNotExist()

        compose.onNodeWithContentDescription("user message: This is my reply.").performClick()
        compose.onNodeWithContentDescription("Quote in reply").performClick()
        compose.runOnIdle { assertEquals("This is my reply.", quotedContent) }
    }
}
