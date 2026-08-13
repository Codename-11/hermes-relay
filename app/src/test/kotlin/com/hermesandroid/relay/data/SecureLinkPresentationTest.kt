package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureLinkPresentationTest {
    private val pin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    @Test
    fun `wire role is presented as Hermes Secure Link`() {
        val candidate = EndpointCandidate(
            role = "plugin_proxy",
            proxy = ProxyEndpoint("https://relay.example:9443", pinSha256 = pin),
            security = "pinned_tls",
        )
        assertEquals("plugin_proxy", candidate.role)
        assertEquals("pinned_tls", candidate.security)
        assertEquals("Hermes Secure Link", candidate.displayLabel())
        assertEquals("https://relay.example:9443", candidate.presentationRouteUrl())
    }

    @Test
    fun `service inventory is normalized and identifies partial protection`() {
        val partial = EndpointCandidate(
            role = "plugin_proxy",
            proxy = ProxyEndpoint(
                "https://relay.example:9443",
                pinSha256 = pin,
                surfaces = listOf("Relay", "api", "relay", "unknown"),
            ),
        )
        assertEquals(listOf("relay", "api"), partial.secureLinkServices())
        assertFalse(partial.secureLinkCoversAllServices())

        val complete = partial.copy(
            proxy = partial.proxy?.copy(surfaces = listOf("relay", "api", "dashboard")),
        )
        assertTrue(complete.secureLinkCoversAllServices())
    }

    @Test
    fun `invalid pin cannot claim Secure Link protection`() {
        val candidate = EndpointCandidate(
            role = "plugin_proxy",
            proxy = ProxyEndpoint("https://relay.example:9443", pinSha256 = "sha256/bad"),
        )
        assertFalse(candidate.hasSecureProxy())
        assertTrue(candidate.secureLinkServices().isEmpty())
    }
}
