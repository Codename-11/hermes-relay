package com.hermesandroid.relay.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionDashboardFieldsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun deriveDefaultDashboardUrl_usesSameHostAndDashboardPort() {
        assertEquals(
            "http://example.local:9119",
            Connection.deriveDefaultDashboardUrl("http://example.local:8642"),
        )
    }

    @Test
    fun deriveDefaultDashboardUrl_preservesHttpsScheme() {
        assertEquals(
            "https://hermes.example.com:9119",
            Connection.deriveDefaultDashboardUrl("https://hermes.example.com:8642"),
        )
    }

    @Test
    fun deriveDefaultDashboardUrl_wrapsIpv6Host() {
        assertEquals(
            "http://[::1]:9119",
            Connection.deriveDefaultDashboardUrl("http://[::1]:8642"),
        )
    }

    @Test
    fun deriveDefaultDashboardUrl_rejectsUnsupportedScheme() {
        assertNull(Connection.deriveDefaultDashboardUrl("ws://localhost:8767"))
    }

    @Test
    fun resolvedDashboardUrl_usesExplicitOverride() {
        val connection = sampleConnection(
            dashboardUrl = "https://dashboard.example.com",
        )

        assertEquals("https://dashboard.example.com", connection.resolvedDashboardUrl)
    }

    @Test
    fun resolvedDashboardUrl_derivesWhenMissing() {
        val connection = sampleConnection(dashboardUrl = null)

        assertEquals("http://localhost:9119", connection.resolvedDashboardUrl)
    }

    @Test
    fun legacySerializedConnection_decodesWithDashboardDefaults() {
        val legacyJson = """
            {
              "id": "conn-1",
              "label": "local",
              "apiServerUrl": "http://localhost:8642",
              "relayUrl": "ws://localhost:8767",
              "tokenStoreKey": "hermes_auth_conn"
            }
        """.trimIndent()

        val connection = json.decodeFromString<Connection>(legacyJson)

        assertNull(connection.dashboardUrl)
        assertEquals("http://localhost:9119", connection.resolvedDashboardUrl)
        assertTrue(Connection.isAutoManagedDashboardUrl(connection.dashboardUrl, connection.apiServerUrl))
    }

    private fun sampleConnection(
        dashboardUrl: String? = null,
    ): Connection = Connection(
        id = "conn-1",
        label = "local",
        apiServerUrl = "http://localhost:8642",
        relayUrl = "ws://localhost:8767",
        tokenStoreKey = "hermes_auth_conn",
        dashboardUrl = dashboardUrl,
    )
}

