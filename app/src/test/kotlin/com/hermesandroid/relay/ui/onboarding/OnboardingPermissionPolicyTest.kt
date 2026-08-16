package com.hermesandroid.relay.ui.onboarding

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingPermissionPolicyTest {

    @Test
    fun permissionSetup_followsSuccessfulConnection() {
        assertEquals(
            OnboardingPage.Permissions,
            standardOnboardingPages[standardOnboardingPages.indexOf(OnboardingPage.Connect) + 1],
        )
    }

    @Test
    fun android13WithoutNotificationGrant_requestsPermission() {
        assertEquals(
            OnboardingNotificationAction.RequestPermission,
            onboardingNotificationAction(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                notificationsPermitted = false,
            ),
        )
    }

    @Test
    fun grantedNotificationPermission_finishesSetup() {
        assertEquals(
            OnboardingNotificationAction.Finish,
            onboardingNotificationAction(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                notificationsPermitted = true,
            ),
        )
    }

    @Test
    fun preAndroid13_finishesWithoutRuntimeNotificationPrompt() {
        assertEquals(
            OnboardingNotificationAction.Finish,
            onboardingNotificationAction(
                sdkInt = Build.VERSION_CODES.S_V2,
                notificationsPermitted = false,
            ),
        )
    }
}
