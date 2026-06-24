package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the connection-security rollup (the single source of truth behind the
 * in-app indicator). The headline correctness property: a plaintext route over
 * an overlay network (Tailscale/WireGuard) is **encrypted**, not "insecure".
 */
class ConnectionSecurityTest {

    private fun endpoint(role: String, security: String? = null) = EndpointCandidate(
        role = role,
        api = ApiEndpoint(host = "h", port = 8642),
        relay = RelayEndpoint(url = "ws://h:8767"),
        security = security,
    )

    @Test
    fun allTlsSurfaces_rollUpToTls() {
        val result = computeConnectionSecurity(
            apiUrl = "https://h:8642",
            dashboardUrl = "https://h:9119",
            relayUrl = "wss://h:8767",
            relayConfigured = true,
            activeEndpoint = endpoint("public"),
            isTailscaleDetected = false,
        )
        assertEquals(ConnectionSecurityLevel.Tls, result.level)
        assertEquals("TLS", result.mechanism)
        assertEquals(3, result.surfaces.size)
    }

    @Test
    fun plaintextOverTailscale_isEncryptedOverlay_notPlain() {
        val result = computeConnectionSecurity(
            apiUrl = "http://100.71.0.1:8642",
            dashboardUrl = "http://100.71.0.1:9119",
            relayUrl = "ws://100.71.0.1:8767",
            relayConfigured = true,
            activeEndpoint = endpoint("tailscale"),
            isTailscaleDetected = false,
        )
        assertEquals(ConnectionSecurityLevel.Overlay, result.level)
        assertEquals("Tailscale", result.mechanism)
        // The whole point: overlay counts as encrypted.
        assertEquals(true, result.isEncrypted)
    }

    @Test
    fun someTlsSomePlain_isMixed() {
        val result = computeConnectionSecurity(
            apiUrl = "https://h:8642",
            dashboardUrl = "https://h:9119",
            relayUrl = "ws://h:8767",
            relayConfigured = true,
            activeEndpoint = endpoint("lan"),
            isTailscaleDetected = false,
        )
        assertEquals(ConnectionSecurityLevel.Mixed, result.level)
        assertEquals(false, result.isEncrypted)
    }

    @Test
    fun allPlainLan_isPlain_withRoleMechanism() {
        val result = computeConnectionSecurity(
            apiUrl = "http://192.168.1.10:8642",
            dashboardUrl = "http://192.168.1.10:9119",
            relayUrl = "ws://192.168.1.10:8767",
            relayConfigured = true,
            activeEndpoint = endpoint("lan"),
            isTailscaleDetected = false,
        )
        assertEquals(ConnectionSecurityLevel.Plain, result.level)
        assertEquals("LAN", result.mechanism)
    }

    @Test
    fun relayNotConfigured_excludesRelaySurface() {
        val result = computeConnectionSecurity(
            apiUrl = "https://h:8642",
            dashboardUrl = "https://h:9119",
            relayUrl = "ws://h:8767", // plain, but relay not configured → ignored
            relayConfigured = false,
            activeEndpoint = endpoint("public"),
            isTailscaleDetected = false,
        )
        assertEquals(ConnectionSecurityLevel.Tls, result.level)
        assertEquals(2, result.surfaces.size)
    }

    @Test
    fun noSurfaces_isUnknown() {
        val result = computeConnectionSecurity(
            apiUrl = "",
            dashboardUrl = "",
            relayUrl = "",
            relayConfigured = false,
            activeEndpoint = null,
            isTailscaleDetected = false,
        )
        assertEquals(ConnectionSecurityLevel.Unknown, result.level)
        assertEquals(ConnectionSecurity.UNKNOWN, result)
    }

    @Test
    fun deviceTailscaleDetected_withSecurityHint_classifiesOverlay() {
        val result = computeConnectionSecurity(
            apiUrl = "http://host:8642",
            dashboardUrl = "http://host:9119",
            relayUrl = "ws://host:8767",
            relayConfigured = false,
            activeEndpoint = endpoint(role = "custom", security = "tailscale-magicdns"),
            isTailscaleDetected = true,
        )
        assertEquals(ConnectionSecurityLevel.Overlay, result.level)
        assertEquals("Tailscale", result.mechanism)
    }
}
