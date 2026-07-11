package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownStreamingParserTest {

    @Test
    fun activeParagraph_remainsRawUntilAStableBlockBoundary() {
        assertEquals(
            listOf(StreamingMarkdownBlock.Text("A paragraph still arriving")),
            parseStreamingMarkdownBlocks("A paragraph still arriving"),
        )

        // A single newline is a Markdown soft break, not a stable block split.
        assertEquals(
            listOf(StreamingMarkdownBlock.Text("line one\nline two")),
            parseStreamingMarkdownBlocks("line one\nline two"),
        )
    }

    @Test
    fun blankLine_promotesSettledPrefixToRealMarkdown() {
        assertEquals(
            listOf(
                StreamingMarkdownBlock.Markdown("## Stable heading"),
                StreamingMarkdownBlock.Text("- one\n- two\n\nTail still arriving"),
            ),
            parseStreamingMarkdownBlocks(
                "## Stable heading\n\n- one\n- two\n\nTail still arriving",
            ),
        )
    }

    @Test
    fun openFence_keepsBlankLinesInsideTheActiveCodeBlock() {
        assertEquals(
            listOf(
                StreamingMarkdownBlock.Markdown("Intro"),
                StreamingMarkdownBlock.Code(
                    language = "kotlin",
                    code = "val first = 1\n\nval second = 2",
                ),
            ),
            parseStreamingMarkdownBlocks(
                "Intro\n\n```kotlin\nval first = 1\n\nval second = 2",
            ),
        )
    }

    @Test
    fun closedFence_staysOnTheStreamingCodeSurfaceUntilFinal() {
        val blocks = parseStreamingMarkdownBlocks(
            "```kotlin\nval answer = 42\n```\n\nNext paragraph",
        )

        assertEquals(2, blocks.size)
        assertEquals(
            StreamingMarkdownBlock.Code(
                language = "kotlin",
                code = "val answer = 42",
            ),
            blocks[0],
        )
        assertEquals(StreamingMarkdownBlock.Text("Next paragraph"), blocks[1])
    }

    @Test
    fun longerFence_isNotClosedByShorterFenceInsideCode() {
        assertEquals(
            listOf(
                StreamingMarkdownBlock.Code(
                    language = "markdown",
                    code = "```\ninside\n```",
                ),
            ),
            parseStreamingMarkdownBlocks(
                "````markdown\n```\ninside\n```\n````",
            ),
        )
    }

    @Test
    fun table_staysRawUntilTheFinalCommonMarkParse() {
        val content = "| Name | Value |\n| --- | --- |\n| Alpha | 1 |\n| Beta |"

        assertEquals(
            listOf(StreamingMarkdownBlock.Text(content)),
            parseStreamingMarkdownBlocks(content),
        )
    }

    @Test
    fun incompleteTableDelimiter_doesNotPrematurelyPromoteTheTable() {
        val content = "| Name | Value |\n| --- | --"

        assertEquals(
            listOf(StreamingMarkdownBlock.Text(content)),
            parseStreamingMarkdownBlocks(content),
        )
    }

    @Test
    fun escapedHeaderPipe_doesNotMakeTheTableLookSettled() {
        val content = "| Name \\| alias | Value |\n| --- | --- |\n| Alpha | 1 |\n| Beta |"

        assertEquals(
            listOf(StreamingMarkdownBlock.Text(content)),
            parseStreamingMarkdownBlocks(content),
        )
    }

    @Test
    fun listAndContinuation_stayTogetherUntilFinal() {
        val content = "- first paragraph\n\n  continuation\n- second"

        assertEquals(
            listOf(StreamingMarkdownBlock.Text(content)),
            parseStreamingMarkdownBlocks(content),
        )
    }

    @Test
    fun lazyBlockQuoteContinuation_isNeverSplitIntoASettledPrefix() {
        val content = "> quoted line\n\nlazy continuation"

        assertEquals(
            listOf(StreamingMarkdownBlock.Text(content)),
            parseStreamingMarkdownBlocks(content),
        )
    }

    @Test
    fun crlfInput_isNormalizedWithoutLeakingCarriageReturns() {
        val blocks = parseStreamingMarkdownBlocks("First\r\n\r\nSecond")

        assertEquals(
            listOf(
                StreamingMarkdownBlock.Markdown("First"),
                StreamingMarkdownBlock.Text("Second"),
            ),
            blocks,
        )
        assertTrue(blocks.none { it.toString().contains('\r') })
    }
}
