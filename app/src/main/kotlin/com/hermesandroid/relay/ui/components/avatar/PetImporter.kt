package com.hermesandroid.relay.ui.components.avatar

import android.content.Context
import android.net.Uri
import android.util.Log
import com.hermesandroid.relay.ui.components.UserContentDir
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/** Outcome of importing a pet pack from a user-picked archive. */
sealed interface PetImportResult {
    /** A valid pack was unpacked and is now selectable. */
    data class Success(val id: String, val label: String) : PetImportResult
    /** Nothing was installed; [reason] is a short, user-facing explanation. */
    data class Failure(val reason: String) : PetImportResult
}

/**
 * Imports a side-loaded pet pack from a user-picked `.zip` into the app's pets
 * directory — the in-app alternative to `adb push`, which scoped storage blocks
 * or stalls on many devices. Pure data only: PNG/JSON files are extracted, never
 * executed.
 *
 * Hardened against hostile archives: every entry is confined to a staging dir
 * (zip-slip guard), and per-file / total-size / entry-count ceilings bound a zip
 * bomb. After extraction the pack is validated through [PetSpec.toAvatar] exactly
 * as the loader would, so an archive that wouldn't render is rejected up front
 * instead of silently installing a pet that never appears.
 *
 * Accepts either archive shape: a `pet.json` at the archive root, or a single
 * top-level folder containing it (the shallowest `pet.json` wins). The installed
 * pack directory is named from the manifest `id` (sanitized), replacing any
 * existing pack of the same name.
 */
object PetImporter {
    private const val TAG = "PetImporter"
    private const val DIR = "pets"
    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 96L * 1024 * 1024
    private const val MAX_ENTRIES = 512

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Import the pack at [uri] (a `.zip`) into the app's pets directory. */
    fun importZip(context: Context, uri: Uri): PetImportResult {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (t: Throwable) {
            Log.w(TAG, "openInputStream failed: ${t.message}")
            null
        } ?: return PetImportResult.Failure("Couldn't open that file.")
        return input.use { importStream(it, UserContentDir.resolve(context, DIR), context.cacheDir) }
    }

    /**
     * Pure core: extract [zip] into a fresh staging dir under [workDir], validate,
     * and install into [petsDir]. No Android Context — unit-testable.
     */
    internal fun importStream(zip: InputStream, petsDir: File, workDir: File): PetImportResult {
        val staging = File(workDir, "pet-import-${UUID.randomUUID()}")
        try {
            staging.mkdirs()
            (extract(zip, staging) as? ExtractResult.Failure)?.let {
                return PetImportResult.Failure(it.reason)
            }

            val manifest = findManifest(staging)
                ?: return PetImportResult.Failure("No pet.json found in that archive.")
            val packRoot = manifest.parentFile ?: staging

            val spec = try {
                json.decodeFromString(PetSpec.serializer(), manifest.readText())
            } catch (t: Throwable) {
                return PetImportResult.Failure("That pet.json isn't valid JSON.")
            }
            val resolved = if (spec.id.isBlank()) spec.copy(id = packRoot.name.ifBlank { "pet" }) else spec
            val avatar = try {
                resolved.toAvatar(packRoot)
            } catch (t: Throwable) {
                return PetImportResult.Failure(t.message ?: "That pack isn't a valid pet.")
            }

            val target = File(petsDir, sanitize(avatar.id))
            if (target.exists()) target.deleteRecursively()
            petsDir.mkdirs()
            if (!packRoot.copyRecursively(target, overwrite = true)) {
                return PetImportResult.Failure("Couldn't write the pet into place.")
            }
            return PetImportResult.Success(avatar.id, avatar.label)
        } catch (t: Throwable) {
            Log.w(TAG, "import failed: ${t.message}")
            return PetImportResult.Failure("Import failed: ${t.message}")
        } finally {
            staging.deleteRecursively()
        }
    }

    private sealed interface ExtractResult {
        data object Ok : ExtractResult
        data class Failure(val reason: String) : ExtractResult
    }

    /** Extract every file entry of [zip] into [staging], confined and bounded. */
    private fun extract(zip: InputStream, staging: File): ExtractResult {
        val base = staging.canonicalPath + File.separator
        var total = 0L
        var count = 0
        ZipInputStream(zip).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (++count > MAX_ENTRIES) return ExtractResult.Failure("Archive has too many files.")
                if (entry.isDirectory) {
                    zin.closeEntry()
                    continue
                }
                val name = entry.name.replace('\\', '/')
                val out = File(staging, name)
                if (!out.canonicalPath.startsWith(base)) {
                    // zip-slip: entry path escapes the staging dir.
                    return ExtractResult.Failure("Archive contains an unsafe path.")
                }
                out.parentFile?.mkdirs()
                var written = 0L
                out.outputStream().use { os ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        written += n
                        total += n
                        if (written > MAX_ENTRY_BYTES) return ExtractResult.Failure("A file in the archive is too large.")
                        if (total > MAX_TOTAL_BYTES) return ExtractResult.Failure("The archive is too large.")
                        os.write(buf, 0, n)
                    }
                }
                zin.closeEntry()
            }
        }
        return ExtractResult.Ok
    }

    /** Shallowest `pet.json` under [root] (breadth-first), or null. */
    private fun findManifest(root: File): File? {
        val queue = ArrayDeque<File>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val manifest = File(dir, "pet.json")
            if (manifest.isFile) return manifest
            dir.listFiles()?.forEach { if (it.isDirectory) queue.add(it) }
        }
        return null
    }

    /** Reduce a manifest id to a safe pack-directory name. */
    private fun sanitize(id: String): String {
        val cleaned = id.trim()
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '-' }
            .joinToString("")
            .trim('.', '-')
        return cleaned.ifBlank { "pet" }
    }
}
