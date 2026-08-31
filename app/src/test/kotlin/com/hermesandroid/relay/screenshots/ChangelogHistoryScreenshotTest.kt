package com.hermesandroid.relay.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.ui.screens.ChangelogScreen
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class ChangelogHistoryScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun latestReleaseShowsHighlightsAndCompleteDetails() {
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                ChangelogScreen(onClose = {})
            }
        }
        compose.onNodeWithText("v1.14.0 — Connections, delegated work, Git, and voice").assertExists()
        compose.onNodeWithText("Highlights").assertExists()
        compose.onNodeWithText("Fixed").assertExists()
        compose.onNodeWithText("Wake-word detection starts reliably").assertExists()
        compose.onNodeWithText("Compatibility").assertExists()
        compose.onNodeWithText("Installed").assertExists()
        compose.onRoot().captureRoboImage("build/ui-regression/changelog-history.png")
    }
}
