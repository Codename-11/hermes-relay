package com.hermesandroid.relay.ui.components

import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(AndroidJUnit4::class)
class OrientedBitmapDecoderTest {

    @Test
    fun `portrait exif orientations rotate into display coordinates`() {
        assertEquals(
            ImageOrientationTransform(rotationDegrees = 90f),
            imageOrientationTransform(ExifInterface.ORIENTATION_ROTATE_90),
        )
        assertEquals(
            ImageOrientationTransform(rotationDegrees = -90f),
            imageOrientationTransform(ExifInterface.ORIENTATION_ROTATE_270),
        )
    }

    @Test
    fun `mirrored exif orientations preserve their reflection`() {
        assertEquals(
            ImageOrientationTransform(flipHorizontal = true),
            imageOrientationTransform(ExifInterface.ORIENTATION_FLIP_HORIZONTAL),
        )
        assertEquals(
            ImageOrientationTransform(rotationDegrees = 90f, flipHorizontal = true),
            imageOrientationTransform(ExifInterface.ORIENTATION_TRANSPOSE),
        )
        assertEquals(
            ImageOrientationTransform(rotationDegrees = -90f, flipHorizontal = true),
            imageOrientationTransform(ExifInterface.ORIENTATION_TRANSVERSE),
        )
    }

    @Test
    fun `content uri decoder reads encoded image without byte array handoff`() {
        val context = RuntimeEnvironment.getApplication()
        val source = File.createTempFile("oriented-bitmap-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        } finally {
            bitmap.recycle()
        }

        val decoded = decodeOrientedBitmap(context, Uri.fromFile(source))

        assertNotNull(decoded)
        assertEquals(3, decoded?.width)
        assertEquals(2, decoded?.height)
        decoded?.recycle()
        source.delete()
    }
}
