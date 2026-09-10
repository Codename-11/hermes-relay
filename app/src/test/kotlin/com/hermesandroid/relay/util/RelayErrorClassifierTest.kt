package com.hermesandroid.relay.util

import com.hermesandroid.relay.network.upstream.DashboardHttpException

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayErrorClassifierTest {
    @Test
    fun dashboard404UsesTypedStatusAndNamesItsOwner() {
        val error = classifyError(DashboardHttpException(404, "request failed"), context = "send_message")
        assertEquals("Endpoint not found", error.title)
        assertTrue(error.body.contains("Dashboard"))
        assertFalse(error.body.contains("relay", ignoreCase = true))
        assertFalse(error.retryable)
    }

    @Test
    fun typedDashboardStatusWinsOverMisleadingResponseBody() {
        val error = classifyError(DashboardHttpException(500, "HTTP 404 missing"))
        assertFalse(error.title == "Endpoint not found")
        assertTrue(error.body.contains("HTTP 500"))
        assertTrue(error.retryable)
    }

    @Test
    fun explicitLegacy404IsNeutralForVoiceAndChat() {
        listOf("HTTP 404", "API error 404: Not found", "Relay responded HTTP 404").forEach { text ->
            listOf("voice_config", "send_message").forEach { context ->
                val error = classifyError(IOException(text), context)
                assertEquals("Endpoint not found", error.title)
                assertFalse(error.body.contains("relay", ignoreCase = true))
                assertFalse(error.body.contains("older", ignoreCase = true))
            }
        }
    }

    @Test
    fun incidental404NeverBecomesMissingEndpoint() {
        listOf("file 404 not readable", "HTTP 4040", "request id=404", "See https://example.test/404", "Response body: HTTP 404").forEach { text ->
            val error = classifyError(IOException(text))
            assertEquals("Network error", error.title)
            assertEquals(text, error.body)
        }
    }

    @Test
    fun typedDashboardAuthDoesNotSuggestRelayRepair() {
        listOf(401, 403).forEach { code ->
            val error = classifyError(DashboardHttpException(code, "request failed"), "media_fetch")
            assertEquals(if (code == 401) "Dashboard sign-in required" else "Not allowed", error.title)
            assertEquals(null, error.action)
            assertFalse(error.body.contains("re-pair", ignoreCase = true))
        }
    }

    @Test
    fun gatewayDrainIsNotMisclassifiedAsProviderOutage() {
        val err = classifyError(
            IOException("API error 503: gateway_draining: Gateway is shutting down (Retry-After: 1s)"),
            context = "send_message",
        )
        assertEquals("Hermes is restarting", err.title)
        assertTrue(err.body.contains("draining"))
        assertTrue(err.retryable)
    }

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
    fun apiSessionLoadUnauthorizedPointsAtApiKeyInsteadOfRepairingRelay() {
        val err = classifyError(
            IOException("List sessions unauthorized - check your API key"),
            context = "load_sessions",
        )

        assertEquals("API key rejected", err.title)
        assertFalse(err.body.contains("re-pair", ignoreCase = true))
    }

    @Test
    fun dashboardProfileSessionUnauthorizedDoesNotBlameRelayPairing() {
        val err = classifyError(
            IOException("Profile sessions unauthorized - HTTP 401"),
            context = "load_profile_sessions",
        )

        assertEquals("Dashboard sign-in required", err.title)
        assertFalse(err.body.contains("re-pair", ignoreCase = true))
        assertEquals(null, err.action)
    }

    @Test
    fun relayUnauthorizedStillPointsAtPairing() {
        val err = classifyError(
            IOException("401 Unauthorized"),
            context = "media_fetch",
        )

        assertEquals("Session expired", err.title)
        assertTrue(err.body.contains("re-pair", ignoreCase = true))
        assertEquals(HumanErrorAction.Repair, err.action)
    }

    @Test
    fun certificateMismatchExposesRepairAction() {
        val err = classifyError(SSLException("certificate changed"))

        assertEquals("Certificate mismatch", err.title)
        assertEquals(HumanErrorAction.Repair, err.action)
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

    @Test
    fun audioRecordCannotCreateMapsToMicUnavailableHint() {
        val err = classifyError(
            UnsupportedOperationException("Cannot create AudioRecord"),
            context = "record",
        )

        assertEquals("Microphone unavailable", err.title)
        assertTrue(err.body.contains("microphone", ignoreCase = true))
        assertTrue(err.retryable)
    }

    @Test
    fun audioRecordFailedToInitializeMapsToMicUnavailableHint() {
        val err = classifyError(
            IllegalStateException("AudioRecord failed to initialize"),
            context = "record",
        )

        assertEquals("Microphone unavailable", err.title)
        assertTrue(err.retryable)
    }

    @Test
    fun microphoneOwnershipConflictMapsToRetryableMicUnavailableHint() {
        val err = classifyError(
            IllegalStateException("Microphone is in use by another voice feature"),
            context = "record",
        )

        assertEquals("Microphone unavailable", err.title)
        assertTrue(err.retryable)
    }
}
