package com.hermesandroid.relay.data

import com.hermesandroid.relay.ui.theme.AppearanceShape
import com.hermesandroid.relay.ui.theme.normalizeAccentHex
import kotlinx.serialization.Serializable

@Serializable
data class CustomThemePreset(
    val id: String,
    val name: String,
    val mode: String,
    val backgroundHex: String,
    val surfaceHex: String,
    val accentHex: String,
    val textHex: String,
    val shapeId: String = AppearanceShape.DEFAULT.id,
) {
    val appThemeId: String get() = "$APP_THEME_PREFIX$id"
    val isDark: Boolean get() = mode != MODE_LIGHT

    fun normalized(): CustomThemePreset? {
        val normalizedId = id.trim().take(64).takeIf { it.matches(ID_PATTERN) } ?: return null
        val normalizedName = name.trim().replace(WHITESPACE, " ").take(MAX_NAME_LENGTH)
            .takeIf(String::isNotBlank) ?: return null
        return copy(
            id = normalizedId,
            name = normalizedName,
            mode = if (mode == MODE_LIGHT) MODE_LIGHT else MODE_DARK,
            backgroundHex = normalizeAccentHex(backgroundHex) ?: return null,
            surfaceHex = normalizeAccentHex(surfaceHex) ?: return null,
            accentHex = normalizeAccentHex(accentHex) ?: return null,
            textHex = normalizeAccentHex(textHex) ?: return null,
            shapeId = AppearanceShape.fromId(shapeId).id,
        )
    }

    companion object {
        const val APP_THEME_PREFIX = "custom:"
        const val MODE_LIGHT = "light"
        const val MODE_DARK = "dark"
        const val MAX_PRESETS = 20
        const val MAX_NAME_LENGTH = 24

        private val ID_PATTERN = Regex("[A-Za-z0-9_-]+")
        private val WHITESPACE = Regex("\\s+")

        fun idFromAppTheme(appThemeId: String?): String? = appThemeId
            ?.takeIf { it.startsWith(APP_THEME_PREFIX) }
            ?.removePrefix(APP_THEME_PREFIX)
            ?.takeIf(String::isNotBlank)
    }
}
