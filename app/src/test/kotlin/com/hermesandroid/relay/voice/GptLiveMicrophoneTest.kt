package com.hermesandroid.relay.voice

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModelStore
import com.hermesandroid.relay.network.shared.GptLiveSession
import com.hermesandroid.relay.ui.components.dispatchVoiceMicTap
import com.hermesandroid.relay.viewmodel.InteractionMode
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceUiState
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GptLiveMicrophoneTest {
    private val store = ViewModelStore()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
    }

    @After fun cleanup() {
        store.clear()
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    @Test fun stopDisablesLiveTrackAndStartEnablesTheSameSession() {
        val voice = VoiceViewModel(mockk<Application>(relaxed = true))
        store.put("voice", voice)
        val session = mockk<GptLiveSession>(relaxed = true)
        voice.enterVoiceMode()
        VoiceViewModel::class.java.getDeclaredField("gptLiveSession").apply {
            isAccessible = true
            set(voice, session)
        }
        voice.stopListening()
        assertTrue(voice.uiState.value.gptLiveMicrophoneMuted)
        assertEquals(VoiceState.Idle, voice.uiState.value.state)
        verify(exactly = 1) { session.setMuted(true) }
        verify(exactly = 0) { session.instruct(any()) }

        voice.startListening()
        assertFalse(voice.uiState.value.gptLiveMicrophoneMuted)
        assertEquals(VoiceState.Listening, voice.uiState.value.state)
        verify(exactly = 1) { session.setMuted(false) }
        verify(exactly = 0) { session.close() }
        voice.exitVoiceMode()
        verify(exactly = 1) { session.close() }
        assertFalse(voice.uiState.value.gptLiveMicrophoneMuted)
    }

    @Test fun liveStartStopIgnoresSpeakingAndContinuousGestureState() {
        VoiceState.entries.forEach { state ->
            var starts = 0
            var stops = 0
            var interrupts = 0
            var pauses = 0
            val ui = VoiceUiState(state = state, interactionMode = InteractionMode.Continuous)
            fun tap(muted: Boolean) = dispatchVoiceMicTap(
                uiState = ui.copy(gptLiveMicrophoneMuted = muted),
                onStartListening = { starts++ },
                onStopListening = { stops++ },
                onInterrupt = { interrupts++ },
                onPauseAutoMode = { pauses++ },
                liveMode = true,
            )
            tap(false)
            tap(true)
            assertEquals(1, starts)
            assertEquals(1, stops)
            assertEquals(0, interrupts)
            assertEquals(0, pauses)
        }
    }
}
