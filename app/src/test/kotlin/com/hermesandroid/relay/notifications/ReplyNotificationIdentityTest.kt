package com.hermesandroid.relay.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import com.hermesandroid.relay.data.ProfileIconStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplyNotificationIdentityTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun avatar(name: String): File {
        val dir = File(app.filesDir, "profile-avatars-server").apply { mkdirs() }
        return File(dir, name).also { file ->
            file.outputStream().use { Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun officialCacheUsesExactConnectionAndProfileWithNoActivePickerFallback() = runBlocking {
        val cache = ProfileIconStore(app)
        cache.setServerAvatar("one", "agent", avatar("one.png").path)
        assertNotNull(cachedReplyAvatar(app, "one", "agent"))
        assertNull(cachedReplyAvatar(app, "two", "agent"))
        assertNull(cachedReplyAvatar(app, "one", null))
        cache.setIcon("two", "agent", avatar("local.png").path)
        assertNull(cachedReplyAvatar(app, "two", "agent"))
    }

    @Test fun boundedDecoderRejectsMissingInvalidAndOutsideCacheFiles() {
        val file = avatar("valid.png")
        val cache = file.parentFile!!
        val decoded = decodeReplyAvatar(cache, file)!!
        assertTrue(decoded.width <= 256 && decoded.height <= 256)
        assertNull(decodeReplyAvatar(cache, File(cache, "missing.png")))
        val invalid = File(cache, "invalid.png").apply { writeText("invalid") }
        assertNull(decodeReplyAvatar(cache, invalid))
        assertNull(decodeReplyAvatar(File(app.filesDir, "other-cache"), file))
    }

    @Test fun notificationUsesOfficialIdentityAndConversationButKeepsPublicVersionGeneric() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        TurnCompleteNotifier.notifyTurnComplete(app, ChatNotificationTarget("connection", "agent", "session"),
            "turn", "Old picker label", "Private synthetic answer", identity = ReplyNotificationIdentity(
                "agent", "Official Agent", "Release review"), avatar = bitmap)
        val notification = manager.activeNotifications.single().notification
        assertEquals("Official Agent · Release review", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertNotNull(notification.getLargeIcon())
        assertNull(notification.publicVersion.getLargeIcon())
        val publicCopy = notification.publicVersion.extras.toString()
        assertFalse(publicCopy.contains("Official Agent"))
        assertFalse(publicCopy.contains("Release review"))
        assertFalse(publicCopy.contains("Private synthetic answer"))
        manager.cancelAll()
    }
}
