package com.hermesandroid.relay.ui.components

import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
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
    fun samplesLargeImagesToBoundedThumbnailMemory() {
        assertEquals(4, inlineImageSampleSize(4_000, 4_000))
        assertEquals(1, inlineImageSampleSize(1_024, 1_024))
        assertNull(inlineImageSampleSize(40_000, 100))
        assertNull(inlineImageSampleSize(Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(inlineImageSampleSize(0, 100))
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

    @Test
    fun multipleInlineImagesAreCappedPerMessage() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val marker = "![image](${url("image/png", png)})"
        val (body, images) = extractChatInlineImages(List(7) { marker }.joinToString("\n"))
        assertEquals(INLINE_IMAGE_DATA_MAX_PER_MESSAGE, images.size)
        assertTrue(body.contains("Additional inline images omitted"))
        assertEquals(1, Regex("Additional inline images omitted").findAll(body).count())
    }

    @Test
    fun cumulativeRawImageBudgetOmitsLaterImages() {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val fourMiBPng = signature + ByteArray(4 * 1024 * 1024 - signature.size)
        val marker = "![image](${url("image/png", fourMiBPng)})"
        val (body, images) = extractChatInlineImages(List(3) { marker }.joinToString("\n"))
        assertEquals(2, images.size)
        assertTrue(body.contains("Additional inline images omitted"))
    }

    @Test
    fun saveShareDecodeRunsOnRequestedBackgroundDispatcher() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val callerThread = Thread.currentThread()
        val backgroundThread = AtomicReference(callerThread)
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "inline-image-decode-test").also(backgroundThread::set)
        }
            .asCoroutineDispatcher().use { dispatcher ->
                val decoded = decodeInlineImageDataUrlOffMain(url("image/png", png), dispatcher)
                assertNotNull(decoded)
                assertFalse(callerThread === backgroundThread.get())
            }
    }
}
