package com.hermesandroid.relay.audio

import kotlin.math.ceil
import kotlin.math.roundToInt
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
    private val ambient = ArrayDeque<Float>(MAX_AMBIENT_FRAMES)
    private val decisions = ArrayDeque<Boolean>(decisionWindowFrames)

    private var quietFloor: Float = DEFAULT_QUIET_FLOOR_RMS
    private var calibrated = false
    private var playbackActive = false
    private var playbackStartedAtMs: Long? = null
    private var playbackStoppedAtMs: Long? = null

    var thresholdMultiplier: Float = DEFAULT_THRESHOLD_MULTIPLIER
        set(value) {
            field = value.coerceIn(MIN_THRESHOLD_MULTIPLIER, MAX_THRESHOLD_MULTIPLIER)
        }

    fun reset() {
        ambient.clear()
        decisions.clear()
        quietFloor = DEFAULT_QUIET_FLOOR_RMS
        calibrated = false
        playbackActive = false
        playbackStartedAtMs = null
        playbackStoppedAtMs = null
    }

    fun markPlaybackStarted(nowMs: Long) {
        updatePlaybackPhase(active = true, nowMs = nowMs)
    }

    fun observe(
        frame: ShortArray,
        rawSpeech: Boolean,
        nowMs: Long,
        playbackGraceMs: Long,
        confirmedSpeech: Boolean = rawSpeech,
        playbackActiveOverride: Boolean? = null,
    ): RmsGateResult {
        val rms = rms(frame)
        playbackActiveOverride?.let { reportedActive ->
            // A renderer marks playback just before its first write so speaker
            // output cannot enter calibration. Do not let a provider that has
            // not observed the first audible frame yet undo that protection
            // during the configured grace window.
            val withinStartupGrace = playbackActive && playbackStartedAtMs?.let {
                nowMs - it < playbackGraceMs
            } == true
            if (reportedActive || !withinStartupGrace) {
                updatePlaybackPhase(active = reportedActive, nowMs = nowMs)
            }
        }
        val playback = playbackActive
        var justCalibrated = false

        if (!playback && !calibrated) {
            addAmbient(rms)
            if (ambient.size >= calibrationFrames) {
                freezeCalibration()
                justCalibrated = true
            }
        }

        if (!playback && (!calibrated || justCalibrated)) {
            return RmsGateResult(
                maybeSpeech = false,
                detected = false,
                rms = rms,
                floor = quietFloor,
                threshold = (quietFloor * thresholdMultiplier).coerceIn(
                    MIN_GENERATION_THRESHOLD_RMS,
                    MAX_THRESHOLD_RMS,
                ),
                calibrating = !calibrated,
                playbackGrace = false,
                playback = false,
            )
        }

        var threshold = if (playback) {
            (quietFloor * thresholdMultiplier).coerceIn(
                MIN_PLAYBACK_THRESHOLD_RMS,
                MAX_THRESHOLD_RMS,
            )
        } else {
            (quietFloor * thresholdMultiplier).coerceIn(
                MIN_GENERATION_THRESHOLD_RMS,
                MAX_THRESHOLD_RMS,
            )
        }

        // Match upstream ambient drift: after initial calibration, keep the
        // 90th-percentile floor current only while the room is quiet and no
        // playback can contaminate it.
        if (!playback && calibrated && !justCalibrated && rms < threshold) {
            addAmbient(rms)
            quietFloor = robustFloor(ambient)
            threshold = (quietFloor * thresholdMultiplier).coerceIn(
                MIN_GENERATION_THRESHOLD_RMS,
                MAX_THRESHOLD_RMS,
            )
        }
        val inPlaybackGrace = playbackStartedAtMs?.let { nowMs - it < playbackGraceMs } == true
        val aboveRaw = rawSpeech && rms >= threshold && !inPlaybackGrace
        val aboveConfirmed = confirmedSpeech && rms >= threshold && !inPlaybackGrace

        decisions.addLast(aboveConfirmed)
        while (decisions.size > decisionWindowFrames) decisions.removeFirst()
        val required = (decisionWindowFrames * requiredWindowRatio).roundToInt().coerceAtLeast(1)
        val detected = aboveConfirmed && decisions.count { it } >= required

        return RmsGateResult(
            maybeSpeech = aboveRaw,
            detected = detected,
            rms = rms,
            floor = quietFloor,
            threshold = threshold,
            calibrating = !playback && !calibrated,
            playbackGrace = inPlaybackGrace,
            playback = playback,
        )
    }

    private fun freezeCalibration() {
        if (!calibrated) {
            quietFloor = robustFloor(ambient)
            calibrated = true
        }
    }

    private fun updatePlaybackPhase(active: Boolean, nowMs: Long) {
        if (active == playbackActive) return
        if (active) {
            freezeCalibration()
            val gapMs = playbackStoppedAtMs?.let { nowMs - it }
            playbackStartedAtMs = if (gapMs == null || gapMs >= PLAYBACK_GRACE_REARM_GAP_MS) {
                nowMs
            } else {
                null
            }
            playbackActive = true
            decisions.clear()
        } else {
            playbackActive = false
            playbackStartedAtMs = null
            playbackStoppedAtMs = nowMs
            decisions.clear()
        }
    }

    private fun addAmbient(rms: Float) {
        ambient.addLast(rms)
        while (ambient.size > MAX_AMBIENT_FRAMES) ambient.removeFirst()
    }

    private fun robustFloor(values: Collection<Float>): Float {
        if (values.isEmpty()) return DEFAULT_QUIET_FLOOR_RMS
        val sorted = values.sorted()
        val percentileIndex = (ceil(sorted.size * 0.9).toInt() - 1).coerceIn(sorted.indices)
        return sorted[percentileIndex].coerceAtLeast(MIN_QUIET_FLOOR_RMS)
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
        internal const val DEFAULT_CALIBRATION_FRAMES = 14
        internal const val DEFAULT_DECISION_WINDOW_FRAMES = 10
        internal const val DEFAULT_REQUIRED_WINDOW_RATIO = 0.8f
        internal const val MIN_PLAYBACK_THRESHOLD_RMS = 1_500f
        internal const val MAX_THRESHOLD_RMS = 4_000f
        internal const val MIN_GENERATION_THRESHOLD_RMS = 400f
        internal const val DEFAULT_QUIET_FLOOR_RMS = 200f
        internal const val MIN_QUIET_FLOOR_RMS = 200f
        internal const val MAX_AMBIENT_FRAMES = 100
        internal const val PLAYBACK_GRACE_REARM_GAP_MS = 1_000L
        internal const val MIN_THRESHOLD_MULTIPLIER = 1f
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
    val playback: Boolean,
)
