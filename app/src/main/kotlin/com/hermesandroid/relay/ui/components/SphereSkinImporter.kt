package com.hermesandroid.relay.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

sealed interface SphereSkinImportResult {
    data class Success(val id: String, val label: String) : SphereSkinImportResult
    data class Failure(val reason: String) : SphereSkinImportResult
}

/** Imports one declarative sphere-skin JSON file into app-scoped local storage. */
object SphereSkinImporter {
    private const val TAG = "SphereSkinImporter"
    private const val MAX_BYTES = 256L * 1024L

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun importUri(context: Context, uri: Uri): SphereSkinImportResult {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (t: Throwable) {
            Log.w(TAG, "openInputStream failed: ${t.message}")
            null
        } ?: return SphereSkinImportResult.Failure("Couldn't open that file.")

        return input.use { importStream(it, SphereSkinLoader.userDir(context)) }
    }

    /** Pure import core used by tests: bounds, parses, validates, then swaps with rollback. */
    internal fun importStream(input: InputStream, skinsDir: File): SphereSkinImportResult {
        return try {
            val bytes = readBounded(input)
                ?: return SphereSkinImportResult.Failure("That sphere file is too large.")
            val spec = try {
                json.decodeFromString(SphereSpec.serializer(), bytes.decodeToString())
            } catch (_: Throwable) {
                return SphereSkinImportResult.Failure("That sphere file isn't valid JSON.")
            }
            val resolved = spec.copy(id = spec.id.ifBlank { "custom-sphere" })
            val skin = try {
                resolved.toSkin()
            } catch (t: Throwable) {
                return SphereSkinImportResult.Failure(t.message ?: "That isn't a valid sphere skin.")
            }

            skinsDir.mkdirs()
            val target = File(skinsDir, "${safeFileName(skin.id)}.json")
            val staging = File(skinsDir, ".${target.name}.tmp")
            val backup = File(skinsDir, ".${target.name}.bak")
            staging.writeBytes(bytes)
            if (backup.exists() && !target.exists()) backup.renameTo(target)
            if (backup.exists() && target.exists()) backup.delete()
            if (target.exists() && !target.renameTo(backup)) {
                staging.delete()
                return SphereSkinImportResult.Failure("Couldn't replace the existing sphere skin.")
            }
            if (!staging.renameTo(target)) {
                staging.delete()
                if (backup.exists()) backup.renameTo(target)
                return SphereSkinImportResult.Failure("Couldn't save that sphere skin.")
            }
            backup.delete()
            SphereSkinImportResult.Success(skin.id, skin.label)
        } catch (t: Throwable) {
            Log.w(TAG, "import failed: ${t.message}")
            SphereSkinImportResult.Failure("Couldn't import that sphere skin.")
        }
    }

    private fun readBounded(input: InputStream): ByteArray? {
        val buffer = ByteArray(8 * 1024)
        val output = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BYTES) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun safeFileName(id: String): String = id.trim()
        .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '-' }
        .joinToString("")
        .trim('.', '-')
        .ifBlank { "custom-sphere" }
}
