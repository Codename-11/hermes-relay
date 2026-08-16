package com.hermesandroid.relay.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionModelUiStateTest {
    @Test
    fun `live session identity wins over profile defaults`() {
        val state = resolveSessionModelUiState(
            hasSession = true,
            pendingModel = null,
            pendingProvider = null,
            gatewayModel = "session-model",
            gatewayProvider = "session-provider",
            persistedSessionModel = "persisted-model",
            profileDefaultModel = "profile-default",
            serverDefaultModel = "server-default",
        )

        assertEquals("session-model", state.model)
        assertEquals("session-provider", state.provider)
        assertEquals("session-model", state.pickerModel)
        assertFalse(state.inheritsProfileDefault)
    }

    @Test
    fun `persisted session model beats profile while resume info is pending`() {
        val state = resolveSessionModelUiState(
            hasSession = true,
            pendingModel = null,
            pendingProvider = null,
            gatewayModel = null,
            gatewayProvider = null,
            persistedSessionModel = "persisted-model",
            profileDefaultModel = "profile-default",
            serverDefaultModel = "server-default",
        )

        assertEquals("persisted-model", state.model)
        assertEquals("persisted-model", state.pickerModel)
        assertFalse(state.inheritsProfileDefault)
    }

    @Test
    fun `fresh draft inherits profile without turning it into an explicit pick`() {
        val state = resolveSessionModelUiState(
            hasSession = false,
            pendingModel = null,
            pendingProvider = null,
            gatewayModel = "launch-profile-model",
            gatewayProvider = "launch-provider",
            persistedSessionModel = null,
            profileDefaultModel = "selected-profile-default",
            serverDefaultModel = "server-default",
        )

        assertEquals("selected-profile-default", state.model)
        assertNull(state.pickerModel)
        assertTrue(state.inheritsProfileDefault)
    }

    @Test
    fun `fresh draft manual pick remains pending session override`() {
        val state = resolveSessionModelUiState(
            hasSession = false,
            pendingModel = "manual-model",
            pendingProvider = "manual-provider",
            gatewayModel = "gateway-default",
            gatewayProvider = "gateway-provider",
            persistedSessionModel = null,
            profileDefaultModel = "profile-default",
            serverDefaultModel = "server-default",
        )

        assertEquals("manual-model", state.model)
        assertEquals("manual-provider", state.provider)
        assertEquals("manual-model", state.pickerModel)
        assertFalse(state.inheritsProfileDefault)
    }
}
