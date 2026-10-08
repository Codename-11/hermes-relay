package com.hermesandroid.relay.ui

import android.app.Activity
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.CustomThemePreset
import com.hermesandroid.relay.ui.theme.AppThemes
import com.hermesandroid.relay.ui.theme.BrandPalette
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.ui.theme.LocalBrand
import com.hermesandroid.relay.ui.theme.RelayRefresh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h720dp-xhdpi-notnight")
class StartupLoadingThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lightSurvivesLoadingTransitions() = exercise("light", false)
    @Test fun autoLightSurvivesLoadingTransitions() = exercise("auto", false)
    @Test fun darkSurvivesLoadingTransitions() = exercise("dark", true)

    @Test @Config(qualifiers = "w360dp-h720dp-xhdpi-night")
    fun autoDarkSurvivesLoadingTransitions() = exercise("auto", true)

    @Test @Config(qualifiers = "w360dp-h720dp-xhdpi-night")
    fun explicitLightOverridesSystemDark() = exercise("light", false)

    @Test fun fixedDarkPresetRetainsItsOwnPalette() = exercise("light", true, appThemeId = AppThemes.Mono.id)

    @Test fun customLightPaletteSurvivesLoadingTransitions() = exercise(
        "auto", false,
        customTheme = CustomThemePreset(
            id = "morning", name = "Morning", mode = CustomThemePreset.MODE_LIGHT,
            backgroundHex = "#F7F3E8", surfaceHex = "#ECE4D3", accentHex = "#A52A60", textHex = "#262019",
        ),
    )

    private fun exercise(
        preference: String,
        expectedDark: Boolean,
        appThemeId: String = AppThemes.DEFAULT_ID,
        customTheme: CustomThemePreset? = null,
    ) {
        val evidenceName = "${customTheme?.id ?: appThemeId}-$preference-$expectedDark"
        val runtimeReady = mutableStateOf(false)
        val navigationHydrated = mutableStateOf(false)
        val routeAllowed = mutableStateOf(true)
        var windowView: View? = null
        var ambientPalette: BrandPalette? = null
        var themeCommits = 0
        compose.setContent {
            val view = LocalView.current
            SideEffect { windowView = view }
            if (!runtimeReady.value) {
                SupervisedStartupLoadingScreen()
            } else {
                HermesRelayTheme(appThemeId = appThemeId, themePreference = preference, customTheme = customTheme) {
                    val palette = LocalBrand.current
                    SideEffect { ambientPalette = palette; themeCommits++ }
                    HydratingContent(navigationHydrated, routeAllowed)
                }
            }
        }

        fun assertAppearance(dark: Boolean, compareAmbient: Boolean) {
            compose.runOnIdle {
                assertEquals("legacy palette mode", dark, RelayRefresh.activePalette.isDark)
                if (compareAmbient) {
                    assertNotNull(ambientPalette)
                    assertEquals("ambient and legacy palettes", ambientPalette, RelayRefresh.activePalette)
                }
                val view = checkNotNull(windowView)
                // A real Activity window is required: a null Activity must not silently skip this check.
                val activity = view.context as Activity
                val bars = WindowCompat.getInsetsController(activity.window, view)
                assertEquals("status bar contrast", !dark, bars.isAppearanceLightStatusBars)
                assertEquals("navigation bar contrast", !dark, bars.isAppearanceLightNavigationBars)
            }
        }

        // Recreate both composition roots repeatedly. This covers theme handoff,
        // not Android process death or firmware-specific launch behavior.
        repeat(3) { launch ->
            compose.onNodeWithText("Loading protected settings…").assertIsDisplayed()
            assertAppearance(dark = true, compareAmbient = false)
            compose.runOnIdle { runtimeReady.value = true }
            compose.onNodeWithText("Loading protected settings…").assertIsDisplayed()
            assertAppearance(expectedDark, compareAmbient = true)
            if (launch == 0) {
                compose.onRoot().captureRoboImage("build/ui-evidence/startup-$evidenceName-covered.png")
            }

            val committedBeforeHydration = themeCommits
            repeat(3) {
                compose.runOnIdle { navigationHydrated.value = true }
                compose.onNodeWithText("Loading protected settings…").assertDoesNotExist()
                compose.onNodeWithText("Legacy surface").assertIsDisplayed()
                assertAppearance(expectedDark, compareAmbient = true)
                if (launch == 0 && it == 0) {
                    compose.onRoot().captureRoboImage("build/ui-evidence/startup-$evidenceName-ready.png")
                }
                compose.runOnIdle { routeAllowed.value = false }
                compose.onNodeWithText("Loading protected settings…").assertIsDisplayed()
                assertAppearance(expectedDark, compareAmbient = true)
                compose.runOnIdle { routeAllowed.value = true; navigationHydrated.value = false }
                compose.onNodeWithText("Loading protected settings…").assertIsDisplayed()
                assertAppearance(expectedDark, compareAmbient = true)
            }
            compose.runOnIdle {
                assertEquals("cover changes must not rely on recomposing the outer theme", committedBeforeHydration, themeCommits)
                runtimeReady.value = false
            }
        }
    }

    @Composable
    private fun HydratingContent(navigationHydrated: State<Boolean>, routeAllowed: State<Boolean>) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Text(
                    "Material surface",
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(24.dp),
                )
                Text(
                    "Legacy surface",
                    color = RelayRefresh.Ink,
                    modifier = Modifier.fillMaxWidth().background(RelayRefresh.Navy).padding(24.dp),
                )
            }
            RelayNavigationLoadingCover(navigationHydrated.value, routeAllowed.value, Screen.Chat.route)
        }
    }
}
