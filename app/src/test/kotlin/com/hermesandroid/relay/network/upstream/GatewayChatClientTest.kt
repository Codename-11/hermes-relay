package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.network.upstream.models.UsageInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

    /** Authoritative profile owner echoed by session results; null follows request. */
    @Volatile
    var sessionProfileOverride: String? = null

    @Volatile
    var omitSessionProfileMetadata = false

    @Volatile
    var recoveryRunning = false

    @Volatile
    var recoveryAssistant = ""

    @Volatile
    var recoveryInflightStreaming: Boolean? = null
    var recoveryInflightError: String? = null
    var recoveryInflightRecoverable: Boolean = false
    var recoveryInflightCorrections: List<String> = emptyList()
    var recoveryAutoContinueAttempt: Int? = null

    @Volatile
    var recoveryQueuedUser: String? = null

    @Volatile
    var recoveryProject: JsonObject? = null

    /** Optional durable-id -> live-id mapping for multi-session switch tests. */
    val resumeLiveSessionIds = ConcurrentHashMap<String, String>()

    @Volatile
    var steerStatus = "queued"

    @Volatile
    var redirectStatus = "redirected"

    @Volatile
    var compressPayload: JsonObject = buildJsonObject {
        put("status", "completed")
        put("removed", 2)
        put("before_messages", 8)
        put("after_messages", 4)
    }

    @Volatile
    var promptSubmitPayload: JsonObject = buildJsonObject { put("ok", true) }

    @Volatile
    var reasoningEffort = "medium"

    @Volatile
    var reasoningDisplay = "hide"

    @Volatile
    var approvalMode = "smart"

    @Volatile
    var modelConfirmationMessage: String? = null
    val modelsRequiringConfirmation: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    var createdSessionProfileName: String? = null

    /** Config keys rejected with the older-gateway unknown-key response. */
    val unsupportedConfigKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    var askResponseStatus = "ok"

    @Volatile
    var approvalResolved = 1

    @Volatile
    var petThumbPayload: JsonObject = buildJsonObject {
        put("ok", true)
        put("slug", "boba")
        put("dataUri", "data:image/png;base64,iVBORw0KGgo=")
    }

    @Volatile
    var petInfoPayload: JsonObject = buildJsonObject {
        put("enabled", true)
        put("slug", "boba")
        put("displayName", "Boba")
        put("mime", "image/png")
        put("spritesheetBase64", "iVBORw0KGgo=")
        put("spritesheetRevision", "123:8")
        put("frameW", 192)
        put("frameH", 208)
        put("framesPerState", 8)
        put("framesByState", buildJsonObject { put("idle", 6); put("run", 8) })
        put("framesByRow", buildJsonObject { put("idle", 6); put("running", 8) })
        put("loopMs", 1100)
        put("scale", 0.75)
        put("stateRows", JsonArray(listOf(JsonPrimitive("idle"), JsonPrimitive("running"))))
    }

    @Volatile
    var petGalleryPayload: JsonObject = buildJsonObject {
        put("enabled", true)
        put("active", "boba")
        put("pets", JsonArray(listOf(buildJsonObject {
            put("slug", "boba")
            put("displayName", "Boba")
            put("installed", true)
            put("spritesheetUrl", "https://assets.petdex.dev/pets/boba/sprites.png")
            put("curated", true)
            put("generated", false)
        })))
    }

    @Volatile
    var fileAttachPayload: JsonObject = buildJsonObject {
        put("attached", true)
        put("ref_text", "@file:notes.txt")
    }

    @Volatile
    var profilesListPayload: JsonObject = buildJsonObject {
        put("profiles", JsonArray(listOf(buildJsonObject {
            put("name", "operator")
            put("model", "gpt-5.6")
            put("provider", "openai")
            put("description", "Android operator")
            put("skill_count", 3)
            put("has_avatar", true)
            put("ui_meta", buildJsonObject { put("accent", "#ff5500") })
        })))
    }

    @Volatile
    var sessionListPayload: JsonObject = buildJsonObject {
        put("sessions", JsonArray(emptyList()))
    }

    @Volatile
    var profileCreatePayload: JsonObject = buildJsonObject {
        put("ok", true)
        put("name", "operator")
        put("soul_written", true)
        put("model_set", true)
        put("mirrored", buildJsonObject {
            put("env", true)
            put("auth", "shared")
            put("model_inherited", false)
            put("voice", true)
        })
    }

    @Volatile
    var profileGetAssetPayload: JsonObject = buildJsonObject { put("found", false) }

    /** Methods answered with JSON-RPC -32601 — exercises the legacy-name fallback. */
    val methodNotFound: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Methods answered with a caller-selected structured JSON-RPC error. */
    val rpcErrors = ConcurrentHashMap<String, Pair<Int, String>>()

    /** One withheld JSON-RPC ack, capturable for delayed release via [releaseAck]. */
    class PendingAck(val ws: WebSocket, val method: String, val id: Long)

    /** Methods whose ack is WITHHELD (queued in [pendingAcks]) instead of auto-answered —
     * models upstream's fire-and-forget `prompt.submit`, whose ack can trail the turn. */
    val suppressAckMethods: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val pendingAcks = LinkedBlockingQueue<PendingAck>()

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
            if (method in suppressAckMethods) {
                pendingAcks.add(PendingAck(webSocket, method, id.toLong()))
                return
            }
            rpcErrors[method]?.let { (code, message) ->
                webSocket.send(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id.toLong())
                        put("error", buildJsonObject {
                            put("code", code)
                            put("message", message)
                        })
                    }.toString(),
                )
                return
            }
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
            val configKey = (params["key"] as? JsonPrimitive)?.contentOrNull
            if (
                (method == "config.get" || method == "config.set") &&
                configKey in unsupportedConfigKeys
            ) {
                webSocket.send(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", id.toLong())
                        put("error", buildJsonObject {
                            put("code", 4002)
                            put("message", "unknown config key: $configKey")
                        })
                    }.toString(),
                )
                return
            }
            val result: JsonObject? = when (method) {
                "session.create" -> buildJsonObject {
                    put("session_id", "live-1")
                    put("stored_session_id", "20260612_120000_abc123")
                    if (!omitSessionProfileMetadata) {
                        put("info", buildJsonObject {
                            put(
                                "profile_name",
                                createdSessionProfileName
                                    ?: sessionProfileOverride
                                    ?: (params["profile"] as? JsonPrimitive)?.contentOrNull
                                    ?: "default",
                            )
                        })
                    }
                }
                "session.resume" ->
                    if (resumeFails) null
                    else {
                        val storedId = (params["session_id"] as? JsonPrimitive)?.contentOrNull
                        recoveryPayload(
                            resumeLiveSessionIds[storedId] ?: "live-resumed",
                            (params["profile"] as? JsonPrimitive)?.contentOrNull,
                        )
                    }
                "session.activate" -> recoveryPayload(
                    (params["session_id"] as? JsonPrimitive)?.contentOrNull ?: "live-activated",
                )
                "session.list" -> sessionListPayload
                "session.title" -> buildJsonObject { put("ok", true) }
                "prompt.submit" -> promptSubmitPayload
                "session.interrupt" -> buildJsonObject { put("ok", true) }
                "process.list" -> buildJsonObject {
                    put(
                        "processes",
                        json.parseToJsonElement(
                            """
                            [
                              {
                                "session_id": "proc-17",
                                "command": "./gradlew test",
                                "cwd": "/workspace/app",
                                "pid": 4812,
                                "started_at": "2026-07-10T09:30:00",
                                "uptime_seconds": 42,
                                "status": "running",
                                "output_preview": "running tests",
                                "output_tail": "running tests\n42 tests completed",
                                "notify_on_complete": true,
                                "session_scoped": true,
                                "watch_patterns": ["BUILD SUCCESSFUL"],
                                "watch_hit": false
                              },
                              {
                                "session_id": "proc-18",
                                "command": "npm run lint",
                                "uptime_seconds": 7,
                                "status": "exited",
                                "exit_code": 1,
                                "detached": true
                              }
                            ]
                            """.trimIndent(),
                        ),
                    )
                }
                "process.kill" -> buildJsonObject { put("status", "killed") }
                "session.redirect" -> buildJsonObject {
                    put("status", redirectStatus)
                    put("text", (params["text"] as? JsonPrimitive)?.contentOrNull ?: "")
                }
                "session.steer" -> buildJsonObject {
                    put("status", steerStatus)
                    put("text", (params["text"] as? JsonPrimitive)?.contentOrNull ?: "")
                }
                "subagent.steer" -> buildJsonObject { put("status", steerStatus) }
                "message.react" -> buildJsonObject { put("row_id", 17) }
                "session.compress" -> compressPayload
                "slash.exec" -> buildJsonObject {
                    put("output", "legacy compression started")
                }
                "image.attach_bytes", "image.attach.bytes" -> buildJsonObject {
                    put("attached", true)
                    put("count", 1)
                    put("path", "/session/images/upload.png")
                }
                "pdf.attach" -> buildJsonObject {
                    put("attached", true)
                    put("pages", JsonArray(listOf(buildJsonObject {
                        put("path", "/session/images/page-1.png")
                        put("page", 1)
                    })))
                }
                "file.attach" -> fileAttachPayload
                "clarify.respond", "sudo.respond", "secret.respond" ->
                    buildJsonObject { put("status", askResponseStatus) }
                "approval.respond" -> buildJsonObject { put("resolved", approvalResolved) }
                "commands.catalog" -> buildJsonObject {
                    put(
                        "pairs",
                        json.parseToJsonElement("""[["/help","Show help"],["/model","Pick model"]]"""),
                    )
                }
                "profiles.describe" -> buildJsonObject {
                    put("name", (params["name"] as? JsonPrimitive)?.contentOrNull ?: "")
                    put("description", "Android operator")
                    put("soul", "# Operator")
                    put("model", buildJsonObject {
                        put("provider", "openai")
                        put("default", "gpt-5.6")
                    })
                    put("skills", JsonArray(listOf(buildJsonObject {
                        put("name", "weather")
                        put("enabled", false)
                    })))
                    put("toolsets", JsonArray(listOf(buildJsonObject {
                        put("name", "terminal")
                        put("description", "Run commands")
                        put("tool_count", 4)
                        put("enabled", true)
                    })))
                    put("toolsets_pinned", true)
                }
                "profiles.list" -> profilesListPayload
                "profiles.create" -> profileCreatePayload
                "profiles.get_asset" -> profileGetAssetPayload
                "profiles.set_asset" -> buildJsonObject {
                    put("ok", true)
                    put("asset", "avatar")
                    val data = (params["data"] as? JsonPrimitive)?.contentOrNull
                    put(
                        "size",
                        if ((params["clear"] as? JsonPrimitive)?.booleanOrNull == true) {
                            0
                        } else {
                            java.util.Base64.getDecoder().decode(data.orEmpty().substringAfter(";base64,")).size
                        },
                    )
                }
                "profiles.configure" -> buildJsonObject {
                    put("ok", false)
                    put("applied", buildJsonObject {
                        if (params.containsKey("description")) put("description", true)
                        if (params.containsKey("provider")) put("model", false)
                        if (params.containsKey("disabled_skills")) put("skills", true)
                        if (params.containsKey("enabled_toolsets")) put("toolsets", true)
                        if (params.containsKey("enabled_mcp_servers")) put("mcp_servers", true)
                        if (params.containsKey("ui_meta")) put("ui_meta", false)
                    })
                }
                "pet.thumb" -> petThumbPayload
                "pet.info" -> petInfoPayload
                "pet.gallery" -> petGalleryPayload
                "pet.select", "pet.disable" -> buildJsonObject { put("ok", true) }
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
                                "capabilities": {
                                  "gpt-5.5": {
                                    "reasoning": true,
                                    "reasoning_efforts": ["minimal", "medium", "ultra"]
                                  }
                                },
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
                    "approvals.mode" -> buildJsonObject { put("value", approvalMode) }
                    else -> JsonObject(emptyMap())
                }
                "config.set" -> when ((params["key"] as? JsonPrimitive)?.contentOrNull) {
                    "model" -> {
                        val confirmation = modelConfirmationMessage
                        val requestedValue = (params["value"] as? JsonPrimitive)?.contentOrNull ?: ""
                        val confirmed = (params["confirm_expensive_model"] as? JsonPrimitive)
                            ?.booleanOrNull == true
                        if (confirmation != null && requestedValue in modelsRequiringConfirmation && !confirmed) {
                            buildJsonObject {
                                put("confirm_required", true)
                                put("confirm_message", confirmation)
                            }
                        } else {
                            buildJsonObject {
                                put("key", "model")
                                put("value", requestedValue)
                            }
                        }
                    }
                    "reasoning" -> {
                        reasoningEffort = (params["value"] as? JsonPrimitive)?.contentOrNull ?: reasoningEffort
                        buildJsonObject {
                            put("key", "reasoning")
                            put("value", reasoningEffort)
                        }
                    }
                    "fast" -> buildJsonObject {
                        put("key", "fast")
                        put("value", (params["value"] as? JsonPrimitive)?.contentOrNull ?: "normal")
                    }
                    "yolo" -> buildJsonObject {
                        put("key", "yolo")
                        put("value", (params["value"] as? JsonPrimitive)?.contentOrNull ?: "0")
                    }
                    "approvals.mode" -> {
                        approvalMode =
                            (params["value"] as? JsonPrimitive)?.contentOrNull ?: approvalMode
                        buildJsonObject {
                            put("key", "approvals.mode")
                            put("value", approvalMode)
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

    private fun recoveryPayload(sessionId: String, requestedProfile: String? = null): JsonObject = buildJsonObject {
        put("session_id", sessionId)
        put("running", recoveryRunning)
        put("status", if (recoveryRunning) "streaming" else "idle")
        if (!omitSessionProfileMetadata || recoveryProject != null) {
            put("info", buildJsonObject {
                if (!omitSessionProfileMetadata) {
                    put("profile_name", sessionProfileOverride ?: requestedProfile ?: "default")
                }
                recoveryProject?.let { put("project", it) }
            })
        }
        val inflightStreaming = recoveryInflightStreaming ?: recoveryRunning
        if (recoveryRunning || recoveryInflightStreaming != null || recoveryInflightError != null) {
            put("inflight", buildJsonObject {
                put("user", "research this")
                put("assistant", recoveryAssistant)
                put("streaming", inflightStreaming)
                if (recoveryInflightCorrections.isNotEmpty()) {
                    put(
                        "corrections",
                        JsonArray(recoveryInflightCorrections.map(::JsonPrimitive)),
                    )
                }
                recoveryInflightError?.let { error ->
                    put("status", "error")
                    put("error", error)
                    put("recoverable", recoveryInflightRecoverable)
                }
            })
        }
        recoveryQueuedUser?.let { user ->
            put("queued", buildJsonObject { put("user", user) })
        }
        recoveryAutoContinueAttempt?.let { attempt ->
            put("auto_continue", buildJsonObject {
                put("attempt", attempt)
                put("interrupted_at", 1_700_000_000.0)
            })
        }
    }

    fun recoveryResult(sessionId: String): JsonObject = recoveryPayload(sessionId)

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

    fun awaitPendingAck(): PendingAck =
        pendingAcks.poll(5, TimeUnit.SECONDS) ?: error("suppressed ack never captured")

    /** Release a withheld ack with a caller-supplied or generic success result. */
    fun releaseAck(
        ack: PendingAck,
        result: JsonObject = buildJsonObject { put("ok", true) },
    ) {
        ack.ws.send(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", ack.id)
                put("result", result)
            }.toString(),
        )
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
    @Test
    fun `personality completion parser keeps configured names and excludes none`() {
        val result = buildJsonObject {
            put("items", JsonArray(listOf(
                buildJsonObject {
                    put("text", "none")
                    put("meta", "clear personality overlay")
                },
                buildJsonObject {
                    put("text", "coder")
                    put("meta", "Expert programmer")
                },
                buildJsonObject {
                    put("text", "coach")
                    put("meta", "Supportive coach")
                },
            )))
        }

        assertEquals(listOf("coder", "coach"), parseGatewayPersonalityOptions(result))
    }


    private lateinit var harness: GatewayClientHarness
    private lateinit var scope: CoroutineScope
    private lateinit var client: GatewayChatClient
    private var unsupportedMarked = false

    private class Recorder {
        val starts = AtomicInteger(0)
        val textDeltas = ConcurrentLinkedQueue<String>()
        val thinkingDeltas = ConcurrentLinkedQueue<String>()
        val sessionIds = ConcurrentLinkedQueue<String>()
        val errors = ConcurrentLinkedQueue<String>()
        val resumeFailures = ConcurrentLinkedQueue<String>()
        val interactions = ConcurrentLinkedQueue<GatewayAsk>()
        val interactionExpiries = ConcurrentLinkedQueue<GatewayAskExpiry>()
        val toolStarts = ConcurrentLinkedQueue<Pair<String, String>>()
        val toolDone = ConcurrentLinkedQueue<Pair<String, String?>>()

        // ConcurrentLinkedQueue rejects nulls — unnamed generating events store "".
        val toolGenerating = ConcurrentLinkedQueue<String>()
        val subagentEvents = ConcurrentLinkedQueue<GatewaySubagentEvent>()
        val moaReferences = ConcurrentLinkedQueue<GatewayMoaReference>()
        val usages = ConcurrentLinkedQueue<UsageInfo>()
        val reconcileRequests = AtomicInteger(0)
        val completeLatch = CountDownLatch(1)
        val preflightFailures = ConcurrentLinkedQueue<String>()

        val callbacks = GatewayTurnCallbacks(
            onSessionId = { sessionIds += it },
            onStart = { starts.incrementAndGet() },
            onTextDelta = { textDeltas += it },
            onThinkingDelta = { thinkingDeltas += it },
            onToolCallStart = { id, name, _ -> toolStarts += id to name },
            onToolCallDone = { id, result -> toolDone += id to result },
            onToolCallFailed = { _, _ -> },
            onTurnComplete = { },
            onReconcileRequired = { reconcileRequests.incrementAndGet() },
            onComplete = { completeLatch.countDown() },
            onUsage = { it?.let(usages::add) },
            onError = { errors += it; completeLatch.countDown() },
            onToolGenerating = { toolGenerating += it ?: "" },
            onSubagentEvent = { subagentEvents += it },
            onMoaReference = { moaReferences += it },
            onInteractionRequest = { interactions += it },
            onInteractionExpired = { interactionExpiries += it },
            onResumeFailure = { resumeFailures += it; completeLatch.countDown() },
            onStatusUpdate = { _, _ -> },
            onStatusClear = { },
        )
    }

    private fun buildClient(
        rpcTimeoutMs: Long = 15_000L,
        promptSubmitTimeoutMs: Long = 1_800_000L,
        turnIdleTimeoutMs: Long = 180_000L,
    ) = GatewayChatClient(
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
        rpcTimeoutMs = rpcTimeoutMs,
        promptSubmitTimeoutMs = promptSubmitTimeoutMs,
        turnIdleTimeoutMs = turnIdleTimeoutMs,
    )

    private fun awaitCondition(
        timeoutMs: Long = 5_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("condition did not settle within ${timeoutMs}ms", condition())
    }

    /**
     * Swap in a client with shortened timeout seams. Mints a FRESH scope:
     * shutdown() cancels the scope's Job, and the replacement client must
     * still be able to launch its sendTurn coroutines.
     */
    private fun rebuildClient(
        rpcTimeoutMs: Long = 15_000L,
        promptSubmitTimeoutMs: Long = 1_800_000L,
        turnIdleTimeoutMs: Long = 180_000L,
    ) {
        client.shutdown()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        client = buildClient(rpcTimeoutMs, promptSubmitTimeoutMs, turnIdleTimeoutMs)
    }

    private fun waitUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("condition did not settle within ${timeoutMs}ms", condition())
    }

    @Before
    fun setUp() {
        harness = GatewayClientHarness()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        unsupportedMarked = false
        client = buildClient()
    }

    @After
    fun tearDown() {
        client.shutdown()
        scope.cancel()
        harness.shutdown()
    }

    @Test
    fun `profile editor describes exact profile and maps upstream shape`() = runBlocking {
        val description = client.describeProfile("operator").getOrThrow()

        assertEquals("operator", description.name)
        assertEquals("openai", description.provider)
        assertEquals("gpt-5.6", description.model)
        assertFalse(description.skills.single().enabled)
        assertEquals(4, description.toolsets.single().toolCount)
        assertTrue(description.toolsetsPinned)
        assertEquals(
            "operator",
            (harness.awaitRpc("profiles.describe")["name"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    @Test
    fun `profile configure is gated by describe and reports every requested section`() = runBlocking {
        assertTrue(client.configureProfile("operator", com.hermesandroid.relay.data.GatewayProfilePatch(description = "x")).isFailure)
        client.describeProfile("operator").getOrThrow()

        val result = client.configureProfile(
            "operator",
            com.hermesandroid.relay.data.GatewayProfilePatch(
                description = "Updated",
                provider = "openai",
                model = "gpt-5.6-sol",
            ),
        ).getOrThrow()

        assertEquals(setOf(com.hermesandroid.relay.data.GatewayProfileSection.Description), result.applied)
        assertEquals(setOf(com.hermesandroid.relay.data.GatewayProfileSection.Model), result.failed)
    }

    @Test
    fun `profile describe method not found becomes sticky unsupported capability`() = runBlocking {
        harness.methodNotFound += "profiles.describe"

        assertTrue(client.describeProfile("operator").exceptionOrNull() is com.hermesandroid.relay.data.GatewayProfileEditorUnsupportedException)
        harness.rpcLog.clear()
        assertTrue(client.describeProfile("operator").exceptionOrNull() is com.hermesandroid.relay.data.GatewayProfileEditorUnsupportedException)
        assertTrue(harness.rpcLog.none { it.first == "profiles.describe" })
    }

    @Test
    fun `profile configure method not found becomes sticky read only capability`() = runBlocking {
        client.describeProfile("operator").getOrThrow()
        harness.methodNotFound += "profiles.configure"

        assertTrue(
            client.configureProfile(
                "operator",
                com.hermesandroid.relay.data.GatewayProfilePatch(description = "Updated"),
            ).exceptionOrNull() is com.hermesandroid.relay.data.GatewayProfileEditorUnsupportedException,
        )
        harness.rpcLog.clear()
        assertTrue(
            client.configureProfile(
                "operator",
                com.hermesandroid.relay.data.GatewayProfilePatch(description = "Again"),
            ).exceptionOrNull() is com.hermesandroid.relay.data.GatewayProfileEditorUnsupportedException,
        )
        assertTrue(harness.rpcLog.none { it.first == "profiles.configure" })
        assertEquals("operator", client.describeProfile("operator").getOrThrow().name)
    }

    @Test
    fun `profile list requests bounded roster metadata without sessions`() = runBlocking {
        val profiles = client.listProfiles().getOrThrow()

        assertEquals(1, profiles.size)
        assertEquals("operator", profiles.single().name)
        assertEquals("openai", profiles.single().provider)
        assertTrue(profiles.single().hasAvatar)
        assertEquals("#ff5500", (profiles.single().uiMeta["accent"] as JsonPrimitive).content)
        assertEquals(false, (harness.awaitRpc("profiles.list")["include_sessions"] as JsonPrimitive).booleanOrNull)
    }

    @Test
    fun `bot roster parses canonical chats activity and read only room projection`() = runBlocking {
        harness.profilesListPayload = buildJsonObject {
            put("bot_mode_protocol", true)
            put("profiles", JsonArray(listOf(buildJsonObject {
                put("name", "default")
                put("display_name", "Hermes")
                put("model", "gpt-5.6")
                put("is_default", true)
                put("canonical_session", buildJsonObject {
                    put("id", "bot-root")
                    put("resolved_id", "bot-tip")
                    put("root_title", "Bot Chat")
                    put("preview", "Release plan ready")
                    put("last_active", 1_777_000_000)
                    put("message_count", 8)
                })
                put("worker_session", buildJsonObject {
                    put("id", "worker-1")
                    put("title", "Build")
                    put("last_active", 1_777_000_030)
                })
                put("ui_meta", buildJsonObject {
                    put("hermes-bots", buildJsonObject { put("title", "Lucy") })
                    put("hermes-bots-groups", buildJsonObject {
                        put("version", 3)
                        put("rooms", buildJsonObject {
                            put("id:launch", buildJsonObject {
                                put("name", "Launch Council")
                                put("roomId", "launch")
                                put("revision", 4)
                                put("members", JsonArray(listOf(buildJsonObject {
                                    put("name", "default")
                                    put("handle", "hermes")
                                })))
                                put("log", JsonArray(listOf(buildJsonObject {
                                    put("id", "message-1")
                                    put("from", buildJsonObject {
                                        put("kind", "member")
                                        put("name", "Lucy")
                                    })
                                    put("text", "Rollout is clear")
                                    put("at", 1_777_000_020)
                                })))
                            })
                        })
                    })
                })
            })))
        }

        val roster = client.listBotModeRoster().getOrThrow()

        assertTrue(roster.botModeProtocolSupported)
        assertEquals("Lucy", roster.bots.single().displayName)
        assertEquals("bot-tip", roster.bots.single().canonicalSession?.resolvedId)
        assertEquals(1_777_000_030_000L, roster.bots.single().workerSession?.lastActiveAtMs)
        assertEquals("Launch Council", roster.groups.single().name)
        assertEquals("Rollout is clear", roster.groups.single().latestMessage?.text)
        assertEquals(true, (harness.awaitRpc("profiles.list")["include_sessions"] as JsonPrimitive).booleanOrNull)
    }

    @Test
    fun `canonical bot chat adopts exact title registry without creating`() = runBlocking {
        harness.sessionListPayload = buildJsonObject {
            put("sessions", JsonArray(listOf(buildJsonObject {
                put("id", "bot-root")
                put("resolved_id", "bot-tip")
                put("root_title", "Bot Chat")
            })))
        }

        val target = client.ensureCanonicalBotChat("operator").getOrThrow()

        assertEquals("bot-root", target.storedSessionId)
        assertEquals("bot-tip", target.resolvedSessionId)
        assertTrue(harness.rpcLog.none { it.first == "session.create" })
        val lookup = harness.awaitRpc("session.list")
        assertEquals("operator", (lookup["profile"] as JsonPrimitive).content)
        assertEquals("Bot Chat", (lookup["title"] as JsonPrimitive).content)
        assertEquals(true, (lookup["include_hidden"] as JsonPrimitive).booleanOrNull)
    }

    @Test
    fun `canonical bot chat creates hidden row only after authoritative empty lookup`() = runBlocking {
        harness.createdSessionProfileName = "operator"

        val target = client.ensureCanonicalBotChat("operator").getOrThrow()

        assertEquals("20260612_120000_abc123", target.storedSessionId)
        val create = harness.awaitRpc("session.create")
        assertEquals("operator", (create["profile"] as JsonPrimitive).content)
        assertEquals("Bot Chat", (create["title"] as JsonPrimitive).content)
        assertEquals(true, (create["hidden"] as JsonPrimitive).booleanOrNull)
        val title = harness.awaitRpc("session.title")
        assertEquals("live-1", (title["session_id"] as JsonPrimitive).content)
        assertEquals("Bot Chat", (title["title"] as JsonPrimitive).content)
    }

    @Test
    fun `canonical bot chat lookup failure never creates replacement`() = runBlocking {
        harness.rpcErrors["session.list"] = 5006 to "profile db unavailable"

        assertTrue(client.ensureCanonicalBotChat("operator").isFailure)
        assertTrue(harness.rpcLog.none { it.first == "session.create" })
    }

    @Test
    fun `fixed route profile rides websocket URL and canonical RPC`() = runBlocking {
        val routeClient = GatewayChatClient(
            initialDashboardClient = DashboardApiClient(
                baseUrl = harness.server.url("/").toString().trimEnd('/'),
                okHttpClient = OkHttpClient(),
            ),
            fixedSessionProfile = "research bot",
            okHttpClient = OkHttpClient(),
            callbackDispatcher = { it() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
        try {
            routeClient.ensureCanonicalBotChat("research bot").getOrThrow()
            val requests = List(2) { harness.server.takeRequest(5, TimeUnit.SECONDS) }
            assertTrue(requests.filterNotNull().any {
                it.path?.contains("profile=research%20bot") == true
            })
            assertEquals(
                "research bot",
                (harness.awaitRpc("session.list")["profile"] as JsonPrimitive).content,
            )
        } finally {
            routeClient.shutdown()
        }
    }

    @Test
    fun `canonical bot lookup on remote route never reaches active gateway`() = runBlocking {
        val remoteHarness = GatewayClientHarness()
        val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val remoteClient = GatewayChatClient(
            initialDashboardClient = DashboardApiClient(
                baseUrl = remoteHarness.server.url("/").toString().trimEnd('/'),
                okHttpClient = OkHttpClient(),
            ),
            fixedSessionProfile = "default",
            okHttpClient = OkHttpClient(),
            callbackDispatcher = { it() },
            scope = remoteScope,
        )
        try {
            remoteClient.ensureCanonicalBotChat("default").getOrThrow()
            assertTrue(remoteHarness.rpcLog.any { it.first == "session.list" })
            assertTrue(harness.rpcLog.none { it.first == "session.list" })
        } finally {
            remoteClient.shutdown()
            remoteScope.cancel()
            remoteHarness.shutdown()
        }
    }

    @Test
    fun `profile list drops oversized ui meta without dropping profile`() = runBlocking {
        harness.profilesListPayload = buildJsonObject {
            put("profiles", JsonArray(listOf(buildJsonObject {
                put("name", "operator")
                put("model", "gpt")
                put("ui_meta", buildJsonObject { put("blob", "x".repeat(65_537)) })
            })))
        }

        val profile = client.listProfiles().getOrThrow().single()
        assertTrue(profile.uiMeta.isEmpty())
    }

    @Test
    fun `profile create serializes every auth choice explicitly`() = runBlocking {
        val cases = listOf(
            com.hermesandroid.relay.data.GatewayProfileAuthChoice.Shared to (true to true),
            com.hermesandroid.relay.data.GatewayProfileAuthChoice.Copied to (true to false),
            com.hermesandroid.relay.data.GatewayProfileAuthChoice.Isolated to (false to false),
        )
        cases.forEach { (choice, _) ->
            client.createProfile(
                com.hermesandroid.relay.data.GatewayProfileCreateRequest(
                    name = "operator",
                    description = "Mobile operator",
                    cloneFrom = "default",
                    noSkills = false,
                    soul = "# Operator",
                    provider = "openai",
                    model = "gpt-5.6",
                    authChoice = choice,
                ),
            ).getOrThrow()
        }
        harness.awaitRpcCount("profiles.create", cases.size).zip(cases).forEach { (params, case) ->
            val expected = case.second
            assertEquals(expected.first, (params["mirror_credentials"] as JsonPrimitive).booleanOrNull)
            assertEquals(expected.second, (params["share_auth"] as JsonPrimitive).booleanOrNull)
        }
    }

    @Test
    fun `profile create exposes partial server results`() = runBlocking {
        harness.profileCreatePayload = buildJsonObject {
            put("ok", true)
            put("name", "operator")
            put("soul_written", false)
            put("model_set", false)
            put("mirrored", buildJsonObject {
                put("env", false)
                put("auth", false)
                put("voice", false)
            })
        }
        val request = com.hermesandroid.relay.data.GatewayProfileCreateRequest(
            name = "operator",
            soul = "# Operator",
            provider = "openai",
            model = "gpt-5.6",
            authChoice = com.hermesandroid.relay.data.GatewayProfileAuthChoice.Copied,
        )

        val partial = client.createProfile(request).getOrThrow().partialMessages(request)
        assertTrue(partial.any { it.contains("SOUL") })
        assertTrue(partial.any { it.contains("model") })
        assertTrue(partial.any { it.contains("credential source") })
    }

    @Test
    fun `profile asset get distinguishes absent and validates bytes`() = runBlocking {
        assertNull(client.getProfileAvatar("operator").getOrThrow())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        harness.profileGetAssetPayload = buildJsonObject {
            put("found", true)
            put("mime", "image/png")
            put("size", png.size)
            put("data", "data:image/png;base64,${java.util.Base64.getEncoder().encodeToString(png)}")
        }

        val asset = client.getProfileAvatar("operator").getOrThrow()
        assertEquals("image/png", asset?.mime)
        assertTrue(png.contentEquals(asset?.data))
    }

    @Test
    fun `profile asset get rejects malformed and magic mismatch responses`() = runBlocking {
        harness.profileGetAssetPayload = buildJsonObject {
            put("found", true)
            put("mime", "image/png")
            put("data", "data:image/png;base64,not-base64!")
        }
        assertTrue(client.getProfileAvatar("operator").isFailure)

        harness.profileGetAssetPayload = buildJsonObject {
            put("found", true)
            put("mime", "image/png")
            put("data", "data:image/png;base64,${java.util.Base64.getEncoder().encodeToString("not an image".toByteArray())}")
        }
        assertTrue(client.getProfileAvatar("operator").isFailure)
    }

    @Test
    fun `profile asset set accepts exact boundary rejects overflow and clears`() = runBlocking {
        val boundary = ByteArray(GatewayChatClient.PROFILE_AVATAR_MAX_BYTES)
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            .copyInto(boundary)
        assertTrue(client.setProfileAvatar("operator", boundary).isSuccess)
        assertTrue(client.setProfileAvatar("operator", boundary.copyOf(boundary.size + 1)).isFailure)
        assertEquals(0, client.clearProfileAvatar("operator").getOrThrow())
        assertEquals(
            true,
            (harness.awaitRpcCount("profiles.set_asset", 2).last()["clear"] as JsonPrimitive).booleanOrNull,
        )
    }

    @Test
    fun `profile management method not found becomes sticky per operation`() = runBlocking {
        harness.methodNotFound += "profiles.list"
        assertTrue(client.listProfiles().exceptionOrNull() is com.hermesandroid.relay.data.GatewayProfileManagementUnsupportedException)
        harness.rpcLog.clear()
        assertTrue(client.listProfiles().isFailure)
        assertTrue(harness.rpcLog.none { it.first == "profiles.list" })

        harness.methodNotFound += "profiles.get_asset"
        assertTrue(client.getProfileAvatar("operator").isFailure)
        harness.rpcLog.clear()
        assertTrue(client.getProfileAvatar("operator").isFailure)
        assertTrue(harness.rpcLog.none { it.first == "profiles.get_asset" })
    }

    @Test
    fun `profile configure reports ui meta partial apply`() = runBlocking {
        client.describeProfile("operator").getOrThrow()
        val result = client.configureProfile(
            "operator",
            com.hermesandroid.relay.data.GatewayProfilePatch(
                uiMeta = buildJsonObject { put("accent", "#ff5500") },
            ),
        ).getOrThrow()
        assertEquals(setOf(com.hermesandroid.relay.data.GatewayProfileSection.UiMeta), result.failed)
    }

    @Test
    fun `profile ui meta rejects embedded image data`() = runBlocking {
        client.describeProfile("operator").getOrThrow()
        listOf("data:image/png;base64,AAAA", "iVBORw0KGgoAAAA", "UEsDBAAAA").forEach { embedded ->
            val result = client.configureProfile(
                "operator",
                com.hermesandroid.relay.data.GatewayProfilePatch(
                    uiMeta = buildJsonObject { put("asset", embedded) },
                ),
            )
            assertTrue(result.isFailure)
        }
        assertTrue(harness.rpcLog.none { it.first == "profiles.configure" })
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
        assertEquals("webui", (create["source"] as? JsonPrimitive)?.contentOrNull)
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
        assertEquals(0, r.reconcileRequests.get())
        assertTrue(r.errors.isEmpty())
        assertTrue(r.preflightFailures.isEmpty())
    }

    @Test
    fun `global reclaim settles active turn once and next send resumes durable session`() {
        val first = Recorder()
        client.sendTurn(
            sessionId = null,
            text = "first",
            newSessionTitle = "first",
            callbacks = first.callbacks,
            onPreflightFailure = { first.preflightFailures += it },
        )
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")
        val reclaim = harness.eventFrame(
            "session.reclaimed",
            buildJsonObject {
                put("session_id", "live-1")
                put("stored_session_id", "20260612_120000_abc123")
                put("reason", "idle_timeout")
            },
            null,
        )
        serverWs.send(reclaim)
        serverWs.send(reclaim)

        assertTrue("reclaimed turn never settled", first.completeLatch.await(5, TimeUnit.SECONDS))
        waitUntil { first.errors.size == 1 }

        val second = Recorder()
        client.sendTurn(
            sessionId = "20260612_120000_abc123",
            text = "continue",
            newSessionTitle = null,
            callbacks = second.callbacks,
            onPreflightFailure = { second.preflightFailures += it },
        )
        harness.awaitRpc("session.resume")
        harness.awaitRpcCount("prompt.submit", 2)
        serverWs.send(
            harness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "resumed") },
                "live-resumed",
            ),
        )

        assertTrue("resumed turn never completed", second.completeLatch.await(5, TimeUnit.SECONDS))
        assertTrue(second.errors.isEmpty())
    }

    @Test
    fun `unsolicited assistant turn for resumed session streams without sendTurn`() = runBlocking {
        val r = Recorder()
        val registrations = ConcurrentLinkedQueue<String>()
        val processEvents = ConcurrentLinkedQueue<GatewayProcessEvent>()
        val processEventLatch = CountDownLatch(1)
        client.setProcessEventListener {
            processEvents += it
            processEventLatch.countDown()
        }
        client.setUnsolicitedTurnProvider { storedSessionId ->
            registrations += storedSessionId
            GatewayInboundTurnRegistration(
                callbacks = r.callbacks,
                onHandle = { true },
            )
        }

        assertTrue(client.prewarmAwait("stored-session"))
        val serverWs = harness.awaitServerSocket()

        serverWs.send(harness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            harness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "Background task finished.") },
                "live-resumed",
            ),
        )
        serverWs.send(
            harness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Background task finished.") },
                "live-resumed",
            ),
        )

        assertTrue("unsolicited turn never completed", r.completeLatch.await(5, TimeUnit.SECONDS))
        assertTrue("turn completion did not invalidate process inventory", processEventLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("stored-session"), registrations.toList())
        assertEquals(1, r.starts.get())
        assertEquals(listOf("Background task finished."), r.textDeltas.toList())
        assertEquals(
            listOf(GatewayProcessEvent.Invalidated(GatewayProcessEvent.Trigger.MESSAGE_COMPLETE)),
            processEvents.toList(),
        )
        assertFalse(harness.rpcLog.any { it.first == "prompt.submit" })
    }

    @Test
    fun `unsolicited starts without exact live session are ignored`() = runBlocking {
        val r = Recorder()
        val registrations = AtomicInteger(0)
        client.setUnsolicitedTurnProvider {
            registrations.incrementAndGet()
            GatewayInboundTurnRegistration(r.callbacks) { true }
        }

        assertTrue(client.prewarmAwait("stored-session"))
        val serverWs = harness.awaitServerSocket()
        serverWs.send(harness.eventFrame("message.start", null, null))
        serverWs.send(harness.eventFrame("message.start", null, "someone-else"))

        assertFalse("foreign turn was accepted", r.completeLatch.await(300, TimeUnit.MILLISECONDS))
        assertEquals(0, registrations.get())
        assertEquals(0, r.starts.get())
    }

    @Test
    fun `cold prewarm reports resumed stored session once`() = runBlocking {
        val resumedSessions = ConcurrentLinkedQueue<String>()
        val resumedLatch = CountDownLatch(1)
        client.setColdPrewarmSessionReadyListener { storedSessionId ->
            resumedSessions += storedSessionId
            resumedLatch.countDown()
        }

        assertTrue(client.prewarmAwait("stored-session"))
        assertTrue("cold resume was not reported", resumedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(client.prewarmAwait("stored-session"))
        Thread.sleep(100)

        assertEquals(listOf("stored-session"), resumedSessions.toList())
    }

    @Test
    fun `newer prewarm selection wins when an older resume completes late`() = runBlocking {
        harness.suppressAckMethods += "session.resume"

        val old = async(Dispatchers.IO) { client.prewarmAwait("old-session") }
        val oldAck = harness.awaitPendingAck()

        // Starting the newer request advances the desired-session generation
        // even though it must wait for the older request's connect mutex.
        val newer = async(Dispatchers.IO) { client.prewarmAwait("new-session") }
        delay(100)
        harness.releaseAck(
            oldAck,
            buildJsonObject { put("session_id", "live-old") },
        )
        assertFalse(old.await())

        val newerAck = harness.awaitPendingAck()
        harness.releaseAck(
            newerAck,
            buildJsonObject { put("session_id", "live-new") },
        )
        assertTrue(newer.await())

        client.listProcesses().getOrThrow()
        val params = harness.awaitRpc("process.list")
        assertEquals("live-new", (params["session_id"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `same stored session id is resumed again when profile namespace changes`() = runBlocking {
        var profile = "profile-a"
        client.sessionProfileProvider = { profile }
        assertTrue(client.prewarmAwait("same-stored-id"))

        profile = "profile-b"
        assertTrue(client.prewarmAwait("same-stored-id"))

        val resumes = harness.awaitRpcCount("session.resume", 2)
        assertEquals(
            "profile-a",
            (resumes[0]["profile"] as? JsonPrimitive)?.contentOrNull,
        )
        assertEquals(
            "profile-b",
            (resumes[1]["profile"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    @Test
    fun `unsolicited error clears turn so the next unsolicited response can arrive`() = runBlocking {
        val recorders = ConcurrentLinkedQueue<Recorder>()
        client.setUnsolicitedTurnProvider {
            val recorder = Recorder()
            recorders += recorder
            GatewayInboundTurnRegistration(recorder.callbacks) { true }
        }

        assertTrue(client.prewarmAwait("stored-session"))
        val serverWs = harness.awaitServerSocket()
        serverWs.send(harness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            harness.eventFrame(
                "error",
                buildJsonObject { put("message", "first failed") },
                "live-resumed",
            ),
        )

        val first = awaitRecorder(recorders, 1)
        assertTrue(first.completeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("first failed"), first.errors.toList())

        serverWs.send(harness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            harness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "second worked") },
                "live-resumed",
            ),
        )

        val second = awaitRecorder(recorders, 2)
        assertTrue(second.completeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("second worked"), second.textDeltas.toList())
    }

    private fun awaitRecorder(recorders: ConcurrentLinkedQueue<Recorder>, count: Int): Recorder {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (recorders.size >= count) return recorders.elementAt(count - 1)
            Thread.sleep(20)
        }
        error("recorder $count was never registered")
    }

    @Test
    fun `model options refresh flag rides gateway rpc only on explicit refresh`() = runBlocking {
        val normal = client.modelOptions().getOrThrow()
        val normalParams = harness.awaitRpc("model.options")
        assertEquals("gpt-5.5", normal.currentModel)
        assertEquals(
            listOf("minimal", "medium", "ultra"),
            normal.providers.single().capabilities.getValue("gpt-5.5").reasoningEfforts,
        )
        assertFalse((normalParams["refresh"] as? JsonPrimitive)?.booleanOrNull == true)

        val refreshed = client.modelOptions(refresh = true).getOrThrow()
        val refreshParams = harness.awaitRpcCount("model.options", 2).last()
        assertEquals("openai", refreshed.currentProvider)
        assertTrue((refreshParams["refresh"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    @Test
    fun `process list uses live session id and parses typed snapshot`() = runBlocking {
        assertTrue(client.prewarmAwait("stored-session"))

        val processes = client.listProcesses().getOrThrow()

        val params = harness.awaitRpc("process.list")
        assertEquals("live-resumed", (params["session_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals(GatewayProcessCapability.Supported, client.processCapability.value)
        assertEquals(2, processes.size)
        assertEquals(
            GatewayProcess(
                id = "proc-17",
                command = "./gradlew test",
                cwd = "/workspace/app",
                pid = 4812L,
                startedAt = "2026-07-10T09:30:00",
                uptimeSeconds = 42L,
                status = "running",
                outputPreview = "running tests",
                outputTail = "running tests\n42 tests completed",
                notifyOnComplete = true,
                sessionScoped = true,
                watchPatterns = listOf("BUILD SUCCESSFUL"),
            ),
            processes[0],
        )
        assertTrue(processes[0].isRunning)
        assertEquals(1, processes[1].exitCode)
        assertTrue(processes[1].detached)
        assertFalse(processes[1].isRunning)
    }

    @Test
    fun `process kill uses exact live session and process id`() = runBlocking {
        assertTrue(client.prewarmAwait("stored-session"))

        assertTrue(client.killProcess("proc-17").isSuccess)

        val params = harness.awaitRpc("process.kill")
        assertEquals("live-resumed", (params["session_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("proc-17", (params["process_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals(GatewayProcessCapability.Supported, client.processCapability.value)
    }

    @Test
    fun `process method not found disables repeat probes for current socket`() = runBlocking {
        harness.methodNotFound.add("process.list")
        assertTrue(client.prewarmAwait("stored-session"))

        assertTrue(client.listProcesses().isFailure)
        assertEquals(GatewayProcessCapability.Unsupported, client.processCapability.value)
        assertEquals(1, harness.rpcLog.count { it.first == "process.list" })

        assertTrue(client.listProcesses().isFailure)
        assertEquals(1, harness.rpcLog.count { it.first == "process.list" })
    }

    @Test
    fun `process events bypass active turn gate but require exact live session`() = runBlocking {
        val events = ConcurrentLinkedQueue<GatewayProcessEvent>()
        val eventLatch = CountDownLatch(5)
        client.setProcessEventListener {
            events += it
            eventLatch.countDown()
        }
        assertTrue(client.prewarmAwait("stored-session"))
        val serverWs = harness.awaitServerSocket()

        serverWs.send(
            harness.eventFrame(
                "agent.terminal.output",
                buildJsonObject { put("process_id", "foreign"); put("chunk", "do not leak") },
                "someone-else",
            ),
        )
        serverWs.send(
            harness.eventFrame(
                "tool.complete",
                buildJsonObject { put("name", "browser"); put("tool_id", "tool-ignored") },
                "live-resumed",
            ),
        )
        serverWs.send(
            harness.eventFrame(
                "tool.complete",
                buildJsonObject { put("name", "terminal"); put("tool_id", "tool-1") },
                "live-resumed",
            ),
        )
        serverWs.send(
            harness.eventFrame(
                "status.update",
                buildJsonObject { put("kind", "process"); put("text", "process proc-17 completed") },
                "live-resumed",
            ),
        )
        serverWs.send(
            harness.eventFrame(
                "agent.terminal.output",
                buildJsonObject { put("process_id", "proc-17"); put("chunk", "BUILD SUCCESSFUL\n") },
                "live-resumed",
            ),
        )
        serverWs.send(
            harness.eventFrame(
                "terminal.close",
                buildJsonObject { put("process_id", "proc-17") },
                "live-resumed",
            ),
        )
        serverWs.send(
            harness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "foreign turn") },
                "someone-else",
            ),
        )
        serverWs.send(
            harness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "missing session id") },
                null,
            ),
        )
        // Upstream can omit tool lifecycle events for a background launch;
        // every exact-session turn completion is therefore a list fallback.
        serverWs.send(
            harness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Started as proc-17") },
                "live-resumed",
            ),
        )

        assertTrue("process events were dropped without an active turn", eventLatch.await(5, TimeUnit.SECONDS))
        assertEquals(
            listOf(
                GatewayProcessEvent.Invalidated(GatewayProcessEvent.Trigger.TOOL_COMPLETE),
                GatewayProcessEvent.Invalidated(GatewayProcessEvent.Trigger.STATUS_UPDATE),
                GatewayProcessEvent.Output("proc-17", "BUILD SUCCESSFUL\n"),
                GatewayProcessEvent.TerminalClosed("proc-17"),
                GatewayProcessEvent.Invalidated(GatewayProcessEvent.Trigger.MESSAGE_COMPLETE),
            ),
            events.toList(),
        )
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
        assertEquals("webui", (resume["source"] as? JsonPrimitive)?.contentOrNull)
        assertEquals(
            "20260101_010101_aaaaaa",
            (resume["session_id"] as? JsonPrimitive)?.contentOrNull,
        )
        assertTrue(harness.rpcLog.none { it.first == "session.create" })
        // Resumed sessions keep their stored id — no onSessionId rotation.
        assertTrue(r.sessionIds.isEmpty())
    }

    @Test
    fun `failed resume is visible and never forks a fresh session`() {
        harness.resumeFails = true
        val r = Recorder()
        client.sendTurn("api_123_dead", "hi", "hi", r.callbacks) { r.preflightFailures += it }
        harness.awaitRpc("session.resume")
        waitUntil { r.resumeFailures.isNotEmpty() }
        assertEquals(listOf("session.resume refused"), r.resumeFailures.toList())
        assertTrue(harness.rpcLog.none { it.first == "session.create" })
        assertTrue(harness.rpcLog.none { it.first == "prompt.submit" })
        assertTrue(r.preflightFailures.isEmpty())
    }

    @Test
    fun `profile-mismatched resume is visible and never submits the continuation`() {
        client.sessionProfileProvider = { "mizu" }
        harness.sessionProfileOverride = "default"
        val r = Recorder()

        client.sendTurn("stored-mizu", "continue", null, r.callbacks) {
            r.preflightFailures += it
        }

        harness.awaitRpc("session.resume")
        waitUntil { r.resumeFailures.isNotEmpty() }
        assertTrue(r.resumeFailures.single().contains("profile", ignoreCase = true))
        assertTrue(harness.rpcLog.none { it.first == "session.create" })
        assertTrue(harness.rpcLog.none { it.first == "prompt.submit" })
        assertTrue(r.preflightFailures.isEmpty())
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
    fun `socket loss mid-turn activates original session without resume and completes`() {
        val r = Recorder()
        client.sendTurn(null, "hello", null, r.callbacks) { r.preflightFailures += it }
        val ws1 = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        // Server connection dies mid-turn (Wi-Fi roam analogue). The client
        // must reconnect with a FRESH ticket and session.activate the original
        // live runtime. session.resume would mint/rebind a different runtime and
        // orphan the running turn.
        harness.rpcLog.clear()
        ws1.close(1011, "server crashed")

        val ws2 = harness.awaitServerSocket()
        assertTrue("reconnect must mint a fresh ticket", harness.ticketMints.get() >= 2)
        val activate = harness.awaitRpc("session.activate")
        assertEquals("live-1", (activate["session_id"] as? JsonPrimitive)?.contentOrNull)

        ws2.send(
            harness.eventFrame("message.delta", buildJsonObject { put("text", "after rejoin") }, "live-1"),
        )
        ws2.send(
            harness.eventFrame("message.complete", buildJsonObject { put("text", "after rejoin") }, "live-1"),
        )

        assertTrue("turn never completed after rejoin", r.completeLatch.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("after rejoin"), r.textDeltas.toList())
        assertEquals(1, r.reconcileRequests.get())
        assertTrue("rejoined turn must not error, got ${r.errors}", r.errors.isEmpty())
        // The fix's core invariant: a mid-turn rejoin must NEVER session.resume.
        assertTrue(
            "mid-turn rejoin must not call session.resume",
            harness.rpcLog.none { it.first == "session.resume" },
        )
        assertTrue(r.preflightFailures.isEmpty())
    }

    @Test
    fun `settled activation after a streamed socket gap completes without a terminal frame`() {
        val r = Recorder()
        client.sendTurn(null, "hello", null, r.callbacks) { r.preflightFailures += it }
        val ws1 = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        // The turn is known-live and has produced content, but the socket dies
        // before message.complete. Current upstream session.activate reports
        // running=false after the turn's finally block; it does not replay the
        // terminal frame that was emitted while this socket was detached.
        ws1.send(harness.eventFrame("message.start", null, "live-1"))
        ws1.send(
            harness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "partial") },
                "live-1",
            ),
        )
        awaitCondition { r.textDeltas.contains("partial") }
        harness.recoveryRunning = false
        ws1.close(1011, "terminal frame lost")

        harness.awaitServerSocket()
        harness.awaitRpc("session.activate")

        assertTrue(
            "authoritative running=false must settle the recovered turn",
            r.completeLatch.await(5, TimeUnit.SECONDS),
        )
        assertEquals(1, r.reconcileRequests.get())
        assertTrue(r.errors.isEmpty())
        assertTrue(r.preflightFailures.isEmpty())
        assertTrue(harness.rpcLog.none { it.first == "session.resume" })
    }

    @Test
    fun `settled session info completes a live turn when message complete is absent`() {
        val r = Recorder()
        client.sendTurn(null, "hello", null, r.callbacks) { r.preflightFailures += it }
        val ws = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        ws.send(harness.eventFrame("message.start", null, "live-1"))
        ws.send(
            harness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "persisted answer") },
                "live-1",
            ),
        )
        ws.send(
            harness.eventFrame(
                "session.info",
                buildJsonObject { put("running", false) },
                "live-1",
            ),
        )

        assertTrue(r.completeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("persisted answer"), r.textDeltas.toList())
        assertEquals(1, r.reconcileRequests.get())
        assertTrue(r.errors.isEmpty())
        assertTrue(r.preflightFailures.isEmpty())
    }

    @Test
    fun `unscoped idle session info cannot settle an active turn`() {
        val r = Recorder()
        client.sendTurn(null, "hello", null, r.callbacks) { r.preflightFailures += it }
        val ws = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        ws.send(harness.eventFrame("message.start", null, "live-1"))
        ws.send(
            harness.eventFrame(
                "session.info",
                buildJsonObject { put("running", false) },
                null,
            ),
        )

        assertFalse(r.completeLatch.await(250, TimeUnit.MILLISECONDS))
        ws.send(
            harness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "done") },
                "live-1",
            ),
        )
        assertTrue(r.completeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(0, r.reconcileRequests.get())
    }

    @Test
    fun `mid-turn activate method-not-found keeps legacy socket recovery`() {
        harness.methodNotFound += "session.activate"
        val r = Recorder()
        client.sendTurn(null, "hello", null, r.callbacks) { r.preflightFailures += it }
        val ws1 = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        harness.rpcLog.clear()
        ws1.close(1011, "radio roam")
        val ws2 = harness.awaitServerSocket()
        harness.awaitRpc("session.activate")
        ws2.send(
            harness.eventFrame("message.complete", buildJsonObject { put("text", "legacy") }, "live-1"),
        )

        assertTrue(r.completeLatch.await(10, TimeUnit.SECONDS))
        assertTrue(r.errors.isEmpty())
        assertTrue(r.preflightFailures.isEmpty())
        assertTrue(harness.rpcLog.none { it.first == "session.resume" })
    }

    @Test
    fun `lost submit ack before first event rejoins without duplicate fallback`() {
        harness.suppressAckMethods += "prompt.submit"
        val r = Recorder()
        client.sendTurn(null, "only once", null, r.callbacks) { r.preflightFailures += it }
        val ws1 = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        ws1.close(1011, "ack lost")
        val ws2 = harness.awaitServerSocket()
        val activate = harness.awaitRpc("session.activate")
        assertEquals("live-1", (activate["session_id"] as? JsonPrimitive)?.contentOrNull)
        ws2.send(
            harness.eventFrame("message.complete", buildJsonObject { put("text", "done") }, "live-1"),
        )

        assertTrue(r.completeLatch.await(10, TimeUnit.SECONDS))
        assertEquals(1, harness.rpcLog.count { it.first == "prompt.submit" })
        assertTrue(r.preflightFailures.isEmpty())
        assertTrue(r.errors.isEmpty())
        assertTrue(harness.rpcLog.none { it.first == "session.resume" })
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

        assertFalse(r.completeLatch.await(100, TimeUnit.MILLISECONDS))
        serverWs.send(harness.eventFrame("approval.expire", buildJsonObject { }, "live-1"))
        assertTrue(r.completeLatch.await(5, TimeUnit.SECONDS))
        val ask = r.interactions.single()
        assertEquals(GatewayAsk.Kind.APPROVAL, ask.kind)
        assertEquals(null, ask.requestId)
        assertEquals("rm -rf /tmp/x — cleanup", ask.text)
    }

    // --- Active-turn correction ---

    @Test
    fun `redirect queued when the server accepts active-turn correction`() {
        val r = Recorder()
        client.sendTurn(null, "long job", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        harness.redirectStatus = "redirected"
        assertEquals(SteerResult.Queued, runBlocking { client.steer("focus on tests") })
        val redirect = harness.awaitRpc("session.redirect")
        assertEquals("live-1", (redirect["session_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("focus on tests", (redirect["text"] as? JsonPrimitive)?.contentOrNull)
        assertTrue(harness.rpcLog.none { it.first == "session.steer" })
    }

    @Test
    fun `redirect falls back to steer when unsupported`() {
        val r = Recorder()
        client.sendTurn(null, "long job", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        harness.methodNotFound.add("session.redirect")
        harness.steerStatus = "queued"
        assertEquals(SteerResult.Queued, runBlocking { client.steer("legacy correction") })
        harness.awaitRpc("session.redirect")
        val steer = harness.awaitRpc("session.steer")
        assertEquals("live-1", (steer["session_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("legacy correction", (steer["text"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `redirect rejected propagates to the caller`() {
        val r = Recorder()
        client.sendTurn(null, "long job", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        harness.redirectStatus = "rejected"
        assertEquals(SteerResult.Rejected, runBlocking { client.steer("too late") })
    }

    @Test
    fun `redirect with no live session fails without touching the wire`() {
        assertEquals(SteerResult.Failed, runBlocking { client.steer("nothing running") })
        assertTrue(harness.rpcLog.none { it.first == "session.redirect" })
        assertTrue(harness.rpcLog.none { it.first == "session.steer" })
    }

    @Test
    fun `subagent redirect carries exact parent and child identity`() = runBlocking {
        val recorder = Recorder()
        client.sendTurn(null, "delegate", null, recorder.callbacks) { recorder.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        val result = client.steerSubagent("child-17", "focus on Android")

        assertEquals("queued", (result.getOrThrow()["status"] as? JsonPrimitive)?.contentOrNull)
        val params = harness.awaitRpc("subagent.steer")
        assertEquals("live-1", (params["session_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("child-17", (params["subagent_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("focus on Android", (params["text"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `compress session uses dedicated rpc and parses authoritative messages`() {
        val r = Recorder()
        client.sendTurn(null, "long job", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")
        harness.compressPayload = buildJsonObject {
            put("status", "completed")
            put("removed", 3)
            put("before_messages", 9)
            put("after_messages", 4)
            put(
                "messages",
                harness.json.parseToJsonElement(
                    """
                    [
                      {"id":"1","role":"user","content":"hello"},
                      {"id":"2","role":"assistant","content":"summary"}
                    ]
                    """.trimIndent(),
                ),
            )
        }

        val result = runBlocking { client.compressSession("tests").getOrThrow() }
        val rpc = harness.awaitRpc("session.compress")
        assertEquals("live-1", (rpc["session_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("tests", (rpc["focus_topic"] as? JsonPrimitive)?.contentOrNull)
        assertEquals(3, result.removed)
        assertEquals(2, result.messages.size)
        assertTrue(harness.rpcLog.none { it.first == "slash.exec" })
    }

    @Test
    fun `compress session falls back to slash exec when rpc is missing`() {
        val r = Recorder()
        client.sendTurn(null, "long job", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        harness.methodNotFound.add("session.compress")
        val result = runBlocking { client.compressSession().getOrThrow() }
        harness.awaitRpc("session.compress")
        val slash = harness.awaitRpc("slash.exec")
        assertEquals("/compress", (slash["command"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("legacy", result.status)
    }

    @Test
    fun `compress session preserves authoritative lock contention message`() {
        val r = Recorder()
        client.sendTurn(null, "long job", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")
        harness.compressPayload = buildJsonObject {
            put("compressed", false)
            put("lock_held", true)
            put("message", "Compression skipped because another request holds the lock.")
        }

        val result = runBlocking { client.compressSession().getOrThrow() }

        assertEquals("noop", result.status)
        assertEquals(
            "Compression skipped because another request holds the lock.",
            result.output,
        )
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
    fun `session create rejects a different authoritative profile owner`() {
        val r = Recorder()
        client.sessionProfileProvider = { "mizu" }
        harness.sessionProfileOverride = "default"

        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        awaitCondition { r.preflightFailures.isNotEmpty() }

        assertTrue(r.preflightFailures.single().contains("refusing to use it as selected profile 'mizu'"))
        assertTrue(harness.rpcLog.none { it.first == "prompt.submit" })
    }

    @Test
    fun `session create rejects older gateway without profile ownership metadata`() {
        val r = Recorder()
        client.sessionProfileProvider = { "mizu" }
        harness.omitSessionProfileMetadata = true

        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        awaitCondition { r.preflightFailures.isNotEmpty() }

        assertTrue(r.preflightFailures.single().contains("did not confirm profile ownership"))
        assertTrue(harness.rpcLog.none { it.first == "prompt.submit" })
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

    // --- Confirmation-safe model sessions ---

    @Test
    fun `session create never binds an unconfirmed model or provider`() {
        val r = Recorder()
        client.sessionModelProvider = { GatewaySessionModel("grok-4.3", "xai") }
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        val create = harness.awaitRpc("session.create")
        assertEquals(null, create["model"])
        assertEquals(null, create["provider"])
    }

    @Test
    fun `session create never binds an unconfirmed providerless model`() {
        val r = Recorder()
        client.sessionModelProvider = { GatewaySessionModel("gpt-5.5", null) }
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        val create = harness.awaitRpc("session.create")
        assertEquals(null, create["model"])
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
        assertFalse(create.containsKey("model"))
        assertFalse(create.containsKey("provider"))
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
    fun `session create binds explicit normal fast tier`() {
        val r = Recorder()
        client.sessionModelProvider = {
            GatewaySessionModel(model = null, provider = null, reasoningEffort = null, fast = false)
        }
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        val create = harness.awaitRpc("session.create")
        assertEquals(false, (create["fast"] as? JsonPrimitive)?.booleanOrNull)
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
        assertFalse(create.containsKey("model"))
        assertFalse(create.containsKey("provider"))
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
        val submit = harness.awaitRpc("prompt.submit")

        val attach = harness.awaitRpc("file.attach")
        assertEquals("live-1", (attach["session_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals(
            "data:text/plain;base64,aGk=",
            (attach["data_url"] as? JsonPrimitive)?.contentOrNull,
        )
        assertEquals("notes.txt", (attach["name"] as? JsonPrimitive)?.contentOrNull)
        assertEquals(
            "@file:notes.txt\n\nread this",
            (submit["text"] as? JsonPrimitive)?.contentOrNull,
        )
        assertTrue(harness.rpcLog.none { it.first == "image.attach_bytes" })
        assertTrue(harness.rpcLog.none { it.first == "pdf.attach" })
        assertTrue(r.preflightFailures.isEmpty())
    }

    @Test
    fun `queued document follow-up keeps its returned file reference on the queued prompt`() {
        val r = Recorder()
        client.sendTurn(
            sessionId = null,
            text = "compare the totals",
            newSessionTitle = null,
            callbacks = r.callbacks,
            attachments = listOf(
                GatewayAttachment(
                    name = "quarterly report.ods",
                    base64 = "UEsDBA==",
                    ext = "ods",
                    contentType = "application/vnd.oasis.opendocument.spreadsheet",
                ),
            ),
            queuedFollowUp = true,
            onPreflightFailure = { r.preflightFailures += it },
        )

        val submit = harness.awaitRpc("prompt.submit")
        assertEquals(true, (submit["queued"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals(
            "@file:notes.txt\n\ncompare the totals",
            (submit["text"] as? JsonPrimitive)?.contentOrNull,
        )
        assertTrue(r.preflightFailures.isEmpty())
    }

    @Test
    fun `document upload without a readable reference fails before prompt submit`() {
        harness.fileAttachPayload = buildJsonObject { put("attached", true) }
        val r = Recorder()
        client.sendTurn(
            sessionId = null,
            text = "read this",
            newSessionTitle = null,
            callbacks = r.callbacks,
            attachments = listOf(
                GatewayAttachment("notes.txt", "aGk=", "txt", "text/plain"),
            ),
            onPreflightFailure = {
                r.preflightFailures += it
                r.completeLatch.countDown()
            },
        )

        assertTrue(r.completeLatch.await(5, TimeUnit.SECONDS))
        assertTrue(r.preflightFailures.single().contains("no readable file reference"))
        assertTrue(harness.rpcLog.none { it.first == "prompt.submit" })
    }

    @Test
    fun `attachment failure never falls through to a transport that drops the file`() {
        harness.methodNotFound.add("file.attach")
        val r = Recorder()
        val attachmentFailures = mutableListOf<String>()
        client.sendTurn(
            sessionId = null,
            text = "use both files",
            newSessionTitle = null,
            callbacks = r.callbacks,
            attachments = listOf(
                GatewayAttachment("shot.png", "QQ==", "png", "image/png", sizeBytes = 1),
                GatewayAttachment("notes.txt", "Qg==", "txt", "text/plain", sizeBytes = 1),
            ),
            onAttachmentFailure = {
                attachmentFailures += it
                r.completeLatch.countDown()
            },
            onPreflightFailure = { r.preflightFailures += it },
        )

        assertTrue(r.completeLatch.await(5, TimeUnit.SECONDS))
        assertTrue(attachmentFailures.single().contains("file.attach"))
        assertTrue(r.preflightFailures.isEmpty())
        assertTrue(harness.rpcLog.none { it.first == "prompt.submit" })
        val detach = harness.awaitRpc("image.detach")
        assertEquals("/session/images/upload.png", (detach["path"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `attachment sizes are derived from base64 and bounded before upload`() {
        assertEquals(1L, decodedBase64Size("QQ=="))
        assertEquals(2L, decodedBase64Size("QUI="))
        assertEquals(3L, decodedBase64Size("QUJD"))

        val r = Recorder()
        client.sendTurn(
            sessionId = null,
            text = "oversized",
            newSessionTitle = null,
            callbacks = r.callbacks,
            attachments = listOf(
                GatewayAttachment(
                    name = "huge.png",
                    base64 = "QQ==",
                    ext = "png",
                    contentType = "image/png",
                    sizeBytes = 25L * 1024L * 1024L + 1L,
                ),
            ),
            onAttachmentFailure = { r.completeLatch.countDown() },
            onPreflightFailure = { r.preflightFailures += it },
        )

        assertTrue(r.completeLatch.await(5, TimeUnit.SECONDS))
        assertTrue(harness.rpcLog.none { it.first.startsWith("image.attach") })
        assertTrue(harness.rpcLog.none { it.first == "prompt.submit" })
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

    @Test
    fun `expired ask response is distinguished from accepted response`() {
        val r = Recorder()
        client.sendTurn(null, "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        harness.askResponseStatus = "expired"
        assertEquals(
            GatewayAskResponse.EXPIRED,
            runBlocking { client.respondSecret("r3", "late") }.getOrThrow(),
        )

        harness.approvalResolved = 0
        assertEquals(
            GatewayAskResponse.EXPIRED,
            runBlocking { client.respondApproval("approve") }.getOrThrow(),
        )
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

    @Test
    fun `cron creation uses upstream manage rpc with finite repeat and profile`() {
        val result = runBlocking {
            client.createCronJob(
                CronCreationDraft(
                    name = "Two reports",
                    schedule = "every 1h",
                    prompt = "Send a concise report",
                    repeat = 2,
                    profile = "work",
                ),
            )
        }

        assertTrue(result.isSuccess)
        val params = harness.awaitRpc("cron.manage")
        assertEquals("add", (params["action"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("Two reports", (params["name"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("every 1h", (params["schedule"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("Send a concise report", (params["prompt"] as? JsonPrimitive)?.contentOrNull)
        assertEquals(2, (params["repeat"] as? JsonPrimitive)?.intOrNull)
        assertEquals("work", (params["profile"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `invalid finite repeat is rejected before opening gateway`() {
        val result = runBlocking {
            client.createCronJob(
                CronCreationDraft("Bad", "every 1h", "Run", repeat = 0),
            )
        }

        assertTrue(result.isFailure)
        assertEquals(0, harness.ticketMints.get())
        assertTrue(harness.rpcLog.none { it.first == "cron.manage" })
    }

    // --- Pet thumbnails ---

    @Test
    fun `message reaction targets newest role without guessing a row id`() {
        client.sendTurn("stored-1", "hi", null, Recorder().callbacks) {}
        harness.awaitRpc("session.resume")
        harness.awaitRpc("prompt.submit")

        val result = runBlocking { client.reactToMessage(null, "assistant", "👍") }

        assertTrue(result.isSuccess)
        val params = harness.awaitRpc("message.react")
        assertEquals("live-resumed", (params["session_id"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("assistant", (params["newest_role"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("👍", (params["emoji"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("user", (params["author"] as? JsonPrimitive)?.contentOrNull)
        assertFalse("row_id" in params)
    }

    @Test
    fun `message reaction targets durable row when history provides it`() {
        client.sendTurn("stored-1", "hi", null, Recorder().callbacks) {}
        harness.awaitRpc("session.resume")
        harness.awaitRpc("prompt.submit")

        val result = runBlocking { client.reactToMessage(42L, "assistant", "❤️") }

        assertTrue(result.isSuccess)
        val params = harness.awaitRpc("message.react")
        assertEquals(42L, (params["row_id"] as? JsonPrimitive)?.longOrNull)
        assertFalse("newest_role" in params)
    }

    @Test
    fun `pet thumbnail connects on demand and sends upstream params`() {
        client.sessionProfileProvider = { "work" }

        val result = runBlocking {
            client.petThumbnail(
                slug = "boba",
                spritesheetUrl = "https://assets.petdex.dev/pets/boba/sprites.png",
            )
        }

        assertEquals("data:image/png;base64,iVBORw0KGgo=", result.getOrThrow())
        assertTrue(harness.ticketMints.get() >= 1)
        val params = harness.awaitRpc("pet.thumb")
        assertEquals("boba", (params["slug"] as? JsonPrimitive)?.contentOrNull)
        assertEquals(
            "https://assets.petdex.dev/pets/boba/sprites.png",
            (params["url"] as? JsonPrimitive)?.contentOrNull,
        )
        assertEquals("work", (params["profile"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `pet thumbnail fail-open response is a successful cache miss`() {
        harness.petThumbPayload = buildJsonObject {
            put("ok", false)
            put("slug", "boba")
        }

        val result = runBlocking { client.petThumbnail("boba") }

        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow())
    }

    @Test
    fun `pet thumbnail preserves method-not-found for compatibility fallback`() {
        harness.methodNotFound += "pet.thumb"

        val result = runBlocking { client.petThumbnail("boba") }

        assertTrue(result.isFailure)
        assertEquals(-32601, (result.exceptionOrNull() as? GatewayRpcException)?.code)
    }

    @Test
    fun `pet thumbnail rejects unsafe inputs before connecting`() {
        val badSlug = runBlocking { client.petThumbnail("../boba") }
        val badUrl = runBlocking {
            client.petThumbnail("boba", "https://example.com/boba.png")
        }

        assertTrue(badSlug.exceptionOrNull() is IllegalArgumentException)
        assertTrue(badUrl.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, harness.ticketMints.get())
        assertEquals(0, harness.rpcLog.count { it.first == "pet.thumb" })
    }

    @Test
    fun `pet thumbnail rejects malformed success payloads`() {
        harness.petThumbPayload = buildJsonObject {
            put("ok", true)
            put("slug", "someone-else")
            put("dataUri", "data:image/png;base64,iVBORw0KGgo=")
        }
        val mismatchedSlug = runBlocking { client.petThumbnail("boba") }

        harness.petThumbPayload = buildJsonObject {
            put("ok", true)
            put("slug", "boba")
            put("dataUri", "data:image/jpeg;base64,iVBORw0KGgo=")
        }
        val wrongMediaType = runBlocking { client.petThumbnail("boba") }

        assertTrue(mismatchedSlug.exceptionOrNull() is GatewayRpcException)
        assertTrue(wrongMediaType.exceptionOrNull() is GatewayRpcException)
    }

    @Test
    fun `pet info parses the upstream renderer payload with profile and revision`() = runBlocking {
        val info = client.petInfo(profile = "work", knownRevision = "old:1").getOrThrow()

        val params = harness.awaitRpc("pet.info")
        assertEquals("work", (params["profile"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("old:1", (params["knownRevision"] as? JsonPrimitive)?.contentOrNull)
        assertTrue(info.enabled)
        assertEquals("boba", info.slug)
        assertEquals("123:8", info.spritesheetRevision)
        assertEquals(192, info.frameWidth)
        assertEquals(8, info.framesByRow["running"])
        assertArrayEquals(java.util.Base64.getDecoder().decode("iVBORw0KGgo="), info.spritesheet)
    }

    @Test
    fun `pet info accepts upstream unchanged response without duplicate sheet`() = runBlocking {
        harness.petInfoPayload = buildJsonObject {
            put("enabled", true)
            put("slug", "boba")
            put("displayName", "Boba")
            put("spritesheetRevision", "123:8")
            put("spritesheetUnchanged", true)
            put("frameW", 192)
            put("frameH", 208)
            put("framesPerState", 8)
            put("loopMs", 1100)
            put("stateRows", JsonArray(listOf(JsonPrimitive("idle"))))
        }

        val info = client.petInfo(knownRevision = "123:8").getOrThrow()

        assertTrue(info.spritesheetUnchanged)
        assertNull(info.spritesheet)
    }

    @Test
    fun `pet gallery and mutations preserve profile scope`() = runBlocking {
        val gallery = client.petGallery(profile = "work", localOnly = true).getOrThrow()
        assertEquals("boba", gallery.active)
        assertEquals("Boba", gallery.pets.single().displayName)
        val galleryParams = harness.awaitRpc("pet.gallery")
        assertEquals("work", (galleryParams["profile"] as? JsonPrimitive)?.contentOrNull)
        assertTrue((galleryParams["localOnly"] as? JsonPrimitive)?.booleanOrNull == true)

        client.selectPet("boba", profile = "work").getOrThrow()
        val selectParams = harness.awaitRpc("pet.select")
        assertEquals("boba", (selectParams["slug"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("work", (selectParams["profile"] as? JsonPrimitive)?.contentOrNull)

        client.disablePet(profile = "work").getOrThrow()
        val disableParams = harness.awaitRpc("pet.disable")
        assertEquals("work", (disableParams["profile"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `pet contract rejects malformed renderer and unsafe mutation`() = runBlocking {
        harness.petInfoPayload = buildJsonObject {
            put("enabled", true)
            put("slug", "boba")
            put("spritesheetRevision", "1:1")
            put("spritesheetBase64", "not base64")
            put("mime", "image/png")
            put("frameW", 192)
            put("frameH", 208)
            put("framesPerState", 8)
            put("loopMs", 1100)
            put("stateRows", JsonArray(listOf(JsonPrimitive("idle"))))
        }

        assertTrue(client.petInfo().isFailure)
        assertTrue(client.selectPet("../boba").exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, harness.rpcLog.count { it.first == "pet.select" })
    }

    // --- Reasoning config ---

    @Test
    fun `reasoning settings fetch uses config get`() {
        harness.reasoningEffort = "ultra"
        harness.reasoningDisplay = "show"

        val result = runBlocking { client.getReasoningSettings() }

        assertTrue(result.isSuccess)
        assertEquals("ultra", result.getOrThrow().effort)
        assertEquals("show", result.getOrThrow().display)
        val rpc = harness.awaitRpc("config.get")
        assertEquals("reasoning", (rpc["key"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `session info preserves coherent provider model and max effort`() {
        val recorder = Recorder()
        client.sendTurn("stored-1", "hi", null, recorder.callbacks) {
            recorder.preflightFailures += it
        }
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("session.resume")
        harness.awaitRpc("prompt.submit")

        serverWs.send(
            harness.eventFrame(
                "session.info",
                buildJsonObject {
                    put("model", "deepseek-v3")
                    put("provider", "opencode")
                    put("reasoning_effort", "max")
                },
                "live-resumed",
            ),
        )

        waitUntil { client.serverReasoningEffort.value == "max" }
        assertEquals(
            GatewayModelIdentity(model = "deepseek-v3", provider = "opencode"),
            client.serverModelIdentity.value,
        )
        assertEquals(
            GatewayReasoningIdentity(
                identity = GatewayModelIdentity(model = "deepseek-v3", provider = "opencode"),
                effort = "max",
            ),
            client.serverReasoningIdentity.value,
        )
    }

    @Test
    fun `session info without provider clears prior session identity`() {
        val recorder = Recorder()
        client.sendTurn("stored-1", "hi", null, recorder.callbacks) {
            recorder.preflightFailures += it
        }
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("session.resume")
        harness.awaitRpc("prompt.submit")
        serverWs.send(
            harness.eventFrame(
                "session.info",
                buildJsonObject {
                    put("model", "deepseek-v3")
                    put("provider", "opencode")
                },
                "live-resumed",
            ),
        )
        waitUntil { client.serverProvider.value == "opencode" }

        serverWs.send(
            harness.eventFrame(
                "session.info",
                buildJsonObject { put("model", "agnes-2") },
                "live-resumed",
            ),
        )

        waitUntil {
            client.serverModel.value == "agnes-2" && client.serverProvider.value == null
        }
        assertNull(client.serverProvider.value)
        assertNull(client.serverModelIdentity.value)
    }

    @Test
    fun `approval mode get and set use profile config without session yolo scope`() {
        harness.approvalMode = "smart"

        val fetched = runBlocking { client.getApprovalMode() }
        val updated = runBlocking { client.setApprovalMode(GatewayApprovalMode.Off) }

        assertEquals(GatewayApprovalMode.Smart, fetched.getOrThrow())
        assertEquals(GatewayApprovalMode.Off, updated.getOrThrow())
        assertEquals(
            GatewayApprovalModeCapability.Supported,
            client.approvalModeCapability.value,
        )
        assertEquals(GatewayApprovalMode.Off, client.serverApprovalMode.value)
        val getRpc = harness.awaitRpc("config.get")
        assertEquals("approvals.mode", (getRpc["key"] as? JsonPrimitive)?.contentOrNull)
        val setRpc = harness.awaitRpc("config.set")
        assertEquals("approvals.mode", (setRpc["key"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("off", (setRpc["value"] as? JsonPrimitive)?.contentOrNull)
        assertFalse(setRpc.containsKey("scope"))
        assertFalse(setRpc.containsKey("session_id"))
    }

    @Test
    fun `session info reconciles known approval modes and ignores unknown values`() {
        val recorder = Recorder()
        client.sendTurn("stored-1", "hi", null, recorder.callbacks) {
            recorder.preflightFailures += it
        }
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("session.resume")
        harness.awaitRpc("prompt.submit")

        serverWs.send(
            harness.eventFrame(
                "session.info",
                buildJsonObject {
                    put("approval_mode", "manual")
                    put("desktop_contract", 3)
                },
                "live-resumed",
            ),
        )
        waitUntil { client.serverApprovalMode.value == GatewayApprovalMode.Manual }
        assertEquals(GatewayApprovalModeCapability.Supported, client.approvalModeCapability.value)

        serverWs.send(
            harness.eventFrame(
                "session.info",
                buildJsonObject { put("approval_mode", "future-mode") },
                "live-resumed",
            ),
        )
        Thread.sleep(30)
        assertEquals(GatewayApprovalMode.Manual, client.serverApprovalMode.value)

        serverWs.send(
            harness.eventFrame(
                "session.info",
                buildJsonObject { put("desktop_contract", 2) },
                "live-resumed",
            ),
        )
        waitUntil {
            client.approvalModeCapability.value ==
                GatewayApprovalModeCapability.Unsupported
        }
        assertEquals(GatewayApprovalMode.Manual, client.serverApprovalMode.value)
    }

    @Test
    fun `older gateway rejection disables only approval mode capability`() {
        harness.unsupportedConfigKeys += "approvals.mode"

        val first = runBlocking { client.getApprovalMode() }
        val configGetsAfterFirst = harness.rpcLog.count { (method, _) -> method == "config.get" }
        val second = runBlocking { client.getApprovalMode() }

        assertTrue(first.isFailure)
        assertTrue(second.isFailure)
        assertEquals(
            GatewayApprovalModeCapability.Unsupported,
            client.approvalModeCapability.value,
        )
        assertEquals(
            configGetsAfterFirst,
            harness.rpcLog.count { (method, _) -> method == "config.get" },
        )
    }

    @Test
    fun `multiplexed profile approval mode is read only until upstream scopes config rpc`() {
        client.sessionProfileProvider = { "work" }

        val fetched = runBlocking { client.getApprovalMode() }
        val updated = runBlocking { client.setApprovalMode(GatewayApprovalMode.Manual) }

        assertTrue(fetched.isFailure)
        assertTrue(updated.isFailure)
        assertTrue(
            fetched.exceptionOrNull()?.message.orEmpty().contains("read-only"),
        )
        assertEquals(
            0,
            harness.rpcLog.count { (method, _) ->
                method == "config.get" || method == "config.set"
            },
        )
        assertEquals(
            GatewayApprovalModeCapability.Unknown,
            client.approvalModeCapability.value,
        )
    }

    @Test
    fun `stale session info cannot overwrite approval mode after session clear`() {
        harness.approvalMode = "smart"
        assertEquals(GatewayApprovalMode.Smart, runBlocking { client.getApprovalMode() }.getOrThrow())

        val recorder = Recorder()
        client.sendTurn("stored-1", "hi", null, recorder.callbacks) {
            recorder.preflightFailures += it
        }
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("session.resume")
        harness.awaitRpc("prompt.submit")
        client.clearSession()

        serverWs.send(
            harness.eventFrame(
                "session.info",
                buildJsonObject { put("approval_mode", "off") },
                "live-resumed",
            ),
        )
        Thread.sleep(30)

        assertEquals(GatewayApprovalMode.Smart, client.serverApprovalMode.value)
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

    @Test
    fun `model update targets live session without global scope`() {
        val r = Recorder()
        client.sendTurn("stored-1", "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("session.resume")
        harness.awaitRpc("prompt.submit")

        val result = runBlocking { client.setModel("grok-4.3 --provider xai") }

        assertTrue(result.isSuccess)
        val rpc = harness.awaitRpc("config.set")
        assertEquals("model", (rpc["key"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("grok-4.3 --provider xai", (rpc["value"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("live-resumed", (rpc["session_id"] as? JsonPrimitive)?.contentOrNull)
        assertFalse(rpc.containsKey("scope"))
    }

    @Test
    fun `confirmed model update echoes upstream confirmation field`() {
        val r = Recorder()
        client.sendTurn("stored-1", "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("session.resume")
        harness.awaitRpc("prompt.submit")

        val result = runBlocking {
            client.setModel("grok-4.3 --provider xai", confirmSelection = true)
        }

        assertTrue(result.isSuccess)
        val rpc = harness.awaitRpc("config.set")
        assertEquals("model", (rpc["key"] as? JsonPrimitive)?.contentOrNull)
        assertEquals(true, (rpc["confirm_expensive_model"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals("live-resumed", (rpc["session_id"] as? JsonPrimitive)?.contentOrNull)
    }

    @Test
    fun `same confirmed draft session is reusable across rapid model picks`() {
        client.sessionProfileProvider = { "mizu" }
        harness.createdSessionProfileName = "mizu"
        val results = runBlocking {
            val first = async(Dispatchers.IO) { client.prepareModelSelectionSession(null) }
            val second = async(Dispatchers.IO) { client.prepareModelSelectionSession(null) }
            listOf(first.await(), second.await())
        }

        assertTrue(results.all { it.isSuccess })
        assertEquals(
            listOf("20260612_120000_abc123", "20260612_120000_abc123"),
            results.map { it.getOrThrow() },
        )
        assertEquals(1, harness.rpcLog.count { it.first == "session.create" })
        assertEquals(0, harness.rpcLog.count { it.first == "config.set" })
    }

    @Test
    fun `fast update targets live session without global scope`() {
        val r = Recorder()
        client.sendTurn("stored-1", "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("session.resume")
        harness.awaitRpc("prompt.submit")

        val result = runBlocking { client.setFast(false) }

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
        val rpc = harness.awaitRpc("config.set")
        assertEquals("fast", (rpc["key"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("normal", (rpc["value"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("live-resumed", (rpc["session_id"] as? JsonPrimitive)?.contentOrNull)
        assertFalse(rpc.containsKey("scope"))
    }

    @Test
    fun `yolo update targets live session with ephemeral session scope`() {
        val r = Recorder()
        client.sendTurn("stored-1", "hi", null, r.callbacks) { r.preflightFailures += it }
        harness.awaitServerSocket()
        harness.awaitRpc("session.resume")
        harness.awaitRpc("prompt.submit")

        val result = runBlocking { client.setYolo(true) }

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
        val rpc = harness.awaitRpc("config.set")
        assertEquals("yolo", (rpc["key"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("1", (rpc["value"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("session", (rpc["scope"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("live-resumed", (rpc["session_id"] as? JsonPrimitive)?.contentOrNull)
    }

    // --- Edit & regenerate ---

    @Test
    fun `later truncate ordinal carries destructive confirmation only`() {
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
        assertEquals(true, (submit["confirm_truncate"] as? JsonPrimitive)?.booleanOrNull)
        assertFalse(submit.containsKey("confirm_empty_truncate"))
    }

    @Test
    fun `durable rewind target and survivor row ids round trip`() {
        harness.promptSubmitPayload = buildJsonObject {
            put("ok", true)
            put("survivor_user_row_ids", buildJsonArray {
                add(JsonPrimitive(101L))
                add(JsonNull)
                add(JsonPrimitive("malformed"))
            })
        }
        val r = Recorder()
        val rebound = AtomicReference<List<Long?>>()
        val reboundLatch = CountDownLatch(1)

        client.sendTurn(
            sessionId = "stored-1",
            text = "edited message",
            newSessionTitle = null,
            callbacks = r.callbacks,
            truncateBeforeUserOrdinal = 2,
            truncateBeforeRowId = 73L,
            onSurvivorUserRowIds = {
                rebound.set(it)
                reboundLatch.countDown()
            },
            onPreflightFailure = { r.preflightFailures += it },
        )

        val submit = harness.awaitRpc("prompt.submit")
        assertEquals(2, (submit["truncate_before_user_ordinal"] as? JsonPrimitive)?.intOrNull)
        assertEquals(73L, (submit["truncate_before_row_id"] as? JsonPrimitive)?.longOrNull)
        assertTrue(reboundLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(101L, null, null), rebound.get())
    }

    @Test
    fun `row id only rewind still carries explicit truncation consent`() {
        val r = Recorder()
        client.sendTurn(
            sessionId = "stored-1",
            text = "edited message",
            newSessionTitle = null,
            callbacks = r.callbacks,
            truncateBeforeRowId = 73L,
            onPreflightFailure = { r.preflightFailures += it },
        )

        val submit = harness.awaitRpc("prompt.submit")
        assertFalse(submit.containsKey("truncate_before_user_ordinal"))
        assertEquals(73L, (submit["truncate_before_row_id"] as? JsonPrimitive)?.longOrNull)
        assertEquals(true, (submit["confirm_truncate"] as? JsonPrimitive)?.booleanOrNull)
        assertFalse(submit.containsKey("confirm_empty_truncate"))
    }

    @Test
    fun `first user truncate carries empty-history confirmation`() {
        val r = Recorder()
        client.sendTurn(
            sessionId = null,
            text = "replace first message",
            newSessionTitle = null,
            callbacks = r.callbacks,
            truncateBeforeUserOrdinal = 0,
            onPreflightFailure = { r.preflightFailures += it },
        )
        val submit = harness.awaitRpc("prompt.submit")
        assertEquals(0, (submit["truncate_before_user_ordinal"] as? JsonPrimitive)?.intOrNull)
        assertEquals(true, (submit["confirm_truncate"] as? JsonPrimitive)?.booleanOrNull)
        assertEquals(true, (submit["confirm_empty_truncate"] as? JsonPrimitive)?.booleanOrNull)
    }

    @Test
    fun `middle truncate ordinal omits empty-history confirmation`() {
        val r = Recorder()
        client.sendTurn(
            sessionId = null,
            text = "replace middle message",
            newSessionTitle = null,
            callbacks = r.callbacks,
            truncateBeforeUserOrdinal = 1,
            onPreflightFailure = { r.preflightFailures += it },
        )
        val submit = harness.awaitRpc("prompt.submit")
        assertEquals(1, (submit["truncate_before_user_ordinal"] as? JsonPrimitive)?.intOrNull)
        assertEquals(true, (submit["confirm_truncate"] as? JsonPrimitive)?.booleanOrNull)
        assertFalse(submit.containsKey("confirm_empty_truncate"))
    }

    @Test
    fun `truncate ordinal is absent from plain sends`() {
        val r = Recorder()
        client.sendTurn(null, "plain", null, r.callbacks) { r.preflightFailures += it }
        val submit = harness.awaitRpc("prompt.submit")
        assertFalse(submit.containsKey("truncate_before_user_ordinal"))
        assertFalse(submit.containsKey("confirm_truncate"))
        assertFalse(submit.containsKey("confirm_empty_truncate"))
    }

    @Test
    fun `queued follow-up marker rides only explicit queue drains`() {
        val r = Recorder()
        client.sendTurn(
            sessionId = null,
            text = "run this next",
            newSessionTitle = null,
            callbacks = r.callbacks,
            queuedFollowUp = true,
            onPreflightFailure = { r.preflightFailures += it },
        )

        val submit = harness.awaitRpc("prompt.submit")
        assertEquals(true, (submit["queued"] as? JsonPrimitive)?.booleanOrNull)
    }

    @Test
    fun `authoritative prompt rejections surface server message without preflight fallback`() {
        val cases = listOf(
            4004 to "Truncation target must be an integer",
            4018 to "Target user message is no longer in session history",
            4028 to "Empty-history truncate confirmation required",
            4029 to "Truncate confirmation required",
            4030 to "Row id and ordinal identify different user turns",
            4090 to "Active session limit reached; close the session held by another client",
            5008 to "Failed to persist history truncation",
            5070 to "Session storage is full; free disk space and retry",
            5071 to "Initial session persistence failed",
        )

        cases.forEachIndexed { index, (code, message) ->
            harness.rpcErrors["prompt.submit"] = code to message
            val r = Recorder()
            client.sendTurn(null, "hello-$code", null, r.callbacks) { r.preflightFailures += it }
            harness.awaitRpc("prompt.submit")

            waitUntil { r.errors.isNotEmpty() }
            assertEquals(listOf(message), r.errors.toList())
            assertTrue("$code must not trigger SSE fallback", r.preflightFailures.isEmpty())
            assertEquals(index + 1, harness.rpcLog.count { it.first == "prompt.submit" })
        }
    }

    @Test
    fun `bounded transcript resume rejection stays visible and never creates a replacement session`() {
        val message =
            "Session has 20,001 active messages, above sessions.max_resume_messages; export or raise the limit"
        harness.rpcErrors["session.resume"] = 4130 to message
        val r = Recorder()

        client.sendTurn("oversized-session", "continue", null, r.callbacks) {
            r.preflightFailures += it
        }

        harness.awaitRpc("session.resume")
        assertTrue(r.completeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(message), r.resumeFailures.toList())
        assertTrue(r.preflightFailures.isEmpty())
        assertEquals(0, harness.rpcLog.count { it.first == "session.create" })
        assertEquals(0, harness.rpcLog.count { it.first == "prompt.submit" })
    }

    // --- HRUI-016: long / fire-and-forget prompt.submit ack semantics.
    // Upstream treats prompt.submit as a long-running RPC (desktop passes a
    // 30-min PROMPT_SUBMIT_REQUEST_TIMEOUT_MS at every call site) because the
    // ack can trail a MoA/deep-reasoning/tool-heavy turn by minutes. A short
    // ack timeout used to preflight-fail into the SSE fallback → the same
    // prompt ran twice. ---

    @Test
    fun `slow prompt submit ack outlives the generic rpc timeout without SSE fallback`() {
        // Shrink the GENERIC rpc timeout below the ack delay: if prompt.submit
        // (wrongly) rode the generic timeout again, the submit would fail at
        // 500ms and the preflight fallback would fire — failing this test.
        rebuildClient(rpcTimeoutMs = 500L)
        harness.suppressAckMethods.add("prompt.submit")
        val r = Recorder()
        client.sendTurn(null, "deep thought", null, r.callbacks) { r.preflightFailures += it }
        val serverWs = harness.awaitServerSocket()
        val ack = harness.awaitPendingAck()

        // Ack arrives well after the generic rpc timeout would have fired.
        Thread.sleep(1_500)
        harness.releaseAck(ack)

        serverWs.send(harness.eventFrame("message.delta", buildJsonObject { put("text", "42") }, "live-1"))
        serverWs.send(harness.eventFrame("message.complete", buildJsonObject { put("text", "42") }, "live-1"))

        assertTrue("turn never completed", r.completeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("42"), r.textDeltas.toList())
        assertTrue("slow ack must not preflight-fail (duplicate turn)", r.preflightFailures.isEmpty())
        assertTrue("slow ack must not surface a stream error, got ${r.errors}", r.errors.isEmpty())
        assertEquals(1, harness.rpcLog.count { it.first == "prompt.submit" })
    }

    @Test
    fun `turn completes when the ack never arrives and the late ack timeout does not fall back`() {
        // Shrink the SUBMIT timeout so its late failure fires inside the test
        // budget — after the turn has already completed via stream events.
        rebuildClient(promptSubmitTimeoutMs = 1_000L)
        harness.suppressAckMethods.add("prompt.submit")
        val r = Recorder()
        client.sendTurn(null, "hello", null, r.callbacks) { r.preflightFailures += it }
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        serverWs.send(harness.eventFrame("message.delta", buildJsonObject { put("text", "Hi!") }, "live-1"))
        serverWs.send(harness.eventFrame("message.complete", buildJsonObject { put("text", "Hi!") }, "live-1"))
        assertTrue("turn never completed", r.completeLatch.await(5, TimeUnit.SECONDS))

        // Let the shortened ack timeout fire AFTER completion — the late
        // failure must not resurrect the finished turn on the SSE fallback.
        Thread.sleep(1_500)
        assertTrue("late ack timeout fired the SSE fallback (duplicate turn)", r.preflightFailures.isEmpty())
        assertTrue(r.errors.isEmpty())
        assertEquals(1, harness.rpcLog.count { it.first == "prompt.submit" })
    }

    @Test
    fun `recoverTurn activates exact live session and continues deltas and tool events`() {
        harness.recoveryRunning = true
        harness.recoveryAssistant = "partial answer"
        harness.recoveryInflightCorrections = listOf("Check the release branch", "Focus on Android")
        val recorder = Recorder()

        val recovery = runBlocking {
            client.recoverTurn(
                storedId = "stored-42",
                preferredLiveId = "live-original",
                callbacks = recorder.callbacks,
            ).getOrThrow()
        }

        assertTrue(recovery.running)
        assertEquals("live-original", recovery.liveSessionId)
        assertEquals("partial answer", recovery.inflight?.assistant)
        assertEquals(
            listOf("Check the release branch", "Focus on Android"),
            recovery.inflight?.corrections,
        )
        assertNotNull(recovery.handle)
        assertEquals(1, harness.rpcLog.count { it.first == "session.activate" })
        assertEquals(0, harness.rpcLog.count { it.first == "session.resume" })

        val serverWs = harness.awaitServerSocket()
        serverWs.send(
            harness.eventFrame(
                "reasoning.delta",
                buildJsonObject { put("text", "still thinking") },
                "live-original",
            ),
        )
        serverWs.send(
            harness.eventFrame(
                "tool.start",
                buildJsonObject {
                    put("tool_id", "tool-1")
                    put("name", "terminal")
                },
                "live-original",
            ),
        )
        serverWs.send(
            harness.eventFrame(
                "tool.complete",
                buildJsonObject {
                    put("tool_id", "tool-1")
                    put("name", "terminal")
                    put("summary", "tests passed")
                },
                "live-original",
            ),
        )
        serverWs.send(
            harness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", " final") },
                "live-original",
            ),
        )
        serverWs.send(
            harness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "partial answer final") },
                "live-original",
            ),
        )

        assertTrue(recorder.completeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("still thinking"), recorder.thinkingDeltas.toList())
        assertEquals(listOf("tool-1" to "terminal"), recorder.toolStarts.toList())
        assertEquals(listOf("tool-1" to "tests passed"), recorder.toolDone.toList())
        assertEquals(listOf(" final"), recorder.textDeltas.toList())
    }

    @Test
    fun `recoverTurn falls back to durable resume when activate is unsupported`() {
        harness.methodNotFound += "session.activate"
        harness.recoveryRunning = true
        val recorder = Recorder()

        val recovery = runBlocking {
            client.recoverTurn(
                storedId = "stored-42",
                preferredLiveId = "expired-live-id",
                callbacks = recorder.callbacks,
            ).getOrThrow()
        }

        assertTrue(recovery.running)
        assertEquals("live-resumed", recovery.liveSessionId)
        assertNotNull(recovery.handle)
        assertEquals(1, harness.rpcLog.count { it.first == "session.activate" })
        assertEquals(1, harness.rpcLog.count { it.first == "session.resume" })

        val serverWs = harness.awaitServerSocket()
        serverWs.send(
            harness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "recovered") },
                "live-resumed",
            ),
        )
        assertTrue(recorder.completeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("recovered"), recorder.textDeltas.toList())
    }

    @Test
    fun `profile mismatch clears buffered recovery events before retry`() = runBlocking {
        client.sessionProfileProvider = { "mizu" }
        harness.sessionProfileOverride = "default"
        harness.recoveryRunning = true
        harness.suppressAckMethods += "session.resume"
        val rejectedRecorder = Recorder()

        val rejected = async(Dispatchers.IO) {
            client.recoverTurn("stored-42", null, rejectedRecorder.callbacks)
        }
        val ack = harness.awaitPendingAck()
        ack.ws.send(
            harness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "stale mismatch delta") },
                "live-resumed",
            ),
        )
        harness.releaseAck(ack, harness.recoveryResult("live-resumed"))

        assertTrue(rejected.await().exceptionOrNull() is GatewayPreflightException)
        assertTrue(rejectedRecorder.textDeltas.isEmpty())

        harness.suppressAckMethods -= "session.resume"
        harness.sessionProfileOverride = "mizu"
        val retryRecorder = Recorder()
        val retry = client.recoverTurn("stored-42", null, retryRecorder.callbacks).getOrThrow()

        assertTrue(retry.running)
        assertTrue(retryRecorder.textDeltas.isEmpty())
        retry.handle?.detach()
        Unit
    }

    @Test
    fun `recoverTurn keeps queued-only resume live`() {
        harness.recoveryQueuedUser = "do this next"
        val queuedRecorder = Recorder()

        val recovery = runBlocking {
            client.recoverTurn(
                "stored-42",
                null,
                Recorder().callbacks,
                queuedTurnProvider = {
                    GatewayInboundTurnRegistration(queuedRecorder.callbacks) { true }
                },
            ).getOrThrow()
        }

        assertFalse(recovery.running)
        assertTrue(recovery.hasPendingWork)
        assertEquals("do this next", recovery.queued?.user)
        assertNull(recovery.inflight)
        assertNotNull(recovery.handle)
        recovery.handle!!.detach()
    }

    @Test
    fun `queued-only activation reroutes events received before acknowledgement`() {
        runBlocking {
            harness.recoveryQueuedUser = "do this next"
            harness.suppressAckMethods += "session.activate"
            val priorRecorder = Recorder()
            val queuedRecorder = Recorder()

            val pending = async(Dispatchers.IO) {
                client.recoverTurn(
                    "stored-42",
                    "live-queued",
                    priorRecorder.callbacks,
                    queuedTurnProvider = {
                        GatewayInboundTurnRegistration(queuedRecorder.callbacks) { true }
                    },
                ).getOrThrow()
            }
            val ack = harness.awaitPendingAck()
            ack.ws.send(harness.eventFrame("message.start", null, "live-queued"))
            ack.ws.send(
                harness.eventFrame(
                    "message.delta",
                    buildJsonObject { put("text", "queued answer") },
                    "live-queued",
                ),
            )
            harness.releaseAck(ack, harness.recoveryResult("live-queued"))

            val recovery = pending.await()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (queuedRecorder.textDeltas.isEmpty() && System.nanoTime() < deadline) delay(10)
            assertEquals(emptyList<String>(), priorRecorder.textDeltas.toList())
            assertEquals(listOf("queued answer"), queuedRecorder.textDeltas.toList())
            recovery.handle?.detach()
        }
    }

    @Test
    fun `recoverTurn keeps inflight-only resume live`() {
        harness.recoveryInflightStreaming = true
        harness.recoveryAssistant = "partial"

        val recovery = runBlocking {
            client.recoverTurn("stored-42", null, Recorder().callbacks).getOrThrow()
        }

        assertTrue(recovery.running)
        assertTrue(recovery.hasPendingWork)
        assertEquals("partial", recovery.inflight?.assistant)
        assertNull(recovery.queued)
        assertNotNull(recovery.handle)
        recovery.handle!!.detach()
    }

    @Test
    fun `recoverTurn exposes retained terminal failure without live handle`() {
        harness.recoveryInflightStreaming = false
        harness.recoveryAssistant = "partial answer"
        harness.recoveryInflightError = "provider failed"
        harness.recoveryInflightRecoverable = true

        val recovery = runBlocking {
            client.recoverTurn(
                "stored-42",
                null,
                Recorder().callbacks,
            ).getOrThrow()
        }

        assertFalse(recovery.running)
        assertFalse(recovery.hasPendingWork)
        assertEquals("error", recovery.inflight?.status)
        assertEquals("provider failed", recovery.inflight?.error)
        assertTrue(recovery.inflight?.recoverable == true)
        assertNull(recovery.handle)
    }

    @Test
    fun `auto continue buffers message start racing resume acknowledgement`() {
        runBlocking {
            harness.recoveryAutoContinueAttempt = 1
            harness.suppressAckMethods += "session.resume"
            val recorder = Recorder()

            val pending = async(Dispatchers.IO) {
                client.recoverTurn(
                    "stored-42",
                    null,
                    recorder.callbacks,
                ).getOrThrow()
            }
            val ack = harness.awaitPendingAck()
            assertEquals("session.resume", ack.method)
            val liveId = (harness.recoveryResult("stored-42")
                .getValue("session_id") as JsonPrimitive).content
            ack.ws.send(harness.eventFrame("message.start", null, liveId))
            ack.ws.send(
                harness.eventFrame(
                    "message.delta",
                    buildJsonObject { put("text", "continued answer") },
                    liveId,
                ),
            )
            harness.releaseAck(ack, harness.recoveryResult(liveId))

            val recovery = pending.await()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (recorder.textDeltas.isEmpty() && System.nanoTime() < deadline) delay(10)
            assertEquals(1, recovery.autoContinue?.attempt)
            assertTrue(recovery.hasPendingWork)
            assertEquals(listOf("continued answer"), recorder.textDeltas.toList())
            recovery.handle?.detach()
        }
    }

    @Test
    fun `resume race delivers auto continue events through exactly one owner`() {
        runBlocking {
            // Keep the recovered live id warm so the early message.start could be
            // accepted by normal unsolicited routing while session.resume is also
            // buffering it. Recovery must exclusively claim the frame instead.
            assertTrue(client.prewarmAwait("stored-42"))
            harness.recoveryAutoContinueAttempt = 1
            harness.suppressAckMethods += "session.resume"
            val recorder = Recorder()
            client.setUnsolicitedTurnProvider {
                GatewayInboundTurnRegistration(recorder.callbacks) { true }
            }

            val pending = async(Dispatchers.IO) {
                client.recoverTurn(
                    "stored-42",
                    null,
                    recorder.callbacks,
                ).getOrThrow()
            }
            val ack = harness.awaitPendingAck()
            assertEquals("session.resume", ack.method)
            val liveId = (harness.recoveryResult("stored-42")
                .getValue("session_id") as JsonPrimitive).content
            ack.ws.send(harness.eventFrame("message.start", null, liveId))
            ack.ws.send(
                harness.eventFrame(
                    "message.delta",
                    buildJsonObject { put("text", "continued once") },
                    liveId,
                ),
            )
            harness.releaseAck(ack, harness.recoveryResult(liveId))

            val recovery = pending.await()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (recorder.textDeltas.isEmpty() && System.nanoTime() < deadline) delay(10)
            assertEquals(listOf("continued once"), recorder.textDeltas.toList())
            recovery.handle?.detach()
        }
    }

    @Test
    fun `recoverTurn keeps inflight and queued resume live`() {
        harness.recoveryInflightStreaming = true
        harness.recoveryQueuedUser = "follow up"
        val priorRecorder = Recorder()
        val queuedRecorder = Recorder()

        val recovery = runBlocking {
            client.recoverTurn(
                "stored-42",
                null,
                priorRecorder.callbacks,
                queuedTurnProvider = {
                    GatewayInboundTurnRegistration(queuedRecorder.callbacks) { true }
                },
            ).getOrThrow()
        }

        assertTrue(recovery.running)
        assertTrue(recovery.hasPendingWork)
        assertEquals("follow up", recovery.queued?.user)
        assertNotNull(recovery.inflight)
        assertNotNull(recovery.handle)

        val serverWs = harness.awaitServerSocket()
        serverWs.send(
            harness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "current answer") },
                "live-resumed",
            ),
        )
        assertTrue(priorRecorder.completeLatch.await(5, TimeUnit.SECONDS))
        serverWs.send(harness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            harness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "queued answer") },
                "live-resumed",
            ),
        )
        serverWs.send(
            harness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "queued answer") },
                "live-resumed",
            ),
        )

        assertTrue(queuedRecorder.completeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("current answer"), priorRecorder.textDeltas.toList())
        assertEquals(listOf("queued answer"), queuedRecorder.textDeltas.toList())
    }

    @Test
    fun `inflight and queued activation preserves turn boundary before acknowledgement`() {
        runBlocking {
            harness.recoveryInflightStreaming = true
            harness.recoveryQueuedUser = "follow up"
            harness.suppressAckMethods += "session.activate"
            val priorRecorder = Recorder()
            val queuedRecorder = Recorder()

            val pending = async(Dispatchers.IO) {
                client.recoverTurn(
                    "stored-42",
                    "live-running-queued",
                    priorRecorder.callbacks,
                    queuedTurnProvider = {
                        GatewayInboundTurnRegistration(queuedRecorder.callbacks) { true }
                    },
                ).getOrThrow()
            }
            val ack = harness.awaitPendingAck()
            ack.ws.send(
                harness.eventFrame(
                    "message.delta",
                    buildJsonObject { put("text", " current") },
                    "live-running-queued",
                ),
            )
            ack.ws.send(
                harness.eventFrame(
                    "message.complete",
                    buildJsonObject { put("text", "current") },
                    "live-running-queued",
                ),
            )
            ack.ws.send(harness.eventFrame("message.start", null, "live-running-queued"))
            ack.ws.send(
                harness.eventFrame(
                    "message.delta",
                    buildJsonObject { put("text", "queued") },
                    "live-running-queued",
                ),
            )
            harness.releaseAck(ack, harness.recoveryResult("live-running-queued"))

            val recovery = pending.await()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (queuedRecorder.textDeltas.isEmpty() && System.nanoTime() < deadline) delay(10)
            assertEquals(listOf(" current"), priorRecorder.textDeltas.toList())
            assertEquals(listOf("queued"), queuedRecorder.textDeltas.toList())
            recovery.handle?.detach()
        }
    }

    @Test
    fun `recoverTurn settles resume with neither inflight nor queued work`() {
        val recovery = runBlocking {
            client.recoverTurn("stored-42", null, Recorder().callbacks).getOrThrow()
        }

        assertFalse(recovery.running)
        assertFalse(recovery.hasPendingWork)
        assertNull(recovery.inflight)
        assertNull(recovery.queued)
        assertNull(recovery.handle)
    }

    @Test
    fun `recoverTurn parses optional session project`() {
        harness.recoveryProject = buildJsonObject {
            put("id", "project-17")
            put("slug", "hermes-relay")
            put("name", "Hermes Relay")
            put("primary_path", "/workspace/hermes-relay")
            put("future_field", "ignored")
        }

        runBlocking {
            client.recoverTurn("stored-42", null, Recorder().callbacks).getOrThrow()
        }

        assertEquals("project-17", client.serverProject.value?.id)
        assertEquals("hermes-relay", client.serverProject.value?.slug)
        assertEquals("Hermes Relay", client.serverProject.value?.name)
        assertEquals("/workspace/hermes-relay", client.serverProject.value?.primaryPath)
    }

    @Test
    fun `recoverTurn clears project for legacy session info`() {
        harness.recoveryProject = buildJsonObject { put("name", "Previous Project") }
        runBlocking {
            client.recoverTurn("stored-42", null, Recorder().callbacks).getOrThrow()
        }
        assertEquals("Previous Project", client.serverProject.value?.name)

        harness.recoveryProject = null
        runBlocking {
            client.recoverTurn("stored-42", null, Recorder().callbacks).getOrThrow()
        }

        assertNull(client.serverProject.value)
    }

    @Test
    fun `recoverTurn returns no handle for an already-settled session`() {
        harness.recoveryRunning = false

        val recovery = runBlocking {
            client.recoverTurn(
                storedId = "stored-42",
                preferredLiveId = "live-original",
                callbacks = Recorder().callbacks,
            ).getOrThrow()
        }

        assertFalse(recovery.running)
        assertEquals("idle", recovery.status)
        assertNull(recovery.handle)
        assertFalse(client.hasActiveTurn())
        assertTrue(harness.rpcLog.none { it.first == "session.interrupt" })
    }

    @Test
    fun `detaching recovered handle does not interrupt server turn`() {
        harness.recoveryRunning = true
        val recovery = runBlocking {
            client.recoverTurn(
                storedId = "stored-42",
                preferredLiveId = "live-original",
                callbacks = Recorder().callbacks,
            ).getOrThrow()
        }

        recovery.handle!!.detach()
        Thread.sleep(100)

        assertFalse(client.hasActiveTurn())
        assertTrue(harness.rpcLog.none { it.first == "session.interrupt" })
    }

    @Test
    fun `backgrounding active turn lets another profile bind while original completes`() {
        val foreground = Recorder()
        val reconciled = ConcurrentLinkedQueue<Pair<String, String?>>()
        client.setUnmatchedTurnCompleteListener { completion ->
            reconciled.add(completion.storedSessionId to completion.expectedAssistantText)
        }
        client.sessionProfileProvider = { "coder" }
        client.sendTurn(null, "long task", null, foreground.callbacks) {
            foreground.preflightFailures += it
        }
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        assertTrue(client.backgroundActiveTurn())
        client.clearSession()
        client.sessionProfileProvider = { "writer" }

        // The original server-side turn remains alive and is reconciled by its
        // durable id even though the visible profile has moved on.
        serverWs.send(
            harness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "coder finished") },
                "live-1",
            ),
        )

        repeat(50) {
            if (reconciled.isNotEmpty()) return@repeat
            Thread.sleep(20)
        }
        assertEquals(
            listOf("20260612_120000_abc123" to "coder finished"),
            reconciled.toList(),
        )
        assertFalse(client.hasActiveTurn())
        assertTrue(harness.rpcLog.none { it.first == "session.interrupt" })
    }

    @Test
    fun `recoverTurn reclaims a deliberately backgrounded live session`() {
        val original = Recorder()
        client.sendTurn(null, "long task", null, original.callbacks) {
            original.preflightFailures += it
        }
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")
        assertTrue(client.backgroundActiveTurn())

        harness.recoveryRunning = true
        harness.recoveryAssistant = "partial answer"
        val resumed = Recorder()
        val recovery = runBlocking {
            client.recoverTurn(
                storedId = "20260612_120000_abc123",
                preferredLiveId = "live-1",
                callbacks = resumed.callbacks,
            ).getOrThrow()
        }

        assertTrue(recovery.running)
        assertNotNull(recovery.handle)
        serverWs.send(
            harness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", " finished") },
                "live-1",
            ),
        )
        serverWs.send(
            harness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "partial answer finished") },
                "live-1",
            ),
        )

        assertTrue(resumed.completeLatch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(" finished"), resumed.textDeltas.toList())
        assertTrue(harness.rpcLog.none { it.first == "session.interrupt" })
    }

    @Test
    fun `detached interaction survives activity and expires only from explicit event`() {
        val original = Recorder()
        val backgroundEvents = ConcurrentLinkedQueue<GatewayBackgroundInteractionEvent>()
        client.setBackgroundInteractionListener(backgroundEvents::add)
        client.sessionProfileProvider = { "work" }
        harness.sessionProfileOverride = "work"
        client.sendTurn(null, "long task", null, original.callbacks) {
            original.preflightFailures += it
        }
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")
        assertTrue(client.backgroundActiveTurn())

        serverWs.send(
            harness.eventFrame(
                "approval.request",
                buildJsonObject { put("command", "redacted command") },
                "live-1",
            ),
        )
        repeat(50) {
            if (backgroundEvents.isNotEmpty()) return@repeat
            Thread.sleep(20)
        }
        val requested = backgroundEvents.single() as GatewayBackgroundInteractionEvent.Requested
        assertEquals("20260612_120000_abc123", requested.storedSessionId)
        assertEquals("work", requested.profile)
        assertEquals(GatewayAsk.Kind.APPROVAL, requested.ask.kind)

        harness.recoveryRunning = true
        val resumed = Recorder()
        runBlocking {
            client.recoverTurn(
                storedId = "20260612_120000_abc123",
                preferredLiveId = "live-1",
                callbacks = resumed.callbacks,
            ).getOrThrow()
        }
        repeat(50) {
            if (resumed.interactions.isNotEmpty()) return@repeat
            Thread.sleep(20)
        }
        assertEquals(GatewayAsk.Kind.APPROVAL, resumed.interactions.single().kind)

        serverWs.send(
            harness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "continued") },
                "live-1",
            ),
        )
        serverWs.send(
            harness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "continued") },
                "live-1",
            ),
        )
        Thread.sleep(100)
        assertEquals(1, resumed.interactions.size)
        assertTrue(resumed.interactionExpiries.isEmpty())
        assertEquals(1L, resumed.completeLatch.count)
        assertTrue(harness.rpcLog.none { it.first == "approval.respond" })

        serverWs.send(harness.eventFrame("approval.expire", buildJsonObject { }, "live-1"))
        repeat(50) {
            if (resumed.interactionExpiries.isNotEmpty()) return@repeat
            Thread.sleep(20)
        }
        assertEquals(
            GatewayAskExpiry(GatewayAsk.Kind.APPROVAL, null),
            resumed.interactionExpiries.single(),
        )
        assertTrue(resumed.completeLatch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `terminal read request is answered empty without user interaction`() {
        val recorder = Recorder()
        client.sendTurn(null, "inspect terminal", null, recorder.callbacks) {
            recorder.preflightFailures += it
        }
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        serverWs.send(
            harness.eventFrame(
                "terminal.read.request",
                buildJsonObject {
                    put("request_id", "terminal-1")
                    put("start", 0)
                    put("count", 20)
                },
                "live-1",
            ),
        )

        val response = harness.awaitRpc("terminal.read.respond")
        assertEquals(JsonPrimitive("terminal-1"), response["request_id"])
        assertEquals(JsonPrimitive(""), response["text"])
        assertTrue(recorder.interactions.isEmpty())
    }

    @Test
    fun `background turn reconnects shared socket and reports completion`() {
        val original = Recorder()
        val completions = ConcurrentLinkedQueue<GatewayBackgroundTurnCompletion>()
        client.setUnmatchedTurnCompleteListener(completions::add)
        client.sendTurn(null, "long task", null, original.callbacks) {
            original.preflightFailures += it
        }
        val firstSocket = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")
        assertTrue(client.backgroundActiveTurn())

        firstSocket.close(1012, "route changed")
        val rejoinedSocket = harness.awaitServerSocket()
        rejoinedSocket.send(
            harness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "finished offscreen") },
                "live-1",
            ),
        )

        repeat(100) {
            if (completions.isNotEmpty()) return@repeat
            Thread.sleep(20)
        }
        assertEquals("20260612_120000_abc123", completions.single().storedSessionId)
        assertEquals("finished offscreen", completions.single().expectedAssistantText)
        assertTrue(client.hasActiveTurn().not())
        assertTrue(harness.ticketMints.get() >= 2)
    }

    @Test
    fun `idle watchdog does not fire while events keep arriving slowly`() {
        rebuildClient(turnIdleTimeoutMs = 1_000L)
        val r = Recorder()
        client.sendTurn(null, "slow drip", null, r.callbacks) { r.preflightFailures += it }
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")

        // Each event lands inside the (shortened) idle window but the run's
        // TOTAL wall-clock far exceeds it — an idle-progress watchdog stays
        // quiet; a hard turn cap would have killed the turn.
        repeat(8) { i ->
            serverWs.send(
                harness.eventFrame("message.delta", buildJsonObject { put("text", "d$i ") }, "live-1"),
            )
            Thread.sleep(250)
        }
        serverWs.send(harness.eventFrame("message.complete", buildJsonObject { put("text", "done") }, "live-1"))

        assertTrue("turn never completed", r.completeLatch.await(5, TimeUnit.SECONDS))
        assertTrue("watchdog fired despite live events: ${r.errors}", r.errors.isEmpty())
        assertTrue(r.preflightFailures.isEmpty())
        assertTrue(
            "watchdog must not have interrupted a live turn",
            harness.rpcLog.none { it.first == "session.interrupt" },
        )
    }

    @Test
    fun `idle watchdog fires when events stop flowing`() {
        rebuildClient(turnIdleTimeoutMs = 500L)
        val r = Recorder()
        client.sendTurn(null, "stalls", null, r.callbacks) { r.preflightFailures += it }
        val serverWs = harness.awaitServerSocket()
        harness.awaitRpc("prompt.submit")
        serverWs.send(harness.eventFrame("message.delta", buildJsonObject { put("text", "partial") }, "live-1"))
        // …then silence: the idle watchdog must fail the turn as a STREAM
        // error (never a preflight fallback — the turn started server-side)
        // and interrupt the server so it stops generating.
        assertTrue("watchdog never fired", r.completeLatch.await(5, TimeUnit.SECONDS))
        assertTrue("expected a stream error from the watchdog", r.errors.isNotEmpty())
        assertTrue(r.preflightFailures.isEmpty())
        harness.awaitRpc("session.interrupt")
    }
}
