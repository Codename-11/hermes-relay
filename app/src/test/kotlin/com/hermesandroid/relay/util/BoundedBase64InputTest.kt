package com.hermesandroid.relay.util

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedBase64InputTest {
    @Test
    fun `encodes incrementally through the exact byte limit`() {
        val result = readBase64Bounded(ByteArrayInputStream("hello".toByteArray()), 5)

        assertEquals("aGVsbG8=", result.base64)
        assertEquals(5L, result.sizeBytes)
    }

    @Test
    fun `rejects before buffering bytes beyond the limit`() {
        val error = assertThrows(AttachmentTooLargeException::class.java) {
            readBase64Bounded(ByteArrayInputStream(ByteArray(8_193)), 8_192)
        }

        assertEquals(8_192L, error.limitBytes)
    }
}
