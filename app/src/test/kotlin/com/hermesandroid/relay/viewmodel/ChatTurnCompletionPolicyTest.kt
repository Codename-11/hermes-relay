package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnCompletionPolicyTest {
    @Test
    fun `successful Sessions turns reload persisted message boundaries`() {
        assertTrue(shouldReloadHistoryAfterSuccessfulTurn("sessions", gatewayReconcileRequired = false))
    }

    @Test
    fun `healthy uninterrupted Gateway turns keep their live transcript`() {
        assertFalse(shouldReloadHistoryAfterSuccessfulTurn("gateway", gatewayReconcileRequired = false))
    }

    @Test
    fun `Gateway turns with a socket gap reload potentially missed events`() {
        assertTrue(shouldReloadHistoryAfterSuccessfulTurn("gateway", gatewayReconcileRequired = true))
    }

    @Test
    fun `stateless structured transports do not reload session history`() {
        assertFalse(shouldReloadHistoryAfterSuccessfulTurn("runs", gatewayReconcileRequired = false))
        assertFalse(shouldReloadHistoryAfterSuccessfulTurn("completions", gatewayReconcileRequired = false))
    }
}
