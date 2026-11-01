package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-xhdpi")
class MarkdownDirectionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun mixedParagraphsAndListItemsFollowTheirOwnTextInBothRenderers() {
        val streaming = mutableStateOf(false)
        val content = "# عنوان أول\n\n## عنوان ثان\n\n### عنوان ثالث\n\n#### عنوان رابع\n\n##### عنوان خامس\n\n###### عنوان سادس\n\nعنوان سابع\n========\n\nعنوان ثامن\n--------\n\nمرحبا بالعالم\n\nHello world\n\n- عنصر عربي\n- English item\n\nاستخدم `git status --short` الآن\n\n```text\n./tool --help\n```"
        compose.setContent {
            // Exercise the neutral fallback independently of the device layout direction.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    Column(Modifier.width(300.dp)) {
                        if (streaming.value) {
                            StreamingMarkdownContent(content, Color.Black)
                        } else {
                            MarkdownContent(content, Color.Black)
                        }
                    }
                }
            }
        }

        repeat(2) { pass ->
            if (pass == 1) compose.runOnIdle { streaming.value = true }
            compose.waitForIdle()
            listOf("عنوان أول", "عنوان ثان", "عنوان ثالث", "عنوان رابع", "عنوان خامس", "عنوان سادس", "عنوان سابع", "عنوان ثامن").forEach {
                assertDirection(it, ResolvedTextDirection.Rtl)
                assertEquals("heading fills the bubble: $it", with(compose.density) { 300.dp.roundToPx() }, textLayout(it).size.width)
            }
            assertDirection("./tool --help", ResolvedTextDirection.Ltr)
            val inline = textLayout("git status --short", substring = true)
            val inlineText = inline.layoutInput.text.text
            val start = inlineText.indexOf("git status --short")
            assertTrue("inline code remains between LTR isolation controls", start > 0 &&
                inlineText[start - 1] == '\u2066' && inlineText[start + "git status --short".length] == '\u2069')
            assertEquals(ResolvedTextDirection.Ltr, inline.getBidiRunDirection(start))
            assertEquals(ResolvedTextDirection.Ltr, inline.getBidiRunDirection(start + "git status --short".length - 1))
            assertDirection("مرحبا بالعالم", ResolvedTextDirection.Rtl)
            assertDirection("Hello world", ResolvedTextDirection.Ltr)
            assertDirection("عنصر عربي", ResolvedTextDirection.Rtl)
            assertDirection("English item", ResolvedTextDirection.Ltr)
        }
    }

    @Test
    fun inlineCodeAtStartDeterminesParagraphAndListDirection() {
        val streaming = mutableStateOf(false)
        val content = "`git pull` لتحديث الفرع\n\n# `git status` حالة\n\n- `git pull` لتحديث الفرع\n- مرحبا `git status`"
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(300.dp)) {
                    if (streaming.value) StreamingMarkdownContent(content, Color.Black)
                    else MarkdownContent(content, Color.Black)
                }
            }
        }
        repeat(2) { pass ->
            if (pass == 1) compose.runOnIdle { streaming.value = true }
            listOf("git pull", "git status").forEach { code ->
                compose.waitUntil(timeoutMillis = 10_000) {
                    compose.onAllNodesWithText(code, substring = true, useUnmergedTree = true)
                        .fetchSemanticsNodes().size >= 2
                }
            }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onAllNodesWithText("git pull", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes().indices.forEach { index ->
                    compose.onAllNodesWithText("git pull", substring = true, useUnmergedTree = true)[index]
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                }
            assertEquals(2, layouts.size)
            layouts.forEach { layout ->
                assertEquals(ResolvedTextDirection.Ltr, layout.getParagraphDirection(0))
                assertEquals(0f, layout.getLineLeft(0), 1f)
            }
            val heading = textLayout("حالة", substring = true)
            assertEquals(ResolvedTextDirection.Ltr, heading.getParagraphDirection(0))
        }
    }

    private fun assertDirection(text: String, direction: ResolvedTextDirection) {
        val layout = textLayout(text)
        assertEquals(text, direction, layout.getParagraphDirection(0))
        if (direction == ResolvedTextDirection.Rtl) {
            assertEquals(text, layout.size.width.toFloat(), layout.getLineRight(0), 1f)
        } else {
            assertEquals(text, 0f, layout.getLineLeft(0), 1f)
        }
    }
    private fun textLayout(text: String, substring: Boolean = false): TextLayoutResult {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, substring = substring, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

}
