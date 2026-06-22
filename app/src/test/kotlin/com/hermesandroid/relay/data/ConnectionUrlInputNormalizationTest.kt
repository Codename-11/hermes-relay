package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Connection.normalizeApiUrlInput] — hand-typed URL forgiveness added with
 * the route-probe visibility work (2026-06-11).
 *
 * Contract: bare hosts/IPs get `http://` and the surface's default port;
 * anything with an explicit scheme passes through verbatim (an
 * `https://host` may be a reverse proxy on 443 — appending :8642 would
 * break it, and a wrong scheme like `ws://` must survive so the field
 * validators can name the actual mistake).
 */
class ConnectionUrlInputNormalizationTest {

    @Test
    fun bareIp_getsSchemeAndDefaultPort() {
        assertEquals(
            "http://100.64.0.1:8642",
            Connection.normalizeApiUrlInput("100.64.0.1"),
        )
    }

    @Test
    fun bareHostWithPort_getsSchemeOnly() {
        assertEquals(
            "http://my-server:8642",
            Connection.normalizeApiUrlInput("my-server:8642"),
        )
    }

    @Test
    fun explicitHttpUrl_passesVerbatim() {
        assertEquals(
            "http://192.168.1.10:8642",
            Connection.normalizeApiUrlInput("http://192.168.1.10:8642"),
        )
    }

    @Test
    fun explicitHttpsWithoutPort_isNotPortDefaulted() {
        // Could be a reverse proxy on 443 — never append :8642 to an
        // explicitly-schemed URL.
        assertEquals(
            "https://hermes.example.com",
            Connection.normalizeApiUrlInput("https://hermes.example.com"),
        )
    }

    @Test
    fun wrongScheme_passesVerbatimForValidatorsToCatch() {
        assertEquals(
            "ws://host:8767",
            Connection.normalizeApiUrlInput("ws://host:8767"),
        )
    }

    @Test
    fun whitespaceAndTrailingSlash_areTrimmed() {
        assertEquals(
            "http://100.64.0.1:8642",
            Connection.normalizeApiUrlInput("  100.64.0.1/  "),
        )
    }

    @Test
    fun blankInput_staysBlank() {
        assertEquals("", Connection.normalizeApiUrlInput("   "))
    }

    @Test
    fun bareHostWithPath_getsSchemeButNoPort() {
        // A path makes naive ":8642" suffixing wrong — scheme only.
        assertEquals(
            "http://host/api",
            Connection.normalizeApiUrlInput("host/api"),
        )
    }

    @Test
    fun dashboardDefaultPort_isHonored() {
        assertEquals(
            "http://192.168.1.10:9119",
            Connection.normalizeApiUrlInput(
                "192.168.1.10",
                defaultPort = Connection.DEFAULT_DASHBOARD_PORT,
            ),
        )
    }

    @Test
    fun bareTailscaleHost_roundTripsThroughCandidateBuilder() {
        // End-to-end: the exact user journey from the bug report — typing a
        // bare Tailscale IP must yield a plain-HTTP (tls=false) candidate on
        // port 8642 with the tailscale role inferred.
        val normalized = Connection.normalizeApiUrlInput("100.64.0.1")
        val candidate = Connection.endpointCandidateFromApiUrl(
            role = "",
            priority = 1,
            apiServerUrl = normalized,
            relayUrl = "",
        )
        assertEquals("tailscale", candidate?.role)
        assertEquals("100.64.0.1", candidate?.api?.host)
        assertEquals(8642, candidate?.api?.port)
        assertEquals(false, candidate?.api?.tls)
    }
}
