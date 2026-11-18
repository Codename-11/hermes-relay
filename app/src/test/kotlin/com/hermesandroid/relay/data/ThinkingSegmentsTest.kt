package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ordering rules for the live reasoning split: one block per stretch of
 * thinking, with a tool boundary sealing the block it interrupted.
 */
class ThinkingSegmentsTest {

    @Test
    fun firstDeltaOpensTheInitialBlock() {
        assertEquals(listOf("Looking"), appendThinkingDelta(emptyList(), "Looking"))
    }

    @Test
    fun deltasAccumulateIntoTheOpenBlock() {
        val once = appendThinkingDelta(emptyList(), "Looking")
        val twice = appendThinkingDelta(once, " at the file")

        assertEquals(listOf("Looking at the file"), twice)
    }

    @Test
    fun sealingOpensTheNextBlockForFollowingDeltas() {
        val sealed = sealThinkingSegment(listOf("first"))
        val next = appendThinkingDelta(sealed, "second")

        assertEquals(listOf("first", "second"), next)
    }

    @Test
    fun sealIsIdempotent() {
        val sealed = sealThinkingSegment(listOf("first"))

        assertEquals(listOf("first", ""), sealThinkingSegment(sealed))
    }

    @Test
    fun sealingWithNoThinkingLeavesTheListAlone() {
        assertEquals(emptyList<String>(), sealThinkingSegment(emptyList()))
    }

    @Test
    fun toolSegmentIndexIsReadBeforeTheSeal() {
        assertEquals(0, openThinkingSegmentIndex(listOf("first")) ?: -1)
        assertEquals(1, openThinkingSegmentIndex(listOf("first", "second")) ?: -1)
        assertNull(openThinkingSegmentIndex(emptyList()))
    }

    @Test
    fun aSealedTrailingBlockDoesNotCountAsReasoning() {
        // The renderer gates on non-blank entries, so a turn whose last action
        // was a tool call still reads as one reasoning block, not two.
        val segments = sealThinkingSegment(listOf("only"))

        assertEquals(1, segments.count { it.isNotBlank() })
    }
}
