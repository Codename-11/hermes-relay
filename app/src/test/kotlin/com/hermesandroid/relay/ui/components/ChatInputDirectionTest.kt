package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class ChatInputDirectionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun typingArabicAndEnglishAlignsWithTheirDirection() {
        var value by mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                ChatInputBar(
                    value = value, onValueChange = { value = it }, placeholder = "Message",
                    trailing = ChatInputTrailing.SEND, onSend = {}, onVoice = {}, onStop = {},
                    onAttachPhotos = {}, onAttachFiles = {}, onAttachCamera = {}, onPasteImage = {},
                    onLongPressAttach = {}, charLimit = 4000, caption = null, voiceReady = true,
                    showVoiceHint = false, onVoiceHintShown = {}, isDarkTheme = false,
                )
            }
        }
        val field = compose.onNodeWithTag(CHAT_INPUT_FIELD_TEST_TAG)
        field.performClick().performTextInput("مرحبا بالعالم")
        assertLayout(ResolvedTextDirection.Rtl)
        field.performTextReplacement("Hello world")
        assertLayout(ResolvedTextDirection.Ltr)
    }

    private fun assertLayout(direction: ResolvedTextDirection) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(CHAT_INPUT_FIELD_TEST_TAG)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val fieldWidth = compose.onNodeWithTag(CHAT_INPUT_FIELD_TEST_TAG).fetchSemanticsNode().size.width
        assertEquals(direction, layout.getParagraphDirection(0))
        if (direction == ResolvedTextDirection.Rtl) {
            assertEquals("Arabic text reaches the editor's right edge", fieldWidth.toFloat(), layout.getLineRight(0), 1f)
        } else {
            assertEquals(0f, layout.getLineLeft(0), 1f)
        }
    }
}
