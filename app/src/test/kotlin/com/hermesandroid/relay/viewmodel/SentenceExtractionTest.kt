package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the sentence-boundary extractor used by [VoiceViewModel]
 * to chunk streaming assistant text into TTS-ready sentences.
 *
 * Pure function over a [StringBuilder] — no Android, no coroutines.
 *
 * Post-V3 semantics (see voice-quality-pass 2026-04-16): short sentences
 * coalesce against [MIN_COALESCE_LEN] and don't emit during streaming
 * unless the combined chunk crosses the threshold. Tests pass
 * `streamComplete = true` when the old-style "emit whatever is there"
 * behavior is what's being exercised. See [VoiceViewModelChunkingTest]
 * for the dedicated V3 coverage.
 */
class SentenceExtractionTest {

    @Test
    fun `returns null when buffer is empty`() {
        val buf = StringBuilder()
        assertNull(extractNextSentence(buf))
    }

    @Test
    fun `returns null when no terminator is present and stream is live`() {
        val buf = StringBuilder("hello world no ending")
        assertNull(extractNextSentence(buf, streamComplete = false))
    }

    @Test
    fun `emits short sentence when stream is complete`() {
        val buf = StringBuilder("Hello world.")
        val out = extractNextSentence(buf, streamComplete = true)
        assertEquals("Hello world.", out)
        assertEquals(0, buf.length)
    }

    @Test
    fun `holds short sentence when stream is live`() {
        val buf = StringBuilder("Hello world.")
        assertNull(extractNextSentence(buf, streamComplete = false))
    }

    @Test
    fun `emits short question when stream is complete`() {
        val buf = StringBuilder("How are you?")
        assertEquals("How are you?", extractNextSentence(buf, streamComplete = true))
    }

    @Test
    fun `emits short exclamation when stream is complete`() {
        val buf = StringBuilder("That's great!")
        assertEquals("That's great!", extractNextSentence(buf, streamComplete = true))
    }

    @Test
    fun `coalesces two short sentences live into one chunk`() {
        // Each sentence is ~20 chars, combined 42 — crosses MIN_COALESCE_LEN.
        val buf = StringBuilder("First sentence here. Second sentence here.")
        val first = extractNextSentence(buf, streamComplete = false)
        assertEquals("First sentence here. Second sentence here.", first)
        assertEquals(0, buf.length)
    }

    @Test
    fun `emits full-length sentence live without coalescing past it`() {
        val long = "This is a sufficiently long first sentence for voice mode."
        val short = " Next up."
        val buf = StringBuilder(long + short)
        val out = extractNextSentence(buf, streamComplete = false)
        assertEquals(long, out)
        assertEquals("Next up.", buf.toString())
    }

    @Test
    fun `does not split on period followed by letter (abbreviation)`() {
        // "e.g." — period + letter must not be a boundary.
        val buf = StringBuilder("See e.g. the docs. That is the reference point now.")
        val out = extractNextSentence(buf, streamComplete = false)
        // First period inside "e.g." is boundary-followed-by-space but the
        // chunk only reaches 4 chars, so we extend. Final period at end of
        // "See e.g. the docs." puts us at 18 chars — still under threshold,
        // so we extend again past the next period. Full buffer = 52 chars,
        // second valid terminator at the ultimate period is 52 >= 40.
        assertEquals(
            "See e.g. the docs. That is the reference point now.",
            out,
        )
    }

    @Test
    fun `accepts sentence ending in newline when crossing threshold`() {
        // Newline is itself a terminator regardless of lookahead. The first
        // line is already over MIN_COALESCE_LEN so it emits on its own.
        val first = "This first paragraph line is reasonably long on its own."
        val second = "Second line is short."
        val buf = StringBuilder(first + "\n" + second)
        val out = extractNextSentence(buf, streamComplete = false)
        assertEquals(first, out)
        assertEquals(second, buf.toString())
    }

    @Test
    fun `drains multiple long sentences in sequence`() {
        val buf = StringBuilder(
            "This is a long first sentence of reasonable weight. " +
                "Here is a long second sentence that is also weighty. " +
                "Finally a third equally-substantial closing sentence.",
        )
        val a = extractNextSentence(buf, streamComplete = false)
        val b = extractNextSentence(buf, streamComplete = false)
        val c = extractNextSentence(buf, streamComplete = true)
        assertEquals("This is a long first sentence of reasonable weight.", a)
        assertEquals("Here is a long second sentence that is also weighty.", b)
        assertEquals("Finally a third equally-substantial closing sentence.", c)
        assertNull(extractNextSentence(buf, streamComplete = true))
    }

    @Test
    fun `handles trailing whitespace cleanup`() {
        val buf = StringBuilder("This is a reasonably long first sentence.   Next up.")
        val first = extractNextSentence(buf, streamComplete = false)
        assertEquals("This is a reasonably long first sentence.", first)
        assertEquals("Next up.", buf.toString())
    }
}
