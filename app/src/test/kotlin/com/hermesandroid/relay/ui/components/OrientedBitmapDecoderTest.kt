package com.hermesandroid.relay.ui.components

import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

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
}
