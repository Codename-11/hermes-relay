package com.hermesandroid.relay.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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

    val orientation = runCatching {
        ByteArrayInputStream(bytes).use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val transform = imageOrientationTransform(orientation)
    if (transform.rotationDegrees == 0f && !transform.flipHorizontal) return decoded

    val matrix = Matrix().apply {
        if (transform.rotationDegrees != 0f) postRotate(transform.rotationDegrees)
        if (transform.flipHorizontal) postScale(-1f, 1f)
    }
    return runCatching {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }.getOrNull()?.also { oriented ->
        if (oriented !== decoded) decoded.recycle()
    } ?: decoded
}
