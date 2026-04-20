package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.data.isKnownRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [parseHermesPairingQr] — focuses on the ADR 24
 * multi-endpoint pairing extension (2026-04-19):
 *
 *  - v1 QRs (no `hermes` field, no `endpoints`) synthesize a single
 *    priority-0 candidate from the top-level fields.
 *  - v2 QRs (`hermes: 2`, no `endpoints`) behave identically to v1 — the
 *    ADR's backward-compat path doesn't distinguish the two.
 *  - v3 QRs (`hermes: 3`, explicit `endpoints`) round-trip the list
 *    verbatim — priority order preserved, role case preserved, unknown
 *    roles tolerated so operators can label custom VPNs.
 *
 * Pure parser test — no Android Context, no DataStore, no coroutines. The
 * parser is library code that does no I/O, so this file stays plain JUnit.
 */
class HermesPairingPayloadTest {

    @Test
    fun v1LegacyPayload_synthesizesLanEndpoint() {
        // v1 QR: no `hermes` field at all. Defaults to version 1. Parser
        // must synthesize a single priority-0 LAN endpoint from the
        // top-level host/port/tls + relay block.
        val raw = """
            {
              "host": "192.168.1.100",
              "port": 8642,
              "key": "bearer-token",
              "tls": false,
              "relay": { "url": "ws://192.168.1.100:8767", "code": "ABCD12" }
            }
        """.trimIndent()

        val payload = parseHermesPairingQr(raw)
        assertNotNull(payload)
        val endpoints = payload!!.endpoints
        assertNotNull("v1 parse must synthesize endpoints array", endpoints)
        assertEquals(1, endpoints!!.size)

        val ep = endpoints[0]
        assertEquals("lan", ep.role)
        assertEquals(0, ep.priority)
        assertEquals("192.168.1.100", ep.api.host)
        assertEquals(8642, ep.api.port)
        assertFalse(ep.api.tls)
        assertEquals("http://192.168.1.100:8642", ep.api.url)
        assertEquals("ws://192.168.1.100:8767", ep.relay.url)
    }

    @Test
    fun v1LegacyPayload_withTailscaleHost_synthesizesTailscaleEndpoint() {
        // v1 QR pointing at a Tailscale MagicDNS host. Parser heuristic
        // (host.endsWith(".ts.net") || host.startsWith("100.")) must flag
        // this as role=tailscale even though no endpoints array is present.
        val raw = """
            {
              "host": "hermes.tail-scale.ts.net",
              "port": 8642,
              "key": "",
              "tls": true,
              "relay": { "url": "wss://hermes.tail-scale.ts.net:8767", "code": "TSCALE" }
            }
        """.trimIndent()

        val payload = parseHermesPairingQr(raw)
        assertNotNull(payload)
        val endpoints = payload!!.endpoints
        assertNotNull(endpoints)
        assertEquals(1, endpoints!!.size)
        assertEquals("tailscale", endpoints[0].role)
        assertTrue(endpoints[0].api.tls)
    }

    @Test
    fun v1LegacyPayload_withCgnatIp_synthesizesTailscaleEndpoint() {
        // CGNAT-range IPv4 (100.64.0.0/10) also trips the tailscale heuristic.
        // Broader check (any `100.`-prefix) mirrors the inline heuristic in
        // parseHermesPairingQr — tolerant of operator labeling.
        val raw = """
            {
              "host": "100.120.45.67",
              "port": 8642,
              "key": "",
              "tls": false,
              "relay": { "url": "ws://100.120.45.67:8767", "code": "AAAAAA" }
            }
        """.trimIndent()

        val payload = parseHermesPairingQr(raw)
        assertNotNull(payload)
        assertEquals("tailscale", payload!!.endpoints!![0].role)
    }

    @Test
    fun v2Payload_noEndpoints_behavesLikeV1() {
        // v2 QR (`hermes: 2`) WITHOUT an endpoints array. ADR 24 says this
        // must synthesize a single priority-0 candidate — identical to v1.
        val raw = """
            {
              "hermes": 2,
              "host": "192.168.1.50",
              "port": 8642,
              "key": "optional-api-key",
              "tls": false,
              "relay": {
                "url": "ws://192.168.1.50:8767",
                "code": "ABC123",
                "ttl_seconds": 2592000,
                "grants": { "terminal": 2592000, "bridge": 604800 },
                "transport_hint": "ws"
              },
              "sig": "base64-sig-here"
            }
        """.trimIndent()

        val payload = parseHermesPairingQr(raw)
        assertNotNull(payload)
        assertEquals(2, payload!!.hermes)
        assertEquals("base64-sig-here", payload.sig)

        val endpoints = payload.endpoints
        assertNotNull(endpoints)
        assertEquals(1, endpoints!!.size)
        assertEquals("lan", endpoints[0].role)
        assertEquals(0, endpoints[0].priority)
        assertEquals("ws://192.168.1.50:8767", endpoints[0].relay.url)
        // The per-endpoint RelayEndpoint carries ONLY url + transport_hint.
        // `code`, `ttl_seconds`, `grants` stay on the top-level RelayPairing
        // — that split is a load-bearing ADR 24 decision.
        assertEquals("ws", endpoints[0].relay.transportHint)
    }

    @Test
    fun v3Payload_explicitEndpoints_roundTripVerbatim() {
        // v3 QR: priorities 0 / 1 / 2 with LAN / Tailscale / public roles.
        // Parser must preserve order (priority is meaningful, not alphabetic)
        // and all per-endpoint fields.
        val raw = """
            {
              "hermes": 3,
              "host": "192.168.1.100",
              "port": 8642,
              "key": "",
              "tls": false,
              "relay": {
                "url": "ws://192.168.1.100:8767",
                "code": "ABC123",
                "ttl_seconds": 2592000,
                "transport_hint": "ws"
              },
              "endpoints": [
                { "role": "lan", "priority": 0,
                  "api":   { "host": "192.168.1.100", "port": 8642, "tls": false },
                  "relay": { "url": "ws://192.168.1.100:8767",
                             "transport_hint": "ws" } },
                { "role": "tailscale", "priority": 1,
                  "api":   { "host": "hermes.tail-scale.ts.net", "port": 8642, "tls": true },
                  "relay": { "url": "wss://hermes.tail-scale.ts.net:8767",
                             "transport_hint": "wss" } },
                { "role": "public", "priority": 2,
                  "api":   { "host": "hermes.example.com", "port": 443, "tls": true },
                  "relay": { "url": "wss://hermes.example.com/relay",
                             "transport_hint": "wss" } }
              ],
              "sig": "base64-hmac-sha256"
            }
        """.trimIndent()

        val payload = parseHermesPairingQr(raw)
        assertNotNull(payload)
        assertEquals(3, payload!!.hermes)

        val endpoints = payload.endpoints
        assertNotNull(endpoints)
        assertEquals(3, endpoints!!.size)

        // Priority-0 LAN entry — unchanged from the wire.
        assertEquals("lan", endpoints[0].role)
        assertEquals(0, endpoints[0].priority)
        assertEquals("192.168.1.100", endpoints[0].api.host)
        assertFalse(endpoints[0].api.tls)
        assertEquals("ws://192.168.1.100:8767", endpoints[0].relay.url)
        assertEquals("ws", endpoints[0].relay.transportHint)

        // Priority-1 Tailscale — tls + wss must round-trip.
        assertEquals("tailscale", endpoints[1].role)
        assertEquals(1, endpoints[1].priority)
        assertEquals("hermes.tail-scale.ts.net", endpoints[1].api.host)
        assertTrue(endpoints[1].api.tls)
        assertEquals("wss", endpoints[1].relay.transportHint)
        assertEquals("https://hermes.tail-scale.ts.net:8642", endpoints[1].api.url)

        // Priority-2 public — 443 + path in relay URL preserved.
        assertEquals("public", endpoints[2].role)
        assertEquals(2, endpoints[2].priority)
        assertEquals(443, endpoints[2].api.port)
        assertEquals("wss://hermes.example.com/relay", endpoints[2].relay.url)
    }

    @Test
    fun unknownRole_parsesAndDisplaysGenericLabel() {
        // Open-string role contract: operators can label any mesh VPN.
        // Parser must accept it unchanged; displayLabel() must fall
        // through to the "Custom VPN (<role>)" path.
        val raw = """
            {
              "hermes": 3,
              "host": "10.8.0.1",
              "port": 8642,
              "key": "",
              "tls": false,
              "relay": { "url": "ws://10.8.0.1:8767", "code": "WGEU01" },
              "endpoints": [
                { "role": "wireguard-eu", "priority": 0,
                  "api":   { "host": "10.8.0.1", "port": 8642, "tls": false },
                  "relay": { "url": "ws://10.8.0.1:8767",
                             "transport_hint": "ws" } }
              ]
            }
        """.trimIndent()

        val payload = parseHermesPairingQr(raw)
        assertNotNull(payload)
        val ep = payload!!.endpoints!![0]
        assertEquals("wireguard-eu", ep.role)
        assertFalse("unknown role must NOT be flagged as known", ep.isKnownRole())
        assertEquals("Custom VPN (wireguard-eu)", ep.displayLabel())
    }

    @Test
    fun roleCaseIsPreservedVerbatim() {
        // ADR 24: role strings are embedded verbatim for HMAC canonicalization
        // — NO .lower(), NO .strip(). Parser must preserve "LAN" as-is.
        // displayLabel() normalizes for UI but leaves the underlying string
        // alone.
        val raw = """
            {
              "hermes": 3,
              "host": "192.168.1.100",
              "port": 8642,
              "key": "",
              "tls": false,
              "relay": { "url": "ws://192.168.1.100:8767", "code": "ABCDEF" },
              "endpoints": [
                { "role": "LAN", "priority": 0,
                  "api":   { "host": "192.168.1.100", "port": 8642, "tls": false },
                  "relay": { "url": "ws://192.168.1.100:8767" } }
              ]
            }
        """.trimIndent()

        val payload = parseHermesPairingQr(raw)
        assertNotNull(payload)
        val ep = payload!!.endpoints!![0]
        assertEquals("LAN", ep.role)
        // Case-insensitive isKnownRole / displayLabel still recognize it.
        assertTrue(ep.isKnownRole())
        assertEquals("LAN", ep.displayLabel())
        // transportHint defaulted to null because the wire didn't carry it.
        assertNull(ep.relay.transportHint)
    }

    @Test
    fun missingHost_rejectsPayload() {
        // The parser's minimum contract: a payload without `host` is not
        // a pair QR — return null so the scanner keeps scanning.
        val raw = """{ "hermes": 3, "port": 8642 }"""
        assertNull(parseHermesPairingQr(raw))
    }

    @Test
    fun invalidJson_rejectsPayload() {
        // Defensive: malformed JSON must not throw out of the parser.
        assertNull(parseHermesPairingQr("not-json-at-all"))
        assertNull(parseHermesPairingQr(""))
    }
}
