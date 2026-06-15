package com.hermesandroid.relay.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Save / share / open helpers for chat images and attachments — the byte
 * plumbing behind the image viewer's Save/Share controls and the attachment
 * card's long-press menu.
 *
 * Save destinations are deliberately permission-free:
 *  - **Android 10+ (API 29, Scoped Storage):** [MediaStore] — images land in
 *    `Pictures/Hermes-Relay`, other files in `Download/Hermes-Relay`. No
 *    runtime permission required.
 *  - **Android 9 and below:** the app declares no `WRITE_EXTERNAL_STORAGE`
 *    permission (and shouldn't prompt for one for this niche path), so save
 *    returns [SaveResult.UseShareInstead] and the caller routes to the system
 *    share sheet — which offers "Save to Files"/Drive/etc.
 *
 * Sharing always works regardless of version: bytes are staged into the
 * FileProvider-backed `hermes-media/` cache (the same path `file_provider_paths`
 * already maps) and handed to `ACTION_SEND` as a `content://` URI, since you
 * cannot share a remote `http(s)` URL as a stream.
 */
object MediaSaver {

    private const val SAVE_SUBDIR = "Hermes-Relay"
    private const val SHARE_CACHE_DIR = "hermes-media"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Outcome of a save attempt. */
    sealed interface SaveResult {
        /** Persisted; [location] is a human-readable folder for the toast. */
        data class Saved(val uri: Uri, val location: String) : SaveResult

        /** Pre-Q with no storage permission — the caller should share instead. */
        data object UseShareInstead : SaveResult

        data class Failed(val message: String) : SaveResult
    }

    // --- Remote bytes -------------------------------------------------------

    /**
     * GET [url] and return its bytes plus the best-effort `Content-Type`. Runs
     * on IO. Throws on a non-2xx response or empty body so callers can fall
     * back to a notice.
     */
    suspend fun fetchRemoteBytes(url: String): Pair<ByteArray, String?> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val contentType = resp.header("Content-Type")?.substringBefore(';')?.trim()
                val body = resp.body.bytes()
                body to contentType
            }
        }

    /** Read the bytes behind a `content://` (or `file://`) [uri]. */
    suspend fun readUriBytes(context: Context, uri: Uri): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
        }

    // --- Save ---------------------------------------------------------------

    suspend fun saveImage(
        context: Context,
        bytes: ByteArray,
        displayName: String?,
        mime: String,
    ): SaveResult {
        // Prefer a magic-byte-sniffed type so the extension is correct even
        // when the caller only had a generic `image/*` guess (remote URLs).
        val effectiveMime = sniffImageMime(bytes) ?: mime.ifBlank { "image/png" }
        return saveTo(context, bytes, ensureNamed(displayName, effectiveMime), effectiveMime, isImage = true)
    }

    suspend fun saveFile(
        context: Context,
        bytes: ByteArray,
        displayName: String?,
        mime: String,
    ): SaveResult = saveTo(
        context,
        bytes,
        ensureNamed(displayName, mime),
        mime.ifBlank { "application/octet-stream" },
        isImage = false,
    )

    private suspend fun saveTo(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mime: String,
        isImage: Boolean,
    ): SaveResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { saveViaMediaStore(context, bytes, fileName, mime, isImage) }
                .getOrElse { SaveResult.Failed(it.message ?: "save failed") }
        } else {
            // Pre-Q public-dir writes need WRITE_EXTERNAL_STORAGE, which we
            // don't request — route the caller to the share sheet instead.
            SaveResult.UseShareInstead
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mime: String,
        isImage: Boolean,
    ): SaveResult {
        val resolver = context.contentResolver
        val collection = if (isImage) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val baseFolder = if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS
        val relativePath = "$baseFolder/$SAVE_SUBDIR"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            if (mime.isNotBlank()) put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert returned null")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("openOutputStream returned null")
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return SaveResult.Saved(uri, relativePath)
    }

    // --- Share / open -------------------------------------------------------

    /**
     * Stage [bytes] in the FileProvider cache; return a shareable `content://`
     * URI. When [mime] is blank or a wildcard (e.g. an `image/…` wildcard from
     * an inline image whose real type we only learn from the bytes), the type is
     * magic-byte-sniffed so the staged file gets a correct extension —
     * FileProvider derives the shared `content://` type from that extension, so
     * a wrong `.bin` would otherwise make the receiver see octet-stream.
     */
    suspend fun stageForShare(context: Context, bytes: ByteArray, displayName: String?, mime: String): Uri =
        withContext(Dispatchers.IO) {
            val effectiveMime = if (mime.isBlank() || mime.endsWith("/*")) {
                sniffImageMime(bytes) ?: mime
            } else {
                mime
            }
            val dir = File(context.cacheDir, SHARE_CACHE_DIR).apply { if (!exists()) mkdirs() }
            val file = File(dir, ensureNamed(displayName, effectiveMime))
            file.writeBytes(bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }

    fun share(context: Context, uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(chooser) }
    }

    fun open(context: Context, uri: Uri, mime: String) {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(view) }
    }

    // --- Naming / sniffing --------------------------------------------------

    /** Sanitize [base] and guarantee a file extension derived from [mime]. */
    fun ensureNamed(base: String?, mime: String): String {
        val cleaned = base
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.take(80)
            ?.ifBlank { null }
        val stem = cleaned ?: "hermes-${System.currentTimeMillis()}"
        // Keep an existing, plausible extension; otherwise append the derived one.
        val hasExt = stem.substringAfterLast('.', "").let { it.isNotBlank() && it.length <= 5 }
        return if (hasExt) stem else "$stem.${extensionFor(mime)}"
    }

    private fun extensionFor(mime: String): String = when (mime.lowercase().substringBefore(';').trim()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/bmp" -> "bmp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/svg+xml" -> "svg"
        "video/mp4" -> "mp4"
        "audio/mpeg" -> "mp3"
        "application/pdf" -> "pdf"
        "text/plain" -> "txt"
        "application/json" -> "json"
        else -> "bin"
    }

    /** Identify a common image type from its magic bytes; null if unknown. */
    private fun sniffImageMime(b: ByteArray): String? {
        if (b.size < 12) return null
        fun u(i: Int) = b[i].toInt() and 0xFF
        return when {
            u(0) == 0x89 && u(1) == 0x50 && u(2) == 0x4E && u(3) == 0x47 -> "image/png"
            u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> "image/jpeg"
            u(0) == 0x47 && u(1) == 0x49 && u(2) == 0x46 -> "image/gif"
            u(0) == 0x42 && u(1) == 0x4D -> "image/bmp"
            // RIFF....WEBP
            u(0) == 0x52 && u(1) == 0x49 && u(2) == 0x46 && u(3) == 0x46 &&
                u(8) == 0x57 && u(9) == 0x45 && u(10) == 0x42 && u(11) == 0x50 -> "image/webp"
            else -> null
        }
    }
}
