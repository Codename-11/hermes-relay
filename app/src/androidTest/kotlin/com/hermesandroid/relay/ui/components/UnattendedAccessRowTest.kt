package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented tests for the v0.4.1 [UnattendedAccessRow] `masterEnabled`
 * gate. When the master Agent Control switch is off, the unattended-access
 * Switch must be disabled and the subtitle must advise the user to enable
 * the master switch first — otherwise they'd flip unattended on and see
 * nothing happen (the wake-lock acquire path short-circuits on master-off
 * regardless of this flag).
 */
class UnattendedAccessRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun masterDisabled_switchIsDisabled() {
        composeTestRule.setContent {
            MaterialTheme {
                UnattendedAccessRow(
                    enabled = false,
                    warningSeen = true,
                    credentialLockDetected = false,
                    onToggle = {},
                    onWarningSeen = {},
                    masterEnabled = false,
                )
            }
        }

        composeTestRule.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test
    fun masterDisabled_subtitleExplainsWhy() {
        composeTestRule.setContent {
            MaterialTheme {
                UnattendedAccessRow(
                    enabled = false,
                    warningSeen = true,
                    credentialLockDetected = false,
                    onToggle = {},
                    onWarningSeen = {},
                    masterEnabled = false,
                )
            }
        }

        composeTestRule
            .onNodeWithText("Requires Agent Control", substring = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("enable the master switch above first", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun masterEnabled_switchIsInteractive() {
        // Regression: don't accidentally disable the Switch in the common path.
        composeTestRule.setContent {
            MaterialTheme {
                UnattendedAccessRow(
                    enabled = false,
                    warningSeen = true,
                    credentialLockDetected = false,
                    onToggle = {},
                    onWarningSeen = {},
                    masterEnabled = true,
                )
            }
        }

        composeTestRule.onNode(isToggleable()).assertIsEnabled()
    }

    @Test
    fun masterEnabled_offSubtitle_doesNotMentionMasterRequirement() {
        composeTestRule.setContent {
            MaterialTheme {
                UnattendedAccessRow(
                    enabled = false,
                    warningSeen = true,
                    credentialLockDetected = false,
                    onToggle = {},
                    onWarningSeen = {},
                    masterEnabled = true,
                )
            }
        }

        // The off-but-master-on subtitle is the "actions only land when the
        // screen is already on" copy, NOT the master-gated one.
        composeTestRule
            .onNodeWithText("bridge actions only land", substring = true)
            .assertIsDisplayed()
    }
}
