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

internal data class PersistedAppearance(
    val themePreference: String = "auto",
    val appThemeId: String = AppThemes.DEFAULT_ID,
    val accentHex: String? = null,
    val shapeId: String = AppearanceShape.DEFAULT.id,
    val appFontId: String = AppFont.DEFAULT.id,
    val fontScale: Float = 1.0f,
)

internal object AppearancePreferences {
    val themeKey = stringPreferencesKey("theme")
    val appThemeKey = stringPreferencesKey("app_theme")
    val accentKey = stringPreferencesKey("appearance_accent")
    val shapeKey = stringPreferencesKey("appearance_shape")
    val appFontKey = stringPreferencesKey("app_font")
    val fontScaleKey = floatPreferencesKey("font_scale")

    fun state(context: Context): Flow<PersistedAppearance> = context.applicationContext.relayDataStore.data
        .map { preferences ->
            PersistedAppearance(
                themePreference = preferences[themeKey]
                    ?.takeIf { it == "auto" || it == "light" || it == "dark" }
                    ?: "auto",
                appThemeId = AppThemes.byId(preferences[appThemeKey]).id,
                accentHex = normalizeAccentHex(preferences[accentKey]),
                shapeId = AppearanceShape.fromId(preferences[shapeKey]).id,
                appFontId = AppFont.byId(preferences[appFontKey]).id,
                fontScale = (preferences[fontScaleKey] ?: 1.0f).coerceIn(0.85f, 1.3f),
            )
        }

    fun shape(context: Context): Flow<String> = state(context).map { it.shapeId }
}
