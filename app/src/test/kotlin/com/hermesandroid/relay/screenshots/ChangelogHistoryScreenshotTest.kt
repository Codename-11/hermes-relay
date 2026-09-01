package com.hermesandroid.relay.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.ui.components.ChangelogStore
import com.hermesandroid.relay.ui.components.ChangelogVersion
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
        lateinit var latest: ChangelogVersion
        compose.setContent {
            latest = ChangelogStore.load(androidx.compose.ui.platform.LocalContext.current).versions.first()
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                ChangelogScreen(onClose = {})
            }
        }
        compose.onNodeWithText("v${latest.version} — ${latest.title}").assertExists()
        compose.onNodeWithText("Highlights").assertExists()
        latest.remainingChangesOfKind("fixed").firstOrNull()?.let { fixed ->
            compose.onNodeWithText("Fixed").assertExists()
            compose.onNodeWithText(fixed.title).assertExists()
        }
        compose.onNodeWithText("Compatibility").assertExists()
        compose.onNodeWithText("Installed").assertExists()
        compose.onRoot().captureRoboImage("build/ui-regression/changelog-history.png")
    }
}
