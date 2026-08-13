package com.hermesandroid.relay.network.shared

import com.hermesandroid.relay.data.ProxyEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginProxyTransportTest {
    private val pin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    @Test
    fun `derives all proxy surfaces from one authority`() {
        val routes = ProxyEndpoint("https://relay.example:9443", pinSha256 = pin)
            .toPluginProxyRoutesOrNull()!!
        assertEquals("relay.example:9443", routes.authority)
        assertEquals("https://relay.example:9443/relay", routes.relayHttpUrl)
        assertEquals("wss://relay.example:9443/relay/ws", routes.relayWebSocketUrl)
    }

    @Test
    fun `rejects incomplete or unsafe proxy advertisements`() {
        val invalid = listOf(
            ProxyEndpoint("http://relay.example:9443", pinSha256 = pin),
            ProxyEndpoint("https://relay.example:9443", pinSha256 = null),
            ProxyEndpoint("https://relay.example:9443", pinSha256 = "sha256/not-base64"),
            ProxyEndpoint("https://user@relay.example:9443", pinSha256 = pin),
            ProxyEndpoint("https://relay.example:9443?route=x", pinSha256 = pin),
            ProxyEndpoint("https://relay.example:9443/a/../b", pinSha256 = pin),
            ProxyEndpoint("https://relay.example:9443/a/%2e%2e/b", pinSha256 = pin),
            ProxyEndpoint("https://relay.example:9443/a%2fb", pinSha256 = pin),
            ProxyEndpoint("https://relay.example:9443/secure", pinSha256 = pin),
        )
        assertTrue(invalid.all { it.toPluginProxyRoutesOrNull() == null })
    }

    @Test
    fun `proxy pin remains scoped to advertised host and port`() {
        val routes = ProxyEndpoint("https://relay.example:9443", pinSha256 = pin)
            .toPluginProxyRoutesOrNull()!!
        assertEquals("relay.example:9443", routes.authority)
        assertNull(ProxyEndpoint("https://relay.example", pinSha256 = "sha256/")
            .toPluginProxyRoutesOrNull())
    }
}
