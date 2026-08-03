package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayUiStateTest {
    @Test
    fun `relay phases use the standard user-facing vocabulary`() {
        assertEquals("Optional", RelayUiState.NotConfigured.statusText("Ready"))
        assertEquals("Ready", RelayUiState.Connected.statusText("Ready"))
        assertEquals("Reconnecting", RelayUiState.Connecting.statusText("Ready"))
        assertEquals("Unavailable", RelayUiState.Stale.statusText("Ready"))
        assertEquals("Needs re-pair", RelayUiState.Expired.statusText("Ready"))
        assertEquals("Unavailable", RelayUiState.Disconnected.statusText("Ready"))
    }

    @Test
    fun `route detail does not replace the standard relay phase`() {
        assertEquals(
            "Unavailable \u00B7 Tailscale",
            RelayRowState(
                phase = RelayUiState.Stale,
                activeEndpointRole = "tailscale",
            ).statusText("Ready"),
        )
        assertEquals(
            "Needs re-pair",
            RelayRowState(
                phase = RelayUiState.Expired,
                activeEndpointRole = "lan",
            ).statusText("Ready"),
        )
    }
}
