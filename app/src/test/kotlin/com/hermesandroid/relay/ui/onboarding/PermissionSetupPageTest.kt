package com.hermesandroid.relay.ui.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xxhdpi")
class PermissionSetupPageTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun missingNotifications_showsOneTapRecommendationAndOptionalReview() {
        var permissionRequests = 0
        var finishes = 0

        composeTestRule.setContent {
            HermesRelayTheme {
                PermissionSetupPage(
                    notificationAction = OnboardingNotificationAction.RequestPermission,
                    onEnableNotifications = { permissionRequests += 1 },
                    onReviewPermissions = {},
                    onFinish = { finishes += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText("Finish setup").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ready — no phone permission needed.").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Review optional permissions")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        composeTestRule
            .onNodeWithText("Enable chat alerts")
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNodeWithText("Not now")
            .performScrollTo()
            .performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, permissionRequests)
            assertEquals(1, finishes)
        }
    }

    @Test
    fun grantedNotifications_showsFinishWithoutPermissionPrompt() {
        composeTestRule.setContent {
            HermesRelayTheme {
                PermissionSetupPage(
                    notificationAction = OnboardingNotificationAction.Finish,
                    onEnableNotifications = {},
                    onReviewPermissions = {},
                    onFinish = {},
                )
            }
        }

        composeTestRule
            .onNodeWithText("Notifications are enabled for this app.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Enable chat alerts").assertDoesNotExist()
        composeTestRule.onNodeWithText("Not now").assertDoesNotExist()
        composeTestRule
            .onNode(hasText("Finish setup") and hasClickAction())
            .performScrollTo()
            .assertIsEnabled()
    }
}
