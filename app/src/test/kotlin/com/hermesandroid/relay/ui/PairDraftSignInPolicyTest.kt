package com.hermesandroid.relay.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairDraftSignInPolicyTest {
    @Test
    fun `add gateway draft commits before dashboard sign in`() {
        assertTrue(shouldCommitPairDraftBeforeDashboardSignIn("draft", "draft"))
        assertFalse(shouldCommitPairDraftBeforeDashboardSignIn("saved", null))
        assertFalse(shouldCommitPairDraftBeforeDashboardSignIn(null, "draft"))
        assertFalse(shouldCommitPairDraftBeforeDashboardSignIn("other", "draft"))
    }

    @Test
    fun `pair-origin dashboard sign in resumes deferred relay pairing`() {
        assertTrue(shouldResumePairingAfterDashboardAuthentication(Screen.DashboardSignIn.SOURCE_PAIR))
        assertFalse(shouldResumePairingAfterDashboardAuthentication(Screen.DashboardSignIn.SOURCE_GENERAL))
        assertTrue(shouldResumePairingAfterDashboardAuthentication(Screen.DashboardSignIn.SOURCE_ONBOARDING))
    }
}
