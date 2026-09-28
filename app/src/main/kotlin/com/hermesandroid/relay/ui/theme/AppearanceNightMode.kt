package com.hermesandroid.relay.ui.theme

import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.preferences.core.Preferences
import com.hermesandroid.relay.data.AppearancePreferences
import com.hermesandroid.relay.data.CustomThemePreset
import com.hermesandroid.relay.data.PersistedAppearance

/**
 * Maps the persisted appearance preference onto AppCompat's night mode.
 *
 * Compose paints its own palette, while the activity and platform surfaces use
 * Theme.AppCompat.DayNight. Both resolve from the same saved appearance.
 */
internal object AppearanceNightMode {
    private val VALID_PREFERENCES = setOf("auto", "light", "dark")

    fun normalizePreference(raw: String?): String =
        raw?.takeIf { it in VALID_PREFERENCES } ?: "auto"

    fun nightModeFor(
        themePreference: String,
        themeMode: ThemeMode,
        customTheme: CustomThemePreset? = null,
    ): Int {
        customTheme?.let { preset ->
            return if (preset.isDark) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        }
        return when (themeMode) {
            ThemeMode.DARK_ONLY -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.LIGHT_ONLY -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.BOTH -> when (normalizePreference(themePreference)) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        }
    }

    fun nightModeFor(appearance: PersistedAppearance): Int {
        val appTheme = appearance.customTheme?.toAppTheme() ?: AppThemes.byId(appearance.appThemeId)
        return nightModeFor(
            themePreference = appearance.customTheme?.mode ?: appearance.themePreference,
            themeMode = appTheme.mode,
            customTheme = appearance.customTheme,
        )
    }

    fun nightModeFor(preferences: Preferences): Int =
        nightModeFor(AppearancePreferences.decode(preferences))

    /** Apply only when the mode actually changes — avoids redundant uiMode churn. */
    fun apply(nightMode: Int) {
        try {
            if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
                AppCompatDelegate.setDefaultNightMode(nightMode)
            }
        } catch (_: Throwable) {
            // Robolectric / headless hosts may not support night-mode switches.
        }
    }

    fun applyFromPreferences(preferences: Preferences) {
        apply(nightModeFor(preferences))
    }

    fun applyFromAppearance(appearance: PersistedAppearance) {
        apply(nightModeFor(appearance))
    }
}
