package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpoint
import com.hermesandroid.relay.network.shared.EndpointSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRoutesAccessPresentationTest {
    @Test
    fun `route editor exposes LAN Tailscale and public paths directly`() {
        assertEquals(listOf("lan", "tailscale", "public"), GATEWAY_ROUTE_EDITOR_ROLES)
    }

    @Test
    fun `API-only route editor prefill uses the derived Gateway address`() {
        val apiOnly = EndpointCandidate(
            role = "lan",
            api = ApiEndpoint("192.168.1.20", 8642),
        )

        assertEquals("http://192.168.1.20:9119", routeEditorInitialGatewayUrl(apiOnly))
    }

    @Test
    fun `LAN and Tailscale routes retain their actual HTTP transport`() {
        val lan = EndpointCandidate(
            role = "lan",
            dashboard = DashboardEndpoint("http://192.168.1.20:9119"),
        )
        val tailscale = EndpointCandidate(
            role = "tailscale",
            dashboard = DashboardEndpoint("https://hermes.tailnet.ts.net"),
        )

        assertEquals("LAN (HTTP)", gatewayRoutePresentation(lan, "").label)
        assertEquals("HTTP", routeTransportLabel(lan))
        assertEquals("Tailscale (HTTPS)", gatewayRoutePresentation(tailscale, "").label)
        assertEquals("HTTPS", routeTransportLabel(tailscale))
    }

    @Test
    fun `API-only candidate presents the derived Dashboard Gateway address`() {
        val route = EndpointCandidate(
            role = "lan",
            api = ApiEndpoint("192.168.1.20", 8642),
        )

        val presentation = gatewayRoutePresentation(route, "")

        assertEquals("http://192.168.1.20:9119", presentation.address)
        assertEquals("LAN (HTTP)", presentation.label)
    }

    @Test
    fun `relay-only candidate leaves the Gateway route unconfigured`() {
        val route = EndpointCandidate(
            role = "lan",
            relay = RelayEndpoint("ws://192.168.1.20:8767"),
        )

        val presentation = gatewayRoutePresentation(route, "")

        assertFalse(presentation.configured)
        assertEquals("", presentation.address)
        assertEquals("Gateway", presentation.label)
    }

    @Test
    fun `public route requires HTTPS while private routes may use HTTP`() {
        val publicHttp = EndpointCandidate(
            role = "public",
            dashboard = DashboardEndpoint("http://hermes.example.test:9119"),
        )
        val publicHttps = publicHttp.copy(
            dashboard = DashboardEndpoint("https://hermes.example.test"),
        )
        val tailscaleHttp = EndpointCandidate(
            role = "tailscale",
            dashboard = DashboardEndpoint("http://100.64.0.2:9119"),
        )

        assertTrue(gatewayRoutePresentation(publicHttp, "").publicHttpViolation)
        assertFalse(gatewayRoutePresentation(publicHttps, "").publicHttpViolation)
        assertFalse(gatewayRoutePresentation(tailscaleHttp, "").publicHttpViolation)
    }

    @Test
    fun `plain warning is scoped to Relay rather than HTTP Gateway`() {
        val dashboardOnly = EndpointCandidate(
            role = "lan",
            dashboard = DashboardEndpoint("http://192.168.1.20:9119"),
        )
        val plainRelay = dashboardOnly.copy(
            relay = RelayEndpoint("ws://192.168.1.20:8767"),
        )
        val tlsRelay = dashboardOnly.copy(
            relay = RelayEndpoint("wss://hermes.example.test/relay"),
        )
        val tailscaleRelay = plainRelay.copy(role = "tailscale")

        assertFalse(dashboardOnly.hasPlainRelayTransport())
        assertTrue(plainRelay.hasPlainRelayTransport())
        assertFalse(tlsRelay.hasPlainRelayTransport())
        assertFalse(tailscaleRelay.hasPlainRelayTransport())
    }

    @Test
    fun `surface security distinguishes tailnet encryption from public plaintext`() {
        val tailscale = EndpointCandidate(
            role = "tailscale",
            dashboard = DashboardEndpoint("http://100.64.0.2:9119"),
            relay = RelayEndpoint("ws://100.64.0.2:9119/api/plugins/hermes-relay/transport/ws"),
        )
        val publicPlain = EndpointCandidate(
            role = "public",
            dashboard = DashboardEndpoint("http://hermes.example.test:9119"),
        )
        val publicTls = publicPlain.copy(
            dashboard = DashboardEndpoint("https://hermes.example.test"),
        )

        assertEquals(
            RouteSurfaceSecurityPresentation.TailscaleOverlay,
            routeSurfaceSecurityPresentation(tailscale, EndpointSurface.Dashboard, tailscale.dashboard?.url),
        )
        assertEquals(
            RouteSurfaceSecurityPresentation.TailscaleOverlay,
            routeSurfaceSecurityPresentation(tailscale, EndpointSurface.Relay, tailscale.relay?.url),
        )
        assertEquals(
            RouteSurfaceSecurityPresentation.PublicPlain,
            routeSurfaceSecurityPresentation(
                publicPlain,
                EndpointSurface.Dashboard,
                publicPlain.dashboard?.url,
            ),
        )
        assertEquals(
            RouteSurfaceSecurityPresentation.ApplicationTls,
            routeSurfaceSecurityPresentation(
                publicTls,
                EndpointSurface.Dashboard,
                publicTls.dashboard?.url,
            ),
        )
    }
}
