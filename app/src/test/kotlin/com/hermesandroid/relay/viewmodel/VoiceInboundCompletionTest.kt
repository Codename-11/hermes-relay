package com.hermesandroid.relay.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.audio.VoicePlayer
import com.hermesandroid.relay.audio.VoiceRecorder
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.VoiceAudioRoute
import com.hermesandroid.relay.data.VoiceEngineMode
import com.hermesandroid.relay.network.shared.VoiceAudioClient
import com.hermesandroid.relay.network.upstream.ChatHandler
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceInboundCompletionTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var chat: ChatViewModel
    private lateinit var handler: ChatHandler
    private lateinit var voice: VoiceViewModel
    private lateinit var recorder: VoiceRecorder
    private lateinit var player: VoicePlayer
    private val synthesis = mutableListOf<String>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        handler = ChatHandler().also { it.setSessionId("session-a") }
        chat = ChatViewModel().also {
            it.initialize(null, handler)
            it.streamingEndpoint = "gateway"
        }
        recorder = mockk(relaxed = true) {
            every { amplitude } returns MutableStateFlow(0f)
            every { isRecording() } returns false
        }
        player = mockk(relaxed = true) {
            every { amplitude } returns MutableStateFlow(0f)
        }
        val app = ApplicationProvider.getApplicationContext<Application>()
        val audio = object : VoiceAudioClient {
            override val route = VoiceAudioRoute.Standard
            override suspend fun transcribe(audioFile: File) = Result.success("")
            override suspend fun synthesize(text: String): Result<File> {
                synthesis.add(text)
                return Result.success(File.createTempFile("inbound-voice", ".wav", app.cacheDir))
            }
        }
        voice = VoiceViewModel(app).also {
            it.initialize(
                voiceClient = mockk(relaxed = true), voiceAudioClient = audio,
                chatViewModel = chat, recorder = recorder, player = player,
                sfxPlayer = mockk(relaxed = true),
            )
            it.enterVoiceMode()
        }
    }

    @After
    fun teardown() {
        voice.exitVoiceMode()
        Dispatchers.resetMain()
    }

    private fun admission(): (String) -> Unit = requireNotNull(chat.gatewayInboundSpeechReceiver?.invoke())

    @Test
    fun settledCompletionUsesConfiguredTtsExactlyOnce() = runTest(dispatcher) {
        runCurrent()
        val receipt = admission()
        receipt("The timer finished.")
        receipt("The timer finished.")
        runCurrent()
        assertEquals(listOf("The timer finished."), synthesis)
        verify(exactly = 1) { player.play(any()) }
    }

    @Test
    fun fastInboundStartCannotBeConsumedByPreviousVoiceObserver() = runTest(dispatcher) {
        runCurrent()
        // Exercise the production observer with the Main dispatcher held between
        // the old terminal and the next inbound admission, as OkHttp can do.
        VoiceViewModel::class.java.getDeclaredMethod("startStreamObserver", ChatViewModel::class.java)
            .apply { isAccessible = true }.invoke(voice, chat)
        handler.addPlaceholderMessage(ChatMessage(
            id = "ordinary", role = MessageRole.ASSISTANT, content = "Work started.",
            timestamp = 1L, isStreaming = true,
        ))
        handler.onStreamComplete("ordinary")
        val receipt = admission()
        handler.addPlaceholderMessage(ChatMessage(
            id = "inbound", role = MessageRole.ASSISTANT, content = "Work finished.",
            timestamp = 2L, isStreaming = true,
        ))
        handler.onStreamComplete("inbound")
        receipt("Work finished.")
        runCurrent()
        assertEquals(listOf("Work started.", "Work finished."), synthesis)
    }

    @Test
    fun completionWaitsForEarlierSpeechAndPreservesOrder() = runTest(dispatcher) {
        runCurrent()
        voice.seedSpeakingStateForTest(listOf("Earlier answer"), 0)
        admission()("Process finished.")
        admission()("Delegated work finished.")
        runCurrent()
        assertTrue(synthesis.isEmpty())
        voice.finishAgentAudioOutputForTest()
        runCurrent()
        assertEquals(listOf("Process finished.", "Delegated work finished."), synthesis)
    }

    @Test
    fun activeCaptureIsNeverCancelledForCompletionSpeech() = runTest(dispatcher) {
        runCurrent()
        every { recorder.isRecording() } returns true
        admission()("Watch matched.")
        runCurrent()
        assertTrue(synthesis.isEmpty())
        verify(exactly = 0) { recorder.cancel() }
        every { recorder.isRecording() } returns false
        voice.finishAgentAudioOutputForTest()
        runCurrent()
        assertEquals(listOf("Watch matched."), synthesis)
    }

    @Test
    fun stopInvalidatesAdmittedAndQueuedCompletions() = runTest(dispatcher) {
        runCurrent()
        val late = admission()
        voice.seedSpeakingStateForTest(emptyList(), 0)
        admission()("Queued answer.")
        voice.interruptSpeaking()
        late("Late answer.")
        runCurrent()
        assertTrue(synthesis.isEmpty())
        assertNull(chat.gatewayInboundSpeechReceiver?.invoke())
    }

    @Test
    fun exitAndReentryRejectsOldReceiptButAcceptsNewTurn() = runTest(dispatcher) {
        runCurrent()
        val old = admission()
        voice.exitVoiceMode()
        voice.enterVoiceMode()
        old("Old answer.")
        admission()("New answer.")
        runCurrent()
        assertEquals(listOf("New answer."), synthesis)
    }

    @Test
    fun sessionSwitchRejectsOldReceiptAndDoesNotAdoptNewSession() = runTest(dispatcher) {
        runCurrent()
        val old = admission()
        handler.setSessionId("session-b")
        old("Wrong session.")
        runCurrent()
        assertNull(chat.gatewayInboundSpeechReceiver?.invoke())
        handler.setSessionId("session-a")
        old("Stale return.")
        runCurrent()
        assertTrue(synthesis.isEmpty())
    }

    @Test
    fun sameSessionIdInAnotherProfileCannotSpeak() = runTest(dispatcher) {
        runCurrent()
        val old = admission()
        chat.switchProfileContext("connection-b::profile-b", "session-a")
        old("Wrong profile.")
        runCurrent()
        assertTrue(synthesis.isEmpty())
        assertNull(chat.gatewayInboundSpeechReceiver?.invoke())
    }

    @Test
    fun realtimeEngineDoesNotConsumeGatewaySpeech() = runTest(dispatcher) {
        runCurrent()
        val old = admission()
        voice.setVoiceEngineModeForTest(VoiceEngineMode.RealtimeAgent)
        old("Wrong engine.")
        assertNull(chat.gatewayInboundSpeechReceiver?.invoke())
        runCurrent()
        assertTrue(synthesis.isEmpty())
    }

    @Test
    fun switchingEnginesBackCannotReviveAnOldReceipt() = runTest(dispatcher) {
        runCurrent()
        val old = admission()
        val applySettings = VoiceViewModel::class.java.getDeclaredMethod(
            "applyVoiceSettingsSnapshot", com.hermesandroid.relay.data.VoiceSettings::class.java,
        ).apply { isAccessible = true }
        applySettings.invoke(voice, com.hermesandroid.relay.data.VoiceSettings(
            engineMode = VoiceEngineMode.RealtimeAgent.storageValue,
        ))
        applySettings.invoke(voice, com.hermesandroid.relay.data.VoiceSettings())
        old("Stale engine receipt.")
        runCurrent()
        assertTrue(synthesis.isEmpty())
    }

    @Test
    fun newVoiceConversationAdoptsOnlyItsSubmittedSession() = runTest(dispatcher) {
        runCurrent()
        voice.exitVoiceMode()
        handler.setSessionId(null)
        voice.enterVoiceMode()
        val fence = VoiceTurnSessionFence(null).also { it.bindSubmittedUser("voice-user") }
        VoiceViewModel::class.java.getDeclaredField("voiceTurnSessionFence")
            .apply { isAccessible = true }.set(voice, fence)
        handler.addUserMessage(ChatMessage(
            id = "voice-user", role = MessageRole.USER, content = "Start work.", timestamp = 1L,
        ))
        handler.setSessionId("new-voice-session")
        runCurrent()
        admission()("New conversation completion.")
        runCurrent()
        assertEquals(listOf("New conversation completion."), synthesis)
    }

    @Test
    fun conversationChangeCancelsPendingSynthesisWithoutCancellingChat() = runTest(dispatcher) {
        runCurrent()
        voice.stopTtsConsumerForTest()
        admission()("Old profile output.")
        chat.switchProfileContext("connection-b::profile-b", "session-b")
        runCurrent()
        assertTrue(voice.drainTtsQueueForTest().isEmpty())
        assertTrue(synthesis.isEmpty())
        verify(atLeast = 1) { player.stop() }
    }

    @Test
    fun historyAndForegroundReplayNeverCreateSpeech() = runTest(dispatcher) {
        runCurrent()
        handler.addPlaceholderMessage(ChatMessage(
            id = "history-a", role = MessageRole.ASSISTANT, content = "Historical answer.",
            timestamp = 1L, isStreaming = false,
        ))
        voice.onAppResumed()
        runCurrent()
        assertTrue(synthesis.isEmpty())
    }
}
