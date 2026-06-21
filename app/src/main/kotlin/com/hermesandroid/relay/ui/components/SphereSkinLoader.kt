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
 * The directory is app-scoped external storage, so no runtime permission is
 * needed. Users place files there via the system file picker / "Save to app"
 * flows, ADB (`adb push file.json $(...)/files/spheres/`), or a future in-app
 * import button. Resolved via [UserContentDir]. See `docs/sphere-spec.md`.
 */
object SphereSkinLoader {
    private const val TAG = "SphereSkinLoader"
    private const val DIR = "spheres"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** The directory users drop `*.json` sphere specs into. Created if absent. */
    fun userDir(context: Context): File = UserContentDir.resolve(context, DIR)

    /** Convenience overload resolving the sphere-skin directory from [context]. */
    fun loadUserSkins(context: Context): List<SphereSkin> = loadUserSkins(userDir(context))

    /**
     * Parse every `*.json` in [dir] into a [SphereSkin], skipping any file that
     * fails to parse or validate. Returns skins sorted by filename for a stable
     * picker order. Pure (no Android Context), so the discovery/skip-invalid
     * behavior is unit-testable against a temp directory.
     */
    fun loadUserSkins(dir: File): List<SphereSkin> {
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
