package com.hermesandroid.relay.ui.components

import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeMarkdownHighlightedCodeTest {
    @Test
    fun malformedMultilineComment_ignoresReversedHighlightRange() {
        val code = "*/path/*"
        val unsafeHighlights = builder()
            .code(code)
            .build()
            .getHighlights()

        assertTrue(
            "The dependency reproducer must contain a reversed range",
            unsafeHighlights.any { it.location.start > it.location.end },
        )

        val annotated = buildSafeHighlightedAnnotatedString(
            code = code,
            language = null,
            highlightsBuilder = builder(),
        )

        assertEquals(code, annotated.text)
        assertTrue(annotated.spanStyles.all { it.start in 0..it.end && it.end <= code.length })
    }

    @Test
    fun validCode_keepsSyntaxHighlighting() {
        val code = "val answer = 42"
        val annotated = buildSafeHighlightedAnnotatedString(
            code = code,
            language = "kotlin",
            highlightsBuilder = builder(),
        )

        assertEquals(code, annotated.text)
        assertTrue(annotated.spanStyles.isNotEmpty())
        assertTrue(annotated.spanStyles.all { it.start in 0 until it.end && it.end <= code.length })
    }

    private fun builder(): Highlights.Builder =
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = true))
}
