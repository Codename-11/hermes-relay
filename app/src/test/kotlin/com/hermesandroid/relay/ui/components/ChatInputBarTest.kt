package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasImeAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "w360dp-h720dp-xhdpi-qwerty")
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
    @Config(qualifiers = "w360dp-h720dp-xhdpi-qwerty")
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
    @Config(qualifiers = "w360dp-h720dp-xhdpi-qwerty")
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
    @Config(qualifiers = "w360dp-h720dp-xhdpi-qwerty")
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

    @Test
    fun `ime synthesized enter key inserts newline instead of submitting`() {
        var value = "Hello"
        var sent = 0
        setComposer(value = value, onValueChange = { value = it }) { sent++ }

        // Some IMEs dispatch the return key as a synthesized KEYCODE_ENTER
        // key event instead of committing "\n" directly. With no physical
        // keyboard attached the send path must not fire, otherwise the soft
        // keyboard's return key sends the message (issue #367). Inject the
        // event at the view root exactly like the IME's InputConnection
        // key-event path does.
        val imeDown = android.view.KeyEvent(
            -1, 0,
            android.view.KeyEvent.ACTION_DOWN,
            android.view.KeyEvent.KEYCODE_ENTER,
            0, 0,
        )
        val imeUp = android.view.KeyEvent(
            -1, 0,
            android.view.KeyEvent.ACTION_UP,
            android.view.KeyEvent.KEYCODE_ENTER,
            0, 0,
        )

        input().performClick()
        compose.runOnIdle {
            val root = compose.activity.findViewById<android.view.View>(android.R.id.content)
            root.dispatchKeyEvent(imeDown)
            root.dispatchKeyEvent(imeUp)
        }

        compose.runOnIdle {
            assertEquals("Hello\n", value)
            assertEquals(0, sent)
        }
    }

    @Test
    fun `large insertion becomes attachment while surrounding draft stays in composer`() {
        var value = "before  after"
        var pasted: String? = null
        val largePaste = "x".repeat(5_000)
        setComposer(
            value = value,
            onValueChange = { value = it },
            largePasteThreshold = 5_000,
            onLargePaste = { pasted = it },
        )

        input().performClick().performTextReplacement("before $largePaste after")

        compose.runOnIdle {
            assertEquals(largePaste, pasted)
            assertEquals("before  after", value)
        }
    }

    @Test
    fun `large paste conversion can be disabled`() {
        var value = ""
        var conversions = 0
        val largePaste = "x".repeat(5_000)
        setComposer(
            value = value,
            onValueChange = { value = it },
            largePasteThreshold = null,
            onLargePaste = { conversions++ },
            charLimit = 8_000,
        )

        input().performClick().performTextInput(largePaste)

        compose.runOnIdle {
            assertEquals(largePaste, value)
            assertEquals(0, conversions)
        }
    }

    @Test
    fun `send stays disabled while composer content is preparing`() {
        var sent = 0
        setComposer(value = "Ready soon", submitEnabled = false) { sent++ }

        compose.onNodeWithContentDescription("Send message")
            .assertIsNotEnabled()
        input().performClick().performKeyInput { pressKey(Key.Enter) }

        compose.runOnIdle { assertEquals(0, sent) }
    }

    // xhdpi test qualifier: 1dp == 2px, so the 48dp minimum is 96px.
    private val minTouchPx = 96

    @Test
    fun `every trailing affordance exposes an explicit 48dp minimum touch target`() {
        // One parameterized pass over all five ChatInputTrailing states: the
        // trailing slot morphs in place, so each state must keep the same
        // minimum hit area.
        var stateTrailing by mutableStateOf(ChatInputTrailing.SEND)
        compose.setContent { MaterialTheme { bar(trailing = stateTrailing) } }
        for (trailing in ChatInputTrailing.entries) {
            compose.runOnIdle { stateTrailing = trailing }
            compose.waitForIdle()
            compose.onNodeWithTag(CHAT_INPUT_TRAILING_BUTTON_TEST_TAG)
                .assertTouchTargetAtLeast(minTouchPx)
        }
    }

    @Test
    fun `attachment button exposes an explicit 48dp minimum touch target`() {
        setComposer(value = "")
        compose.onNodeWithTag(CHAT_INPUT_ATTACH_BUTTON_TEST_TAG)
            .assertTouchTargetAtLeast(minTouchPx)
    }

    @Test
    fun `voice button exposes an explicit 48dp minimum touch target`() {
        setComposer(value = "")
        compose.onNodeWithTag(CHAT_INPUT_VOICE_BUTTON_TEST_TAG)
            .assertTouchTargetAtLeast(minTouchPx)
    }

    @Test
    fun `trailing touch target stays stable while the draft grows`() {
        // The bar is a single row: a long draft must not stretch the send
        // button, and neither may it push the affordance below 48dp.
        var value by mutableStateOf("short")
        compose.setContent { MaterialTheme { bar(value = value) } }
        compose.waitForIdle()
        val short = compose.onNodeWithTag(CHAT_INPUT_TRAILING_BUTTON_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot

        compose.runOnIdle { value = "x".repeat(2_000) }
        compose.waitForIdle()
        val long = compose.onNodeWithTag(CHAT_INPUT_TRAILING_BUTTON_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(short.width, long.width)
        org.junit.Assert.assertEquals(short.height, long.height)
        org.junit.Assert.assertTrue(long.width >= minTouchPx && long.height >= minTouchPx)
    }

    @Test
    fun `composer affordances keep their accessible names while targets grow`() {
        setComposer(value = "")
        compose.onNodeWithContentDescription("Add attachment; hold for commands").assertExists()
        compose.onNodeWithContentDescription("Start voice conversation").assertExists()
        compose.onNodeWithContentDescription("Send message").assertExists()
    }

    @Test
    fun `voice setup badge stays inside the 48dp voice target`() {
        // The amber "needs setup" dot is decoration: it must not poke outside
        // the touchable box, and it must not announce itself (the button's
        // content description already names the state).
        setComposer(value = "", voiceReady = false)
        compose.runOnIdle {
            val button = compose.onNodeWithTag(CHAT_INPUT_VOICE_BUTTON_TEST_TAG)
                .fetchSemanticsNode().boundsInRoot
            val badge = compose.onNodeWithTag(CHAT_INPUT_VOICE_BADGE_TEST_TAG)
                .fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue(badge.left >= button.left)
            org.junit.Assert.assertTrue(badge.top >= button.top)
            org.junit.Assert.assertTrue(badge.right <= button.right)
            org.junit.Assert.assertTrue(badge.bottom <= button.bottom)
        }
    }

    @Test
    fun `status strip model pill keeps a bounded width and accessible name`() {
        compose.setContent {
            MaterialTheme {
                RelayStatusStrip(
                    leadingBadge = { },
                    routeLabel = "gateway",
                    modelLabel = "anthropic-claude-opus-4-6-20260205-long-horizon-preview",
                    onModelClick = {},
                )
            }
        }
        compose.runOnIdle {
            val pill = compose.onNodeWithContentDescription("Select model")
            pill.assertExists()
            // The label is capped (140dp == 280px at xhdpi), with bounded
            // padding and the affordance icon kept inside the pill.
            org.junit.Assert.assertTrue(
                pill.fetchSemanticsNode().boundsInRoot.width <= 480,
            )
        }
    }

    @Test
    fun `status strip effort pill keeps its own accessible name`() {
        compose.setContent {
            MaterialTheme {
                RelayStatusStrip(
                    leadingBadge = { },
                    routeLabel = "gateway",
                    modelLabel = "model",
                    effortLabel = "high",
                    onModelClick = {},
                    onEffortClick = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Select reasoning effort").assertExists()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTouchTargetAtLeast(minPx: Int) {
        val bounds = fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(
            "touch target ${bounds.width}x${bounds.height}px is below the ${minPx}px minimum",
            bounds.width >= minPx && bounds.height >= minPx,
        )
    }

    /** Renders a bare [ChatInputBar] with defaults; [bar] overrides one state at a time. */
    @Composable
    private fun bar(
        value: String = "",
        trailing: ChatInputTrailing = ChatInputTrailing.SEND,
    ) {
        ChatInputBar(
            value = value,
            onValueChange = {},
            placeholder = "Message",
            trailing = trailing,
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
            isDarkTheme = false,
        )
    }

    private fun setComposer(
        value: String,
        onValueChange: (String) -> Unit = {},
        trailing: ChatInputTrailing = ChatInputTrailing.SEND,
        physicalEnterSends: Boolean = true,
        caption: String? = null,
        largePasteThreshold: Int? = null,
        onLargePaste: (String) -> Unit = {},
        charLimit: Int = 4_000,
        submitEnabled: Boolean = true,
        voiceReady: Boolean = true,
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
                    charLimit = charLimit,
                    caption = caption,
                    voiceReady = voiceReady,
                    showVoiceHint = false,
                    onVoiceHintShown = {},
                    isDarkTheme = false,
                    physicalEnterSends = physicalEnterSends,
                    largePasteThreshold = largePasteThreshold,
                    onLargePaste = onLargePaste,
                    submitEnabled = submitEnabled,
                )
            }
        }
    }

    private fun input() = compose.onNodeWithTag(CHAT_INPUT_FIELD_TEST_TAG)
}
