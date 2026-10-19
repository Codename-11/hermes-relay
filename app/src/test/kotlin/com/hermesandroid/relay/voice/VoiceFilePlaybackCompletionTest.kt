package com.hermesandroid.relay.voice

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.audio.VoicePlayer
import com.hermesandroid.relay.audio.VoiceRecorder
import com.hermesandroid.relay.data.VoiceAudioRoute
import com.hermesandroid.relay.network.shared.VoiceAudioClient
import com.hermesandroid.relay.viewmodel.InteractionMode
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceUiState
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Media3 file player owns audible output between `play(file)` and its
 * drain. The end-of-reply completion check must not end Speaking (and, in
 * Continuous mode, reopen the microphone and stop the player) while the last
 * synthesized file is still playing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceFilePlaybackCompletionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private lateinit var vm: VoiceViewModel
    private lateinit var player: VoicePlayer
    private lateinit var recorder: VoiceRecorder
    private var playing = false
    private var playbackDrained = CompletableDeferred<Unit>()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0

        player = mockk(relaxed = true)
        every { player.play(any()) } answers { playing = true }
        every { player.isPlaying() } answers { playing }
        coEvery { player.awaitCompletion() } coAnswers { playbackDrained.await() }
        every { player.stop() } answers {
            playing = false
            playbackDrained.complete(Unit)
        }

        recorder = mockk(relaxed = true)
        every { recorder.isRecording() } returns false
        every { recorder.amplitude } returns MutableStateFlow(0f)

        val audioClient = mockk<VoiceAudioClient>(relaxed = true)
        every { audioClient.effectiveRoute } returns VoiceAudioRoute.Relay
        coEvery { audioClient.synthesize(any()) } coAnswers {
            Result.success(tempFolder.newFile().apply { writeText("x") })
        }

        vm = VoiceViewModel(mockk<Application>(relaxed = true))
        setField("player", player)
        setField("recorder", recorder)
        setField("voiceAudioClient", audioClient)
        // Production starts the consumer during initialize(), before voice mode.
        invokePrivate("startTtsConsumer")
        setField("continuousLoopArmed", true)
        uiState().value = VoiceUiState(
            voiceMode = true,
            state = VoiceState.Speaking,
            interactionMode = InteractionMode.Continuous,
        )
    }

    @After
    fun cleanup() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `final file still playing keeps Speaking and does not reopen the microphone`() =
        runTest(UnconfinedTestDispatcher(scheduler)) {
            vm.enqueueSentenceForTts("This is the final sentence of a long spoken reply.")
            runCurrent()
            verify(exactly = 1) { player.play(any()) }

            // The assistant stream has completed; this is the check the stream
            // observer schedules. Give it far longer than the retry cadence.
            scheduleCompletionCheck()
            advanceTimeBy(3_000)
            runCurrent()

            assertEquals(VoiceState.Speaking, vm.uiState.value.state)
            verify(exactly = 0) { player.stop() }
            verify(exactly = 0) { recorder.startRecording() }

            // Once Media3 drains, the shared finalizer may hand the turn back.
            playing = false
            playbackDrained.complete(Unit)
            advanceTimeBy(3_000)
            runCurrent()

            assertEquals(VoiceState.Listening, vm.uiState.value.state)
            verify(exactly = 1) { recorder.startRecording() }
        }

    private fun setField(name: String, value: Any) {
        VoiceViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun uiState(): MutableStateFlow<VoiceUiState> =
        VoiceViewModel::class.java.getDeclaredField("_uiState")
            .apply { isAccessible = true }
            .get(vm) as MutableStateFlow<VoiceUiState>

    private fun invokePrivate(name: String) {
        VoiceViewModel::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(vm)
    }

    private fun scheduleCompletionCheck() {
        VoiceViewModel::class.java
            .getDeclaredMethod("scheduleAgentAudioCompletionCheck", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(vm, 100L)
    }
}
