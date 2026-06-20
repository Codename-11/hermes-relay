package com.hermesandroid.relay.ui.components

import android.content.Context
import java.io.File

/**
 * Single source of truth for *where side-loaded customization content lives* —
 * the agent-avatar "pets" ([com.hermesandroid.relay.ui.components.avatar.PetLoader])
 * and the sphere skins ([SphereSkinLoader]). Both customization systems are
 * reachable the same way, so they resolve their directory through here.
 *
 * We prefer **external app-scoped storage** (`getExternalFilesDir`), i.e.
 * `/sdcard/Android/data/<pkg>/files/<name>/`, because that is the directory a
 * user can populate with a plain `adb push` (or a file-manager copy) with **no
 * runtime permission** on API 19+. Internal `filesDir`
 * (`/data/data/<pkg>/files/`) is *not* writable over `adb push` on a
 * non-rooted device, so pointing users there made side-loading impossible — the
 * bug this helper exists to close.
 *
 * If external storage is unavailable (un-mounted — rare for app-scoped emulated
 * storage), we fall back to internal `filesDir` so the picker still works; the
 * directory is created if absent either way.
 */
object UserContentDir {
    /** Resolve (and create) the app-scoped directory named [name] for user content. */
    fun resolve(context: Context, name: String): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, name).apply { if (!exists()) mkdirs() }
    }
}
