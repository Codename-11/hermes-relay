package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpoint
import com.hermesandroid.relay.ui.components.HermesPairingPayload
import com.hermesandroid.relay.ui.components.RelayPairing
import com.hermesandroid.relay.ui.components.resolvedDashboardIngressPairingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardConnectionDraftTest {
    @Test
    fun `dashboard setup materializes the existing draft without inventing API or Relay`() {
        val staged = materializeDashboardConnectionDraft(
            draft = PendingConnectionDraft(id = "draft-id", previousConnectionId = null),
            dashboardUrl = "https://hermes.example.com",
            discoveredHostname = "Docker-Server",
        )

        assertEquals("draft-id", staged.id)
        assertEquals("Docker-Server", staged.label)
        assertEquals("https://hermes.example.com", staged.pairingPayload?.dashboardUrl)
        assertFalse(staged.pairingPayload?.hasApiServer == true)
        assertNull(staged.pairingPayload?.relay)
        assertEquals("public", staged.pairingPayload?.endpoints?.single()?.role)
    }

    @Test
    fun `preallocated draft remains the setup owner until commit`() {
        assertEquals("draft", connectionSetupOwnerId("draft", null))
        assertEquals("draft", connectionSetupOwnerId("draft", "existing"))
        assertEquals("existing", connectionSetupOwnerId(null, "existing"))
        assertNull(connectionSetupOwnerId(null, null))
    }

    @Test
    fun `deferred ingress pair retains exact payload and ttl without exposing code`() {
        val payload = HermesPairingPayload(
            hermes = 3,
            dashboardUrl = "https://hermes.example.com",
            relay = RelayPairing(
                url = "wss://hermes.example.com/api/plugins/hermes-relay/transport",
                code = "SECRET",
            ),
            endpoints = listOf(
                EndpointCandidate(
                    role = "https",
                    dashboard = DashboardEndpoint("https://hermes.example.com"),
                    relay = RelayEndpoint(
                        "wss://hermes.example.com/api/plugins/hermes-relay/transport",
                    ),
                ),
            ),
        )
        val deferred = DeferredDashboardRelayPairing(
            connectionId = "draft-id",
            payload = payload,
            ttlSeconds = 86_400L,
            preserveStandardConfig = false,
        )

        assertEquals("draft-id", deferred.connectionId)
        assertSame(payload, deferred.payload)
        assertEquals(86_400L, deferred.ttlSeconds)
        assertFalse(deferred.toString().contains("SECRET"))
    }

    @Test
    fun `deferred ingress pair resumes only for exact active owner`() {
        assertTrue(deferredDashboardRelayPairingOwnsActiveConnection("draft", "draft"))
        assertFalse(deferredDashboardRelayPairingOwnsActiveConnection("draft", "other"))
        assertFalse(deferredDashboardRelayPairingOwnsActiveConnection("draft", null))
    }

    @Test
    fun `deferred ingress preserves standard routes only for an existing gateway`() {
        val existing = Connection(
            id = "existing",
            label = "Hermes",
            apiServerUrl = "",
            relayUrl = "",
            tokenStoreKey = Connection.buildTokenStoreKey("existing"),
            dashboardUrl = "https://hermes.example.com",
        )
        val onboardingPlaceholder = existing.copy(
            id = "onboarding",
            label = ConnectionViewModel.PLACEHOLDER_LABEL,
            tokenStoreKey = Connection.buildTokenStoreKey("onboarding"),
        )

        assertTrue(shouldPreserveStandardConfigForDeferredPairing(null, existing))
        assertFalse(shouldPreserveStandardConfigForDeferredPairing("draft", existing))
        assertFalse(shouldPreserveStandardConfigForDeferredPairing(null, onboardingPlaceholder))
        assertFalse(shouldPreserveStandardConfigForDeferredPairing(null, null))
    }

    @Test
    fun `active onboarding owner materializes ingress topology without persisting code`() {
        val current = Connection(
            id = "onboarding",
            label = ConnectionViewModel.PLACEHOLDER_LABEL,
            apiServerUrl = "",
            relayUrl = "",
            tokenStoreKey = Connection.buildTokenStoreKey("onboarding"),
        )
        val payload = HermesPairingPayload(
            dashboardUrl = "https://hermes.example.com/",
            relay = RelayPairing(
                url = "wss://hermes.example.com/api/plugins/hermes-relay/transport",
                code = "SECRET",
            ),
            endpoints = listOf(
                EndpointCandidate(
                    role = "https",
                    dashboard = DashboardEndpoint("https://hermes.example.com"),
                    relay = RelayEndpoint(
                        "wss://hermes.example.com/api/plugins/hermes-relay/transport",
                    ),
                ),
            ),
        )

        val materialized = connectionWithDeferredDashboardRelayTopology(current, payload)

        assertEquals("hermes.example.com", materialized.label)
        assertEquals("https://hermes.example.com", materialized.dashboardUrl)
        assertEquals(payload.relay?.url, materialized.relayUrl)
        assertEquals(listOf("https"), materialized.routeCandidates.map { it.role })
        assertFalse(materialized.toString().contains("SECRET"))
    }

    @Test
    fun `deferred public ingress keeps complete route topology and matching relay`() {
        val current = Connection(
            id = "onboarding",
            label = ConnectionViewModel.PLACEHOLDER_LABEL,
            apiServerUrl = "",
            relayUrl = "",
            tokenStoreKey = Connection.buildTokenStoreKey("onboarding"),
        )
        val endpoints = listOf(
            EndpointCandidate(
                role = "tailscale",
                priority = 0,
                dashboard = DashboardEndpoint("http://100.71.8.56:9119"),
                relay = RelayEndpoint(
                    "ws://100.71.8.56:9119/api/plugins/hermes-relay/transport",
                ),
            ),
            EndpointCandidate(
                role = "public",
                priority = 1,
                dashboard = DashboardEndpoint("https://hermes.example.com"),
                relay = RelayEndpoint(
                    "wss://hermes.example.com/api/plugins/hermes-relay/transport",
                    "wss",
                ),
            ),
        )
        val payload = HermesPairingPayload(
            dashboardUrl = "https://hermes.example.com",
            relay = RelayPairing(
                url = "ws://100.71.8.56:8767",
                code = "SECRET",
            ),
            endpoints = endpoints,
        )
        val resolved = requireNotNull(resolvedDashboardIngressPairingPayload(payload))

        val materialized = connectionWithDeferredDashboardRelayTopology(current, resolved)

        assertEquals(
            "wss://hermes.example.com/api/plugins/hermes-relay/transport",
            materialized.relayUrl,
        )
        assertEquals(listOf("tailscale", "public"), materialized.routeCandidates.map { it.role })
        assertEquals(listOf(0, 1), materialized.routeCandidates.map { it.priority })
        assertFalse(materialized.toString().contains("SECRET"))
    }
}
