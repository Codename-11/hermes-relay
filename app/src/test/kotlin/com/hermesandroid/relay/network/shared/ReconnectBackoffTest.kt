package com.hermesandroid.relay.network.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun `full jitter stays within the cap`() {
        assertEquals(0L, fullJitterDelayMs(1_000L, 0.0))
        assertEquals(500L, fullJitterDelayMs(1_000L, 0.5))
        assertEquals(1_000L, fullJitterDelayMs(1_000L, 1.0))
    }

    @Test
    fun `non-positive caps are immediate`() {
        assertEquals(0L, fullJitterDelayMs(0L, 0.5))
        assertEquals(0L, fullJitterDelayMs(-1L, 0.5))
    }
}
