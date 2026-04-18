package com.hermesandroid.relay.audio

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.hermesandroid.relay.data.BargeInSensitivity
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate

/**
 * Voice activity detection engine for barge-in (plan unit B2).
 *
 * Wraps the upstream `com.github.gkonovalov.android-vad:silero` Silero VAD
 * behind a project-internal interface so callers never see the library type —
 * swapping to `vad-webrtc` or another backend later is a single-file change
 * here, not a fan-out edit across [com.hermesandroid.relay.audio.BargeInListener]
 * (B3) and [com.hermesandroid.relay.viewmodel.VoiceViewModel] (B4).
 *
 * ### Frame contract
 *
 * - 16 kHz mono 16-bit PCM.
 * - Exactly **512 samples** per call (32 ms at 16 kHz). This is the smallest
 *   Silero-supported frame size at 16 kHz per the library's public
 *   `supportedParameters` map (512, 1024, 1536); 512 keeps latency tight.
 *   The plan document references 640 samples — that was the previous library
 *   constraint before the Silero 2.0 series dropped 160/320/640/1024 in
 *   favour of 512/1024/1536. Use 512.
 * - [analyze] is synchronous. Cost is one ONNX forward pass + a couple of
 *   counter increments. Callers (B3) feed frames in a tight loop; any heap
 *   allocation beyond the returned [VadResult] is avoided.
 *
 * ### Two-layer hysteresis
 *
 * 1. **Library layer** — Silero's own `isSpeech()` already applies an
 *    attack/release window driven by `speechDurationMs`/`silenceDurationMs`
 *    ([SENSITIVITY_PROFILES]). This handles per-frame wobble from the DNN.
 * 2. **Our layer** — on top, we require `consecutiveSpeechFrames` successive
 *    post-library `true` returns before [VadResult.isSpeech] flips to `true`.
 *    This is the "2–3 consecutive speech frames" debouncer from the plan.
 *
 * The two layers compose: library filters per-frame noise, ours defends
 * against short false-positive bursts (~40 ms) that slip through.
 *
 * ### Sensitivity semantics
 *
 * [BargeInSensitivity.Off] short-circuits: [analyze] always returns
 * `isSpeech=false` without touching the model. Useful as a "disable without
 * flipping the master enabled toggle" UI affordance.
 */
class VadEngine @VisibleForTesting internal constructor(
    private val client: VadClient,
    sampleRate: Int,
) {

    /**
     * Production constructor. Builds a real Silero-backed [VadClient].
     *
     * @param sampleRate currently pinned to 16000 — other rates are not
     *   supported by this engine (and the plan standardises on 16 kHz mic
     *   capture in B3).
     */
    constructor(context: Context, sampleRate: Int = 16_000) : this(
        client = SileroVadClient(context.applicationContext, sampleRate.toSampleRate()),
        sampleRate = sampleRate,
    )

    init {
        require(sampleRate == 16_000) {
            "VadEngine currently supports only 16 kHz sample rate; got $sampleRate"
        }
    }

    @Volatile
    private var sensitivity: BargeInSensitivity = BargeInSensitivity.Default

    @Volatile
    private var profile: SensitivityProfile = SENSITIVITY_PROFILES.getValue(BargeInSensitivity.Default)
        .also { client.applyProfile(it) }

    // Rolling counters for the second-layer "N consecutive speech frames"
    // hysteresis. These are touched only from [analyze], which callers drive
    // single-threaded from B3's audio-read loop, so no synchronization is
    // required beyond reading the latest [profile] volatile.
    private var consecutiveSpeechCount: Int = 0
    private var debounced: Boolean = false

    /**
     * Analyze one frame of 16-bit PCM audio.
     *
     * @param frame exactly [FRAME_SIZE_SAMPLES] (512) samples at 16 kHz. Any
     *   other size is rejected by the Silero backend.
     * @return a [VadResult] whose [VadResult.isSpeech] incorporates both the
     *   library's internal attack/release and our N-consecutive debouncer;
     *   [VadResult.probability] is a coarse 0f/1f signal derived from the
     *   pre-debounce library decision (Silero's public API exposes only the
     *   boolean, not the raw confidence).
     */
    fun analyze(frame: ShortArray): VadResult {
        if (sensitivity == BargeInSensitivity.Off) {
            return VadResult.NOT_SPEECH
        }

        val rawSpeech = client.isSpeech(frame)

        if (rawSpeech) {
            if (consecutiveSpeechCount < profile.consecutiveSpeechFrames) {
                consecutiveSpeechCount++
            }
            if (consecutiveSpeechCount >= profile.consecutiveSpeechFrames) {
                debounced = true
            }
        } else {
            consecutiveSpeechCount = 0
            debounced = false
        }

        return if (debounced) {
            VadResult(isSpeech = true, probability = 1f)
        } else {
            VadResult(isSpeech = false, probability = if (rawSpeech) 1f else 0f)
        }
    }

    /**
     * Apply a sensitivity preset. Updates the library's attack/release
     * durations and our debouncer's `consecutive` count. Safe to call from
     * the UI thread; takes effect on the next [analyze].
     */
    fun setSensitivity(sensitivity: BargeInSensitivity) {
        this.sensitivity = sensitivity
        val newProfile = SENSITIVITY_PROFILES.getValue(sensitivity)
        profile = newProfile
        client.applyProfile(newProfile)
        // Reset the second-layer debouncer so a sensitivity change doesn't
        // latch a stale speech count from the previous profile.
        consecutiveSpeechCount = 0
        debounced = false
    }

    /** Release the underlying ONNX session and native resources. */
    fun close() {
        client.close()
    }

    companion object {
        /** Samples per analyze-call at 16 kHz (32 ms). */
        const val FRAME_SIZE_SAMPLES: Int = 512

        /**
         * Sensitivity → `(libraryMode, speechDurationMs, silenceDurationMs,
         * consecutiveSpeechFrames)` map. Tunings come from the B2 unit spec
         * in `docs/plans/2026-04-17-voice-barge-in.md`.
         *
         * The Silero library does not accept an arbitrary threshold float
         * — it hardcodes one per [Mode]. So we lean on [Mode] for threshold
         * and use `speech/silenceDurationMs` for the library-layer
         * attack/release, with our own `consecutiveSpeechFrames` for the
         * second-layer debouncer.
         *
         * Mode mapping (more aggressive = lower threshold = more sensitive):
         *   - [BargeInSensitivity.Low]  → [Mode.VERY_AGGRESSIVE] (high thr)
         *   - [BargeInSensitivity.Default] → [Mode.AGGRESSIVE]
         *   - [BargeInSensitivity.High] → [Mode.NORMAL] (lowest thr)
         *
         * NOTE: "aggressive" in the Silero library refers to how aggressively
         * it rejects non-speech (higher threshold), so Low-sensitivity UX
         * maps to the MORE aggressive library mode.
         */
        internal val SENSITIVITY_PROFILES: Map<BargeInSensitivity, SensitivityProfile> = mapOf(
            BargeInSensitivity.Off to SensitivityProfile(
                mode = Mode.VERY_AGGRESSIVE,
                attackMs = 0,
                releaseMs = 0,
                consecutiveSpeechFrames = Int.MAX_VALUE,
            ),
            BargeInSensitivity.Low to SensitivityProfile(
                mode = Mode.VERY_AGGRESSIVE,
                attackMs = 80,
                releaseMs = 300,
                consecutiveSpeechFrames = 3,
            ),
            BargeInSensitivity.Default to SensitivityProfile(
                mode = Mode.AGGRESSIVE,
                attackMs = 50,
                releaseMs = 250,
                consecutiveSpeechFrames = 2,
            ),
            BargeInSensitivity.High to SensitivityProfile(
                mode = Mode.NORMAL,
                attackMs = 30,
                releaseMs = 200,
                consecutiveSpeechFrames = 1,
            ),
        )
    }

    /**
     * Internal seam over the Silero library so unit tests can replace the
     * native ONNX-backed client with a deterministic fake. Not exposed
     * publicly — callers always go through [VadEngine].
     */
    internal interface VadClient {
        fun isSpeech(frame: ShortArray): Boolean
        fun applyProfile(profile: SensitivityProfile)
        fun close()
    }

    internal data class SensitivityProfile(
        val mode: Mode,
        val attackMs: Int,
        val releaseMs: Int,
        val consecutiveSpeechFrames: Int,
    )

    private class SileroVadClient(
        context: Context,
        sampleRate: SampleRate,
    ) : VadClient {
        // Built lazily with a default profile so construction doesn't race
        // with an initial [applyProfile] call from [VadEngine.init].
        private val vad: VadSilero = VadSilero(
            context = context,
            sampleRate = sampleRate,
            frameSize = FrameSize.FRAME_SIZE_512,
            mode = Mode.AGGRESSIVE,
            speechDurationMs = 50,
            silenceDurationMs = 250,
        )

        override fun isSpeech(frame: ShortArray): Boolean = vad.isSpeech(frame)

        override fun applyProfile(profile: SensitivityProfile) {
            vad.mode = profile.mode
            vad.speechDurationMs = profile.attackMs
            vad.silenceDurationMs = profile.releaseMs
        }

        override fun close() {
            vad.close()
        }
    }
}

/**
 * Result of a single [VadEngine.analyze] call.
 *
 * [isSpeech] is the post-debounce decision callers should act on.
 * [probability] is a best-effort confidence hint — Silero's public API
 * exposes only a boolean, so we surface a coarse 0f/1f until we swap to a
 * backend that gives us the raw score.
 */
data class VadResult(
    val isSpeech: Boolean,
    val probability: Float,
) {
    companion object {
        internal val NOT_SPEECH = VadResult(isSpeech = false, probability = 0f)
    }
}

private fun Int.toSampleRate(): SampleRate = when (this) {
    8_000 -> SampleRate.SAMPLE_RATE_8K
    16_000 -> SampleRate.SAMPLE_RATE_16K
    else -> error("Unsupported sample rate: $this")
}
