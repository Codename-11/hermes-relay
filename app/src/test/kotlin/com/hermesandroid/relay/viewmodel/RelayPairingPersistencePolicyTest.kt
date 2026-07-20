package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.DashboardConnectionStatus
import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayPairingPersistencePolicyTest {

    @Test
    fun `relay pairing preserves dashboard-primary connection identity and status`() {
        val status = DashboardConnectionStatus(
            checkedAtMillis = 1234L,
            reachable = true,
            authenticated = true,
        )
        val standardRoute = EndpointCandidate(
            role = "tailscale",
            priority = 0,
            dashboard = DashboardEndpoint("https://hermes-node.example.test:9119"),
        )
        val current = Connection(
            id = "hermes-node",
            label = "Hermes node",
            apiServerUrl = "",
            relayUrl = "",
            tokenStoreKey = "hermes_auth_node",
            dashboardUrl = "https://hermes-node.example.test:9119",
            dashboardAuthRequired = true,
            dashboardAuthProviders = listOf("local"),
            dashboardLastStatus = status,
            routeCandidates = listOf(standardRoute),
            preferredRouteRole = "tailscale",
        )
        val relayQrRoute = EndpointCandidate(
            role = "tailscale",
            api = ApiEndpoint("wrong-api.example", 8642, tls = true),
            dashboard = DashboardEndpoint("https://wrong-dashboard.example:9119"),
            relay = RelayEndpoint("wss://relay.example:8767", "wss"),
        )

        val result = preserveStandardConnectionWhileApplyingRelay(
            current = current,
            relayUrl = "wss://relay.example:8767",
            relayRoutes = listOf(relayQrRoute),
        )

        assertEquals("", result.apiServerUrl)
        assertEquals(current.dashboardUrl, result.dashboardUrl)
        assertEquals(current.dashboardAuthRequired, result.dashboardAuthRequired)
        assertEquals(current.dashboardAuthProviders, result.dashboardAuthProviders)
        assertEquals(status, result.dashboardLastStatus)
        assertEquals("tailscale", result.preferredRouteRole)
        assertEquals("wss://relay.example:8767", result.relayUrl)
        assertEquals(standardRoute.dashboard, result.routeCandidates.single().dashboard)
        assertNull(result.routeCandidates.single().api)
        assertEquals(relayQrRoute.relay, result.routeCandidates.single().relay)
    }

    @Test
    fun `relay pairing merges transport without replacing configured API or standard routes`() {
        val lan = EndpointCandidate(
            role = "lan",
            priority = 0,
            dashboard = DashboardEndpoint("http://192.168.1.20:9119"),
            api = ApiEndpoint("192.168.1.20", 8642),
        )
        val tailscale = EndpointCandidate(
            role = "tailscale",
            priority = 1,
            dashboard = DashboardEndpoint("https://hermes-node.example.test:9119"),
            api = ApiEndpoint("hermes-node.example.test", 8642, tls = true),
        )
        val current = Connection(
            id = "hermes-node",
            label = "Hermes node",
            apiServerUrl = "https://hermes-node.example.test:8642",
            relayUrl = "",
            tokenStoreKey = "hermes_auth_node",
            dashboardUrl = "https://hermes-node.example.test:9119",
            routeCandidates = listOf(lan, tailscale),
        )
        val relayRoutes = listOf(
            EndpointCandidate(
                role = "lan",
                api = ApiEndpoint("qr-api.invalid", 8642),
                relay = RelayEndpoint("ws://192.168.1.20:8767", "ws"),
            ),
            EndpointCandidate(
                role = "tailscale",
                api = ApiEndpoint("qr-api.invalid", 8642),
                relay = RelayEndpoint("wss://hermes-node.example.test:8767", "wss"),
            ),
        )

        val result = preserveStandardConnectionWhileApplyingRelay(
            current = current,
            relayUrl = "wss://hermes-node.example.test:8767",
            relayRoutes = relayRoutes,
        )

        assertEquals(current.apiServerUrl, result.apiServerUrl)
        assertEquals(current.dashboardUrl, result.dashboardUrl)
        assertEquals(listOf(lan.api, tailscale.api), result.routeCandidates.map { it.api })
        assertEquals(listOf(lan.dashboard, tailscale.dashboard), result.routeCandidates.map { it.dashboard })
        assertEquals(
            listOf("ws://192.168.1.20:8767", "wss://hermes-node.example.test:8767"),
            result.routeCandidates.map { it.relay?.url },
        )
    }
}
