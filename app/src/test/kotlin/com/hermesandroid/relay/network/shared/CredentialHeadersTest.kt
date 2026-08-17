package com.hermesandroid.relay.network.shared

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialHeadersTest {
    @Test
    fun harmlessOuterHorizontalWhitespaceIsNormalized() {
        val request = Request.Builder()
            .url("https://example.test/")
            .bearerAuthorization(" \tcredential-value\t ", "API credential")
            .build()

        assertEquals("Bearer credential-value", request.header("Authorization"))
    }

    @Test
    fun multilineAndEmbeddedWhitespaceAreRejectedWithoutCredentialDisclosure() {
        for (raw in listOf("first\nsecond", "first\r\nsecond", "first second", "first\tsecond")) {
            val failure = assertThrows(InvalidCredentialException::class.java) {
                Request.Builder()
                    .url("https://example.test/")
                    .bearerAuthorization(raw, "API credential")
            }
            assertEquals(
                "Invalid API credential — enter or import a single-line value.",
                failure.message,
            )
            assertFalse(failure.message.orEmpty().contains("first"))
            assertFalse(failure.message.orEmpty().contains("second"))
        }
    }
}
