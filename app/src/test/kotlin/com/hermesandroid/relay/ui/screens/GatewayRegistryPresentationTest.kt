package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.DashboardConnectionStatus
import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpoint
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayRegistryPresentationTest {
    @Test
    fun `active gateway uses effective signed-in origin instead of the network candidate`() {
        val result = resolveGatewayCardPresentation(
            connection = connection(),
            active = true,
            gatewayAvailability = GatewayAvailability.Ready,
            activeEndpoint = route("lan", "http://192.168.1.20:9119"),
            effectiveDashboardUrl = "https://hermes.example.test",
        )

        assertEquals(GatewayCardStatus.Online, result.status)
        assertEquals("Public", result.routeName)
        assertEquals("HTTPS", result.transport)
    }

    @Test
    fun `sign in requirement stays distinct from gateway reachability`() {
        val result = resolveGatewayCardPresentation(
            connection = connection(),
            active = true,
            gatewayAvailability = GatewayAvailability.SignInRequired,
            activeEndpoint = route("tailscale", "https://hermes.tailnet.ts.net"),
            effectiveDashboardUrl = "https://hermes.tailnet.ts.net",
        )

        assertEquals(GatewayCardStatus.SignInRequired, result.status)
        assertEquals("Tailscale", result.routeName)
        assertEquals("HTTPS", result.transport)
    }

    @Test
    fun `inactive gateway uses its persisted status and preferred route`() {
        val connection = connection(
            status = DashboardConnectionStatus(reachable = true, authRequired = false),
            routes = listOf(
                route("lan", "http://192.168.1.20:9119", priority = 0),
                route("tailscale", "https://hermes.tailnet.ts.net", priority = 1),
            ),
            preferredRouteRole = "tailscale",
        )

        val result = resolveGatewayCardPresentation(
            connection = connection,
            active = false,
            gatewayAvailability = null,
            activeEndpoint = null,
            effectiveDashboardUrl = connection.resolvedDashboardUrl,
        )

        assertEquals(GatewayCardStatus.LastCheckSucceeded, result.status)
        assertEquals("Tailscale", result.routeName)
        assertEquals("HTTPS", result.transport)
    }

    @Test
    fun `configured public HTTPS route is presented without implying it is required`() {
        val result = resolveGatewayCardPresentation(
            connection = connection(routes = listOf(route("public", "https://hermes.example.test"))),
            active = false,
            gatewayAvailability = null,
            activeEndpoint = null,
            effectiveDashboardUrl = "https://hermes.example.test",
        )

        assertEquals(GatewayCardStatus.NotChecked, result.status)
        assertEquals("Public", result.routeName)
        assertEquals("HTTPS", result.transport)
    }

    @Test
    fun `gateway with no address reports no route`() {
        val result = resolveGatewayCardPresentation(
            connection = connection(dashboardUrl = "", routes = emptyList()),
            active = false,
            gatewayAvailability = null,
            activeEndpoint = null,
            effectiveDashboardUrl = "",
        )

        assertEquals(GatewayCardStatus.NoRoute, result.status)
        assertNull(result.routeName)
        assertNull(result.transport)
    }

    @Test
    fun `relay-only candidate is not presented as a Gateway route`() {
        val relayOnly = EndpointCandidate(
            role = "lan",
            relay = RelayEndpoint("ws://192.168.1.20:8767"),
        )
        val result = resolveGatewayCardPresentation(
            connection = connection(dashboardUrl = "", routes = listOf(relayOnly)),
            active = false,
            gatewayAvailability = null,
            activeEndpoint = relayOnly,
            effectiveDashboardUrl = "",
        )

        assertEquals(GatewayCardStatus.NoRoute, result.status)
        assertNull(result.routeName)
        assertNull(result.transport)
    }

    private fun connection(
        status: DashboardConnectionStatus? = null,
        routes: List<EndpointCandidate> = emptyList(),
        preferredRouteRole: String? = null,
        dashboardUrl: String = "https://hermes.example.test",
    ) = Connection(
        id = "gateway",
        label = "Hermes",
        apiServerUrl = "",
        relayUrl = "",
        tokenStoreKey = "token",
        dashboardUrl = dashboardUrl,
        dashboardLastStatus = status,
        routeCandidates = routes,
        preferredRouteRole = preferredRouteRole,
    )

    private fun route(
        role: String,
        dashboardUrl: String,
        priority: Int = 0,
    ) = EndpointCandidate(
        role = role,
        priority = priority,
        dashboard = DashboardEndpoint(dashboardUrl),
    )
}
