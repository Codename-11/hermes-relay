package com.hermesandroid.relay.network.upstream

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.app.NotificationManager
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.hermesandroid.relay.data.KEY_GATEWAY_KEEP_ALIVE
import com.hermesandroid.relay.data.relayDataStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31, 35])
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayKeepAliveServiceTest {
    private val context = mockk<Context>(relaxed = true)
    private val start = slot<Intent>()
    private var service: GatewayKeepAliveService? = null
    private val preferences = TestPreferences()

    init {
        every { context.applicationContext } returns context
        every { context.startForegroundService(capture(start)) } returns
            ComponentName("test", GatewayKeepAliveService::class.java.name)
    }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        GatewayKeepAliveService.resetForTest()
        mockkStatic("com.hermesandroid.relay.data.DataStoreProviderKt")
        every { any<Context>().relayDataStore } returns preferences
    }

    @After fun cleanup() {
        preferences.gate?.complete(Unit)
        service?.onDestroy()
        ActiveTurnKeepAliveRegistry.resetForTest()
        GatewayKeepAliveService.resetForTest()
        unmockkAll()
        Dispatchers.resetMain()
    }

    private fun create(): GatewayKeepAliveService =
        Robolectric.buildService(GatewayKeepAliveService::class.java).create().get()
            .also { service = it }

    @Test fun immediateStopWaitsForForegroundPromotion() {
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
        GatewayKeepAliveService.stop(context)
        verify(exactly = 0) { context.stopService(any()) }
        val instance = create()
        assertNotNull(shadowOf(instance).lastForegroundNotification)
        instance.onStartCommand(start.captured, 0, 1)
        assertTrue(shadowOf(instance).isStoppedBySelf)
    }

    @Test fun creationPromotesBeforePublishingTheInstance() {
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
        assertNotNull(shadowOf(create()).lastForegroundNotification)
    }

    @Test fun queuedStartCannotOverwriteNewerDemand() {
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
        val oldStart = start.captured
        val instance = create()
        GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.Snapshot(2, 1))
        instance.onStartCommand(oldStart, 0, 1)
        val notification = shadowOf(instance).lastForegroundNotification!!
        assertEquals("Hermes is waiting for input", notification.extras.getString("android.title"))
        assertTrue(notification.actions.isNullOrEmpty())
    }

    @Test fun overlappingStartsAreCoalescedAndLatestDemandWins() {
        GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.Snapshot(1))
        GatewayKeepAliveService.stop(context)
        GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.Snapshot(2))
        verify(exactly = 1) { context.startForegroundService(any()) }
        val instance = create()
        instance.onStartCommand(start.captured, 0, 1)
        assertFalse(shadowOf(instance).isStoppedBySelf)
        assertEquals("Hermes is finishing 2 turns", notificationTitle(instance))
    }

    @Test fun siblingSettlementKeepsTheRemainingSessionProtected() {
        ActiveTurnKeepAliveRegistry.acquire("connection::profile-a::session")
        ActiveTurnKeepAliveRegistry.acquire("connection::profile-b::session")
        GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.snapshot.value)
        val instance = create()
        instance.onStartCommand(start.captured, 0, 1)
        ActiveTurnKeepAliveRegistry.release("connection::profile-a::session")
        GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.snapshot.value)
        assertEquals("Hermes is finishing a turn", notificationTitle(instance))
        assertFalse(shadowOf(instance).isStoppedBySelf)
        ActiveTurnKeepAliveRegistry.release("connection::profile-b::session")
        GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.snapshot.value)
        assertTrue(shadowOf(instance).isStoppedBySelf)
    }

    @Test fun retiringInstanceIsNotReusedAndItsDestructionCannotClearReplacement() {
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
        val old = create()
        old.onStartCommand(start.captured, 0, 1)
        GatewayKeepAliveService.stop(context)
        GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.Snapshot(1))
        verify(exactly = 2) { context.startForegroundService(any()) }
        val replacement = create()
        replacement.onStartCommand(start.captured, 0, 2)
        old.onDestroy()
        old.onConfigurationChanged(Configuration())
        GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.Snapshot(2))
        assertEquals("Hermes is finishing 2 turns", notificationTitle(replacement))
        verify(exactly = 2) { context.startForegroundService(any()) }
    }

    @Test fun androidCanDeliverANewStartToTheRetiringInstance() {
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
        val instance = create()
        instance.onStartCommand(start.captured, 0, 1)
        GatewayKeepAliveService.stop(context)
        GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.Snapshot(1))
        instance.onStartCommand(start.captured, 0, 2)
        GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.Snapshot(2))
        assertEquals("Hermes is finishing 2 turns", notificationTitle(instance))
        verify(exactly = 2) { context.startForegroundService(any()) }
    }

    @Test fun backgroundDemandWaitsForVisibilityAndExistingProtectionSurvivesBackgrounding() {
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot(), false)
        verify(exactly = 0) { context.startForegroundService(any()) }
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot(), true)
        val instance = create()
        instance.onStartCommand(start.captured, 0, 1)
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot(1), false)
        assertEquals("Hermes is finishing a turn", notificationTitle(instance))
        assertFalse(shadowOf(instance).isStoppedBySelf)
    }

    @Test fun rejectedLaunchCanRetryOnNextForegroundWithoutReleasingTurnOwnership() {
        ActiveTurnKeepAliveRegistry.acquire("connection::profile::session")
        every { context.startForegroundService(any()) } throws IllegalStateException("background start")
        GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.snapshot.value)
        assertTrue(ActiveTurnKeepAliveRegistry.snapshot.value.required)
        every { context.startForegroundService(capture(start)) } returns
            ComponentName("test", GatewayKeepAliveService::class.java.name)
        GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.snapshot.value, false)
        GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.snapshot.value, true)
        val instance = create()
        instance.onStartCommand(start.captured, 0, 1)
        assertEquals("Hermes is finishing a turn", notificationTitle(instance))
    }

    @Test fun promotionFailureRetiresTheInstanceAndAllowsAFreshLaunch() {
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
        val instance = spyk(create()).also { service = it }
        if (Build.VERSION.SDK_INT >= 34) {
            every { instance.startForeground(any(), any(), any()) } throws SecurityException("denied")
        } else {
            every { instance.startForeground(any(), any()) } throws SecurityException("denied")
        }
        instance.onStartCommand(start.captured, 0, 1)
        verify { instance.stopSelf() }
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
        verify(exactly = 2) { context.startForegroundService(any()) }
    }

    @Test fun channelFailureIsContainedBeforePublishingAnOwner() {
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
        val instance = spyk(Robolectric.buildService(GatewayKeepAliveService::class.java).get())
            .also { service = it }
        every { instance.getSystemService(NotificationManager::class.java) } throws IllegalStateException("channel unavailable")
        instance.onCreate()
        verify { instance.stopSelf() }
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
        verify(exactly = 2) { context.startForegroundService(any()) }
    }

    @Test fun taskRemovalPreservesLeasesAndDoesNotRestartUntilTheNextVisibleLifecycle() {
        ActiveTurnKeepAliveRegistry.acquire("connection::profile::session")
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.snapshot.value)
        val instance = create()
        instance.onStartCommand(start.captured, 0, 1)
        instance.onTaskRemoved(null)
        assertTrue(shadowOf(instance).isStoppedBySelf)
        assertTrue(ActiveTurnKeepAliveRegistry.snapshot.value.required)
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.snapshot.value)
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.snapshot.value, false)
        verify(exactly = 1) { context.startForegroundService(any()) }
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.snapshot.value, true)
        verify(exactly = 2) { context.startForegroundService(any()) }
    }

    @Test fun taskRemovalDuringStartupStillAcknowledgesPromotion() {
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
        val instance = create()
        instance.onTaskRemoved(null)
        assertFalse(shadowOf(instance).isStoppedBySelf)
        instance.onStartCommand(start.captured, 0, 1)
        assertNotNull(shadowOf(instance).lastForegroundNotification)
        assertTrue(shadowOf(instance).isStoppedBySelf)
    }

    @Test fun processLossDoesNotReplayOldDemandButStillPromotesADeliveredStart() {
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
        val oldStart = start.captured
        GatewayKeepAliveService.resetForTest()
        val instance = create()
        assertNull(shadowOf(instance).lastForegroundNotification)
        instance.onStartCommand(oldStart, 0, 1)
        assertNotNull(shadowOf(instance).lastForegroundNotification)
        assertTrue(shadowOf(instance).isStoppedBySelf)
    }

    @Test fun staleStartCannotAcknowledgeANewerPendingStart() {
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
        val oldStart = start.captured
        GatewayKeepAliveService.resetForTest()
        GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.Snapshot(1))
        val newStart = start.captured
        val instance = create()
        instance.onStartCommand(oldStart, 0, 1)
        GatewayKeepAliveService.stop(context)
        assertFalse(shadowOf(instance).isStoppedBySelf)
        instance.onStartCommand(newStart, 0, 2)
        assertTrue(shadowOf(instance).isStoppedBySelf)
    }

    @Test fun notificationOnlyDisablesIdleRetentionAndUsesLatestTurnDemand() {
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
        val instance = create()
        instance.onStartCommand(start.captured, 0, 1)
        val action = stopAction(instance)
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot(2, 1))
        instance.onStartCommand(action, 0, 2)
        assertEquals(false, preferences.data.value[KEY_GATEWAY_KEEP_ALIVE])
        assertFalse(shadowOf(instance).isStoppedBySelf)
        GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.Snapshot(2, 1))
        assertEquals("Hermes is waiting for input", notificationTitle(instance))
        assertTrue(shadowOf(instance).lastForegroundNotification!!.actions.isNullOrEmpty())
    }

    @Test fun notificationWriteSurvivesServiceDestruction() {
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
        val instance = create()
        instance.onStartCommand(start.captured, 0, 1)
        preferences.gate = CompletableDeferred()
        instance.onStartCommand(stopAction(instance), 0, 2)
        instance.onTaskRemoved(null)
        instance.onDestroy()
        preferences.gate!!.complete(Unit)
        assertEquals(false, preferences.data.value[KEY_GATEWAY_KEEP_ALIVE])
    }

    @Test fun queuedNotificationEditCannotDisableAReenabledPreference() {
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot(1))
        val instance = create()
        instance.onStartCommand(start.captured, 0, 1)
        val oldAction = stopAction(instance)
        preferences.gate = CompletableDeferred()
        instance.onStartCommand(oldAction, 0, 2)
        GatewayKeepAliveService.update(context, false, ActiveTurnKeepAliveRegistry.Snapshot(1))
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot(1))
        preferences.gate!!.complete(Unit)
        assertEquals(true, preferences.data.value[KEY_GATEWAY_KEEP_ALIVE])
        instance.onStartCommand(oldAction, 0, 3)
        assertEquals(true, preferences.data.value[KEY_GATEWAY_KEEP_ALIVE])
    }

    @Test fun failedPreferenceWriteKeepsTruthfulNotificationAndProtection() {
        GatewayKeepAliveService.update(context, true, ActiveTurnKeepAliveRegistry.Snapshot())
        val instance = create()
        instance.onStartCommand(start.captured, 0, 1)
        preferences.fail = true
        instance.onStartCommand(stopAction(instance), 0, 2)
        assertEquals(true, preferences.data.value[KEY_GATEWAY_KEEP_ALIVE])
        assertFalse(shadowOf(instance).isStoppedBySelf)
        assertEquals(1, shadowOf(instance).lastForegroundNotification!!.actions.size)
    }

    @Test fun coldOldNotificationAndNullRestartDoNotEnableIdleRetention() {
        val instance = create()
        assertEquals(android.app.Service.START_NOT_STICKY, instance.onStartCommand(null, 0, 1))
        instance.onStartCommand(Intent().setAction(GatewayKeepAliveService.ACTION_STOP), 0, 2)
        assertNull(shadowOf(instance).lastForegroundNotification)
        assertTrue(shadowOf(instance).isStoppedBySelf)
        assertEquals(true, preferences.data.value[KEY_GATEWAY_KEEP_ALIVE])
    }

    private fun notificationTitle(instance: GatewayKeepAliveService) =
        shadowOf(instance).lastForegroundNotification!!.extras.getString("android.title")

    private fun stopAction(instance: GatewayKeepAliveService): Intent =
        shadowOf(shadowOf(instance).lastForegroundNotification!!.actions.single().actionIntent).savedIntent

    private class TestPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(preferencesOf(KEY_GATEWAY_KEEP_ALIVE to true))
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            gate?.await()
            if (fail) throw java.io.IOException("write failed")
            return transform(data.value).also { data.value = it }
        }
    }
}
