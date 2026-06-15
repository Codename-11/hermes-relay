package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BalancedRealtimeTtsCoalescerTest {

    @Test
    fun `normal speech waits until balanced first chunk target`() {
        val coalescer = BalancedRealtimeTtsCoalescer(
            firstChunkTargetChars = 90,
            followupChunkTargetChars = 140,
            maxChunkChars = 220,
        )

        assertTrue(coalescer.append("Test two, coming through.").isEmpty())

        val out = coalescer.append(
            "This is a longer sentence to check pacing, pauses, and voice stability.",
        )

        assertEquals(
            listOf(
                "Test two, coming through. " +
                    "This is a longer sentence to check pacing, pauses, and voice stability.",
            ),
            out,
        )
    }

    @Test
    fun `stream completion flushes trailing short speech`() {
        val coalescer = BalancedRealtimeTtsCoalescer(
            firstChunkTargetChars = 90,
            followupChunkTargetChars = 140,
            maxChunkChars = 220,
        )

        assertTrue(coalescer.append("Okay.").isEmpty())

        assertEquals(listOf("Okay."), coalescer.flush())
    }

    @Test
    fun `immediate status flushes pending prose before status`() {
        val coalescer = BalancedRealtimeTtsCoalescer(
            firstChunkTargetChars = 120,
            followupChunkTargetChars = 160,
            maxChunkChars = 220,
        )

        assertTrue(coalescer.append("I can check that from the relay logs.").isEmpty())

        assertEquals(
            listOf(
                "I can check that from the relay logs.",
                "I'm checking the relay logs.",
            ),
            coalescer.enqueueImmediate("I'm checking the relay logs."),
        )
    }

    @Test
    fun `long prose splits at a natural boundary before max chars`() {
        val coalescer = BalancedRealtimeTtsCoalescer(
            firstChunkTargetChars = 80,
            followupChunkTargetChars = 120,
            maxChunkChars = 130,
        )
        val text = "This first sentence is intentionally long enough to trigger a render. " +
            "This second sentence should remain queued for a later flush instead of forcing " +
            "one oversized provider render."

        val first = coalescer.append(text)

        assertEquals(1, first.size)
        assertTrue(first.single().length <= 130)
        assertTrue(first.single().endsWith("."))

        val rest = coalescer.flush()
        assertEquals(1, rest.size)
        assertTrue(rest.single().startsWith("This second sentence"))
    }

    @Test
    fun `clear drops pending speech and resets first chunk behavior`() {
        val coalescer = BalancedRealtimeTtsCoalescer(
            firstChunkTargetChars = 50,
            followupChunkTargetChars = 100,
            maxChunkChars = 180,
        )

        assertTrue(coalescer.append("This pending chunk should be discarded.").isEmpty())
        coalescer.clear()

        assertTrue(coalescer.append("Short reset.").isEmpty())
        assertEquals(listOf("Short reset."), coalescer.flush())
    }
}
