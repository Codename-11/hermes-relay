package com.hermesandroid.relay.network.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayReconnectStateTest {

    @Test
    fun consecutiveFailuresAreScopedToTheSocketRoute() {
        val state = RelayReconnectState()

        assertEquals(1, state.recordSocketFailure("ws://lan:8767/ws"))
        state.beginAutomaticRouteSwap("wss://tail:8767/ws")

        assertEquals(
            "one LAN failure plus one Tailscale failure is not two consecutive failures on Tailscale",
            1,
            state.recordSocketFailure("wss://tail:8767/ws"),
        )
    }

    @Test
    fun automaticRouteSwapPreservesReconnectBackoff() {
        val state = RelayReconnectState()
        state.beginExplicitConnect("ws://lan:8767/ws")
        repeat(5) { state.nextReconnectAttempt() }

        state.beginAutomaticRouteSwap("wss://tail:8767/ws")

        assertEquals(
            "automatic fallback must not restart the retry loop at one second",
            5,
            state.reconnectAttempt,
        )
        assertEquals(6, state.nextReconnectAttempt())
    }
}
