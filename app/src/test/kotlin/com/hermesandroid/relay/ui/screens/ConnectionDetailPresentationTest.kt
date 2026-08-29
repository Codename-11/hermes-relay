package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionDetailPresentationTest {
    @Test
    fun `unreachable API remains optional while Gateway is ready`() {
        assertEquals(
            OptionalApiPresentation.Optional,
            resolveOptionalApiPresentation(
                gatewayReady = true,
                apiReachable = false,
                apiConfigured = true,
                apiProbing = false,
            ),
        )
        assertEquals(
            OptionalApiPresentation.Offline,
            resolveOptionalApiPresentation(
                gatewayReady = false,
                apiReachable = false,
                apiConfigured = true,
                apiProbing = false,
            ),
        )
    }

    @Test
    fun `active gateway tabs put access before advanced`() {
        assertEquals(
            listOf(DetailTab.Overview, DetailTab.Routes, DetailTab.Access, DetailTab.Advanced),
            detailTabs(isActive = true),
        )
    }

    @Test
    fun `inactive gateway only exposes overview`() {
        assertEquals(listOf(DetailTab.Overview), detailTabs(isActive = false))
    }

    @Test
    fun `current route uses selected LAN identity with dashboard transport`() {
        val route = resolveDetailRoutePresentation(
            activeEndpoint = EndpointCandidate(
                role = "lan",
                dashboard = DashboardEndpoint("http://192.168.1.20:9119"),
            ),
            effectiveDashboardUrl = "http://192.168.1.20:9119/",
        )

        assertEquals("LAN (HTTP)", route.label)
        assertEquals("http://192.168.1.20:9119", route.address)
    }

    @Test
    fun `current route supports Tailscale HTTPS`() {
        val route = resolveDetailRoutePresentation(
            activeEndpoint = EndpointCandidate(
                role = "tailscale",
                dashboard = DashboardEndpoint("https://hermes.tailnet.ts.net"),
            ),
            effectiveDashboardUrl = "https://hermes.tailnet.ts.net/",
        )

        assertEquals("Tailscale (HTTPS)", route.label)
        assertEquals("https://hermes.tailnet.ts.net", route.address)
    }

    @Test
    fun `current route infers public HTTPS without a selected candidate`() {
        val route = resolveDetailRoutePresentation(
            activeEndpoint = null,
            effectiveDashboardUrl = "https://hermes.example.test",
        )

        assertEquals("Public (HTTPS)", route.label)
        assertEquals("https://hermes.example.test", route.address)
    }

    @Test
    fun `selected public route keeps its route identity`() {
        val route = resolveDetailRoutePresentation(
            activeEndpoint = EndpointCandidate(
                role = "public",
                dashboard = DashboardEndpoint("https://hermes.example.test"),
            ),
            effectiveDashboardUrl = "https://hermes.example.test",
        )

        assertEquals("Public (HTTPS)", route.label)
    }

    @Test
    fun `signed-in Dashboard origin owns current route identity`() {
        val route = resolveDetailRoutePresentation(
            activeEndpoint = EndpointCandidate(
                role = "lan",
                dashboard = DashboardEndpoint("http://192.168.1.20:9119"),
            ),
            effectiveDashboardUrl = "https://hermes.example.test",
        )

        assertEquals("Public (HTTPS)", route.label)
        assertEquals("https://hermes.example.test", route.address)
    }

    @Test
    fun `relay-only endpoint does not become the current Gateway route`() {
        val route = resolveDetailRoutePresentation(
            activeEndpoint = EndpointCandidate(
                role = "lan",
                relay = RelayEndpoint("ws://192.168.1.20:8767"),
            ),
            effectiveDashboardUrl = "",
        )

        assertEquals("Gateway", route.label)
        assertEquals("", route.address)
    }

    @Test
    fun `missing route does not claim plain HTTP`() {
        val route = resolveDetailRoutePresentation(
            activeEndpoint = null,
            effectiveDashboardUrl = "",
        )

        assertEquals("Gateway", route.label)
        assertEquals("", route.address)
    }
}
