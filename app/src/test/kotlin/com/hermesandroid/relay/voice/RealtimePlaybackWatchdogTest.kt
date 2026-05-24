package com.hermesandroid.relay.voice

import com.hermesandroid.relay.viewmodel.evaluateFirstFrameWatchdog
import com.hermesandroid.relay.viewmodel.playbackDrainDrift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimePlaybackWatchdogTest {

    // ---- evaluateFirstFrameWatchdog (#1) ----

    @Test
    fun `stops watching when track is gone`() {
        val d = evaluateFirstFrameWatchdog(
            active = false,
            playbackStarted = false,
            headFrames = 0,
            elapsedSinceStartMs = 0,
            playStatePlaying = false,
            alreadyReportedStuck = false,
            stuckThresholdMs = 1_200,
        )
        assertNull(d.firstAudioMs)
        assertFalse(d.reportStuck)
        assertFalse(d.keepWatching)
    }

    @Test
    fun `keeps watching before playback starts`() {
        val d = evaluateFirstFrameWatchdog(
            active = true,
            playbackStarted = false,
            headFrames = 0,
            elapsedSinceStartMs = 0,
            playStatePlaying = false,
            alreadyReportedStuck = false,
            stuckThresholdMs = 1_200,
        )
        assertNull(d.firstAudioMs)
        assertFalse(d.reportStuck)
        assertTrue(d.keepWatching)
    }

    @Test
    fun `reports time-to-first-audio once the cursor advances`() {
        val d = evaluateFirstFrameWatchdog(
            active = true,
            playbackStarted = true,
            headFrames = 240,
            elapsedSinceStartMs = 180,
            playStatePlaying = true,
            alreadyReportedStuck = false,
            stuckThresholdMs = 1_200,
        )
        assertEquals(180L, d.firstAudioMs)
        assertFalse(d.reportStuck)
        assertFalse(d.keepWatching)
    }

    @Test
    fun `flags a parked cursor after the stuck threshold`() {
        val d = evaluateFirstFrameWatchdog(
            active = true,
            playbackStarted = true,
            headFrames = 0,
            elapsedSinceStartMs = 1_300,
            playStatePlaying = true,
            alreadyReportedStuck = false,
            stuckThresholdMs = 1_200,
        )
        assertNull(d.firstAudioMs)
        assertTrue(d.reportStuck)
        assertTrue(d.keepWatching)
    }

    @Test
    fun `does not flag a parked cursor before the threshold`() {
        val d = evaluateFirstFrameWatchdog(
            active = true,
            playbackStarted = true,
            headFrames = 0,
            elapsedSinceStartMs = 400,
            playStatePlaying = true,
            alreadyReportedStuck = false,
            stuckThresholdMs = 1_200,
        )
        assertFalse(d.reportStuck)
        assertTrue(d.keepWatching)
    }

    @Test
    fun `reports the parked cursor only once`() {
        val d = evaluateFirstFrameWatchdog(
            active = true,
            playbackStarted = true,
            headFrames = 0,
            elapsedSinceStartMs = 2_000,
            playStatePlaying = true,
            alreadyReportedStuck = true,
            stuckThresholdMs = 1_200,
        )
        assertFalse(d.reportStuck)
        assertTrue(d.keepWatching)
    }

    @Test
    fun `does not blame a paused track for missing audio`() {
        val d = evaluateFirstFrameWatchdog(
            active = true,
            playbackStarted = true,
            headFrames = 0,
            elapsedSinceStartMs = 2_000,
            playStatePlaying = false,
            alreadyReportedStuck = false,
            stuckThresholdMs = 1_200,
        )
        assertFalse(d.reportStuck)
        assertTrue(d.keepWatching)
    }

    // ---- playbackDrainDrift (#3) ----

    @Test
    fun `estimate matching real head has no early-stop risk`() {
        // 24kHz, 12000 frames still queued = 500ms; estimate agrees.
        val drift = playbackDrainDrift(
            estimatedRemainingMs = 500,
            framesWritten = 24_000,
            headFrames = 12_000,
            sampleRate = 24_000,
        )
        assertEquals(500L, drift.actualRemainingMs)
        assertEquals(500L, drift.effectiveRemainingMs)
        assertEquals(0L, drift.driftMs)
        assertFalse(drift.earlyStopRisk)
    }

    @Test
    fun `estimate of zero while frames remain flags early-stop risk`() {
        // Byte-math estimate says done, but 24000 frames (1s) are still queued.
        val drift = playbackDrainDrift(
            estimatedRemainingMs = 0,
            framesWritten = 48_000,
            headFrames = 24_000,
            sampleRate = 24_000,
        )
        assertEquals(1_000L, drift.actualRemainingMs)
        assertEquals(1_000L, drift.effectiveRemainingMs)
        assertEquals(1_000L, drift.driftMs)
        assertTrue(drift.earlyStopRisk)
    }

    @Test
    fun `conservative estimate larger than real head wins without risk`() {
        val drift = playbackDrainDrift(
            estimatedRemainingMs = 800,
            framesWritten = 24_000,
            headFrames = 21_600, // 100ms remaining
            sampleRate = 24_000,
        )
        assertEquals(100L, drift.actualRemainingMs)
        assertEquals(800L, drift.effectiveRemainingMs)
        assertTrue(drift.driftMs < 0)
        assertFalse(drift.earlyStopRisk)
    }

    @Test
    fun `head past written frames clamps to zero remaining`() {
        val drift = playbackDrainDrift(
            estimatedRemainingMs = 0,
            framesWritten = 24_000,
            headFrames = 30_000,
            sampleRate = 24_000,
        )
        assertEquals(0L, drift.actualRemainingMs)
        assertEquals(0L, drift.effectiveRemainingMs)
        assertFalse(drift.earlyStopRisk)
    }

    @Test
    fun `unknown sample rate yields zero actual remaining`() {
        val drift = playbackDrainDrift(
            estimatedRemainingMs = 0,
            framesWritten = 48_000,
            headFrames = 0,
            sampleRate = 0,
        )
        assertEquals(0L, drift.actualRemainingMs)
        assertFalse(drift.earlyStopRisk)
    }
}
