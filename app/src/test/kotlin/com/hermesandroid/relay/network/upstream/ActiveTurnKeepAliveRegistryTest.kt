package com.hermesandroid.relay.network.upstream

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTurnKeepAliveRegistryTest {
    @After
    fun tearDown() = ActiveTurnKeepAliveRegistry.resetForTest()

    @Test
    fun siblingSessionsHoldIndependentLeases() {
        ActiveTurnKeepAliveRegistry.acquire("connection::victor::session-a")
        ActiveTurnKeepAliveRegistry.acquire("connection::default::session-b")
        ActiveTurnKeepAliveRegistry.setWaiting("connection::victor::session-a", true)

        assertEquals(
            ActiveTurnKeepAliveRegistry.Snapshot(activeTurnCount = 2, waitingSessionCount = 1),
            ActiveTurnKeepAliveRegistry.snapshot.value,
        )

        ActiveTurnKeepAliveRegistry.release("connection::victor::session-a")

        assertEquals(1, ActiveTurnKeepAliveRegistry.snapshot.value.activeTurnCount)
        assertEquals(0, ActiveTurnKeepAliveRegistry.snapshot.value.waitingSessionCount)
        assertTrue(ActiveTurnKeepAliveRegistry.snapshot.value.required)
    }

    @Test
    fun sessionRenameMovesRatherThanDuplicatesLease() {
        ActiveTurnKeepAliveRegistry.acquire("temporary")
        ActiveTurnKeepAliveRegistry.setWaiting("temporary", true)
        ActiveTurnKeepAliveRegistry.rename("temporary", "durable")

        assertEquals(1, ActiveTurnKeepAliveRegistry.snapshot.value.activeTurnCount)
        assertEquals(1, ActiveTurnKeepAliveRegistry.snapshot.value.waitingSessionCount)

        ActiveTurnKeepAliveRegistry.release("durable")
        assertFalse(ActiveTurnKeepAliveRegistry.snapshot.value.required)
    }
}
