package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.ProxyEndpoint
import com.hermesandroid.relay.data.RelayEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EffectiveRelayRouteTest {
    @Test
    fun `dashboard ingress uses only the matching authenticated origin`() {
        val ingress = "wss://hermes.example/api/plugins/hermes-relay/transport/ws"

        assertEquals(
            "https://hermes.example",
            dashboardOriginForRelayIngress("https://hermes.example", ingress),
        )
        assertEquals(
            "https://hermes.example/base",
            dashboardOriginForRelayIngress(
                "https://hermes.example/base",
                "wss://hermes.example/base/api/plugins/hermes-relay/transport/ws",
            ),
        )
        assertNull(
            dashboardOriginForRelayIngress("https://other.example", ingress),
        )
        assertNull(
            dashboardOriginForRelayIngress(
                "https://hermes.example",
                "wss://hermes.example:8767/ws",
            ),
        )
    }

    @Test
    fun `dashboard ingress request carries exactly one fresh ticket`() {
        val request = dashboardRelayWebSocketRequest(
            "wss://hermes.example/api/plugins/hermes-relay/transport/ws?ticket=stale",
            "fresh-ticket",
        )

        assertEquals("fresh-ticket", request?.url?.queryParameter("ticket"))
        assertEquals(1, request?.url?.queryParameterValues("ticket")?.size)
    }

    @Test
    fun `relay-specific winner overrides unrelated standard route`() {
        val relayWinner = EndpointCandidate(
            role = "tailscale",
            relay = RelayEndpoint("wss://relay.tailnet.example/relay/ws"),
        )

        assertEquals(
            "wss://relay.tailnet.example/relay/ws",
            resolveEffectiveRelayUrl(
                savedRelayUrl = "ws://lan.example:8767",
                savedApiUrl = "http://dashboard-lan.example:8642",
                activeRelayEndpoint = relayWinner,
                relayConfigured = true,
            ),
        )
    }

    @Test
    fun `namespaced relay proxy remains a separate authenticated service`() {
        val relayWinner = EndpointCandidate(
            role = "plugin_proxy",
            proxy = ProxyEndpoint(
                url = "https://hermes.example",
                pinSha256 = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                surfaces = listOf("relay"),
            ),
        )

        assertEquals(
            "wss://hermes.example/relay/ws",
            resolveEffectiveRelayUrl("", "https://hermes.example/api", relayWinner, true),
        )
    }

    @Test
    fun `unconfigured relay does not expose auto-derived peer port`() {
        assertEquals(
            "",
            resolveEffectiveRelayUrl(
                savedRelayUrl = "ws://dashboard.example:8767",
                savedApiUrl = "http://dashboard.example:8642",
                activeRelayEndpoint = null,
                relayConfigured = false,
            ),
        )
    }
}
