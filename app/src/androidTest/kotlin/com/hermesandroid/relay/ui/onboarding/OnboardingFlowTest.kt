package com.hermesandroid.relay.ui.onboarding

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import org.junit.Rule
import org.junit.Test

/** Standard setup stays separate from Direct API and optional Relay grants. */
class OnboardingFlowTest {
    @get:Rule val compose = createComposeRule()

    private fun start() {
        val model = ConnectionViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent { HermesRelayTheme { OnboardingScreen(model, onComplete = {}) } }
    }

    private fun connectPage() {
        compose.onNodeWithText("Get started").performClick()
        repeat(3) { compose.onNodeWithText("Next").performClick(); compose.waitForIdle() }
    }

    @Test fun welcomeOffersStartAndDemo() {
        start()
        compose.onNodeWithText("Hermes,\nin your pocket").assertIsDisplayed()
        compose.onNodeWithText("Get started").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Try the demo").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Back").assertDoesNotExist()
    }

    @Test fun introNavigationAndSkipRemainAvailable() {
        start()
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Chat").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Get started").assertIsDisplayed()
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Skip").performClick()
        compose.onNodeWithText("Skip setup?").assertIsDisplayed()
        compose.onNodeWithText("Go back").performClick()
        compose.onNodeWithText("Chat").assertIsDisplayed()
    }

    @Test fun standardMethodsDoNotAskForApiCredentials() {
        start(); connectPage()
        compose.onNodeWithText("Hermes nearby").assertIsDisplayed()
        compose.onNodeWithText("Remote gateway").assertIsDisplayed().performClick()
        compose.onNodeWithText("Hermes address").assertIsDisplayed()
        compose.onNodeWithText("API key").assertDoesNotExist()
        compose.onNodeWithText("Find Hermes").performScrollTo().assertIsDisplayed()
    }

    @Test fun publicHttpConsentResetsWhenAddressChanges() {
        start(); connectPage()
        compose.onNodeWithText("Remote gateway").performClick()
        compose.onNodeWithText("Hermes address").performTextReplacement("http://11.0.0.1:9119")
        compose.onNodeWithText("Find Hermes").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("I accept the risk and allow HTTP for this address").performScrollTo().performClick()
        compose.onNodeWithText("Find Hermes").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Hermes address").performScrollTo().performTextReplacement("http://11.0.0.1:9120")
        compose.onNodeWithText("Find Hermes").performScrollTo().assertIsNotEnabled()
    }

    @Test fun advancedKeepsApiAndRelaySeparate() {
        start(); connectPage()
        compose.onNodeWithText("Advanced").performScrollTo().performClick()
        compose.onNodeWithText("API-only connection").assertIsDisplayed()
        compose.onNodeWithText("Pair Relay by code").performScrollTo().assertIsDisplayed()
    }

    @Test fun setupSkipIsScrollReachable() {
        start(); connectPage()
        compose.onNodeWithText("Skip for now — set up later in Settings").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Skip setup?").assertIsDisplayed()
    }

    @Test fun hostedGatewayKeepsItsSeparateAddressEntry() {
        start(); connectPage()
        compose.onNodeWithText("Nous-hosted Hermes").performClick()
        compose.onNodeWithText("Connect to Nous-hosted Hermes").assertIsDisplayed()
        compose.onNodeWithText("Find Hermes").performScrollTo().assertIsNotEnabled()
    }

    @Test fun optionalPowerPermissionsRemainReachable() {
        start()
        compose.onNodeWithText("Get started").performClick()
        repeat(2) { compose.onNodeWithText("Next").performClick(); compose.waitForIdle() }
        compose.onNodeWithText("Review permissions").performScrollTo().assertIsDisplayed().assertIsEnabled()
    }
}
