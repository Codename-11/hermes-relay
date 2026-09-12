package com.hermesandroid.relay.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesRuntimeBinderSessionDirectoryPolicyTest {
    @Test
    fun `session directory starts from dashboard publication before Gateway readiness`() {
        assertTrue(
            shouldRefreshSessionDirectory(
                chatReady = true,
                dashboardUrl = "",
            ),
        )
        assertTrue(
            shouldRefreshSessionDirectory(
                chatReady = false,
                dashboardUrl = "https://dashboard.example.test",
            ),
        )
        assertFalse(
            shouldRefreshSessionDirectory(
                chatReady = false,
                dashboardUrl = "",
            ),
        )
        assertFalse(
            shouldRefreshSessionDirectory(
                chatReady = false,
                dashboardUrl = "   ",
            ),
        )
    }
}
