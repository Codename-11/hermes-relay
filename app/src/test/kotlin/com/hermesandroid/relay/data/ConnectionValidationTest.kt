package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionValidationTest {

    @Test
    fun dashboardOnlyConnection_isValid() {
        assertNull(
            ConnectionValidation.validateConnectionEndpoints(
                dashboardUrl = "https://hermes.example.com",
                apiServerUrl = "",
                relayUrl = "",
            ),
        )
    }

    @Test
    fun optionalApiAndRelay_acceptBlankValues() {
        assertNull(ConnectionValidation.validateOptionalApiServerUrl(""))
        assertNull(ConnectionValidation.validateOptionalRelayUrl("  "))
    }

    @Test
    fun connectionWithoutAnyEndpoint_isRejected() {
        assertEquals(
            "Configure at least one Hermes endpoint",
            ConnectionValidation.validateConnectionEndpoints(null, "", ""),
        )
    }

    @Test
    fun malformedConfiguredOptionalEndpoint_isRejected() {
        assertNotNull(
            ConnectionValidation.validateConnectionEndpoints(
                dashboardUrl = "https://hermes.example.com",
                apiServerUrl = "ws://wrong.example.com",
                relayUrl = "",
            ),
        )
    }

    @Test
    fun duplicateDetection_usesDashboardIdentityForDashboardOnlyConnections() {
        val existing = Connection(
            id = "existing",
            label = "Hermes",
            apiServerUrl = "",
            relayUrl = "",
            tokenStoreKey = "hermes_auth_existing",
            dashboardUrl = "https://hermes.example.com/",
        )

        val duplicate = ConnectionValidation.findDuplicate(
            connections = listOf(existing),
            apiServerUrl = "",
            relayUrl = "",
            dashboardUrl = "https://HERMES.example.com",
        )

        assertEquals(existing, duplicate)
    }

    @Test
    fun duplicateDetection_preservesLegacyApiAndRelayMatch() {
        val existing = Connection(
            id = "existing",
            label = "Hermes",
            apiServerUrl = "http://localhost:8642",
            relayUrl = "ws://localhost:8767",
            tokenStoreKey = "hermes_auth_existing",
        )

        assertEquals(
            existing,
            ConnectionValidation.findDuplicate(
                connections = listOf(existing),
                apiServerUrl = "HTTP://LOCALHOST:8642/",
                relayUrl = "WS://LOCALHOST:8767/",
            ),
        )
    }

    @Test
    fun duplicateDetection_matchesLegacyApiDerivedDashboard() {
        val existing = Connection(
            id = "existing",
            label = "Hermes",
            apiServerUrl = "http://hermes.local:8642",
            relayUrl = "",
            tokenStoreKey = "hermes_auth_existing",
        )

        assertEquals(
            existing,
            ConnectionValidation.findDuplicate(
                connections = listOf(existing),
                apiServerUrl = "",
                relayUrl = "",
                dashboardUrl = "http://HERMES.local:9119/",
            ),
        )
    }

    @Test
    fun duplicateDetection_excludesActiveConnection() {
        val existing = Connection(
            id = "existing",
            label = "Hermes",
            apiServerUrl = "",
            relayUrl = "",
            tokenStoreKey = "hermes_auth_existing",
            dashboardUrl = "https://hermes.example.com",
        )

        assertNull(
            ConnectionValidation.findDuplicate(
                connections = listOf(existing),
                apiServerUrl = "",
                relayUrl = "",
                dashboardUrl = "https://hermes.example.com",
                excludeId = existing.id,
            ),
        )
    }

    @Test
    fun duplicateDetection_keepsDifferentDashboardPortsDistinct() {
        val existing = Connection(
            id = "existing",
            label = "Hermes",
            apiServerUrl = "",
            relayUrl = "",
            tokenStoreKey = "hermes_auth_existing",
            dashboardUrl = "https://hermes.example.com:9119",
        )

        assertNull(
            ConnectionValidation.findDuplicate(
                connections = listOf(existing),
                apiServerUrl = "",
                relayUrl = "",
                dashboardUrl = "https://hermes.example.com:9120",
            ),
        )
    }
}
