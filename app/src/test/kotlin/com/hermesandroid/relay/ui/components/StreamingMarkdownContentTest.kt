package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownContentTest {
    @Test
    fun liveText_discardsOnlyLeadingBlankLines() {
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
    fun `paragraph list table and link promote only at safe boundaries`() {
        val content = """
            A [link](https://example.com).

            - first
            - second

            | Name | Value |
            | --- | --- |
            | one | two |

            unfinished **tail
        """.trimIndent()

        val partition = partitionStreamingMarkdown(content, finalizeTail = false)

        assertEquals(3, partition.blocks.size)
        assertEquals("A [link](https://example.com).\n\n", partition.blocks[0].content)
        assertEquals("- first\n- second\n\n", partition.blocks[1].content)
        assertEquals(
            "| Name | Value |\n| --- | --- |\n| one | two |\n\n",
            partition.blocks[2].content,
        )
        assertEquals("unfinished **tail", partition.tail?.content)
    }

    @Test
    fun `fenced code waits for a complete matching close line`() {
        val incomplete = partitionStreamingMarkdown(
            "```kotlin\nval answer = 42\n``",
            finalizeTail = false,
        )
        assertTrue(incomplete.blocks.isEmpty())
        assertEquals("```kotlin\nval answer = 42\n``", incomplete.tail?.content)

        val completed = partitionStreamingMarkdown(
            "```kotlin\nval answer = 42\n```\nnext",
            finalizeTail = false,
        )
        assertEquals(1, completed.blocks.size)
        assertEquals("```kotlin\nval answer = 42\n```\n", completed.blocks.single().content)
        assertEquals("next", completed.tail?.content)
    }

    @Test
    fun `tilde fence ignores shorter and mismatched delimiters`() {
        val partition = partitionStreamingMarkdown(
            "~~~~\nbody\n```\n~~~\n~~~~\n",
            finalizeTail = false,
        )

        assertEquals(1, partition.blocks.size)
        assertEquals("~~~~\nbody\n```\n~~~\n~~~~\n", partition.blocks.single().content)
        assertNull(partition.tail)
    }

    @Test
    fun `every chunk boundary keeps promoted blocks immutable`() {
        val content = "Intro with **bold**.\n\n- one\n- two\n\n```text\nfence\n```\nTail"
        var previousBlocks = emptyList<StreamingMarkdownBlock>()

        for (end in 1..content.length) {
            val partition = partitionStreamingMarkdown(content.take(end), finalizeTail = false)
            assertEquals(previousBlocks, partition.blocks.take(previousBlocks.size))
            previousBlocks = partition.blocks
        }
    }

    @Test
    fun `finalization promotes only the remaining tail`() {
        val content = "Stable paragraph.\n\nFinal *paragraph*."
        val live = partitionStreamingMarkdown(content, finalizeTail = false)
        val settled = partitionStreamingMarkdown(content, finalizeTail = true)

        assertEquals(live.blocks, settled.blocks.take(live.blocks.size))
        assertEquals(live.tail, settled.blocks.last())
        assertNull(settled.tail)
    }

    @Test
    fun `incremental state rescans only unfinished suffix`() {
        val stablePrefix = "x".repeat(40_000) + "\n\n"
        val state = StreamingMarkdownState()
        val first = state.update(stablePrefix + "tail", finalizeTail = false)
        val updated = state.update(stablePrefix + "tail grows", finalizeTail = false)

        assertEquals(first.blocks, updated.blocks)
        assertEquals(stablePrefix.length, state.lastScanStart)
        assertEquals("tail grows", updated.tail?.content)

        val finalized = state.update(stablePrefix + "tail grows", finalizeTail = true)
        assertEquals(stablePrefix.length, state.lastScanStart)
        assertEquals(updated.blocks, finalized.blocks.take(updated.blocks.size))
        assertNull(finalized.tail)
    }

    @Test
    fun `document scoped reference link waits and finalizes with its definition`() {
        val content = "See [the guide][guide].\n\n[guide]: https://example.com/guide"

        val live = partitionStreamingMarkdown(content, finalizeTail = false)
        val finalized = partitionStreamingMarkdown(content, finalizeTail = true)

        assertTrue(live.blocks.isEmpty())
        assertEquals(content, live.tail?.content)
        assertEquals(listOf(StreamingMarkdownBlock(0, content)), finalized.blocks)
        assertNull(finalized.tail)
    }

    @Test
    fun `inline link remains independently promotable`() {
        val content = "See [the guide](https://example.com/guide).\n\nTail"

        val live = partitionStreamingMarkdown(content, finalizeTail = false)

        assertEquals(1, live.blocks.size)
        assertEquals("See [the guide](https://example.com/guide).\n\n", live.blocks.single().content)
        assertEquals("Tail", live.tail?.content)
    }
}
