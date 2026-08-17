package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class ChatInputBarTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `hardware enter submits through the composer action`() {
        var sent = 0
        setComposer(value = "Hello") { sent++ }

        input().performClick().performKeyInput { pressKey(Key.Enter) }

        compose.runOnIdle { assertEquals(1, sent) }
    }

    @Test
    fun `shift enter inserts a newline without submitting`() {
        var value = "Hello"
        var sent = 0
        setComposer(value = value, onValueChange = { value = it }) { sent++ }

        input().performClick().performKeyInput {
            keyDown(Key.ShiftLeft)
            pressKey(Key.Enter)
            keyUp(Key.ShiftLeft)
        }

        compose.runOnIdle {
            assertEquals("Hello\n", value)
            assertEquals(0, sent)
        }
    }

    @Test
    fun `plain enter inserts newline when configured while ctrl enter submits`() {
        var value = "Hello"
        var sent = 0
        setComposer(
            value = value,
            onValueChange = { value = it },
            physicalEnterSends = false,
        ) { sent++ }

        input().performClick().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle {
            assertEquals("Hello\n", value)
            assertEquals(0, sent)
        }

        input().performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.Enter)
            keyUp(Key.CtrlLeft)
        }
        compose.runOnIdle { assertEquals(1, sent) }
    }

    @Test
    fun `hardware submit preserves steer action`() {
        var submitted = 0
        setComposer(value = "Correct this", trailing = ChatInputTrailing.STEER) { submitted++ }

        input().performClick().performKeyInput { pressKey(Key.Enter) }

        compose.runOnIdle { assertEquals(1, submitted) }
    }

    @Test
    fun `steer state is named visibly and not only by color`() {
        setComposer(
            value = "Correct this",
            trailing = ChatInputTrailing.STEER,
            caption = "Sends into the active response",
        )

        compose.onNodeWithText("Correct the response").assertExists()
    }

    @Test
    fun `hardware submit preserves queue action`() {
        var submitted = 0
        setComposer(value = "Follow up", trailing = ChatInputTrailing.QUEUE) { submitted++ }

        input().performClick().performKeyInput { pressKey(Key.Enter) }

        compose.runOnIdle { assertEquals(1, submitted) }
    }

    @Test
    fun `queue state is named visibly and not only by icon`() {
        setComposer(
            value = "Follow up",
            trailing = ChatInputTrailing.QUEUE,
            caption = "Delivers after this turn",
        )

        compose.onNodeWithText("Queue message").assertExists()
    }

    @Test
    fun `arrow keys keep focus and move the text caret`() {
        var value = "abc"
        setComposer(value = value, onValueChange = { value = it })

        input().performClick().performKeyInput { pressKey(Key.DirectionLeft) }
        input().assertIsFocused().performTextInput("X")

        compose.runOnIdle { assertEquals("abXc", value) }
    }

    @Test
    fun `software keyboard exposes newline without submitting`() {
        var value = "Hello"
        var sent = 0
        setComposer(value = value, onValueChange = { value = it }) { sent++ }

        input()
            .assert(hasImeAction(ImeAction.Default))
            .performClick()
            .performTextInput("\nWorld")

        compose.runOnIdle {
            assertEquals("Hello\nWorld", value)
            assertEquals(0, sent)
        }
    }

    private fun setComposer(
        value: String,
        onValueChange: (String) -> Unit = {},
        trailing: ChatInputTrailing = ChatInputTrailing.SEND,
        physicalEnterSends: Boolean = true,
        caption: String? = null,
        onSend: () -> Unit = {},
    ) {
        var currentValue by mutableStateOf(value)
        compose.setContent {
            MaterialTheme {
                ChatInputBar(
                    value = currentValue,
                    onValueChange = {
                        currentValue = it
                        onValueChange(it)
                    },
                    placeholder = "Message",
                    trailing = trailing,
                    onSend = onSend,
                    onVoice = {},
                    onStop = {},
                    onAttachPhotos = {},
                    onAttachFiles = {},
                    onAttachCamera = {},
                    onPasteImage = {},
                    onLongPressAttach = {},
                    charLimit = 4_000,
                    caption = caption,
                    voiceReady = true,
                    showVoiceHint = false,
                    onVoiceHintShown = {},
                    isDarkTheme = false,
                    physicalEnterSends = physicalEnterSends,
                )
            }
        }
    }

    private fun input() = compose.onNodeWithTag(CHAT_INPUT_FIELD_TEST_TAG)
}
