package com.hermesandroid.relay.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Resolution matrix for [resolveStreamingEndpointPreference] — the gateway
 * tier sits above the capability-preferred SSE endpoint, but only for "auto"
 * and only when the dashboard probe reports Ready.
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
    fun `auto falls back to capability preference for every non-ready state`() {
        listOf(
            GatewayAvailability.Unknown,
            GatewayAvailability.SignInRequired,
            GatewayAvailability.Unreachable,
            GatewayAvailability.Unsupported,
        ).forEach { availability ->
            assertEquals(
                "expected SSE fallback for $availability",
                "sessions",
                resolveStreamingEndpointPreference("auto", availability, fullCaps),
            )
        }
    }

    @Test
    fun `auto fallback respects capability ordering`() {
        assertEquals(
            "completions",
            resolveStreamingEndpointPreference(
                "auto",
                GatewayAvailability.SignInRequired,
                portableOnlyCaps,
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
