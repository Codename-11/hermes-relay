package com.hermesandroid.relay.viewmodel

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
}
