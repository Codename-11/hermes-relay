package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayEndpointContractTest {

    @Test
    fun `equivalent relay inputs map to one route pair`() {
        val cases = listOf(
            Case("wss://relay.example.test", "wss://relay.example.test/ws", "https://relay.example.test/health"),
            Case("wss://relay.example.test/", "wss://relay.example.test/ws", "https://relay.example.test/health"),
            Case("wss://relay.example.test/ws", "wss://relay.example.test/ws", "https://relay.example.test/health"),
            Case("https://relay.example.test/health", "wss://relay.example.test/ws", "https://relay.example.test/health"),
            Case("wss://relay.example.test/relay", "wss://relay.example.test/relay/ws", "https://relay.example.test/relay/health"),
            Case("wss://relay.example.test/relay/", "wss://relay.example.test/relay/ws", "https://relay.example.test/relay/health"),
            Case("wss://relay.example.test/relay/ws", "wss://relay.example.test/relay/ws", "https://relay.example.test/relay/health"),
            Case("https://relay.example.test/relay/health", "wss://relay.example.test/relay/ws", "https://relay.example.test/relay/health"),
            Case("ws://192.0.2.10:8767", "ws://192.0.2.10:8767/ws", "http://192.0.2.10:8767/health"),
            Case("wss://[2001:db8::7]:9443/relay/ws", "wss://[2001:db8::7]:9443/relay/ws", "https://[2001:db8::7]:9443/relay/health"),
            Case("wss://relay.example.test:8443/tailscale/custom", "wss://relay.example.test:8443/tailscale/custom/ws", "https://relay.example.test:8443/tailscale/custom/health"),
            Case("wss://relay.example.test/hermes%20relay", "wss://relay.example.test/hermes%20relay/ws", "https://relay.example.test/hermes%20relay/health"),
        )

        cases.forEach { case ->
            val endpoints = requireNotNull(RelayEndpointContract.parseOrNull(case.input)) { case.input }
            assertEquals(case.input, case.webSocket, endpoints.webSocketUrl)
            assertEquals(case.input, case.health, endpoints.healthUrl)
        }
    }

    @Test
    fun `normalization is idempotent for derived socket and health routes`() {
        val first = requireNotNull(RelayEndpointContract.parseOrNull("wss://relay.example.test/relay/ws/"))

        assertEquals(first, RelayEndpointContract.parseOrNull(first.webSocketUrl))
        assertEquals(first, RelayEndpointContract.parseOrNull(first.healthUrl))
    }

    @Test
    fun `dashboard plugin ingress is recognized across canonical route forms`() {
        val base = "https://dashboard.example.test/api/plugins/hermes-relay/transport"

        assertTrue(isDashboardRelayIngressUrl(base))
        assertTrue(isDashboardRelayIngressUrl(base.replace("https://", "wss://") + "/ws"))
        assertTrue(isDashboardRelayIngressUrl("$base/health"))
        assertTrue(
            isDashboardRelayIngressUrl(
                "wss://dashboard.example.test/base/api/plugins/hermes-relay/transport/ws",
            ),
        )
        assertFalse(isDashboardRelayIngressUrl("wss://dashboard.example.test/relay/ws"))
        assertFalse(
            isDashboardRelayIngressUrl(
                "wss://dashboard.example.test/not-api/plugins/hermes-relay/transportish/ws",
            ),
        )
    }

    @Test
    fun `unsafe or malformed relay inputs fail closed`() {
        listOf(
            "",
            "relay.example.test",
            "ftp://relay.example.test/relay",
            "wss://@relay.example.test/relay",
            "wss://user:secret@relay.example.test/relay",
            "wss://relay.example.test/relay?token=secret",
            "wss://relay.example.test/relay#fragment",
            "wss://relay.example.test:99999/relay",
            "wss://relay example.test/relay",
            "wss://relay.example.test/relay/%",
            "wss://relay.example.test/relay/%2Fhidden",
            "wss://relay.example.test/relay/%2e%2e",
            "wss://relay.example.test/relay/../admin",
            "wss://relay.example.test/relay//nested",
        ).forEach { input ->
            assertNull(input, RelayEndpointContract.parseOrNull(input))
        }
    }

    private data class Case(
        val input: String,
        val webSocket: String,
        val health: String,
    )
}
