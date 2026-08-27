package com.hermesandroid.relay.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.ui.components.ChangelogStore
import com.hermesandroid.relay.ui.components.WhatsNewToast
import com.hermesandroid.relay.ui.components.WhatsNewToastContent
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class WhatsNewToastScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactNoticeRemainsClearAtLargeText() {
        compose.setContent {
            val context = LocalContext.current
            val density = LocalDensity.current
            val entry = ChangelogStore.load(context).versions.first()
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.35f)) {
                HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                            .padding(12.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        WhatsNewToastContent(
                            entry = entry,
                            progress = 0.62f,
                            onExpand = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Supervised Mode").assertExists()
        compose.onNodeWithText("Also: 2 fixes").assertExists()
        compose.onNodeWithText("Accurate activity, safer return…").assertExists()
        compose.onNodeWithText("View all").assertExists()
        compose.onNodeWithContentDescription("Close").assertExists()
        compose.onRoot().captureRoboImage("build/ui-regression/whats-new-toast-large-text.png")
    }

    @Test fun exposesExpandAndCloseActions() {
        var expanded = false
        var dismissed = false
        compose.setContent {
            val entry = ChangelogStore.load(LocalContext.current).versions.first()
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                WhatsNewToastContent(
                    entry = entry,
                    progress = 1f,
                    onExpand = { expanded = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithText("Supervised Mode").performClick()
        expanded = false
        compose.onNodeWithText("View all").performClick()
        compose.onNodeWithContentDescription("Close").performClick()

        assertTrue(expanded)
        assertTrue(dismissed)
    }

    @Test fun horizontalSwipeDismissesTheNotice() {
        var dismissed = false
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                WhatsNewToast(
                    onDismiss = { dismissed = true },
                    onExpand = {},
                    autoDismissMillis = 60_000L,
                )
            }
        }
        compose.mainClock.advanceTimeBy(300L)
        compose.onNodeWithText("Supervised Mode").performTouchInput { swipeLeft(durationMillis = 300L) }
        compose.mainClock.advanceTimeBy(300L)

        assertTrue(dismissed)
    }
}
