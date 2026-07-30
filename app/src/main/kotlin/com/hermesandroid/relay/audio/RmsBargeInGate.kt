package com.hermesandroid.relay.audio

import kotlin.math.sqrt

/**
 * Turn-scoped RMS gate layered in front of the model VAD.
 *
 * The first quiet frames establish a room floor before playback. That floor is
 * frozen as soon as playback begins so speaker output can never teach the gate
 * to ignore the user. Detection uses a majority window rather than requiring
 * perfectly consecutive frames, which tolerates short consonant/syllable dips.
 */
internal class RmsBargeInGate(
    private val calibrationFrames: Int = DEFAULT_CALIBRATION_FRAMES,
    private val decisionWindowFrames: Int = DEFAULT_DECISION_WINDOW_FRAMES,
    private val requiredWindowRatio: Float = DEFAULT_REQUIRED_WINDOW_RATIO,
) {
    private val calibration = ArrayList<Float>(calibrationFrames)
    private val decisions = ArrayDeque<Boolean>(decisionWindowFrames)

    private var frozenFloor: Float? = null
    private var playbackStartedAtMs: Long? = null

    var thresholdMultiplier: Float = DEFAULT_THRESHOLD_MULTIPLIER
        set(value) {
            field = value.coerceIn(MIN_THRESHOLD_MULTIPLIER, MAX_THRESHOLD_MULTIPLIER)
        }

    fun reset() {
        calibration.clear()
        decisions.clear()
        frozenFloor = null
        playbackStartedAtMs = null
    }

    fun markPlaybackStarted(nowMs: Long) {
        if (playbackStartedAtMs == null) {
            freezeCalibration()
            playbackStartedAtMs = nowMs
            decisions.clear()
        }
    }

    fun observe(
        frame: ShortArray,
        rawSpeech: Boolean,
        nowMs: Long,
        playbackGraceMs: Long,
        confirmedSpeech: Boolean = rawSpeech,
    ): RmsGateResult {
        val rms = rms(frame)
        val playback = playbackStartedAtMs != null

        if (!playback && frozenFloor == null && calibration.size < calibrationFrames) {
            calibration += rms
            if (calibration.size == calibrationFrames) freezeCalibration()
        }

        val floor = frozenFloor ?: robustFloor(calibration)
        val scaled = (floor * thresholdMultiplier).coerceAtLeast(MIN_GENERATION_THRESHOLD_RMS)
        val threshold = if (playback) {
            scaled.coerceIn(MIN_PLAYBACK_THRESHOLD_RMS, MAX_PLAYBACK_THRESHOLD_RMS)
        } else {
            scaled.coerceAtMost(MAX_PLAYBACK_THRESHOLD_RMS)
        }
        val inPlaybackGrace = playbackStartedAtMs?.let { nowMs - it < playbackGraceMs } == true
        val aboveRaw = rawSpeech && rms >= threshold && !inPlaybackGrace
        val aboveConfirmed = confirmedSpeech && rms >= threshold && !inPlaybackGrace

        decisions.addLast(aboveConfirmed)
        while (decisions.size > decisionWindowFrames) decisions.removeFirst()
        val required = (decisionWindowFrames * requiredWindowRatio).toInt()
        val detected = decisions.size == decisionWindowFrames && decisions.count { it } >= required

        return RmsGateResult(
            maybeSpeech = aboveRaw,
            detected = detected,
            rms = rms,
            floor = floor,
            threshold = threshold,
            calibrating = !playback && frozenFloor == null,
            playbackGrace = inPlaybackGrace,
        )
    }

    private fun freezeCalibration() {
        if (frozenFloor == null) {
            frozenFloor = robustFloor(calibration)
        }
    }

    private fun robustFloor(values: List<Float>): Float {
        if (values.isEmpty()) return DEFAULT_QUIET_FLOOR_RMS
        // Use the quieter half so a cough or chair noise during turn setup
        // cannot permanently deafen the listener.
        val sorted = values.sorted()
        val quietHalf = sorted.take((sorted.size / 2).coerceAtLeast(1))
        return quietHalf.average().toFloat().coerceAtLeast(MIN_QUIET_FLOOR_RMS)
    }

    private fun rms(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        var sum = 0.0
        frame.forEach { sample ->
            val value = sample.toDouble()
            sum += value * value
        }
        return sqrt(sum / frame.size).toFloat()
    }

    companion object {
        const val DEFAULT_THRESHOLD_MULTIPLIER = 3f
        const val DEFAULT_PLAYBACK_GRACE_MS = 500L
        internal const val DEFAULT_CALIBRATION_FRAMES = 10
        internal const val DEFAULT_DECISION_WINDOW_FRAMES = 10
        internal const val DEFAULT_REQUIRED_WINDOW_RATIO = 0.8f
        internal const val MIN_PLAYBACK_THRESHOLD_RMS = 1_500f
        internal const val MAX_PLAYBACK_THRESHOLD_RMS = 4_000f
        internal const val MIN_GENERATION_THRESHOLD_RMS = 300f
        internal const val DEFAULT_QUIET_FLOOR_RMS = 500f
        internal const val MIN_QUIET_FLOOR_RMS = 50f
        internal const val MIN_THRESHOLD_MULTIPLIER = 1.5f
        internal const val MAX_THRESHOLD_MULTIPLIER = 8f
    }
}

internal data class RmsGateResult(
    val maybeSpeech: Boolean,
    val detected: Boolean,
    val rms: Float,
    val floor: Float,
    val threshold: Float,
    val calibrating: Boolean,
    val playbackGrace: Boolean,
)
