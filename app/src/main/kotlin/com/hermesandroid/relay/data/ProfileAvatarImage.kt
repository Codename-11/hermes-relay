package com.hermesandroid.relay.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

internal fun profileAvatarMime(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
    ) -> "image/png"
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
        bytes[2] == 0xff.toByte() -> "image/jpeg"
    bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
        bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"
    else -> null
}

/**
 * Convert any image Android can decode into the small static format accepted by
 * upstream `profiles.set_asset`. ImageDecoder applies camera EXIF orientation
 * and downsamples before allocating the bitmap on current Android releases.
 */
internal fun prepareProfileAvatar(
    context: Context,
    uri: Uri,
    maxBytes: Int,
): ByteArray? {
    val original = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream(minOf(maxBytes + 1, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (output.size() <= maxBytes) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }.getOrNull()
    if (original != null && original.size <= maxBytes && profileAvatarMime(original) != null) {
        return original
    }

    val bitmap = decodeProfileAvatar(context, uri) ?: return null
    return try {
        encodeProfileAvatar(bitmap, maxBytes)
    } finally {
        bitmap.recycle()
    }
}

private fun decodeProfileAvatar(context: Context, uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val longest = maxOf(width, height)
            if (longest > PROFILE_AVATAR_MAX_DIMENSION) {
                val scale = PROFILE_AVATAR_MAX_DIMENSION.toFloat() / longest
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > PROFILE_AVATAR_MAX_DIMENSION) sample *= 2
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}.getOrNull()

internal fun encodeProfileAvatar(bitmap: Bitmap, maxBytes: Int): ByteArray? {
    var working = bitmap.scaledToFit(PROFILE_AVATAR_MAX_DIMENSION)
    var ownsWorking = working !== bitmap
    try {
        while (true) {
            val format = if (working.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val qualities = if (format == Bitmap.CompressFormat.PNG) intArrayOf(100) else intArrayOf(92, 82, 72, 62)
            for (quality in qualities) {
                val encoded = ByteArrayOutputStream().use { output ->
                    if (!working.compress(format, quality, output)) null else output.toByteArray()
                }
                if (encoded != null && encoded.size <= maxBytes) return encoded
            }
            if (maxOf(working.width, working.height) <= PROFILE_AVATAR_MIN_DIMENSION) return null
            val next = Bitmap.createScaledBitmap(
                working,
                (working.width * 0.75f).roundToInt().coerceAtLeast(1),
                (working.height * 0.75f).roundToInt().coerceAtLeast(1),
                true,
            )
            if (ownsWorking) working.recycle()
            working = next
            ownsWorking = true
        }
    } finally {
        if (ownsWorking) working.recycle()
    }
}

private fun Bitmap.scaledToFit(maxDimension: Int): Bitmap {
    val longest = maxOf(width, height)
    if (longest <= maxDimension) return this
    val scale = maxDimension.toFloat() / longest
    return Bitmap.createScaledBitmap(
        this,
        (width * scale).roundToInt().coerceAtLeast(1),
        (height * scale).roundToInt().coerceAtLeast(1),
        true,
    )
}

private const val PROFILE_AVATAR_MAX_DIMENSION = 1024
private const val PROFILE_AVATAR_MIN_DIMENSION = 128
