package com.hermesandroid.relay.network.upstream

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hermesandroid.relay.data.KEY_GATEWAY_KEEP_ALIVE
import com.hermesandroid.relay.data.relayDataStore
import com.hermesandroid.relay.data.setGatewayKeepAlive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real ActivityManager/notification lifecycle; no Gateway or personal data. */
class GatewayKeepAliveServiceInstrumentedTest {
    @get:Rule val activity = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun setup() {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
            ).close()
        }
        runBlocking { context.setGatewayKeepAlive(false) }
    }

    @After fun cleanup() {
        instrumentation.runOnMainSync {
            GatewayKeepAliveService.stop(context)
            ActiveTurnKeepAliveRegistry.releaseAll()
        }
        await("service shutdown") { serviceState() == null }
        runBlocking { context.setGatewayKeepAlive(false) }
    }

    @Test fun immediateStopsAndOverlappingStartsSurviveThePlatformWatchdog() {
        // All changes happen before Android can dispatch onCreate/onStartCommand.
        instrumentation.runOnMainSync {
            repeat(25) {
                GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
                GatewayKeepAliveService.stop(context)
            }
            GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.Snapshot(2, 1))
        }
        await("foreground promotion") { serviceState()?.foreground == true }
        instrumentation.runOnMainSync { GatewayKeepAliveService.stop(context) }
        await("settled shutdown") { serviceState() == null }
        // Observation window, not a startup workaround: an asynchronous system
        // foreground-start crash fails the instrumentation process during it.
        CountDownLatch(1).await(12, TimeUnit.SECONDS)
        assertTrue(serviceState() == null)
    }

    @Test fun notificationDisablesAlwaysOnWhileANewerTurnStaysProtected() {
        runBlocking { context.setGatewayKeepAlive(true) }
        instrumentation.runOnMainSync {
            GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        await("persistent notification") {
            manager.activeNotifications.any { it.id == GatewayKeepAliveService.NOTIFICATION_ID }
        }
        val action = manager.activeNotifications.single {
            it.id == GatewayKeepAliveService.NOTIFICATION_ID
        }.notification.actions.single().actionIntent
        instrumentation.runOnMainSync {
            ActiveTurnKeepAliveRegistry.acquire("fixture::profile-a::session")
            GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.snapshot.value)
        }
        action.send()
        await("persisted notification action") {
            runBlocking { context.relayDataStore.data.first()[KEY_GATEWAY_KEEP_ALIVE] == false }
        }
        instrumentation.runOnMainSync {
            GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.snapshot.value)
        }
        assertTrue(serviceState()?.foreground == true)
        assertTrue(ActiveTurnKeepAliveRegistry.snapshot.value.required)
        await("active-turn notification without always-on action") {
            manager.activeNotifications.singleOrNull {
                it.id == GatewayKeepAliveService.NOTIFICATION_ID
            }?.notification?.let { it.actions.isNullOrEmpty() } == true
        }
    }

    @Suppress("DEPRECATION")
    private fun serviceState(): ActivityManager.RunningServiceInfo? =
        context.getSystemService(ActivityManager::class.java).getRunningServices(100)
            .singleOrNull { it.service.className == GatewayKeepAliveService::class.java.name }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(20)
        }
        assertTrue(description, condition())
    }
}
