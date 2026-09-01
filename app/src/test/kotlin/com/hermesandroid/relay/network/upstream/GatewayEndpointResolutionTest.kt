package com.hermesandroid.relay.network.upstream

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Resolution matrix for [resolveStreamingEndpointPreference] — the gateway
 * tier is a stable owner for standard "auto" conversations. API capability
 * ordering applies only to true API-only compatibility connections.
 */
class GatewayEndpointResolutionTest {

    private val fullCaps = ServerCapabilities(
        sessionsApi = true,
        sessionsChatStream = true,
        runs = true,
        portable = true,
        healthy = true,
    )

    private val portableOnlyCaps = ServerCapabilities(
        sessionsApi = false,
        sessionsChatStream = false,
        runs = false,
        portable = true,
        healthy = true,
    )

    @Test
    fun `auto prefers gateway when ready`() {
        assertEquals(
            "gateway",
            resolveStreamingEndpointPreference("auto", GatewayAvailability.Ready, fullCaps),
        )
    }

    @Test
    fun `auto stays on gateway while cold-start availability is unresolved`() {
        assertEquals(
            "gateway",
            resolveStreamingEndpointPreference("auto", GatewayAvailability.Unknown, fullCaps),
        )
    }

    @Test
    fun `standard auto remains gateway owned after auth expiry or outage`() {
        listOf(
            GatewayAvailability.SignInRequired,
            GatewayAvailability.Unreachable,
            GatewayAvailability.Unsupported,
        ).forEach { availability ->
            assertEquals(
                "expected Gateway affinity for $availability",
                "gateway",
                resolveStreamingEndpointPreference("auto", availability, fullCaps),
            )
        }
    }

    @Test
    fun `API-only auto respects capability ordering`() {
        assertEquals(
            "sessions",
            resolveStreamingEndpointPreference(
                "auto",
                GatewayAvailability.SignInRequired,
                fullCaps,
                gatewayOwned = false,
            ),
        )
        assertEquals(
            "completions",
            resolveStreamingEndpointPreference(
                "auto",
                GatewayAvailability.SignInRequired,
                portableOnlyCaps,
                gatewayOwned = false,
            ),
        )
    }

    @Test
    fun `standard auto does not consult API health when gateway is unavailable`() {
        assertEquals(
            "gateway",
            resolveStreamingEndpointPreference(
                "auto",
                GatewayAvailability.Unreachable,
                ServerCapabilities.DISCONNECTED,
                gatewayOwned = true,
            ),
        )
    }

    @Test
    fun `manual API selection remains explicit compatibility mode`() {
        assertEquals(
            "sessions",
            resolveStreamingEndpointPreference(
                "sessions",
                GatewayAvailability.SignInRequired,
                fullCaps,
            ),
        )
    }

    @Test
    fun `manual picks pass through regardless of gateway state`() {
        listOf("gateway", "sessions", "completions", "runs").forEach { pick ->
            assertEquals(
                pick,
                resolveStreamingEndpointPreference(pick, GatewayAvailability.Unreachable, fullCaps),
            )
            assertEquals(
                pick,
                resolveStreamingEndpointPreference(pick, GatewayAvailability.Ready, fullCaps),
            )
        }
    }
}
