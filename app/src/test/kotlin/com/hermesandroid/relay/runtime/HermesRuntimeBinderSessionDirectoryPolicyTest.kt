package com.hermesandroid.relay.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesRuntimeBinderSessionDirectoryPolicyTest {
    @Test
    fun `session directory can refresh from either standard route owner`() {
        assertTrue(
            shouldRefreshSessionDirectory(
                chatReady = true,
                dashboardRouteResolved = false,
            ),
        )
        assertTrue(
            shouldRefreshSessionDirectory(
                chatReady = false,
                dashboardRouteResolved = true,
            ),
        )
        assertFalse(
            shouldRefreshSessionDirectory(
                chatReady = false,
                dashboardRouteResolved = false,
            ),
        )
    }
}
