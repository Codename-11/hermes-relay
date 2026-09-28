package com.hermesandroid.relay.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hermesandroid.relay.HermesRelayApp
import com.hermesandroid.relay.MainActivity
import com.hermesandroid.relay.data.AppearancePreferences
import com.hermesandroid.relay.data.CustomThemePreset
import com.hermesandroid.relay.data.relayDataStore
import com.hermesandroid.relay.runtime.HermesRuntimeInitializationState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Activity/DataStore/Compose ownership, with the device set to dark mode. */
@RunWith(AndroidJUnit4::class)
class AppearanceColdStartInstrumentedTest {
    @Test
    fun savedAppearanceOwnsColdStartAndLaterModeChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<HermesRelayApp>()
        val previousPreferences = runBlocking { app.relayDataStore.data.first() }
        val originalNightMode =
            (app.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).nightMode
        assumeTrue(
            originalNightMode == UiModeManager.MODE_NIGHT_AUTO ||
                originalNightMode == UiModeManager.MODE_NIGHT_NO ||
                originalNightMode == UiModeManager.MODE_NIGHT_YES,
        )
        val custom = CustomThemePreset(
            id = "day",
            name = "Day",
            mode = CustomThemePreset.MODE_LIGHT,
            backgroundHex = "#F5F5F5",
            surfaceHex = "#FFFFFF",
            accentHex = "#0E18D6",
            textHex = "#111111",
        )
        instrumentation.uiAutomation.executeShellCommand("cmd uimode night yes").close()
        try {
            runBlocking {
                app.relayDataStore.edit { preferences ->
                    preferences[AppearancePreferences.themeKey] = "light"
                    preferences[AppearancePreferences.appThemeKey] = AppThemes.DEFAULT_ID
                }
            }
            instrumentation.runOnMainSync {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
            ActivityScenario.launch(MainActivity::class.java).use {
                runBlocking {
                    withTimeout(30_000) {
                        app.runtime.connectionViewModel.isReady.first { it }
                        app.runtime.initializationState.first {
                            it == HermesRuntimeInitializationState.Ready
                        }
                    }
                }
                awaitTheme(isDark = false, nightMode = AppCompatDelegate.MODE_NIGHT_NO)

                runBlocking {
                    app.relayDataStore.edit {
                        it[AppearancePreferences.themeKey] = "dark"
                    }
                }
                awaitTheme(isDark = true, nightMode = AppCompatDelegate.MODE_NIGHT_YES)

                runBlocking {
                    app.relayDataStore.edit {
                        it[AppearancePreferences.themeKey] = "auto"
                    }
                }
                awaitTheme(isDark = true, nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

                runBlocking {
                    app.relayDataStore.edit {
                        it[AppearancePreferences.customThemesKey] =
                            AppearancePreferences.encodeCustomThemes(listOf(custom))
                        it[AppearancePreferences.appThemeKey] = custom.appThemeId
                    }
                }
                awaitTheme(isDark = false, nightMode = AppCompatDelegate.MODE_NIGHT_NO)
            }
        } finally {
            runBlocking {
                app.relayDataStore.edit { preferences ->
                    previousPreferences[AppearancePreferences.themeKey]?.let {
                        preferences[AppearancePreferences.themeKey] = it
                    } ?: preferences.remove(AppearancePreferences.themeKey)
                    previousPreferences[AppearancePreferences.appThemeKey]?.let {
                        preferences[AppearancePreferences.appThemeKey] = it
                    } ?: preferences.remove(AppearancePreferences.appThemeKey)
                    previousPreferences[AppearancePreferences.customThemesKey]?.let {
                        preferences[AppearancePreferences.customThemesKey] = it
                    } ?: preferences.remove(AppearancePreferences.customThemesKey)
                }
            }
            val restoreMode = when (originalNightMode) {
                UiModeManager.MODE_NIGHT_YES -> "yes"
                UiModeManager.MODE_NIGHT_NO -> "no"
                else -> "auto"
            }
            instrumentation.uiAutomation.executeShellCommand("cmd uimode night $restoreMode").close()
        }
    }

    private fun awaitTheme(isDark: Boolean, nightMode: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (RelayRefresh.activePalette.isDark == isDark &&
                AppCompatDelegate.getDefaultNightMode() == nightMode
            ) {
                assertEquals(nightMode, AppCompatDelegate.getDefaultNightMode())
                if (isDark) assertTrue(RelayRefresh.activePalette.isDark)
                else assertFalse(RelayRefresh.activePalette.isDark)
                return
            }
            SystemClock.sleep(25)
        }
        fail(
            "Appearance did not settle: paletteDark=${RelayRefresh.activePalette.isDark}, " +
                "nightMode=${AppCompatDelegate.getDefaultNightMode()}",
        )
    }
}
