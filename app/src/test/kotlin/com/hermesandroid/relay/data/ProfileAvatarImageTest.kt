package com.hermesandroid.relay.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileAvatarImageTest {
    @Test
    fun `oversized bitmap is encoded within upstream limit`() {
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff336699.toInt())
        }

        val encoded = encodeProfileAvatar(bitmap, 80_000)

        assertNotNull(encoded)
        assertTrue(encoded!!.size <= 80_000)
        assertTrue(profileAvatarMime(encoded) in setOf("image/png", "image/jpeg"))
        val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
        assertTrue(maxOf(decoded.width, decoded.height) <= 1024)
        decoded.recycle()
        bitmap.recycle()
    }

    @Test
    fun `profile avatar mime recognizes upstream formats`() {
        assertEquals("image/jpeg", profileAvatarMime(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
        assertEquals(
            "image/webp",
            profileAvatarMime("RIFF1234WEBP".toByteArray()),
        )
    }
}
