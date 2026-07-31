package com.hermesandroid.relay.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RmsBargeInGateTest {
    @Test
    fun `quiet calibration is frozen before playback and speaker bleed alone does not trip`() {
        val gate = RmsBargeInGate()
        repeat(14) { gate.observe(frame(200), rawSpeech = false, nowMs = it * 32L, playbackGraceMs = 500) }

        gate.markPlaybackStarted(400)
        repeat(20) { index ->
            val result = gate.observe(
                frame = frame(1_200),
                rawSpeech = true,
                nowMs = 1_000L + index * 32L,
                playbackGraceMs = 500,
            )
            assertFalse(result.detected)
        }
    }

    @Test
    fun `speech over playback crosses held floor after grace and majority window`() {
        val gate = RmsBargeInGate()
        repeat(14) { gate.observe(frame(250), rawSpeech = false, nowMs = it * 32L, playbackGraceMs = 500) }
        gate.markPlaybackStarted(500)

        var detected = false
        repeat(10) { index ->
            detected = gate.observe(
                frame = frame(if (index == 4) 1_000 else 3_000),
                rawSpeech = index != 4,
                nowMs = 1_000L + index * 32L,
                playbackGraceMs = 500,
            ).detected
        }

        assertTrue(detected)
    }

    @Test
    fun `higher multiplier is stricter`() {
        val eager = RmsBargeInGate().apply { thresholdMultiplier = 2f }
        val strict = RmsBargeInGate().apply { thresholdMultiplier = 8f }
        repeat(14) {
            eager.observe(frame(500), false, it * 32L, 0)
            strict.observe(frame(500), false, it * 32L, 0)
        }

        var eagerDetected = false
        var strictDetected = false
        repeat(10) { index ->
            eagerDetected = eager.observe(frame(1_500), true, 500L + index * 32L, 0).detected
            strictDetected = strict.observe(frame(1_500), true, 500L + index * 32L, 0).detected
        }

        assertTrue(eagerDetected)
        assertFalse(strictDetected)
    }

    @Test
    fun `playback grace suppresses early detection`() {
        val gate = RmsBargeInGate()
        repeat(14) { gate.observe(frame(100), false, it * 32L, 500) }
        gate.markPlaybackStarted(500)

        repeat(12) { index ->
            val result = gate.observe(frame(4_000), true, 410L + index * 32L, 500)
            assertFalse(result.detected)
        }
    }

    @Test
    fun `calibration frames never trigger interruption`() {
        val gate = RmsBargeInGate()

        repeat(13) { index ->
            val result = gate.observe(
                frame = frame(4_000),
                rawSpeech = true,
                confirmedSpeech = true,
                nowMs = index * 32L,
                playbackGraceMs = 500L,
            )
            assertFalse(result.maybeSpeech)
            assertFalse(result.detected)
            assertTrue(result.calibrating)
        }
        val finalCalibrationFrame = gate.observe(
            frame = frame(4_000),
            rawSpeech = true,
            confirmedSpeech = true,
            nowMs = 13 * 32L,
            playbackGraceMs = 500L,
        )
        assertFalse(finalCalibrationFrame.maybeSpeech)
        assertFalse(finalCalibrationFrame.detected)
        assertFalse(finalCalibrationFrame.calibrating)
    }

    @Test
    fun `playback phase returns to generation and long gaps rearm grace`() {
        val gate = RmsBargeInGate()
        repeat(14) { gate.observe(frame(200), false, it * 32L, 500L) }

        gate.markPlaybackStarted(500L)
        val playback = gate.observe(
            frame(2_000),
            rawSpeech = false,
            nowMs = 1_100L,
            playbackGraceMs = 500L,
            playbackActiveOverride = false,
        )
        assertFalse(playback.playback)
        assertTrue(playback.threshold < RmsBargeInGate.MIN_PLAYBACK_THRESHOLD_RMS)

        val shortGapRestart = gate.observe(
            frame(2_000),
            rawSpeech = true,
            nowMs = 1_500L,
            playbackGraceMs = 500L,
            playbackActiveOverride = true,
        )
        assertFalse(shortGapRestart.playbackGrace)

        gate.observe(frame(200), false, 1_600L, 500L, playbackActiveOverride = false)
        val longGapRestart = gate.observe(
            frame(2_000),
            rawSpeech = true,
            nowMs = 2_700L,
            playbackGraceMs = 500L,
            playbackActiveOverride = true,
        )
        assertTrue(longGapRestart.playbackGrace)
    }

    @Test
    fun `calibration uses upstream 90th percentile and phase clamps`() {
        val gate = RmsBargeInGate()
        repeat(12) { gate.observe(frame(100), false, it * 32L, 0) }
        gate.observe(frame(300), false, 384L, 0)
        val calibrated = gate.observe(frame(1_000), false, 416L, 0)

        assertTrue(calibrated.floor >= 300f)
        assertTrue(calibrated.threshold >= 900f)

        gate.markPlaybackStarted(448L)
        val playback = gate.observe(frame(1_000), true, 1_000L, 0)
        assertTrue(playback.threshold >= 1_500f)
        assertTrue(playback.threshold <= 4_000f)
    }

    private fun frame(amplitude: Int): ShortArray =
        ShortArray(VadEngine.FRAME_SIZE_SAMPLES) { amplitude.toShort() }
}
