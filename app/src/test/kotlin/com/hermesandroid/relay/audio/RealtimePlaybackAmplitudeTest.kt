package com.hermesandroid.relay.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimePlaybackAmplitudeTest {

    private val queue = listOf(
        FrameAmp(endFrame = 1_000, rms = 0.1f),
        FrameAmp(endFrame = 2_000, rms = 0.5f),
        FrameAmp(endFrame = 3_000, rms = 0.9f),
    )

    @Test
    fun `empty queue is silent`() {
        assertEquals(0f, amplitudeAtHead(emptyList(), 0), 0f)
    }

    @Test
    fun `before any chunk finishes returns the first chunk`() {
        assertEquals(0.1f, amplitudeAtHead(queue, 0), 0f)
        assertEquals(0.1f, amplitudeAtHead(queue, 999), 0f)
    }

    @Test
    fun `cursor in the second chunk returns the second chunk amplitude`() {
        // head past the first chunk's end (1000) but before the second (2000)
        assertEquals(0.5f, amplitudeAtHead(queue, 1_000), 0f)
        assertEquals(0.5f, amplitudeAtHead(queue, 1_500), 0f)
    }

    @Test
    fun `cursor in the last chunk returns the last amplitude`() {
        assertEquals(0.9f, amplitudeAtHead(queue, 2_500), 0f)
    }

    @Test
    fun `fully drained queue is silent`() {
        assertEquals(0f, amplitudeAtHead(queue, 3_000), 0f)
        assertEquals(0f, amplitudeAtHead(queue, 9_999), 0f)
    }
}
