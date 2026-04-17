package com.hermesandroid.relay.voice

import com.hermesandroid.relay.viewmodel.extractNextSentence
import com.hermesandroid.relay.viewmodel.findLastSecondaryBreak
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V3 coverage (voice-quality-pass, 2026-04-16): exercises the
 * sentence-coalescing rules added to [extractNextSentence], the
 * MAX_BUFFER_LEN secondary-break escape hatch, abbreviation
 * preservation, the end-of-stream short-flush behavior, and the
 * 800 ms debounced idle-flush timer.
 *
 * The timer test exercises the debouncer pattern that
 * [com.hermesandroid.relay.viewmodel.VoiceViewModel.rearmIdleFlush]
 * uses in production — a fresh `Job` re-armed on every delta, cancelled
 * and replaced if more input arrives inside the window, firing exactly
 * once if the buffer truly stalls. We run it against a [StandardTestDispatcher]
 * instead of reaching into the ViewModel (which needs an Application +
 * wired dependencies) so the coroutine semantics stay observable.
 */
class VoiceViewModelChunkingTest {

    // ── Short-sentence coalescing ────────────────────────────────────────

    @Test
    fun `three short sentences coalesce into one chunk`() {
        // "Sure. Let me check. Here's the result." = 38 chars — exactly the
        // pathological pattern from the diagnosis. Should emit as ONE chunk
        // when the stream is live, not three.
        val buf = StringBuilder("Sure. Let me check. Here's the result.")
        // Live stream, buffer exactly covers all three sentences. The last
        // boundary puts us at 38 < 40 — so during streaming we DON'T emit
        // (we wait for the next delta). On stream-complete we emit the
        // whole coalesced run.
        assertNull(extractNextSentence(buf, streamComplete = false))
        val out = extractNextSentence(buf, streamComplete = true)
        assertEquals("Sure. Let me check. Here's the result.", out)
        assertEquals(0, buf.length)
    }

    @Test
    fun `short-then-long coalesces past MIN_COALESCE_LEN`() {
        // "Sure." is 5 chars. The second sentence pushes us well over 40.
        // During streaming we extend past the short boundary and emit when
        // the combined chunk crosses the threshold.
        val buf = StringBuilder(
            "Sure. Here is a substantially longer follow-up sentence for you.",
        )
        val out = extractNextSentence(buf, streamComplete = false)
        assertEquals(
            "Sure. Here is a substantially longer follow-up sentence for you.",
            out,
        )
        assertEquals(0, buf.length)
    }

    // ── MAX_BUFFER_LEN secondary-break escape ───────────────────────────

    @Test
    fun `long run-on flushes at last secondary break inside window`() {
        // 450 chars of comma-separated run-on with NO `.?!\n` terminators.
        // The escape hatch must trigger and cut at the last comma inside the
        // first 400 chars, not the first comma — we ship as much as possible
        // per chunk.
        val fragment = "and then another comma-separated clause, "
        val builder = StringBuilder()
        while (builder.length < 450) {
            builder.append(fragment)
        }
        val raw = builder.toString()
        val buf = StringBuilder(raw)

        // Find expected cut point: the last index of ", " inside the first
        // 400 chars. findLastSecondaryBreak returns the index of the break
        // char; the chunk is [0..breakIdx+1].
        val expectedBreakIdx = findLastSecondaryBreak(raw, 400)
        assertTrue(
            "sanity: the 450-char run-on must contain a secondary break inside the window",
            expectedBreakIdx in 0 until 400,
        )

        val out = extractNextSentence(buf, streamComplete = false)
        assertNotNull("expected the escape hatch to flush", out)
        assertTrue("expected chunk to end with a comma", out!!.endsWith(","))
        assertTrue("expected chunk to be at most 400 chars", out.length <= 400)
        // The remainder should still contain more fragments.
        assertTrue(
            "expected buffer to retain the tail",
            buf.length > 0 && buf.length < raw.length,
        )
    }

    @Test
    fun `findLastSecondaryBreak returns latest comma inside window`() {
        val s = "a, b, c, d, e, f"
        // Last ", " inside the first 10 chars. Positions of commas:
        // "a, b, c, d, e, f"
        //  0123456789012345
        // commas at 1, 4, 7, 10, 13. Inside first 10 chars: 1, 4, 7. Last = 7.
        val idx = findLastSecondaryBreak(s, 10)
        assertEquals(7, idx)
    }

    @Test
    fun `findLastSecondaryBreak refuses comma with no following space`() {
        // "3,000" — the comma is in a numeric context, NOT a break point.
        val s = "value 3,000 more"
        val idx = findLastSecondaryBreak(s, s.length)
        // The only break-eligible comma is the one inside "3,000", and it
        // has a digit right after — must be refused.
        assertEquals(-1, idx)
    }

    // ── Abbreviation preservation ────────────────────────────────────────

    @Test
    fun `does not split on e-dot-g-dot during coalescing`() {
        // "See e.g. the docs." = 18 chars (under threshold). "Next sentence."
        // adds 15 more for 33 total — still under. Concatenated the full
        // buffer reads as one coalesced chunk on stream-complete. The
        // critical assertion is that the CHUNK contains BOTH "See e.g. the
        // docs." and "Next sentence." as one piece, not split at either of
        // the `e.g.` periods.
        val buf = StringBuilder("See e.g. the docs. Next sentence.")
        val out = extractNextSentence(buf, streamComplete = true)
        assertEquals("See e.g. the docs. Next sentence.", out)
        assertEquals(0, buf.length)
    }

    @Test
    fun `single-letter abbreviation U dot S dot does not split`() {
        val buf = StringBuilder("The U.S. approach differs from other countries.")
        val out = extractNextSentence(buf, streamComplete = false)
        // 47 chars, final period + end-of-buffer → boundary at 47 >= 40.
        // The critical thing is the U.S. periods were SKIPPED (their next
        // char is a letter, not whitespace) so the chunker correctly
        // treats the whole thing as a single sentence.
        assertEquals("The U.S. approach differs from other countries.", out)
    }

    // ── End-of-stream short flush ────────────────────────────────────────

    @Test
    fun `emits short trailing sentence on stream-complete`() {
        val buf = StringBuilder("Okay.")
        // During streaming: held back — too short.
        assertNull(extractNextSentence(buf, streamComplete = false))
        // On stream-complete: emit anyway so the TTS queue doesn't starve.
        val out = extractNextSentence(buf, streamComplete = true)
        assertEquals("Okay.", out)
        assertEquals(0, buf.length)
    }

    @Test
    fun `emits trailing terminator-less fragment on stream-complete`() {
        // The SSE stream ended without emitting a terminator — we should
        // still ship whatever is in the buffer rather than drop it.
        val buf = StringBuilder("trailing fragment no period")
        val out = extractNextSentence(buf, streamComplete = true)
        assertEquals("trailing fragment no period", out)
        assertEquals(0, buf.length)
    }

    // ── Timer-based flush (debounced idle) ───────────────────────────────
    //
    // The production hookup lives in VoiceViewModel.rearmIdleFlush; this
    // test exercises the debounce pattern we use there against a virtual
    // clock. It isn't the same code, but it MUST behave the same way —
    // if either diverges, the production path is what should move.

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `idle timer flushes buffer after 800ms of silence`() = runTest {
        val flushed = mutableListOf<String>()
        val buffer = StringBuilder()
        var idleJob: Job? = null

        fun appendDelta(delta: String) {
            buffer.append(delta)
            idleJob?.cancel()
            idleJob = launch {
                delay(800L)
                // Treat as stream-complete for this one-shot drain — short
                // fragments inclusive.
                val out = extractNextSentence(buffer, streamComplete = true)
                if (out != null) flushed.add(out)
            }
        }

        appendDelta("Hm")
        // Nothing fired yet — the scheduler is paused.
        advanceTimeBy(799L)
        assertTrue("must not flush before 800 ms", flushed.isEmpty())
        advanceTimeBy(2L) // cross the threshold
        advanceUntilIdle()
        assertEquals(listOf("Hm"), flushed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `delta within the window cancels the pending flush`() = runTest {
        val flushed = mutableListOf<String>()
        val buffer = StringBuilder()
        var idleJob: Job? = null

        fun appendDelta(delta: String) {
            buffer.append(delta)
            idleJob?.cancel()
            idleJob = launch {
                delay(800L)
                val out = extractNextSentence(buffer, streamComplete = true)
                if (out != null) flushed.add(out)
            }
        }

        appendDelta("Hm")
        advanceTimeBy(400L)
        appendDelta(", let me think.")
        advanceTimeBy(400L)
        // 800 ms elapsed overall but the last delta was only 400 ms ago.
        assertTrue("must not flush while deltas keep arriving", flushed.isEmpty())
        advanceTimeBy(401L)
        advanceUntilIdle()
        assertEquals(listOf("Hm, let me think."), flushed)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `explicit dispatcher preserves debounce semantics`() = runTest(
        StandardTestDispatcher(),
    ) {
        // Extra belt-and-braces test that the debounce still works when the
        // test body is explicitly bound to a StandardTestDispatcher. Catches
        // the regression where runTest's default dispatcher behaves
        // differently from the one VoiceViewModel would use in production.
        val flushed = mutableListOf<String>()
        val buffer = StringBuilder("Hm")
        val job = launch {
            delay(800L)
            val out = extractNextSentence(buffer, streamComplete = true)
            if (out != null) flushed.add(out)
        }
        advanceTimeBy(800L)
        advanceUntilIdle()
        job.join()
        assertEquals(listOf("Hm"), flushed)
    }
}
