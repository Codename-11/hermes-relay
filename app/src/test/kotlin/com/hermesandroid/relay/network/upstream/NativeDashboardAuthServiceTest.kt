package com.hermesandroid.relay.network.upstream

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31, 35])
@OptIn(ExperimentalCoroutinesApi::class)
class NativeDashboardAuthServiceTest {
    private val context = mockk<Context>(relaxed = true)
    private val connection = slot<ServiceConnection>()
    private val component = ComponentName("test", NativeDashboardAuthService::class.java.name)
    private lateinit var service: NativeDashboardAuthService

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { context.applicationContext } returns context
        every { context.bindService(any(), capture(connection), Context.BIND_AUTO_CREATE) } returns true
        service = Robolectric.buildService(NativeDashboardAuthService::class.java).create().get()
    }

    @After fun cleanup() {
        service.onDestroy()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun connect() = connection.captured.onServiceConnected(component, service.onBind(Intent()))

    @Test fun browserWaitsForForegroundPromotionAndCompletionReleasesBinding() = runTest {
        var browserOpened = false
        val result = async(Dispatchers.Main.immediate) {
            withNativeDashboardAuthForeground(context) {
                assertEquals(NativeDashboardAuthService::class.java.name,
                    shadowOf(service).nextStartedService.component?.className)
                assertNotNull(shadowOf(service).lastForegroundNotification)
                browserOpened = true
                "done"
            }
        }
        assertFalse(browserOpened)
        connect()
        assertEquals("done", result.await())
        verify(exactly = 1) { context.unbindService(connection.captured) }
    }

    @Test fun cancellationBeforeConnectionDoesNotPromoteOrOpenBrowser() = runTest {
        var browserOpened = false
        val result = async(Dispatchers.Main.immediate) {
            withNativeDashboardAuthForeground(context) { browserOpened = true }
        }
        result.cancel()
        result.join()
        connect()
        assertFalse(browserOpened)
        assertNull(shadowOf(service).lastForegroundNotification)
        verify(exactly = 1) { context.unbindService(connection.captured) }
        verify(exactly = 0) { context.startForegroundService(any()) }
    }

    @Test fun browserFailureStillReleasesBinding() = runTest {
        val result = async(Dispatchers.Main.immediate) {
            runCatching { withNativeDashboardAuthForeground(context) { error("no browser") } }
        }
        connect()
        assertTrue(result.await().isFailure)
        verify(exactly = 1) { context.unbindService(connection.captured) }
    }

    @Test fun serviceLossCancelsTheCallbackOwnerAndReleasesBinding() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val result = async(Dispatchers.Main.immediate) {
            withNativeDashboardAuthForeground(context) { waiting.await() }
        }
        connect()
        connection.captured.onServiceDisconnected(component)
        result.join()
        assertTrue(result.isCancelled)
        verify(exactly = 1) { context.unbindService(connection.captured) }
    }

    @Test fun refusedBindingNeverOpensBrowser() = runTest {
        every { context.bindService(any(), any<ServiceConnection>(), any<Int>()) } returns false
        var browserOpened = false
        val result = runCatching {
            withNativeDashboardAuthForeground(context) { browserOpened = true }
        }
        assertTrue(result.isFailure)
        assertFalse(browserOpened)
        verify(exactly = 1) { context.unbindService(any()) }
    }

    @Test fun bindingTimeoutReleasesConnectionAndNeverOpensBrowser() = runTest {
        var browserOpened = false
        val result = async(Dispatchers.Main.immediate) {
            runCatching { withNativeDashboardAuthForeground(context) { browserOpened = true } }
        }
        testScheduler.advanceUntilIdle()
        assertTrue(result.await().exceptionOrNull() is java.io.IOException)
        assertFalse(browserOpened)
        verify(exactly = 1) { context.unbindService(connection.captured) }
    }

    @Test fun invalidServiceCannotLaunchAnUnprotectedBrowser() = runTest {
        var browserOpened = false
        val result = async(Dispatchers.Main.immediate) {
            runCatching { withNativeDashboardAuthForeground(context) { browserOpened = true } }
        }
        connection.captured.onServiceConnected(component, android.os.Binder())
        assertTrue(result.await().isFailure)
        assertFalse(browserOpened)
        verify(exactly = 1) { context.unbindService(connection.captured) }
    }

    @Test fun lastBindingStopsServiceAndItNeverRequestsARestart() {
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        service.onUnbind(Intent())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
