package com.hermesandroid.relay.viewmodel

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.ConnectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ConnectionViewModelColdStartTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `API fallback stays absent before persisted connection hydration`() {
        val viewModel = ConnectionViewModel(application)

        assertEquals("", viewModel.apiServerUrl.value)
        assertEquals("", viewModel.effectiveApiServerUrl.value)
        assertNull(viewModel.apiClient.value)
    }

    @Test
    fun `preallocated add never reuses a different placeholder id`() {
        val stale = Connection(
            id = "stale-placeholder",
            label = ConnectionViewModel.PLACEHOLDER_LABEL,
            apiServerUrl = "",
            relayUrl = "",
            tokenStoreKey = Connection.buildTokenStoreKey("stale-placeholder"),
        )
        assertNull(
            reusablePlaceholderForAdd(
                preAllocatedId = "route-placeholder",
                connections = listOf(stale),
            ),
        )
        assertEquals(
            stale,
            reusablePlaceholderForAdd(
                preAllocatedId = null,
                connections = listOf(stale),
            ),
        )
    }

    @Test
    fun `cold start orphan sweep observes persisted placeholders after hydration`() {
        val seedStore = ConnectionStore(application)
        awaitWithMainLooper { seedStore.isHydrated.first { it } }
        awaitWithMainLooper {
            seedStore.addConnection(
                Connection(
                    id = "persisted-orphan",
                    label = ConnectionViewModel.PLACEHOLDER_LABEL,
                    apiServerUrl = "",
                    relayUrl = "",
                    tokenStoreKey = Connection.buildTokenStoreKey("persisted-orphan"),
                ),
            )
        }

        val viewModel = ConnectionViewModel(application)
        awaitWithMainLooper { viewModel.connectionStore.isHydrated.first { it } }
        val deadline = System.currentTimeMillis() + 5_000L
        while (
            viewModel.connectionStore.connections.value.any { it.id == "persisted-orphan" } &&
            System.currentTimeMillis() < deadline
        ) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        val afterSweep = viewModel.connectionStore.connections.value

        assertTrue(afterSweep.none { it.id == "persisted-orphan" })
    }

    private fun <T> awaitWithMainLooper(block: suspend () -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val deferred = scope.async { block() }
        val deadline = System.currentTimeMillis() + 5_000L
        while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        check(deferred.isCompleted) { "Suspend test operation did not finish within 5 seconds" }
        return try {
            runBlocking { deferred.await() }
        } finally {
            scope.cancel()
        }
    }
}
