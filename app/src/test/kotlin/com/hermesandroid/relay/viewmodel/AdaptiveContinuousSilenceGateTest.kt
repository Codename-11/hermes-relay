package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveContinuousSilenceGateTest {
    private fun gate() = AdaptiveContinuousSilenceGate(
        silenceMs = 1250,
        idleNoSpeechMs = 12_000,
        hardCapMs = 60_000,
    )

    private fun feed(
        gate: AdaptiveContinuousSilenceGate,
        startMs: Long,
        frames: Int,
        amplitude: Float,
    ): List<AdaptiveContinuousSilenceGate.Outcome> = (0 until frames).map { tick ->
        gate.observe(amplitude, startMs + tick * 150L)
    }

    @Test fun quietRoomSpeechEndsAfterConfiguredSilence() {
        val gate = gate()
        assertEquals(AdaptiveContinuousSilenceGate.Outcome.Continue, feed(gate, 0, 5, 0.025f).last())
        assertEquals(AdaptiveContinuousSilenceGate.Outcome.Continue, feed(gate, 750, 5, 0.45f).last())
        val quiet = feed(gate, 1500, 12, 0.025f)
        assertTrue(quiet.contains(AdaptiveContinuousSilenceGate.Outcome.EndOfSpeech))
    }

    @Test fun steadyFanAboveOldFixedThresholdStillEndsAfterSpeech() {
        val gate = gate()
        val noise = feed(gate, 0, 12, 0.23f)
        assertTrue(noise.all { it == AdaptiveContinuousSilenceGate.Outcome.Continue })
        val speech = feed(gate, 1800, 9, 0.56f)
        assertTrue(speech.all { it == AdaptiveContinuousSilenceGate.Outcome.Continue })
        val after = feed(gate, 3150, 20, 0.23f)
        assertTrue("Background noise must not prevent end of turn", after.contains(AdaptiveContinuousSilenceGate.Outcome.EndOfSpeech))
    }

    @Test fun stableLoudBackgroundWithoutSpeechDoesNotSubmitNoise() {
        val gate = gate()
        val outcomes = feed(gate, 0, 85, 0.24f)
        assertTrue(outcomes.none { it == AdaptiveContinuousSilenceGate.Outcome.EndOfSpeech })
        assertEquals(AdaptiveContinuousSilenceGate.Outcome.IdleNoSpeech, outcomes[80])
    }

    @Test fun isolatedPeakIsNotSpeech() {
        val gate = gate()
        feed(gate, 0, 8, 0.03f)
        assertEquals(AdaptiveContinuousSilenceGate.Outcome.Continue, gate.observe(0.85f, 1200))
        val after = feed(gate, 1350, 80, 0.03f)
        assertTrue(after.none { it == AdaptiveContinuousSilenceGate.Outcome.EndOfSpeech })
        assertTrue(after.contains(AdaptiveContinuousSilenceGate.Outcome.IdleNoSpeech))
    }

    @Test fun speakingWithBriefPausesDoesNotSubmitPrematurely() {
        val gate = gate()
        feed(gate, 0, 6, 0.04f)
        feed(gate, 900, 6, 0.44f)
        assertTrue(feed(gate, 1800, 5, 0.04f).all { it == AdaptiveContinuousSilenceGate.Outcome.Continue })
        assertTrue(feed(gate, 2550, 7, 0.41f).all { it == AdaptiveContinuousSilenceGate.Outcome.Continue })
        assertTrue(feed(gate, 3600, 13, 0.04f).contains(AdaptiveContinuousSilenceGate.Outcome.EndOfSpeech))
    }

    @Test fun longUninterruptedSpeechIsNeverMisclassifiedAsBackground() {
        val gate = gate()
        feed(gate, 0, 6, 0.035f)
        val spoken = feed(gate, 900, 160, 0.46f)
        assertTrue("Continuous speech must not become the noise baseline", spoken.all {
            it == AdaptiveContinuousSilenceGate.Outcome.Continue
        })
        assertTrue(feed(gate, 24_900, 12, 0.035f).contains(
            AdaptiveContinuousSilenceGate.Outcome.EndOfSpeech,
        ))
    }

    @Test fun quietRoomThenHigherRoomNoiseStillEndsAfterSpeech() {
        val gate = gate()
        feed(gate, 0, 8, 0.03f)
        feed(gate, 1200, 8, 0.53f)
        val after = feed(gate, 2400, 20, 0.22f)
        assertTrue("Lower-level noise after loud speech must not hold the mic open",
            after.contains(AdaptiveContinuousSilenceGate.Outcome.EndOfSpeech))
    }

    @Test fun hardCapAppliesEvenWithNeverEndingSpeech() {
        val gate = gate()
        feed(gate, 0, 5, 0.03f)
        feed(gate, 750, 7, 0.6f)
        assertEquals(AdaptiveContinuousSilenceGate.Outcome.HardCap, gate.observe(0.6f, 60_000))
    }
}
