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

    @Test
    fun `relay pairing appends QR routes missing from LAN-only standard connection`() {
        val lan = EndpointCandidate(
            role = "lan",
            priority = 0,
            dashboard = DashboardEndpoint("http://192.168.1.20:9119"),
            api = ApiEndpoint("192.168.1.20", 8642),
        )
        val current = Connection(
            id = "hermes-node",
            label = "Hermes node",
            apiServerUrl = "http://192.168.1.20:8642",
            relayUrl = "",
            tokenStoreKey = "hermes_auth_node",
            dashboardUrl = "http://192.168.1.20:9119",
            routeCandidates = listOf(lan),
        )
        val relayRoutes = listOf(
            EndpointCandidate(
                role = "lan",
                priority = 0,
                api = ApiEndpoint("192.168.1.20", 8642),
                relay = RelayEndpoint("ws://192.168.1.20:8767", "ws"),
            ),
            EndpointCandidate(
                role = "tailscale",
                priority = 1,
                api = ApiEndpoint("100.71.8.56", 8642),
                relay = RelayEndpoint("ws://100.71.8.56:8767", "ws"),
            ),
        )

        val result = preserveStandardConnectionWhileApplyingRelay(
            current = current,
            relayUrl = "ws://192.168.1.20:8767",
            relayRoutes = relayRoutes,
        )

        assertEquals(listOf("lan", "tailscale"), result.routeCandidates.map { it.role })
        assertEquals("100.71.8.56", result.routeCandidates[1].api?.host)
        assertEquals("ws://100.71.8.56:8767", result.routeCandidates[1].relay?.url)
    }

    @Test
    fun `relay pairing fills API and Relay on manually-added dashboard route`() {
        val tailscaleDashboard = DashboardEndpoint("http://100.71.8.56:9119")
        val current = Connection(
            id = "hermes-node",
            label = "Hermes node",
            apiServerUrl = "http://192.168.1.20:8642",
            relayUrl = "ws://192.168.1.20:8767",
            tokenStoreKey = "hermes_auth_node",
            dashboardUrl = "http://192.168.1.20:9119",
            routeCandidates = listOf(
                EndpointCandidate(
                    role = "lan",
                    priority = 0,
                    dashboard = DashboardEndpoint("http://192.168.1.20:9119"),
                    api = ApiEndpoint("192.168.1.20", 8642),
                ),
                EndpointCandidate(
                    role = "tailscale",
                    priority = 1,
                    dashboard = tailscaleDashboard,
                ),
            ),
        )
        val qrTailscale = EndpointCandidate(
            role = "tailscale",
            priority = 1,
            api = ApiEndpoint("100.71.8.56", 8642),
            relay = RelayEndpoint("ws://100.71.8.56:8767", "ws"),
        )

        val result = preserveStandardConnectionWhileApplyingRelay(
            current = current,
            relayUrl = current.relayUrl,
            relayRoutes = listOf(qrTailscale),
        )

        val route = result.routeCandidates.single { it.role == "tailscale" }
        assertEquals(tailscaleDashboard, route.dashboard)
        assertEquals(qrTailscale.api, route.api)
        assertEquals(qrTailscale.relay, route.relay)
    }
}
