package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownContentTest {
    @Test
    fun liveContent_discardsOnlyLeadingBlankLines() {
        assertEquals(
            "First line\n\nSecond line",
            "\n\r\n  \t\nFirst line\n\nSecond line".withoutLeadingBlankLines(),
        )
        assertEquals(
            "    indented code",
            "\n\n    indented code".withoutLeadingBlankLines(),
        )
    }

    @Test
    fun appendPlan_emitsOnlyTheNewSuffix() {
        assertEquals(
            StreamingMarkdownAppendPlan(resetRequired = false, delta = "Hello"),
            planStreamingMarkdownAppend(renderedContent = "", nextContent = "Hello"),
        )
        assertEquals(
            StreamingMarkdownAppendPlan(resetRequired = false, delta = " world"),
            planStreamingMarkdownAppend(renderedContent = "Hello", nextContent = "Hello world"),
        )
        assertEquals(
            StreamingMarkdownAppendPlan(resetRequired = false, delta = ""),
            planStreamingMarkdownAppend(renderedContent = "Hello", nextContent = "Hello"),
        )
    }

    @Test
    fun arbitraryChunkBoundaries_reconstructTheExactMarkdownStream() {
        val source = "Intro **bold**.\n\n- one\n- two\n\n\u0060\u0060\u0060kotlin\nval answer = 42\n\u0060\u0060\u0060"
        var rendered = ""

        source.indices.forEach { index ->
            val snapshot = source.take(index + 1)
            val plan = planStreamingMarkdownAppend(rendered, snapshot)
            assertFalse(plan.resetRequired)
            rendered += plan.delta
            assertEquals(snapshot, rendered)
        }
    }

    @Test
    fun nonAppendReconciliation_requestsOneFreshStreamingGeneration() {
        val plan = planStreamingMarkdownAppend(
            renderedContent = "partial server draft",
            nextContent = "authoritative final response",
        )

        assertTrue(plan.resetRequired)
        assertEquals("", plan.delta)
        assertEquals(
            StreamingMarkdownAppendPlan(
                resetRequired = false,
                delta = "authoritative final response",
            ),
            planStreamingMarkdownAppend("", "authoritative final response"),
        )
    }

    @Test
    fun oversizedMarkdownKeepsDataOutsideTheBoundedRenderWindow() {
        val source = "x".repeat(MAX_RENDERED_MARKDOWN_CHARS + 1)

        val rendered = markdownRenderWindow(source)

        assertEquals(MAX_RENDERED_MARKDOWN_CHARS, rendered.takeWhile { it == 'x' }.length)
        assertFalse(rendered.startsWith(source))
        assertTrue(rendered.contains("shortened for Android memory safety"))
    }
}
