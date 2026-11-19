package com.hermesandroid.relay.viewmodel

import kotlin.math.max

/**
 * Endpoint detection for hands-free Continuous voice capture.
 *
 * VoiceRecorder.amplitude is a perceptually scaled *peak* meter, not a speech
 * probability. A steady fan/road/noise floor can therefore exceed the stock
 * fixed 0.08 cutoff indefinitely (hermes-relay #645). Estimate the lower
 * quartile of recent peak levels as the background baseline. Only sustained
 * excursions above that baseline count as speech; ambient sound after the
 * utterance can then count as silence even when its peak exceeds 0.08.
 *
 * This does not record, log or transmit audio. The amplitude history is
 * bounded to a few seconds and discarded with each turn. Tap/Hold and barge-in
 * interruption retain their existing behaviour.
 */
internal class AdaptiveContinuousSilenceGate(
    private val silenceMs: Long,
    private val idleNoSpeechMs: Long,
    private val hardCapMs: Long,
) {
    enum class Outcome { Continue, EndOfSpeech, IdleNoSpeech, HardCap }

    private val history = ArrayDeque<Float>(20)
    private var startedAt: Long? = null
    private var lastSpeechAt: Long? = null
    private var consecutiveVoiceFrames = 0
    private var confirmedSpeechPeak = 0f

    internal var lastBackgroundFloor: Float = 0f
        private set
    internal var lastSpeechThreshold: Float = 0f
        private set

    fun observe(amplitude: Float, nowMs: Long): Outcome {
        if (startedAt == null) startedAt = nowMs
        val start = checkNotNull(startedAt)
        val level = amplitude.coerceIn(0f, 1f)

        // Calculate from PRIOR frames. In particular, a first syllable should
        // not instantly calibrate itself as background sound.
        val floor = if (history.isEmpty()) 0.06f else {
            val sorted = history.sorted()
            sorted[(sorted.size - 1) / 4]
        }
        lastBackgroundFloor = floor
        // The lower bound keeps quiet-room sensitivity similar to stock.
        // The adaptive margin prevents steady ambient noise from being
        // classified as perpetual speech on devices with strong mic gain.
        val speechThreshold = max(0.11f, floor + max(0.05f, floor * 0.22f))
        // Once speech is established, its peak provides a second reference
        // for the eventual drop back to room tone. Without this, a quiet room
        // can abruptly become noisy after speech and lock the turn open.
        val activeThreshold = if (lastSpeechAt == null) speechThreshold else {
            max(speechThreshold, confirmedSpeechPeak * 0.58f)
        }
        lastSpeechThreshold = activeThreshold

        if (level >= activeThreshold) {
            consecutiveVoiceFrames++
            // An isolated impulse / transient must not start or prolong a turn.
            if (consecutiveVoiceFrames >= 2) {
                lastSpeechAt = nowMs
                confirmedSpeechPeak = max(confirmedSpeechPeak, level)
            }
        } else {
            consecutiveVoiceFrames = 0
        }

        // Calibrate from pre-speech or likely ambient samples only. Including
        // long sustained speech in the noise history would eventually raise
        // the baseline up to speech level and cut an unfinished sentence off.
        if (lastSpeechAt == null || level < activeThreshold) {
            history.addLast(level)
            if (history.size > 20) history.removeFirst()
        }

        // Keep the hard cap even if the recorder produces a constant clipped
        // stream or the silence duration is set to a large value.
        if (nowMs - start >= hardCapMs) return Outcome.HardCap
        val spokenAt = lastSpeechAt
        if (spokenAt != null) {
            if (nowMs - spokenAt >= silenceMs) return Outcome.EndOfSpeech
        } else if (nowMs - start >= idleNoSpeechMs) {
            return Outcome.IdleNoSpeech
        }
        return Outcome.Continue
    }
}
