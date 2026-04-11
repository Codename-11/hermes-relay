package com.hermesandroid.relay.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Writes inbound media bytes to the app's cache directory and returns a
 * `content://` URI backed by the app's FileProvider (authority:
 * `${applicationId}.fileprovider`).
 *
 * Layout on disk: `context.cacheDir/hermes-media/<hash>.<ext>`.
 *
 * Enforces a user-configurable LRU cap: after every write, if the total size
 * of `hermes-media/` exceeds the cap, files are deleted oldest-mtime-first
 * until the cache is back under the limit.
 *
 * Intentionally dumb: no in-memory index. The filesystem is the source of
 * truth so the cache survives process restarts without extra plumbing.
 */
class MediaCacheWriter(
    private val context: Context,
    /** Lambda so a settings change is picked up without reconstructing. */
    private val cachedMediaCapMbProvider: () -> Int
) {

    companion object {
        private const val CACHE_DIR = "hermes-media"

        /**
         * MIME → extension map. Covers the common types; falls back to `.bin`
         * for anything not listed. Keep the set small on purpose — any type
         * we can render correctly from a generic file card just needs *some*
         * extension so third-party viewers accept the intent.
         */
        private val mimeToExt = mapOf(
            // images
            "image/jpeg" to "jpg",
            "image/jpg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "image/gif" to "gif",
            "image/bmp" to "bmp",
            "image/svg+xml" to "svg",
            "image/heic" to "heic",
            "image/heif" to "heif",
            // video
            "video/mp4" to "mp4",
            "video/quicktime" to "mov",
            "video/webm" to "webm",
            "video/x-matroska" to "mkv",
            "video/x-msvideo" to "avi",
            // audio
            "audio/mpeg" to "mp3",
            "audio/mp4" to "m4a",
            "audio/wav" to "wav",
            "audio/x-wav" to "wav",
            "audio/ogg" to "ogg",
            "audio/flac" to "flac",
            "audio/webm" to "weba",
            // documents
            "application/pdf" to "pdf",
            "application/zip" to "zip",
            "application/x-tar" to "tar",
            "application/gzip" to "gz",
            "application/msword" to "doc",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
            // text-like
            "text/plain" to "txt",
            "text/markdown" to "md",
            "text/html" to "html",
            "text/css" to "css",
            "text/csv" to "csv",
            "application/json" to "json",
            "application/xml" to "xml",
            "text/xml" to "xml",
            "application/yaml" to "yaml",
            "application/x-yaml" to "yaml",
            "application/toml" to "toml",
            "application/javascript" to "js",
            "application/x-sh" to "sh"
        )
    }

    private val cacheRoot: File
        get() = File(context.cacheDir, CACHE_DIR).apply { if (!exists()) mkdirs() }

    /**
     * Cache [bytes] to disk and return a content:// URI the rest of the app
     * can hand to an Intent. Filename is derived from [fileName] when
     * provided (best effort — sanitized to remove path separators), otherwise
     * a SHA-1 of the bytes + a MIME-derived extension.
     */
    suspend fun cache(bytes: ByteArray, contentType: String, fileName: String?): Uri =
        withContext(Dispatchers.IO) {
            val dir = cacheRoot
            val targetName = buildFilename(bytes, contentType, fileName)
            val file = File(dir, targetName)
            file.writeBytes(bytes)
            // Best-effort LRU enforcement — never fatal on a cache operation.
            try {
                enforceCap()
            } catch (_: Exception) { /* swallow */ }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }

    /**
     * Wipe the entire `hermes-media/` directory. Returns the number of bytes
     * freed so the caller can show a "Freed X MB" snackbar/toast.
     */
    suspend fun clear(): Long = withContext(Dispatchers.IO) {
        val dir = cacheRoot
        val total = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        dir.listFiles()?.forEach { it.deleteRecursively() }
        // Recreate the empty dir so subsequent writes don't race with mkdirs().
        dir.mkdirs()
        total
    }

    /** Current total size of the cache directory in bytes. */
    suspend fun currentSizeBytes(): Long = withContext(Dispatchers.IO) {
        cacheRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    // --- internals ---

    private fun buildFilename(bytes: ByteArray, contentType: String, fileName: String?): String {
        val ext = mimeToExt[contentType.lowercase().substringBefore(';').trim()] ?: "bin"
        // SHA-1 of the bytes — stable + collision-resistant enough for cache keys.
        val sha1 = try {
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            // fallback to a weaker but always-available hash
            bytes.contentHashCode().toUInt().toString(16)
        }

        // If the server supplied a filename, keep the base (sanitized) but
        // force the extension we derived from the MIME. This keeps the
        // filename human-readable when the user taps the generic file card
        // and a picker shows the name.
        val sanitizedBase = fileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringBeforeLast('.')
            ?.take(64)
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.ifBlank { null }

        return if (sanitizedBase != null) {
            "${sanitizedBase}_${sha1.take(8)}.$ext"
        } else {
            "$sha1.$ext"
        }
    }

    /**
     * Delete oldest-mtime files until the cache directory fits under the cap.
     * Called after every write; safe to call more often.
     */
    private fun enforceCap() {
        val capBytes = cachedMediaCapMbProvider().toLong().coerceAtLeast(1) * 1024L * 1024L
        val files = cacheRoot.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= capBytes) return

        val sortedOldestFirst = files.sortedBy { it.lastModified() }
        for (file in sortedOldestFirst) {
            if (total <= capBytes) break
            val size = file.length()
            if (file.delete()) {
                total -= size
            }
        }
    }
}
