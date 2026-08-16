package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnCompletionPolicyTest {
    @Test
    fun `successful Sessions turns reload persisted message boundaries`() {
        assertTrue(shouldReloadHistoryAfterSuccessfulTurn("sessions", false, false))
    }

    @Test
    fun `Gateway turns reload persisted tool calls even without a socket gap`() {
        assertTrue(shouldReloadHistoryAfterSuccessfulTurn("gateway", false, true))
    }

    @Test
    fun `Gateway turns with a socket gap reload potentially missed events`() {
        assertTrue(shouldReloadHistoryAfterSuccessfulTurn("gateway", true, false))
    }

    @Test
    fun `healthy Gateway turns do not republish reconciled history`() {
        assertFalse(shouldReloadHistoryAfterSuccessfulTurn("gateway", false, false))
    }

    @Test
    fun `stateless structured transports do not reload session history`() {
        assertFalse(shouldReloadHistoryAfterSuccessfulTurn("runs", false, true))
        assertFalse(shouldReloadHistoryAfterSuccessfulTurn("completions", false, true))
    }
}
