package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveDashboardRouteTest {
    @Test
    fun `late dashboard probe cannot publish across connection or route change`() {
        assertTrue(
            isCurrentDashboardProbe(
                requestConnectionId = "a",
                requestDashboardUrl = "https://hermes.example/base",
                activeConnectionId = "a",
                activeDashboardUrl = "https://hermes.example/base/",
            ),
        )
        assertFalse(
            isCurrentDashboardProbe(
                requestConnectionId = "a",
                requestDashboardUrl = "https://hermes.example/base",
                activeConnectionId = "b",
                activeDashboardUrl = "https://hermes.example/base",
            ),
        )
        assertFalse(
            isCurrentDashboardProbe(
                requestConnectionId = "a",
                requestDashboardUrl = "https://hermes.example/base",
                activeConnectionId = "a",
                activeDashboardUrl = "https://other.example/base",
            ),
        )
    }


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
    fun `authenticated dashboard origin wins without changing selected network route`() {
        val connection = connection(
            dashboardUrl = "http://192.168.1.20:9119",
            apiServerUrl = "http://192.168.1.20:8642",
        ).copy(authenticatedDashboardOrigin = "https://hermes.example.com")
        val tailscale = EndpointCandidate(
            role = "tailscale",
            priority = 1,
            api = ApiEndpoint("100.71.8.56", 8642),
            relay = RelayEndpoint("ws://100.71.8.56:8767"),
            dashboard = DashboardEndpoint("http://100.71.8.56:9119"),
        )

        assertEquals(
            "https://hermes.example.com",
            resolveEffectiveDashboardUrl(connection, tailscale),
        )
        assertEquals("http://100.71.8.56:8642", resolveEffectiveApiServerUrl(connection.apiServerUrl, tailscale))
    }

    @Test
    fun `reviewed private http origin wins consistently`() {
        val route = EndpointCandidate(
            role = "lan",
            dashboard = DashboardEndpoint("http://192.168.1.20:9119"),
        )
        listOf("http://100.75.1.2:9119", "http://127.0.0.1:9119").forEach { origin ->
            val connection = connection(
                dashboardUrl = "http://192.168.1.20:9119",
                apiServerUrl = "http://192.168.1.20:8642",
            ).copy(authenticatedDashboardOrigin = origin)

            assertEquals(origin, connection.resolvedDashboardUrl)
            assertEquals(origin, resolveEffectiveDashboardUrl(connection, route))
        }
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
        ).copy(
            relayUrl = "ws://192.168.1.20:8767",
            routeCandidates = listOf(originalRoute),
            preferredRouteRole = "lan",
        )

        val promoted = withAuthenticatedDashboardOrigin(original, "https://hermes.example.com")

        assertEquals("https://hermes.example.com", promoted.authenticatedDashboardOrigin)
        assertEquals(original.dashboardUrl, promoted.dashboardUrl)
        assertEquals("lan", promoted.preferredRouteRole)
        assertEquals(listOf(originalRoute), promoted.routeCandidates)
    }

    @Test
    fun `failed promotion restores the exact previous routing model`() = runTest {
        val originalRoute = EndpointCandidate(
            role = "lan",
            api = ApiEndpoint("192.168.1.20", 8642),
            relay = RelayEndpoint("ws://192.168.1.20:8767"),
            dashboard = DashboardEndpoint("http://192.168.1.20:9119"),
        )
        val original = connection(
            dashboardUrl = "http://192.168.1.20:9119",
            apiServerUrl = "http://192.168.1.20:8642",
        ).copy(routeCandidates = listOf(originalRoute), preferredRouteRole = "lan")

        val writes = mutableListOf<Connection>()
        val success = persistAuthenticatedDashboardOriginWithRollback(
            previous = original,
            normalizedOrigin = "https://hermes.example.com",
            persist = writes::add,
            activated = { false },
        )

        assertEquals(false, success)
        assertEquals(2, writes.size)
        assertEquals("https://hermes.example.com", writes.first().authenticatedDashboardOrigin)
        assertEquals(original, writes.last())
    }

    @Test
    fun `authenticated dashboard origin rejects unsafe or non-origin inputs`() {
        assertEquals("https://hermes.example.com", normalizeAuthenticatedDashboardOrigin("https://hermes.example.com/"))
        assertEquals("http://100.71.8.56:9119", normalizeAuthenticatedDashboardOrigin("http://100.71.8.56:9119"))
        assertEquals("http://127.0.0.1:9119", normalizeAuthenticatedDashboardOrigin("http://127.0.0.1:9119"))
        assertEquals(null, normalizeAuthenticatedDashboardOrigin("http://hermes.example.com"))
        assertEquals(null, normalizeAuthenticatedDashboardOrigin("http://203.0.113.10:9119"))
        assertEquals(null, normalizeAuthenticatedDashboardOrigin("https://user:pass@hermes.example.com"))
        assertEquals(null, normalizeAuthenticatedDashboardOrigin("https://hermes.example.com?next=evil"))
        assertEquals(null, normalizeAuthenticatedDashboardOrigin("https://hermes.example.com#fragment"))
    }

    @Test
    fun `explicit dashboard edit rejects unsupported or credentialed addresses`() {
        assertEquals("http://192.168.1.20:9119", normalizeDashboardAddressForEdit("192.168.1.20"))
        assertEquals(null, normalizeDashboardAddressForEdit("ftp://hermes.example.com"))
        assertEquals(null, normalizeDashboardAddressForEdit("https://user:pass@hermes.example.com"))
        assertEquals(null, normalizeDashboardAddressForEdit("https://hermes.example.com?next=bad"))
        assertEquals(null, normalizeDashboardAddressForEdit("https://hermes.example.com#settings"))
        assertEquals(null, normalizeDashboardAddressForEdit("https://hermes.example.com:99999"))
        assertEquals(null, normalizeDashboardAddressForEdit("https://bad host.example.com"))
        assertEquals(null, normalizeDashboardAddressForEdit("https://hermes.example.com#fragment"))
        assertEquals(null, normalizeDashboardAddressForEdit("http://"))
        assertEquals("https://hermes.example.com/base", normalizeDashboardAddressForEdit("https://hermes.example.com/base"))
    }

    @Test
    fun `explicit dashboard host change clears only authenticated origin`() {
        val route = EndpointCandidate(
            role = "lan",
            dashboard = DashboardEndpoint("http://192.168.1.20:9119"),
            relay = RelayEndpoint("ws://192.168.1.20:8767"),
        )
        val original = connection(
            dashboardUrl = "http://192.168.1.20:9119",
            apiServerUrl = "http://192.168.1.20:8642",
        ).copy(
            authenticatedDashboardOrigin = "https://old.example.com",
            routeCandidates = listOf(route),
            preferredRouteRole = "lan",
        )

        val updated = withExplicitDashboardAddress(original, "https://new.example.com")

        assertEquals("https://new.example.com", updated.dashboardUrl)
        assertEquals(null, updated.authenticatedDashboardOrigin)
        assertEquals(listOf(route), updated.routeCandidates)
        assertEquals("lan", updated.preferredRouteRole)
    }

    @Test
    fun `promoting canonical origin to explicit address removes only redundant override`() {
        val original = connection(
            dashboardUrl = "http://192.168.1.20:9119",
            apiServerUrl = "http://192.168.1.20:8642",
        ).copy(authenticatedDashboardOrigin = "https://hermes.example.com")

        val updated = withExplicitDashboardAddress(original, "https://hermes.example.com")

        assertEquals("https://hermes.example.com", updated.dashboardUrl)
        assertEquals(null, updated.authenticatedDashboardOrigin)
    }

    @Test
    fun `dashboard credentials retire only when their exact owner changes`() {
        val privateRoute = connection(
            dashboardUrl = "http://192.168.1.20:9119",
            apiServerUrl = "http://192.168.1.20:8642",
        ).copy(authenticatedDashboardOrigin = "https://hermes.example.com")

        val sameOwnerMadeExplicit = privateRoute.copy(
            dashboardUrl = "https://hermes.example.com/",
            authenticatedDashboardOrigin = null,
        )
        val differentOwner = privateRoute.copy(
            dashboardUrl = "https://other.example.com",
            authenticatedDashboardOrigin = null,
        )

        assertFalse(dashboardCredentialsMustBeRetired(privateRoute, sameOwnerMadeExplicit))
        assertTrue(dashboardCredentialsMustBeRetired(privateRoute, differentOwner))
    }

    @Test
    fun `dashboard install identity rejects mismatch and requires confirmation when missing`() {
        assertEquals(
            DashboardInstallIdentityDecision.Match,
            dashboardInstallIdentityDecision("install-a", " install-a "),
        )
        assertEquals(
            DashboardInstallIdentityDecision.Mismatch,
            dashboardInstallIdentityDecision("install-a", "install-b"),
        )
        assertEquals(
            DashboardInstallIdentityDecision.Missing,
            dashboardInstallIdentityDecision(null, "install-a"),
        )
        assertEquals(
            DashboardInstallIdentityDecision.Missing,
            dashboardInstallIdentityDecision("install-a", ""),
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
