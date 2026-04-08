package com.hermesandroid.relay.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.material3.MaterialTheme
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented tests for Terminal and Bridge empty state screens.
 */
class EmptyStateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // --- Terminal Screen ---

    @Test
    fun terminalScreen_showsTitle() {
        composeTestRule.setContent {
            MaterialTheme {
                TerminalScreen()
            }
        }

        composeTestRule
            .onNodeWithText("Remote Terminal")
            .assertIsDisplayed()
    }

    @Test
    fun terminalScreen_showsPhase2Chip() {
        composeTestRule.setContent {
            MaterialTheme {
                TerminalScreen()
            }
        }

        composeTestRule
            .onNodeWithText("Coming in Phase 2")
            .assertIsDisplayed()
    }

    @Test
    fun terminalScreen_showsDescription() {
        composeTestRule.setContent {
            MaterialTheme {
                TerminalScreen()
            }
        }

        composeTestRule
            .onNodeWithText("Secure shell access", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun terminalScreen_showsTopBarTitle() {
        composeTestRule.setContent {
            MaterialTheme {
                TerminalScreen()
            }
        }

        composeTestRule
            .onNodeWithText("Terminal")
            .assertIsDisplayed()
    }

    @Test
    fun terminalScreen_showsPlannedFeatures() {
        composeTestRule.setContent {
            MaterialTheme {
                TerminalScreen()
            }
        }

        composeTestRule
            .onNodeWithText("Full ANSI terminal emulator", substring = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("tmux session management", substring = true)
            .assertIsDisplayed()
    }

    // --- Bridge Screen ---

    @Test
    fun bridgeScreen_showsTitle() {
        composeTestRule.setContent {
            MaterialTheme {
                BridgeScreen()
            }
        }

        composeTestRule
            .onNodeWithText("Device Bridge")
            .assertIsDisplayed()
    }

    @Test
    fun bridgeScreen_showsPhase3Chip() {
        composeTestRule.setContent {
            MaterialTheme {
                BridgeScreen()
            }
        }

        composeTestRule
            .onNodeWithText("Coming in Phase 3")
            .assertIsDisplayed()
    }

    @Test
    fun bridgeScreen_showsDescription() {
        composeTestRule.setContent {
            MaterialTheme {
                BridgeScreen()
            }
        }

        composeTestRule
            .onNodeWithText("Let your Hermes agent interact with your phone", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun bridgeScreen_showsTopBarTitle() {
        composeTestRule.setContent {
            MaterialTheme {
                BridgeScreen()
            }
        }

        composeTestRule
            .onNodeWithText("Bridge")
            .assertIsDisplayed()
    }

    @Test
    fun bridgeScreen_showsPlannedFeatures() {
        composeTestRule.setContent {
            MaterialTheme {
                BridgeScreen()
            }
        }

        composeTestRule
            .onNodeWithText("Agent-controlled device interaction", substring = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Permission management", substring = true)
            .assertIsDisplayed()
    }
}
