package com.hermesandroid.relay.voice

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.hermesandroid.relay.viewmodel.VoiceUiState
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class VoiceOverlayLifecycleTest {
    private lateinit var host: VoiceOverlayHost
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    private var exits = 0
    private var access = VoiceOverlayAccess(true, true, true, true)
    private val state = MutableStateFlow(VoiceUiState(voiceMode = true))

    @Before fun setup() {
        mockkObject(VoiceOverlayAccess.Companion)
        every { VoiceOverlayAccess.read(any()) } answers { access }
        host = VoiceOverlayHost(RuntimeEnvironment.getApplication())
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
    }
    @After fun cleanup() { host.hide(); unmockkAll() }

    private fun session() = VoiceOverlaySession(state, provider = null, model = null, voice = null,
        profileName = "Test", configScope = null, outputEnabled = true, fallbackEnabled = false,
        onStartListening = {}, onStopListening = {}, onInterrupt = {}, onPauseAutoMode = {},
        onReturnToHermes = {}, onDismissOverlay = {}, onExit = { exits++ })

    @Test fun backgroundCallerCannotCreateSession() {
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertFalse(host.show(session(), owner.lifecycle))
        assertNull(host.sessionId)
    }

    @Test fun missingAccessCannotCreateSession() {
        listOf(access.copy(microphone = false), access.copy(notifications = false),
            access.copy(overlay = false), access.copy(unlocked = false)).forEach {
            access = it
            assertFalse(host.show(session(), owner.lifecycle))
            assertNull(host.sessionId)
        }
    }

    @Test fun stopBeforeServiceReadyRejectsLateStartAndExitsOnce() {
        assertTrue(host.show(session(), owner.lifecycle))
        val id = host.sessionId!!
        host.exitVoiceSession(id)
        host.exitVoiceSession(id)
        assertFalse(host.onServiceReady(id))
        assertEquals(1, exits)
    }

    @Test fun oldStopAndReadyCannotAffectNewSession() {
        host.show(session(), owner.lifecycle)
        val old = host.sessionId!!
        host.exitVoiceSession(old)
        host.show(session(), owner.lifecycle)
        val current = host.sessionId!!
        host.exitVoiceSession(old)
        assertFalse(host.onServiceReady(old))
        assertEquals(current, host.sessionId)
        assertTrue(host.canStart(current))
        assertEquals(1, exits)
    }

    @Test fun lossOfForegroundBeforeServicePromotionFailsClosed() {
        host.show(session(), owner.lifecycle)
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertFalse(host.canStart(host.sessionId!!))
    }

    @Test fun revocationAndVoiceExitInvalidateActiveEligibility() {
        host.show(session(), owner.lifecycle)
        val id = host.sessionId!!
        access = access.copy(overlay = false)
        assertFalse(host.canContinue(id))
        access = access.copy(overlay = true)
        state.value = VoiceUiState(voiceMode = false)
        assertFalse(host.canContinue(id))
    }

    @Test fun destroyedCallerEndsPendingSession() {
        host.show(session(), owner.lifecycle)
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        assertNull(host.sessionId)
        assertEquals(1, exits)
    }

    @Test fun returningToResumedAppReleasesOverlayWithoutEndingVoice() {
        host.show(session(), owner.lifecycle)
        val id = host.sessionId!!
        assertTrue(host.onServiceReady(id))
        owner.lifecycle.currentState = Lifecycle.State.STARTED
        assertTrue(host.canContinue(id))
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertNull(host.sessionId)
        assertEquals(0, exits)
        assertTrue(state.value.voiceMode)
    }
}
