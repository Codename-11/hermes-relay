package com.hermesandroid.relay.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hermesandroid.relay.data.ProfileIconStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** Presentation captured from the owning official profile, independent of later picker changes. */
@Serializable
data class ReplyNotificationIdentity(
    val profileName: String? = null,
    val displayName: String? = null,
    val conversationTitle: String? = null,
)

/** Uses only the existing profiles.get_asset cache. Never fetch arbitrary image URLs on completion. */
internal suspend fun cachedReplyAvatar(context: Context, connectionId: String, profileName: String?): Bitmap? {
    if (profileName.isNullOrBlank()) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val path = withTimeoutOrNull(500) {
                ProfileIconStore(context).serverAvatarFlow(connectionId, profileName).first()
            } ?: return@runCatching null
            decodeReplyAvatar(File(context.filesDir, "profile-avatars-server"), File(path))
        }.getOrNull()
    }
}

internal fun decodeReplyAvatar(cacheDir: File, file: File): Bitmap? = runCatching {
    val root = cacheDir.canonicalFile.toPath()
    val path = file.canonicalFile.toPath()
    if (!path.startsWith(root) || !file.isFile || file.length() > 8 * 1024 * 1024) return@runCatching null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
        bounds.outWidth.toLong() * bounds.outHeight > 16_000_000) return@runCatching null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()
