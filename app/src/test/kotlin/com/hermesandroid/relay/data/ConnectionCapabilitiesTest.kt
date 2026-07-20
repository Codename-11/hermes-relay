package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCapabilitiesTest {

    @Test
    fun dashboardOnlyConnection_exposesStandardHermesCapabilities() {
        val connection = connection(
            dashboardUrl = "https://hermes.example.com",
            apiServerUrl = "",
            relayUrl = "",
        )

        assertEquals("https://hermes.example.com", connection.primaryEndpointUrl)
        assertEquals("hermes.example.com", connection.primaryHost)
        assertTrue(connection.capabilities.dashboardGatewayConfigured)
        assertTrue(connection.capabilities.gatewayChatAvailable)
        assertTrue(connection.capabilities.manageAvailable)
        assertTrue(connection.capabilities.standardVoiceAvailable)
        assertTrue(connection.capabilities.chatConfigured)
        assertFalse(connection.capabilities.apiChatFallbackAvailable)
        assertFalse(connection.capabilities.relayFeaturesAvailable)
    }

    @Test
    fun legacyApiRecord_retainsDerivedDashboardAndAllConfiguredSurfaces() {
        val connection = connection(
            dashboardUrl = null,
            apiServerUrl = "http://192.168.1.25:8642",
            relayUrl = "ws://192.168.1.25:8767",
        )

        assertEquals("http://192.168.1.25:9119", connection.primaryEndpointUrl)
        assertTrue(connection.capabilities.dashboardGatewayConfigured)
        assertTrue(connection.capabilities.apiServerConfigured)
        assertTrue(connection.capabilities.relayConfigured)
    }

    @Test
    fun apiOnlyConnection_canChatWithoutRelay() {
        val connection = connection(
            dashboardUrl = "",
            apiServerUrl = "https://api.example.com",
            relayUrl = "",
        )

        // A blank dashboard retains the legacy conventional derivation when
        // an API endpoint is present.
        assertTrue(connection.capabilities.dashboardGatewayConfigured)
        assertTrue(connection.capabilities.apiChatFallbackAvailable)
        assertFalse(connection.capabilities.relayFeaturesAvailable)
    }

    @Test
    fun dashboardFirstLabel_usesDashboardWhenApiIsAbsent() {
        assertEquals(
            "hermes.example.com",
            Connection.extractDefaultLabel(
                dashboardUrl = "https://hermes.example.com:9119",
                apiServerUrl = "",
                relayUrl = "",
            ),
        )
    }

    private fun connection(
        dashboardUrl: String?,
        apiServerUrl: String,
        relayUrl: String,
    ) = Connection(
        id = "id-a",
        label = "Hermes",
        apiServerUrl = apiServerUrl,
        relayUrl = relayUrl,
        tokenStoreKey = "hermes_auth_id-a",
        dashboardUrl = dashboardUrl,
    )
}
