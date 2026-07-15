package com.hermesandroid.relay.network.upstream

import android.util.Log
import com.hermesandroid.relay.util.AppForegroundTracker
import com.hermesandroid.relay.util.TurnLatencyTracer
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Chat transport over upstream hermes-agent's `tui_gateway` JSON-RPC
 * WebSocket at the dashboard's `/api/ws` — the surface the official
 * hermes-desktop client speaks, and the only upstream surface that streams
 * reasoning live (`reasoning.delta`). See `GatewayModels.kt` for context and
 * `desktop/src/gatewayTypes.ts` for the vendored wire shapes.
 *
 * Lifecycle: lazy connect on first [sendTurn]; stays connected while the app
 * is foregrounded; ~2min after backgrounding the socket closes unless a turn
 * is in flight. The tui_gateway is a single SHARED process that multiplexes
 * every session's events over one stream tagged by `session_id`; a turn runs
 * in a background thread that keeps emitting on the id it was STARTED with,
 * regardless of WS state. So a mid-turn socket drop is recovered by
 * reconnecting the socket and KEEPING the in-flight session id (see
 * [attemptMidTurnRejoin]). Current upstream Hermes can also rebind a detached
 * live session through `session.activate` / `session.resume`; the direct socket
 * rejoin remains compatible with older gateways and avoids an extra RPC.
 * No background reconnect loops; a fresh send reconnects on demand.
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
    initialDashboardClient: DashboardApiClient,
    okHttpClient: OkHttpClient? = null,
    private val callbackDispatcher: (block: () -> Unit) -> Unit = MainThreadDispatcher,
    /** Surface for "this server has no usable /api/ws" — flips availability to Unsupported. */
    private val onGatewayUnsupported: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Max wall-clock a single mid-turn reconnect keeps retrying before failing the turn. */
    private val midTurnRejoinWindowMs: Long = MAX_MIDTURN_REJOIN_MS,
    /** Test seam — generic RPC ack timeout. Production keeps [RPC_TIMEOUT_MS]. */
    private val rpcTimeoutMs: Long = RPC_TIMEOUT_MS,
    /** Test seam — `prompt.submit` ack ceiling. Production keeps [PROMPT_SUBMIT_REQUEST_TIMEOUT_MS]. */
    private val promptSubmitTimeoutMs: Long = PROMPT_SUBMIT_REQUEST_TIMEOUT_MS,
    /** Test seam — idle-progress watchdog base. Production keeps [TURN_TIMEOUT_MS]. */
    private val turnIdleTimeoutMs: Long = TURN_TIMEOUT_MS,
) {
    companion object {
        private const val TAG = "GatewayChatClient"

        /**
         * Idle-progress turn watchdog — reset on EVERY received gateway event
         * (deltas, tool events, status lines), so it only fires after this
         * long with no events at all. It is NOT a hard turn cap: a turn that
         * keeps streaming lives indefinitely, and a slow `prompt.submit` ack
         * is bounded separately by [PROMPT_SUBMIT_REQUEST_TIMEOUT_MS].
         */
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

        private const val RPC_TIMEOUT_MS = 15_000L

        /**
         * `prompt.submit` ack ceiling — mirrors upstream desktop's
         * PROMPT_SUBMIT_REQUEST_TIMEOUT_MS (apps/desktop/src/hermes.ts,
         * upstream commit 164144183). The submit is effectively
         * fire-and-forget: turn completion is signaled by stream events
         * (`message.complete`), NOT by the RPC return, and MoA/deep-reasoning/
         * tool-heavy turns can legitimately take minutes to ack. Bounding the
         * ack by [RPC_TIMEOUT_MS] false-failed a running turn into the SSE
         * preflight fallback — which resubmits the same prompt → duplicate
         * turn. Matches the backend's own agent-turn ceiling
         * (agent.gateway_timeout = 1800s), so this only fires when the turn
         * would have been abandoned server-side anyway.
         */
        private const val PROMPT_SUBMIT_REQUEST_TIMEOUT_MS = 1_800_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L

        /**
         * Uploads ship whole base64 frames (image ≤25MB, PDF ≤50MB server cap)
         * and `pdf.attach` renders pages server-side before replying — 15s is
         * not enough.
         */
        private const val ATTACH_RPC_TIMEOUT_MS = 60_000L

        /** Upstream image byte-upload RPC (underscore — `image.attach_bytes`, content_base64). */
        private const val ATTACH_METHOD_UPSTREAM = "image.attach_bytes"

        /** Legacy desktop-CLI image name (dots — `image.attach.bytes`, bytes_base64/format). */
        private const val ATTACH_METHOD_LEGACY = "image.attach.bytes"

        /** Upstream PDF byte-upload RPC — server renders each page to a vision tile. */
        private const val ATTACH_METHOD_PDF = "pdf.attach"

        /** Upstream generic-file byte-upload RPC — materialized as an `@file:` workspace ref. */
        private const val ATTACH_METHOD_FILE = "file.attach"

        /**
         * Grace before closing an idle socket after the app backgrounds. Kept
         * generous so a quick context-switch (glance at a notification, copy a
         * snippet) returns to a still-warm socket+session — a cold rejoin
         * re-pays `session.resume` (~seconds) on the next send. The OS will
         * freeze the process well before this anyway; on return the chat
         * surface also pre-warms (see [prewarm]).
         */
        private const val BACKGROUND_CLOSE_GRACE_MS = 120_000L

        /** Cooldown after a failed connect so rapid sends don't hammer a down server. */
        private const val CONNECT_FAILURE_COOLDOWN_MS = 5_000L
        private const val RATE_LIMIT_COOLDOWN_MS = 300_000L
        private const val CONNECT_ATTEMPTS = 2
        private const val INBOUND_BIND_TIMEOUT_MS = 2_000L
        private const val CANCELLED_TURN_SUBMIT_WAIT_MS = 2_000L

        /** Distinct socket-loss (flap) events per turn we'll try to recover from. */
        private const val MAX_TURN_REJOINS = 4

        /**
         * How long a single mid-turn reconnect keeps retrying (with backoff)
         * before the turn is failed. Sized to outlast a typical mobile radio
         * blip / Wi-Fi⇄cellular handover (seconds) — the old behavior fired
         * two connect attempts in ~24ms and gave up, abandoning a turn the
         * server then finished and whose answer was silently dropped.
         */
        private const val MAX_MIDTURN_REJOIN_MS = 20_000L

        /**
         * After a route RETARGET (LAN⇄Tailscale mid-turn), the fresh socket
         * can't pick up the in-flight turn's events — upstream `session.resume`
         * doesn't reattach to a running turn. So arm a SHORT settle on the
         * reconnect: if nothing flows we fail fast and the post-turn reconcile
         * recovers the server's answer, instead of waiting the full turn
         * watchdog. Any live event resets it back to the normal timeout.
         */
        private const val POST_RETARGET_SETTLE_MS = 30_000L

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
        // The 10s default connectTimeout is LAN-tuned; a remote dashboard
        // reached over Tailscale (DERP cold start) can take longer to complete
        // the WS upgrade. A failed connect drops chat to the SSE fallback and a
        // 5s cooldown, so give the first remote handshake room.
        .connectTimeout(20, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * The dashboard surface this client targets. Mutable so the client can
     * FOLLOW a route change (LAN⇄Tailscale) mid-turn via [retarget] instead of
     * being torn down — the in-flight turn's session is server-side and the
     * same shared gateway sits behind both routes.
     */
    @Volatile
    private var dashboardClient: DashboardApiClient = initialDashboardClient

    private val _connectionState = MutableStateFlow(GatewayConnectionState.Idle)
    val connectionState: StateFlow<GatewayConnectionState> = _connectionState.asStateFlow()

    /**
     * Per-socket feature probe for upstream's session-scoped `process.*` RPCs.
     * Method-not-found marks the current socket unsupported; reconnecting resets
     * this to [GatewayProcessCapability.Unknown] so a server upgrade is noticed.
     */
    private val _processCapability = MutableStateFlow(GatewayProcessCapability.Unknown)
    val processCapability: StateFlow<GatewayProcessCapability> = _processCapability.asStateFlow()

    /**
     * Active personality the gateway is applying, as a config value ("none" when
     * the overlay is cleared, otherwise the personality name). Tracks the
     * upstream `display.personality` the way the desktop/TUI do: updated from the
     * [setPersonality] / [getPersonality] round-trips AND from connection-level
     * `session.info` events, so a change made via `/personality`, the desktop, or
     * the TUI reflects in the app. Null until first observed.
     */
    private val _serverPersonality = MutableStateFlow<String?>(null)
    val serverPersonality: StateFlow<String?> = _serverPersonality.asStateFlow()

    /**
     * Active model / provider the gateway reports for our session, tracked off
     * `session.info` the same way as [serverPersonality]. Lets a `/model` switch
     * made on the desktop/TUI (or our own dispatch) reflect in the app's model
     * pill without an app reload. Null until first observed; only ever set to a
     * non-blank value.
     */
    private val _serverModel = MutableStateFlow<String?>(null)
    val serverModel: StateFlow<String?> = _serverModel.asStateFlow()

    private val _serverProvider = MutableStateFlow<String?>(null)
    val serverProvider: StateFlow<String?> = _serverProvider.asStateFlow()

    /**
     * Active reasoning EFFORT from `session.info` (string; "" when reasoning is
     * disabled). The reasoning DISPLAY mode is NOT on session.info — it stays a
     * `config.get reasoning` concern ([getReasoningSettings]). Only ever set to a
     * non-blank value so a disabled-reasoning "" never clobbers the chip.
     */
    private val _serverReasoningEffort = MutableStateFlow<String?>(null)
    val serverReasoningEffort: StateFlow<String?> = _serverReasoningEffort.asStateFlow()

    /**
     * Server-reported credential warning (upstream `session.info.credential_warning`)
     * — present ONLY when the active provider's key is missing/invalid, absent
     * (→ null here) when healthy. Cleared on absence so it self-resolves when the
     * key is fixed.
     */
    private val _serverCredentialWarning = MutableStateFlow<String?>(null)
    val serverCredentialWarning: StateFlow<String?> = _serverCredentialWarning.asStateFlow()

    /**
     * Effective approval-bypass (YOLO) + fast-mode state from `session.info`
     * (`yolo`/`fast` booleans). YOLO has NO `config.get` upstream — session.info
     * is the only read. Null until first observed.
     */
    private val _serverYolo = MutableStateFlow<Boolean?>(null)
    val serverYolo: StateFlow<Boolean?> = _serverYolo.asStateFlow()

    private val _serverFast = MutableStateFlow<Boolean?>(null)
    val serverFast: StateFlow<Boolean?> = _serverFast.asStateFlow()

    /**
     * Context-window usage `(used, max)` from `session.info`'s `usage` block
     * (upstream `_get_usage`). `session.info` is emitted on session resume, so
     * this lets the context bar paint immediately on resume instead of waiting
     * for the first turn's usage event. Null until observed / when omitted.
     */
    private val _serverContext = MutableStateFlow<Pair<Int, Int>?>(null)
    val serverContext: StateFlow<Pair<Int, Int>?> = _serverContext.asStateFlow()

    /** Serializes connect / session-establish so concurrent sends share one socket. */
    private val connectMutex = Mutex()

    @Volatile
    private var webSocket: WebSocket? = null

    /** Completed when the server's `gateway.ready` event arrives for the current socket. */
    @Volatile
    private var readySignal: CompletableDeferred<Unit>? = null

    private val rpcId = AtomicLong(1)
    /** Invalidates an older async prewarm when a newer session selection wins. */
    private val prewarmRequestGeneration = AtomicLong(0)
    private val pendingRpcs = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()

    /** Live (per-connection) session id ←→ the stored DB id it was resumed/created from. */
    @Volatile
    private var liveSessionId: String? = null

    @Volatile
    private var storedSessionId: String? = null

    /** Profile namespace that owns [liveSessionId]; stored IDs are not globally unique. */
    @Volatile
    private var liveSessionProfile: String? = null

    /**
     * Supplies the profile to bind each `session.create` / `session.resume` to —
     * the upstream `tui_gateway` opens that profile's HERMES_HOME/db and builds
     * the agent (its model, SOUL, personality, skills) from it. Sessions are
     * profile-bound: a live session keeps its agent, so a profile switch means a
     * NEW session created under the new profile. Pulled live so it always
     * reflects the current pick; null/blank = the gateway's launch (default)
     * profile. Wired by ChatViewModel from the selected-profile provider.
     */
    @Volatile
    var sessionProfileProvider: () -> String? = { null }

    private fun currentSessionProfile(): String? =
        sessionProfileProvider().takeIf { !it.isNullOrBlank() }

    /**
     * Supplies the explicit in-chat overrides to bind onto each fresh
     * `session.create` (upstream honors `model`/`provider`/`reasoning_effort`/
     * `fast` → the new session's per-session overrides). Pulled live so it
     * always reflects the current picker + safety/speed controls; null (or all
     * fields null) = no explicit override, so the new session inherits the
     * profile / server default. Wired by ChatViewModel. A live session keeps its
     * agent config, so this only affects session creation — mid-session switches
     * go through [setModel]/[setReasoning]/[setFast] (`config.set`).
     */
    @Volatile
    var sessionModelProvider: () -> GatewaySessionModel? = { null }

    private fun currentSessionModel(): GatewaySessionModel? =
        sessionModelProvider()?.takeIf {
            !it.model.isNullOrBlank() || !it.reasoningEffort.isNullOrBlank() || it.fast != null
        }

    @Volatile
    private var activeTurn: GatewayTurn? = null

    /**
     * Turns deliberately detached when the user switches profile/session.
     * Upstream continues them server-side; retain the live→durable binding so
     * their terminal event can trigger an authoritative history reconcile.
     */
    private val backgroundTurns = ConcurrentHashMap<String, BackgroundTurn>()

    private data class BackgroundTurn(
        val storedSessionId: String,
    )

    /**
     * Upstream may emit the interrupted turn's tail and terminal event after
     * `session.interrupt` returns. Keep a short exact-session tombstone so that
     * tail cannot be mistaken for an unsolicited completion or complete a
     * newly submitted turn. A same-session send briefly waits for this drain
     * before `prompt.submit`; once a turn actually started, its tombstone stays
     * until the required terminal event even when that submit wait elapses.
     */
    @Volatile
    private var cancelledTurnDrain: CancelledTurnDrain? = null

    private data class CancelledTurnDrain(
        val storedSessionId: String,
        val liveSessionId: String,
        val submitWaitUntilMs: Long,
        val terminalRequired: Boolean,
    )

    /**
     * Creates UI callbacks when the server starts a turn that has no matching
     * [sendTurn] call (for example a background-process completion). The
     * provider is consulted only for an explicit `message.start` whose live
     * session id exactly matches this client's active session.
     */
    @Volatile
    private var unsolicitedTurnProvider: ((storedSessionId: String) -> GatewayInboundTurnRegistration?)? = null

    /** Recover persisted events that may have completed while the socket was closed. */
    @Volatile
    private var coldPrewarmSessionReadyListener: ((storedSessionId: String) -> Unit)? = null

    /** Exact-session completion observed without a bound live mapper. */
    @Volatile
    private var unmatchedTurnCompleteListener:
        ((storedSessionId: String, expectedAssistantText: String?) -> Unit)? = null

    /**
     * Connection-level process listener. Unlike [GatewayTurnCallbacks], this is
     * consulted even when there is no locally initiated [activeTurn].
     */
    @Volatile
    private var processEventListener: ((GatewayProcessEvent) -> Unit)? = null

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

    /**
     * When true, the socket is never auto-closed on background — the opt-in
     * keep-alive foreground service (sideload) holds the process up so the
     * conversation stays connected until the app is killed. Driven by
     * ConnectionViewModel from the user's "Keep connected in background" toggle.
     */
    @Volatile
    private var keepAliveInBackground = false

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
     * @param attachments attachments uploaded onto the session between session
     *   establish and `prompt.submit` — upstream snapshots+clears the session's
     *   queued attachments at turn start, so they bind to THIS turn. Routed by
     *   MIME: images → `image.attach_bytes` (vision tiles), PDFs → `pdf.attach`
     *   (rendered to vision tiles), everything else → `file.attach` (staged as
     *   an `@file:` workspace artifact the agent's file tools can read).
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
        attachments: List<GatewayAttachment> = emptyList(),
        truncateBeforeUserOrdinal: Int? = null,
        onPreflightFailure: (reason: String) -> Unit,
    ): ActiveTurnHandle {
        val turn = GatewayTurn(dispatchOn(callbacks))
        // Warm = the connection-establish phases are skipped this turn (socket
        // alive AND the requested session already live). A "cold" turn re-pays
        // ticket/ws/session — exactly the asymmetry vs always-connected desktop.
        val socketWarm = webSocket != null && readySignal?.isCompleted == true
        val sessionWarm = liveSessionId != null &&
            storedSessionId == sessionId &&
            sessionId != null &&
            liveSessionProfile == currentSessionProfile()
        turn.tracer.warm(socketWarm && sessionWarm)
        scope.launch {
            try {
                connectMutex.withLock {
                    ensureConnected()
                    turn.tracer.mark("connect")
                    ensureSession(sessionId, newSessionTitle, turn)
                    turn.tracer.mark("session")
                }
                if (turn.cancelled) return@launch
                attachments.forEach { attachment ->
                    uploadAttachment(attachment).getOrElse { e ->
                        throw GatewayPreflightException("attachment upload failed: ${e.message}")
                    }
                }
                if (turn.cancelled) return@launch
                if (!awaitCancelledTurnDrain(turn, storedSessionId)) return@launch
                activeTurn = turn
                turn.armWatchdog()
                val submitted = rpc(
                    "prompt.submit",
                    buildJsonObject {
                        put("session_id", liveSessionId ?: error("no live session"))
                        put("text", text)
                        truncateBeforeUserOrdinal?.let { put("truncate_before_user_ordinal", it) }
                    },
                    // Long-running RPC, not a generic 15s ack — see the
                    // constant's doc. The idle watchdog (armed above, reset by
                    // every event) owns liveness while this await is pending.
                    timeoutMs = promptSubmitTimeoutMs,
                )
                if (submitted.isFailure) {
                    // Once this turn's own events are flowing (or it already
                    // finished), the prompt provably reached the server — a
                    // slow, lost, or socket-severed ack must NOT preflight-fail
                    // into the SSE fallback, which would resubmit the same
                    // prompt as a duplicate turn. Recovery belongs to the
                    // stream: the watchdog and mid-turn rejoin own it.
                    if (turn.started || turn.ended) {
                        Log.w(
                            TAG,
                            "prompt.submit ack failed after turn start " +
                                "(${submitted.exceptionOrNull()?.message}) — no SSE fallback",
                        )
                        return@launch
                    }
                    if (activeTurn === turn) activeTurn = null
                    turn.disarmWatchdog()
                    throw GatewayPreflightException(
                        submitted.exceptionOrNull()?.message ?: "prompt.submit failed",
                    )
                }
                turn.tracer.mark("submit")
                // One INFO line per turn so logcat shows which transport
                // served a send — the SSE paths log their SSE events, and
                // a silent happy path here made on-device verification a
                // read-the-absence exercise.
                Log.i(TAG, "Gateway turn submitted (session=$storedSessionId)")
            } catch (e: Exception) {
                if (activeTurn === turn) activeTurn = null
                if (!turn.cancelled) {
                    Log.w(TAG, "Gateway preflight failed: ${e.message}")
                    turn.tracer.done("preflight-fail")
                    callbackDispatcher { onPreflightFailure(e.message ?: "gateway unavailable") }
                }
            }
        }
        return turn
    }

    /** Drop the remembered session so the next send creates a fresh one. */
    fun clearSession() {
        prewarmRequestGeneration.incrementAndGet()
        liveSessionId = null
        storedSessionId = null
        liveSessionProfile = null
        cancelledTurnDrain = null
    }

    /**
     * Detach the visible callbacks without interrupting the server-side turn.
     * This is the profile/session switch primitive: the old turn keeps running,
     * while this client is free to create or resume another profile-bound
     * session. Completion is reported through [unmatchedTurnCompleteListener]
     * using the original durable id.
     */
    fun backgroundActiveTurn(): Boolean {
        val turn = activeTurn?.takeIf { !it.ended } ?: return false
        val liveId = liveSessionId ?: return false
        val storedId = storedSessionId ?: return false
        backgroundTurns[liveId] = BackgroundTurn(
            storedSessionId = storedId,
        )
        turn.detach()
        return true
    }

    /**
     * True while a turn is in flight (including mid-rejoin). Callers that would
     * otherwise tear down / replace this client on a route change defer that
     * teardown so a transient blip can't cancel the running turn — the client
     * recovers its own socket and keeps the live session. See
     * [com.hermesandroid.relay.viewmodel.ConnectionViewModel.activeGatewayChatClient].
     */
    fun hasActiveTurn(): Boolean = activeTurn?.ended == false

    /** Live id to persist beside a durable stored id while a turn is active. */
    fun currentLiveSessionId(storedId: String): String? =
        liveSessionId?.takeIf { storedSessionId == storedId }

    /**
     * Point this client at a new dashboard route (e.g. LAN→Tailscale after a
     * sustained network change). If a turn is in flight, the current socket is
     * cancelled to force the mid-turn rejoin to reconnect via the NEW route
     * while KEEPING the live session id — the in-flight turn FOLLOWS the route
     * instead of dying on the old one. No-op if the route is unchanged.
     */
    fun retarget(newDashboardClient: DashboardApiClient) {
        if (dashboardClient === newDashboardClient) return
        Log.i(TAG, "Gateway retargeting to a new route (turn active=${hasActiveTurn()})")
        dashboardClient = newDashboardClient
        if (hasActiveTurn()) {
            retargetedThisTurn = true
            webSocket?.cancel()
        }
    }

    /** Set by [retarget] so the rejoin arms the short post-retarget settle once. */
    @Volatile
    private var retargetedThisTurn = false

    /**
     * Toggle the opt-in background keep-alive (sideload). While on, the socket
     * is not auto-closed when the app backgrounds. Turning it off while already
     * backgrounded arms the normal idle-close so the socket still eventually
     * releases.
     */
    fun setKeepAliveInBackground(enabled: Boolean) {
        keepAliveInBackground = enabled
        if (!enabled && !AppForegroundTracker.isForeground.value) scheduleBackgroundClose()
    }

    /** Process polling must not silently undo the normal background socket close. */
    fun isBackgroundProcessPollingAllowed(): Boolean =
        AppForegroundTracker.isForeground.value || keepAliveInBackground

    fun setUnsolicitedTurnProvider(
        provider: ((storedSessionId: String) -> GatewayInboundTurnRegistration?)?,
    ) {
        unsolicitedTurnProvider = provider
    }

    fun setColdPrewarmSessionReadyListener(listener: ((storedSessionId: String) -> Unit)?) {
        coldPrewarmSessionReadyListener = listener
    }

    fun setUnmatchedTurnCompleteListener(
        listener: ((storedSessionId: String, expectedAssistantText: String?) -> Unit)?,
    ) {
        unmatchedTurnCompleteListener = listener
    }

    fun setProcessEventListener(listener: ((GatewayProcessEvent) -> Unit)?) {
        processEventListener = listener
    }

    /**
     * Establish the socket (and resume an existing session) ahead of the
     * user's first send, so a warm turn reaches first token in tens of ms
     * instead of paying the cold connect + `session.resume` on the critical
     * "I pressed send" path. Best-effort and idempotent: a no-op when already
     * warm, silently skipped on cooldown / unsupported / unreachable.
     *
     * Deliberately does NOT create a session when [storedSessionId] is null —
     * pre-creating on screen-open would litter the session list with empty
     * conversations. A brand-new chat's `session.create` stays on its first
     * send.
     */
    fun prewarm(storedSessionId: String?) {
        scope.launch { prewarmAwait(storedSessionId) }
    }

    /**
     * Suspending [prewarm]: establishes the socket and (when [storedSessionId]
     * is non-null) resumes the existing session, returning only once that work
     * has settled. Returns true when a live session is available afterwards.
     *
     * An in-chat model/effort/fast switch MUST await this before its
     * `config.set`. Otherwise the switch races the fire-and-forget [prewarm]
     * and runs with `liveSessionId == null`, which upstream applies as a GLOBAL
     * config write instead of a per-session one — so the pick never lands on
     * the session the next turn actually uses (a fresh chat pre-creates a
     * session, so this path is the common case, not the edge case).
     */
    suspend fun prewarmAwait(storedSessionId: String?): Boolean {
        val requestGeneration = prewarmRequestGeneration.incrementAndGet()
        val requestedProfile = currentSessionProfile()
        val wasLiveForRequestedSession = storedSessionId != null &&
            liveSessionId != null &&
            this.storedSessionId == storedSessionId &&
            liveSessionProfile == requestedProfile
        try {
            connectMutex.withLock {
                ensureConnected()
                if (storedSessionId != null) {
                    resumeForPrewarm(storedSessionId, requestedProfile, requestGeneration)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Gateway prewarm skipped: ${e.message}")
        }
        val sessionReady = storedSessionId != null &&
            liveSessionId != null &&
            this.storedSessionId == storedSessionId &&
            liveSessionProfile == requestedProfile
        if (!wasLiveForRequestedSession && sessionReady) {
            callbackDispatcher {
                coldPrewarmSessionReadyListener?.invoke(storedSessionId)
            }
        }
        return sessionReady
    }

    /**
     * Reattach callbacks to a turn that survived the Android UI/process.
     *
     * New Hermes gateways expose `session.activate`, which attaches the new
     * WebSocket transport to the exact live id saved in the client checkpoint.
     * If that id has already been reaped (or the method is unavailable), fall
     * back to `session.resume` by durable session id. Its `running` + `inflight`
     * fields decide whether a live mapper is installed or history should settle
     * the turn instead.
     */
    suspend fun recoverTurn(
        storedId: String,
        preferredLiveId: String?,
        callbacks: GatewayTurnCallbacks,
    ): Result<GatewaySessionRecovery> = runCatching {
        require(storedId.isNotBlank()) { "stored session id required" }
        val requestedProfile = currentSessionProfile()
        connectMutex.withLock {
            val existing = activeTurn
            if (existing != null && !existing.ended) {
                throw GatewayRpcException("a gateway turn is already attached")
            }
            ensureConnected()

            var response: JsonObject? = null
            var boundTurn: GatewayTurn? = null

            if (!preferredLiveId.isNullOrBlank()) {
                // Bind before session.activate: upstream swaps the live session's
                // transport during the RPC, so an immediate next delta must not
                // fall through the active-turn gate while the ack is in flight.
                liveSessionId = preferredLiveId
                storedSessionId = storedId
                liveSessionProfile = requestedProfile
                boundTurn = GatewayTurn(
                    callbacks = dispatchOn(callbacks),
                    dedupeAdjacentMessageStarts = true,
                ).also { turn ->
                    turn.markRecoveredStarted()
                    activeTurn = turn
                }
                val activated = rpc(
                    "session.activate",
                    buildJsonObject {
                        put("session_id", preferredLiveId)
                    },
                )
                response = activated.getOrNull()
                if (response == null) {
                    if (activeTurn === boundTurn) activeTurn = null
                    boundTurn.detach()
                    boundTurn = null
                    Log.d(
                        TAG,
                        "Exact live-session activation unavailable; resuming durable session " +
                            "(${activated.exceptionOrNull()?.message})",
                    )
                }
            }

            if (response == null) {
                response = rpc(
                    "session.resume",
                    buildJsonObject {
                        put("session_id", storedId)
                        put("cols", DEFAULT_COLS)
                        requestedProfile?.let { put("profile", it) }
                    },
                ).getOrElse { error -> throw error }
            }

            val recoveredLiveId = response.stringField("session_id")
                ?: throw GatewayRpcException("session recovery returned no session_id")
            liveSessionId = recoveredLiveId
            storedSessionId = storedId
            liveSessionProfile = requestedProfile
            updateCancelledDrainLiveSession(storedId, recoveredLiveId)
            (response["info"] as? JsonObject)?.let { applySessionInfo(it) }

            val inflight = (response["inflight"] as? JsonObject)?.let { value ->
                GatewayInflightTurn(
                    user = value.stringField("user").orEmpty(),
                    assistant = value.stringField("assistant").orEmpty(),
                    streaming = value.booleanField("streaming") == true,
                )
            }
            val running = response.booleanField("running") == true || inflight?.streaming == true

            if (running) {
                if (boundTurn == null || boundTurn.ended) {
                    boundTurn = GatewayTurn(
                        callbacks = dispatchOn(callbacks),
                        dedupeAdjacentMessageStarts = true,
                    ).also { turn ->
                        turn.markRecoveredStarted()
                        activeTurn = turn
                    }
                }
                boundTurn.armWatchdog()
            } else {
                if (boundTurn != null) {
                    if (activeTurn === boundTurn) activeTurn = null
                    boundTurn.detach()
                }
                boundTurn = null
            }

            GatewaySessionRecovery(
                storedSessionId = storedId,
                liveSessionId = recoveredLiveId,
                running = running,
                status = response.stringField("status"),
                inflight = inflight,
                handle = boundTurn?.takeUnless { it.ended },
            )
        }
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
    suspend fun respondClarify(requestId: String, answer: String): Result<GatewayAskResponse> =
        rpc(
            "clarify.respond",
            buildJsonObject {
                put("request_id", requestId)
                put("answer", answer)
            },
        ).map { it.gatewayAskResponse() }

    /**
     * Answer a [GatewayAsk.Kind.SUDO] ask. The password must NEVER be logged
     * or persisted — it exists only inside this outbound frame.
     */
    suspend fun respondSudo(requestId: String, password: String): Result<GatewayAskResponse> =
        rpc(
            "sudo.respond",
            buildJsonObject {
                put("request_id", requestId)
                put("password", password)
            },
        ).map { it.gatewayAskResponse() }

    /**
     * Answer a [GatewayAsk.Kind.SECRET] ask. Empty [value] = skip (upstream
     * returns `skipped: true` to the tool). The value must NEVER be logged
     * or persisted — it exists only inside this outbound frame.
     */
    suspend fun respondSecret(requestId: String, value: String): Result<GatewayAskResponse> =
        rpc(
            "secret.respond",
            buildJsonObject {
                put("request_id", requestId)
                put("value", value)
            },
        ).map { it.gatewayAskResponse() }

    /**
     * Answer a [GatewayAsk.Kind.APPROVAL] ask — correlated by the live
     * session, not a request id. [choice] is "approve" or "deny"; [all]
     * resolves every pending approval on the session at once.
     */
    suspend fun respondApproval(choice: String, all: Boolean = false): Result<GatewayAskResponse> {
        val sid = liveSessionId
            ?: return Result.failure(GatewayRpcException("no live session"))
        return rpc(
            "approval.respond",
            buildJsonObject {
                put("session_id", sid)
                put("choice", choice)
                put("all", all)
            },
        ).map { it.gatewayAskResponse() }
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
     * Fetch the current chat session's running and recently-finished background
     * processes. Callers never provide a session id: this wrapper resolves and
     * sends the exact LIVE gateway id, not the stored history id exposed to UI.
     *
     * A remembered stored session is resumed after a socket reconnect. A brand-
     * new chat has no server process ownership yet and therefore returns an empty
     * snapshot without creating an otherwise-empty session.
     */
    suspend fun listProcesses(): Result<List<GatewayProcess>> {
        if (_processCapability.value == GatewayProcessCapability.Unsupported) {
            return Result.failure(processFeatureUnsupported())
        }
        if (liveSessionId == null && storedSessionId == null) {
            return Result.success(emptyList())
        }
        val sid = ensureLiveProcessSession().getOrElse { return Result.failure(it) }
        val result = rpc(
            "process.list",
            buildJsonObject { put("session_id", sid) },
        )
        if (result.isFailure) {
            markProcessUnsupportedIfNeeded(result.exceptionOrNull())
            return Result.failure(result.exceptionOrNull() ?: GatewayRpcException("process.list failed"))
        }
        _processCapability.value = GatewayProcessCapability.Supported
        return Result.success(
            ((result.getOrThrow()["processes"] as? JsonArray).orEmpty()).mapNotNull(::parseGatewayProcess),
        )
    }

    /** Stop one process owned by the current live gateway session. */
    suspend fun killProcess(processId: String): Result<Unit> {
        if (processId.isBlank()) {
            return Result.failure(GatewayRpcException("process id required"))
        }
        if (_processCapability.value == GatewayProcessCapability.Unsupported) {
            return Result.failure(processFeatureUnsupported())
        }
        val sid = ensureLiveProcessSession().getOrElse { return Result.failure(it) }
        val result = rpc(
            "process.kill",
            buildJsonObject {
                put("session_id", sid)
                put("process_id", processId)
            },
        )
        if (result.isFailure) {
            markProcessUnsupportedIfNeeded(result.exceptionOrNull())
            return Result.failure(result.exceptionOrNull() ?: GatewayRpcException("process.kill failed"))
        }
        _processCapability.value = GatewayProcessCapability.Supported
        return Result.success(Unit)
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

    /**
     * Read the active personality (`config.get {key:"personality"}`). Returns the
     * upstream config value — `"none"` when the overlay is cleared, otherwise the
     * personality name. Connects on demand. Used to seed [serverPersonality] when
     * a gateway connection comes up so the app reflects whatever the server
     * (config / desktop / TUI) currently has active.
     */
    suspend fun getPersonality(): Result<String> {
        if (webSocket == null || readySignal?.isCompleted != true) {
            try {
                connectMutex.withLock { ensureConnected() }
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        return rpc("config.get", buildJsonObject { put("key", "personality") })
            .map { result ->
                (result.stringField("value") ?: "none").ifBlank { "none" }
                    .also { _serverPersonality.value = it }
            }
    }

    /**
     * Set the personality the way the desktop + TUI do (`config.set
     * {key:"personality"}`). The gateway persists `display.personality` +
     * `agent.system_prompt` to the active profile's config AND applies the
     * overlay live to the current session (no history reset). Pass `"none"`
     * (or `"default"`/`"neutral"`) to clear the overlay. Returns the resolved
     * active value (`"none"` or the name); also updates [serverPersonality]
     * directly so observers don't have to wait on the `session.info` echo (which
     * only fires when a live session exists).
     */
    suspend fun setPersonality(value: String): Result<String> {
        if (webSocket == null || readySignal?.isCompleted != true) {
            try {
                connectMutex.withLock { ensureConnected() }
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        val params = buildJsonObject {
            put("key", "personality")
            put("value", value)
            liveSessionId?.let { put("session_id", it) }
        }
        return rpc("config.set", params).map { result ->
            (result.stringField("value") ?: value).ifBlank { "none" }
                .also { _serverPersonality.value = it }
        }
    }

    /**
     * Fetch the curated provider/model list (`model.options`) — the same RPC
     * the upstream desktop + TUI model picker uses (grok / kimi / gpt-5.5 …,
     * grouped by authenticated provider), NOT the api_server `/v1/models`
     * generic alias. Connects on demand if needed. Switching a model is then a
     * `/model <model> --provider <slug>` slash dispatch.
     */
    suspend fun modelOptions(refresh: Boolean = false): Result<GatewayModelOptions> {
        if (webSocket == null || readySignal?.isCompleted != true) {
            try {
                connectMutex.withLock { ensureConnected() }
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        val params = buildJsonObject {
            liveSessionId?.let { put("session_id", it) }
            if (refresh) put("refresh", true)
        }
        return rpc("model.options", params).map { result ->
            val providers = (result["providers"] as? JsonArray).orEmpty().mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val slug = obj.stringField("slug") ?: return@mapNotNull null
                GatewayModelProvider(
                    name = obj.stringField("name") ?: slug,
                    slug = slug,
                    models = (obj["models"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                    isCurrent = (obj["is_current"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    warning = obj.stringField("warning"),
                    authenticated = (obj["authenticated"] as? JsonPrimitive)?.booleanOrNull ?: true,
                    unavailableModels = (obj["unavailable_models"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                    freeTier = (obj["free_tier"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    totalModels = (obj["total_models"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                )
            }
            GatewayModelOptions(
                providers = providers,
                currentModel = result.stringField("model") ?: "",
                currentProvider = result.stringField("provider") ?: "",
            )
        }
    }

    /**
     * Switch the active model via `config.set {key:"model", value}` — the same
     * `_apply_model_switch` path the desktop/CLI `/model` picker uses. [value]
     * is the upstream model-switch flag string: `<model> [--provider <slug>]
     * [--global]`. Session-scoped when a session is live, else the global
     * default. Returns `{value, warning}`. This avoids the `/model` SLASH path,
     * which falls through to `command.dispatch` and reports a spurious
     * "not a quick/plugin/skill command" failure even when the switch applied.
     */
    suspend fun setModel(value: String): Result<JsonObject> =
        rpc(
            "config.set",
            buildJsonObject {
                put("key", "model")
                put("value", value)
                liveSessionId?.let { put("session_id", it) }
            },
        )

    /** Fetch the session/global reasoning effort and display mode. */
    suspend fun getReasoningSettings(): Result<GatewayReasoningSettings> {
        if (webSocket == null || readySignal?.isCompleted != true) {
            try {
                connectMutex.withLock { ensureConnected() }
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        return rpc(
            "config.get",
            buildJsonObject {
                put("key", "reasoning")
                liveSessionId?.let { put("session_id", it) }
            },
        ).map { result ->
            GatewayReasoningSettings(
                effort = result.stringField("value")?.takeIf { it.isNotBlank() } ?: "medium",
                display = result.stringField("display")?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * Switch the active reasoning effort through the same `config.set` path
     * the desktop/TUI `/reasoning` command uses. Values are upstream-defined:
     * none, minimal, low, medium, high, xhigh.
     */
    suspend fun setReasoning(value: String): Result<JsonObject> =
        rpc(
            "config.set",
            buildJsonObject {
                put("key", "reasoning")
                put("value", value)
                liveSessionId?.let { put("session_id", it) }
            },
        )

    /**
     * Toggle per-session approval bypass (YOLO) via `config.set {key:"yolo"}` —
     * the same session-scoped flag the desktop's setSessionYolo and the TUI's
     * Shift+Tab use (`value` "1"/"0", `scope` "session" = ephemeral, never writes
     * config.yaml). Requires a live session for the per-session flag. Updates
     * [serverYolo] from the echo so observers don't wait on `session.info`.
     * Returns the resolved enabled state. There is deliberately NO `getYolo()` —
     * upstream has no `config.get yolo`; session.info is the only read.
     */
    suspend fun setYolo(enabled: Boolean, scope: String = "session"): Result<Boolean> {
        if (webSocket == null || readySignal?.isCompleted != true) {
            try {
                connectMutex.withLock { ensureConnected() }
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        val params = buildJsonObject {
            put("key", "yolo")
            put("value", if (enabled) "1" else "0")
            put("scope", scope)
            liveSessionId?.let { put("session_id", it) }
        }
        return rpc("config.set", params).map { result ->
            (result.stringField("value") == "1").also { _serverYolo.value = it }
        }
    }

    /**
     * Toggle fast mode (priority service tier) via `config.set {key:"fast"}` —
     * desktop parity (`value` "fast"/"normal", session-scoped). Capability-gated
     * upstream: enabling fails (error 4002) when the current model has no fast
     * tier. Updates [serverFast]; returns the resolved enabled state.
     */
    suspend fun setFast(enabled: Boolean): Result<Boolean> {
        if (webSocket == null || readySignal?.isCompleted != true) {
            try {
                connectMutex.withLock { ensureConnected() }
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        val params = buildJsonObject {
            put("key", "fast")
            put("value", if (enabled) "fast" else "normal")
            liveSessionId?.let { put("session_id", it) }
        }
        return rpc("config.set", params).map { result ->
            (result.stringField("value") == "fast").also { _serverFast.value = it }
        }
    }

    fun shutdown() {
        activeTurn?.cancel()
        activeTurn = null
        backgroundTurns.clear()
        cancelledTurnDrain = null
        unsolicitedTurnProvider = null
        coldPrewarmSessionReadyListener = null
        unmatchedTurnCompleteListener = null
        processEventListener = null
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
        val connectStart = System.nanoTime()
        _processCapability.value = GatewayProcessCapability.Unknown
        _connectionState.value = GatewayConnectionState.MintingTicket
        val ticket = dashboardClient.requestWsTicket().getOrElse { e ->
            throw GatewayConnectAttemptException("ws-ticket mint failed: ${e.message}")
        }
        val ticketMs = (System.nanoTime() - connectStart) / 1_000_000
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
        // Split the cold-connect cost so a slow ticket mint (HTTP) is told
        // apart from a slow WS upgrade + gateway.ready (socket/TLS) on device.
        val wsMs = (System.nanoTime() - connectStart) / 1_000_000 - ticketMs
        Log.i(TAG, "Gateway connected (/api/ws ready) — ticket=${ticketMs}ms ws=${wsMs}ms")
        _connectionState.value = GatewayConnectionState.Ready
    }

    /**
     * Must hold [connectMutex]. Resume an existing session ahead of a send
     * (no [GatewayTurn] context). Failure is silent — the real send's
     * [ensureSession] will resume-or-create properly.
     */
    private suspend fun resumeForPrewarm(
        storedId: String,
        requestedProfile: String?,
        requestGeneration: Long,
    ) {
        // Never change session binding while this client already owns a live
        // mapper. Current upstream may reuse the same live session, while older
        // builds mint a new id; either way the existing mapper owns recovery.
        if (activeTurn != null) return
        if (
            liveSessionId != null &&
            storedSessionId == storedId &&
            liveSessionProfile == requestedProfile
        ) return
        val resumed = rpc(
            "session.resume",
            buildJsonObject {
                put("session_id", storedId)
                put("cols", DEFAULT_COLS)
                requestedProfile?.let { put("profile", it) }
            },
        )
        val result = resumed.getOrNull()
        val live = result?.stringField("session_id")
        if (
            live != null &&
            activeTurn == null &&
            prewarmRequestGeneration.get() == requestGeneration
        ) {
            liveSessionId = live
            storedSessionId = storedId
            liveSessionProfile = requestedProfile
            updateCancelledDrainLiveSession(storedId, live)
            // Paint the session's real model/provider/effort/etc NOW from the
            // resume result's embedded `info` (same shape session.info carries),
            // so a reopened session shows its ACTUAL model immediately instead of
            // a misleading default until the first turn's async session.info.
            (result["info"] as? JsonObject)?.let { applySessionInfo(it) }
        }
    }

    /**
     * Apply connection-level session info (model / provider / reasoning effort /
     * personality / yolo / fast / context usage) into the `_server*` state flows.
     * Shared by the `session.info` event handler and the `session.resume` RPC
     * result — the resume response embeds the same `info` object, so reopening a
     * session can paint its real model up front rather than waiting for a turn.
     */
    private fun applySessionInfo(info: JsonObject) {
        if (info.containsKey("personality")) {
            _serverPersonality.value =
                (info.stringField("personality") ?: "").ifBlank { "none" }
        }
        info.stringField("model")?.takeIf { it.isNotBlank() }?.let { _serverModel.value = it }
        info.stringField("provider")?.takeIf { it.isNotBlank() }?.let { _serverProvider.value = it }
        // reasoning effort: ignore "" (reasoning disabled) so it can't clobber
        // the chip; display mode is config.get-only, not here.
        info.stringField("reasoning_effort")?.takeIf { it.isNotBlank() }
            ?.let { _serverReasoningEffort.value = it }
        // credential_warning: present only when the provider key is missing/
        // invalid. ABSENT means healthy — clear to null so it self-resolves.
        _serverCredentialWarning.value =
            info.stringField("credential_warning")?.takeIf { it.isNotBlank() }
        (info["yolo"] as? JsonPrimitive)?.booleanOrNull?.let { _serverYolo.value = it }
        (info["fast"] as? JsonPrimitive)?.booleanOrNull?.let { _serverFast.value = it }
        // Context usage: require used > 0 — a COLD resume resets counters and
        // reports 0 until the first turn rebuilds the prompt; painting 0 would
        // mislead on a session that actually has history.
        (info["usage"] as? JsonObject)?.let { usage ->
            val used = (usage["context_used"] as? JsonPrimitive)?.intOrNull
            val max = (usage["context_max"] as? JsonPrimitive)?.intOrNull
            if (used != null && used > 0 && max != null && max > 0) {
                _serverContext.value = used to max
            }
        }
    }

    /** Resolve a process RPC against the exact live id, resuming after reconnect when possible. */
    private suspend fun ensureLiveProcessSession(): Result<String> {
        val requestedProfile = currentSessionProfile()
        liveSessionId?.takeIf { liveSessionProfile == requestedProfile }
            ?.let { return Result.success(it) }
        val rememberedStoredId = storedSessionId
            ?: return Result.failure(GatewayRpcException("no live session"))
        val requestGeneration = prewarmRequestGeneration.incrementAndGet()
        return try {
            connectMutex.withLock {
                ensureConnected()
                if (liveSessionId == null || liveSessionProfile != requestedProfile) {
                    resumeForPrewarm(rememberedStoredId, requestedProfile, requestGeneration)
                }
            }
            val resumedLiveId = liveSessionId
            if (
                resumedLiveId != null &&
                storedSessionId == rememberedStoredId &&
                liveSessionProfile == requestedProfile
            ) {
                Result.success(resumedLiveId)
            } else {
                Result.failure(GatewayRpcException("could not resume live session"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseGatewayProcess(element: kotlinx.serialization.json.JsonElement): GatewayProcess? {
        val process = element as? JsonObject ?: return null
        val id = process.stringField("session_id")?.takeIf { it.isNotBlank() } ?: return null
        return GatewayProcess(
            id = id,
            command = process.stringField("command").orEmpty(),
            cwd = process.stringField("cwd"),
            pid = (process["pid"] as? JsonPrimitive)?.longOrNull,
            startedAt = process.stringField("started_at"),
            uptimeSeconds = (process["uptime_seconds"] as? JsonPrimitive)?.longOrNull ?: 0L,
            status = process.stringField("status") ?: "unknown",
            outputPreview = process.stringField("output_preview")?.takeIf { it.isNotEmpty() },
            outputTail = process.stringField("output_tail")?.takeIf { it.isNotEmpty() },
            exitCode = (process["exit_code"] as? JsonPrimitive)?.intOrNull,
            detached = (process["detached"] as? JsonPrimitive)?.booleanOrNull ?: false,
            notifyOnComplete = (process["notify_on_complete"] as? JsonPrimitive)?.booleanOrNull ?: false,
            sessionScoped = (process["session_scoped"] as? JsonPrimitive)?.booleanOrNull ?: false,
            watchPatterns = (process["watch_patterns"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            watchHit = (process["watch_hit"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    private fun markProcessUnsupportedIfNeeded(error: Throwable?) {
        if (error.isMethodNotFound()) {
            _processCapability.value = GatewayProcessCapability.Unsupported
        }
    }

    private fun processFeatureUnsupported(): GatewayRpcException =
        GatewayRpcException("background process RPCs are not supported by this gateway", JSONRPC_METHOD_NOT_FOUND)

    /** Must hold [connectMutex]. Resolves [liveSessionId] for the requested stored id. */
    private suspend fun ensureSession(
        requestedStoredId: String?,
        newSessionTitle: String?,
        turn: GatewayTurn,
    ) {
        val requestedProfile = currentSessionProfile()
        if (requestedStoredId != null && requestedStoredId != storedSessionId) {
            cancelledTurnDrain = null
        }
        if (
            liveSessionId != null &&
            storedSessionId == requestedStoredId &&
            requestedStoredId != null &&
            liveSessionProfile == requestedProfile
        ) {
            return
        }

        if (requestedStoredId != null) {
            val resumed = rpc(
                "session.resume",
                buildJsonObject {
                    put("session_id", requestedStoredId)
                    put("cols", DEFAULT_COLS)
                    requestedProfile?.let { put("profile", it) }
                },
            )
            val result = resumed.getOrNull()
            val live = result?.stringField("session_id")
            if (live != null) {
                liveSessionId = live
                storedSessionId = requestedStoredId
                liveSessionProfile = requestedProfile
                updateCancelledDrainLiveSession(requestedStoredId, live)
                (result["info"] as? JsonObject)?.let { applySessionInfo(it) }
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
                requestedProfile?.let { put("profile", it) }
                // Bind the in-chat overrides to the new session as its
                // per-session overrides. Upstream tui_gateway session.create
                // reads `model`/`provider` (→ model_override), `reasoning_effort`
                // (→ create_reasoning_override) and `fast` (→ priority service
                // tier) — verified server.py:4175-4191. Without this a fresh
                // chat ignores the picker/safety controls and builds the agent
                // from the global default, and worse, setting effort/fast before
                // the first message runs a SESSIONLESS config.set that upstream
                // applies as a GLOBAL config write. A live session keeps its own
                // config — this is create-only; mid-session switches use
                // config.set (setModel/setReasoning/setFast).
                currentSessionModel()?.let { sm ->
                    sm.model?.takeIf { it.isNotBlank() }?.let { put("model", it) }
                    sm.provider?.takeIf { it.isNotBlank() }?.let { put("provider", it) }
                    sm.reasoningEffort?.takeIf { it.isNotBlank() }?.let { put("reasoning_effort", it) }
                    sm.fast?.let { put("fast", it) }
                }
            },
        ).getOrElse { e ->
            throw GatewayPreflightException("session.create failed: ${e.message}")
        }
        val live = created.stringField("session_id")
            ?: throw GatewayPreflightException("session.create returned no session_id")
        val stored = created.stringField("stored_session_id") ?: live
        liveSessionId = live
        storedSessionId = stored
        liveSessionProfile = requestedProfile
        if (cancelledTurnDrain?.storedSessionId != stored) cancelledTurnDrain = null
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
            "message.delta", "reasoning.delta", "thinking.delta", "agent.terminal.output" ->
                Log.d(TAG, "GW ← $type (${payload?.toString()?.length ?: 0} chars)")
            else ->
                Log.d(TAG, "GW ← $type | ${payload?.toString()?.take(300) ?: "{}"}")
        }

        if (type == "gateway.ready") {
            ready.complete(Unit)
            return
        }

        // A profile/session switch may leave an upstream turn running while a
        // different profile becomes visible. Its events must never paint the
        // new transcript, but the terminal event still needs to reconcile the
        // original durable session so the answer is waiting when the user
        // switches back.
        val backgroundTurn = eventSessionId?.let(backgroundTurns::get)
        if (backgroundTurn != null) {
            if (type == "message.complete") {
                backgroundTurns.remove(eventSessionId, backgroundTurn)
                val expectedText = payload?.stringField("text")
                callbackDispatcher {
                    unmatchedTurnCompleteListener?.invoke(
                        backgroundTurn.storedSessionId,
                        expectedText,
                    )
                }
            }
            return
        }

        // `session.info` is connection-level (personality / model / context
        // usage), emitted on a config change even with no turn in flight. Capture
        // the active personality here — for our own session only — so a
        // `/personality`, desktop, or TUI change keeps the app in sync. Falls
        // through to the turn dispatch below so an in-flight turn still sees it.
        if (type == "session.info" &&
            (eventSessionId == null || liveSessionId == null || eventSessionId == liveSessionId)
        ) {
            // Connection-level session info (model / provider / effort / persona /
            // yolo / fast / usage) — shared with the session.resume result via
            // applySessionInfo so both paths stay in lockstep.
            payload?.let { applySessionInfo(it) }
        }

        // Foreign-session events (another client's chat on the same gateway) are not ours.
        if (eventSessionId != null && liveSessionId != null && eventSessionId != liveSessionId) {
            return
        }
        dispatchProcessEvent(type, payload, eventSessionId)
        if (consumeCancelledTurnEvent(type, eventSessionId)) return
        var turn = activeTurn
        if (turn == null && type == "message.start") {
            // Unsolicited turns are accepted only with an explicit exact live-
            // session match. This gateway stream is process-wide; treating a
            // missing id as ours would leak another desktop tab's response.
            val liveId = liveSessionId
            val storedId = storedSessionId
            if (!eventSessionId.isNullOrBlank() &&
                eventSessionId == liveId &&
                !storedId.isNullOrBlank()
            ) {
                val registration = unsolicitedTurnProvider?.invoke(storedId)
                if (registration != null) {
                    val inboundTurn = GatewayTurn(
                        callbacks = dispatchOn(registration.callbacks),
                        dedupeAdjacentMessageStarts = true,
                    )
                    if (bindInboundTurn(registration, inboundTurn)) {
                        turn = inboundTurn
                        activeTurn = inboundTurn
                        inboundTurn.armWatchdog()
                        Log.i(TAG, "Accepted unsolicited gateway turn for session=$storedId")
                    } else {
                        Log.i(TAG, "Deferred unsolicited gateway turn for session=$storedId")
                    }
                }
            }
        }
        if (turn == null) {
            // A foreground SSE/realtime turn may already own Chat, or the
            // socket may have reconnected after message.start. The exact
            // terminal event is still authoritative and persisted upstream;
            // recover it through history once the UI becomes idle.
            val storedId = storedSessionId
            if (type == "message.complete" &&
                !eventSessionId.isNullOrBlank() &&
                eventSessionId == liveSessionId &&
                !storedId.isNullOrBlank()
            ) {
                val expectedText = payload?.stringField("text")
                callbackDispatcher {
                    unmatchedTurnCompleteListener?.invoke(storedId, expectedText)
                }
            }
            return
        }
        turn.onEvent(type, payload)
        if (turn.ended) {
            if (activeTurn === turn) activeTurn = null
            if (AppForegroundTracker.isForeground.value.not()) scheduleBackgroundClose()
        }
    }

    /**
     * Deliver session-scoped process events before the active-turn gate. The
     * gateway socket is process-wide, so an exact non-blank live id match is
     * required; missing/foreign ids must never leak another window's process.
     */
    private fun dispatchProcessEvent(type: String, payload: JsonObject?, eventSessionId: String?) {
        val liveId = liveSessionId ?: return
        if (eventSessionId.isNullOrBlank() || eventSessionId != liveId) return
        val event = when (type) {
            "tool.complete" -> when (payload?.stringField("name")) {
                "terminal", "process" -> GatewayProcessEvent.Invalidated(
                    GatewayProcessEvent.Trigger.TOOL_COMPLETE,
                )
                else -> null
            }
            "status.update" -> if (payload?.stringField("kind") == "process") {
                GatewayProcessEvent.Invalidated(GatewayProcessEvent.Trigger.STATUS_UPDATE)
            } else {
                null
            }
            // Some upstream tool paths complete a background terminal launch
            // without emitting tool.start/tool.complete to this UI session.
            // The assistant still closes the initiating turn normally, so use
            // that exact-session boundary as a cheap authoritative discovery
            // fallback. process.list then starts the running-only poller.
            "message.complete" -> GatewayProcessEvent.Invalidated(
                GatewayProcessEvent.Trigger.MESSAGE_COMPLETE,
            )
            "agent.terminal.output" -> {
                val processId = payload?.stringField("process_id")
                val chunk = payload?.stringField("chunk")
                if (!processId.isNullOrBlank() && chunk != null) {
                    GatewayProcessEvent.Output(processId, chunk)
                } else {
                    null
                }
            }
            "terminal.close" -> payload?.stringField("process_id")
                ?.takeIf { it.isNotBlank() }
                ?.let { GatewayProcessEvent.TerminalClosed(it) }
            else -> null
        } ?: return
        callbackDispatcher { processEventListener?.invoke(event) }
    }

    private fun onSocketDown(reason: String) {
        Log.i(TAG, "Gateway socket down ($reason)")
        // Capture the in-flight session id BEFORE clearing it — the mid-turn
        // rejoin restores it so the running turn's tail (still tagged with this
        // id on the shared gateway stream) keeps matching after reconnect.
        val preservedLiveId = liveSessionId
        webSocket = null
        readySignal = null
        liveSessionId = null
        attachMethodForSocket = null
        commandsCatalogCache = null
        _processCapability.value = GatewayProcessCapability.Unknown
        _connectionState.value = GatewayConnectionState.Idle
        pendingRpcs.values.forEach {
            it.completeExceptionally(GatewayRpcException("gateway connection lost"))
        }
        pendingRpcs.clear()
        val turn = activeTurn ?: return
        if (turn.ended) {
            if (activeTurn === turn) activeTurn = null
            return
        }
        // A rejoin already owns recovery — a connect attempt failing inside
        // it must not spawn a second concurrent rejoin.
        if (rejoinInProgress) return
        // A turn is in flight. Mobile radios drop sockets mid-turn routinely
        // (Wi-Fi power-save/roam, Wi-Fi⇄cellular handover — ECONNABORTED). The
        // server keeps generating into the session and emitting on the SAME id,
        // so reconnect the socket and keep listening on [preservedLiveId].
        if (turn.beginRejoin()) {
            rejoinInProgress = true
            scope.launch {
                try {
                    attemptMidTurnRejoin(turn, preservedLiveId)
                } finally {
                    rejoinInProgress = false
                }
            }
        } else {
            if (activeTurn === turn) activeTurn = null
            turn.failFromTransport("Connection to the gateway was lost")
        }
    }

    @Volatile
    private var rejoinInProgress = false

    /**
     * Recover an in-flight turn after a mid-turn socket loss by reconnecting
     * the SOCKET ONLY and keeping [preservedLiveId] as the live session id.
     *
     * A bare reconnect lets the tail — including the final
     * `message.complete` — keep matching without another RPC. Current upstream
     * can rebind live sessions too, but older builds cannot, so this remains the
     * lowest-common-denominator same-client recovery path.
     *
     * Retries with backoff for up to [midTurnRejoinWindowMs] so a multi-second
     * radio blip doesn't abandon the turn. Events emitted while the socket was
     * down are lost, but the post-turn REST reconcile (getMessages) fills in
     * the authoritative final transcript.
     */
    private suspend fun attemptMidTurnRejoin(turn: GatewayTurn, preservedLiveId: String?) {
        val deadline = System.currentTimeMillis() + midTurnRejoinWindowMs
        var backoffMs = 500L
        while (!turn.ended && System.currentTimeMillis() < deadline) {
            val reconnected = try {
                connectMutex.withLock {
                    // The user is mid-conversation, not hammering a dead
                    // server — override the post-failure cooldown.
                    connectCooldownUntil = 0L
                    ensureConnected()
                }
                true
            } catch (e: Exception) {
                Log.d(TAG, "Mid-turn reconnect retry failed: ${e.message}")
                false
            }
            if (turn.ended) break
            if (reconnected) {
                // Keep the in-flight session id so the running turn's events
                // (tagged with the OLD id) keep matching. No session.resume.
                if (preservedLiveId != null) liveSessionId = preservedLiveId
                Log.i(
                    TAG,
                    "Gateway socket rejoined mid-turn (session=$storedSessionId) — kept live session, awaiting tail",
                )
                // A reconnect that followed a route RETARGET gets a short settle
                // (the fresh socket won't replay the in-flight turn); a normal
                // blip-rejoin keeps the full turn watchdog. A live event resets
                // either back to the per-event timeout.
                turn.armWatchdog(
                    if (retargetedThisTurn) {
                        retargetedThisTurn = false
                        POST_RETARGET_SETTLE_MS
                    } else {
                        turnIdleTimeoutMs
                    },
                )
                return
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(5_000L)
        }
        if (activeTurn === turn) activeTurn = null
        if (!turn.ended) turn.failFromTransport("Connection to the gateway was lost")
    }

    private fun closeSocket(reason: String) {
        webSocket?.close(1000, reason)
        webSocket = null
        readySignal = null
        liveSessionId = null
        attachMethodForSocket = null
        commandsCatalogCache = null
        _processCapability.value = GatewayProcessCapability.Unknown
        _connectionState.value = GatewayConnectionState.Idle
    }

    private fun scheduleBackgroundClose() {
        // Opt-in keep-alive: the foreground service holds the process up, so
        // never tear the socket down on background while it's on.
        if (keepAliveInBackground) return
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
        timeoutMs: Long = rpcTimeoutMs,
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
     * Queue one attachment onto the live session, routed by MIME so it lands on
     * the same upstream handler the desktop client uses:
     *   - `image/…`         → [ATTACH_METHOD_UPSTREAM] (vision tiles)
     *   - `application/pdf` → [ATTACH_METHOD_PDF] (pages rendered to vision tiles)
     *   - everything else   → [ATTACH_METHOD_FILE] (staged as an `@file:` ref)
     */
    private suspend fun uploadAttachment(attachment: GatewayAttachment): Result<JsonObject> {
        val sid = liveSessionId
            ?: return Result.failure(GatewayRpcException("no live session"))
        val mime = attachment.contentType.substringBefore(';').trim().lowercase()
        return when {
            mime.startsWith("image/") -> uploadImage(sid, attachment)
            mime == "application/pdf" -> rpc(
                ATTACH_METHOD_PDF,
                buildJsonObject {
                    put("session_id", sid)
                    put("content_base64", attachment.base64)
                },
                timeoutMs = ATTACH_RPC_TIMEOUT_MS,
            )
            else -> rpc(
                ATTACH_METHOD_FILE,
                buildJsonObject {
                    put("session_id", sid)
                    // file.attach wants a `data:<mime>;base64,…` data URL so the
                    // gateway can materialize the bytes; it tolerates bare base64
                    // too, but the prefix preserves the MIME for the agent.
                    put("data_url", "data:${attachment.contentType};base64,${attachment.base64}")
                    attachment.name?.let { put("name", it) }
                },
                timeoutMs = ATTACH_RPC_TIMEOUT_MS,
            )
        }
    }

    /**
     * Upload one image. Tries the upstream RPC name first; on method-not-found
     * falls back ONCE per socket to the legacy dotted name (older builds
     * matching the vendored desktop CLI contract), then remembers whichever
     * name worked for the socket's lifetime.
     */
    private suspend fun uploadImage(
        sessionId: String,
        attachment: GatewayAttachment,
    ): Result<JsonObject> {
        val preferred = attachMethodForSocket ?: ATTACH_METHOD_UPSTREAM
        val first = attachRpc(preferred, sessionId, attachment)
        if (first.isSuccess) {
            attachMethodForSocket = preferred
            return first
        }
        if (preferred == ATTACH_METHOD_UPSTREAM &&
            attachMethodForSocket == null &&
            first.exceptionOrNull().isMethodNotFound()
        ) {
            val legacy = attachRpc(ATTACH_METHOD_LEGACY, sessionId, attachment)
            if (legacy.isSuccess) attachMethodForSocket = ATTACH_METHOD_LEGACY
            return legacy
        }
        return first
    }

    private suspend fun attachRpc(
        method: String,
        sessionId: String,
        attachment: GatewayAttachment,
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

    /** Per-event idle-watchdog duration — asks block server-side with no events, so they arm longer. */
    private fun watchdogTimeoutFor(eventType: String): Long = when (eventType) {
        "clarify.request", "secret.request" -> ASK_CLARIFY_SECRET_TIMEOUT_MS
        "sudo.request" -> ASK_SUDO_TIMEOUT_MS
        "approval.request", "terminal.read.request" -> ASK_UNBOUNDED_TIMEOUT_MS
        else -> turnIdleTimeoutMs
    }

    private inner class GatewayTurn(
        val callbacks: GatewayTurnCallbacks,
        dedupeAdjacentMessageStarts: Boolean = false,
    ) : ActiveTurnHandle {
        private val mapper = GatewayEventMapper(callbacks, dedupeAdjacentMessageStarts)

        /** t0 = construction ≈ sendTurn entry (the moment the user sent). */
        val tracer = TurnLatencyTracer("gateway")

        @Volatile
        var cancelled = false
            private set

        private val rejoinAttempts = java.util.concurrent.atomic.AtomicInteger(0)

        /** True if this socket loss should be answered with a rejoin attempt. */
        fun beginRejoin(): Boolean =
            !ended && rejoinAttempts.incrementAndGet() <= MAX_TURN_REJOINS

        private var watchdog: Job? = null

        val ended: Boolean get() = mapper.turnEnded || cancelled

        /**
         * True once any turn-scoped event has arrived — proof the server
         * received the submit and is running the turn. `session.info` doesn't
         * count: it's connection-level (resume/config echoes) and can arrive
         * independent of this turn, so it must not suppress a legitimate
         * preflight fallback.
         */
        @Volatile
        var started = false
            private set

        fun onEvent(type: String, payload: JsonObject?) {
            if (type != "session.info") started = true
            tracer.mark("ttfe")
            if (type == "message.delta" || type == "reasoning.delta" || type == "thinking.delta") {
                tracer.mark("ttft")
            }
            // Reset on every event — long tool runs keep the turn alive.
            // Ask requests block with no further events, so they arm with
            // their own (longer) duration via watchdogTimeoutFor.
            armWatchdog(watchdogTimeoutFor(type))
            mapper.onEvent(type, payload)
            if (mapper.turnEnded) {
                disarmWatchdog()
                tracer.done()
            }
        }

        fun armWatchdog(timeoutMs: Long = turnIdleTimeoutMs) {
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

        /** Recovery attaches after the original prompt.submit, so it is already started. */
        fun markRecoveredStarted() {
            started = true
        }

        /** Transport-level failure after submit — surface as a stream error once. */
        fun failFromTransport(message: String) {
            disarmWatchdog()
            if (ended) return
            cancelled = true
            tracer.done("transport-fail")
            callbacks.onError(message)
        }

        override fun cancel() {
            if (ended) return
            cancelled = true
            disarmWatchdog()
            tracer.done("cancelled")
            if (activeTurn === this) {
                armCancelledTurnDrain(terminalRequired = started)
                activeTurn = null
            }
            interruptServerSide()
        }

        override fun detach() {
            if (ended) return
            cancelled = true
            disarmWatchdog()
            tracer.done("detached")
            if (activeTurn === this) activeTurn = null
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

    private fun armCancelledTurnDrain(terminalRequired: Boolean) {
        val storedId = storedSessionId ?: return
        val liveId = liveSessionId ?: return
        cancelledTurnDrain = CancelledTurnDrain(
            storedSessionId = storedId,
            liveSessionId = liveId,
            submitWaitUntilMs = System.currentTimeMillis() + CANCELLED_TURN_SUBMIT_WAIT_MS,
            terminalRequired = terminalRequired,
        )
    }

    private fun updateCancelledDrainLiveSession(storedId: String, liveId: String) {
        val drain = cancelledTurnDrain ?: return
        if (drain.storedSessionId == storedId) {
            cancelledTurnDrain = drain.copy(liveSessionId = liveId)
        }
    }

    /** Ignore the canceled turn's exact-session tail through its terminal event. */
    private fun consumeCancelledTurnEvent(type: String, eventSessionId: String?): Boolean {
        val drain = cancelledTurnDrain ?: return false
        if (!drain.terminalRequired && System.currentTimeMillis() >= drain.submitWaitUntilMs) {
            if (cancelledTurnDrain === drain) cancelledTurnDrain = null
            return false
        }
        if (eventSessionId != drain.liveSessionId) return false
        if (type == "message.complete" || type == "error") {
            if (cancelledTurnDrain === drain) cancelledTurnDrain = null
        }
        Log.d(TAG, "Ignored canceled gateway turn event: $type")
        return true
    }

    /**
     * Preserve same-session event ordering after Stop. The interrupt terminal
     * normally drains immediately. The wait is bounded so a slow cooperative
     * interrupt does not indefinitely delay the next submit, but a started
     * turn's tombstone remains until its terminal event and continues draining
     * the old tail in front of the server-serialized next prompt.
     */
    private suspend fun awaitCancelledTurnDrain(
        turn: GatewayTurn,
        targetStoredSessionId: String?,
    ): Boolean {
        while (!turn.cancelled) {
            val drain = cancelledTurnDrain ?: return true
            if (drain.storedSessionId != targetStoredSessionId) return true
            if (System.currentTimeMillis() >= drain.submitWaitUntilMs) {
                if (!drain.terminalRequired && cancelledTurnDrain === drain) {
                    cancelledTurnDrain = null
                }
                return true
            }
            delay(25L)
        }
        return false
    }

    /**
     * Serialize inbound ownership onto the callback/main dispatcher before any
     * mapper callback is posted. A local SSE turn can otherwise start between
     * the socket event and UI binding and lose its cancellable handle.
     */
    private fun bindInboundTurn(
        registration: GatewayInboundTurnRegistration,
        turn: GatewayTurn,
    ): Boolean {
        val pending = AtomicBoolean(true)
        val completed = CountDownLatch(1)
        var accepted = false
        try {
            callbackDispatcher {
                try {
                    if (pending.compareAndSet(true, false)) {
                        accepted = registration.onHandle(turn)
                    }
                } finally {
                    completed.countDown()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Inbound turn dispatcher rejected callback", e)
            return false
        }
        if (!completed.await(INBOUND_BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            // If the callback has not started, expire it so a late main-thread
            // delivery cannot bind a handle the client already discarded. If
            // it has started, wait for that short ownership check to finish.
            if (pending.compareAndSet(true, false)) return false
            completed.await()
        }
        return accepted
    }

    /** Wrap callbacks so every invocation lands on the callback dispatcher (main thread). */
    private fun dispatchOn(callbacks: GatewayTurnCallbacks) = GatewayTurnCallbacks(
        onSessionId = { v -> callbackDispatcher { callbacks.onSessionId(v) } },
        onStart = { callbackDispatcher { callbacks.onStart() } },
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
        onInteractionExpired = { v -> callbackDispatcher { callbacks.onInteractionExpired(v) } },
        // MUST be wrapped like every other member: GatewayTurnCallbacks gives
        // onStatusUpdate a default no-op, so omitting it here silently swallows
        // EVERY gateway status line — the ❌ terminal-error lifecycle update
        // included. Without it markError never fires, the turn isn't badged
        // "Error", and onComplete's history reload wipes the error bubble (the
        // "reply appears then vanishes" bug).
        onStatusUpdate = { kind, text -> callbackDispatcher { callbacks.onStatusUpdate(kind, text) } },
        onStatusClear = { kind -> callbackDispatcher { callbacks.onStatusClear(kind) } },
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
 * One outbound attachment bound for the gateway. [contentType] decides which
 * upstream RPC carries it (`image.attach_bytes` / `pdf.attach` / `file.attach`).
 * [ext] is the bare extension without the dot ("png", "jpg" …) — for images it
 * doubles as the legacy contract's `format` field on fallback; unused for the
 * PDF/file paths. [name] is the original filename (drives `@file:` naming).
 */
data class GatewayAttachment(
    val name: String?,
    val base64: String,
    val ext: String?,
    val contentType: String,
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

private fun JsonObject.booleanField(key: String): Boolean? =
    (get(key) as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.gatewayAskResponse(): GatewayAskResponse {
    val status = stringField("status")
    val resolved = (get("resolved") as? JsonPrimitive)?.intOrNull
    return if (status.equals("expired", ignoreCase = true) || resolved == 0) {
        GatewayAskResponse.EXPIRED
    } else {
        GatewayAskResponse.ACCEPTED
    }
}
