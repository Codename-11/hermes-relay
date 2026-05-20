package com.hermesandroid.relay.util

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayErrorClassifierTest {
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
}
