package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w600dp-h720dp-xhdpi")
class TranscriptNavigatorTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `search steps through rendered rows by stable ui key and wraps`() {
        val jumps = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                TranscriptSearchNavigator(
                    messages = listOf(
                        message("user-1", MessageRole.USER, "Find Kotlin help"),
                        message("assistant-1", MessageRole.ASSISTANT, "Kotlin is concise"),
                        message("user-2", MessageRole.USER, "More kotlin"),
                    ),
                    onJumpToMessage = jumps::add,
                    onClose = {},
                )
            }
        }

        compose.onNodeWithTag(TranscriptNavigatorTestTags.Query).performTextInput("kotlin")
        compose.onNodeWithTag(TranscriptNavigatorTestTags.ResultCount).assertTextEquals("1 of 3")
        compose.onNodeWithTag(TranscriptNavigatorTestTags.Next).assertIsEnabled().performClick()
        compose.onNodeWithTag(TranscriptNavigatorTestTags.Next).performClick()
        compose.onNodeWithTag(TranscriptNavigatorTestTags.Next).performClick()

        compose.runOnIdle {
            assertEquals(listOf("assistant-1", "user-2", "user-1"), jumps)
        }
    }

    @Test
    fun `turn rail jumps to prompt and exposes prompt and match count`() {
        var jumpedTo: String? = null
        compose.setContent {
            MaterialTheme {
                TranscriptSearchNavigator(
                    messages = listOf(
                        message("prompt-a", MessageRole.USER, "First prompt"),
                        message("answer-a", MessageRole.ASSISTANT, "needle"),
                        message("prompt-b", MessageRole.USER, "Second prompt"),
                    ),
                    onJumpToMessage = { jumpedTo = it },
                    onClose = {},
                )
            }
        }

        compose.onNodeWithTag(TranscriptNavigatorTestTags.Query).performTextInput("needle")
        compose.onNodeWithTag("${TranscriptNavigatorTestTags.TurnPrefix}prompt-a")
            .assert(hasContentDescription("First prompt", substring = true))
            .assert(hasContentDescription("1 match", substring = true))
            .performClick()

        compose.runOnIdle { assertEquals("prompt-a", jumpedTo) }
    }

    @Test
    fun `close is delegated to host`() {
        var closed = false
        compose.setContent {
            MaterialTheme {
                TranscriptSearchNavigator(
                    messages = emptyList(),
                    onJumpToMessage = {},
                    onClose = { closed = true },
                )
            }
        }

        compose.onNodeWithTag(TranscriptNavigatorTestTags.Close).performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test
    fun `model searches text that message bubbles render`() {
        val rich = message("rich", MessageRole.ASSISTANT, "plain").copy(
            thinkingContent = "reasoning needle",
            toolCalls = listOf(
                ToolCall(name = "lookup", args = "topic", result = "tool needle", success = true),
            ),
            attachments = listOf(Attachment("text/plain", "", fileName = "needle.txt")),
        )

        val model = buildTranscriptNavigationModel(listOf(rich), "needle")

        assertEquals(3, model.matches.size)
        assertTrue(model.matches.all { it.messageUiKey == "rich" })
    }

    @Test
    fun `turn model groups assistant matches beneath preceding user prompt`() {
        val messages = listOf(
            message("u1", MessageRole.USER, "Alpha"),
            message("a1", MessageRole.ASSISTANT, "match"),
            message("a2", MessageRole.ASSISTANT, "match again"),
            message("u2", MessageRole.USER, "Beta match"),
            message("a3", MessageRole.ASSISTANT, "done"),
        )

        val model = buildTranscriptNavigationModel(messages, "match", activeMatchIndex = 2)

        assertEquals(listOf(2, 1), model.turns.map { it.matchCount })
        assertFalse(model.turns.first().containsActiveMatch)
        assertTrue(model.turns.last().containsActiveMatch)
        assertEquals(listOf("u1", "u2"), model.turns.map { it.messageUiKey })
    }

    @Test
    fun `matching is case insensitive and preserves repeated occurrences`() {
        val model = buildTranscriptNavigationModel(
            listOf(message("stable-key", MessageRole.USER, "Hermes hermes HERMES")),
            "HeRmEs",
        )

        assertEquals(3, model.matches.size)
        assertEquals(listOf(0, 7, 14), model.matches.map { it.start })
    }

    private fun message(id: String, role: MessageRole, content: String) = ChatMessage(
        id = "server-$id",
        uiKey = id,
        role = role,
        content = content,
        timestamp = 1L,
    )
}
