package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.network.upstream.GatewayAvailability
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
    fun `ready with reachable API when Gateway is unavailable`() {
        assertTrue(
            isChatTransportReady(
                apiClientPresent = true,
                apiReachable = true,
                gatewayAvailability = GatewayAvailability.Unreachable,
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
