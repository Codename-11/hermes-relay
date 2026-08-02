package com.hermesandroid.relay.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthManagerProfileApiKeysTest {
    @Test
    fun profileApiKeys_roundTripWithoutBlankEntries() {
        val encoded = AuthManager.encodeProfileApiKeys(
            mapOf(
                "research" to " secret-research ",
                " coder " to "secret-coder",
                "blank" to " ",
            ),
        )

        assertEquals(
            mapOf(
                "research" to "secret-research",
                "coder" to "secret-coder",
            ),
            AuthManager.decodeProfileApiKeys(encoded),
        )
    }

    @Test
    fun profileApiKeys_corruptPayloadFailsClosed() {
        assertTrue(AuthManager.decodeProfileApiKeys("{not-json").isEmpty())
    }
}
