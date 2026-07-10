package com.hermesandroid.relay.viewmodel

import android.os.Handler
import android.os.Looper
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.GatewayClientHarness
import com.hermesandroid.relay.network.upstream.GatewayConnectionState
import com.hermesandroid.relay.network.upstream.HermesApiClient
import com.hermesandroid.relay.network.upstream.models.MessageItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelGatewayInboundTurnTest {

    private lateinit var gatewayHarness: GatewayClientHarness
    private lateinit var apiServer: MockWebServer
    private lateinit var gatewayScope: CoroutineScope
    private lateinit var gatewayClient: GatewayChatClient
    private lateinit var serverWs: WebSocket
    private lateinit var handler: ChatHandler
    private lateinit var viewModel: ChatViewModel
    @Volatile
    private var persistedHistory: List<MessageItem> = emptyList()
    @Volatile
    private var holdCompletionsStream = false

    @Before
    fun setUp() {
        gatewayHarness = GatewayClientHarness()
        apiServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    if (holdCompletionsStream && request.path == "/v1/chat/completions") {
                        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    } else {
                        MockResponse().setResponseCode(404)
                    }
            }
            start()
        }
        gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        gatewayClient = GatewayChatClient(
            initialDashboardClient = DashboardApiClient(
                baseUrl = gatewayHarness.server.url("/").toString().trimEnd('/'),
                okHttpClient = OkHttpClient(),
            ),
            okHttpClient = OkHttpClient(),
            // Match production ordering: Gateway callbacks are posted from the
            // OkHttp WebSocket thread onto Android's main looper.
            callbackDispatcher = { block ->
                Handler(Looper.getMainLooper()).post(block)
            },
            scope = gatewayScope,
        )
        handler = ChatHandler().also { it.setSessionId(STORED_SESSION_ID) }
        persistedHistory = emptyList()
        holdCompletionsStream = false
        viewModel = ChatViewModel().also {
            it.initialize(
                HermesApiClient(apiServer.url("/").toString(), "test-key"),
                handler,
            )
            it.streamingEndpoint = "gateway"
            it.setProfileMessageLoader {
                Result.success(persistedHistory)
            }
            it.updateGatewayClient(gatewayClient)
        }
        assertTrue(runBlocking { gatewayClient.prewarmAwait(STORED_SESSION_ID) })
        serverWs = gatewayHarness.awaitServerSocket()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        viewModel.updateGatewayClient(null)
        gatewayClient.shutdown()
        gatewayScope.cancel()
        gatewayHarness.shutdown()
        apiServer.shutdown()
    }

    @Test
    fun unsolicitedGatewayCompletionAppearsAsOneAssistantTurnAndSettles() {
        // Upstream's process-completion poller currently emits this adjacent
        // duplicate pair; it must still create exactly one placeholder.
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )

        awaitCondition {
            handler.messages.value.singleOrNull()?.content == BACKGROUND_ANSWER
        }
        assertTrue(handler.isStreaming.value)
        assertEquals(1, handler.messages.value.size)
        assertEquals(MessageRole.ASSISTANT, handler.messages.value.single().role)

        persistedHistory = persistedAnswerHistory()
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )

        awaitCondition { !handler.isStreaming.value }
        shadowOf(Looper.getMainLooper()).idle()
        awaitCondition {
            handler.messages.value.singleOrNull()?.content == BACKGROUND_ANSWER
        }
        assertFalse(handler.messages.value.single().isStreaming)
        assertFalse(gatewayHarness.rpcLog.any { it.first == "prompt.submit" })
    }

    @Test
    fun queuedMainDispatchAdmitsBackgroundStartAfterLocalCompletion() {
        viewModel.sendMessage("Local gateway turn")
        gatewayHarness.awaitRpc("prompt.submit")
        awaitCondition { gatewayClient.hasActiveTurn() }

        // Queue the local completion and the server-initiated start back to
        // back. The inbound admission must run behind the local completion on
        // main, rather than reading stale activeStream state on the socket.
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Local answer") },
                "live-resumed",
            ),
        )
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )

        awaitCondition {
            handler.messages.value.any {
                it.id.startsWith("gateway-inbound-") && it.content == BACKGROUND_ANSWER
            }
        }
        awaitCondition { !handler.isStreaming.value }
    }

    @Test
    fun stopOnUnsolicitedTurnInterruptsTheGatewaySession() {
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "Still composing") },
                "live-resumed",
            ),
        )
        awaitCondition { handler.isStreaming.value }

        viewModel.cancelStream()

        gatewayHarness.awaitRpc("session.interrupt")
        assertFalse(handler.isStreaming.value)

        // Upstream can emit the interrupted turn's terminal event after the
        // interrupt RPC. It is a drain marker, not a new background answer.
        persistedHistory = persistedAnswerHistory("Canceled answer", "canceled-server-answer")
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Canceled answer") },
                "live-resumed",
            ),
        )
        Thread.sleep(150)
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        assertFalse(handler.messages.value.any { it.content == "Canceled answer" })
        assertTrue(handler.messages.value.any { "Stopped" in it.badges })
    }

    @Test
    fun lateCanceledCompletionDrainsBeforeImmediateNextTurn() {
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "Old partial") },
                "live-resumed",
            ),
        )
        awaitCondition { handler.isStreaming.value }

        viewModel.cancelStream()
        gatewayHarness.awaitRpc("session.interrupt")
        viewModel.sendMessage("Start the next turn")

        // Let the bounded next-submit wait elapse first. The started turn's
        // tombstone must still drain its eventual terminal event rather than
        // routing it into the new active mapper.
        Thread.sleep(2_100)
        awaitCondition {
            gatewayHarness.rpcLog.any { (method, params) ->
                method == "prompt.submit" && params["text"] == JsonPrimitive("Start the next turn")
            }
        }
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Canceled answer") },
                "live-resumed",
            ),
        )

        persistedHistory = persistedAnswerHistory("New answer", "new-server-answer")
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "New answer") },
                "live-resumed",
            ),
        )
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "New answer") },
                "live-resumed",
            ),
        )

        awaitCondition { handler.messages.value.any { it.content == "New answer" } }
        awaitCondition { !handler.isStreaming.value }
        assertFalse(handler.messages.value.any { it.content == "Canceled answer" })
    }

    @Test
    fun queuedMessageDrainsAfterUnsolicitedTurnCompletes() {
        gatewayHarness.steerStatus = "rejected"
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "Finishing background work") },
                "live-resumed",
            ),
        )
        awaitCondition { handler.isStreaming.value }

        viewModel.sendMessage("Run this next")
        gatewayHarness.awaitRpc("session.steer")
        awaitCondition { viewModel.queuedMessages.value == listOf("Run this next") }

        persistedHistory = persistedAnswerHistory()
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Finishing background work") },
                "live-resumed",
            ),
        )

        awaitCondition {
            gatewayHarness.rpcLog.any { (method, params) ->
                method == "prompt.submit" && params["text"] == JsonPrimitive("Run this next")
            }
        }
        assertTrue(viewModel.queuedMessages.value.isEmpty())
    }

    @Test
    fun coldForegroundPrewarmReloadsACompletionMissedWhileDisconnected() {
        persistedHistory = persistedAnswerHistory()
        serverWs.close(1012, "test disconnect")
        awaitCondition { gatewayClient.connectionState.value == GatewayConnectionState.Idle }

        viewModel.prewarmGateway()
        gatewayHarness.awaitServerSocket()

        awaitCondition {
            handler.messages.value.singleOrNull()?.content == BACKGROUND_ANSWER
        }
        assertFalse(handler.isStreaming.value)
    }

    @Test
    fun unsolicitedTurnDoesNotReplaceAnActiveForcedSseTurn() {
        holdCompletionsStream = true
        viewModel.sendVoiceMessage("local voice turn", "Respond for spoken playback")
        awaitCondition { handler.isStreaming.value }
        val localPlaceholderId = handler.messages.value.last().id

        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )
        persistedHistory = persistedAnswerHistory()
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )
        Thread.sleep(150)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the forced SSE turn must still own streaming", handler.isStreaming.value)
        assertTrue(handler.messages.value.any { it.id == localPlaceholderId && it.isStreaming })
        assertFalse(handler.messages.value.any { it.content == BACKGROUND_ANSWER })

        viewModel.cancelStream()
        awaitCondition { !handler.isStreaming.value }
        awaitCondition { handler.messages.value.any { it.content == BACKGROUND_ANSWER } }
    }

    @Test
    fun acceptedInboundTurnSettlesAfterGatewayDowngrade() {
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )
        awaitCondition { handler.isStreaming.value }

        viewModel.streamingEndpoint = "sessions"
        viewModel.updateGatewayClient(null)
        persistedHistory = persistedAnswerHistory()
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )

        awaitCondition { !handler.isStreaming.value }
        awaitCondition {
            handler.messages.value.singleOrNull()?.id == "persisted-background-answer"
        }
        assertEquals(BACKGROUND_ANSWER, handler.messages.value.single().content)
    }

    @Test
    fun lateOldCompletionDoesNotClearNewGatewayTurnSteering() {
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "Old inbound") },
                "live-resumed",
            ),
        )
        awaitCondition { handler.isStreaming.value }

        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Old inbound") },
                "live-resumed",
            ),
        )
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (gatewayClient.hasActiveTurn() && System.nanoTime() < deadline) {
            // Deliberately do not idle main: keep the old completion callback
            // queued while the socket-side mapper reaches its terminal state.
            Thread.sleep(20)
        }
        assertFalse("old gateway turn never ended", gatewayClient.hasActiveTurn())

        viewModel.cancelStream()
        viewModel.sendMessage("New gateway turn")
        awaitCondition { gatewayClient.hasActiveTurn() }

        // Pump the queued old callback. It no longer owns activeStream and must
        // not clear the new turn's steering affordance.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.steerableTurn.value)
    }

    @Test
    fun reconnectAfterMissedStartRecoversOnExactSessionCompletion() {
        serverWs.close(1012, "missed start")
        awaitCondition { gatewayClient.connectionState.value == GatewayConnectionState.Idle }
        viewModel.prewarmGateway()
        serverWs = gatewayHarness.awaitServerSocket()

        // Reconnected midway through the synthetic turn: no message.start is
        // replayed, so the delta is intentionally ignored and completion drives
        // authoritative history recovery.
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )
        persistedHistory = persistedAnswerHistory()
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )

        awaitCondition { handler.messages.value.any { it.content == BACKGROUND_ANSWER } }
        assertFalse(handler.isStreaming.value)
    }

    @Test
    fun staleHistoryReadCannotEraseATurnCompletedDuringTheFetch() {
        val loadCount = AtomicInteger(0)
        val firstLoadStarted = CompletableDeferred<Unit>()
        val releaseFirstLoad = CompletableDeferred<Unit>()
        viewModel.setProfileMessageLoader {
            when (loadCount.incrementAndGet()) {
                1 -> {
                    firstLoadStarted.complete(Unit)
                    releaseFirstLoad.await()
                    Result.success(persistedAnswerHistory())
                }
                else -> Result.success(
                    persistedAnswerHistory() +
                        MessageItem(
                            id = "newer-local-answer",
                            sessionId = STORED_SESSION_ID,
                            role = "assistant",
                            content = JsonPrimitive("Newer answer"),
                        ),
                )
            }
        }

        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )
        awaitCondition { firstLoadStarted.isCompleted }

        handler.addPlaceholderMessage(
            ChatMessage(
                id = "newer-local-answer",
                role = MessageRole.ASSISTANT,
                content = "Newer answer",
                timestamp = System.currentTimeMillis(),
                isStreaming = true,
            ),
        )
        handler.onStreamComplete("newer-local-answer")
        releaseFirstLoad.complete(Unit)

        awaitCondition {
            handler.messages.value.any { it.content == "Newer answer" } && loadCount.get() >= 2
        }
        assertTrue(handler.messages.value.any { it.content == BACKGROUND_ANSWER })
    }

    private fun persistedAnswerHistory(
        answer: String = BACKGROUND_ANSWER,
        id: String = "persisted-background-answer",
    ): List<MessageItem> = listOf(
        MessageItem(
            id = id,
            sessionId = STORED_SESSION_ID,
            role = "assistant",
            content = JsonPrimitive(answer),
        ),
    )

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            // Advance Robolectric's paused main clock so coroutine delay-based
            // reconciliation retries can resume as they do on-device.
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("condition not met; messages=${handler.messages.value}", condition())
    }

    companion object {
        private const val STORED_SESSION_ID = "stored-session"
        private const val BACKGROUND_ANSWER = "Background task finished."
    }
}
