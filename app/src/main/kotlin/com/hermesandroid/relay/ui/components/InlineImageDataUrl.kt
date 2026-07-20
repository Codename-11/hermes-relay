package com.hermesandroid.relay.ui.components

import java.util.Base64

internal const val INLINE_IMAGE_DATA_MAX_BYTES = 5 * 1024 * 1024
internal const val INLINE_IMAGE_MAX_PIXELS = 20_000_000L
private const val MAX_HEADER_CHARS = 64
private val DATA_IMAGE_HEADER = Regex(
    "^data:(image/(?:png|jpeg|gif|webp|bmp));base64$",
    RegexOption.IGNORE_CASE,
)

internal data class DecodedInlineImageData(
    val bytes: ByteArray,
    val mime: String,
)

/**
 * Strict decoder for upstream API-server inline images.
 *
 * Only the five raster formats upstream emits for this path are accepted. The
 * encoded input is bounded before allocation, strict base64 is required, and
 * the decoded magic bytes must agree with the declared MIME type.
 */
internal fun decodeInlineImageDataUrl(
    source: String,
    maxBytes: Int = INLINE_IMAGE_DATA_MAX_BYTES,
): DecodedInlineImageData? {
    if (maxBytes <= 0 || !source.startsWith("data:", ignoreCase = true)) return null
    val comma = source.indexOf(',')
    if (comma <= 0 || comma > MAX_HEADER_CHARS) return null
    val mime = DATA_IMAGE_HEADER.matchEntire(source.substring(0, comma))
        ?.groupValues?.get(1)?.lowercase() ?: return null
    val encoded = source.substring(comma + 1)
    if (encoded.isEmpty()) return null
    // ceil(maxBytes / 3) * 4 plus at most two trailing padding characters.
    val maxEncoded = ((maxBytes.toLong() + 2L) / 3L) * 4L
    if (encoded.length.toLong() > maxEncoded) return null
    val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
    if (bytes.isEmpty() || bytes.size > maxBytes || !matchesImageMagic(mime, bytes)) return null
    return DecodedInlineImageData(bytes, mime)
}

private fun matchesImageMagic(mime: String, bytes: ByteArray): Boolean = when (mime) {
    "image/png" -> bytes.size >= 8 && bytes.sliceArray(0..7).contentEquals(
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
    )
    "image/jpeg" -> bytes.size >= 3 &&
        bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
    "image/webp" -> bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).contentEquals("RIFF".encodeToByteArray()) &&
        bytes.copyOfRange(8, 12).contentEquals("WEBP".encodeToByteArray())
    "image/gif" -> bytes.size >= 6 &&
        (bytes.copyOfRange(0, 6).contentEquals("GIF87a".encodeToByteArray()) ||
            bytes.copyOfRange(0, 6).contentEquals("GIF89a".encodeToByteArray()))
    "image/bmp" -> bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()
    else -> false
}

internal fun isInlineImageDimensionsSafe(
    width: Int,
    height: Int,
    maxPixels: Long = INLINE_IMAGE_MAX_PIXELS,
): Boolean = width > 0 && height > 0 && maxPixels > 0 &&
    width.toLong() <= maxPixels / height.toLong()
