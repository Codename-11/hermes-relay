package com.hermesandroid.relay.screenshots

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.ui.screens.SettingsScreen
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.ui.theme.LayoutStyle
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
class SettingsLandingScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun groupedLandingFoldsRelayToolsUntilRelayIsSetUp() {
        var terminalOpens = 0
        val vm = ConnectionViewModel(ApplicationProvider.getApplicationContext<Application>())
        val chatVm = ChatViewModel()
        compose.setContent {
            HermesRelayTheme(appThemeId = "clean", themePreference = "light", layoutStyleId = LayoutStyle.CLEAN.id) {
                SettingsScreen(
                    connectionViewModel = vm,
                    onBack = {},
                    chatViewModel = chatVm,
                    gitRepoScanningEnabled = false,
                    onNavigateToConnections = {},
                    onNavigateToManage = {},
                    onNavigateToProviderUsage = {},
                    onNavigateToPlugins = {},
                    onNavigateToGitWorkspace = {},
                    onNavigateToChatSettings = {},
                    onNavigateToTerminal = { terminalOpens++ },
                    onNavigateToBridge = {},
                    onNavigateToMediaSettings = {},
                    onNavigateToAppearanceSettings = {},
                    onNavigateToAnalytics = {},
                    onNavigateToDiagnostics = {},
                    onNavigateToVoiceSettings = {},
                    onNavigateToNotificationCompanion = {},
                    onNavigateToProactiveSettings = {},
                    onNavigateToPermissions = {},
                    onNavigateToPairedDevices = {},
                    onNavigateToDeveloperSettings = {},
                    onNavigateToAbout = {},
                    onNavigateToProfileInspector = {},
                )
            }
        }
        compose.onNodeWithText("Assistant").assertIsDisplayed()
        compose.onNodeWithText("Voice").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/ui-regression/settings-landing-top.png")
        compose.onNodeWithText("Manage").performScrollTo()
        compose.onNodeWithText("Bridge safety").assertDoesNotExist()
        compose.onNodeWithText("Terminal").assertDoesNotExist()
        compose.onNodeWithText("Relay tools").performScrollTo().performClick()
        compose.onNodeWithText("Terminal").performScrollTo().performClick()
        compose.runOnIdle { check(terminalOpens == 1) }
        compose.onNodeWithText("Help & info").performScrollTo()
        compose.onNodeWithText("About").performScrollTo().assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/ui-regression/settings-landing-bottom.png")
    }
}
