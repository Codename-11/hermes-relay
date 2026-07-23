package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveDashboardRouteTest {

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
    fun `auto managed dashboard derives from selected legacy API-only route`() {
        val connection = connection(
            dashboardUrl = null,
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
    fun `explicit saved dashboard remains fallback when selected route lacks dashboard`() {
        val connection = connection(
            dashboardUrl = "https://dashboard.example.com",
            apiServerUrl = "https://api.example.com",
        )
        val incompleteRoute = EndpointCandidate(
            role = "custom",
            priority = 1,
            api = ApiEndpoint("100.71.8.56", 8642),
        )

        assertEquals(
            "https://dashboard.example.com",
            resolveEffectiveDashboardUrl(connection, incompleteRoute),
        )
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
