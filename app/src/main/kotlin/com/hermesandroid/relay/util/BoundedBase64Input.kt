package com.hermesandroid.relay.util

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64

internal data class BoundedBase64Payload(
    val base64: String,
    val sizeBytes: Long,
)

internal class AttachmentTooLargeException(
    val limitBytes: Long,
) : Exception("attachment exceeds the $limitBytes byte limit")

/**
 * Base64-encode [input] while enforcing [limitBytes] before an oversized body
 * is buffered. The picker previously called `readBytes()` and checked the cap
 * afterwards, allowing a content provider to exhaust the app heap first.
 */
internal fun readBase64Bounded(
    input: InputStream,
    limitBytes: Long,
): BoundedBase64Payload {
    require(limitBytes >= 0L) { "limitBytes must be non-negative" }
    val initialCapacity = minOf(limitBytes, 64L * 1024L).toInt()
    val encoded = ByteArrayOutputStream(initialCapacity)
    var count = 0L
    Base64.getEncoder().wrap(encoded).use { base64Out ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val remaining = limitBytes - count
            val requested = minOf(buffer.size.toLong(), remaining + 1L).toInt()
            val read = input.read(buffer, 0, requested)
            if (read < 0) break
            if (read == 0) continue
            if (read.toLong() > remaining) throw AttachmentTooLargeException(limitBytes)
            base64Out.write(buffer, 0, read)
            count += read
        }
    }
    return BoundedBase64Payload(
        base64 = encoded.toString(Charsets.US_ASCII.name()),
        sizeBytes = count,
    )
}
