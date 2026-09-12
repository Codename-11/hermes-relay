package com.hermesandroid.relay.voice

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.VoiceUiState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h720dp-xhdpi", sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceOverlayPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingPermissionKeepsStartDisabled() = setupDialog(false, 1f)
    @Test fun grantedPermissionsStillRequireStart() = setupDialog(true, 1f)
    @Test fun largeTextPermissionSetup() = setupDialog(true, 1.5f)

    private fun setupDialog(granted: Boolean, scale: Float) {
        org.robolectric.RuntimeEnvironment.setFontScale(scale)
        compose.waitForIdle()
        var starts = 0
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    VoiceOverlaySetupContent(VoiceOverlayAccess(true, granted, granted, true), false,
                        {}, { starts++ }, {}, {}, {})
                }
            }
        }
        val start = compose.onNodeWithText("Start voice overlay")
        start.assertIsDisplayed()
        assertEquals(0, starts)
        if (granted) start.assertIsEnabled() else start.assertIsNotEnabled()
        capture("setup-$granted-$scale")
        compose.onNodeWithText("Display over other apps", substring = true).performScrollTo().assertIsDisplayed()
        if (scale > 1f) capture("setup-large-text-scrolled")
        if (granted) {
            start.performClick()
            compose.runOnIdle { assertEquals(1, starts) }
        }
    }

    @Test fun stopRemainsReachableWhenMinimized() = overlay(1f)

    @Test
    @Config(qualifiers = "w320dp-h480dp-xhdpi")
    fun narrowOverlayWithLargeText() = overlay(1.5f)

    private fun overlay(scale: Float) {
        org.robolectric.RuntimeEnvironment.setFontScale(scale)
        compose.waitForIdle()
        var exits = 0
        val session = VoiceOverlaySession(MutableStateFlow(VoiceUiState(voiceMode = true)),
            provider = null, model = null, voice = null, profileName = "Research", configScope = null,
            outputEnabled = true, fallbackEnabled = false, onStartListening = {}, onStopListening = {},
            onInterrupt = {}, onPauseAutoMode = {}, onReturnToHermes = {}, onDismissOverlay = {},
            onExit = { exits++ }, connectionLabel = "Home server")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                Box(Modifier.fillMaxSize()) {
                    VoiceFloatingOverlayPill(session, { _, _ -> })
                }
            }
        }
        compose.onNodeWithContentDescription("Stop voice").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("overlay-compact-$scale")
        compose.onNodeWithContentDescription("Expand voice controls")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(500)
        capture("overlay-expanded-$scale")
        compose.onNodeWithText("Minimize").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Stop voice").assertIsDisplayed()
        capture("overlay-minimized-$scale")
        compose.onNodeWithText("Stop voice")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(1, exits) }
    }

    private fun capture(name: String) {
        val file = File("build/ui-evidence/play-voice-$name.png")
        file.parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(file.absolutePath)
    }
}
