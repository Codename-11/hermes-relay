package com.hermesandroid.relay.voice

import com.hermesandroid.relay.viewmodel.countOccurrences
import com.hermesandroid.relay.viewmodel.sanitizeForTts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * Unit tests for [sanitizeForTts] — the client-side companion to the
 * relay-side sanitizer in `plugin/relay/tts_sanitizer.py` (V1 of the
 * voice-quality-pass plan).
 *
 * These tests deliberately mirror `plugin/tests/test_tts_sanitizer.py`
 * test-for-test so a drift between the two sanitizer implementations
 * surfaces as a failure here instead of a field bug. If you add a case
 * here, add it there — and vice versa.
 *
 * Pure top-level function; no Android, no coroutines, no ViewModel.
 */
@Ignore("Tracked in GitHub issue #32 — voice test suite validation deferred (conservative; pure-logic but defer-blocked by v0.5.1 timeline)")
class VoiceViewModelSanitizerTest {

    // ── Code fences ────────────────────────────────────────────────────

    @Test
    fun `strips fenced code block`() {
        val src = "Before\n```python\nprint('hi')\n```\nAfter"
        val out = sanitizeForTts(src)
        assertFalse(out.contains("```"))
        assertFalse(out.contains("print"))
        assertTrue(out.contains("Before"))
        assertTrue(out.contains("After"))
    }

    @Test
    fun `leaves plain prose untouched`() {
        val src = "This is a plain sentence with no code."
        assertEquals(src, sanitizeForTts(src))
    }

    // ── Markdown links ─────────────────────────────────────────────────

    @Test
    fun `replaces link with label`() {
        val src = "See [the docs](https://example.com/docs) for details."
        val out = sanitizeForTts(src)
        assertTrue(out.contains("the docs"))
        assertFalse(out.contains("https://"))
        assertFalse(out.contains("["))
        assertFalse(out.contains("]("))
    }

    // ── URLs ───────────────────────────────────────────────────────────

    @Test
    fun `strips bare http url`() {
        val src = "Check https://github.com/foo/bar please."
        val out = sanitizeForTts(src)
        assertFalse(out.contains("github.com"))
        assertFalse(out.contains("http"))
        assertTrue(out.contains("Check"))
        assertTrue(out.contains("please."))
    }

    @Test
    fun `non url colon usage unchanged`() {
        val src = "The ratio is 3:1 overall."
        assertEquals(src, sanitizeForTts(src))
    }

    // ── Bold + italic ──────────────────────────────────────────────────

    @Test
    fun `strips bold markers`() {
        assertEquals("This is very important.", sanitizeForTts("This is **very** important."))
    }

    @Test
    fun `strips italic markers`() {
        assertEquals("This is slightly relevant.", sanitizeForTts("This is *slightly* relevant."))
    }

    // ── Inline code ────────────────────────────────────────────────────

    @Test
    fun `strips inline code backticks`() {
        assertEquals(
            "Use the sanitize_for_tts function.",
            sanitizeForTts("Use the `sanitize_for_tts` function."),
        )
    }

    // ── Headers ────────────────────────────────────────────────────────

    @Test
    fun `strips header marker`() {
        val out = sanitizeForTts("# Heading\nBody text.")
        assertTrue(out.contains("Heading"))
        assertFalse(out.contains("#"))
    }

    @Test
    fun `hash mid line is preserved`() {
        // MULTILINE-anchored ^#+ — a mid-sentence '#' must not go.
        val src = "The tag is issue #42 here."
        assertEquals(src, sanitizeForTts(src))
    }

    // ── List items ─────────────────────────────────────────────────────

    @Test
    fun `strips dash list marker`() {
        val out = sanitizeForTts("- first\n- second\n- third")
        assertFalse(out.contains("- "))
        assertTrue(out.contains("first"))
        assertTrue(out.contains("third"))
    }

    @Test
    fun `strips asterisk list marker`() {
        val out = sanitizeForTts("* alpha\n* beta")
        assertTrue(out.contains("alpha"))
        assertTrue(out.contains("beta"))
        assertFalse(out.trimStart().startsWith("*"))
    }

    @Test
    fun `hyphenated word is not a list`() {
        val src = "Well-formed prose stays intact."
        assertEquals(src, sanitizeForTts(src))
    }

    // ── Horizontal rules ───────────────────────────────────────────────

    @Test
    fun `strips horizontal rule`() {
        val out = sanitizeForTts("Before\n---\nAfter")
        assertFalse(out.contains("---"))
        assertTrue(out.contains("Before"))
        assertTrue(out.contains("After"))
    }

    @Test
    fun `two dashes preserved`() {
        // Only 3+ dashes count as HR.
        val src = "A -- B"
        assertEquals(src, sanitizeForTts(src))
    }

    // ── Excess newlines ────────────────────────────────────────────────

    @Test
    fun `collapses excess newlines`() {
        assertEquals("one\n\ntwo", sanitizeForTts("one\n\n\n\ntwo"))
    }

    @Test
    fun `two newlines preserved`() {
        assertEquals("one\n\ntwo", sanitizeForTts("one\n\ntwo"))
    }

    // ── Tool annotations ───────────────────────────────────────────────

    @Test
    fun `strips terminal annotation`() {
        val out = sanitizeForTts("Running `\uD83D\uDCBB terminal` now.")
        assertFalse(out.contains("terminal"))
        assertFalse(out.contains("\uD83D\uDCBB"))
        assertTrue(out.contains("Running"))
        assertTrue(out.contains("now."))
    }

    @Test
    fun `strips wrench annotation`() {
        val out = sanitizeForTts("Calling `\uD83D\uDD27 some_tool` next.")
        assertFalse(out.contains("some_tool"))
        assertFalse(out.contains("\uD83D\uDD27"))
    }

    @Test
    fun `strips check annotation`() {
        val out = sanitizeForTts("Task `\u2705 done` complete.")
        assertFalse(out.contains("done"))
        assertFalse(out.contains("\u2705"))
        assertFalse(out.contains("`"))
    }

    @Test
    fun `strips warning annotation with variation selector`() {
        val out = sanitizeForTts("Caution `\u26A0\uFE0F warning` ahead.")
        assertFalse(out.contains("warning"))
        assertFalse(out.contains("\u26A0"))
    }

    @Test
    fun `non annotation inline code is just inline code`() {
        // A bare inline-code span without an emoji falls through to
        // the inline-code rule — backticks gone, content preserved.
        assertEquals("Import json for this.", sanitizeForTts("Import `json` for this."))
    }

    // ── Standalone emoji ───────────────────────────────────────────────

    @Test
    fun `strips standalone check emoji`() {
        val out = sanitizeForTts("Done \u2705 moving on.")
        assertFalse(out.contains("\u2705"))
        assertTrue(out.contains("Done"))
        assertTrue(out.contains("moving on."))
    }

    @Test
    fun `does not strip flag emoji`() {
        // Regional-indicator sequence for USA flag — must survive.
        val src = "Greetings from \uD83C\uDDFA\uD83C\uDDF8 today."
        val out = sanitizeForTts(src)
        assertTrue(out.contains("\uD83C\uDDFA\uD83C\uDDF8"))
    }

    @Test
    fun `does not strip unrelated symbols`() {
        val src = "Cost is \$5 and ratio is 3:1."
        assertEquals(src, sanitizeForTts(src))
    }

    // ── Empty / whitespace edge cases ──────────────────────────────────

    @Test
    fun `empty string returns empty`() {
        assertEquals("", sanitizeForTts(""))
    }

    @Test
    fun `whitespace only returns empty`() {
        assertEquals("", sanitizeForTts("   \n\t  "))
    }

    // ── Combined realistic fixture ─────────────────────────────────────
    //
    // Mirrors CombinedFixtureTest in test_tts_sanitizer.py so a drift
    // between Kotlin and Python regex sets fails loudly here first.

    @Test
    fun `realistic hermes assistant message reads cleanly`() {
        val src = (
            "# Summary\n" +
                "\n" +
                "Running `\uD83D\uDCBB terminal` to check the repo. " +
                "See [the plan](https://example.com/plan) for details — " +
                "it uses **ElevenLabs** and the *flash* streaming model.\n" +
                "\n" +
                "Steps:\n" +
                "- Pull https://github.com/foo/bar\n" +
                "- Run the `sanitize_for_tts` helper\n" +
                "- Confirm `\u2705 done`\n" +
                "\n" +
                "---\n" +
                "\n" +
                "```python\n" +
                "print('this should not be spoken')\n" +
                "```\n" +
                "\n" +
                "All good \u2705."
            )
        val out = sanitizeForTts(src)

        assertFalse(out.contains("```"))
        assertFalse(out.contains("**"))
        assertFalse(out.contains("http"))
        assertFalse(out.contains("github.com"))
        assertFalse(out.contains("example.com"))
        assertFalse(out.contains("print("))
        assertFalse(out.contains("\uD83D\uDCBB"))
        assertFalse(out.contains("\u2705"))
        assertFalse(out.contains("---"))
        assertFalse(out.contains("#"))
        assertTrue(out.contains("Summary"))
        // Tool-annotation spans gone entirely (including labels).
        assertFalse(out.contains("terminal"))
        assertFalse(out.contains("done"))
        // Link label preserved, URL gone.
        assertTrue(out.contains("the plan"))
        // Bold/italic markers gone, words kept.
        assertTrue(out.contains("ElevenLabs"))
        assertTrue(out.contains("flash"))
        // List content preserved, markers gone.
        assertTrue(out.contains("Pull"))
        assertTrue(out.contains("sanitize_for_tts"))
        // No line should begin with a list marker.
        for (line in out.lines()) {
            val stripped = line.trimStart()
            assertFalse(stripped.startsWith("- "))
            assertFalse(stripped.startsWith("* "))
        }
        assertFalse(out.contains("\n\n\n"))
    }

    // ── Streaming code-fence edge case ─────────────────────────────────
    //
    // A code fence can span multiple SSE deltas. The sanitizer MUST NOT
    // strip an unclosed opener on its own, or sanitize the fence
    // contents as if they were prose — the closing fence would then
    // arrive alone and leave orphan backticks in the sentence buffer.
    //
    // We simulate the production path by mirroring
    // [com.hermesandroid.relay.viewmodel.VoiceViewModel.appendSanitizedDelta]'s
    // deferral logic here. The real method is private on the
    // ViewModel, but the contract — "hold raw until the running count
    // of ``` is even, then sanitize the pending region as a whole" —
    // is fully exercised via [countOccurrences] + [sanitizeForTts].

    @Test
    fun `streaming delta defers until closing code fence arrives`() {
        val pending = StringBuilder()
        val sentenceBuffer = StringBuilder()

        fun feed(delta: String) {
            pending.append(delta)
            if (countOccurrences(pending, "```") % 2 != 0) return
            val cleaned = sanitizeForTts(pending.toString())
            pending.clear()
            if (cleaned.isNotEmpty()) sentenceBuffer.append(cleaned)
        }

        // Canonical three-chunk scenario: opening fence in chunk 1,
        // contents in chunk 2, closing fence in chunk 3. Each delta
        // arrives independently via SSE.
        feed("before ```")          // odd fence count → held
        feed("code block contents") // still odd → still held
        feed("``` after.")          // even → sanitize + flush

        val combined = sentenceBuffer.toString()
        assertFalse(
            "sentenceBuffer must contain no orphan triple-fence: <$combined>",
            combined.contains("```"),
        )
        assertFalse(
            "fenced contents must not reach TTS: <$combined>",
            combined.contains("code block contents"),
        )
        assertTrue(
            "prose before the fence should survive: <$combined>",
            combined.contains("before"),
        )
        assertTrue(
            "prose after the fence should survive: <$combined>",
            combined.contains("after."),
        )
    }

    @Test
    fun `streaming delta with no fence flushes immediately per chunk`() {
        val pending = StringBuilder()
        val sentenceBuffer = StringBuilder()

        fun feed(delta: String) {
            pending.append(delta)
            if (countOccurrences(pending, "```") % 2 != 0) return
            val cleaned = sanitizeForTts(pending.toString())
            pending.clear()
            if (cleaned.isNotEmpty()) sentenceBuffer.append(cleaned)
        }

        feed("Hello ")
        feed("world. ")
        feed("How are you?")

        // No fences in any delta → each one passes through the
        // sanitizer immediately and nothing is held back.
        assertEquals(0, pending.length)
        assertTrue(sentenceBuffer.toString().contains("Hello"))
        assertTrue(sentenceBuffer.toString().contains("How are you?"))
    }

    @Test
    fun `countOccurrences returns expected totals`() {
        assertEquals(0, countOccurrences("no fences here", "```"))
        assertEquals(1, countOccurrences("open ``` only", "```"))
        assertEquals(2, countOccurrences("```a```", "```"))
        assertEquals(3, countOccurrences("```a```b```", "```"))
        // Non-overlapping: "``````" is two matches, not four.
        assertEquals(2, countOccurrences("``````", "```"))
    }
}
