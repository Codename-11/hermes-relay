package com.hermesandroid.relay.screenshots

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.ui.components.WhatsNewDialog
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class WhatsNewScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun modalRemainsUsableAtLargeText() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.35f)) {
                HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                    WhatsNewDialog(onDismiss = {}, onViewHistory = {})
                }
            }
        }
        compose.onNodeWithText("View full history").assertExists()
        compose.onRoot().captureRoboImage("build/ui-regression/whats-new-large-text.png")
    }
}
