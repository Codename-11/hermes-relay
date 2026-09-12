package com.hermesandroid.relay.voice

import android.app.Application
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
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
@Config(application = Application::class, sdk = [35])
class VoiceOverlayForegroundServiceTest {
    private val host = mockk<VoiceOverlayHost>(relaxed = true)
    private lateinit var service: VoiceOverlayForegroundService
    @Before fun setup() {
        mockkObject(VoiceOverlayHost.Companion)
        every { VoiceOverlayHost.peek() } returns host
        every { host.sessionId } returns 7L
        every { host.canStart(7L) } returns true
        every { host.canContinue(7L) } returns true
        every { host.onServiceReady(7L) } returns true
        service = Robolectric.buildService(VoiceOverlayForegroundService::class.java).create().get()
    }
    @After fun cleanup() { service.onDestroy(); unmockkAll() }
    private fun command(action: String, id: Long = 7L) = Intent().setAction(action)
        .putExtra(VoiceOverlayForegroundService.EXTRA_SESSION_ID, id)

    @Test fun notificationIsPostedBeforeWindowReadiness() {
        every { host.onServiceReady(7L) } answers {
            assertNotNull(shadowOf(service).lastForegroundNotification)
            true
        }
        service.onStartCommand(command(VoiceOverlayForegroundService.ACTION_START), 0, 1)
        verify(exactly = 1) { host.onServiceReady(7L) }
    }
    @Test fun unownedOrRestartIntentNeverPromotesService() {
        service.onStartCommand(null, 0, 1)
        service.onStartCommand(command(VoiceOverlayForegroundService.ACTION_START, 8), 0, 2)
        assertNull(shadowOf(service).lastForegroundNotification)
        verify(exactly = 0) { host.onServiceReady(any()) }
    }
    @Test fun oldNotificationCannotStopCurrentSession() {
        service.onStartCommand(command(VoiceOverlayForegroundService.ACTION_START), 0, 1)
        service.onStartCommand(command(VoiceOverlayForegroundService.ACTION_STOP, 6), 0, 2)
        verify(exactly = 0) { host.exitVoiceSession(any()) }
        service.onStartCommand(command(VoiceOverlayForegroundService.ACTION_STOP), 0, 3)
        verify(exactly = 1) { host.exitVoiceSession(7L) }
    }
    @Test fun windowFailureEndsVoiceInsteadOfLeavingUnprotectedCapture() {
        every { host.onServiceReady(7L) } returns false
        service.onStartCommand(command(VoiceOverlayForegroundService.ACTION_START), 0, 1)
        verify(exactly = 1) { host.exitVoiceSession(7L) }
    }
    @Test fun taskRemovalEndsVoice() {
        service.onStartCommand(command(VoiceOverlayForegroundService.ACTION_START), 0, 1)
        service.onTaskRemoved(null)
        verify(exactly = 1) { host.exitVoiceSession(7L) }
    }

    @Test fun permissionLossEndsVoiceAtTheNextAccessCheck() {
        service.onStartCommand(command(VoiceOverlayForegroundService.ACTION_START), 0, 1)
        every { host.canContinue(7L) } returns false
        shadowOf(android.os.Looper.getMainLooper()).idle()
        verify(exactly = 1) { host.exitVoiceSession(7L) }
    }

    @Test fun screenOffEndsVoiceWithoutWaitingForPolling() {
        service.onStartCommand(command(VoiceOverlayForegroundService.ACTION_START), 0, 1)
        service.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        verify(exactly = 1) { host.exitVoiceSession(7L) }
    }

    @Test fun failedForegroundPromotionNeverAttachesWindow() {
        service = spyk(service)
        every { service.startForeground(any(), any(), any()) } throws SecurityException("denied")
        service.onStartCommand(command(VoiceOverlayForegroundService.ACTION_START), 0, 1)
        verify(exactly = 0) { host.onServiceReady(any()) }
        verify(exactly = 1) { host.exitVoiceSession(7L) }
    }
}
