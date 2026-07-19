package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Connection.mergeRouteCandidates] — URL edits must not wipe the stored
 * fallback routes. Before this helper, `updateApiServerUrl` /
 * `updateRelayUrl` / `saveApiAndProbeVoice` rebuilt `routeCandidates` from
 * just the edited URL, silently dropping the setup wizard's Tailscale route
 * (or a pairing payload's extra endpoints) — after which LAN↔Tailscale
 * roaming had nothing to roam to.
 */
class ConnectionRouteCandidateMergeTest {

    private fun candidate(
        role: String,
        priority: Int,
        host: String,
        port: Int = 8642,
        relayUrl: String = "ws://$host:8767",
    ): EndpointCandidate = EndpointCandidate(
        role = role,
        priority = priority,
        api = ApiEndpoint(host = host, port = port, tls = false),
        relay = RelayEndpoint(url = relayUrl),
    )

    @Test
    fun `preserves stored tailscale extra when primary is rebuilt`() {
        val rebuilt = Connection.buildRouteCandidates(
            apiServerUrl = "http://192.168.1.50:8642",
            relayUrl = "ws://192.168.1.50:8767",
        )
        val existing = listOf(
            candidate(role = "lan", priority = 0, host = "192.168.1.50"),
            candidate(role = "tailscale", priority = 1, host = "100.64.0.7"),
        )

        val merged = Connection.mergeRouteCandidates(rebuilt, existing)

        assertEquals(2, merged.size)
        assertEquals("lan", merged[0].role)
        assertEquals("tailscale", merged[1].role)
        assertEquals("100.64.0.7", merged[1].api?.host)
    }

    @Test
    fun `stored extras keep their relay url verbatim`() {
        val rebuilt = Connection.buildRouteCandidates(
            apiServerUrl = "http://192.168.1.50:8642",
            relayUrl = "ws://192.168.1.50:8767",
        )
        // Pairing payloads can carry relay URLs that differ from the
        // derive-from-api default (e.g. a reverse-proxied wss path).
        val payloadRelay = "wss://hermes.tail1234.ts.net:8767/relay"
        val existing = listOf(
            candidate(
                role = "tailscale",
                priority = 1,
                host = "hermes.tail1234.ts.net",
                relayUrl = payloadRelay,
            ),
        )

        val merged = Connection.mergeRouteCandidates(rebuilt, existing)

        assertEquals(payloadRelay, merged.first { it.role == "tailscale" }.relay?.url)
    }

    @Test
    fun `rebuilt entry wins a host-port collision with a stored extra`() {
        val rebuilt = Connection.buildRouteCandidates(
            apiServerUrl = "http://192.168.1.50:8642",
            relayUrl = "ws://192.168.1.50:8767",
            extraApiUrls = listOf("tailscale" to "http://100.64.0.7:8642"),
        )
        val existing = listOf(
            // Same host:port as the rebuilt extra but stale role/relay —
            // must NOT survive alongside it.
            candidate(
                role = "vpn-old",
                priority = 2,
                host = "100.64.0.7",
                relayUrl = "ws://stale.example:8767",
            ),
        )

        val merged = Connection.mergeRouteCandidates(rebuilt, existing)

        assertEquals(2, merged.size)
        val tailscale = merged.first { it.api?.host == "100.64.0.7" }
        assertEquals("tailscale", tailscale.role)
        assertEquals("ws://100.64.0.7:8767", tailscale.relay?.url)
    }

    @Test
    fun `stored primary is never preserved - rebuilt primary replaces it`() {
        val rebuilt = Connection.buildRouteCandidates(
            apiServerUrl = "http://10.0.0.99:8642",
            relayUrl = "ws://10.0.0.99:8767",
        )
        val existing = listOf(
            candidate(role = "lan", priority = 0, host = "192.168.1.50"),
            candidate(role = "tailscale", priority = 1, host = "100.64.0.7"),
        )

        val merged = Connection.mergeRouteCandidates(rebuilt, existing)

        assertEquals(2, merged.size)
        assertTrue(
            "old priority-0 host must be replaced by the edited URL",
            merged.none { it.api?.host == "192.168.1.50" },
        )
        assertEquals("10.0.0.99", merged.first { it.priority == 0 }.api?.host)
        assertEquals("100.64.0.7", merged.first { it.priority == 1 }.api?.host)
    }

    @Test
    fun `merge with no stored extras returns rebuilt list unchanged`() {
        val rebuilt = Connection.buildRouteCandidates(
            apiServerUrl = "http://192.168.1.50:8642",
            relayUrl = "ws://192.168.1.50:8767",
        )

        assertEquals(rebuilt, Connection.mergeRouteCandidates(rebuilt, emptyList()))
    }
}
