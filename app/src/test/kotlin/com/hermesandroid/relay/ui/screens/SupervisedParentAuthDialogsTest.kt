package com.hermesandroid.relay.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.SupervisedParentCredentialType
import com.hermesandroid.relay.data.SupervisedParentEnrollment
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h900dp-432dpi")
class SupervisedParentAuthDialogsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `choice presents mutually exclusive pin and password routes`() {
        var selected: SupervisedParentCredentialType? = null
        compose.setContent {
            HermesRelayTheme {
                CredentialChoiceScreen("Choose parent access", "Pick one.") { selected = it }
            }
        }

        compose.onNodeWithText("Use a PIN").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(SupervisedParentCredentialType.Pin, selected) }
        compose.onNodeWithText("Use a password").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(SupervisedParentCredentialType.Password, selected) }
    }

    @Test
    fun `pin auth uses six positions and a dedicated numeric keypad`() {
        var submitted: String? = null
        compose.setContent {
            HermesRelayTheme {
                PinEntryScreen("Parent PIN", "Enter your 6-digit PIN.", false, null, { submitted = it })
            }
        }

        (0..9).forEach { compose.onNodeWithText(it.toString()).assertIsDisplayed() }
        compose.onNodeWithContentDescription("Delete digit").assertIsDisplayed()
        (1..6).forEach { compose.onNodeWithText(it.toString()).performClick() }
        compose.runOnIdle { assertEquals("123456", submitted) }
    }

    @Test
    fun `password setup uses distinct password fields and visibility controls`() {
        compose.setContent {
            HermesRelayTheme { PasswordSetupScreen(false, null, {}) }
        }

        compose.onNodeWithText("Create a parent password").assertIsDisplayed()
        compose.onNodeWithText("Password").assertIsDisplayed()
        compose.onNodeWithText("Confirm password").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Show password").assertCountEquals(2)
    }

    @Test
    fun `recovery phrase handoff exposes sharing copy and cleanup guidance`() {
        var shares = 0
        var copies = 0
        compose.setContent {
            HermesRelayTheme {
                SupervisedParentRecoveryCodeContent(
                    SupervisedParentEnrollment("maple-river-lantern-copper-sparrow-moon"),
                    onShare = { shares += 1 },
                    onCopy = { copies += 1 },
                )
            }
        }

        compose.onNodeWithText("maple-river-lantern", substring = true).assertIsDisplayed()
        compose.onNodeWithText("copper-sparrow-moon", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Share").assertIsDisplayed()
        compose.onNodeWithText("Copy phrase").assertIsDisplayed()
        compose.onNodeWithText("delete the message or saved copy", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Share").performClick()
        compose.onNodeWithText("Copy phrase").performClick()
        compose.runOnIdle {
            assertEquals(1, shares)
            assertEquals(1, copies)
        }
    }
}
