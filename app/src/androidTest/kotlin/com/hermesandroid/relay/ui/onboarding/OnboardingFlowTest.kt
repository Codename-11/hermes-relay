package com.hermesandroid.relay.ui.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented tests for the onboarding pager flow.
 *
 * These tests require an Android device or emulator because they use
 * Compose UI testing APIs and interact with real Compose components.
 */
class OnboardingFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setOnboardingContent() {
        composeTestRule.setContent {
            HermesRelayTheme {
                OnboardingScreen(
                    onComplete = { _, _, _ -> }
                )
            }
        }
    }

    // --- Page 1: Welcome ---

    @Test
    fun firstPage_showsHermesRelayTitle() {
        setOnboardingContent()

        composeTestRule
            .onNodeWithText("Hermes-Relay")
            .assertIsDisplayed()
    }

    @Test
    fun firstPage_showsWelcomeDescription() {
        setOnboardingContent()

        composeTestRule
            .onNodeWithText("Your AI agent, in your pocket. Chat, control, and connect — all from your phone.")
            .assertIsDisplayed()
    }

    // --- Skip button ---

    @Test
    fun skipButton_isAlwaysVisible_onFirstPage() {
        setOnboardingContent()

        composeTestRule
            .onNodeWithText("Skip")
            .assertIsDisplayed()
    }

    // --- Navigation: Next button ---

    @Test
    fun nextButton_isDisplayed_onFirstPage() {
        setOnboardingContent()

        composeTestRule
            .onNodeWithText("Next")
            .assertIsDisplayed()
    }

    @Test
    fun nextButton_navigatesForward_toPage2() {
        setOnboardingContent()

        // Page 1 -> Page 2
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.waitForIdle()

        // Page 2 is "Talk to Your Agent"
        composeTestRule
            .onNodeWithText("Talk to Your Agent")
            .assertIsDisplayed()
    }

    @Test
    fun canNavigateForward_throughAllPages() {
        setOnboardingContent()

        // Page 1: Hermes-Relay (Welcome)
        composeTestRule.onNodeWithText("Hermes-Relay").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.waitForIdle()

        // Page 2: Talk to Your Agent (Chat)
        composeTestRule.onNodeWithText("Talk to Your Agent").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.waitForIdle()

        // Page 3: Remote Terminal
        composeTestRule.onNodeWithText("Remote Terminal").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.waitForIdle()

        // Page 4: Device Bridge
        composeTestRule.onNodeWithText("Device Bridge").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.waitForIdle()

        // Page 5: Connect to Hermes
        composeTestRule.onNodeWithText("Connect to Hermes").assertIsDisplayed()
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.waitForIdle()

        // Page 6: Relay Server (last page)
        composeTestRule.onNodeWithText("Relay Server").assertIsDisplayed()
    }

    // --- Back button ---

    @Test
    fun backButton_hiddenOnFirstPage() {
        setOnboardingContent()

        // On page 1, Back should not exist
        composeTestRule
            .onNodeWithText("Back")
            .assertDoesNotExist()
    }

    @Test
    fun backButton_visibleOnPage2() {
        setOnboardingContent()

        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.waitForIdle()

        composeTestRule
            .onNodeWithText("Back")
            .assertIsDisplayed()
    }

    @Test
    fun backButton_navigatesBackward() {
        setOnboardingContent()

        // Go to page 2
        composeTestRule.onNodeWithText("Next").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Talk to Your Agent").assertIsDisplayed()

        // Go back to page 1
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Hermes-Relay").assertIsDisplayed()
    }

    // --- Page 5: Connect page ---

    @Test
    fun connectPage_hasApiServerUrlField() {
        setOnboardingContent()
        navigateToPage(4) // 0-indexed, page 5 is index 4

        composeTestRule
            .onNodeWithText("API Server URL")
            .assertIsDisplayed()
    }

    @Test
    fun connectPage_hasApiKeyField() {
        setOnboardingContent()
        navigateToPage(4)

        composeTestRule
            .onNodeWithText("API Key (optional)", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun connectPage_whereDoIFindThis_showsHelpDialog() {
        setOnboardingContent()
        navigateToPage(4)

        // Tap "Where do I find this?"
        composeTestRule
            .onNodeWithText("Where do I find this?")
            .performClick()
        composeTestRule.waitForIdle()

        // Dialog should show
        composeTestRule
            .onNodeWithText("Do I need an API key?")
            .assertIsDisplayed()
    }

    @Test
    fun connectPage_helpDialog_canBeDismissed() {
        setOnboardingContent()
        navigateToPage(4)

        composeTestRule.onNodeWithText("Where do I find this?").performClick()
        composeTestRule.waitForIdle()

        // Dialog is showing
        composeTestRule.onNodeWithText("Do I need an API key?").assertIsDisplayed()

        // Dismiss it
        composeTestRule.onNodeWithText("Got it").performClick()
        composeTestRule.waitForIdle()

        // Dialog should be gone
        composeTestRule
            .onNodeWithText("Do I need an API key?")
            .assertDoesNotExist()
    }

    // --- Page 6: Relay page ---

    @Test
    fun relayPage_showsOptionalMessaging() {
        setOnboardingContent()
        navigateToPage(5) // Last page

        composeTestRule
            .onNodeWithText("This is optional", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun relayPage_showsRelayUrlField() {
        setOnboardingContent()
        navigateToPage(5)

        composeTestRule
            .onNodeWithText("Relay URL (optional)")
            .assertIsDisplayed()
    }

    // --- Get Started button ---

    @Test
    fun lastPage_showsGetStartedButton() {
        setOnboardingContent()
        navigateToPage(5)

        composeTestRule
            .onNodeWithText("Get Started")
            .assertIsDisplayed()
    }

    @Test
    fun lastPage_getStartedButton_isEnabled_withDefaultUrl() {
        setOnboardingContent()
        navigateToPage(5)

        // Default URL is "http://localhost:8642" which is non-blank
        composeTestRule
            .onNodeWithText("Get Started")
            .assertIsEnabled()
    }

    // --- Skip button visibility across pages ---

    @Test
    fun skipButton_visibleOnAllPages() {
        setOnboardingContent()

        // Check skip on first page
        composeTestRule.onNodeWithText("Skip").assertIsDisplayed()

        // Navigate through all pages and check skip
        for (i in 0 until 5) {
            composeTestRule.onNodeWithText("Next").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithText("Skip").assertIsDisplayed()
        }
    }

    // --- Helper ---

    private fun navigateToPage(pageIndex: Int) {
        repeat(pageIndex) {
            composeTestRule.onNodeWithText("Next").performClick()
            composeTestRule.waitForIdle()
        }
    }
}
