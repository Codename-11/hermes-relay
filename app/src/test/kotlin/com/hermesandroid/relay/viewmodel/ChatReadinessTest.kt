package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
