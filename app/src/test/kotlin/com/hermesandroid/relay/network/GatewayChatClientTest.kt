package com.hermesandroid.relay.network

import com.hermesandroid.relay.network.models.UsageInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
        val interactions = ConcurrentLinkedQueue<String>()
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
            onInteractionRequest = { kind, _ -> interactions += kind },
        )
    }

    @Before
    fun setUp() {
        harness = GatewayClientHarness()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        unsupportedMarked = false
        client = GatewayChatClient(
            dashboardClient = DashboardApiClient(
                baseUrl = harness.server.url("/").toString().trimEnd('/'),
                okHttpClient = OkHttpClient(),
            ),
            okHttpClient = OkHttpClient(),
            callbackDispatcher = { it() },
            onGatewayUnsupported = { unsupportedMarked = true },
            scope = scope,
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
    fun `socket failure mid-turn surfaces stream error`() {
        val r = Recorder()
        client.sendTurn(null, "hello", null, r.callbacks) { r.preflightFailures += it }
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        // Server goes away mid-turn (cancel() NPEs on mockwebserver's
        // server-side sockets, so a server-initiated close stands in for the
        // drop — the client sees the socket vanish mid-turn either way).
        serverWs.close(1011, "server crashed")

        assertTrue(r.completeLatch.await(10, TimeUnit.SECONDS))
        assertTrue("expected stream error, got ${r.errors}", r.errors.isNotEmpty())
        assertTrue(r.preflightFailures.isEmpty())
    }

    @Test
    fun `interactive ask surfaces as interaction`() {
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
        assertEquals(listOf("approval"), r.interactions.toList())
    }
}
