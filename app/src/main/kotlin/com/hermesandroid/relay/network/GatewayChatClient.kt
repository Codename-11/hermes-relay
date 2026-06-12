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

        private const val RPC_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L

        /** Grace before closing an idle socket after the app backgrounds. */
        private const val BACKGROUND_CLOSE_GRACE_MS = 30_000L

        /** Cooldown after a failed connect so rapid sends don't hammer a down server. */
        private const val CONNECT_FAILURE_COOLDOWN_MS = 5_000L
        private const val RATE_LIMIT_COOLDOWN_MS = 300_000L
        private const val CONNECT_ATTEMPTS = 2

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
                activeTurn = turn
                turn.armWatchdog()
                val submitted = rpc(
                    "prompt.submit",
                    buildJsonObject {
                        put("session_id", liveSessionId ?: error("no live session"))
                        put("text", text)
                    },
                )
                if (submitted.isFailure) {
                    activeTurn = null
                    turn.disarmWatchdog()
                    throw GatewayPreflightException(
                        submitted.exceptionOrNull()?.message ?: "prompt.submit failed",
                    )
                }
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
                pending.completeExceptionally(GatewayRpcException(message))
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
        _connectionState.value = GatewayConnectionState.Idle
        pendingRpcs.values.forEach {
            it.completeExceptionally(GatewayRpcException("gateway connection lost"))
        }
        pendingRpcs.clear()
        activeTurn?.let { turn ->
            activeTurn = null
            turn.failFromTransport("Connection to the gateway was lost")
        }
    }

    private fun closeSocket(reason: String) {
        webSocket?.close(1000, reason)
        webSocket = null
        readySignal = null
        liveSessionId = null
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

    private suspend fun rpc(method: String, params: JsonObject): Result<JsonObject> {
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
            Result.success(withTimeout(RPC_TIMEOUT_MS) { deferred.await() })
        } catch (e: Exception) {
            pendingRpcs.remove(id)
            Result.failure(if (e is GatewayRpcException) e else GatewayRpcException("$method timed out"))
        }
    }

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

        private var watchdog: Job? = null

        val ended: Boolean get() = mapper.turnEnded || cancelled

        fun onEvent(type: String, payload: JsonObject?) {
            armWatchdog() // reset on every event — long tool runs keep the turn alive
            mapper.onEvent(type, payload)
            if (mapper.turnEnded) disarmWatchdog()
        }

        fun armWatchdog() {
            watchdog?.cancel()
            watchdog = scope.launch {
                delay(TURN_TIMEOUT_MS)
                if (!ended) {
                    Log.w(TAG, "Gateway turn timed out after ${TURN_TIMEOUT_MS}ms")
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
        onInteractionRequest = { a, b -> callbackDispatcher { callbacks.onInteractionRequest(a, b) } },
    )
}

/** Connect/auth/submit failed before the turn started — safe to fall back to SSE. */
internal class GatewayPreflightException(message: String) : Exception(message)

/** One connect attempt failed; [GatewayChatClient] may retry with a fresh ticket. */
internal class GatewayConnectAttemptException(message: String) : Exception(message)

internal class GatewayRpcException(message: String) : Exception(message)

private fun JsonObject.stringField(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull
