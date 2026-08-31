package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.SessionTransport
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.net.UnknownHostException

class ChatReadinessTest {

    @Test
    fun `dashboard-only connection is a configured Hermes connection`() {
        assertTrue(
            hasConfiguredHermesConnection(
                Connection(
                    id = "dashboard-only",
                    label = "Dashboard",
                    apiServerUrl = "",
                    relayUrl = "",
                    dashboardUrl = "http://host:9119",
                    tokenStoreKey = "test-key",
                ),
            ),
        )
    }

    @Test
    fun `ready with authenticated Gateway and no API client`() {
        assertTrue(
            isChatTransportReady(
                apiClientPresent = false,
                apiReachable = false,
                gatewayAvailability = GatewayAvailability.Ready,
            ),
        )
    }

    @Test
    fun `ready with reachable API for API-only owner`() {
        assertTrue(
            isChatTransportReady(
                apiClientPresent = true,
                apiReachable = true,
                gatewayAvailability = GatewayAvailability.Unreachable,
                chatOwner = SessionTransport.SSE,
            ),
        )
    }

    @Test
    fun `reachable API cannot make a Gateway-owned chat ready`() {
        assertFalse(
            isChatTransportReady(
                apiClientPresent = true,
                apiReachable = true,
                gatewayAvailability = GatewayAvailability.SignInRequired,
                chatOwner = SessionTransport.GATEWAY,
            ),
        )
    }

    @Test
    fun `eager status owner resolves from backing preference before public alias`() {
        val connection = Connection(
            id = "dashboard-owner",
            label = "Dashboard",
            apiServerUrl = "https://hermes.example.com:8642",
            relayUrl = "",
            tokenStoreKey = "test-key",
            dashboardUrl = "https://hermes.example.com:9119",
        )

        assertEquals(
            SessionTransport.GATEWAY,
            resolveActiveChatTransport(
                boundOwner = null,
                connection = connection,
                preference = "auto",
            ),
        )
        assertEquals(
            SessionTransport.SSE,
            resolveActiveChatTransport(
                boundOwner = SessionTransport.SSE,
                connection = connection,
                preference = "auto",
            ),
        )
    }

    @Test
    fun `not ready without a usable Gateway or API`() {
        assertFalse(
            isChatTransportReady(
                apiClientPresent = false,
                apiReachable = false,
                gatewayAvailability = GatewayAvailability.SignInRequired,
            ),
        )
        assertFalse(
            isChatTransportReady(
                apiClientPresent = true,
                apiReachable = false,
                gatewayAvailability = GatewayAvailability.Unsupported,
            ),
        )
    }

    @Test
    fun `startup transport timeouts remain retryable instead of settling disconnected`() {
        assertTrue(isTransientDashboardTransportFailure(InterruptedIOException("timeout")))
        assertTrue(
            isTransientDashboardTransportFailure(
                IllegalStateException("wrapped", UnknownHostException("dashboard")),
            ),
        )
        assertFalse(isTransientDashboardTransportFailure(IllegalArgumentException("bad route")))
        assertFalse(shouldSettleDashboardTransientFailures(2))
        assertTrue(shouldSettleDashboardTransientFailures(3))
        assertTrue(
            shouldRefreshGatewayProfilesOnReady(
                lastRefreshedConnectionId = null,
                activeConnectionId = "connection-a",
                availability = GatewayAvailability.Ready,
            ),
        )
        assertFalse(
            shouldRefreshGatewayProfilesOnReady(
                lastRefreshedConnectionId = "connection-a",
                activeConnectionId = "connection-a",
                availability = GatewayAvailability.Ready,
            ),
        )
        assertFalse(
            shouldRefreshGatewayProfilesOnReady(
                lastRefreshedConnectionId = null,
                activeConnectionId = "connection-a",
                availability = GatewayAvailability.Unknown,
            ),
        )
        assertTrue(nativeDashboardBearerCompatible(listOf("basic", "nous")))
        assertTrue(
            nativeDashboardBearerCompatible(listOf("basic", "nous", "self-hosted")),
        )
    }
}
