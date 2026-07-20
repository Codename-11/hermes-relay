package com.hermesandroid.relay.ui.components

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineImageDataUrlTest {
    private fun url(mime: String, bytes: ByteArray): String =
        "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"

    @Test
    fun acceptsUpstreamImageMimesWhenMagicMatches() {
        val fixtures = mapOf(
            "image/png" to byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            "image/jpeg" to byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00),
            "image/webp" to "RIFF0000WEBP".encodeToByteArray(),
            "image/gif" to "GIF89a".encodeToByteArray(),
            "image/bmp" to "BM".encodeToByteArray(),
        )
        fixtures.forEach { (mime, bytes) ->
            assertEquals(mime, decodeInlineImageDataUrl(url(mime, bytes))?.mime)
        }
    }

    @Test
    fun rejectsUnknownMimeInvalidBase64AndMimeSpoofing() {
        assertNull(decodeInlineImageDataUrl(url("image/svg+xml", "<svg/>".encodeToByteArray())))
        assertNull(decodeInlineImageDataUrl("data:image/png;base64,%%%"))
        assertNull(decodeInlineImageDataUrl(url("image/png", "GIF89a".encodeToByteArray())))
        assertNull(decodeInlineImageDataUrl("data:image/png,not-base64"))
    }

    @Test
    fun rejectsOversizeBeforeAndAfterDecode() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        assertNull(decodeInlineImageDataUrl(url("image/png", png + ByteArray(9)), maxBytes = 16))
        val tooLong = "data:image/png;base64," + "A".repeat(25)
        assertNull(decodeInlineImageDataUrl(tooLong, maxBytes = 16))
    }

    @Test
    fun boundsDecodedPixelCountWithoutOverflow() {
        assertTrue(isInlineImageDimensionsSafe(4_000, 4_000))
        assertFalse(isInlineImageDimensionsSafe(10_000, 10_000))
        assertFalse(isInlineImageDimensionsSafe(Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(isInlineImageDimensionsSafe(0, 100))
    }

    @Test
    fun markdownExtractionPreservesSensitivityForDataUrl() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val (body, images) = extractChatInlineImages("before ||![sensitive](${url("image/png", png)})|| after")
        assertEquals("before  after", body)
        assertEquals(1, images.size)
        assertTrue(images.single().sensitive)
        assertNotNull(decodeInlineImageDataUrl(images.single().src))
    }
}
