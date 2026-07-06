package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.network.upstream.models.UsageInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [GatewayChatClient] wire tests against a scripted fake tui_gateway:
 * MockWebServer serves POST /api/auth/ws-ticket and upgrades /api/ws,
 * auto-answering session/prompt RPCs like upstream does.
 */
class GatewayClientHarness(
    autoRespond: Boolean = true,
) {
    val json = Json { ignoreUnknownKeys = true }
    val server = MockWebServer()
    val ticketMints = AtomicInteger(0)
    val serverSockets = LinkedBlockingQueue<WebSocket>()
    private val allServerSockets = ConcurrentLinkedQueue<WebSocket>()
    val rpcLog = ConcurrentLinkedQueue<Pair<String, JsonObject>>()
    var failTicketMint = false
    var resumeFails = false

    @Volatile
    var steerStatus = "queued"

    @Volatile
    var reasoningEffort = "medium"

    @Volatile
    var reasoningDisplay = "hide"

    /** Methods answered with JSON-RPC -32601 — exercises the legacy-name fallback. */
    val methodNotFound: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            serverSockets.add(webSocket)
            allServerSockets.add(webSocket)
            webSocket.send(eventFrame("gateway.ready", null, null))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = json.parseToJsonElement(text) as JsonObject
            val method = (frame["method"] as? JsonPrimitive)?.contentOrNull ?: return
            val id = (frame["id"] as? JsonPrimitive)?.contentOrNull ?: return
            val params = frame["params"] as? JsonObject ?: JsonObject(emptyMap())
            rpcLog.add(method to params)
            if (!autoRespondEnabled) return
            if (method in methodNotFound) {
                webSocket.send(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id.toLong())
                        put("error", buildJsonObject {
                            put("code", -32601)
                            put("message", "Method not found: $method")
                        })
                    }.toString(),
                )
                return
            }
            val result: JsonObject? = when (method) {
                "session.create" -> buildJsonObject {
                    put("session_id", "live-1")
                    put("stored_session_id", "20260612_120000_abc123")
                }
                "session.resume" ->
                    if (resumeFails) null
                    else buildJsonObject { put("session_id", "live-resumed") }
                "prompt.submit" -> buildJsonObject { put("ok", true) }
                "session.interrupt" -> buildJsonObject { put("ok", true) }
                "session.steer" -> buildJsonObject {
                    put("status", steerStatus)
                    put("text", (params["text"] as? JsonPrimitive)?.contentOrNull ?: "")
                }
                "image.attach_bytes", "image.attach.bytes" -> buildJsonObject {
                    put("attached", true)
                    put("count", 1)
                }
                "pdf.attach" -> buildJsonObject {
                    put("attached", true)
                    put("pages", 1)
                }
                "file.attach" -> buildJsonObject {
                    put("attached", true)
                    put("ref_text", "@file:notes.txt")
                }
                "clarify.respond", "sudo.respond", "secret.respond" ->
                    buildJsonObject { put("status", "ok") }
                "approval.respond" -> buildJsonObject { put("resolved", true) }
                "commands.catalog" -> buildJsonObject {
                    put(
                        "pairs",
                        json.parseToJsonElement("""[["/help","Show help"],["/model","Pick model"]]"""),
                    )
                }
                "model.options" -> buildJsonObject {
                    put("model", "gpt-5.5")
                    put("provider", "openai")
                    put(
                        "providers",
                        json.parseToJsonElement(
                            """
                            [
                              {
                                "slug": "openai",
                                "name": "OpenAI",
                                "models": ["gpt-5.5"],
                                "is_current": true,
                                "authenticated": true
                              }
                            ]
                            """.trimIndent(),
                        ),
                    )
                }
                "config.get" -> when ((params["key"] as? JsonPrimitive)?.contentOrNull) {
                    "reasoning" -> buildJsonObject {
                        put("value", reasoningEffort)
                        put("display", reasoningDisplay)
                    }
                    else -> JsonObject(emptyMap())
                }
                "config.set" -> when ((params["key"] as? JsonPrimitive)?.contentOrNull) {
                    "reasoning" -> {
                        reasoningEffort = (params["value"] as? JsonPrimitive)?.contentOrNull ?: reasoningEffort
                        buildJsonObject {
                            put("key", "reasoning")
                            put("value", reasoningEffort)
                        }
                    }
                    else -> JsonObject(emptyMap())
                }
                else -> JsonObject(emptyMap())
            }
            val reply = if (result != null) {
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id.toLong())
                    put("result", result)
                }
            } else {
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id.toLong())
                    put("error", buildJsonObject { put("message", "$method refused") })
                }
            }
            webSocket.send(reply.toString())
        }
    }

    private val autoRespondEnabled = autoRespond

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/api/auth/ws-ticket") -> {
                        ticketMints.incrementAndGet()
                        if (failTicketMint) {
                            MockResponse().setResponseCode(401).setBody("""{"error":"no session"}""")
                        } else {
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"ticket":"tkt-${ticketMints.get()}","ttl_seconds":30}""")
                        }
                    }
                    path.startsWith("/api/ws") -> MockResponse().withWebSocketUpgrade(wsListener)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    fun eventFrame(type: String, payload: JsonObject?, sessionId: String?): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "event")
            put("params", buildJsonObject {
                put("type", type)
                if (payload != null) put("payload", payload)
                if (sessionId != null) put("session_id", sessionId)
            })
        }.toString()

    fun awaitServerSocket(): WebSocket =
        serverSockets.poll(5, TimeUnit.SECONDS) ?: error("server socket never opened")

    fun awaitRpc(method: String): JsonObject {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            rpcLog.firstOrNull { it.first == method }?.let { return it.second }
            Thread.sleep(20)
        }
        error("rpc $method never arrived; saw ${rpcLog.map { it.first }}")
    }

    /** Waits until [method] has been seen at least [count] times; returns the params in arrival order. */
    fun awaitRpcCount(method: String, count: Int): List<JsonObject> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val seen = rpcLog.filter { it.first == method }
            if (seen.size >= count) return seen.map { it.second }
            Thread.sleep(20)
        }
        error("rpc $method x$count never arrived; saw ${rpcLog.map { it.first }}")
    }

    fun shutdown() {
        // Close any still-open server-side sockets first — an upgraded WS
        // connection otherwise occupies a MockWebServer dispatcher thread
        // and shutdown() gives up waiting for its queue. close(), not
        // cancel(): mockwebserver's server-side RealWebSocket has no `call`
        // and cancel() NPEs on it.
        allServerSockets.forEach { runCatching { it.close(1001, "teardown") } }
        try {
            server.shutdown()
        } catch (e: Throwable) {
            // Known mockwebserver limitation: shutdown can give up waiting
            // when a WS upgrade was served this test. Behaviour is asserted
            // in test bodies; teardown noise must not fail the suite.
            println("MockWebServer shutdown tolerated: ${e.message}")
        }
    }
}

class GatewayChatClientTest {

    private lateinit var harness: GatewayClientHarness
    private lateinit var scope: CoroutineScope
    private lateinit var client: GatewayChatClient
    private var unsupportedMarked = false

    private class Recorder {
        val textDeltas = ConcurrentLinkedQueue<String>()
        val thinkingDeltas = ConcurrentLinkedQueue<String>()
        val sessionIds = ConcurrentLinkedQueue<String>()
        val errors = ConcurrentLinkedQueue<String>()
        val interactions = ConcurrentLinkedQueue<GatewayAsk>()

        // ConcurrentLinkedQueue rejects nulls — unnamed generating events store "".
        val toolGenerating = ConcurrentLinkedQueue<String>()
        val subagentEvents = ConcurrentLinkedQueue<GatewaySubagentEvent>()
        val usages = ConcurrentLinkedQueue<UsageInfo>()
        val completeLatch = CountDownLatch(1)
        val preflightFailures = ConcurrentLinkedQueue<String>()

        val callbacks = GatewayTurnCallbacks(
            onSessionId = { sessionIds += it },
            onTextDelta = { textDeltas += it },
            onThinkingDelta = { thinkingDeltas += it },
            onToolCallStart = { _, _ -> },
            onToolCallDone = { _, _ -> },
            onToolCallFailed = { _, _ -> },
            onTurnComplete = { },
            onComplete = { completeLatch.countDown() },
            onUsage = { it?.let(usages::add) },
            onError = { errors += it; completeLatch.countDown() },
            onToolGenerating = { toolGenerating += it ?: "" },
            onSubagentEvent = { subagentEvents += it },
            onInteractionRequest = { interactions += it },
        )
    }

    @Before
    fun setUp() {
        harness = GatewayClientHarness()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        unsupportedMarked = false
        client = GatewayChatClient(
            initialDashboardClient = DashboardApiClient(
                baseUrl = harness.server.url("/").toString().trimEnd('/'),
                okHttpClient = OkHttpClient(),
            ),
            okHttpClient = OkHttpClient(),
            callbackDispatcher = { it() },
            onGatewayUnsupported = { unsupportedMarked = true },
            scope = scope,
            // Keep the mid-turn reconnect window short so `failed rejoin`
            // surfaces its error well within the test's await budget.
            midTurnRejoinWindowMs = 3_000L,
        )
    }

    @After
    fun tearDown() {
        client.shutdown()
        scope.cancel()
        harness.shutdown()
    }

    @Test
    fun `happy path - ticket, ready, create, submit, stream, complete`() {
        val r = Recorder()
        client.sendTurn(
            sessionId = null,
            text = "hello",
            newSessionTitle = "hello",
            callbacks = r.callbacks,
            onPreflightFailure = { r.preflightFailures += it },
        )

        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        // Verify the create carried the title and the stored id was reported.
        val create = harness.awaitRpc("session.create")
        assertEquals("hello", (create["title"] as? JsonPrimitive)?.contentOrNull)

        serverWs.send(harness.eventFrame("message.start", null, "live-1"))
        serverWs.send(
            harness.eventFrame(
                "reasoning.delta",
                buildJsonObject { put("text", "thinking hard") },
                "live-1",
            ),
        )
        serverWs.send(
            harness.eventFrame("message.delta", buildJsonObject { put("text", "Hi!") }, "live-1"),
        )
        serverWs.send(
            harness.eventFrame(
                "message.complete",
                buildJsonObject {
                    put("text", "Hi!")
                    put("status", "complete")
                    put("usage", buildJsonObject { put("input", 5); put("output", 2); put("total", 7) })
                },
                "live-1",
            ),
        )

        assertTrue("turn never completed", r.completeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("thinking hard"), r.thinkingDeltas.toList())
        assertEquals(listOf("Hi!"), r.textDeltas.toList())
        assertEquals(listOf("20260612_120000_abc123"), r.sessionIds.toList())
        assertEquals(5, r.usages.firstOrNull()?.resolvedInputTokens)
        assertTrue(r.errors.isEmpty())
        assertTrue(r.preflightFailures.isEmpty())
    }

    @Test
    fun `model options refresh flag rides gateway rpc only on explicit refresh`() = runBlocking {
        val normal = client.modelOptions().getOrThrow()
        val normalParams = harness.awaitRpc("model.options")
        assertEquals("gpt-5.5", normal.currentModel)
        assertFalse((normalParams["refresh"] as? JsonPrimitive)?.booleanOrNull == true)

        val refreshed = client.modelOptions(refresh = true).getOrThrow()
        val refreshParams = harness.awaitRpcCount("model.options", 2).last()
        assertEquals("openai", refreshed.currentProvider)
        assertTrue((refreshParams["refresh"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    @Test
    fun `foreign session events are dropped`() {
        val r = Recorder()
        client.sendTurn(null, "hello", null, r.callbacks) { r.preflightFailures += it }
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        serverWs.send(
            harness.eventFrame("message.delta", buildJsonObject { put("text", "not yours") }, "someone-else"),
        )
        serverWs.send(
            harness.eventFrame("message.complete", buildJsonObject { put("text", "yours") }, "live-1"),
        )

        assertTrue(r.completeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("yours"), r.textDeltas.toList())
    }

    @Test
    fun `existing session id is resumed not recreated`() {
        val r = Recorder()
        client.sendTurn("20260101_010101_aaaaaa", "again", null, r.callbacks) {
            r.preflightFailures += it
        }
        harness.awaitRpc("prompt.submit")

        val resume = harness.awaitRpc("session.resume")
        assertEquals(
            "20260101_010101_aaaaaa",
            (resume["session_id"] as? JsonPrimitive)?.contentOrNull,
        )
        assertTrue(harness.rpcLog.none { it.first == "session.create" })
        // Resumed sessions keep their stored id — no onSessionId rotation.
        assertTrue(r.sessionIds.isEmpty())
    }

    @Test
    fun `failed resume falls back to fresh create`() {
        harness.resumeFails = true
        val r = Recorder()
        client.sendTurn("api_123_dead", "hi", "hi", r.callbacks) { r.preflightFailures += it }
        harness.awaitRpc("session.resume")
        harness.awaitRpc("session.create")
        harness.awaitRpc("prompt.submit")
        val deadline = System.currentTimeMillis() + 5_000
        while (r.sessionIds.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf("20260612_120000_abc123"), r.sessionIds.toList())
    }

    @Test
    fun `ticket mint failure triggers preflight fallback not error`() {
        harness.failTicketMint = true
        val r = Recorder()
        client.sendTurn(null, "hello", null, r.callbacks) {
            r.preflightFailures += it
            r.completeLatch.countDown()
        }
        assertTrue(r.completeLatch.await(5, TimeUnit.SECONDS))
        assertTrue(r.preflightFailures.isNotEmpty())
        assertTrue(r.errors.isEmpty())
        assertTrue(r.textDeltas.isEmpty())
    }

    @Test
    fun `each connect attempt mints a fresh ticket`() {
        val r1 = Recorder()
        client.sendTurn(null, "one", null, r1.callbacks) { r1.preflightFailures += it }
        val ws1 = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")
        ws1.send(harness.eventFrame("message.complete", buildJsonObject { put("text", "ok") }, "live-1"))
        assertTrue(r1.completeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(1, harness.ticketMints.get())

        // Kill the socket server-side; next send must reconnect with a NEW ticket.
        ws1.close(1001, "server restart")
        Thread.sleep(200)

        harness.rpcLog.clear()
        val r2 = Recorder()
        client.sendTurn(null, "two", null, r2.callbacks) { r2.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")
        // ≥2: the reconnect minted at least one fresh ticket (a retried
        // attempt may mint a third — what matters is no reuse).
        assertTrue("expected a fresh ticket on reconnect", harness.ticketMints.get() >= 2)
    }

    @Test
    fun `cancel sends session interrupt`() {
        val r = Recorder()
        val handle = client.sendTurn(null, "long task", null, r.callbacks) {
            r.preflightFailures += it
        }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        handle.cancel()
        val interrupt = harness.awaitRpc("session.interrupt")
        assertEquals("live-1", (interrupt["session_id"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `socket loss mid-turn reconnects without resume and completes on the original session`() {
        val r = Recorder()
        client.sendTurn(null, "hello", null, r.callbacks) { r.preflightFailures += it }
        val ws1 = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        // Server connection dies mid-turn (Wi-Fi roam analogue). The client
        // must reconnect with a FRESH ticket but WITHOUT session.resume —
        // upstream resume would mint a new id + fresh agent and orphan the
        // running turn — then keep consuming the still-running turn's events,
        // which arrive tagged with the ORIGINAL live session id ("live-1")
        // on the shared gateway stream.
        harness.rpcLog.clear()
        ws1.close(1011, "server crashed")

        val ws2 = harness.awaitServerSocket()
        assertTrue("reconnect must mint a fresh ticket", harness.ticketMints.get() >= 2)

        ws2.send(
            harness.eventFrame("message.delta", buildJsonObject { put("text", "after rejoin") }, "live-1"),
        )
        ws2.send(
            harness.eventFrame("message.complete", buildJsonObject { put("text", "after rejoin") }, "live-1"),
        )

        assertTrue("turn never completed after rejoin", r.completeLatch.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("after rejoin"), r.textDeltas.toList())
        assertTrue("rejoined turn must not error, got ${r.errors}", r.errors.isEmpty())
        // The fix's core invariant: a mid-turn rejoin must NEVER session.resume.
        assertTrue(
            "mid-turn rejoin must not call session.resume",
            harness.rpcLog.none { it.first == "session.resume" },
        )
        assertTrue(r.preflightFailures.isEmpty())
    }

    @Test
    fun `failed rejoin surfaces stream error`() {
        val r = Recorder()
        client.sendTurn(null, "hello", null, r.callbacks) { r.preflightFailures += it }
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        // Reconnect is impossible (ticket mint rejected) — the turn must
        // surface a stream error rather than hang.
        harness.failTicketMint = true
        serverWs.close(1011, "server crashed")

        assertTrue(r.completeLatch.await(10, TimeUnit.SECONDS))
        assertTrue("expected stream error, got ${r.errors}", r.errors.isNotEmpty())
        assertTrue(r.preflightFailures.isEmpty())
    }

    @Test
    fun `interactive ask surfaces as structured GatewayAsk`() {
        val r = Recorder()
        client.sendTurn(null, "do something risky", null, r.callbacks) { r.preflightFailures += it }
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        serverWs.send(
            harness.eventFrame(
                "approval.request",
                buildJsonObject { put("command", "rm -rf /tmp/x"); put("description", "cleanup") },
                "live-1",
            ),
        )
        serverWs.send(
            harness.eventFrame("message.complete", buildJsonObject { put("text", "done") }, "live-1"),
        )

        assertTrue(r.completeLatch.await(5, TimeUnit.SECONDS))
        val ask = r.interactions.single()
        assertEquals(GatewayAsk.Kind.APPROVAL, ask.kind)
        assertEquals(null, ask.requestId)
        assertEquals("rm -rf /tmp/x — cleanup", ask.text)
    }

    // --- Steer ---

    @Test
    fun `steer queued when the server accepts`() {
        val r = Recorder()
        client.sendTurn(null, "long job", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        harness.steerStatus = "queued"
        assertEquals(SteerResult.Queued, runBlocking { client.steer("focus on tests") })
        val steer = harness.awaitRpc("session.steer")
        assertEquals("live-1", (steer["session_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("focus on tests", (steer["text"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `steer rejected propagates to the caller`() {
        val r = Recorder()
        client.sendTurn(null, "long job", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        harness.steerStatus = "rejected"
        assertEquals(SteerResult.Rejected, runBlocking { client.steer("too late") })
    }

    @Test
    fun `steer with no live session fails without touching the wire`() {
        assertEquals(SteerResult.Failed, runBlocking { client.steer("nothing running") })
        assertTrue(harness.rpcLog.none { it.first == "session.steer" })
    }

    // --- Profile-bound sessions (upstream tui_gateway: session.create/resume
    // take a `profile` arg; a session's agent is built from it) ---

    @Test
    fun `session create binds the selected profile`() {
        val r = Recorder()
        client.sessionProfileProvider = { "mizu" }
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        val create = harness.awaitRpc("session.create")
        assertEquals("mizu", (create["profile"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `session create omits profile when none is selected`() {
        val r = Recorder()
        // Default provider returns null → no profile bound (launch profile).
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        val create = harness.awaitRpc("session.create")
        assertEquals(null, create["profile"])
    }

    // --- Model-bound sessions (upstream tui_gateway: session.create honors
    // `model`/`provider` → the new session's model_override). Without this a
    // fresh chat ignores the in-chat picker and runs on the global default. ---

    @Test
    fun `session create binds the picked model and provider`() {
        val r = Recorder()
        client.sessionModelProvider = { GatewaySessionModel("grok-4.3", "xai") }
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        val create = harness.awaitRpc("session.create")
        assertEquals("grok-4.3", (create["model"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("xai", (create["provider"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `session create binds model without provider when provider is null`() {
        val r = Recorder()
        client.sessionModelProvider = { GatewaySessionModel("gpt-5.5", null) }
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        val create = harness.awaitRpc("session.create")
        assertEquals("gpt-5.5", (create["model"] as? JsonPrimitive)?.contentOrNull)
        assertEquals(null, create["provider"])
    }

    @Test
    fun `session create omits model when no pick`() {
        val r = Recorder()
        // Default provider returns null → no model bound (profile/server default).
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        val create = harness.awaitRpc("session.create")
        assertEquals(null, create["model"])
        assertEquals(null, create["provider"])
    }

    // --- Per-session reasoning_effort / fast binding (upstream tui_gateway:
    // session.create honors `reasoning_effort` → create_reasoning_override and
    // `fast` → priority service tier; server.py:4181-4191). Setting these before
    // a new chat's first message must ride session.create — NOT a sessionless
    // config.set, which upstream applies as a GLOBAL write. ---

    @Test
    fun `session create binds reasoning_effort and fast`() {
        val r = Recorder()
        client.sessionModelProvider = {
            GatewaySessionModel(model = "grok-4.3", provider = "xai", reasoningEffort = "high", fast = true)
        }
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        val create = harness.awaitRpc("session.create")
        assertEquals("grok-4.3", (create["model"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("high", (create["reasoning_effort"] as? JsonPrimitive)?.contentOrNull)
        assertEquals(true, (create["fast"] as? JsonPrimitive)?.booleanOrNull)
    }

    @Test
    fun `session create binds reasoning_effort and fast without a model pick`() {
        val r = Recorder()
        // No model, but the user set effort/fast before the first message — they
        // still ride session.create (the whole object is no longer model-gated).
        client.sessionModelProvider = {
            GatewaySessionModel(model = null, provider = null, reasoningEffort = "low", fast = true)
        }
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        val create = harness.awaitRpc("session.create")
        assertEquals(null, create["model"])
        assertEquals("low", (create["reasoning_effort"] as? JsonPrimitive)?.contentOrNull)
        assertEquals(true, (create["fast"] as? JsonPrimitive)?.booleanOrNull)
    }

    @Test
    fun `session create omits reasoning_effort and fast when unset`() {
        val r = Recorder()
        // Model pick only → no per-session effort/fast override, so the new
        // session inherits the profile's own reasoning + service tier.
        client.sessionModelProvider = { GatewaySessionModel(model = "gpt-5.5", provider = null) }
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        val create = harness.awaitRpc("session.create")
        assertEquals("gpt-5.5", (create["model"] as? JsonPrimitive)?.contentOrNull)
        assertFalse(create.containsKey("reasoning_effort"))
        assertFalse(create.containsKey("fast"))
    }

    @Test
    fun `session create with all overrides null binds nothing`() {
        val r = Recorder()
        // A model object whose fields are all null is treated as "no override"
        // (currentSessionModel filters it out) → a clean profile-default create.
        client.sessionModelProvider = {
            GatewaySessionModel(model = null, provider = null, reasoningEffort = null, fast = null)
        }
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        val create = harness.awaitRpc("session.create")
        assertEquals(null, create["model"])
        assertFalse(create.containsKey("reasoning_effort"))
        assertFalse(create.containsKey("fast"))
    }

    // --- Attachments (image / pdf / file routing) ---

    @Test
    fun `image attachments upload between session establish and prompt submit`() {
        val r = Recorder()
        client.sendTurn(
            sessionId = null,
            text = "describe this",
            newSessionTitle = null,
            callbacks = r.callbacks,
            attachments = listOf(GatewayAttachment(name = "shot.png", base64 = "aGVsbG8=", ext = "png", contentType = "image/png")),
            onPreflightFailure = { r.preflightFailures += it },
        )
        harness.awaitRpc("prompt.submit")

        val methods = harness.rpcLog.map { it.first }
        val createIdx = methods.indexOf("session.create")
        val attachIdx = methods.indexOf("image.attach_bytes")
        val submitIdx = methods.indexOf("prompt.submit")
        assertTrue("expected create < attach < submit, got $methods", createIdx in 0 until attachIdx)
        assertTrue("expected attach before submit, got $methods", attachIdx < submitIdx)

        val attach = harness.awaitRpc("image.attach_bytes")
        assertEquals("live-1", (attach["session_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("aGVsbG8=", (attach["content_base64"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("shot.png", (attach["filename"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("png", (attach["ext"] as? JsonPrimitive)?.contentOrNull)
        assertTrue(r.preflightFailures.isEmpty())
    }

    @Test
    fun `attach falls back to the legacy name on method-not-found and remembers it`() {
        harness.methodNotFound.add("image.attach_bytes")
        val r1 = Recorder()
        client.sendTurn(
            sessionId = null,
            text = "one",
            newSessionTitle = null,
            callbacks = r1.callbacks,
            attachments = listOf(GatewayAttachment("a.png", "QQ==", "png", "image/png")),
            onPreflightFailure = { r1.preflightFailures += it },
        )
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        val legacy = harness.awaitRpc("image.attach.bytes")
        assertEquals("live-1", (legacy["session_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("QQ==", (legacy["bytes_base64"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("png", (legacy["format"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("a.png", (legacy["filename_hint"] as? JsonPrimitive)?.contentOrNull)
        assertTrue(r1.preflightFailures.isEmpty())

        serverWs.send(harness.eventFrame("message.complete", buildJsonObject { put("text", "ok") }, "live-1"))
        assertTrue(r1.completeLatch.await(5, TimeUnit.SECONDS))

        // Same socket: the second upload must go straight to the legacy name —
        // no second probe of the upstream name.
        val r2 = Recorder()
        client.sendTurn(
            sessionId = "20260612_120000_abc123",
            text = "two",
            newSessionTitle = null,
            callbacks = r2.callbacks,
            attachments = listOf(GatewayAttachment("b.png", "Qg==", "png", "image/png")),
            onPreflightFailure = { r2.preflightFailures += it },
        )
        harness.awaitRpcCount("image.attach.bytes", 2)
        assertEquals(1, harness.rpcLog.count { it.first == "image.attach_bytes" })
        assertTrue(r2.preflightFailures.isEmpty())
    }

    @Test
    fun `attach failing on both names surfaces as preflight fallback`() {
        harness.methodNotFound.add("image.attach_bytes")
        harness.methodNotFound.add("image.attach.bytes")
        val r = Recorder()
        client.sendTurn(
            sessionId = null,
            text = "img",
            newSessionTitle = null,
            callbacks = r.callbacks,
            attachments = listOf(GatewayAttachment("a.png", "QQ==", "png", "image/png")),
            onPreflightFailure = {
                r.preflightFailures += it
                r.completeLatch.countDown()
            },
        )
        assertTrue(r.completeLatch.await(5, TimeUnit.SECONDS))
        assertTrue(r.preflightFailures.isNotEmpty())
        // Nothing started server-side — the prompt was never submitted.
        assertTrue(harness.rpcLog.none { it.first == "prompt.submit" })
        assertTrue(r.errors.isEmpty())
    }

    @Test
    fun `pdf attachments route to pdf attach with content_base64`() {
        val r = Recorder()
        client.sendTurn(
            sessionId = null,
            text = "summarize this",
            newSessionTitle = null,
            callbacks = r.callbacks,
            attachments = listOf(
                GatewayAttachment(name = "report.pdf", base64 = "JVBERi0=", ext = "pdf", contentType = "application/pdf"),
            ),
            onPreflightFailure = { r.preflightFailures += it },
        )
        harness.awaitRpc("prompt.submit")

        val attach = harness.awaitRpc("pdf.attach")
        assertEquals("live-1", (attach["session_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("JVBERi0=", (attach["content_base64"] as? JsonPrimitive)?.contentOrNull)
        // No image RPC should have fired for a PDF.
        assertTrue(harness.rpcLog.none { it.first == "image.attach_bytes" })
        assertTrue(r.preflightFailures.isEmpty())
    }

    @Test
    fun `non-image non-pdf attachments route to file attach with a data url`() {
        val r = Recorder()
        client.sendTurn(
            sessionId = null,
            text = "read this",
            newSessionTitle = null,
            callbacks = r.callbacks,
            attachments = listOf(
                GatewayAttachment(name = "notes.txt", base64 = "aGk=", ext = "txt", contentType = "text/plain"),
            ),
            onPreflightFailure = { r.preflightFailures += it },
        )
        harness.awaitRpc("prompt.submit")

        val attach = harness.awaitRpc("file.attach")
        assertEquals("live-1", (attach["session_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals(
            "data:text/plain;base64,aGk=",
            (attach["data_url"] as? JsonPrimitive)?.contentOrNull,
        )
        assertEquals("notes.txt", (attach["name"] as? JsonPrimitive)?.contentOrNull)
        assertTrue(harness.rpcLog.none { it.first == "image.attach_bytes" })
        assertTrue(harness.rpcLog.none { it.first == "pdf.attach" })
        assertTrue(r.preflightFailures.isEmpty())
    }

    // --- Ask responders ---

    @Test
    fun `clarify respond carries request id and answer`() {
        val r = Recorder()
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        assertTrue(runBlocking { client.respondClarify("r1", "use a.txt") }.isSuccess)
        val respond = harness.awaitRpc("clarify.respond")
        assertEquals("r1", (respond["request_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("use a.txt", (respond["answer"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `sudo and secret responds carry request id under their key names`() {
        val r = Recorder()
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        assertTrue(runBlocking { client.respondSudo("r2", "hunter2") }.isSuccess)
        val sudo = harness.awaitRpc("sudo.respond")
        assertEquals("r2", (sudo["request_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("hunter2", (sudo["password"] as? JsonPrimitive)?.contentOrNull)

        assertTrue(runBlocking { client.respondSecret("r3", "sk-123") }.isSuccess)
        val secret = harness.awaitRpc("secret.respond")
        assertEquals("r3", (secret["request_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("sk-123", (secret["value"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `approval respond targets the live session`() {
        val r = Recorder()
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        assertTrue(runBlocking { client.respondApproval("approve") }.isSuccess)
        val respond = harness.awaitRpc("approval.respond")
        assertEquals("live-1", (respond["session_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("approve", (respond["choice"] as? JsonPrimitive)?.contentOrNull)
        assertEquals(false, (respond["all"] as? JsonPrimitive)?.booleanOrNull)
    }

    // --- Commands catalog ---

    @Test
    fun `commands catalog is fetched once and cached per socket`() {
        val r = Recorder()
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        val first = runBlocking { client.commandsCatalog() }
        assertTrue(first.isSuccess)
        assertTrue(first.getOrThrow().containsKey("pairs"))
        val second = runBlocking { client.commandsCatalog() }
        assertTrue(second.isSuccess)
        assertEquals(1, harness.rpcLog.count { it.first == "commands.catalog" })
    }

    @Test
    fun `commands catalog without a ready socket fails fast and mints no ticket`() {
        val result = runBlocking { client.commandsCatalog() }
        assertTrue(result.isFailure)
        assertEquals(0, harness.ticketMints.get())
    }

    @Test
    fun `commands catalog connects on demand only when asked`() {
        val result = runBlocking { client.commandsCatalog(connectIfNeeded = true) }
        assertTrue(result.isSuccess)
        assertTrue(harness.ticketMints.get() >= 1)
    }

    // --- Reasoning config ---

    @Test
    fun `reasoning settings fetch uses config get`() {
        harness.reasoningEffort = "high"
        harness.reasoningDisplay = "show"

        val result = runBlocking { client.getReasoningSettings() }

        assertTrue(result.isSuccess)
        assertEquals("high", result.getOrThrow().effort)
        assertEquals("show", result.getOrThrow().display)
        val rpc = harness.awaitRpc("config.get")
        assertEquals("reasoning", (rpc["key"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `reasoning settings update targets live session when present`() {
        val r = Recorder()
        client.sendTurn("stored-1", "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("session.resume")
        harness.awaitRpc("prompt.submit")

        val result = runBlocking { client.setReasoning("low") }

        assertTrue(result.isSuccess)
        val rpc = harness.awaitRpc("config.set")
        assertEquals("reasoning", (rpc["key"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("low", (rpc["value"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("live-resumed", (rpc["session_id"] as? JsonPrimitive)?.contentOrNull)
    }

    // --- Edit & regenerate ---

    @Test
    fun `truncate ordinal rides prompt submit`() {
        val r = Recorder()
        client.sendTurn(
            sessionId = null,
            text = "edited message",
            newSessionTitle = null,
            callbacks = r.callbacks,
            truncateBeforeUserOrdinal = 2,
            onPreflightFailure = { r.preflightFailures += it },
        )
        val submit = harness.awaitRpc("prompt.submit")
        assertEquals(2, (submit["truncate_before_user_ordinal"] as? JsonPrimitive)?.intOrNull)
    }

    @Test
    fun `truncate ordinal is absent from plain sends`() {
        val r = Recorder()
        client.sendTurn(null, "plain", null, r.callbacks) { r.preflightFailures += it }
        val submit = harness.awaitRpc("prompt.submit")
        assertFalse(submit.containsKey("truncate_before_user_ordinal"))
    }
}
