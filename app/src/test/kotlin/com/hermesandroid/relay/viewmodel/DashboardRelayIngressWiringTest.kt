package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpoint
import com.hermesandroid.relay.ui.components.HermesPairingPayload
import com.hermesandroid.relay.ui.components.RelayPairing
import com.hermesandroid.relay.ui.components.resolvedDashboardIngressPairingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardRelayIngressWiringTest {
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
                "https://hermes.example/base",
                "wss://hermes.example/api/plugins/hermes-relay/transport/ws",
            ),
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
    fun `selected Dashboard origin owns Relay ingress despite higher priority Tailscale route`() {
        val payload = HermesPairingPayload(
            dashboardUrl = "https://hermes.example",
            relay = RelayPairing(
                url = "ws://100.71.8.56:8767",
                code = "ONE-TIME",
            ),
            endpoints = listOf(
                EndpointCandidate(
                    role = "tailscale",
                    priority = 0,
                    dashboard = DashboardEndpoint("http://100.71.8.56:9119"),
                    relay = RelayEndpoint(
                        "ws://100.71.8.56:9119/api/plugins/hermes-relay/transport",
                    ),
                ),
                EndpointCandidate(
                    role = "public",
                    priority = 1,
                    dashboard = DashboardEndpoint("https://hermes.example"),
                    relay = RelayEndpoint(
                        "wss://hermes.example/api/plugins/hermes-relay/transport",
                        "wss",
                    ),
                ),
            ),
        )

        val resolved = resolvedDashboardIngressPairingPayload(payload)

        assertEquals(
            "https://hermes.example",
            dashboardOriginForRelayIngress(resolved?.dashboardUrl, resolved?.relay?.url),
        )
        assertEquals("ONE-TIME", resolved?.relay?.code)
    }
}
