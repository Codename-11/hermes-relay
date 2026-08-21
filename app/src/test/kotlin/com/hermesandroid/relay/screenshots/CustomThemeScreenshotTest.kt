package com.hermesandroid.relay.screenshots

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.AppearancePreferences
import com.hermesandroid.relay.data.CustomThemePreset
import com.hermesandroid.relay.data.relayDataStore
import com.hermesandroid.relay.ui.screens.CustomThemeScreen
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-432dpi")
class CustomThemeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun savedPresetWorkshopMatchesSelectedDirection() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val presets = listOf(
            preset("aurora", "Aurora", "#0B0B0F", "#141421", "#5B6CFF", "#F5F6F7"),
            preset("terminal-green", "Terminal Green", "#04120E", "#0C2921", "#00BFA5", "#ECF7F3"),
            preset("warm-mono", "Warm Mono", "#180B07", "#2A1710", "#E45B32", "#FFF0E6"),
        )
        runBlocking {
            app.relayDataStore.edit {
                it[AppearancePreferences.customThemesKey] = AppearancePreferences.encodeCustomThemes(presets)
                it[AppearancePreferences.appThemeKey] = presets.first().appThemeId
                it[AppearancePreferences.shapeKey] = "balanced"
            }
        }
        val vm = ConnectionViewModel(app)
        compose.setContent {
            HermesRelayTheme(customTheme = presets.first(), shapeId = "balanced") {
                CustomThemeScreen(connectionViewModel = vm, onBack = {})
            }
        }

        compose.onNodeWithText("Aurora").assertExists()
        compose.onRoot().captureRoboImage("build/ui-evidence/custom-theme-preset-workshop.png")
        compose.onAllNodesWithContentDescription("More theme actions")[0].performClick()
        compose.onNodeWithText("Duplicate").assertExists()
        compose.onRoot().captureRoboImage("build/ui-evidence/custom-theme-preset-workshop-menu.png")
    }

    @Test
    fun newPresetEnablesSaveThroughTheRealScreenOwner() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val aurora = preset("aurora", "Aurora", "#0B0B0F", "#141421", "#5B6CFF", "#F5F6F7")
        runBlocking {
            app.relayDataStore.edit {
                it[AppearancePreferences.customThemesKey] = AppearancePreferences.encodeCustomThemes(listOf(aurora))
                it[AppearancePreferences.appThemeKey] = aurora.appThemeId
            }
        }
        val vm = ConnectionViewModel(app)
        compose.setContent {
            HermesRelayTheme(customTheme = aurora) {
                CustomThemeScreen(connectionViewModel = vm, onBack = {})
            }
        }

        compose.onNodeWithText("Aurora").assertExists()
        compose.onNodeWithText("New").performClick()
        compose.onNodeWithText("Save changes").assertIsEnabled()
    }

    @Test
    fun fixedModeAndShapeControlsRenderBelowTheFold() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val aurora = preset("aurora", "Aurora", "#0B0B0F", "#141421", "#5B6CFF", "#F5F6F7")
        runBlocking {
            app.relayDataStore.edit {
                it[AppearancePreferences.customThemesKey] = AppearancePreferences.encodeCustomThemes(listOf(aurora))
                it[AppearancePreferences.appThemeKey] = aurora.appThemeId
            }
        }
        val vm = ConnectionViewModel(app)
        compose.setContent {
            HermesRelayTheme(customTheme = aurora) {
                CustomThemeScreen(connectionViewModel = vm, onBack = {})
            }
        }

        compose.onNodeWithText("Changes saved to Aurora").performScrollTo()
        compose.onNodeWithText("Auto").assertIsNotEnabled()
        compose.onNodeWithText("Light").assertIsNotEnabled()
        compose.onRoot().captureRoboImage("build/ui-evidence/custom-theme-preset-workshop-style.png")
    }

    private fun preset(
        id: String,
        name: String,
        background: String,
        surface: String,
        accent: String,
        text: String,
    ) = CustomThemePreset(
        id = id,
        name = name,
        mode = CustomThemePreset.MODE_DARK,
        backgroundHex = background,
        surfaceHex = surface,
        accentHex = accent,
        textHex = text,
        shapeId = "balanced",
    )
}
