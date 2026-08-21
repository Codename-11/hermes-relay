package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.hermesandroid.relay.ui.theme.AppFont
import com.hermesandroid.relay.ui.theme.AppThemes
import com.hermesandroid.relay.ui.theme.AppearanceShape
import com.hermesandroid.relay.ui.theme.normalizeAccentHex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal data class PersistedAppearance(
    val themePreference: String = "auto",
    val appThemeId: String = AppThemes.DEFAULT_ID,
    val accentHex: String? = null,
    val shapeId: String = AppearanceShape.DEFAULT.id,
    val appFontId: String = AppFont.DEFAULT.id,
    val fontScale: Float = 1.0f,
    val customTheme: CustomThemePreset? = null,
)

internal object AppearancePreferences {
    val themeKey = stringPreferencesKey("theme")
    val appThemeKey = stringPreferencesKey("app_theme")
    val accentKey = stringPreferencesKey("appearance_accent")
    val shapeKey = stringPreferencesKey("appearance_shape")
    val appFontKey = stringPreferencesKey("app_font")
    val fontScaleKey = floatPreferencesKey("font_scale")
    val customThemesKey = stringPreferencesKey("custom_theme_presets")

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CustomThemePreset.serializer())

    fun state(context: Context): Flow<PersistedAppearance> = context.applicationContext.relayDataStore.data
        .map { preferences ->
            val customThemes = decodeCustomThemes(preferences[customThemesKey])
            val requestedThemeId = preferences[appThemeKey]
            val customTheme = CustomThemePreset.idFromAppTheme(requestedThemeId)
                ?.let { id -> customThemes.firstOrNull { it.id == id } }
            PersistedAppearance(
                themePreference = preferences[themeKey]
                    ?.takeIf { it == "auto" || it == "light" || it == "dark" }
                    ?: "auto",
                appThemeId = customTheme?.appThemeId ?: AppThemes.byId(requestedThemeId).id,
                accentHex = normalizeAccentHex(preferences[accentKey]),
                shapeId = AppearanceShape.fromId(preferences[shapeKey]).id,
                appFontId = AppFont.byId(preferences[appFontKey]).id,
                fontScale = (preferences[fontScaleKey] ?: 1.0f).coerceIn(0.85f, 1.3f),
                customTheme = customTheme,
            )
        }

    fun shape(context: Context): Flow<String> = state(context).map { it.shapeId }

    fun customThemes(context: Context): Flow<List<CustomThemePreset>> =
        context.applicationContext.relayDataStore.data.map { decodeCustomThemes(it[customThemesKey]) }

    fun decodeCustomThemes(raw: String?): List<CustomThemePreset> = raw
        ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
        .orEmpty()
        .mapNotNull { it.normalized() }
        .distinctBy { it.id }
        .take(CustomThemePreset.MAX_PRESETS)

    fun encodeCustomThemes(themes: List<CustomThemePreset>): String = json.encodeToString(
        serializer,
        themes.mapNotNull { it.normalized() }
            .distinctBy { it.id }
            .take(CustomThemePreset.MAX_PRESETS),
    )

    fun upsertCustomTheme(
        current: List<CustomThemePreset>,
        preset: CustomThemePreset,
    ): List<CustomThemePreset>? {
        val normalized = preset.normalized() ?: return null
        val safeCurrent = current.mapNotNull { it.normalized() }
            .distinctBy { it.id }
            .take(CustomThemePreset.MAX_PRESETS)
        val existingIndex = safeCurrent.indexOfFirst { it.id == normalized.id }
        if (existingIndex < 0 && safeCurrent.size >= CustomThemePreset.MAX_PRESETS) return null
        return safeCurrent.toMutableList().apply {
            if (existingIndex >= 0) set(existingIndex, normalized) else add(normalized)
        }
    }
}
