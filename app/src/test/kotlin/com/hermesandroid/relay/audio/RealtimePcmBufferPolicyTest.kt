package com.hermesandroid.relay.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimePcmBufferPolicyTest {
    @Test
    fun `does not start on tiny first realtime chunk`() {
        val decision = RealtimePcmBufferPolicy.startDecision(
            pendingBytes = RealtimePcmBufferPolicy.bytesForDurationMs(24_000, 80),
            sampleRate = 24_000,
            waitedMs = 40,
            force = false,
        )

        assertFalse(decision.shouldStart)
        assertEquals("buffering", decision.reason)
    }

    @Test
    fun `starts after target prebuffer is available`() {
        val decision = RealtimePcmBufferPolicy.startDecision(
            pendingBytes = RealtimePcmBufferPolicy.bytesForDurationMs(
                24_000,
                RealtimePcmBufferPolicy.START_PREBUFFER_MS,
            ),
            sampleRate = 24_000,
            waitedMs = 120,
            force = false,
        )

        assertTrue(decision.shouldStart)
        assertEquals("prebuffer", decision.reason)
    }

    @Test
    fun `startup preroll plus first provider chunk waits for smooth prebuffer`() {
        val pendingBytes =
            RealtimePcmBufferPolicy.startupPrerollBytes(24_000) +
                RealtimePcmBufferPolicy.bytesForDurationMs(24_000, 180)

        val decision = RealtimePcmBufferPolicy.startDecision(
            pendingBytes = pendingBytes,
            sampleRate = 24_000,
            waitedMs = 0,
            force = false,
        )

        assertFalse(decision.shouldStart)
        assertEquals("buffering", decision.reason)
    }

    @Test
    fun `starts once smooth realtime prebuffer is available`() {
        val decision = RealtimePcmBufferPolicy.startDecision(
            pendingBytes = RealtimePcmBufferPolicy.bytesForDurationMs(
                24_000,
                RealtimePcmBufferPolicy.START_PREBUFFER_MS,
            ),
            sampleRate = 24_000,
            waitedMs = 250,
            force = false,
        )

        assertTrue(decision.shouldStart)
        assertEquals("prebuffer", decision.reason)
    }

    @Test
    fun `starts after max wait once minimum prebuffer is available`() {
        val decision = RealtimePcmBufferPolicy.startDecision(
            pendingBytes = RealtimePcmBufferPolicy.bytesForDurationMs(
                24_000,
                RealtimePcmBufferPolicy.MIN_PREBUFFER_MS,
            ),
            sampleRate = 24_000,
            waitedMs = RealtimePcmBufferPolicy.MAX_PREBUFFER_WAIT_MS,
            force = false,
        )

        assertTrue(decision.shouldStart)
        assertEquals("max-wait", decision.reason)
    }

    @Test
    fun `flush starts short buffered response`() {
        val decision = RealtimePcmBufferPolicy.startDecision(
            pendingBytes = RealtimePcmBufferPolicy.bytesForDurationMs(24_000, 90),
            sampleRate = 24_000,
            waitedMs = 10,
            force = true,
        )

        assertTrue(decision.shouldStart)
        assertEquals("flush", decision.reason)
    }

    @Test
    fun `short fast-arriving turn starts mid-stream without waiting for flush`() {
        // ~350ms of audio that arrived in 120ms of wall-clock (faster than
        // realtime, as providers front-load PCM). This used to satisfy neither
        // the prebuffer nor the max-wait threshold, so streaming never started
        // and the turn depended entirely on the end-of-turn flush — the root of
        // the "valid PCM logs but no audible audio on short turns" bug.
        val decision = RealtimePcmBufferPolicy.startDecision(
            pendingBytes = RealtimePcmBufferPolicy.bytesForDurationMs(24_000, 350),
            sampleRate = 24_000,
            waitedMs = 120,
            force = false,
        )

        assertTrue(decision.shouldStart)
        assertEquals("prebuffer", decision.reason)
    }

    @Test
    fun `start prebuffer stays within a low-latency budget`() {
        assertTrue(
            "realtime start prebuffer should stay well under half a second",
            RealtimePcmBufferPolicy.START_PREBUFFER_MS <= 500L,
        )
        assertTrue(
            RealtimePcmBufferPolicy.MAX_ADAPTIVE_START_PREBUFFER_MS <=
                2L * RealtimePcmBufferPolicy.START_PREBUFFER_MS + 1_000L,
        )
    }

    @Test
    fun `adaptive prebuffer target is capped even when requested higher`() {
        val decision = RealtimePcmBufferPolicy.startDecision(
            pendingBytes = RealtimePcmBufferPolicy.bytesForDurationMs(24_000, 5_000),
            sampleRate = 24_000,
            waitedMs = 0,
            force = false,
            startPrebufferMs = 10_000L,
        )

        // A 5s buffer exceeds the capped adaptive target, so playback starts
        // rather than waiting for an unbounded prebuffer.
        assertTrue(decision.shouldStart)
        assertEquals("prebuffer", decision.reason)
    }

    @Test
    fun `stream buffer is larger than startup prebuffer`() {
        val bufferBytes = RealtimePcmBufferPolicy.streamBufferSize(
            minBufferBytes = 4_800,
            sampleRate = 24_000,
        )
        val prebufferBytes = RealtimePcmBufferPolicy.bytesForDurationMs(
            24_000,
            RealtimePcmBufferPolicy.START_PREBUFFER_MS,
        )

        assertTrue(bufferBytes > prebufferBytes)
    }
}
