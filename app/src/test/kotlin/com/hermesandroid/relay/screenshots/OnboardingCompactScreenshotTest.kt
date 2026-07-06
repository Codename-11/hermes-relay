package com.hermesandroid.relay.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.ui.onboarding.OnboardingPage
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression harness for issue #145 — onboarding slide content overflowed
 * below the fold with no scroll affordance on short viewports / raised font
 * scale. Renders [OnboardingPage] the way the pager hosts it (a centering
 * fillMaxSize Box) at compact heights and a raised font scale, then scrolls
 * to the last body line to prove every line is reachable.
 *
 * Render-success + reachability assertions only — no golden PNGs are
 * committed; the store screenshot set is untouched.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h480dp-xhdpi")
class OnboardingCompactScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val lastLine = "Final onboarding body line for reachability"

    /** Mirrors the pager's page container: fillMaxSize Box, centered content. */
    @Composable
    private fun PagerHostedSlide(fontScale: Float) {
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(base.density, fontScale = fontScale)
        ) {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    OnboardingPage(
                        icon = Icons.Outlined.Forum,
                        title = "Chat",
                        description = "Your Hermes agent, streaming in real time.",
                    ) {
                        Text(
                            text = "Live responses with tool progress, markdown, and rich cards as the agent works.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "Switch agent profiles mid-flow — each keeps its own sessions, model, and persona.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = lastLine,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    // 480dp-tall viewport at 1.5x font scale — the hero shrinks/drops and the
    // body overflows; the page must scroll so the last line stays reachable.
    @Test
    fun compactHeight_largeFontScale_scrollsToLastLine() {
        compose.setContent { PagerHostedSlide(fontScale = 1.5f) }
        compose.onNodeWithText(lastLine).performScrollTo().assertExists()
        compose.onRoot().captureRoboImage("build/onboarding-shots/compact_480dp_font1_5.png")
    }

    // 600dp-tall viewport — the 160dp shrunk-hero tier; content should render
    // (and remain scroll-reachable) without dropping the hero entirely.
    @Test
    @Config(qualifiers = "w360dp-h600dp-xhdpi")
    fun mediumHeight_defaultFontScale_rendersShrunkHero() {
        compose.setContent { PagerHostedSlide(fontScale = 1.0f) }
        compose.onNodeWithText(lastLine).performScrollTo().assertExists()
        compose.onRoot().captureRoboImage("build/onboarding-shots/medium_600dp.png")
    }
}
