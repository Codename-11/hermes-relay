package com.hermesandroid.relay.util

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayErrorClassifierTest {
    @Test
    fun connectivityErrorsAreClassifiedForSnackbarSuppression() {
        // The "can't reach the server" family the themed banner owns.
        assertTrue(isConnectivityError(ConnectException("Connection refused")))
        assertTrue(isConnectivityError(UnknownHostException("host")))
        assertTrue(isConnectivityError(SocketTimeoutException("timeout")))
        assertTrue(isConnectivityError(IOException("read timeout")))
    }

    @Test
    fun nonConnectivityErrorsAreNotSuppressed() {
        // Actionable / unrelated errors must still surface a snackbar.
        assertFalse(isConnectivityError(null))
        assertFalse(isConnectivityError(IOException("401 Unauthorized")))
        assertFalse(isConnectivityError(IllegalStateException("not ready")))
        assertFalse(isConnectivityError(RuntimeException("boom")))
    }

    @Test
    fun apiUnauthorizedFromChatPointsAtApiKeyInsteadOfRepairingRelay() {
        val err = classifyError(
            IOException("List sessions unauthorized - check your API key"),
            context = "send_message",
        )

        assertEquals("API key rejected", err.title)
        assertTrue(err.body.contains("API key"))
        assertFalse(err.body.contains("re-pair", ignoreCase = true))
    }

    @Test
    fun relayUnauthorizedStillPointsAtPairing() {
        val err = classifyError(
            IOException("401 Unauthorized"),
            context = "media_fetch",
        )

        assertEquals("Session expired", err.title)
        assertTrue(err.body.contains("re-pair", ignoreCase = true))
    }

    @Test
    fun realtimeHermesBrokerUnauthorizedDoesNotBlameSavedPhoneKey() {
        val err = classifyError(
            IOException("Hermes broker auth failed (401): relay-side Hermes credential was rejected."),
            context = "voice_config",
        )

        assertEquals("Relay Hermes auth failed", err.title)
        assertTrue(err.body.contains("server-side Hermes credential"))
        assertFalse(err.body.contains("saved API key", ignoreCase = true))
    }

    @Test
    fun xaiRealtimeAuthMissingDoesNotBlameHermesApiKey() {
        val err = classifyError(
            IOException("xAI Realtime auth is not configured. Configure relay-side xAI realtime provider credentials."),
            context = "voice_config",
        )

        assertEquals("Realtime provider auth unavailable", err.title)
        assertTrue(err.body.contains("provider auth", ignoreCase = true))
        assertFalse(err.body.contains("saved API key", ignoreCase = true))
    }

    @Test
    fun realtimeProviderAuthRejectedDoesNotUseGenericForbiddenCopy() {
        val err = classifyError(
            IOException("xAI Realtime rejected the relay auth (403; source: Hermes auth providers.xai-oauth). Refresh xAI OAuth."),
            context = "voice_config",
        )

        assertEquals("Realtime provider auth unavailable", err.title)
        assertFalse(err.body.contains("server refused", ignoreCase = true))
    }
}
