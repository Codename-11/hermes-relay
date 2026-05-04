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
}
