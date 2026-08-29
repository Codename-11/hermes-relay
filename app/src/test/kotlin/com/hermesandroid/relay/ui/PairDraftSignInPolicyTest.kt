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
}
