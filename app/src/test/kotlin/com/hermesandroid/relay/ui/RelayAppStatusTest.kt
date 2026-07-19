package com.hermesandroid.relay.ui

import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpoint
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.viewmodel.ChatRuntimeStatus
import com.hermesandroid.relay.viewmodel.ChatTransportPath
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayAppStatusTest {

    @Test
    fun `dashboard-only connection counts as configured startup chat`() {
        assertTrue(hasConfiguredStartupChat(connection(dashboardUrl = "https://host.ts.net:9119")))
        assertFalse(hasConfiguredStartupChat(connection(relayUrl = "wss://host.ts.net:8767")))
    }

    @Test
    fun `gateway readiness owns chat status when optional surfaces are unavailable`() {
        val status = resolveAppChatRuntimeStatus(
            connection = connection(
                dashboardUrl = "https://host.ts.net:9119",
                apiServerUrl = "https://host.ts.net:8642",
                relayUrl = "wss://host.ts.net:8767",
            ),
            gatewayAvailability = GatewayAvailability.Ready,
            apiHealth = ConnectionViewModel.HealthStatus.Unreachable,
        )

        assertEquals(
            ChatRuntimeStatus.Connected(ChatTransportPath.Gateway, fallback = false),
            status,
        )
    }

    @Test
    fun `configured gateway probe appears connecting without Relay involvement`() {
        val status = resolveAppChatRuntimeStatus(
            connection = connection(dashboardUrl = "https://host.ts.net:9119"),
            gatewayAvailability = GatewayAvailability.Unknown,
            apiHealth = ConnectionViewModel.HealthStatus.Unknown,
        )

        assertEquals(ChatRuntimeStatus.Connecting, status)
    }

    @Test
    fun `gateway footer derives Dashboard route without an API endpoint`() {
        val connection = connection(dashboardUrl = "https://host.ts.net:9119")
        val unrelatedApiRoute = EndpointCandidate(
            role = "lan",
            api = ApiEndpoint("192.168.1.20", 8642),
            relay = RelayEndpoint("ws://192.168.1.20:8767"),
        )

        val route = resolveFooterRouteCandidate(
            runtimeStatus = ChatRuntimeStatus.Connected(
                transport = ChatTransportPath.Gateway,
                fallback = false,
            ),
            activeEndpoint = unrelatedApiRoute,
            connection = connection,
            effectiveDashboardUrl = connection.resolvedDashboardUrl,
        )

        assertEquals("tailscale", route?.role)
        assertEquals("https://host.ts.net:9119", route?.dashboard?.url)
        assertNull(route?.api)
    }

    private fun connection(
        dashboardUrl: String? = null,
        apiServerUrl: String = "",
        relayUrl: String = "",
    ) = Connection(
        id = "connection",
        label = "Hermes",
        apiServerUrl = apiServerUrl,
        relayUrl = relayUrl,
        tokenStoreKey = "hermes_auth_connection",
        dashboardUrl = dashboardUrl,
    )
}
