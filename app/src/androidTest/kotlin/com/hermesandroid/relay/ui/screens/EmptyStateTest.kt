package com.hermesandroid.relay.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.TerminalViewModel
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented smoke tests for the current Terminal and Bridge surfaces.
 */
class EmptyStateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun terminalScreen_showsCurrentTopBar() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val terminalViewModel = TerminalViewModel(app)
        val connectionViewModel = ConnectionViewModel(app)

        composeTestRule.setContent {
            MaterialTheme {
                TerminalScreen(
                    terminalViewModel = terminalViewModel,
                    connectionViewModel = connectionViewModel,
                )
            }
        }

        composeTestRule.onNodeWithText("Terminal").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Search scrollback").assertIsDisplayed()
    }

    @Test
    fun bridgeScreen_showsCurrentTopBar() {
        composeTestRule.setContent {
            MaterialTheme {
                BridgeScreen()
            }
        }

        composeTestRule.onNodeWithText("Bridge").assertIsDisplayed()
    }
}
