package com.hermesandroid.relay.viewmodel

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DashboardManageOAuthViewModelTest {
    @Test
    fun pendingFlow_survivesRecreationWithOnlyOpaqueNonSecretFields() = runTest {
        val handle = SavedStateHandle()
        val first = DashboardManageOAuthViewModel(handle)
        first.remember("opaque-flow", "hosted", "work")
        advanceUntilIdle()

        val recreated = DashboardManageOAuthViewModel(handle)
        advanceUntilIdle()

        assertEquals(PendingMcpOAuth("opaque-flow", "hosted", "work"), recreated.pending.value)
        val persistedText = handle.keys().associateWith { key -> handle.get<Any?>(key) }.toString()
        assertFalse(persistedText.contains("authorization_url"))
        assertFalse(persistedText.contains("code="))
        assertFalse(persistedText.contains("state="))
        assertFalse(persistedText.contains("token"))
    }

    @Test
    fun clearAndUnsupportedRoute_areStableAcrossRecreation() = runTest {
        val handle = SavedStateHandle()
        val first = DashboardManageOAuthViewModel(handle)
        first.remember("opaque-flow", "hosted", null)
        first.markUnsupported("https://dashboard.example|work")
        first.clear()
        advanceUntilIdle()

        val recreated = DashboardManageOAuthViewModel(handle)
        advanceUntilIdle()

        assertNull(recreated.pending.value)
        assertTrue("https://dashboard.example|work" in recreated.unsupportedRoutes.value)
    }
}
