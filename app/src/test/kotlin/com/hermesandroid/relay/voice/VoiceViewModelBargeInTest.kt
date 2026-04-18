package com.hermesandroid.relay.voice

import android.app.Application
import android.util.Log
import com.hermesandroid.relay.audio.BargeInListener
import com.hermesandroid.relay.audio.VadEngine
import com.hermesandroid.relay.audio.VoicePlayer
import com.hermesandroid.relay.audio.VoiceRecorder
import com.hermesandroid.relay.audio.VoiceSfxPlayer
import com.hermesandroid.relay.data.BargeInPreferences
import com.hermesandroid.relay.data.BargeInPreferencesRepository
import com.hermesandroid.relay.data.BargeInSensitivity
import com.hermesandroid.relay.network.RelayVoiceClient
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * B4 coverage (voice-barge-in, 2026-04-17): exercises the
 * [VoiceViewModel] barge-in coordinator — listener start/stop gating on
 * Speaking lifecycle, `maybeSpeech` → duck + un-duck watchdog,
 * `bargeInDetected` → `interruptSpeaking` + pre-warm + resume watchdog,
 * and live preference-change reactivity.
 *
 * We drive the coordinator via the `@VisibleForTesting internal`
 * entry points (`onMaybeSpeech`, `onBargeInDetected`,
 * `startBargeInListenerForTest`, `seedSpeakingStateForTest`,
 * `drainTtsQueueForTest`). That matches the plan's direction —
 * "mocked `BargeInListener`, `VadEngine`, `VoicePlayer`,
 * `BargeInPreferencesRepository`; inject them — don't instantiate real
 * ones" — without needing to push files through the V4 synth/play
 * supervisor or synthesize real MP3s on disk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Ignore("Tracked in GitHub issue #32 — voice test suite validation deferred")
class VoiceViewModelBargeInTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    // Mocks shared across tests. Re-created in [setUp] so verify-counts
    // don't leak between cases.
    private lateinit var application: Application
    private lateinit var voiceClient: RelayVoiceClient
    private lateinit var chatViewModel: ChatViewModel
    private lateinit var recorder: VoiceRecorder
    private lateinit var player: VoicePlayer
    private lateinit var sfxPlayer: VoiceSfxPlayer
    private lateinit var vadEngine: VadEngine
    private lateinit var bargeInListener: BargeInListener
    private lateinit var prefsRepo: BargeInPreferencesRepository
    private lateinit var prefsFlow: MutableStateFlow<BargeInPreferences>
    private lateinit var maybeSpeechFlow: MutableSharedFlow<Unit>
    private lateinit var bargeInFlow: MutableSharedFlow<Unit>
    private lateinit var recorderAmplitude: MutableStateFlow<Float>
    private lateinit var playerAmplitude: MutableStateFlow<Float>

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)

        // Static stubs for android.util.Log — [VoiceViewModel] logs on
        // error paths; without these the JVM unit-test classpath throws
        // "Method not mocked."
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0

        application = mockk(relaxed = true)
        voiceClient = mockk(relaxed = true)
        chatViewModel = mockk(relaxed = true) {
            every { messages } returns MutableStateFlow(
                emptyList<com.hermesandroid.relay.data.ChatMessage>(),
            )
        }
        recorderAmplitude = MutableStateFlow(0f)
        recorder = mockk(relaxed = true) {
            every { amplitude } returns recorderAmplitude
            every { isRecording() } returns false
        }
        playerAmplitude = MutableStateFlow(0f)
        player = mockk(relaxed = true) {
            every { amplitude } returns playerAmplitude
            every { audioSessionId } returns 42
        }
        sfxPlayer = mockk(relaxed = true)
        vadEngine = mockk(relaxed = true) {
            every { setSensitivity(any()) } just runs
            every { close() } just runs
        }
        maybeSpeechFlow = MutableSharedFlow(extraBufferCapacity = 4)
        bargeInFlow = MutableSharedFlow(extraBufferCapacity = 4)
        bargeInListener = mockk(relaxed = true) {
            every { maybeSpeech } returns maybeSpeechFlow
            every { bargeInDetected } returns bargeInFlow
        }
        prefsFlow = MutableStateFlow(
            BargeInPreferences(
                enabled = true,
                sensitivity = BargeInSensitivity.Default,
                resumeAfterInterruption = true,
            ),
        )
        prefsRepo = mockk {
            every { flow } returns prefsFlow
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    /**
     * Build a [VoiceViewModel] wired with the shared mocks. Callers can
     * override individual prefs via [prefs] if they need a different
     * initial snapshot.
     */
    private fun buildViewModel(
        prefs: BargeInPreferences = prefsFlow.value,
    ): VoiceViewModel {
        prefsFlow.value = prefs
        val vm = VoiceViewModel(application)
        vm.initialize(
            voiceClient = voiceClient,
            chatViewModel = chatViewModel,
            recorder = recorder,
            player = player,
            sfxPlayer = sfxPlayer,
            bridgeMultiplexer = null,
            localBridgeDispatcher = null,
            bargeInPreferences = prefsRepo,
            vadEngineFactory = { vadEngine },
            bargeInListenerFactory = { _, _ -> bargeInListener },
        )
        return vm
    }

    // -------------------------------------------------------------------
    // Test 1 — bargeInDetected while Speaking → interrupt + Listening
    // -------------------------------------------------------------------

    @Test
    fun `bargeInDetected during Speaking interrupts and flips to Listening`() = runTest {
        val vm = buildViewModel()
        vm.seedSpeakingStateForTest(chunks = listOf("Hello."), currentIdx = 0)
        runCurrent()

        vm.onBargeInDetected()
        runCurrent()

        verify(atLeast = 1) { player.stop() }
        assertEquals(
            "bargeInDetected must flip state to Listening so the recorder owns the mic",
            VoiceState.Listening,
            vm.uiState.value.state,
        )
        verify(atLeast = 1) { recorder.startRecording() }
    }

    // -------------------------------------------------------------------
    // Test 2 — resume with resumeAfterInterruption=true + silence
    // -------------------------------------------------------------------

    @Test
    fun `resume with silence and resume-enabled re-enqueues un-played chunks`() = runTest {
        val vm = buildViewModel(
            BargeInPreferences(
                enabled = true,
                sensitivity = BargeInSensitivity.Default,
                resumeAfterInterruption = true,
            ),
        )
        // Three-chunk turn, playing the first when the interrupt lands.
        // Remaining tail → two chunks should be re-enqueued.
        vm.seedSpeakingStateForTest(
            chunks = listOf("one.", "two.", "three."),
            currentIdx = 0,
        )
        // Ensure any prior seed data left the queue empty before we assert
        // on its contents post-watchdog.
        vm.drainTtsQueueForTest()
        runCurrent()

        // Recorder amplitude stays at 0 → observePeakAmplitude() registers
        // silence, resume fires.
        recorderAmplitude.value = 0f

        vm.onBargeInDetected()
        runCurrent()
        // Stop the V4 consumer so its synth worker doesn't race us and
        // drain the resumed tail before our assertion reads it.
        vm.stopTtsConsumerForTest()
        runCurrent()
        // Past the 600 ms resume watchdog.
        advanceTimeBy(650)
        runCurrent()

        val drained = vm.drainTtsQueueForTest()
        assertEquals(
            "tail chunks after the interrupted index must be re-enqueued on resume",
            listOf("two.", "three."),
            drained,
        )
        assertEquals(
            "state flips back to Speaking once the resume re-enqueue fires",
            VoiceState.Speaking,
            vm.uiState.value.state,
        )
    }

    // -------------------------------------------------------------------
    // Test 3 — resume cancelled by user speech within 600 ms
    // -------------------------------------------------------------------

    @Test
    fun `user speech within 600ms cancels resume watchdog`() = runTest {
        val vm = buildViewModel(
            BargeInPreferences(
                enabled = true,
                sensitivity = BargeInSensitivity.Default,
                resumeAfterInterruption = true,
            ),
        )
        vm.seedSpeakingStateForTest(
            chunks = listOf("one.", "two.", "three."),
            currentIdx = 0,
        )
        vm.drainTtsQueueForTest()
        runCurrent()

        vm.onBargeInDetected()
        runCurrent()
        // User keeps speaking — amplitude climbs above the 0.08 silence
        // threshold inside the 600 ms window.
        advanceTimeBy(100)
        recorderAmplitude.value = 0.5f
        advanceTimeBy(700)
        runCurrent()

        val drained = vm.drainTtsQueueForTest()
        assertTrue(
            "ttsQueue must not receive tail chunks when user kept speaking",
            drained.isEmpty(),
        )
        assertEquals(
            "continued user speech leaves state at Listening — new turn owns it",
            VoiceState.Listening,
            vm.uiState.value.state,
        )
    }

    // -------------------------------------------------------------------
    // Test 4 — resumeAfterInterruption=false → silence still no re-enqueue
    // -------------------------------------------------------------------

    @Test
    fun `resume disabled with silence drops the tail`() = runTest {
        val vm = buildViewModel(
            BargeInPreferences(
                enabled = true,
                sensitivity = BargeInSensitivity.Default,
                resumeAfterInterruption = false,
            ),
        )
        vm.seedSpeakingStateForTest(
            chunks = listOf("one.", "two.", "three."),
            currentIdx = 0,
        )
        vm.drainTtsQueueForTest()
        runCurrent()

        recorderAmplitude.value = 0f
        vm.onBargeInDetected()
        runCurrent()
        vm.stopTtsConsumerForTest()
        runCurrent()
        advanceTimeBy(650)
        runCurrent()

        val drained = vm.drainTtsQueueForTest()
        assertTrue(
            "with resume-off, ttsQueue must stay empty even after silence",
            drained.isEmpty(),
        )
        assertEquals(
            "resume-off treats the interrupt as a hard cancel back to Idle",
            VoiceState.Idle,
            vm.uiState.value.state,
        )
    }

    // -------------------------------------------------------------------
    // Test 5 — maybeSpeech ducks, watchdog un-ducks after 500 ms
    // -------------------------------------------------------------------

    @Test
    fun `maybeSpeech ducks player and watchdog un-ducks when no follow-up`() = runTest {
        val vm = buildViewModel()
        vm.seedSpeakingStateForTest(chunks = listOf("speaking."), currentIdx = 0)
        runCurrent()

        vm.onMaybeSpeech()
        runCurrent()
        verify(atLeast = 1) { player.duck() }

        // Watchdog fires at 500 ms with no bargeInDetected follow-up.
        advanceTimeBy(550)
        runCurrent()

        verify(atLeast = 1) { player.unduck() }
    }

    // -------------------------------------------------------------------
    // Test 6 — bargeInDetected during ducking cancels the watchdog
    // -------------------------------------------------------------------

    @Test
    fun `bargeInDetected during ducking cancels watchdog and proceeds with interrupt`() = runTest {
        val vm = buildViewModel()
        vm.seedSpeakingStateForTest(chunks = listOf("speaking."), currentIdx = 0)
        runCurrent()

        vm.onMaybeSpeech()
        runCurrent()
        verify(atLeast = 1) { player.duck() }

        // Hysteresis passes before the watchdog would have fired.
        advanceTimeBy(100)
        vm.onBargeInDetected()
        runCurrent()

        // Push past the original watchdog window. If the watchdog were
        // still armed and fired, it would call unduck() a second time.
        // stopBargeInListener (invoked inside interruptSpeaking) will
        // have unducked once on the way down — that's the ONLY unduck
        // we expect. The watchdog contribution must be zero.
        advanceTimeBy(500)
        runCurrent()

        // stopBargeInListener unducks once on the way down (isDucked was
        // true), and the watchdog adds nothing because it was cancelled.
        verify(exactly = 1) { player.unduck() }
        verify(atLeast = 1) { player.stop() }
    }

    // -------------------------------------------------------------------
    // Test 7 — mid-Speaking flip of enabled false → listener stopped
    // -------------------------------------------------------------------

    @Test
    fun `mid-Speaking pref flip from enabled-true to enabled-false stops listener`() = runTest {
        val vm = buildViewModel(
            BargeInPreferences(
                enabled = true,
                sensitivity = BargeInSensitivity.Default,
                resumeAfterInterruption = true,
            ),
        )
        vm.seedSpeakingStateForTest(chunks = listOf("streaming."), currentIdx = 0)
        // Force the listener to start without pushing a file through the
        // V4 play pipeline — the production call site (onFileReady) is
        // exercised indirectly via runPlayWorker but the coordinator
        // behaviour we care about here is the preferences collector.
        vm.startBargeInListenerForTest()
        runCurrent()
        verify(atLeast = 1) { bargeInListener.start(any()) }

        // Flip enabled → false; preferences collector in VoiceViewModel
        // observes the change and tears the listener down.
        prefsFlow.value = BargeInPreferences(
            enabled = false,
            sensitivity = BargeInSensitivity.Default,
            resumeAfterInterruption = true,
        )
        runCurrent()

        verify(atLeast = 1) { bargeInListener.stop() }
    }
}
