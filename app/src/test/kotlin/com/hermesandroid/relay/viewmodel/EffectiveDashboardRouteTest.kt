package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveDashboardRouteTest {

    @Test
    fun `discovered API route stays dormant when fallback is not configured`() {
        val tailscale = EndpointCandidate(
            role = "tailscale",
            priority = 1,
            api = ApiEndpoint("100.71.8.56", 8642),
        )

        assertEquals("", resolveEffectiveApiServerUrl("", tailscale))
    }

    @Test
    fun `selected API route wins after fallback is configured`() {
        val tailscale = EndpointCandidate(
            role = "tailscale",
            priority = 1,
            api = ApiEndpoint("100.71.8.56", 8642),
        )

        assertEquals(
            "http://100.71.8.56:8642",
            resolveEffectiveApiServerUrl("http://192.168.1.20:8642", tailscale),
        )
    }

    @Test
    fun `selected route dashboard wins over explicit primary dashboard`() {
        val connection = connection(
            dashboardUrl = "http://192.168.1.20:9119",
            apiServerUrl = "http://192.168.1.20:8642",
        )
        val tailscale = EndpointCandidate(
            role = "tailscale",
            priority = 1,
            api = ApiEndpoint("100.71.8.56", 8642),
            dashboard = DashboardEndpoint("http://100.71.8.56:9119"),
        )

        assertEquals(
            "http://100.71.8.56:9119",
            resolveEffectiveDashboardUrl(connection, tailscale),
        )
    }

    @Test
    fun `selected API-only route keeps explicit same-host secure dashboard`() {
        val connection = connection(
            dashboardUrl = "https://hermes.example.com:443",
            apiServerUrl = "https://hermes.example.com:8643",
        )
        val fallback = EndpointCandidate(
            role = "public",
            priority = 1,
            api = ApiEndpoint("hermes.example.com", 8643, tls = true),
        )

        assertEquals(
            "https://hermes.example.com:443",
            resolveEffectiveDashboardUrl(connection, fallback),
        )
    }

    @Test
    fun `selected API-only route derives dashboard for a different route host`() {
        val connection = connection(
            dashboardUrl = "http://192.168.1.20:9119",
            apiServerUrl = "http://192.168.1.20:8642",
        )
        val tailscale = EndpointCandidate(
            role = "tailscale",
            priority = 1,
            api = ApiEndpoint("100.71.8.56", 8642),
        )

        assertEquals(
            "http://100.71.8.56:9119",
            resolveEffectiveDashboardUrl(connection, tailscale),
        )
    }

    @Test
    fun `explicit saved dashboard remains fallback when selected route lacks standard surfaces`() {
        val connection = connection(
            dashboardUrl = "https://dashboard.example.com",
            apiServerUrl = "https://api.example.com",
        )
        val incompleteRoute = EndpointCandidate(
            role = "custom",
            priority = 1,
        )

        assertEquals(
            "https://dashboard.example.com",
            resolveEffectiveDashboardUrl(connection, incompleteRoute),
        )
    }

    @Test
    fun `authenticated dashboard promotion preserves API and Relay ownership`() {
        val originalRoute = EndpointCandidate(
            role = "lan",
            api = ApiEndpoint("192.168.1.20", 8642),
            relay = RelayEndpoint("ws://192.168.1.20:8767"),
            dashboard = DashboardEndpoint("http://192.168.1.20:9119"),
        )
        val original = connection(
            dashboardUrl = "http://192.168.1.20:9119",
            apiServerUrl = "http://192.168.1.20:8642",
        ).copy(relayUrl = "ws://192.168.1.20:8767", routeCandidates = listOf(originalRoute))

        val promoted = withAuthenticatedDashboardOrigin(original, "https://hermes.example.com")

        assertEquals("https://hermes.example.com", promoted.dashboardUrl)
        assertEquals(AUTHENTICATED_DASHBOARD_ROUTE_ROLE, promoted.preferredRouteRole)
        assertEquals(originalRoute, promoted.routeCandidates.first())
        val publicRoute = promoted.routeCandidates.last()
        assertEquals(AUTHENTICATED_DASHBOARD_ROUTE_ROLE, publicRoute.role)
        assertEquals(DashboardEndpoint("https://hermes.example.com"), publicRoute.dashboard)
        assertEquals(null, publicRoute.api)
        assertEquals(null, publicRoute.relay)
    }

    @Test
    fun `authenticated dashboard origin rejects unsafe or non-origin inputs`() {
        assertEquals("https://hermes.example.com", normalizeAuthenticatedDashboardOrigin("https://hermes.example.com/"))
        assertEquals(null, normalizeAuthenticatedDashboardOrigin("http://hermes.example.com"))
        assertEquals(null, normalizeAuthenticatedDashboardOrigin("https://user:pass@hermes.example.com"))
        assertEquals(null, normalizeAuthenticatedDashboardOrigin("https://hermes.example.com?next=evil"))
        assertEquals(null, normalizeAuthenticatedDashboardOrigin("https://hermes.example.com#fragment"))
    }

    private fun connection(
        dashboardUrl: String?,
        apiServerUrl: String,
    ) = Connection(
        id = "connection",
        label = "Hermes",
        apiServerUrl = apiServerUrl,
        relayUrl = "",
        tokenStoreKey = "hermes_auth_connection",
        dashboardUrl = dashboardUrl,
    )
}
