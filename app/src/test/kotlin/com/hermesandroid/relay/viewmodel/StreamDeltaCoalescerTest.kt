package com.hermesandroid.relay.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamDeltaCoalescerTest {
    @Test
    fun `token burst drains across display paced frames`() = runTest {
        val textBatches = mutableListOf<String>()
        val coalescer = StreamDeltaCoalescer(
            scope = this,
            onTextDelta = textBatches::add,
            onThinkingDelta = {},
        )

        repeat(100) { coalescer.appendText("x") }
        runCurrent()

        assertTrue(textBatches.isEmpty())
        advanceTimeBy(STREAM_DELTA_FRAME_MS - 1)
        runCurrent()
        assertTrue(textBatches.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("x".repeat(streamDeltaFrameBudget(100))), textBatches)
        assertTrue(textBatches.joinToString("").length < 100)

        advanceUntilIdle()
        assertEquals("x".repeat(100), textBatches.joinToString(""))
        assertTrue(textBatches.size > 1)
    }

    @Test
    fun `text and thinking transitions preserve stream order`() = runTest {
        val events = mutableListOf<String>()
        val coalescer = StreamDeltaCoalescer(
            scope = this,
            onTextDelta = { events += "text:$it" },
            onThinkingDelta = { events += "thinking:$it" },
        )

        coalescer.appendThinking("plan ")
        coalescer.appendThinking("first")
        coalescer.appendText("answer ")
        coalescer.appendText("next")
        coalescer.appendThinking("tail")
        coalescer.flushNow()

        assertEquals(
            listOf("thinking:plan first", "text:answer next", "thinking:tail"),
            events,
        )
    }

    @Test
    fun `terminal flush cancels the scheduled duplicate`() = runTest {
        val textBatches = mutableListOf<String>()
        val coalescer = StreamDeltaCoalescer(
            scope = this,
            onTextDelta = textBatches::add,
            onThinkingDelta = {},
        )

        coalescer.appendText("complete before timer")
        coalescer.flushNow()
        advanceUntilIdle()

        assertEquals(listOf("complete before timer"), textBatches)
    }

    @Test
    fun `frame pacing never separates a surrogate pair`() = runTest {
        val textBatches = mutableListOf<String>()
        val coalescer = StreamDeltaCoalescer(
            scope = this,
            onTextDelta = textBatches::add,
            onThinkingDelta = {},
        )

        coalescer.appendText("1234567🚀tail")
        advanceUntilIdle()

        assertEquals("1234567🚀tail", textBatches.joinToString(""))
        assertTrue(textBatches.none { it.endsWith('\uD83D') })
        assertTrue(textBatches.none { it.startsWith('\uDE80') })
    }

    @Test
    fun `discard drops pending content`() = runTest {
        val textBatches = mutableListOf<String>()
        val coalescer = StreamDeltaCoalescer(
            scope = this,
            onTextDelta = textBatches::add,
            onThinkingDelta = {},
        )

        coalescer.appendText("old connection")
        coalescer.discard()
        advanceUntilIdle()

        assertTrue(textBatches.isEmpty())
    }
}
