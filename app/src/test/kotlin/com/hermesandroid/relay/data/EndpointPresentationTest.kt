package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointPresentationTest {
    @Test
    fun `authenticated dashboard role never exposes its internal key`() {
        val candidate = EndpointCandidate(
            role = "authenticated_dashboard",
            dashboard = DashboardEndpoint("https://hermes.example.com"),
        )

        assertEquals("HTTPS Dashboard", candidate.displayLabel())
        assertTrue(candidate.isKnownRole())
        assertTrue(candidate.isDashboardOnlyRoute())
    }

    @Test
    fun `unknown transport is a custom route not a vpn`() {
        val candidate = EndpointCandidate(
            role = "reverse-proxy-west",
            dashboard = DashboardEndpoint("https://west.example.com"),
            api = ApiEndpoint("west.example.com", 8642, tls = true),
        )

        assertEquals("Custom route (reverse-proxy-west)", candidate.displayLabel())
        assertFalse(candidate.isDashboardOnlyRoute())
    }
}
