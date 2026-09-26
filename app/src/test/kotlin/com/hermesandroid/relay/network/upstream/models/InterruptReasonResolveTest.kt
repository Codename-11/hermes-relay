package com.hermesandroid.relay.network.upstream.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptReasonResolveTest {
    @Test
    fun barePlaceholdersDetected() {
        assertTrue(isBareInterruptPlaceholder("Operation interrupted."))
        assertTrue(isBareInterruptPlaceholder("Response interrupted"))
        assertTrue(isBareInterruptPlaceholder("Run interrupted"))
        assertFalse(isBareInterruptPlaceholder("Operation interrupted: stopped by user."))
    }

    @Test
    fun prefersErrorOverFallback() {
        val event = HermesSseEvent(
            interrupted = true,
            error = "Operation interrupted: client disconnected.",
            content = "Operation interrupted.",
        )
        assertEquals(
            "Operation interrupted: client disconnected.",
            event.resolveInterruptMessage(preferRun = true),
        )
    }

    @Test
    fun usesTurnExitReasonWhenNoProse() {
        val event = HermesSseEvent(
            interrupted = true,
            turnExitReason = "interrupted_by_user",
        )
        assertEquals(
            "Operation interrupted: interrupted by user.",
            event.resolveInterruptMessage(),
        )
    }

    @Test
    fun fallsBackOnlyWhenNothingAvailable() {
        val event = HermesSseEvent(interrupted = true)
        assertEquals("Run interrupted", event.resolveInterruptMessage(preferRun = true))
        assertEquals("Response interrupted", event.resolveInterruptMessage(preferRun = false))
    }
}
