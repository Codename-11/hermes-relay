package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class PowerFeatureGateUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun requiresPairingCard_showsPairToUnlock() {
        composeTestRule.setContent {
            MaterialTheme {
                PowerFeatureGateCard(
                    title = "Terminal",
                    summary = "Open a server shell through your paired relay session.",
                    status = PowerFeatureGateStatus.RequiresPairing,
                    onPrimaryAction = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Requires pairing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pair to unlock").assertIsDisplayed()
        composeTestRule.onNodeWithText("This feature uses relay grants", substring = true).assertIsDisplayed()
    }

    @Test
    fun expiredPairingCard_showsPairAgain() {
        composeTestRule.setContent {
            MaterialTheme {
                PowerFeatureGateCard(
                    title = "Bridge",
                    summary = "Let Hermes send approved bridge commands to this phone.",
                    status = PowerFeatureGateStatus.PairingExpired,
                    onPrimaryAction = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Pairing expired").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pair again").assertIsDisplayed()
    }

    @Test
    fun dashboardSignInCard_usesDashboardLanguage() {
        composeTestRule.setContent {
            MaterialTheme {
                PowerFeatureGateCard(
                    title = "Manage",
                    summary = "Open dashboard-backed management features.",
                    status = PowerFeatureGateStatus.DashboardSignInRequired,
                    onPrimaryAction = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Dashboard sign-in required").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open sign-in").assertIsDisplayed()
    }
}
