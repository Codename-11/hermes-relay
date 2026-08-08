package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class QrPairingScannerMotionTest {
    @Test
    fun regularMotionUsesBriefSuccessSettle() {
        assertEquals(200L, scannerSettleDelayMs(motionEnabled = true))
    }

    @Test
    fun reducedMotionCompletesWithoutAnimationDelay() {
        assertEquals(0L, scannerSettleDelayMs(motionEnabled = false))
    }
}
