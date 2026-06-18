package com.hermesandroid.relay.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Rule
import org.junit.Test

class PermissionsStatusScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun permissionsScreen_showsStandardAndOnDemandRows() {
        composeTestRule.setContent {
            HermesRelayTheme {
                PermissionsStatusScreen(
                    onBack = {},
                    onOpenBridge = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText("Permissions and capabilities")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Chat and Manage")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("No Android runtime permission needed. API/dashboard auth is configured separately.")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Camera")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Microphone")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
