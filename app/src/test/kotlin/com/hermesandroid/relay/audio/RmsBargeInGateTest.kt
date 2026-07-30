package com.hermesandroid.relay.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RmsBargeInGateTest {
    @Test
    fun `quiet calibration is frozen before playback and speaker bleed alone does not trip`() {
        val gate = RmsBargeInGate()
        repeat(10) { gate.observe(frame(200), rawSpeech = false, nowMs = it * 32L, playbackGraceMs = 500) }

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
        repeat(10) { gate.observe(frame(250), rawSpeech = false, nowMs = it * 32L, playbackGraceMs = 500) }
        gate.markPlaybackStarted(400)

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
        repeat(10) {
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
        repeat(10) { gate.observe(frame(100), false, it * 32L, 500) }
        gate.markPlaybackStarted(400)

        repeat(12) { index ->
            val result = gate.observe(frame(4_000), true, 410L + index * 32L, 500)
            assertFalse(result.detected)
        }
    }

    private fun frame(amplitude: Int): ShortArray =
        ShortArray(VadEngine.FRAME_SIZE_SAMPLES) { amplitude.toShort() }
}
