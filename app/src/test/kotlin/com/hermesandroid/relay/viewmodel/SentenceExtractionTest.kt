package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the sentence-boundary extractor used by [VoiceViewModel]
 * to chunk streaming assistant text into TTS-ready sentences.
 *
 * Pure function over a [StringBuilder] — no Android, no coroutines.
 */
class SentenceExtractionTest {

    @Test
    fun `returns null when buffer is empty`() {
        val buf = StringBuilder()
        assertNull(extractNextSentence(buf))
    }

    @Test
    fun `returns null when no terminator is present`() {
        val buf = StringBuilder("hello world no ending")
        assertNull(extractNextSentence(buf))
    }

    @Test
    fun `extracts a simple sentence ending in period`() {
        val buf = StringBuilder("Hello world.")
        val out = extractNextSentence(buf)
        assertEquals("Hello world.", out)
        assertEquals(0, buf.length)
    }

    @Test
    fun `extracts a sentence ending in question mark`() {
        val buf = StringBuilder("How are you?")
        assertEquals("How are you?", extractNextSentence(buf))
    }

    @Test
    fun `extracts a sentence ending in exclamation mark`() {
        val buf = StringBuilder("That's great!")
        assertEquals("That's great!", extractNextSentence(buf))
    }

    @Test
    fun `extracts first sentence leaves rest in buffer`() {
        val buf = StringBuilder("First sentence here. Second sentence here.")
        val first = extractNextSentence(buf)
        assertEquals("First sentence here.", first)
        assertEquals("Second sentence here.", buf.toString())
    }

    @Test
    fun `does not split on period followed by letter (abbreviation)`() {
        val buf = StringBuilder("e.g. this is fine.")
        val out = extractNextSentence(buf)
        assertEquals("e.g. this is fine.", out)
    }

    @Test
    fun `refuses too-short sentences`() {
        // "Hi!" is only 3 chars, below MIN_SENTENCE_LEN=6.
        val buf = StringBuilder("Hi!")
        assertNull(extractNextSentence(buf))
    }

    @Test
    fun `accepts sentence ending in newline`() {
        val buf = StringBuilder("Hello there\nmore text")
        val out = extractNextSentence(buf)
        assertEquals("Hello there", out)
        assertEquals("more text", buf.toString())
    }

    @Test
    fun `drains multiple sentences in sequence`() {
        val buf = StringBuilder("Hello world. How are you? Fine thanks!")
        val a = extractNextSentence(buf)
        val b = extractNextSentence(buf)
        val c = extractNextSentence(buf)
        assertEquals("Hello world.", a)
        assertEquals("How are you?", b)
        assertEquals("Fine thanks!", c)
        assertNull(extractNextSentence(buf))
    }

    @Test
    fun `handles trailing whitespace cleanup`() {
        val buf = StringBuilder("Hello there.   Next up.")
        val first = extractNextSentence(buf)
        assertEquals("Hello there.", first)
        assertEquals("Next up.", buf.toString())
    }
}
