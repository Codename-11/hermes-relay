package com.hermesandroid.relay.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

internal data class ImageOrientationTransform(
    val rotationDegrees: Float = 0f,
    val flipHorizontal: Boolean = false,
)

internal fun imageOrientationTransform(orientation: Int): ImageOrientationTransform = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ImageOrientationTransform(flipHorizontal = true)
    ExifInterface.ORIENTATION_ROTATE_180 -> ImageOrientationTransform(rotationDegrees = 180f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL ->
        ImageOrientationTransform(rotationDegrees = 180f, flipHorizontal = true)
    ExifInterface.ORIENTATION_TRANSPOSE ->
        ImageOrientationTransform(rotationDegrees = 90f, flipHorizontal = true)
    ExifInterface.ORIENTATION_ROTATE_90 -> ImageOrientationTransform(rotationDegrees = 90f)
    ExifInterface.ORIENTATION_TRANSVERSE ->
        ImageOrientationTransform(rotationDegrees = -90f, flipHorizontal = true)
    ExifInterface.ORIENTATION_ROTATE_270 -> ImageOrientationTransform(rotationDegrees = -90f)
    else -> ImageOrientationTransform()
}

internal fun shouldApplyExifOrientation(mimeType: String?): Boolean = when (mimeType?.lowercase()) {
    "image/png", "image/gif", "image/bmp" -> false
    else -> true
}

/**
 * Decode image bytes for display and apply their EXIF orientation. BitmapFactory
 * returns the stored pixel layout and otherwise leaves portrait phone photos
 * sideways when the camera encoded rotation as metadata.
 */
internal fun decodeOrientedBitmap(
    bytes: ByteArray,
    options: BitmapFactory.Options? = null,
): Bitmap? {
    if (bytes.isEmpty()) return null
    val decoded = if (options == null) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } else {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } ?: return null

    val orientation = try {
        ByteArrayInputStream(bytes).use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val transform = imageOrientationTransform(orientation)
    if (transform.rotationDegrees == 0f && !transform.flipHorizontal) return decoded

    val matrix = Matrix().apply {
        if (transform.rotationDegrees != 0f) postRotate(transform.rotationDegrees)
        if (transform.flipHorizontal) postScale(-1f, 1f)
    }
    val oriented = try {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    } catch (_: Exception) {
        null
    }
    return oriented?.also { bitmap ->
        if (bitmap !== decoded) decoded.recycle()
    } ?: decoded
}

/** Decode a cached content URI without copying the encoded file into RAM. */
internal fun decodeOrientedBitmap(context: Context, uri: Uri): Bitmap? =
    decodeBoundedOrientedBitmap(context, uri)

internal fun decodeBoundedOrientedBitmap(context: Context, uri: Uri): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsInput = context.contentResolver.openInputStream(uri) ?: return null
    boundsInput.use { input ->
        // Bounds-only decode deliberately returns null; the populated Options
        // are the result and must not be treated as a decode failure.
        BitmapFactory.decodeStream(input, null, bounds)
    }
    val sample = inlineImageSampleSize(bounds.outWidth, bounds.outHeight) ?: return null
    val decoded = context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(
            input,
            null,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    } ?: return null
    val orientation = if (shouldApplyExifOrientation(bounds.outMimeType)) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        } catch (_: Exception) {
            null
        } ?: ExifInterface.ORIENTATION_NORMAL
    } else {
        ExifInterface.ORIENTATION_NORMAL
    }
    val transform = imageOrientationTransform(orientation)
    if (transform.rotationDegrees == 0f && !transform.flipHorizontal) return decoded

    val matrix = Matrix().apply {
        if (transform.rotationDegrees != 0f) postRotate(transform.rotationDegrees)
        if (transform.flipHorizontal) postScale(-1f, 1f)
    }
    val oriented = try {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    } catch (_: Exception) {
        null
    }
    return oriented?.also { bitmap ->
        if (bitmap !== decoded) decoded.recycle()
    } ?: decoded
}

/** Decode an encoded chat image only after bounding its decoded pixel footprint. */
internal fun decodeBoundedOrientedBitmap(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val sample = inlineImageSampleSize(bounds.outWidth, bounds.outHeight) ?: return null
    return decodeOrientedBitmap(
        bytes,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}
