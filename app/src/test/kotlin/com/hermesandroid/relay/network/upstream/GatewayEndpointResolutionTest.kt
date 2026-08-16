package com.hermesandroid.relay.network.upstream

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Resolution matrix for [resolveStreamingEndpointPreference] — the gateway
 * tier sits above the capability-preferred SSE endpoint for "auto". An
 * unresolved cold-start probe remains on Gateway until it produces a
 * definitive fallback verdict.
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
    fun `auto falls back after a definitive non-ready verdict`() {
        listOf(
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
