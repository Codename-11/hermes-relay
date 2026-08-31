package com.hermesandroid.relay.ui.screens

import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.DashboardConnectionStatus
import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.viewmodel.ChatRuntimeStatus
import com.hermesandroid.relay.viewmodel.ChatTransportPath
import com.hermesandroid.relay.viewmodel.RelayRowState
import com.hermesandroid.relay.viewmodel.RelayUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionClarityPresentationTest {
    @Test
    fun `gateway path remains distinct from configured network fallbacks`() {
        val connection = connection(
            status = DashboardConnectionStatus(
                reachable = true,
                authRequired = true,
                authenticated = true,
                authProvider = "self-hosted",
            ),
            routes = listOf(lan(), tailscale()),
            authenticatedDashboardOrigin = "https://hermes.example.test",
        )

        val result = resolveConnectionClarityPresentation(
            connection = connection,
            active = true,
            activeEndpoint = lan(),
            effectiveDashboardUrl = "https://hermes.example.test/",
            chatRuntimeStatus = ChatRuntimeStatus.Connected(ChatTransportPath.Gateway, fallback = false),
            relayConfigured = true,
            relayRowState = RelayRowState(RelayUiState.Connected, activeEndpointRole = "tailscale"),
        )

        assertEquals(CurrentHermesSurface.Gateway, result.currentSurface)
        assertEquals("HTTPS Dashboard", result.currentPath)
        assertEquals(listOf("LAN", "Tailscale"), result.configuredFallbacks)
        assertEquals("Tailscale", result.relayPath)
        assertEquals(DashboardAuthPresentation.SignedIn, result.dashboardAuth)
        assertEquals("Self-hosted OIDC", result.dashboardAuthProvider)
    }

    @Test
    fun `API fallback is current only when runtime selected it`() {
        val result = resolveConnectionClarityPresentation(
            connection = connection(
                status = DashboardConnectionStatus(reachable = false, authRequired = true),
                routes = listOf(tailscale()),
            ),
            active = true,
            activeEndpoint = null,
            effectiveDashboardUrl = "https://hermes.example.test",
            chatRuntimeStatus = ChatRuntimeStatus.Connected(ChatTransportPath.ApiSse, fallback = true),
            relayConfigured = false,
            relayRowState = RelayRowState(RelayUiState.NotConfigured),
        )

        assertEquals(CurrentHermesSurface.ApiFallback, result.currentSurface)
        assertNull(result.currentPath)
        assertEquals(DashboardAuthPresentation.Unreachable, result.dashboardAuth)
        assertEquals(listOf("Tailscale"), result.configuredFallbacks)
    }

    @Test
    fun `inactive card never claims a configured route is in use`() {
        val result = resolveConnectionClarityPresentation(
            connection = connection(
                status = DashboardConnectionStatus(
                    reachable = true,
                    authRequired = false,
                    authenticated = false,
                ),
                routes = listOf(lan(), tailscale()),
            ),
            active = false,
            activeEndpoint = null,
            effectiveDashboardUrl = "http://192.168.1.20:9119",
            chatRuntimeStatus = null,
            relayConfigured = false,
            relayRowState = null,
        )

        assertEquals(CurrentHermesSurface.Inactive, result.currentSurface)
        assertNull(result.currentPath)
        assertEquals(listOf("LAN", "Tailscale"), result.configuredFallbacks)
        assertEquals(DashboardAuthPresentation.NoSignInRequired, result.dashboardAuth)
    }

    @Test
    fun `dashboard-only identity route is not presented as a network fallback`() {
        val dashboardOnly = EndpointCandidate(
            role = "authenticated_dashboard",
            dashboard = DashboardEndpoint("https://hermes.example.test"),
        )
        val result = resolveConnectionClarityPresentation(
            connection = connection(status = null, routes = listOf(dashboardOnly)),
            active = true,
            activeEndpoint = null,
            effectiveDashboardUrl = "https://hermes.example.test",
            chatRuntimeStatus = ChatRuntimeStatus.Connecting,
            relayConfigured = false,
            relayRowState = null,
        )

        assertEquals(CurrentHermesSurface.Connecting, result.currentSurface)
        assertTrue(result.configuredFallbacks.isEmpty())
        assertEquals(DashboardAuthPresentation.Unchecked, result.dashboardAuth)
    }

    private fun connection(
        status: DashboardConnectionStatus?,
        routes: List<EndpointCandidate>,
        authenticatedDashboardOrigin: String? = null,
    ) = Connection(
        id = "connection",
        label = "Hermes",
        apiServerUrl = "",
        relayUrl = "",
        tokenStoreKey = "token",
        dashboardUrl = "https://hermes.example.test",
        authenticatedDashboardOrigin = authenticatedDashboardOrigin,
        dashboardLastStatus = status,
        routeCandidates = routes,
    )

    private fun lan() = EndpointCandidate(
        role = "lan",
        priority = 0,
        api = ApiEndpoint("192.168.1.20", 8642),
    )

    private fun tailscale() = EndpointCandidate(
        role = "tailscale",
        priority = 1,
        api = ApiEndpoint("hermes.tailnet.ts.net", 8642, tls = true),
    )
}
