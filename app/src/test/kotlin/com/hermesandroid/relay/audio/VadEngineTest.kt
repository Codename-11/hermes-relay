package com.hermesandroid.relay.audio

import com.hermesandroid.relay.data.BargeInSensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Unit tests for [VadEngine].
 *
 * The real [VadEngine] wraps `com.konovalov.vad.silero.VadSilero`, which
 * loads an ONNX Silero model from Android asset storage and requires a live
 * [android.content.Context] plus native ONNX Runtime libs. Neither is
 * available in a JVM-only Gradle unit test (`test` source set, no
 * Robolectric in this module — see `BargeInPreferencesTest` for the same
 * pattern), so we bypass the backend by injecting a scripted
 * [VadEngine.VadClient] via the `@VisibleForTesting internal` constructor.
 *
 * What that buys us:
 *   - deterministic control over the raw library-layer speech/no-speech
 *     decision without needing to generate audio that actually crosses the
 *     Silero model's threshold;
 *   - tests run in plain JUnit with no device/emulator — Bailey just hits
 *     the green `test` task in Studio;
 *   - the second-layer (N-consecutive-frames) hysteresis is exercised end
 *     to end because that logic lives in [VadEngine], not the client.
 *
 * The silence / synthetic-tone generators below produce real PCM buffers so
 * that the `frame: ShortArray` parameter to [VadEngine.analyze] is exactly
 * the right shape a production caller (B3's audio loop) will pass. The fake
 * client ignores their content and returns the pre-programmed verdict.
 */
// Tracked in GitHub issue #32. Deferred with BargeInListenerTest + the
// VoiceViewModel coroutine tests pending the test-infra follow-up PR
// (separate source set or proper fork strategy).
@Ignore("Tracked in GitHub issue #32 — voice test suite validation deferred")
class VadEngineTest {

    @Test
    fun `silence input never reports speech`() {
        val fake = FakeVadClient(alwaysSpeech = false)
        val engine = VadEngine(fake, sampleRate = 16_000).apply {
            setSensitivity(BargeInSensitivity.High) // consecutive=1 — most eager
        }

        val silence = generateSilence()
        repeat(30) {
            val result = engine.analyze(silence)
            assertFalse(
                "silence frame #$it must not report speech",
                result.isSpeech,
            )
        }
    }

    @Test
    fun `synthetic tone reports speech after debounce threshold`() {
        val fake = FakeVadClient(alwaysSpeech = true)
        val engine = VadEngine(fake, sampleRate = 16_000).apply {
            // Default profile requires 2 consecutive raw-speech frames.
            setSensitivity(BargeInSensitivity.Default)
        }

        val tone = generate1kHzTone()

        // Frame 1 — raw library returns speech, but consecutive=1 < 2 so
        // [VadResult.isSpeech] must still be false. Library reports speech
        // in [VadResult.probability] so B3 / B4 can react (duck) before the
        // debounce latches.
        val first = engine.analyze(tone)
        assertFalse("first tone frame should not yet flip debouncer", first.isSpeech)
        assertEquals(
            "first tone frame should expose raw-speech via probability",
            1f,
            first.probability,
            0f,
        )

        // Frame 2 — hits the consecutive=2 threshold; flip to speech.
        val second = engine.analyze(tone)
        assertTrue("second tone frame should flip debouncer", second.isSpeech)
    }

    @Test
    fun `Off sensitivity always returns not-speech`() {
        val fake = FakeVadClient(alwaysSpeech = true)
        val engine = VadEngine(fake, sampleRate = 16_000).apply {
            setSensitivity(BargeInSensitivity.Off)
        }

        val tone = generate1kHzTone()
        repeat(10) {
            val result = engine.analyze(tone)
            assertFalse("Off sensitivity must never report speech", result.isSpeech)
            assertEquals(
                "Off sensitivity must short-circuit before consulting the backend",
                0f,
                result.probability,
                0f,
            )
        }
        // Also prove we didn't even call the backend.
        assertEquals(
            "Off sensitivity should not consult the VAD backend at all",
            0,
            fake.callCount,
        )
    }

    @Test
    fun `isolated single speech frame does not trigger under consecutive=3`() {
        // Scripted pattern: speech frame, then 10 silence frames. With
        // Low sensitivity (consecutiveSpeechFrames = 3), the single spike
        // must never latch the debouncer.
        val pattern = listOf(true) + List(10) { false }
        val fake = FakeVadClient.scripted(pattern)
        val engine = VadEngine(fake, sampleRate = 16_000).apply {
            setSensitivity(BargeInSensitivity.Low) // consecutive = 3
        }

        val frame = generate1kHzTone() // content is ignored by the fake
        pattern.forEachIndexed { idx, _ ->
            val result = engine.analyze(frame)
            assertFalse(
                "frame $idx: isolated speech spike must not flip debouncer " +
                    "(consecutive=3, run length=1)",
                result.isSpeech,
            )
        }
    }

    @Test
    fun `silence after sustained speech releases the debouncer`() {
        // Sustain 3 speech frames → debouncer latches → a single silence
        // frame must release it, and the next speech frame starts the
        // count over. Verifies the counter reset on the silence branch.
        val fake = FakeVadClient.scripted(
            listOf(true, true, true, false, true),
        )
        val engine = VadEngine(fake, sampleRate = 16_000).apply {
            setSensitivity(BargeInSensitivity.Low) // consecutive = 3
        }
        val frame = generate1kHzTone()

        assertFalse("frame 0: count=1 < 3", engine.analyze(frame).isSpeech)
        assertFalse("frame 1: count=2 < 3", engine.analyze(frame).isSpeech)
        assertTrue("frame 2: count=3 == 3, latch", engine.analyze(frame).isSpeech)
        assertFalse("frame 3: silence releases debouncer", engine.analyze(frame).isSpeech)
        assertFalse(
            "frame 4: single speech frame after release must not re-latch (count=1 < 3)",
            engine.analyze(frame).isSpeech,
        )
    }

    // ─── Helpers ──────────────────────────────────────────────────────────

    /** A full frame of zero-valued PCM (true digital silence). */
    private fun generateSilence(): ShortArray =
        ShortArray(VadEngine.FRAME_SIZE_SAMPLES)

    /**
     * 512-sample 1 kHz sine tone at 16 kHz / 16-bit PCM, ~−6 dBFS. The fake
     * client doesn't inspect the buffer content, but producing real audio
     * keeps the test authentic to the caller contract and guards against
     * accidental buffer-shape regressions.
     */
    private fun generate1kHzTone(): ShortArray {
        val samples = ShortArray(VadEngine.FRAME_SIZE_SAMPLES)
        val freq = 1000.0
        val sampleRate = 16_000.0
        val amplitude = Short.MAX_VALUE * 0.5
        for (i in samples.indices) {
            samples[i] = (amplitude * sin(2 * PI * freq * i / sampleRate)).toInt().toShort()
        }
        return samples
    }

    /**
     * Deterministic stand-in for [VadEngine.VadClient] — the real client
     * wraps a native ONNX session and can't run in a JVM unit test.
     *
     * - [alwaysSpeech] mode returns the same verdict for every frame (the
     *   constant-state tests).
     * - [scripted] mode walks through a pre-baked list of decisions; once
     *   the list is exhausted it falls back to `false` (silence), which
     *   keeps tail-of-test assertions safe.
     */
    private class FakeVadClient private constructor(
        private val alwaysSpeech: Boolean?,
        private val script: List<Boolean>?,
    ) : VadEngine.VadClient {
        var callCount: Int = 0
            private set
        private var scriptIndex: Int = 0

        constructor(alwaysSpeech: Boolean) : this(alwaysSpeech, null)

        override fun isSpeech(frame: ShortArray): Boolean {
            callCount++
            alwaysSpeech?.let { return it }
            val s = script!!
            if (scriptIndex >= s.size) return false
            return s[scriptIndex++]
        }

        override fun applyProfile(profile: VadEngine.SensitivityProfile) {
            // No-op for the fake — the second-layer hysteresis inside
            // VadEngine is what we're testing; the library-side attack /
            // release durations are exercised in instrumented tests on a
            // real device (out of scope for B2 JVM coverage).
        }

        override fun close() { /* no native resources to release */ }

        companion object {
            fun scripted(verdicts: List<Boolean>): FakeVadClient =
                FakeVadClient(alwaysSpeech = null, script = verdicts)
        }
    }
}
