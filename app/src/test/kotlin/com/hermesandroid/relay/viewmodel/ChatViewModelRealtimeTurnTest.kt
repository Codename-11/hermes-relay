package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.BackgroundTaskPhase
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.HermesApiClient
import com.hermesandroid.relay.network.relay.RealtimeVoiceEvent
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelRealtimeTurnTest {

    private lateinit var server: MockWebServer
    private lateinit var handler: ChatHandler
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(404)
            }
            start()
        }
        handler = ChatHandler()
        viewModel = ChatViewModel().also {
            it.initialize(HermesApiClient(server.url("/").toString(), "test-key"), handler)
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun transportFailureSettlesRealtimePlaceholder() {
        val assistantId = viewModel.startRealtimeAgentTurn(userText = "", chatSessionId = "session-1")

        viewModel.failRealtimeAgentTurn(
            assistantId,
            "Voice connection was interrupted. Tap the mic to try again.",
        )

        val assistant = handler.messages.value.single { it.id == assistantId }
        assertFalse(handler.isStreaming.value)
        assertFalse(assistant.isStreaming)
        assertEquals("Voice connection was interrupted. Tap the mic to try again.", assistant.content)
        assertTrue("Error" in assistant.badges)
        assertFalse(handler.messages.value.any { it.content == "Listening..." })
    }

    @Test
    fun localStopSettlesRealtimePlaceholder() {
        val assistantId = viewModel.startRealtimeAgentTurn(userText = "", chatSessionId = "session-1")

        viewModel.cancelRealtimeAgentTurnLocally(assistantId)
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = assistantId,
            event = RealtimeVoiceEvent(type = "voice.response.delta", delta = "late response", raw = "{}"),
        )

        val assistant = handler.messages.value.single { it.id == assistantId }
        assertFalse(handler.isStreaming.value)
        assertFalse(assistant.isStreaming)
        assertEquals("Cancelled.", assistant.content)
        assertFalse(handler.messages.value.any { it.content == "Listening..." })
    }

    @Test
    fun localVoiceCommandRemovesItsSyntheticChatTurn() {
        val assistantId = viewModel.startRealtimeAgentTurn(userText = "", chatSessionId = "session-1")
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = assistantId,
            event = RealtimeVoiceEvent(
                type = "voice.input_transcript.final",
                text = "pause",
                raw = "{}",
            ),
        )

        viewModel.discardRealtimeAgentLocalCommandTurn(assistantId)
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = assistantId,
            event = RealtimeVoiceEvent(
                type = "voice.response.delta",
                delta = "late provider response",
                raw = "{}",
            ),
        )

        assertTrue(handler.messages.value.none { it.id == assistantId })
        assertTrue(handler.messages.value.none { it.content == "pause" })
        assertTrue(handler.messages.value.none { it.content == "Cancelled." })
        assertNull(handler.lastSentMessage.value)
    }

    @Test
    fun normalResponseCompletionAllowsLaterBackgroundSummaryOnSameTurn() {
        val assistantId = viewModel.startRealtimeAgentTurn(userText = "Check Hermes", chatSessionId = "session-1")
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = assistantId,
            event = RealtimeVoiceEvent(type = "voice.response.delta", delta = "I'll check.", raw = "{}"),
        )
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = assistantId,
            event = RealtimeVoiceEvent(type = "voice.response.done", raw = "{}"),
        )

        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = assistantId,
            event = RealtimeVoiceEvent(
                type = "voice.response.started",
                provider = "xai_realtime",
                raw = "{}",
            ),
        )
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = assistantId,
            event = RealtimeVoiceEvent(type = "voice.response.delta", delta = " Final answer.", raw = "{}"),
        )

        val assistant = handler.messages.value.single { it.id == assistantId }
        assertTrue(handler.isStreaming.value)
        assertEquals("I'll check. Final answer.", assistant.content)
    }

    @Test
    fun backgroundRunKeepsOneChatIdentityFromPromotionThroughDelivery() {
        val assistantId = viewModel.startRealtimeAgentTurn(
            userText = "Check release readiness across all targets",
            chatSessionId = "session-1",
        )

        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = assistantId,
            event = RealtimeVoiceEvent(
                type = "hermes.run.promoted",
                runId = "run-42",
                tier = "durable",
                queuedCount = 1,
                raw = "{}",
            ),
        )

        val promoted = handler.messages.value.single { it.id == assistantId }.backgroundTask
        assertEquals("run-42", promoted?.id)
        assertEquals("Check release readiness across all targets", promoted?.title)
        assertEquals(BackgroundTaskPhase.RUNNING, promoted?.phase)
        assertEquals(1, promoted?.queuedCount)
        assertEquals(2, handler.messages.value.size)

        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = assistantId,
            event = RealtimeVoiceEvent(
                type = "hermes.run.progress",
                runId = "run-42",
                activeToolName = "shell_command",
                completedToolCount = 2,
                message = "Checking Android targets",
                raw = "{}",
            ),
        )
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = assistantId,
            event = RealtimeVoiceEvent(
                type = "hermes.run.queued",
                runId = "run-42",
                queuedCount = 3,
                raw = "{}",
            ),
        )

        val running = handler.messages.value.single { it.id == assistantId }.backgroundTask
        assertEquals(BackgroundTaskPhase.RUNNING, running?.phase)
        assertEquals("Checking Android targets", running?.statusLine)
        assertEquals(2, running?.completedToolCount)
        assertEquals(3, running?.queuedCount)

        // The provider's initial spoken handoff completes before Hermes does;
        // that must not settle the still-running task card.
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = assistantId,
            event = RealtimeVoiceEvent(type = "voice.response.done", raw = "{}"),
        )
        assertEquals(
            BackgroundTaskPhase.RUNNING,
            handler.messages.value.single { it.id == assistantId }.backgroundTask?.phase,
        )

        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = assistantId,
            event = RealtimeVoiceEvent(
                type = "hermes.run.background_completed",
                runId = "run-42",
                success = true,
                queuedCount = 0,
                raw = "{}",
            ),
        )
        assertEquals(
            BackgroundTaskPhase.DELIVERING,
            handler.messages.value.single { it.id == assistantId }.backgroundTask?.phase,
        )

        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = assistantId,
            event = RealtimeVoiceEvent(type = "voice.response.started", raw = "{}"),
        )
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = assistantId,
            event = RealtimeVoiceEvent(
                type = "voice.response.delta",
                delta = "All targets are ready.",
                raw = "{}",
            ),
        )
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = assistantId,
            event = RealtimeVoiceEvent(type = "voice.response.done", raw = "{}"),
        )

        val settled = handler.messages.value.single { it.id == assistantId }
        assertEquals(BackgroundTaskPhase.COMPLETE, settled.backgroundTask?.phase)
        assertEquals("All targets are ready.", settled.content)
        assertEquals(2, handler.messages.value.size)
    }

    @Test
    fun backgroundRunKeepsItsOwnerAfterANewerLocalVoiceCommand() {
        val backgroundAssistantId = viewModel.startRealtimeAgentTurn(
            userText = "Check every release target",
            chatSessionId = "session-1",
        )
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = backgroundAssistantId,
            event = RealtimeVoiceEvent(
                type = "hermes.run.promoted",
                runId = "run-42",
                tier = "durable",
                raw = "{}",
            ),
        )
        // The provider handoff ends, but run ownership must outlive the turn's
        // normal tracking maps while Hermes continues in the background.
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = backgroundAssistantId,
            event = RealtimeVoiceEvent(type = "voice.response.done", raw = "{}"),
        )

        val commandAssistantId = viewModel.startRealtimeAgentTurn(
            userText = "",
            chatSessionId = "session-1",
        )
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = commandAssistantId,
            event = RealtimeVoiceEvent(
                type = "voice.input_transcript.final",
                text = "pause",
                raw = "{}",
            ),
        )
        viewModel.discardRealtimeAgentLocalCommandTurn(commandAssistantId)

        // The persistent Voice callback supplies its newest assistant id, but
        // run-scoped and delivery events still belong to the initiating row.
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = commandAssistantId,
            event = RealtimeVoiceEvent(
                type = "hermes.run.progress",
                runId = "run-42",
                message = "Checking the final target",
                completedToolCount = 3,
                raw = "{}",
            ),
        )
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = commandAssistantId,
            event = RealtimeVoiceEvent(
                type = "hermes.run.background_completed",
                runId = "run-42",
                success = true,
                raw = "{}",
            ),
        )
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = commandAssistantId,
            event = RealtimeVoiceEvent(
                type = "voice.response.started",
                delivery = "forced_summary",
                raw = "{}",
            ),
        )
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = commandAssistantId,
            event = RealtimeVoiceEvent(
                type = "voice.response.delta",
                source = "provider",
                delta = "Every release target is ready.",
                delivery = "forced_summary",
                raw = "{}",
            ),
        )
        viewModel.applyRealtimeAgentEvent(
            assistantMessageId = commandAssistantId,
            event = RealtimeVoiceEvent(
                type = "voice.response.done",
                delivery = "forced_summary",
                raw = "{}",
            ),
        )

        val background = handler.messages.value.single { it.id == backgroundAssistantId }
        assertEquals(BackgroundTaskPhase.COMPLETE, background.backgroundTask?.phase)
        assertEquals("Every release target is ready.", background.content)
        assertEquals(3, background.backgroundTask?.completedToolCount)
        assertTrue(handler.messages.value.none { it.id == commandAssistantId })
        assertTrue(handler.messages.value.none { it.content == "pause" })
        assertEquals("Check every release target", handler.lastSentMessage.value)
    }
}
