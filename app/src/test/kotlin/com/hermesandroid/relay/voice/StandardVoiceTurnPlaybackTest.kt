package com.hermesandroid.relay.voice

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.audio.RealtimePcmPlayer
import com.hermesandroid.relay.audio.VoicePlayer
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.VoiceAudioRoute
import com.hermesandroid.relay.network.shared.VoiceAudioClient
import com.hermesandroid.relay.network.shared.VoiceSpeechStream
import com.hermesandroid.relay.network.shared.VoiceSpeechStreamCallbacks
import com.hermesandroid.relay.network.shared.VoiceSpeechStreamOutcome
import com.hermesandroid.relay.network.shared.VoiceSpeechStreamStatus
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.GatewayEventMapper
import com.hermesandroid.relay.network.upstream.GatewayTurnCallbacks
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.InteractionMode
import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceUiState
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class StandardVoiceTurnPlaybackTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var vm: VoiceViewModel
    private lateinit var pcm: RealtimePcmPlayer
    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val running = MutableStateFlow(true)
    private val session = MutableStateFlow<String?>("voice-test")
    private val appended = mutableListOf<String>()
    private val synthesized = mutableListOf<String>()
    private val files = mutableListOf<File>()
    private var streamAvailable = true
    private var opens = 0
    private var finishes = 0
    private var stops = 0
    private var remaining = 500L
    private lateinit var callbacks: VoiceSpeechStreamCallbacks
    private val outcome = CompletableDeferred<VoiceSpeechStreamOutcome>()
    private val stream = object : VoiceSpeechStream {
        override fun append(text: String) { appended += text }
        override fun finish() { finishes++ }
        override fun stop() { stops++ }
        override suspend fun awaitOutcome() = outcome.await()
    }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        pcm = mockk(relaxed = true)
        every { pcm.estimatedRemainingPlaybackMs(any()) } answers { remaining }
        vm = VoiceViewModel(mockk<Application>(relaxed = true))
        vm.standardSpeechWriteDispatcher = dispatcher
        field("realtimePcmPlayer", pcm)
        field("player", mockk<VoicePlayer>(relaxed = true))
        field("voiceAudioClient", object : VoiceAudioClient {
            override val route = VoiceAudioRoute.Standard
            override suspend fun transcribe(audioFile: File) = Result.success("")
            override suspend fun synthesize(text: String): Result<File> {
                synthesized += text
                return Result.success(File.createTempFile("final-answer-test", ".wav").also { files += it })
            }
            override suspend fun openSpeechStream(callbacks: VoiceSpeechStreamCallbacks): Result<VoiceSpeechStream?> {
                this@StandardVoiceTurnPlaybackTest.callbacks = callbacks
                opens++
                return Result.success(stream.takeIf { streamAvailable })
            }
        })
        @Suppress("UNCHECKED_CAST")
        val ui = VoiceViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
            .get(vm) as MutableStateFlow<VoiceUiState>
        ui.value = VoiceUiState(voiceMode = true, state = VoiceState.Thinking,
            interactionMode = InteractionMode.HoldToTalk)
    }

    @After
    fun cleanup() {
        vm.viewModelScope.cancel()
        files.forEach { it.delete() }
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun observe(
        finalOnly: Boolean = false,
        messageFlow: StateFlow<List<ChatMessage>> = messages,
        activeFlow: StateFlow<Boolean> = running,
        sessionFlow: StateFlow<String?> = session,
    ) {
        field("finalAnswerOnly", finalOnly)
        if (!finalOnly) invoke("prepareStandardSpeechStream")
        val chat = mockk<ChatViewModel>(relaxed = true)
        every { chat.messages } returns messageFlow
        every { chat.isStreaming } returns activeFlow
        every { chat.currentSessionId } returns sessionFlow
        VoiceViewModel::class.java.getDeclaredMethod("startStreamObserver", ChatViewModel::class.java)
            .apply { isAccessible = true }.invoke(vm, chat)
    }

    @Test
    fun toolGapDoesNotFinishStreamAndFinalPcmDrainsBeforeIdle() = runTest(dispatcher) {
        observe()
        runCurrent()
        messages.value = listOf(message("first", "The opening sentence."))
        runCurrent()
        callbacks.onPcm(ByteArray(90_000), 24_000)
        runCurrent()
        remaining = 0
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, finishes)
        assertEquals(0, stops)
        assertTrue(vm.uiState.value.state != VoiceState.Idle)

        messages.value += message("last", "The final answer.")
        running.value = false
        runCurrent()
        assertEquals("The opening sentence.\n\nThe final answer.", appended.joinToString(""))
        assertEquals(1, finishes)
        remaining = 1_000
        callbacks.onPcm(ByteArray(48_000), 24_000)
        outcome.complete(VoiceSpeechStreamOutcome(VoiceSpeechStreamStatus.Completed, true))
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(VoiceState.Speaking, vm.uiState.value.state)
        verify(exactly = 2) { pcm.write(any(), any(), any()) }
        remaining = 0
        // The production echo tail guard uses wall time, independent of the test scheduler.
        field("lastRealtimeAudioDeltaAtMs", 1L)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(VoiceState.Idle, vm.uiState.value.state)
    }

    @Test
    fun finalOnlyReusesRapidToolsGatewayScenario() = runTest(dispatcher) {
        val handler = ChatHandler().also { it.setSessionId("voice-test") }
        val active = HeldEmissionStateFlow(handler.isStreaming) { it }
        observe(true, handler.messages, active, handler.currentSessionId)
        runCurrent()
        val mapper = GatewayEventMapper(GatewayTurnCallbacks(
            onSessionId = handler::setSessionId,
            onStart = { handler.addPlaceholderMessage(message("answer", "").copy(isStreaming = true)) },
            onTextDelta = { handler.onTextDelta("answer", it) },
            onThinkingDelta = { handler.onThinkingDelta("answer", it) },
            onToolCallStart = { id, name, args -> handler.onToolCallStart("answer", id, name, args) },
            onToolCallDone = { id, result -> handler.onToolCallComplete("answer", id, result) },
            onToolCallFailed = { id, error -> handler.onToolCallFailed("answer", id, error) },
            onTurnComplete = { handler.onTurnComplete("answer") },
            onReconcileRequired = { },
            onComplete = { handler.onStreamComplete("answer") },
            onUsage = { }, onError = { error(it) }, onToolGenerating = { },
            onSubagentEvent = { }, onMoaReference = { },
            onInteractionRequest = { }, onInteractionExpired = { },
        ))
        val relative = "test-fixtures/vanilla-gateway/vanilla_gateway/scenarios/rapid_tools_interims.json"
        val fixture = listOf(File(relative), File("../$relative")).first { it.isFile }
        val steps = Json.parseToJsonElement(fixture.readText()).jsonObject
            .getValue("turns").jsonArray.first().jsonObject.getValue("steps").jsonArray
        for (step in steps.map { it.jsonObject }) {
            if (step.getValue("op").jsonPrimitive.content != "event") continue
            val type = step.getValue("type").jsonPrimitive.content
            mapper.onEvent(type, step["payload"]?.jsonObject)
            runCurrent()
            if (type != "message.complete") assertEquals(0, opens)
        }
        active.release.complete(Unit)
        runCurrent()
        assertTrue(mapper.turnEnded)
        assertEquals(1, opens)
        assertEquals(listOf("First interim. Final."), appended)
        assertEquals(1, finishes)
        assertTrue(synthesized.isEmpty())
    }

    @Test
    fun finalOnlyDoesNotRetireObserverWhenActiveFlagDeliveryLagsPlaceholder() = delayedRunStart()

    @Test
    fun finalOnlyUsesFileFallbackWhenActiveFlagDeliveryLagsPlaceholder() = delayedRunStart(fileFallback = true)

    private fun delayedRunStart(fileFallback: Boolean = false) = runTest(dispatcher) {
        streamAvailable = !fileFallback
        if (fileFallback) invoke("startTtsConsumer")
        val handler = ChatHandler().also { it.setSessionId("voice-test") }
        val active = HeldEmissionStateFlow(handler.isStreaming) { it }
        observe(true, handler.messages, active, handler.currentSessionId)
        runCurrent()
        handler.addPlaceholderMessage(message("answer", "").copy(isStreaming = true))
        runCurrent()
        assertEquals(0, opens)
        assertTrue(synthesized.isEmpty())
        handler.onTextDelta("answer", "The complete final answer.")
        runCurrent()
        assertEquals(0, opens)
        active.release.complete(Unit)
        runCurrent()
        handler.onStreamComplete("answer")
        runCurrent()
        assertEquals(1, opens)
        if (fileFallback) {
            assertEquals(listOf("The complete final answer."), synthesized)
            assertTrue(appended.isEmpty())
        } else {
            assertEquals(listOf("The complete final answer."), appended)
            assertEquals(1, finishes)
            assertTrue(synthesized.isEmpty())
        }
        handler.replaceMessageContent("answer", "The complete final answer. Updated history.")
        runCurrent()
        assertEquals(1, opens)
        assertEquals(if (fileFallback) 1 else 0, synthesized.size)
    }

    @Test
    fun finalOnlyReadsSettledAnswerWhenMessageDeliveryLagsCompletion() = delayedFinalAnswer()

    @Test
    fun finalOnlyFileFallbackReadsSettledAnswerAfterTools() = delayedFinalAnswer(fileFallback = true)

    private fun delayedFinalAnswer(fileFallback: Boolean = false) = runTest(dispatcher) {
        streamAvailable = !fileFallback
        if (fileFallback) invoke("startTtsConsumer")
        val handler = ChatHandler().also { it.setSessionId("voice-test") }
        val delayedMessages = HeldEmissionStateFlow(handler.messages) { rows ->
            rows.any { it.content == "The complete final answer." }
        }
        observe(true, delayedMessages, handler.isStreaming, handler.currentSessionId)
        runCurrent()
        handler.addPlaceholderMessage(message("interim", "I will check.").copy(isStreaming = true))
        handler.onToolCallStart("interim", "tool-1", "terminal")
        runCurrent()
        handler.onToolCallComplete("interim", "tool-1", "done")
        handler.onTurnComplete("interim")
        handler.addPlaceholderMessage(message("answer", "").copy(isStreaming = true))
        runCurrent()
        handler.onTextDelta("answer", "The complete final answer.")
        runCurrent()
        assertEquals(0, opens)
        handler.onStreamComplete("answer")
        runCurrent()
        assertEquals(1, opens)
        if (fileFallback) {
            assertEquals(listOf("The complete final answer."), synthesized)
            assertTrue(appended.isEmpty())
        } else {
            assertEquals(listOf("The complete final answer."), appended)
            assertEquals(1, finishes)
        }
        delayedMessages.release.complete(Unit)
        runCurrent()
        assertEquals(1, opens)
        assertEquals(if (fileFallback) 1 else 0, synthesized.size)
    }

    // Delay one collector without changing the real handler's current values.
    // combine's sources can be scheduled independently on the Main dispatcher.
    @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
    private class HeldEmissionStateFlow<T>(
        private val source: StateFlow<T>,
        private val hold: (T) -> Boolean,
    ) : StateFlow<T> by source {
        val release = CompletableDeferred<Unit>()
        override suspend fun collect(collector: FlowCollector<T>): Nothing = source.collect {
            if (hold(it)) release.await()
            collector.emit(it)
        }
    }

    @Test
    fun finalOnlyObservesRealChatStartAndCompletion() = realChatStartAndCompletion(0)

    @Test
    fun finalOnlyObservesChatStartedDuringObserverSubscription() = realChatStartAndCompletion(1)

    @Test
    fun finalOnlyObservesChatStartedAfterObserverSubscription() = realChatStartAndCompletion(2)

    private fun realChatStartAndCompletion(startYields: Int) = runTest(dispatcher) {
        val handler = ChatHandler().also { it.setSessionId("voice-test") }
        observe(true, handler.messages, handler.isStreaming, handler.currentSessionId)
        launch {
            repeat(startYields) { yield() }
            handler.addPlaceholderMessage(message("answer", "").copy(isStreaming = true))
        }
        runCurrent()
        assertEquals(0, opens)
        handler.onTextDelta("answer", "The complete final answer.")
        handler.onStreamComplete("answer")
        runCurrent()
        assertEquals(1, opens)
        assertEquals(listOf("The complete final answer."), appended)
        assertEquals(1, finishes)
    }

    @Test
    fun finalAnswerOnlyOpensSpeechForSettledFinalBubble() = runTest(dispatcher) {
        observe(finalOnly = true)
        runCurrent()
        messages.value = listOf(message("interim", "I will check."))
        runCurrent()
        advanceTimeBy(5_000)
        assertEquals(0, opens)
        messages.value += message("final", "The complete final answer.")
        running.value = false
        runCurrent()
        assertEquals(1, opens)
        assertEquals(listOf("The complete final answer."), appended)
        assertEquals(1, finishes)
    }

    @Test
    fun finalOnlyStopBeforeCompletionDoesNotSubmitLateAnswer() = runTest(dispatcher) {
        observe(finalOnly = true)
        runCurrent()
        messages.value = listOf(message("answer", "A partial answer."))
        runCurrent()
        vm.interruptSpeaking()
        messages.value = listOf(message("answer", "The complete final answer."))
        running.value = false
        runCurrent()
        assertEquals(0, opens)
        assertTrue(synthesized.isEmpty())
    }

    @Test
    fun finalOnlyKeepsSessionFenceWhenSessionDeliveryLagsCompletion() = runTest(dispatcher) {
        val delayedSession = HeldEmissionStateFlow(session) { it == "another-session" }
        observe(finalOnly = true, sessionFlow = delayedSession)
        runCurrent()
        session.value = "another-session"
        runCurrent()
        messages.value = listOf(message("foreign", "Another session's answer."))
        running.value = false
        runCurrent()
        delayedSession.release.complete(Unit)
        runCurrent()
        assertEquals(0, opens)
        assertTrue(synthesized.isEmpty())
    }

    @Test
    fun finalOnlyDoesNotRequestSpeechForSanitizedEmptyAnswer() = runTest(dispatcher) {
        observe(finalOnly = true)
        runCurrent()
        messages.value = listOf(message("answer", "```python\nprint(1)\n```"))
        running.value = false
        runCurrent()
        assertEquals(0, opens)
        assertTrue(synthesized.isEmpty())
    }

    @Test
    fun cancelledStreamCannotPlayItsLatePcm() = runTest(dispatcher) {
        observe()
        runCurrent()
        val old = callbacks
        VoiceViewModel::class.java.getDeclaredMethod("cancelStandardSpeechStream", String::class.java)
            .apply { isAccessible = true }.invoke(vm, "test cancellation")
        old.onPcm(ByteArray(90_000), 24_000)
        runCurrent()
        verify(exactly = 0) { pcm.write(any(), any(), any()) }
        assertTrue(stops > 0)
        assertFalse(outcome.isCompleted)
    }

    @Test
    fun socketEndWaitsForPendingIoAndPreservesFrameOrder() = runTest(dispatcher) {
        val io = PausedDispatcher()
        vm.standardSpeechWriteDispatcher = io
        observe()
        runCurrent()
        messages.value = listOf(message("answer", "A complete answer."))
        running.value = false
        runCurrent()
        val first = ByteArray(90_000) { 1 }
        val last = ByteArray(48_000) { 2 }
        callbacks.onPcm(first, 24_000)
        callbacks.onPcm(last, 24_000)
        outcome.complete(VoiceSpeechStreamOutcome(VoiceSpeechStreamStatus.Completed, true))
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        verify(exactly = 0) { pcm.flushBufferedPlayback(any()) }
        assertEquals(VoiceState.Speaking, vm.uiState.value.state)
        io.runNext()
        runCurrent()
        verify(exactly = 0) { pcm.flushBufferedPlayback(any()) }
        io.runNext()
        runCurrent()
        verifyOrder {
            pcm.write(first, 24_000, any())
            pcm.write(last, 24_000, any())
            pcm.flushBufferedPlayback(any())
        }
    }

    @Test
    fun replacementStreamRejectsOldSocketAndScheduledIo() = runTest(dispatcher) {
        val io = PausedDispatcher()
        vm.standardSpeechWriteDispatcher = io
        observe()
        runCurrent()
        val old = callbacks
        old.onPcm(ByteArray(90_000), 24_000)
        runCurrent()
        invoke("prepareStandardSpeechStream")
        runCurrent()
        old.onPcm(ByteArray(48_000), 24_000)
        io.runNext()
        runCurrent()
        verify(exactly = 0) { pcm.write(any(), any(), any()) }
        callbacks.onPcm(ByteArray(960), 24_000)
        runCurrent()
        io.runNext()
        runCurrent()
        verify(exactly = 1) { pcm.write(any(), any(), any()) }
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun runNext() { pending.removeAt(0).run() }
    }

    private fun message(id: String, text: String) = ChatMessage(
        id = id, role = MessageRole.ASSISTANT, content = text, timestamp = 1L, isStreaming = false,
    )
    private fun invoke(name: String) {
        VoiceViewModel::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(vm)
    }
    private fun field(name: String, value: Any) {
        VoiceViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }
}
