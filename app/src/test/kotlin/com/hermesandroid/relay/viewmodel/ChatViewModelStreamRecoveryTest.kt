package com.hermesandroid.relay.viewmodel

import android.os.Looper
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.HermesApiClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * End-to-end coverage for the issue #166 dropped-stream answer recovery:
 * a real [HermesApiClient] against a [MockWebServer] whose sessions SSE
 * route dies mid-turn (transport error, NOT a server error), with the
 * `/api/sessions/{id}/messages` route scripted so the poller first finds
 * no answer and then the persisted final answer.
 *
 * Runs under Robolectric so the client's main-thread Handler dispatch and
 * `viewModelScope` (Dispatchers.Main → Robolectric main looper) are real;
 * the test drives time by idling the main looper. Recovery cadence is
 * shrunk via [ChatViewModel.recoveryTimingOverride] so polls take
 * milliseconds instead of seconds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelStreamRecoveryTest {

    private companion object {
        const val SESSION_ID = "s1"
        const val PROMPT = "hello there"
        const val RECOVERED = "recovered answer"

        const val USER_ONLY_BODY =
            """{"data":[{"id":"u1","role":"user","content":"$PROMPT","timestamp":1000.0}]}"""
        const val WITH_ANSWER_BODY =
            """{"data":[""" +
                """{"id":"u1","role":"user","content":"$PROMPT","timestamp":1000.0},""" +
                """{"id":"a1","role":"assistant","content":"$RECOVERED","timestamp":1001.0}]}"""
        const val EMPTY_BODY = """{"data":[]}"""
    }

    private lateinit var server: MockWebServer
    private lateinit var vm: ChatViewModel
    private lateinit var handler: ChatHandler

    private val messagesRequests = AtomicInteger(0)
    private val streamRequests = AtomicInteger(0)

    /** GET /messages body for the [n]-th poll (1-based). */
    @Volatile
    private var messagesBodyFor: (Int) -> String = { USER_ONLY_BODY }

    /** Non-first POST /chat/stream responses complete cleanly when true. */
    @Volatile
    private var secondStreamSucceeds = false

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: return MockResponse().setResponseCode(404)
                return when {
                    request.method == "POST" &&
                        path == "/api/sessions/$SESSION_ID/chat/stream" -> {
                        val n = streamRequests.incrementAndGet()
                        if (n > 1 && secondStreamSucceeds) {
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "text/event-stream")
                                .setBody("data: [DONE]\n\n")
                        } else {
                            // Transport death mid-turn: advertise a full body
                            // but cut the socket halfway — the SSE listener's
                            // onFailure fires with an IOException, exactly the
                            // Doze / Wi-Fi power-save drop class.
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "text/event-stream")
                                .setBody(": keepalive\n\n: keepalive\n\n: keepalive\n\n")
                                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                        }
                    }
                    request.method == "GET" &&
                        path == "/api/sessions/$SESSION_ID/messages" -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(messagesBodyFor(messagesRequests.incrementAndGet()))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        handler = ChatHandler()
        vm = ChatViewModel()
        vm.streamingEndpoint = "sessions"
        vm.recoveryTimingOverride = ChatStreamRecovery.Timing(
            pollIntervalMs = 150,
            maxPollIntervalMs = 150,
            recoveryWindowMs = 60_000,
        )
        vm.initialize(HermesApiClient(server.url("/").toString(), "test-key"), handler)
        handler.setSessionId(SESSION_ID)
        idle()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        idle()
    }

    /** Run queued main-looper tasks and advance the Robolectric clock a bit. */
    private fun idle(ms: Long = 200) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    /**
     * Idle the main looper (advancing virtual time so coroutine delays fire)
     * while real IO threads make progress, until [condition] holds.
     */
    private fun awaitCondition(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(15)
        }
        fail("Timed out waiting for: $what")
    }

    /** Settle, snapshot the poll counter, idle on, and assert it stayed put. */
    private fun assertPollingStopped() {
        // Let any already-in-flight poll (or post-turn reconcile read) land.
        Thread.sleep(150)
        idle(500)
        val settled = messagesRequests.get()
        repeat(10) {
            idle(300)
            Thread.sleep(15)
        }
        assertEquals("poller must not keep hitting /messages", settled, messagesRequests.get())
    }

    @Test
    fun streamKilledMidTurn_recoversPersistedAnswerAndCompletesPlaceholder() {
        // Poll 1 has no answer yet; the answer is persisted from poll 2 on.
        messagesBodyFor = { n -> if (n < 2) USER_ONLY_BODY else WITH_ANSWER_BODY }

        vm.sendMessage(PROMPT)
        idle()

        awaitCondition("streaming placeholder shown") {
            handler.messages.value.any { it.role == MessageRole.ASSISTANT && it.isStreaming }
        }
        awaitCondition("recovery started after the transport drop") {
            vm.recoveringAnswer.value
        }

        awaitCondition("recovered answer reconciled + turn finalized") {
            !vm.recoveringAnswer.value &&
                !handler.isStreaming.value &&
                handler.messages.value.any {
                    it.role == MessageRole.ASSISTANT && it.content == RECOVERED && !it.isStreaming
                }
        }

        // Stability requires the answer on two consecutive polls, and the
        // first poll had none — at least 3 polls total.
        assertTrue("expected >= 3 polls, saw ${messagesRequests.get()}", messagesRequests.get() >= 3)
        assertNull("a recovered turn must not surface an error", handler.error.value)
        assertFalse(
            "no streaming placeholder may survive recovery",
            handler.messages.value.any { it.isStreaming },
        )
    }

    @Test
    fun userCancelDuringRecovery_abortsThePoller() {
        messagesBodyFor = { USER_ONLY_BODY } // answer never arrives

        vm.sendMessage(PROMPT)
        awaitCondition("recovery started") { vm.recoveringAnswer.value }
        awaitCondition("at least one poll issued") { messagesRequests.get() >= 1 }

        vm.cancelStream()
        idle()

        assertFalse(vm.recoveringAnswer.value)
        assertFalse(handler.isStreaming.value)
        assertNull("user cancel is not an error", handler.error.value)
        assertTrue(
            "the cancelled placeholder should carry the Stopped badge",
            handler.messages.value.any { it.role == MessageRole.ASSISTANT && "Stopped" in it.badges },
        )
        assertPollingStopped()
    }

    @Test
    fun sessionSwitchDuringRecovery_leavesChatNotStreamingWithNoStuckStatus() {
        messagesBodyFor = { USER_ONLY_BODY } // answer never arrives

        vm.sendMessage(PROMPT)
        awaitCondition("recovery started") { vm.recoveringAnswer.value }
        awaitCondition("at least one poll issued") { messagesRequests.get() >= 1 }

        // Switch away while recovery is polling — there is NO live stream, so
        // aborting the poller must itself settle the streaming/turn-status
        // state instead of wedging the chat "streaming forever". (s2 has no
        // scripted /messages route → the history load 404s to empty, which is
        // fine: this asserts the abort settles, not that s2 loads anything.)
        vm.switchSession("s2")
        idle()

        assertFalse("recovery poller must be aborted", vm.recoveringAnswer.value)
        assertFalse("chat must not be stuck streaming", handler.isStreaming.value)
        assertNull("no stuck 'Reconnecting…' turn status", handler.turnStatus.value)
        assertNull("a silent abandon is not an error", handler.error.value)
        assertPollingStopped()
    }

    @Test
    fun newSendDuringRecovery_abortsThePoller() {
        messagesBodyFor = { USER_ONLY_BODY } // first turn's answer never arrives
        secondStreamSucceeds = true

        vm.sendMessage(PROMPT)
        awaitCondition("recovery started") { vm.recoveringAnswer.value }
        awaitCondition("at least one poll issued") { messagesRequests.get() >= 1 }

        vm.sendMessage("a second question")
        idle()
        assertFalse("a new send must abort the poller", vm.recoveringAnswer.value)

        awaitCondition("second turn completes") { !handler.isStreaming.value }
        assertPollingStopped()
    }

    @Test
    fun recoveryWindowExpiry_fallsBackToTheErrorUi() {
        // Empty transcript = "no information" → the poller keeps trying until
        // the (shrunken) window expires, then the existing error UI fires.
        messagesBodyFor = { EMPTY_BODY }
        vm.recoveryTimingOverride = ChatStreamRecovery.Timing(
            pollIntervalMs = 100,
            maxPollIntervalMs = 100,
            recoveryWindowMs = 350,
        )

        vm.sendMessage(PROMPT)
        awaitCondition("recovery started") { vm.recoveringAnswer.value }
        awaitCondition("cap expiry surfaces the error") { handler.error.value != null }

        assertFalse(vm.recoveringAnswer.value)
        assertFalse(handler.isStreaming.value)
        assertNotNull(handler.error.value)
        assertPollingStopped()
    }
}
