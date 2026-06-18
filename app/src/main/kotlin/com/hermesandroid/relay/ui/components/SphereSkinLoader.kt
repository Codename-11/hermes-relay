package com.hermesandroid.relay.ui.components

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Loads user-authored sphere skins from JSON specs dropped into the app-private
 * `spheres/` directory. Data-only: specs are parsed and validated, never
 * executed. Invalid files are skipped (with a log line) so one bad spec can't
 * break the picker.
 *
 * The directory is app-private internal storage, so no runtime permission is
 * needed. Users place files there via the system file picker / "Save to app"
 * flows, ADB (`adb push file.json $(...)/files/spheres/`), or a future in-app
 * import button. See `docs/sphere-spec.md`.
 */
object SphereSkinLoader {
    private const val TAG = "SphereSkinLoader"
    private const val DIR = "spheres"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** The directory users drop `*.json` sphere specs into. Created if absent. */
    fun userDir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /**
     * Parse every `*.json` in [userDir] into a [SphereSkin], skipping any file
     * that fails to parse or validate. Returns skins sorted by filename for a
     * stable picker order.
     */
    fun loadUserSkins(context: Context): List<SphereSkin> {
        val dir = userDir(context)
        val files = dir.listFiles { file ->
            file.isFile && file.name.endsWith(".json", ignoreCase = true)
        } ?: return emptyList()

        return files.sortedBy { it.name }.mapNotNull { file ->
            try {
                val spec = json.decodeFromString(SphereSpec.serializer(), file.readText())
                val resolved = if (spec.id.isBlank()) spec.copy(id = file.nameWithoutExtension) else spec
                resolved.toSkin()
            } catch (t: Throwable) {
                Log.w(TAG, "Skipping sphere spec ${file.name}: ${t.message}")
                null
            }
        }
    }
}
