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
        assertEquals(0, connection.routeCandidates.size)
    }

    @Test
    fun buildRouteCandidates_createsLanAndTailscaleRoutes() {
        val routes = Connection.buildRouteCandidates(
            apiServerUrl = "http://192.168.1.25:8642",
            relayUrl = "ws://192.168.1.25:8767",
            extraApiUrls = listOf("tailscale" to "https://hermes.tail1234.ts.net:8642"),
        )

        assertEquals(2, routes.size)
        assertEquals("lan", routes[0].role)
        assertEquals("192.168.1.25", routes[0].api.host)
        assertEquals("ws://192.168.1.25:8767", routes[0].relay.url)
        assertEquals("tailscale", routes[1].role)
        assertEquals("hermes.tail1234.ts.net", routes[1].api.host)
        assertEquals("wss://hermes.tail1234.ts.net:8767", routes[1].relay.url)
    }

    @Test
    fun inferRouteRole_detectsTailscaleCgnat() {
        assertEquals("tailscale", Connection.inferRouteRole("https://100.75.1.2:8642"))
        assertEquals("lan", Connection.inferRouteRole("http://10.0.0.5:8642"))
        assertEquals("public", Connection.inferRouteRole("https://hermes.example.com:8642"))
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

