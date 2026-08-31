package com.hermesandroid.relay.viewmodel

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.ChatTurnAssistantCheckpoint
import com.hermesandroid.relay.data.ChatTurnCheckpoint
import com.hermesandroid.relay.data.ChatTurnCheckpointStore
import com.hermesandroid.relay.data.ChatTurnUserCheckpoint
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.HermesApiClient
import com.hermesandroid.relay.network.upstream.models.MessageItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device contract coverage for issue #365.
 *
 * This deliberately uses the production [GatewayChatClient], [ChatViewModel],
 * and [ChatHandler]. [DeviceGatewayFixture] supplies only the upstream HTTP/WSS
 * boundary, so Android main-looper dispatch and Compose collection are real.
 */
class GatewayForegroundRecoveryInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var fixture: AndroidGatewayContractFixture
    private lateinit var gatewayScope: CoroutineScope
    private lateinit var gatewayClient: GatewayChatClient
    private lateinit var handler: ChatHandler
    private lateinit var viewModel: ChatViewModel
    private lateinit var serverSocket: WebSocket

    @Volatile
    private var persistedHistory: List<MessageItem> = emptyList()
    private val historySignInRequired = MutableStateFlow(false)

    @Before
    fun setUp() {
        fixture = AndroidGatewayContractFixture().also { it.profileName = PROFILE_NAME }
        gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val okHttp = OkHttpClient()
        gatewayClient = GatewayChatClient(
            initialDashboardClient = DashboardApiClient(
                baseUrl = fixture.server.url("/").toString().trimEnd('/'),
                okHttpClient = okHttp,
            ),
            okHttpClient = okHttp,
            callbackDispatcher = { block -> Handler(Looper.getMainLooper()).post(block) },
            scope = gatewayScope,
            reconnectJitterUnit = { 0.0 },
        )
        handler = ChatHandler().also { it.setSessionId(STORED_SESSION_ID) }
        viewModel = ChatViewModel().also {
            it.initialize(
                HermesApiClient(fixture.server.url("/").toString(), "fixture-key"),
                handler,
            )
            it.streamingEndpoint = "gateway"
            it.setSessionProfileNameProvider { PROFILE_NAME }
            it.setProfileMessageLoader { Result.success(persistedHistory) }
            it.updateGatewayClient(gatewayClient)
            it.setChatVisible(true)
        }

        compose.setContent {
            val messages by viewModel.messages.collectAsStateWithLifecycle()
            val streaming by viewModel.isStreaming.collectAsStateWithLifecycle()
            val signInRequired by historySignInRequired.collectAsStateWithLifecycle()
            MaterialTheme {
                Column(Modifier.testTag("contract-transcript")) {
                    Text(
                        text = if (streaming) "STREAMING" else "IDLE",
                        modifier = Modifier.testTag("stream-state"),
                    )
                    messages.forEach { message ->
                        Text(
                            text = "${message.role.name}:${message.content}",
                            modifier = Modifier.testTag("message-${message.id}"),
                        )
                    }
                    if (signInRequired) {
                        Button(
                            onClick = {},
                            modifier = Modifier.testTag("dashboard-sign-in-recovery"),
                        ) {
                            Text("SIGN IN")
                        }
                    }
                }
            }
        }

        assertTrue(runBlocking { gatewayClient.prewarmAwait(STORED_SESSION_ID) })
        serverSocket = fixture.awaitServerSocket()
        fixture.awaitRpc("session.resume")
    }

    @After
    fun tearDown() {
        viewModel.updateGatewayClient(null)
        gatewayClient.shutdown()
        gatewayScope.cancel()
        fixture.shutdown()
    }

    @Test
    fun terminalGapActivate_recoversForegroundTurnWithoutNavigationOrCrossSessionLeak() {
        viewModel.sendMessage("Run a long foreground task")
        fixture.awaitRpc("prompt.submit")

        // A multiplexed Gateway shares one socket. Foreign-session events must
        // neither render nor settle the visible turn.
        serverSocket.send(fixture.event("message.start", null, FOREIGN_SESSION_ID))
        serverSocket.send(
            fixture.event(
                "message.delta",
                buildJsonObject { put("text", FOREIGN_ANSWER) },
                FOREIGN_SESSION_ID,
            ),
        )
        serverSocket.send(
            fixture.event(
                "message.complete",
                buildJsonObject { put("text", FOREIGN_ANSWER) },
                FOREIGN_SESSION_ID,
            ),
        )

        serverSocket.send(fixture.event("message.start", null, LIVE_SESSION_ID))
        serverSocket.send(
            fixture.event(
                "tool.start",
                buildJsonObject {
                    put("tool_id", "tool-foreground")
                    put("name", "terminal")
                },
                LIVE_SESSION_ID,
            ),
        )
        serverSocket.send(
            fixture.event(
                "message.delta",
                buildJsonObject { put("text", PARTIAL_ANSWER) },
                LIVE_SESSION_ID,
            ),
        )

        compose.waitUntil(5_000) { handler.isStreaming.value }
        compose.onNodeWithTag("stream-state").assertTextEquals("STREAMING")
        compose.onNodeWithTag("contract-transcript").assertIsDisplayed()
        assertFalse(handler.messages.value.any { it.content.contains(FOREIGN_ANSWER) })

        // Exercise the real Activity collection boundary while the turn is
        // still live. STARTED models a covered/backgrounded activity without
        // destroying the test host; returning to RESUMED must preserve the
        // same turn and transcript without navigation.
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { handler.isStreaming.value }
        compose.onNodeWithTag("stream-state").assertTextEquals("STREAMING")

        // The server finishes while this socket is detached. The replacement
        // socket cannot replay message.complete; exact-session activation
        // reports running=false and history is now authoritative.
        persistedHistory = listOf(
            MessageItem(
                id = PERSISTED_ANSWER_ID,
                sessionId = STORED_SESSION_ID,
                role = "assistant",
                content = JsonPrimitive(AUTHORITATIVE_ANSWER),
            ),
        )
        fixture.recoveryRunning = false
        serverSocket.close(1011, "fixture foreground gap")
        serverSocket = fixture.awaitServerSocket()
        fixture.awaitRpc("session.activate")

        compose.waitUntil(5_000) {
            !handler.isStreaming.value &&
                handler.messages.value.singleOrNull()?.id == PERSISTED_ANSWER_ID
        }
        compose.onNodeWithTag("contract-transcript").assertIsDisplayed()
        compose.onNodeWithTag("stream-state").assertTextEquals("IDLE")
        compose.onNodeWithTag("message-$PERSISTED_ANSWER_ID")
            .assertTextEquals("${MessageRole.ASSISTANT.name}:$AUTHORITATIVE_ANSWER")

        val visible = handler.messages.value
        assertEquals(1, visible.size)
        assertEquals(AUTHORITATIVE_ANSWER, visible.single().content)
        assertFalse(visible.single().isStreaming)
        assertFalse(visible.any { it.content.contains(PARTIAL_ANSWER) })
        assertFalse(visible.any { it.content.contains(FOREIGN_ANSWER) })
        assertEquals(
            "history catch-up must not duplicate the authoritative assistant row",
            1,
            compose.onAllNodesWithTag("message-$PERSISTED_ANSWER_ID").fetchSemanticsNodes().size,
        )
        assertEquals(
            "the prompt must never be resubmitted during recovery",
            1,
            fixture.rpcCount("prompt.submit"),
        )
        assertEquals(
            "the exact live session should be activated once",
            1,
            fixture.rpcCount("session.activate"),
        )
        assertEquals(0, fixture.requestsTo("/v1/chat/completions"))
    }

    @Test
    fun desktopOwnedTurn_remainsReadOnlyAcrossAndroidForegroundLifecycle() {
        viewModel.setChatVisible(false)
        viewModel.updateGatewayClient(null)
        gatewayClient.shutdown()
        gatewayScope.cancel()

        val controlMethods = setOf(
            "session.resume",
            "session.activate",
            "session.interrupt",
            "prompt.submit",
        )
        gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val okHttp = OkHttpClient()
        gatewayClient = GatewayChatClient(
            initialDashboardClient = DashboardApiClient(
                baseUrl = fixture.server.url("/").toString().trimEnd('/'),
                okHttpClient = okHttp,
            ),
            okHttpClient = okHttp,
            callbackDispatcher = { block -> Handler(Looper.getMainLooper()).post(block) },
            scope = gatewayScope,
            reconnectJitterUnit = { 0.0 },
        )
        viewModel.setChatTurnCheckpointStore(null)
        viewModel.updateGatewayClient(gatewayClient)
        assertTrue(runBlocking { gatewayClient.observeAwait() })
        serverSocket = fixture.awaitServerSocket()
        viewModel.switchProfileContext(
            AgentDisplay.profileContextKey("fixture-connection", PROFILE_NAME),
            STORED_SESSION_ID,
        )
        viewModel.updateSessionActivityDirectory(listOf(PROFILE_NAME to STORED_SESSION_ID))

        val baseline = controlMethods.associateWith(fixture::rpcCount)
        val baselineActiveList = fixture.rpcCount("session.active_list")
        fixture.activeSessionStatus = "working"

        viewModel.setChatVisible(true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        viewModel.setChatVisible(false)
        viewModel.setChatVisible(true)
        fixture.awaitRpcCount("session.active_list", baselineActiveList + 1)

        controlMethods.forEach { method ->
            assertEquals(
                "passive lifecycle sent $method",
                baseline.getValue(method),
                fixture.rpcCount(method),
            )
        }
        viewModel.updateGatewayClient(null)
        gatewayClient.shutdown()
        assertEquals(
            "observer teardown interrupted the Desktop turn",
            baseline.getValue("session.interrupt"),
            fixture.rpcCount("session.interrupt"),
        )
    }

    @Test
    fun normalCompletion_genericHistory401RetainsTranscriptAndRequiresProfileSignIn() {
        bindDashboardHistoryFailure(
            body = "Unauthorized",
            profileName = PROFILE_NAME,
        )

        viewModel.sendMessage("Keep this local transcript")
        fixture.awaitRpc("prompt.submit")
        serverSocket.send(fixture.event("message.start", null, LIVE_SESSION_ID))
        serverSocket.send(
            fixture.event(
                "message.delta",
                buildJsonObject { put("text", LOCAL_COMPLETION) },
                LIVE_SESSION_ID,
            ),
        )
        serverSocket.send(
            fixture.event(
                "message.complete",
                buildJsonObject { put("text", LOCAL_COMPLETION) },
                LIVE_SESSION_ID,
            ),
        )

        compose.waitUntil(15_000) {
            historySignInRequired.value &&
                !handler.isStreaming.value &&
                handler.messages.value.any { it.content == LOCAL_COMPLETION }
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        compose.onNodeWithTag("contract-transcript").assertIsDisplayed()
        compose.onNodeWithTag("stream-state").assertTextEquals("IDLE")
        compose.onNodeWithTag("dashboard-sign-in-recovery").assertIsDisplayed()
        assertFalse(viewModel.isLoadingHistory.value)
        assertTrue(handler.messages.value.any { it.content == "Keep this local transcript" })
        assertTrue(handler.messages.value.any { it.content == LOCAL_COMPLETION })
        assertNull(viewModel.chatFailure.value)
        assertExactProfileHistoryOnly(PROFILE_NAME)
    }

    @Test
    fun recoveredCompletion_sessionExpiredHistoryRetainsSettledTranscript() {
        bindDashboardHistoryFailure(
            body = """{"reason":"session_expired"}""",
            profileName = PROFILE_NAME,
        )
        val now = System.currentTimeMillis()
        val contextKey = AgentDisplay.profileContextKey("fixture-connection", PROFILE_NAME)
        viewModel.setChatTurnCheckpointStore(
            MemoryCheckpointStore(
                ChatTurnCheckpoint(
                    contextKey = contextKey,
                    profileKey = PROFILE_NAME,
                    sessionId = STORED_SESSION_ID,
                    liveSessionId = LIVE_SESSION_ID,
                    transport = "gateway",
                    user = ChatTurnUserCheckpoint("recovered-user", "Resume this turn", now - 2_000L),
                    assistant = ChatTurnAssistantCheckpoint(
                        id = "recovered-assistant",
                        content = "Recovered partial",
                        timestamp = now - 1_900L,
                    ),
                    priorUserMessageCount = 0,
                    baselineAssistantCount = 0,
                    startedAt = now - 2_000L,
                    updatedAt = now,
                ),
            ),
        )
        fixture.recoveryRunning = true
        handler.setSessionId(null)
        viewModel.switchProfileContext(contextKey, STORED_SESSION_ID)
        fixture.awaitRpc("session.activate")

        serverSocket.send(
            fixture.event(
                "message.delta",
                buildJsonObject { put("text", RECOVERED_COMPLETION) },
                LIVE_SESSION_ID,
            ),
        )
        serverSocket.send(
            fixture.event(
                "message.complete",
                buildJsonObject { put("text", RECOVERED_COMPLETION) },
                LIVE_SESSION_ID,
            ),
        )

        compose.waitUntil(15_000) {
            historySignInRequired.value && !handler.isStreaming.value
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        compose.onNodeWithTag("contract-transcript").assertIsDisplayed()
        compose.onNodeWithTag("stream-state").assertTextEquals("IDLE")
        compose.onNodeWithTag("dashboard-sign-in-recovery").assertIsDisplayed()
        assertFalse(viewModel.isLoadingHistory.value)
        assertTrue(
            "recovered completion was not retained: ${handler.messages.value}",
            handler.messages.value.any { it.content.contains(RECOVERED_COMPLETION.trim()) },
        )
        assertFalse(handler.messages.value.any { it.isStreaming || it.isThinkingStreaming })
        assertNull(viewModel.chatFailure.value)
        assertExactProfileHistoryOnly(PROFILE_NAME)
    }

    private fun bindDashboardHistoryFailure(body: String, profileName: String) {
        fixture.profileName = profileName
        fixture.historyFailureBody = body
        val dashboard = DashboardApiClient(
            baseUrl = fixture.server.url("/").toString().trimEnd('/'),
            okHttpClient = OkHttpClient(),
        )
        viewModel.setProfileMessageLoaderWithMode { profile, sessionId, mode ->
            dashboard.getSessionMessages(sessionId, profile, mode)
        }
        viewModel.setDashboardSignInRequiredHandler {
            historySignInRequired.value = true
        }
    }

    private fun assertExactProfileHistoryOnly(profileName: String) {
        val historyRequests = fixture.historyRequestPaths()
        assertTrue("no Dashboard history request was observed", historyRequests.isNotEmpty())
        assertTrue(
            "history escaped the exact profile: $historyRequests",
            historyRequests.all { it.contains("profile=$profileName") },
        )
    }

    private companion object {
        const val STORED_SESSION_ID = "20260821_120000_fixture"
        const val LIVE_SESSION_ID = "fixture-live-1"
        const val FOREIGN_SESSION_ID = "live-foreign"
        const val PERSISTED_ANSWER_ID = "persisted-foreground-answer"
        const val PARTIAL_ANSWER = "Partial foreground answer"
        const val AUTHORITATIVE_ANSWER = "Foreground task finished."
        const val FOREIGN_ANSWER = "Wrong session content"
        const val PROFILE_NAME = "research"
        const val LOCAL_COMPLETION = "Completed before Dashboard auth expired."
        const val RECOVERED_COMPLETION = " and then recovered to completion."
    }
}

private class MemoryCheckpointStore(
    private var checkpoint: ChatTurnCheckpoint?,
) : ChatTurnCheckpointStore {
    override suspend fun read(): ChatTurnCheckpoint? = checkpoint

    override suspend fun write(checkpoint: ChatTurnCheckpoint) {
        this.checkpoint = checkpoint
    }

    override suspend fun clear() {
        checkpoint = null
    }
}

/** Minimal real-socket implementation of the vanilla Gateway contract used above. */
internal class AndroidGatewayContractFixture {
    val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val sockets = LinkedBlockingQueue<WebSocket>()
    private val allSockets = ConcurrentLinkedQueue<WebSocket>()
    private val rpcLog = ConcurrentLinkedQueue<Pair<String, JsonObject>>()
    private val requestPaths = ConcurrentLinkedQueue<String>()
    private val ticketCount = AtomicInteger(0)

    @Volatile
    var recoveryRunning = false

    @Volatile
    var activeSessionStatus: String? = null

    @Volatile
    var historyFailureBody: String? = null

    @Volatile
    var profileName: String = "default"

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sockets.add(webSocket)
            allSockets.add(webSocket)
            webSocket.send(event("gateway.ready", null, null))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = json.parseToJsonElement(text) as? JsonObject ?: return
            val method = (frame["method"] as? JsonPrimitive)?.contentOrNull ?: return
            val id = (frame["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return
            val params = frame["params"] as? JsonObject ?: JsonObject(emptyMap())
            rpcLog.add(method to params)

            val result = when (method) {
                "session.resume" -> sessionSnapshot("fixture-live-1")
                "session.activate" -> sessionSnapshot(
                    (params["session_id"] as? JsonPrimitive)?.contentOrNull ?: "fixture-live-1",
                )
                "session.active_list" -> buildJsonObject {
                    put("sessions", kotlinx.serialization.json.buildJsonArray {
                        activeSessionStatus?.let { status ->
                            add(buildJsonObject {
                                put("id", LIVE_SESSION_ID)
                                put("session_key", STORED_SESSION_ID)
                                put("status", status)
                                put("last_active", 1.0)
                            })
                        }
                    })
                }
                "prompt.submit", "session.interrupt" -> buildJsonObject { put("ok", true) }
                else -> JsonObject(emptyMap())
            }
            webSocket.send(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", result)
                }.toString(),
            )
        }
    }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requestPaths.add(path)
                return when {
                    path.startsWith("/api/auth/ws-ticket") -> MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody(
                            """{"ticket":"device-${ticketCount.incrementAndGet()}","ttl_seconds":30}""",
                        )
                    path.startsWith("/api/ws") -> MockResponse().withWebSocketUpgrade(listener)
                    path.startsWith("/api/sessions/") && path.contains("/messages") &&
                        historyFailureBody != null -> MockResponse()
                        .setResponseCode(401)
                        .setHeader("Content-Type", "application/json")
                        .setBody(historyFailureBody.orEmpty())
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    private fun sessionSnapshot(sessionId: String): JsonObject = buildJsonObject {
        put("session_id", sessionId)
        put("running", recoveryRunning)
        put("status", if (recoveryRunning) "streaming" else "idle")
        put("info", buildJsonObject { put("profile_name", profileName) })
    }

    fun event(type: String, payload: JsonObject?, sessionId: String?): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", type)
                payload?.let { put("payload", it) }
                sessionId?.let { put("session_id", it) }
            })
        }.toString()

    fun awaitServerSocket(): WebSocket =
        sockets.poll(5, TimeUnit.SECONDS) ?: error("Gateway WebSocket did not open")

    fun awaitRpc(method: String): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            rpcLog.firstOrNull { it.first == method }?.let { return it.second }
            Thread.sleep(20)
        }
        error("Gateway RPC $method not observed; saw ${rpcLog.map { it.first }}")
    }

    fun awaitRpcCount(method: String, count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (rpcCount(method) >= count) return
            Thread.sleep(20)
        }
        error("Gateway RPC $method count $count not observed; saw ${rpcLog.map { it.first }}")
    }

    fun requestsTo(path: String): Int = requestPaths.count { it.startsWith(path) }

    fun historyRequestPaths(): List<String> = requestPaths.filter {
        it.startsWith("/api/sessions/") && it.contains("/messages")
    }

    fun rpcCount(method: String): Int = rpcLog.count { it.first == method }

    fun shutdown() {
        allSockets.forEach { socket -> runCatching { socket.close(1001, "teardown") } }
        runCatching { server.shutdown() }
    }

    private companion object {
        const val STORED_SESSION_ID = "20260821_120000_fixture"
        const val LIVE_SESSION_ID = "fixture-live-1"
    }
}
