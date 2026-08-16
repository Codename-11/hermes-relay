package com.hermesandroid.relay.network.upstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOptionsResponseFenceTest {
    @Test
    fun acceptsOnlySameGenerationAndProfile() {
        assertTrue(isCurrentModelOptionsResponse(4, 4, "connection::alpha", "connection::alpha"))
        assertFalse(isCurrentModelOptionsResponse(3, 4, "connection::alpha", "connection::alpha"))
        assertFalse(isCurrentModelOptionsResponse(4, 4, "connection::alpha", "connection::beta"))
    }

    @Test
    fun catalogRefreshNeverPublishesDefaultIdentity() {
        val options = GatewayModelOptions(
            providers = emptyList(),
            currentModel = "server-default",
            currentProvider = "server",
        )

        assertNull(
            modelOptionsIdentityToPublish(
                catalogOnly = true,
                hasLiveSession = true,
                sessionIdentity = GatewayModelIdentity("session-model", "session-provider"),
                options = options,
            ),
        )
    }

    @Test
    fun normalRefreshKeepsCanonicalLiveSessionIdentity() {
        val sessionIdentity = GatewayModelIdentity("session-model", "session-provider")
        val options = GatewayModelOptions(
            providers = emptyList(),
            currentModel = "server-default",
            currentProvider = "server",
        )

        assertEquals(
            sessionIdentity,
            modelOptionsIdentityToPublish(
                catalogOnly = false,
                hasLiveSession = true,
                sessionIdentity = sessionIdentity,
                options = options,
            ),
        )
    }
}
