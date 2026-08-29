package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `first committed gateway activates without a connection switch teardown`() {
        assertTrue(shouldActivateCommittedDraftWithoutSwitch(null))
        assertFalse(shouldActivateCommittedDraftWithoutSwitch("existing-gateway"))
    }

    @Test
    fun `preallocated draft remains the setup owner until commit`() {
        assertEquals("draft", connectionSetupOwnerId("draft", null))
        assertEquals("draft", connectionSetupOwnerId("draft", "existing"))
        assertEquals("existing", connectionSetupOwnerId(null, "existing"))
        assertNull(connectionSetupOwnerId(null, null))
    }
}
