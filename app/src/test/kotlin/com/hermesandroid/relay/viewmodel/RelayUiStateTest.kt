package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.network.relay.ConnectionState
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
    fun `scheduled reconnect becomes unavailable after grace`() {
        val inputs = RelayUiInputs(
            auth = AuthState.Paired("token"),
            conn = ConnectionState.Reconnecting,
            url = "wss://relay.example/ws",
            configured = true,
        )

        assertEquals(RelayUiState.Connecting, inputs.resolveRelayUiState())
        assertEquals(RelayUiState.Stale, inputs.resolveRelayUiState(graceElapsed = true))
    }

    @Test
    fun `failed auth takes precedence over reconnecting transport`() {
        val inputs = RelayUiInputs(
            auth = AuthState.Failed("expired"),
            conn = ConnectionState.Reconnecting,
            url = "wss://relay.example/ws",
            configured = true,
        )

        assertEquals(RelayUiState.Expired, inputs.resolveRelayUiState())
    }

    @Test
    fun `socket is not ready until pairing auth succeeds`() {
        val inputs = RelayUiInputs(
            auth = AuthState.Pairing,
            conn = ConnectionState.Connected,
            url = "wss://relay.example/ws",
            configured = true,
        )

        assertEquals(RelayUiState.Connecting, inputs.resolveRelayUiState())
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
