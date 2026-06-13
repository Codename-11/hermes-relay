package com.hermesandroid.relay.network

import android.util.Log
import com.hermesandroid.relay.util.AppForegroundTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Chat transport over upstream hermes-agent's `tui_gateway` JSON-RPC
 * WebSocket at the dashboard's `/api/ws` — the surface the official
 * hermes-desktop client speaks, and the only upstream surface that streams
 * reasoning live (`reasoning.delta`). See `GatewayModels.kt` for context and
 * `desktop/src/gatewayTypes.ts` for the vendored wire shapes.
 *
 * Lifecycle: lazy connect on first [sendTurn]; stays connected while the app
 * is foregrounded; 30s after backgrounding the socket closes unless a turn
 * is in flight (the server detaches sessions to a grace-windowed orphan
 * reaper — a later `session.resume` picks the conversation back up). No
 * background reconnect loops; reconnection happens on the next send.
 *
 * Auth: every connect attempt mints a FRESH single-use ws-ticket (30s TTL)
 * via [DashboardApiClient.requestWsTicket] — tickets must never be reused
 * across attempts.
 *
 * Threading: all [GatewayTurnCallbacks] invocations are marshalled through
 * [callbackDispatcher] (main thread in production, inline in tests) —
 * matching HermesApiClient's mainHandler.post contract so ChatHandler
 * mutations stay on the main thread.
 */
class GatewayChatClient(
    private val dashboardClient: DashboardApiClient,
    okHttpClient: OkHttpClient? = null,
    private val callbackDispatcher: (block: () -> Unit) -> Unit = MainThreadDispatcher,
    /** Surface for "this server has no usable /api/ws" — flips availability to Unsupported. */
    private val onGatewayUnsupported: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    companion object {
        private const val TAG = "GatewayChatClient"

        /** Mirrors the desktop CLI's turn timeout — reset on every received event. */
        private const val TURN_TIMEOUT_MS = 180_000L

        /**
         * Ask requests block the agent server-side with NO events flowing
         * until answered — arm with headroom over each kind's upstream block
         * timeout (clarify/secret 300s, sudo 120s; approval/terminal-read
         * unbounded). The next regular event rearms [TURN_TIMEOUT_MS].
         */
        private const val ASK_CLARIFY_SECRET_TIMEOUT_MS = 330_000L
        private const val ASK_SUDO_TIMEOUT_MS = 150_000L
        private const val ASK_UNBOUNDED_TIMEOUT_MS = 600_000L

        private fun watchdogTimeoutFor(eventType: String): Long = when (eventType) {
            "clarify.request", "secret.request" -> ASK_CLARIFY_SECRET_TIMEOUT_MS
            "sudo.request" -> ASK_SUDO_TIMEOUT_MS
            "approval.request", "terminal.read.request" -> ASK_UNBOUNDED_TIMEOUT_MS
            else -> TURN_TIMEOUT_MS
        }

        private const val RPC_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L

        /** Image uploads ship whole base64 frames (≤25MB server cap) — 15s is not enough. */
        private const val ATTACH_RPC_TIMEOUT_MS = 60_000L

        /** Upstream upload RPC name (underscore — `image.attach_bytes`, content_base64). */
        private const val ATTACH_METHOD_UPSTREAM = "image.attach_bytes"

        /** Legacy desktop-CLI name (dots — `image.attach.bytes`, bytes_base64/format). */
        private const val ATTACH_METHOD_LEGACY = "image.attach.bytes"

        /** Grace before closing an idle socket after the app backgrounds. */
        private const val BACKGROUND_CLOSE_GRACE_MS = 30_000L

        /** Cooldown after a failed connect so rapid sends don't hammer a down server. */
        private const val CONNECT_FAILURE_COOLDOWN_MS = 5_000L
        private const val RATE_LIMIT_COOLDOWN_MS = 300_000L
        private const val CONNECT_ATTEMPTS = 2

        /** Socket losses answered with reconnect+resume per turn before giving up. */
        private const val MAX_TURN_REJOINS = 2

        private const val DEFAULT_COLS = 80

        private object MainThreadDispatcher : (() -> Unit) -> Unit {
            // Lazy so JVM unit tests never touch android.os.Looper.
            private val handler by lazy {
                android.os.Handler(android.os.Looper.getMainLooper())
            }

            override fun invoke(block: () -> Unit) {
                handler.post(block)
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient = (okHttpClient ?: OkHttpClient())
        .newBuilder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _connectionState = MutableStateFlow(GatewayConnectionState.Idle)
    val connectionState: StateFlow<GatewayConnectionState> = _connectionState.asStateFlow()

    /** Serializes connect / session-establish so concurrent sends share one socket. */
    private val connectMutex = Mutex()

    @Volatile
    private var webSocket: WebSocket? = null

    /** Completed when the server's `gateway.ready` event arrives for the current socket. */
    @Volatile
    private var readySignal: CompletableDeferred<Unit>? = null

    private val rpcId = AtomicLong(1)
    private val pendingRpcs = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()

    /** Live (per-connection) session id ←→ the stored DB id it was resumed/created from. */
    @Volatile
    private var liveSessionId: String? = null

    @Volatile
    private var storedSessionId: String? = null

    @Volatile
    private var activeTurn: GatewayTurn? = null

    /**
     * Which upload RPC name this socket understands — set after the first
     * successful upload so the legacy fallback is probed at most once per
     * socket lifetime. Reset on socket loss.
     */
    @Volatile
    private var attachMethodForSocket: String? = null

    /** `commands.catalog` result for the current socket — invalidated on socket loss. */
    @Volatile
    private var commandsCatalogCache: JsonObject? = null

    @Volatile
    private var connectCooldownUntil: Long = 0L

    private var backgroundCloseJob: Job? = null

    init {
        scope.launch {
            AppForegroundTracker.isForeground.collect { foreground ->
                if (foreground) {
                    backgroundCloseJob?.cancel()
                    backgroundCloseJob = null
                } else {
                    scheduleBackgroundClose()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Run one prompt→response turn.
     *
     * @param sessionId stored (DB) session id to resume, or null for a fresh
     *   session. On create/rotate the new stored id is reported via
     *   [GatewayTurnCallbacks.onSessionId].
     * @param newSessionTitle title applied when a fresh session is created.
     * @param attachments image attachments uploaded onto the session between
     *   session establish and `prompt.submit` — upstream snapshots+clears the
     *   session's queued images at turn start, so they bind to THIS turn.
     *   Images only; non-image attachments stay on the SSE fallback path.
     * @param truncateBeforeUserOrdinal edit-and-regenerate: 0-based index
     *   into the session's USER messages (counted from the first user
     *   message). The server drops that message and everything after it
     *   before running [text] as a fresh turn.
     * @param onPreflightFailure invoked INSTEAD of starting the turn when the
     *   gateway could not be reached / authenticated / the prompt could not
     *   be submitted — i.e. nothing started server-side, so the caller can
     *   safely fall back to an SSE endpoint for this turn. After
     *   `prompt.submit` succeeds, failures surface via callbacks.onError.
     */
    fun sendTurn(
        sessionId: String?,
        text: String,
        newSessionTitle: String?,
        callbacks: GatewayTurnCallbacks,
        attachments: List<GatewayImageAttachment> = emptyList(),
        truncateBeforeUserOrdinal: Int? = null,
        onPreflightFailure: (reason: String) -> Unit,
    ): ActiveTurnHandle {
        val turn = GatewayTurn(dispatchOn(callbacks))
        scope.launch {
            try {
                connectMutex.withLock {
                    ensureConnected()
                    ensureSession(sessionId, newSessionTitle, turn)
                }
                if (turn.cancelled) return@launch
                attachments.forEach { attachment ->
                    uploadImage(attachment).getOrElse { e ->
                        throw GatewayPreflightException("image upload failed: ${e.message}")
                    }
                }
                if (turn.cancelled) return@launch
                activeTurn = turn
                turn.armWatchdog()
                val submitted = rpc(
                    "prompt.submit",
                    buildJsonObject {
                        put("session_id", liveSessionId ?: error("no live session"))
                        put("text", text)
                        truncateBeforeUserOrdinal?.let { put("truncate_before_user_ordinal", it) }
                    },
                )
                if (submitted.isFailure) {
                    activeTurn = null
                    turn.disarmWatchdog()
                    throw GatewayPreflightException(
                        submitted.exceptionOrNull()?.message ?: "prompt.submit failed",
                    )
                }
                // One INFO line per turn so logcat shows which transport
                // served a send — the SSE paths log their SSE events, and
                // a silent happy path here made on-device verification a
                // read-the-absence exercise.
                Log.i(TAG, "Gateway turn submitted (session=$storedSessionId)")
            } catch (e: Exception) {
                activeTurn = null
                if (!turn.cancelled) {
                    Log.w(TAG, "Gateway preflight failed: ${e.message}")
                    callbackDispatcher { onPreflightFailure(e.message ?: "gateway unavailable") }
                }
            }
        }
        return turn
    }

    /** Drop the remembered session so the next send creates a fresh one. */
    fun clearSession() {
        liveSessionId = null
        storedSessionId = null
    }

    /**
     * Inject [text] into the in-flight turn (`session.steer`). The server
     * only accepts a steer while a tool batch is running — [SteerResult.Rejected]
     * means "no batch in flight, queue it instead". [SteerResult.Failed]
     * covers transport/RPC failure (no live session, socket down, 4010 …) —
     * callers should fall back to the local queue for both non-queued cases.
     */
    suspend fun steer(text: String): SteerResult {
        val sid = liveSessionId ?: return SteerResult.Failed
        val result = rpc(
            "session.steer",
            buildJsonObject {
                put("session_id", sid)
                put("text", text)
            },
        )
        val outcome = when (result.getOrNull()?.stringField("status")) {
            "queued" -> SteerResult.Queued
            "rejected" -> SteerResult.Rejected
            else -> SteerResult.Failed
        }
        Log.i(TAG, "Steer → $outcome (session=$storedSessionId)")
        return outcome
    }

    /** Answer a [GatewayAsk.Kind.CLARIFY] ask. */
    suspend fun respondClarify(requestId: String, answer: String): Result<Unit> =
        rpc(
            "clarify.respond",
            buildJsonObject {
                put("request_id", requestId)
                put("answer", answer)
            },
        ).map { }

    /**
     * Answer a [GatewayAsk.Kind.SUDO] ask. The password must NEVER be logged
     * or persisted — it exists only inside this outbound frame.
     */
    suspend fun respondSudo(requestId: String, password: String): Result<Unit> =
        rpc(
            "sudo.respond",
            buildJsonObject {
                put("request_id", requestId)
                put("password", password)
            },
        ).map { }

    /**
     * Answer a [GatewayAsk.Kind.SECRET] ask. Empty [value] = skip (upstream
     * returns `skipped: true` to the tool). The value must NEVER be logged
     * or persisted — it exists only inside this outbound frame.
     */
    suspend fun respondSecret(requestId: String, value: String): Result<Unit> =
        rpc(
            "secret.respond",
            buildJsonObject {
                put("request_id", requestId)
                put("value", value)
            },
        ).map { }

    /**
     * Answer a [GatewayAsk.Kind.APPROVAL] ask — correlated by the live
     * session, not a request id. [choice] is "approve" or "deny"; [all]
     * resolves every pending approval on the session at once.
     */
    suspend fun respondApproval(choice: String, all: Boolean = false): Result<Unit> {
        val sid = liveSessionId
            ?: return Result.failure(GatewayRpcException("no live session"))
        return rpc(
            "approval.respond",
            buildJsonObject {
                put("session_id", sid)
                put("choice", choice)
                put("all", all)
            },
        ).map { }
    }

    /**
     * Server slash-command catalog (`commands.catalog`), cached per socket —
     * the command set only changes with server config, so one fetch per
     * connection is enough. Default [connectIfNeeded] = false fails fast
     * (no ticket mint) when no socket is ready — a catalog fetch must never
     * be the reason /api/ws cold-opens.
     */
    suspend fun commandsCatalog(connectIfNeeded: Boolean = false): Result<JsonObject> {
        commandsCatalogCache?.let { return Result.success(it) }
        if (!connectIfNeeded && (webSocket == null || readySignal?.isCompleted != true)) {
            return Result.failure(GatewayRpcException("not connected"))
        }
        try {
            connectMutex.withLock { ensureConnected() }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return rpc("commands.catalog", JsonObject(emptyMap()))
            .onSuccess { commandsCatalogCache = it }
    }

    /**
     * Run a full slash command line (`slash.exec {session_id, command}`) on
     * the live session. Returns the raw result object; failures carry the
     * JSON-RPC error code via [GatewayRpcException.code] — upstream rejects
     * pending-input/skill commands with 4018, and falling through to
     * [commandDispatch] on that code is the CALLER's job.
     */
    suspend fun slashExec(command: String): Result<JsonObject> {
        val sid = liveSessionId
            ?: return Result.failure(GatewayRpcException("no live session"))
        return rpc(
            "slash.exec",
            buildJsonObject {
                put("session_id", sid)
                put("command", command)
            },
        )
    }

    /**
     * Dispatch one resolved command (`command.dispatch {session_id?, name, arg?}`).
     * Returns the raw result union (`type`: exec/alias/plugin/skill/send/prefill);
     * failures carry the JSON-RPC error code via [GatewayRpcException.code].
     */
    suspend fun commandDispatch(name: String, arg: String? = null): Result<JsonObject> =
        rpc(
            "command.dispatch",
            buildJsonObject {
                liveSessionId?.let { put("session_id", it) }
                put("name", name)
                if (arg != null) put("arg", arg)
            },
        )

    fun shutdown() {
        activeTurn?.cancel()
        activeTurn = null
        closeSocket("client shutdown")
        backgroundCloseJob?.cancel()
        // Stop the foreground collector — a replaced client must not keep
        // observing AppForegroundTracker for the life of the process.
        scope.coroutineContext[Job]?.cancel()
    }

    // ------------------------------------------------------------------
    // Connection
    // ------------------------------------------------------------------

    /** Must hold [connectMutex]. Throws [GatewayPreflightException] on failure. */
    private suspend fun ensureConnected() {
        if (webSocket != null && readySignal?.isCompleted == true) return

        if (System.currentTimeMillis() < connectCooldownUntil) {
            throw GatewayPreflightException("gateway connect cooling down")
        }

        // Two attempts: a just-died socket (network switch, server restart)
        // can poison the first try via a stale pooled connection. Each
        // attempt mints a FRESH single-use ticket — never reuse one.
        var lastFailure = "gateway connect failed"
        repeat(CONNECT_ATTEMPTS) { attempt ->
            try {
                connectOnce()
                connectCooldownUntil = 0L
                return
            } catch (e: GatewayConnectAttemptException) {
                lastFailure = e.message ?: lastFailure
                Log.w(TAG, "Gateway connect attempt ${attempt + 1}/$CONNECT_ATTEMPTS failed: $lastFailure")
            }
        }
        connectCooldownUntil = System.currentTimeMillis() + CONNECT_FAILURE_COOLDOWN_MS
        _connectionState.value = GatewayConnectionState.Idle
        throw GatewayPreflightException(lastFailure)
    }

    private suspend fun connectOnce() {
        _connectionState.value = GatewayConnectionState.MintingTicket
        val ticket = dashboardClient.requestWsTicket().getOrElse { e ->
            throw GatewayConnectAttemptException("ws-ticket mint failed: ${e.message}")
        }
        val url = dashboardClient.gatewayWebSocketUrl(ticket.ticket)
            ?: throw GatewayConnectAttemptException("could not build /api/ws URL")

        _connectionState.value = GatewayConnectionState.Connecting
        val ready = CompletableDeferred<Unit>()
        readySignal = ready
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            createListener(ready),
        )
        webSocket = socket

        _connectionState.value = GatewayConnectionState.AwaitingReady
        val readyOk = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            runCatching { ready.await() }.isSuccess
        } ?: false
        if (!readyOk) {
            socket.cancel()
            webSocket = null
            throw GatewayConnectAttemptException("gateway.ready never arrived")
        }
        Log.i(TAG, "Gateway connected (/api/ws ready)")
        _connectionState.value = GatewayConnectionState.Ready
    }

    /** Must hold [connectMutex]. Resolves [liveSessionId] for the requested stored id. */
    private suspend fun ensureSession(
        requestedStoredId: String?,
        newSessionTitle: String?,
        turn: GatewayTurn,
    ) {
        if (liveSessionId != null && storedSessionId == requestedStoredId && requestedStoredId != null) {
            return
        }

        if (requestedStoredId != null) {
            val resumed = rpc(
                "session.resume",
                buildJsonObject {
                    put("session_id", requestedStoredId)
                    put("cols", DEFAULT_COLS)
                },
            )
            val live = resumed.getOrNull()?.stringField("session_id")
            if (live != null) {
                liveSessionId = live
                storedSessionId = requestedStoredId
                return
            }
            Log.w(
                TAG,
                "session.resume failed for $requestedStoredId — creating fresh " +
                    "(${resumed.exceptionOrNull()?.message})",
            )
        }

        val created = rpc(
            "session.create",
            buildJsonObject {
                put("cols", DEFAULT_COLS)
                if (!newSessionTitle.isNullOrBlank()) put("title", newSessionTitle)
            },
        ).getOrElse { e ->
            throw GatewayPreflightException("session.create failed: ${e.message}")
        }
        val live = created.stringField("session_id")
            ?: throw GatewayPreflightException("session.create returned no session_id")
        val stored = created.stringField("stored_session_id") ?: live
        liveSessionId = live
        storedSessionId = stored
        turn.callbacks.onSessionId(stored)
    }

    private fun createListener(ready: CompletableDeferred<Unit>) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            // Newline-delimited JSON-RPC: tolerate multiple objects per frame.
            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) handleFrame(trimmed, ready)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // OkHttp does NOT auto-acknowledge a peer-initiated close frame —
            // without this ack the socket sits half-closed for the close
            // timeout (~60s) and onClosed is badly delayed. Ack, then treat
            // the connection as gone immediately: the server is going away.
            webSocket.close(code, null)
            if (this@GatewayChatClient.webSocket === webSocket) {
                onSocketDown("closing: $code $reason")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (this@GatewayChatClient.webSocket === webSocket) {
                onSocketDown("closed: $code $reason")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (this@GatewayChatClient.webSocket !== webSocket) return
            when (response?.code) {
                404, 403 -> {
                    // No /api/ws on this build (or embedded chat disabled) —
                    // sticky downgrade so auto-resolution stops picking gateway.
                    Log.w(TAG, "Gateway WS upgrade rejected (${response.code}) — marking unsupported")
                    onGatewayUnsupported()
                }
                429 -> connectCooldownUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
            }
            if (!ready.isCompleted) ready.completeExceptionally(t)
            onSocketDown("failure: ${t.message}")
        }
    }

    private fun handleFrame(frameText: String, ready: CompletableDeferred<Unit>) {
        val frame = try {
            json.parseToJsonElement(frameText) as? JsonObject ?: return
        } catch (e: Exception) {
            Log.w(TAG, "Unparseable gateway frame (${frameText.length} chars): ${e.message}")
            return
        }

        // RPC response?
        val id = (frame["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        if (id != null && (frame.containsKey("result") || frame.containsKey("error"))) {
            val pending = pendingRpcs.remove(id) ?: return
            val error = frame["error"] as? JsonObject
            if (error != null) {
                val message = error.stringField("message") ?: "gateway rpc error"
                val code = (error["code"] as? JsonPrimitive)?.intOrNull
                pending.completeExceptionally(GatewayRpcException(message, code))
            } else {
                pending.complete(frame["result"] as? JsonObject ?: JsonObject(emptyMap()))
            }
            return
        }

        // Event notification?
        val method = frame.stringField("method")
        if (method != "event") return
        val params = frame["params"] as? JsonObject ?: return
        val type = params.stringField("type") ?: return
        val payload = params["payload"] as? JsonObject
        val eventSessionId = params.stringField("session_id")

        // Mirror HermesApiClient's per-event SSE logging — high-frequency
        // delta types log length only, everything else logs a payload
        // excerpt so on-device diagnosis doesn't read absences.
        when (type) {
            "message.delta", "reasoning.delta", "thinking.delta" ->
                Log.d(TAG, "GW ← $type (${payload?.toString()?.length ?: 0} chars)")
            else ->
                Log.d(TAG, "GW ← $type | ${payload?.toString()?.take(300) ?: "{}"}")
        }

        if (type == "gateway.ready") {
            ready.complete(Unit)
            return
        }

        val turn = activeTurn ?: return
        // Foreign-session events (another client's chat on the same gateway) are not ours.
        if (eventSessionId != null && liveSessionId != null && eventSessionId != liveSessionId) {
            return
        }
        turn.onEvent(type, payload)
        if (turn.ended) {
            activeTurn = null
            if (AppForegroundTracker.isForeground.value.not()) scheduleBackgroundClose()
        }
    }

    private fun onSocketDown(reason: String) {
        Log.i(TAG, "Gateway socket down ($reason)")
        webSocket = null
        readySignal = null
        liveSessionId = null
        attachMethodForSocket = null
        commandsCatalogCache = null
        _connectionState.value = GatewayConnectionState.Idle
        pendingRpcs.values.forEach {
            it.completeExceptionally(GatewayRpcException("gateway connection lost"))
        }
        pendingRpcs.clear()
        val turn = activeTurn ?: return
        if (turn.ended) {
            activeTurn = null
            return
        }
        // A rejoin already owns recovery — a connect attempt failing inside
        // it must not spawn a second concurrent rejoin.
        if (rejoinInProgress) return
        // A turn is in flight and the server is still generating into the
        // session (its orphan reaper holds it through a disconnect). Mobile
        // radios drop sockets mid-turn routinely (Wi-Fi power-save/roam —
        // ECONNABORTED), so try to rejoin: reconnect with a fresh ticket and
        // session.resume rebinds the live session to the new socket, and the
        // event stream continues — same recovery the desktop TUI relies on.
        if (turn.beginRejoin()) {
            rejoinInProgress = true
            scope.launch {
                try {
                    attemptMidTurnRejoin(turn)
                } finally {
                    rejoinInProgress = false
                }
            }
        } else {
            activeTurn = null
            turn.failFromTransport("Connection to the gateway was lost")
        }
    }

    @Volatile
    private var rejoinInProgress = false

    private suspend fun attemptMidTurnRejoin(turn: GatewayTurn) {
        val stored = storedSessionId
        val rejoined = if (stored == null) {
            false
        } else {
            try {
                connectMutex.withLock {
                    // An active turn overrides the failure cooldown — the
                    // user is mid-conversation, not hammering a dead server.
                    connectCooldownUntil = 0L
                    ensureConnected()
                    val resumed = rpc(
                        "session.resume",
                        buildJsonObject {
                            put("session_id", stored)
                            put("cols", DEFAULT_COLS)
                        },
                    )
                    val live = resumed.getOrNull()?.stringField("session_id")
                    if (live != null) {
                        liveSessionId = live
                        true
                    } else {
                        false
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mid-turn rejoin failed: ${e.message}")
                false
            }
        }
        when {
            turn.ended -> if (activeTurn === turn) activeTurn = null
            rejoined -> {
                Log.i(TAG, "Gateway rejoined mid-turn (session=$stored)")
                turn.armWatchdog()
            }
            else -> {
                if (activeTurn === turn) activeTurn = null
                turn.failFromTransport("Connection to the gateway was lost")
            }
        }
    }

    private fun closeSocket(reason: String) {
        webSocket?.close(1000, reason)
        webSocket = null
        readySignal = null
        liveSessionId = null
        attachMethodForSocket = null
        commandsCatalogCache = null
        _connectionState.value = GatewayConnectionState.Idle
    }

    private fun scheduleBackgroundClose() {
        backgroundCloseJob?.cancel()
        backgroundCloseJob = scope.launch {
            delay(BACKGROUND_CLOSE_GRACE_MS)
            if (activeTurn == null && !AppForegroundTracker.isForeground.value) {
                closeSocket("app backgrounded")
            }
        }
    }

    // ------------------------------------------------------------------
    // JSON-RPC
    // ------------------------------------------------------------------

    private suspend fun rpc(
        method: String,
        params: JsonObject,
        timeoutMs: Long = RPC_TIMEOUT_MS,
    ): Result<JsonObject> {
        val socket = webSocket ?: return Result.failure(GatewayRpcException("not connected"))
        val id = rpcId.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pendingRpcs[id] = deferred
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        if (!socket.send(json.encodeToString(JsonObject.serializer(), frame))) {
            pendingRpcs.remove(id)
            return Result.failure(GatewayRpcException("send failed — socket closed"))
        }
        return try {
            Result.success(withTimeout(timeoutMs) { deferred.await() })
        } catch (e: Exception) {
            pendingRpcs.remove(id)
            Result.failure(if (e is GatewayRpcException) e else GatewayRpcException("$method timed out"))
        }
    }

    /**
     * Queue one image onto the live session. Tries the upstream RPC name
     * first; on method-not-found falls back ONCE per socket to the legacy
     * dotted name (older builds matching the vendored desktop CLI contract),
     * then remembers whichever name worked for the socket's lifetime.
     */
    private suspend fun uploadImage(attachment: GatewayImageAttachment): Result<JsonObject> {
        val sid = liveSessionId
            ?: return Result.failure(GatewayRpcException("no live session"))
        val preferred = attachMethodForSocket ?: ATTACH_METHOD_UPSTREAM
        val first = attachRpc(preferred, sid, attachment)
        if (first.isSuccess) {
            attachMethodForSocket = preferred
            return first
        }
        if (preferred == ATTACH_METHOD_UPSTREAM &&
            attachMethodForSocket == null &&
            first.exceptionOrNull().isMethodNotFound()
        ) {
            val legacy = attachRpc(ATTACH_METHOD_LEGACY, sid, attachment)
            if (legacy.isSuccess) attachMethodForSocket = ATTACH_METHOD_LEGACY
            return legacy
        }
        return first
    }

    private suspend fun attachRpc(
        method: String,
        sessionId: String,
        attachment: GatewayImageAttachment,
    ): Result<JsonObject> = rpc(
        method,
        buildJsonObject {
            put("session_id", sessionId)
            if (method == ATTACH_METHOD_UPSTREAM) {
                put("content_base64", attachment.base64)
                attachment.name?.let { put("filename", it) }
                attachment.ext?.let { put("ext", it) }
            } else {
                put("bytes_base64", attachment.base64)
                put("format", attachment.ext ?: "png")
                attachment.name?.let { put("filename_hint", it) }
            }
        },
        timeoutMs = ATTACH_RPC_TIMEOUT_MS,
    )

    // ------------------------------------------------------------------
    // Turn handle
    // ------------------------------------------------------------------

    private inner class GatewayTurn(
        val callbacks: GatewayTurnCallbacks,
    ) : ActiveTurnHandle {
        private val mapper = GatewayEventMapper(callbacks)

        @Volatile
        var cancelled = false
            private set

        private val rejoinAttempts = java.util.concurrent.atomic.AtomicInteger(0)

        /** True if this socket loss should be answered with a rejoin attempt. */
        fun beginRejoin(): Boolean =
            !ended && rejoinAttempts.incrementAndGet() <= MAX_TURN_REJOINS

        private var watchdog: Job? = null

        val ended: Boolean get() = mapper.turnEnded || cancelled

        fun onEvent(type: String, payload: JsonObject?) {
            // Reset on every event — long tool runs keep the turn alive.
            // Ask requests block with no further events, so they arm with
            // their own (longer) duration via watchdogTimeoutFor.
            armWatchdog(watchdogTimeoutFor(type))
            mapper.onEvent(type, payload)
            if (mapper.turnEnded) disarmWatchdog()
        }

        fun armWatchdog(timeoutMs: Long = TURN_TIMEOUT_MS) {
            watchdog?.cancel()
            watchdog = scope.launch {
                delay(timeoutMs)
                if (!ended) {
                    Log.w(TAG, "Gateway turn timed out after ${timeoutMs}ms")
                    interruptServerSide()
                    failFromTransport("Gateway turn timed out")
                }
            }
        }

        fun disarmWatchdog() {
            watchdog?.cancel()
            watchdog = null
        }

        /** Transport-level failure after submit — surface as a stream error once. */
        fun failFromTransport(message: String) {
            disarmWatchdog()
            if (ended) return
            cancelled = true
            callbacks.onError(message)
        }

        override fun cancel() {
            if (ended) return
            cancelled = true
            disarmWatchdog()
            if (activeTurn === this) activeTurn = null
            interruptServerSide()
        }

        private fun interruptServerSide() {
            val sid = liveSessionId ?: return
            scope.launch {
                // Best-effort: unblocks the server (also releases blocked
                // interactive asks). Failure is fine — socket may be gone.
                rpc("session.interrupt", buildJsonObject { put("session_id", sid) })
            }
        }
    }

    /** Wrap callbacks so every invocation lands on the callback dispatcher (main thread). */
    private fun dispatchOn(callbacks: GatewayTurnCallbacks) = GatewayTurnCallbacks(
        onSessionId = { v -> callbackDispatcher { callbacks.onSessionId(v) } },
        onTextDelta = { v -> callbackDispatcher { callbacks.onTextDelta(v) } },
        onThinkingDelta = { v -> callbackDispatcher { callbacks.onThinkingDelta(v) } },
        onToolCallStart = { a, b -> callbackDispatcher { callbacks.onToolCallStart(a, b) } },
        onToolCallDone = { a, b -> callbackDispatcher { callbacks.onToolCallDone(a, b) } },
        onToolCallFailed = { a, b -> callbackDispatcher { callbacks.onToolCallFailed(a, b) } },
        onTurnComplete = { callbackDispatcher { callbacks.onTurnComplete() } },
        onComplete = { callbackDispatcher { callbacks.onComplete() } },
        onUsage = { v -> callbackDispatcher { callbacks.onUsage(v) } },
        onError = { v -> callbackDispatcher { callbacks.onError(v) } },
        onToolGenerating = { v -> callbackDispatcher { callbacks.onToolGenerating(v) } },
        onSubagentEvent = { v -> callbackDispatcher { callbacks.onSubagentEvent(v) } },
        onInteractionRequest = { v -> callbackDispatcher { callbacks.onInteractionRequest(v) } },
    )
}

/** Outcome of [GatewayChatClient.steer] — Rejected and Failed both mean "queue locally instead". */
enum class SteerResult {
    /** Server accepted — text lands in the next tool batch's last result. */
    Queued,

    /** Server reachable but no tool batch in flight to steer. */
    Rejected,

    /** Transport/RPC failure (no live session, socket down, unsupported …). */
    Failed,
}

/**
 * One image bound for `image.attach_bytes`. [ext] is the bare extension
 * without the dot ("png", "jpg" …) — it doubles as the legacy contract's
 * `format` field on fallback.
 */
data class GatewayImageAttachment(
    val name: String?,
    val base64: String,
    val ext: String?,
)

/** Connect/auth/submit failed before the turn started — safe to fall back to SSE. */
internal class GatewayPreflightException(message: String) : Exception(message)

/** One connect attempt failed; [GatewayChatClient] may retry with a fresh ticket. */
internal class GatewayConnectAttemptException(message: String) : Exception(message)

/** [code] is the JSON-RPC error code when the failure came from the server (e.g. 4018, -32601). */
internal class GatewayRpcException(message: String, val code: Int? = null) : Exception(message)

private const val JSONRPC_METHOD_NOT_FOUND = -32601

private fun Throwable?.isMethodNotFound(): Boolean {
    val rpcError = this as? GatewayRpcException ?: return false
    if (rpcError.code == JSONRPC_METHOD_NOT_FOUND) return true
    val msg = rpcError.message ?: return false
    return msg.contains("method not found", ignoreCase = true) ||
        msg.contains("unknown method", ignoreCase = true)
}

private fun JsonObject.stringField(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull
