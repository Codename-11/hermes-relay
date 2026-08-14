package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleRoutePresenceTest {
    @Test
    fun primaryTailnetIpCountsAsConfiguredRoute() {
        assertTrue(
            hasConfiguredTailscaleRoute(
                endpoints = emptyList(),
                primaryEndpointUrl = "http://100.75.1.2:9119",
            ),
        )
    }

    @Test
    fun explicitTailscaleCandidateStillCounts() {
        assertTrue(
            hasConfiguredTailscaleRoute(
                endpoints = listOf(
                    EndpointCandidate(
                        role = "tailscale",
                        api = ApiEndpoint("server.ts.net", 8642),
                    ),
                ),
                primaryEndpointUrl = "http://192.168.1.2:9119",
            ),
        )
    }

    @Test
    fun ordinaryLanDoesNotCount() {
        assertFalse(
            hasConfiguredTailscaleRoute(
                endpoints = emptyList(),
                primaryEndpointUrl = "http://192.168.1.2:9119",
            ),
        )
    }
}
