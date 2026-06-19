package com.hermesandroid.relay.ui.onboarding

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented tests for the Standard-first onboarding pager.
 */
class OnboardingFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setOnboardingContent() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val connectionViewModel = ConnectionViewModel(app)
        composeTestRule.setContent {
            HermesRelayTheme {
                OnboardingScreen(
                    connectionViewModel = connectionViewModel,
                    onComplete = {},
                )
            }
        }
    }

    @Test
    fun firstPage_showsHermesForAndroidTitle() {
        setOnboardingContent()

        composeTestRule
            .onNodeWithText("Hermes-Relay for Android")
            .assertIsDisplayed()
    }

    @Test
    fun firstPage_showsStandardFirstDescription() {
        setOnboardingContent()

        composeTestRule
            .onNodeWithText("Chat with Hermes and manage your dashboard from your phone.")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Standard")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Advanced")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Setup Guide")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Hermes Docs")
            .assertIsDisplayed()
    }

    @Test
    fun nextButton_navigatesForward_toChatPage() {
        setOnboardingContent()

        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithText("Chat")
            .assertIsDisplayed()
    }

    @Test
    fun canNavigateForward_throughStandardAndPowerPages() {
        setOnboardingContent()

        composeTestRule.onNodeWithText("Hermes-Relay for Android").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Chat").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Manage").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Power tools").assertIsDisplayed()
        composeTestRule.onNodeWithText("Connect").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Connect to Hermes").assertIsDisplayed()
    }

    @Test
    fun backButton_hiddenOnFirstPage() {
        setOnboardingContent()

        composeTestRule
            .onNodeWithText("Back")
            .assertDoesNotExist()
    }

    @Test
    fun backButton_navigatesBackward() {
        setOnboardingContent()

        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Chat").assertIsDisplayed()

        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hermes-Relay for Android").assertIsDisplayed()
    }

    @Test
    fun connectPage_showsStandardChoiceFirst() {
        setOnboardingContent()
        navigateToPage(4)

        composeTestRule
            .onNodeWithText("Vanilla Hermes")
            .assertIsDisplayed()
    }

    @Test
    fun standardSetup_showsApiFields() {
        setOnboardingContent()
        navigateToPage(4)

        composeTestRule.onNodeWithText("Vanilla Hermes").performClick()
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithText("API server URL")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("API key")
            .assertIsDisplayed()
    }

    @Test
    fun standardSetup_connectButton_isEnabled_withDefaultUrl() {
        setOnboardingContent()
        navigateToPage(4)

        composeTestRule.onNodeWithText("Vanilla Hermes").performClick()
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithText("Connect")
            .assertIsEnabled()
    }

    @Test
    fun connectPage_keepsPairingOptional() {
        setOnboardingContent()
        navigateToPage(4)

        composeTestRule
            .onNodeWithText("Pair Relay by code")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Power-user path for Terminal, Bridge, Relay sessions, and grants")
            .assertIsDisplayed()
    }

    @Test
    fun powerPage_linksToPermissionReview() {
        setOnboardingContent()
        navigateToPage(3)

        composeTestRule
            .onNodeWithText("Review permissions")
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun skipButton_visibleOnIntroPages_andWizardSkipOnConnectPage() {
        setOnboardingContent()

        repeat(4) {
            composeTestRule.onNodeWithText("Skip").assertIsDisplayed()
            composeTestRule.onNodeWithText(if (it == 3) "Connect" else "Next").performClick()
            composeTestRule.waitForIdle()
        }

        composeTestRule
            .onNodeWithText("Skip for now — set up later in Settings")
            .assertIsDisplayed()
    }

    private fun navigateToPage(pageIndex: Int) {
        repeat(pageIndex) {
            composeTestRule.onNodeWithText(if (it == 3) "Connect" else "Next").performClick()
            composeTestRule.waitForIdle()
        }
    }
}
