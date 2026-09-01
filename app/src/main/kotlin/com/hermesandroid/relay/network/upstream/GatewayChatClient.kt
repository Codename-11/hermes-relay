package com.hermesandroid.relay.network.upstream

import android.util.Log
import com.hermesandroid.relay.data.GatewayProfileConfigureResult
import com.hermesandroid.relay.data.GatewayProfileAsset
import com.hermesandroid.relay.data.GatewayProfileAuthChoice
import com.hermesandroid.relay.data.GatewayProfileCreateRequest
import com.hermesandroid.relay.data.GatewayProfileCreateResult
import com.hermesandroid.relay.data.GatewayProfileDescription
import com.hermesandroid.relay.data.GatewayProfileEditorClient
import com.hermesandroid.relay.data.GatewayProfileEditorUnsupportedException
import com.hermesandroid.relay.data.GatewayProfileManagementUnsupportedException
import com.hermesandroid.relay.data.GatewayProfilePatch
import com.hermesandroid.relay.data.GatewayProfileSection
import com.hermesandroid.relay.data.GatewayProfileSkill
import com.hermesandroid.relay.data.GatewayProfileToolset
import com.hermesandroid.relay.data.BotChatTarget
import com.hermesandroid.relay.data.BotGroupMember
import com.hermesandroid.relay.data.BotGroupMessage
import com.hermesandroid.relay.data.BotGroupRoom
import com.hermesandroid.relay.data.BotModeRoster
import com.hermesandroid.relay.data.BotRosterEntry
import com.hermesandroid.relay.data.BotSessionSummary
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.isSafeProfileUiMeta
import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.network.upstream.models.UsageInfo
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
import com.hermesandroid.relay.network.shared.fullJitterDelayMs
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    private val fixedSessionProfile: String? = null,
    okHttpClient: OkHttpClient? = null,
    private val callbackDispatcher: (block: () -> Unit) -> Unit = MainThreadDispatcher,
    /** Surface for "this server has no usable /api/ws" — flips availability to Unsupported. */
    private val onGatewayUnsupported: () -> Unit = {},
    /** Ticket/upgrade auth rejection is distinct from an unsupported Gateway. */
    private val onGatewaySignInRequired: () -> Unit = {},
    /** A bounded ticket/connect failure makes this route unreachable for now. */
    private val onGatewayUnreachable: () -> Unit = {},
    /** A completed gateway.ready handshake is authoritative live transport evidence. */
    private val onGatewayReady: () -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Max wall-clock a single mid-turn reconnect keeps retrying before failing the turn. */
    private val midTurnRejoinWindowMs: Long = MAX_MIDTURN_REJOIN_MS,
    /** Test seam — generic RPC ack timeout. Production keeps [RPC_TIMEOUT_MS]. */
    private val rpcTimeoutMs: Long = RPC_TIMEOUT_MS,
    /** Test seam — `prompt.submit` ack ceiling. Production keeps [PROMPT_SUBMIT_REQUEST_TIMEOUT_MS]. */
    private val promptSubmitTimeoutMs: Long = PROMPT_SUBMIT_REQUEST_TIMEOUT_MS,
    /** Test seam — idle-progress watchdog base. Production keeps [TURN_TIMEOUT_MS]. */
    private val turnIdleTimeoutMs: Long = TURN_TIMEOUT_MS,
    /** Test seam — lazy session.create/resume readiness barrier. */
    private val sessionReadyTimeoutMs: Long = SESSION_READY_TIMEOUT_MS,
    /** Test seam — compaction idle lease. Production keeps [COMPACTING_TIMEOUT_MS]. */
    private val compactingTimeoutMs: Long = COMPACTING_TIMEOUT_MS,
    /** Random source for ordinary reconnect full-jitter. */
    private val reconnectJitterUnit: () -> Double = { kotlin.random.Random.nextDouble() },
) : GatewayProfileEditorClient {
    /** Existing upstream rich-chat vocabulary; do not invent a Relay-only source. */
    private val sessionSource = "webui"
    @Volatile
    private var profileDescribeSupported: Boolean? = null
    @Volatile
    private var profileConfigureSupported: Boolean? = null
    @Volatile
    private var profileListSupported: Boolean? = null
    @Volatile
    private var profileCreateSupported: Boolean? = null
    @Volatile
    private var profileGetAssetSupported: Boolean? = null
    @Volatile
    private var profileSetAssetSupported: Boolean? = null
    companion object {
        private const val TAG = "GatewayChatClient"
        private const val BOT_CHAT_TITLE = "Bot Chat"

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

        /**
         * Server-side context compaction summarizes the transcript through a
         * (possibly slow) model with NO deltas or tool events flowing until it
         * finishes — near the context ceiling that silence routinely exceeds
         * [TURN_TIMEOUT_MS], so the idle watchdog would `session.interrupt` a
         * healthy compression, roll back its work, and retrigger on the next
         * prompt forever. A `status.update` event with kind `compacting`
         * (emitted at compaction start, and periodically by newer gateways)
         * arms this longer leash instead; any regular event rearms
         * [TURN_TIMEOUT_MS].
         */
        private const val COMPACTING_TIMEOUT_MS = 600_000L

        private const val RPC_TIMEOUT_MS = 15_000L
        const val PROFILE_AVATAR_MAX_BYTES = 2_000_000

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
        private const val SESSION_READY_TIMEOUT_MS = 300_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L

        /**
         * Uploads ship whole base64 frames (image ≤25MB, PDF ≤50MB server cap)
         * and `pdf.attach` renders pages server-side before replying — 15s is
         * not enough.
         */
        private const val ATTACH_RPC_TIMEOUT_MS = 60_000L
        private const val COMPRESS_RPC_TIMEOUT_MS = 120_000L

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
        private const val MAX_CONNECT_FAILURE_COOLDOWN_MS = 30_000L
        private const val COLD_START_FAILURE_EPISODE_LIMIT = 5
        private const val RATE_LIMIT_COOLDOWN_MS = 300_000L
        private const val CONNECT_ATTEMPTS = 2
        private const val INBOUND_BIND_TIMEOUT_MS = 2_000L
        private const val CANCELLED_TURN_SUBMIT_WAIT_MS = 2_000L
        private const val MAX_RECOVERY_BUFFERED_EVENTS = 256
        internal const val MAX_CHILD_WATCH_HISTORY_ITEMS = 200
        internal const val MAX_CHILD_WATCH_HISTORY_CHARS = 64_000
        private const val MAX_PENDING_CHILD_WATCH_EVENTS = 256

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
        // the WS upgrade. A failed connect leaves Android on its Gateway owner and a
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
    private val _reconnectDisposition = MutableStateFlow(GatewayReconnectDisposition.Retryable)
    val reconnectDisposition: StateFlow<GatewayReconnectDisposition> =
        _reconnectDisposition.asStateFlow()

    /** Delay a foreground reconnect only while the bounded failure cooldown is active. */
    fun remainingConnectCooldownMillis(nowMillis: Long = System.currentTimeMillis()): Long =
        (connectCooldownUntil - nowMillis).coerceAtLeast(0L)

    /**
     * Per-socket feature probe for upstream's session-scoped `process.*` RPCs.
     * Method-not-found marks the current socket unsupported; reconnecting resets
     * this to [GatewayProcessCapability.Unknown] so a server upgrade is noticed.
     */
    private val _processCapability = MutableStateFlow(GatewayProcessCapability.Unknown)
    val processCapability: StateFlow<GatewayProcessCapability> = _processCapability.asStateFlow()

    /** Per-socket capability for upstream's process-wide live-session snapshot. */
    private val _activeSessionCapability =
        MutableStateFlow(GatewayActiveSessionCapability.Unknown)
    val activeSessionCapability: StateFlow<GatewayActiveSessionCapability> =
        _activeSessionCapability.asStateFlow()

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

    private val _serverModelIdentity = MutableStateFlow<GatewayModelIdentity?>(null)
    val serverModelIdentity: StateFlow<GatewayModelIdentity?> = _serverModelIdentity.asStateFlow()

    /**
     * Active reasoning EFFORT from `session.info` (string; "" when reasoning is
     * disabled). The reasoning DISPLAY mode is NOT on session.info — it stays a
     * `config.get reasoning` concern ([getReasoningSettings]). Only ever set to a
     * non-blank value so a disabled-reasoning "" never clobbers the chip.
     */
    private val _serverReasoningEffort = MutableStateFlow<String?>(null)
    val serverReasoningEffort: StateFlow<String?> = _serverReasoningEffort.asStateFlow()

    private val _serverReasoningIdentity = MutableStateFlow<GatewayReasoningIdentity?>(null)
    val serverReasoningIdentity: StateFlow<GatewayReasoningIdentity?> =
        _serverReasoningIdentity.asStateFlow()

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

    private val _serverApprovalMode = MutableStateFlow<GatewayApprovalMode?>(null)
    val serverApprovalMode: StateFlow<GatewayApprovalMode?> = _serverApprovalMode.asStateFlow()

    private val _approvalModeCapability =
        MutableStateFlow(GatewayApprovalModeCapability.Unknown)
    val approvalModeCapability: StateFlow<GatewayApprovalModeCapability> =
        _approvalModeCapability.asStateFlow()

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

    /** Optional upstream project identity for the active session. */
    private val _serverProject = MutableStateFlow<GatewaySessionProject?>(null)
    val serverProject: StateFlow<GatewaySessionProject?> = _serverProject.asStateFlow()

    /**
     * Exact model-callable tool names from upstream `session.info.tools` for
     * the selected live session/profile. Null means the gateway has not
     * supplied a catalog; an empty set means it authoritatively supplied none.
     */
    private val _serverTools = MutableStateFlow<Set<String>?>(null)
    val serverTools: StateFlow<Set<String>?> = _serverTools.asStateFlow()

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
    private val lazyLiveSessions = ConcurrentHashMap.newKeySet<String>()
    private val readyLiveSessions = ConcurrentHashMap.newKeySet<String>()
    private val sessionReadyWaiters = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Monotonic client-local fence for lazy child watch open/close races. */
    private val childWatchGeneration = AtomicLong(0)

    /** Live child runtime id -> exact watcher that owns its callbacks. */
    private val childWatches = ConcurrentHashMap<String, ChildWatchRegistration>()

    /** Events that race a lazy `session.resume` acknowledgement. */
    private val pendingChildWatchOpens = ConcurrentHashMap<Long, PendingChildWatchOpen>()

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
        fixedSessionProfile?.trim()?.takeIf(String::isNotBlank)
            ?: sessionProfileProvider().takeIf { !it.isNullOrBlank() }

    /**
     * Supplies non-model overrides for each fresh `session.create`. Model and
     * provider must never ride this raw create contract because that bypasses
     * upstream model-selection confirmation; all model changes use [setModel]
     * after [prepareModelSelectionSession].
     */
    @Volatile
    var sessionModelProvider: () -> GatewaySessionModel? = { null }

    private fun currentSessionModel(): GatewaySessionModel? =
        sessionModelProvider()?.takeIf {
            !it.model.isNullOrBlank() || !it.reasoningEffort.isNullOrBlank() || it.fast != null
        }

    @Volatile
    private var activeTurn: GatewayTurn? = null

    private data class RecoveryEvent(
        val sessionId: String,
        val type: String,
        val payload: JsonObject?,
    )

    private val recoveryEventLock = Any()
    private var recoveryEvents: MutableList<RecoveryEvent>? = null

    /**
     * Turns deliberately detached when the user switches profile/session.
     * Upstream continues them server-side; retain the live→durable binding so
     * their terminal event can trigger an authoritative history reconcile.
     */
    private val backgroundTurns = ConcurrentHashMap<String, BackgroundTurn>()

    private data class BackgroundTurn(
        val storedSessionId: String,
        val profile: String?,
        @Volatile var pendingAsk: GatewayAsk? = null,
    )

    private class ChildWatchRegistration(
        val storedSessionId: String,
        val liveSessionId: String,
        val profile: String?,
        val generation: Long,
        val callbacks: GatewayTurnCallbacks,
    ) {
        lateinit var mapper: GatewayEventMapper
    }

    private data class ChildWatchEvent(
        val sessionId: String,
        val type: String,
        val payload: JsonObject?,
    )

    private data class PendingChildWatchReplay(
        val events: List<ChildWatchEvent>,
        val truncated: Boolean,
    )

    private class PendingChildWatchOpen {
        private val lock = Any()
        private val events = mutableListOf<ChildWatchEvent>()
        private var closed = false
        private var truncated = false

        fun capture(event: ChildWatchEvent): Boolean = synchronized(lock) {
            if (closed) return@synchronized false
            if (events.size >= MAX_PENDING_CHILD_WATCH_EVENTS) {
                events.removeAt(0)
                truncated = true
            }
            events += event
            true
        }

        fun closeAndTake(sessionId: String): PendingChildWatchReplay = synchronized(lock) {
            closed = true
            PendingChildWatchReplay(
                events = events.filter { it.sessionId == sessionId },
                truncated = truncated,
            ).also { events.clear() }
        }

        fun close() = synchronized(lock) {
            closed = true
            events.clear()
        }
    }

    private data class BoundedChildHistory(
        val messages: List<MessageItem>,
        val truncated: Boolean,
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
     * One exact turn settled from authoritative session state may still receive
     * the terminal frame that was already in flight. Consume only that terminal
     * so it cannot be reported as a second unmatched completion. A subsequent
     * message.start clears the drain because it establishes the next turn on
     * the same live runtime.
     */
    @Volatile
    private var settledTurnDrain: SettledTurnDrain? = null

    private data class SettledTurnDrain(
        val storedSessionId: String,
        val liveSessionId: String,
    )

    private data class ActiveTurnLivenessProbe(
        val turn: GatewayTurn,
        val storedSessionId: String,
        val liveSessionId: String,
        val progressGeneration: Long,
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
        ((GatewayBackgroundTurnCompletion) -> Unit)? = null

    /** Input requested or resolved on a deliberately detached turn. */
    @Volatile
    private var backgroundInteractionListener:
        ((GatewayBackgroundInteractionEvent) -> Unit)? = null

    /**
     * Connection-level process listener. Unlike [GatewayTurnCallbacks], this is
     * consulted even when there is no locally initiated [activeTurn].
     */
    @Volatile
    private var processEventListener: ((GatewayProcessEvent) -> Unit)? = null

    /** Process-wide durable-session invalidation/liveness edge. */
    @Volatile
    private var sessionDirectoryInvalidationListener: (() -> Unit)? = null

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
    private var hasEverReachedReady = false
    private var coldStartFailureEpisodes = 0

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
     * @param truncateBeforeRowId durable identity of the same target user row
     *   when current Gateway history supplied one. Sent alongside the ordinal
     *   so the server can fail closed if local position and durable identity
     *   diverge; omitted for older Gateway history without row ids.
     * @param queuedFollowUp true only when Android is draining a prompt the
     *   user explicitly queued behind an active turn. Newer gateways use the
     *   additive `queued:true` marker to preserve run-after semantics while
     *   the previous turn is still settling; older gateways ignore it.
     * @param onAttachmentFailure invoked when attachment bytes could not be
     *   safely bound to this Gateway turn. Callers must surface that failure
     *   instead of falling back to a transport without equivalent media.
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
        truncateBeforeRowId: Long? = null,
        queuedFollowUp: Boolean = false,
        onSurvivorUserRowIds: (List<Long?>) -> Unit = { },
        onTransportAccepted: () -> Unit = { },
        onAttachmentFailure: ((String) -> Unit)? = null,
        onPreflightFailure: (reason: String) -> Unit,
    ): ActiveTurnHandle {
        val turn = GatewayTurn(
            callbacks = dispatchOn(callbacks),
            androidOwned = true,
            onTransportAccepted = onTransportAccepted,
        )
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
                val stagedImagePaths = mutableListOf<String>()
                val attachmentRefs = attachments.mapNotNull { attachment ->
                    val upload = uploadAttachment(attachment).getOrElse { e ->
                        cleanupStagedAttachments(stagedImagePaths)
                        throw GatewayAttachmentPreflightException("attachment upload failed: ${e.message}")
                    }
                    stagedImagePaths += upload.attachedImagePaths()
                    if (attachment.requiresPromptReference()) {
                        upload.stringField("ref_text")
                            ?: run {
                                cleanupStagedAttachments(stagedImagePaths)
                                throw GatewayAttachmentPreflightException(
                                    "attachment upload failed: Hermes returned no readable file reference",
                                )
                            }
                    } else {
                        null
                    }
                }
                if (turn.cancelled) {
                    cleanupStagedAttachments(stagedImagePaths)
                    return@launch
                }
                if (!awaitCancelledTurnDrain(turn, storedSessionId)) {
                    cleanupStagedAttachments(stagedImagePaths)
                    return@launch
                }
                // A newly accepted Android send is a distinct generation on
                // this runtime. Its terminal must never be consumed by the
                // prior turn's optional late-terminal drain.
                settledTurnDrain = null
                activeTurn = turn
                turn.armWatchdog()
                // Generic `file.attach` uploads are staged artifacts, not
                // session-owned image/PDF attachments. The gateway returns
                // the exact workspace/sandbox-safe `@file:` reference that
                // must accompany this prompt. Keep the user's prose last,
                // matching upstream Desktop's context-reference contract.
                val submittedText = (attachmentRefs + text)
                    .filter(String::isNotBlank)
                    .joinToString("\n\n")
                val submitted = rpc(
                    "prompt.submit",
                    buildJsonObject {
                        put("session_id", liveSessionId ?: error("no live session"))
                        put("text", submittedText)
                        if (truncateBeforeUserOrdinal != null || truncateBeforeRowId != null) {
                            put("confirm_truncate", true)
                        }
                        truncateBeforeUserOrdinal?.let { ordinal ->
                            put("truncate_before_user_ordinal", ordinal)
                            if (ordinal == 0) put("confirm_empty_truncate", true)
                        }
                        truncateBeforeRowId?.let { put("truncate_before_row_id", it) }
                        if (queuedFollowUp) put("queued", true)
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
                    // into a second transport, which would resubmit the same
                    // prompt as a duplicate turn. Recovery belongs to the
                    // stream: the watchdog and mid-turn rejoin own it.
                    if (turn.started || turn.ended || turn.transportRecoveryStarted) {
                        turn.markTransportAccepted()
                        Log.w(
                            TAG,
                            "prompt.submit ack failed after turn start/rejoin " +
                                "(${submitted.exceptionOrNull()?.message}) — no SSE fallback",
                        )
                        return@launch
                    }
                    if (activeTurn === turn) activeTurn = null
                    turn.disarmWatchdog()
                    val submitError = submitted.exceptionOrNull()
                    if (submitError.isAuthoritativePromptSubmitRejection()) {
                        // Authoritative policy rejection: the gateway received
                        // the prompt and deliberately refused to create the
                        // first turn. Falling back to SSE would bypass the cap
                        // and duplicate the optimistic user turn on another
                        // transport. Surface the holder-aware upstream message
                        // through the normal failed-turn callback instead.
                        cleanupStagedAttachments(stagedImagePaths)
                        turn.tracer.done("submit-rejected")
                        turn.callbacks.onSubmitRejected(
                            submitError?.message ?: "Hermes rejected the new session",
                        )
                        return@launch
                    }
                    if (attachments.isNotEmpty()) {
                        cleanupStagedAttachments(stagedImagePaths)
                        throw GatewayAttachmentPreflightException(
                            submitError?.message ?: "attachment prompt submission could not be confirmed",
                        )
                    }
                    throw GatewayPreflightException(
                        submitError?.message ?: "prompt.submit failed",
                    )
                }
                turn.markTransportAccepted()
                (submitted.getOrNull()?.get("survivor_user_row_ids") as? JsonArray)?.let { raw ->
                    val rebound = raw.map { element ->
                        (element as? JsonPrimitive)?.longOrNull
                    }
                    callbackDispatcher { onSurvivorUserRowIds(rebound) }
                }
                turn.tracer.mark("submit")
                // One INFO line per turn so logcat shows which transport
                // served a send — the SSE paths log their SSE events, and
                // a silent happy path here made on-device verification a
                // read-the-absence exercise.
                Log.i(TAG, "Gateway turn submitted (session=$storedSessionId)")
            } catch (e: GatewayAuthoritativeResumeException) {
                if (activeTurn === turn) activeTurn = null
                if (!turn.cancelled) {
                    turn.disarmWatchdog()
                    turn.tracer.done("resume-rejected")
                    turn.callbacks.onResumeFailure(
                        e.message ?: "Hermes could not resume this session",
                    )
                }
            } catch (e: GatewayAttachmentPreflightException) {
                if (activeTurn === turn) activeTurn = null
                if (!turn.cancelled) {
                    Log.w(TAG, "Gateway attachment preflight failed: ${e.message}")
                    turn.disarmWatchdog()
                    turn.tracer.done("attachment-preflight-fail")
                    callbackDispatcher {
                        (onAttachmentFailure ?: onPreflightFailure)(
                            e.message ?: "attachment could not be sent",
                        )
                    }
                }
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
        failSessionReadyWaiters("gateway session cleared")
        lazyLiveSessions.clear()
        readyLiveSessions.clear()
        cancelledTurnDrain = null
        _serverTools.value = null
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
        val backgroundTurn = BackgroundTurn(
            storedSessionId = storedId,
            profile = liveSessionProfile,
            pendingAsk = turn.pendingInteraction,
        )
        backgroundTurns[liveId] = backgroundTurn
        backgroundTurn.pendingAsk?.let { ask ->
            callbackDispatcher {
                backgroundInteractionListener?.invoke(
                    GatewayBackgroundInteractionEvent.Requested(
                        storedSessionId = storedId,
                        profile = backgroundTurn.profile,
                        ask = ask,
                    ),
                )
            }
        }
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
    fun hasActiveTurn(): Boolean = activeTurn?.ended == false || backgroundTurns.isNotEmpty()

    /** True only when [storedId] still owns a foreground or deliberately detached turn. */
    fun hasActiveTurnForSession(storedId: String): Boolean =
        (activeTurn?.ended == false && storedSessionId == storedId) ||
            backgroundTurns.values.any { it.storedSessionId == storedId }

    /** Live id to persist beside a durable stored id while a turn is active. */
    fun currentLiveSessionId(storedId: String): String? =
        liveSessionId?.takeIf { storedSessionId == storedId }

    /**
     * Exact durable/profile owner already held by this client for [runtimeId].
     * Unlike `session.active_list`, this mapping is safe for multiplexed profiles
     * because Android recorded it when the runtime was created/resumed/detached.
     */
    fun knownSessionOwner(runtimeId: String): GatewayKnownSessionOwner? {
        if (runtimeId == liveSessionId) {
            val storedId = storedSessionId ?: return null
            return GatewayKnownSessionOwner(storedId, liveSessionProfile)
        }
        return backgroundTurns[runtimeId]?.let { owner ->
            GatewayKnownSessionOwner(owner.storedSessionId, owner.profile)
        }
    }

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
            retargetedThisTurn = activeTurn?.ended == false
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
        listener: ((GatewayBackgroundTurnCompletion) -> Unit)?,
    ) {
        unmatchedTurnCompleteListener = listener
    }

    fun setBackgroundInteractionListener(
        listener: ((GatewayBackgroundInteractionEvent) -> Unit)?,
    ) {
        backgroundInteractionListener = listener
    }

    fun setProcessEventListener(listener: ((GatewayProcessEvent) -> Unit)?) {
        processEventListener = listener
    }

    fun setSessionDirectoryInvalidationListener(listener: (() -> Unit)?) {
        sessionDirectoryInvalidationListener = listener
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
     * Establish only the shared Gateway socket for read-only observation.
     *
     * `session.resume` and `session.activate` attach a live runtime to this
     * transport. Opening Chat, foreground restoration, and selecting a saved
     * transcript must not claim a turn that another Desktop/TUI client owns,
     * so those paths use this socket-only warmup and observe through REST
     * history plus `session.active_list` instead.
     */
    fun observe(onReady: (() -> Unit)? = null) {
        scope.launch {
            if (observeAwait() && onReady != null) callbackDispatcher(onReady)
        }
    }

    /** Suspending [observe]; returns true once the read-only socket is ready. */
    suspend fun observeAwait(): Boolean = try {
        connectMutex.withLock { ensureConnected() }
        true
    } catch (e: Exception) {
        Log.d(TAG, "Gateway observation warmup skipped: ${e.message}")
        false
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
     * Open the vanilla-upstream child-session watcher advertised by
     * `subagent.*.child_session_id`. This RPC deliberately does not mutate
     * [liveSessionId], [storedSessionId], or [liveSessionProfile]: the parent
     * conversation keeps owning the main mapper while the returned short live
     * id routes a second, read-only event stream on the same socket.
     *
     * Returned history is bounded locally even when an upstream gateway sends
     * the child's entire transcript in the resume acknowledgement. The server
     * may still enforce its own larger resume safety limit before replying.
     */
    suspend fun openChildWatch(
        childSessionId: String,
        profile: String? = currentSessionProfile(),
        callbacks: GatewayTurnCallbacks,
        historyLimit: Int = MAX_CHILD_WATCH_HISTORY_ITEMS,
    ): Result<GatewayChildWatch> = runCatching {
        val storedChildId = childSessionId.trim()
        require(storedChildId.isNotEmpty()) { "child session id is required" }
        val requestedProfile = profile?.trim()?.takeIf(String::isNotEmpty)
        connectMutex.withLock {
            // Allocate and register under the same mutex as the resume RPC so
            // concurrent opens complete in generation order; an older caller
            // can never `put` after a newer one for the same live child id.
            val generation = childWatchGeneration.incrementAndGet()
            val pending = PendingChildWatchOpen()
            pendingChildWatchOpens[generation] = pending
            try {
                ensureConnected()
                val result = rpc(
                    "session.resume",
                    buildJsonObject {
                        put("session_id", storedChildId)
                        put("cols", DEFAULT_COLS)
                        put("source", sessionSource)
                        put("lazy", true)
                        put("close_on_disconnect", true)
                        requestedProfile?.let { put("profile", it) }
                    },
                ).getOrElse { error ->
                    throw GatewayPreflightException(
                        "child session resume failed: ${error.message}",
                    )
                }
                val liveChildId = result.stringField("session_id")?.takeIf(String::isNotBlank)
                    ?: throw GatewayPreflightException(
                        "child session resume returned no live session id",
                    )
                try {
                    requireConfirmedSessionProfile(result, requestedProfile)
                } catch (error: GatewayPreflightException) {
                    // The wrong profile must not leave an unowned lazy watcher behind.
                    rpc(
                        "session.close",
                        buildJsonObject { put("session_id", liveChildId) },
                    )
                    throw error
                }

                val registration = ChildWatchRegistration(
                    storedSessionId = storedChildId,
                    liveSessionId = liveChildId,
                    profile = requestedProfile,
                    generation = generation,
                    callbacks = callbacks,
                )
                val dispatchedCallbacks = dispatchOn(callbacks) {
                    childWatches[liveChildId] === registration
                }
                registration.mapper = GatewayEventMapper(
                    dispatchedCallbacks,
                    dedupeAdjacentMessageStarts = true,
                )
                childWatches.put(liveChildId, registration)?.let { prior ->
                    if (prior.generation != generation) {
                        notifyChildWatchFailure(
                            prior,
                            "Child watch was replaced by a newer view",
                        )
                    }
                }

                // Replay only frames tagged with the exact live id returned by
                // this resume. Unknown gateway sessions captured during the
                // narrow ack race remain foreign and are discarded.
                val replay = pending.closeAndTake(liveChildId)
                if (replay.truncated) dispatchedCallbacks.onReconcileRequired()
                replay.events.forEach { event ->
                    if (childWatches[liveChildId] === registration) {
                        registration.mapper.onEvent(event.type, event.payload)
                    }
                }
                val replayedTerminal = replay.events.any {
                    it.type == "message.complete" || it.type == "error"
                }

                val history = parseChildWatchMessages(result, historyLimit)
                GatewayChildWatch(
                    storedSessionId = storedChildId,
                    liveSessionId = liveChildId,
                    profile = requestedProfile,
                    generation = generation,
                    messages = history.messages,
                    historyTruncated = history.truncated,
                    running = !replayedTerminal && result.booleanField("running") == true,
                    status = if (replayedTerminal) "idle" else result.stringField("status"),
                )
            } finally {
                pendingChildWatchOpens.remove(generation, pending)
                pending.close()
            }
        }
    }

    /**
     * Close only the exact lazy watcher represented by [watch]. A stale handle
     * is a no-op so it can never close a newer watcher whose live id was reused.
     * The parent session and delegated child continue running server-side.
     */
    suspend fun closeChildWatch(watch: GatewayChildWatch): Result<Unit> =
        connectMutex.withLock {
            val registration = childWatches[watch.liveSessionId]
                ?: return@withLock Result.success(Unit)
            if (
                registration.generation != watch.generation ||
                registration.storedSessionId != watch.storedSessionId ||
                registration.profile != watch.profile ||
                !childWatches.remove(watch.liveSessionId, registration)
            ) {
                return@withLock Result.success(Unit)
            }
            if (webSocket == null || readySignal?.isCompleted != true) {
                return@withLock Result.success(Unit)
            }
            val result = rpc(
                "session.close",
                buildJsonObject { put("session_id", watch.liveSessionId) },
            )
            result.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { error ->
                    // Permit an exact-handle retry. Opens share connectMutex,
                    // so no newer registration can race this restoration.
                    childWatches.putIfAbsent(watch.liveSessionId, registration)
                    Result.failure(error)
                },
            )
        }

    private fun parseChildWatchMessages(
        result: JsonObject,
        requestedLimit: Int,
    ): BoundedChildHistory {
        val limit = requestedLimit.coerceIn(1, MAX_CHILD_WATCH_HISTORY_ITEMS)
        val all = (result["messages"] as? JsonArray).orEmpty()
        val raw = all.takeLast(limit)
        var retainedChars = 0
        var truncated = all.size > raw.size
        val newestFirst = raw.asReversed().mapNotNull { element ->
            val message = element as? JsonObject ?: run {
                truncated = true
                return@mapNotNull null
            }
            // Gateway display history uses `text`; the shared session DTO uses
            // `content`. Normalize only that projection boundary.
            val normalized = JsonObject(message.toMutableMap().apply {
                if (!containsKey("content")) {
                    put("content", message["text"] ?: message["context"] ?: JsonNull)
                }
                if (!containsKey("tool_name") && message.containsKey("name")) {
                    put("tool_name", message["name"] ?: JsonNull)
                }
            })
            val serializedChars = normalized.toString().length
            if (serializedChars > MAX_CHILD_WATCH_HISTORY_CHARS - retainedChars) {
                truncated = true
                return@mapNotNull null
            }
            val decoded = runCatching {
                json.decodeFromJsonElement(MessageItem.serializer(), normalized)
            }.onFailure {
                Log.w(TAG, "child watch returned an unreadable history row", it)
            }.getOrNull()
            if (decoded == null) {
                truncated = true
                null
            } else {
                retainedChars += serializedChars
                decoded
            }
        }
        return BoundedChildHistory(newestFirst.asReversed(), truncated)
    }

    /**
     * Obtain a session-scoped target before a model-selection `config.set`.
     *
     * Unlike [prewarmAwait], a draft is deliberately materialized here because
     * sending a model with `session.create` bypasses upstream's cost/data-policy
     * confirmation guard, while a sessionless `config.set` mutates global
     * configuration. Draft creation therefore binds only the profile and
     * non-model session options; the model always follows through [setModel].
     */
    suspend fun prepareModelSelectionSession(requestedStoredId: String?): Result<String> {
        if (requestedStoredId != null) {
            return if (prewarmAwait(requestedStoredId)) {
                Result.success(requestedStoredId)
            } else {
                Result.failure(GatewayPreflightException("gateway session is unavailable"))
            }
        }
        return runCatching {
            val requestedProfile = currentSessionProfile()
            connectMutex.withLock {
                ensureConnected()
                val reusableStoredId = storedSessionId
                if (
                    liveSessionId != null && reusableStoredId != null &&
                    liveSessionProfile == requestedProfile
                ) {
                    return@withLock reusableStoredId
                }
                if (liveSessionId != null || storedSessionId != null) {
                    throw GatewayPreflightException("draft session state changed before model selection")
                }
                val created = rpc(
                    "session.create",
                    buildJsonObject {
                        put("cols", DEFAULT_COLS)
                        put("source", sessionSource)
                        requestedProfile?.let { put("profile", it) }
                        currentSessionModel()?.let { options ->
                            options.reasoningEffort
                                ?.takeIf(String::isNotBlank)
                                ?.let { put("reasoning_effort", it) }
                            options.fast?.let { put("fast", it) }
                        }
                    },
                ).getOrElse { error ->
                    throw GatewayPreflightException("session.create failed: ${error.message}")
                }
                requireConfirmedSessionProfile(created, requestedProfile)
                val live = created.stringField("session_id")
                    ?: throw GatewayPreflightException("session.create returned no session_id")
                val stored = created.stringField("stored_session_id") ?: live
                liveSessionId = live
                storedSessionId = stored
                liveSessionProfile = requestedProfile
                if (cancelledTurnDrain?.storedSessionId != stored) cancelledTurnDrain = null
                stored
            }
        }
    }

    /**
     * Reattach callbacks to a turn that survived the Android UI/process.
     *
     * New Hermes gateways expose `session.activate`, which attaches the new
     * WebSocket transport to the exact live id saved in the client checkpoint.
     * If that id has already been reaped (or the method is unavailable), fall
     * back to `session.resume` by durable session id. Its `running`, `inflight`,
     * and optional `queued` fields decide whether a live mapper is installed or
     * history should settle the turn instead.
     */
    suspend fun recoverTurn(
        storedId: String,
        preferredLiveId: String?,
        callbacks: GatewayTurnCallbacks,
        queuedTurnProvider: ((GatewayQueuedTurn) -> GatewayInboundTurnRegistration?)? = null,
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
            var claimedBackground: BackgroundTurn? = null

            if (!preferredLiveId.isNullOrBlank()) {
                // A deliberately detached sibling owns this id in
                // backgroundTurns. Claim it before activation so the first
                // post-attach delta reaches the new live mapper instead of the
                // background completion-only gate.
                claimedBackground = backgroundTurns.remove(preferredLiveId)
                // Bind before session.activate: upstream swaps the live session's
                // transport during the RPC, so an immediate next delta must not
                // fall through the active-turn gate while the ack is in flight.
                liveSessionId = preferredLiveId
                storedSessionId = storedId
                liveSessionProfile = requestedProfile
                boundTurn = GatewayTurn(
                    callbacks = dispatchOn(callbacks),
                    dedupeAdjacentMessageStarts = true,
                    deferEvents = true,
                    androidOwned = true,
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
                    boundTurn.discardDeferredEvents()
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
                synchronized(recoveryEventLock) {
                    recoveryEvents = mutableListOf()
                }
                response = try {
                    rpc(
                        "session.resume",
                        buildJsonObject {
                            put("session_id", storedId)
                            put("cols", DEFAULT_COLS)
                            put("source", sessionSource)
                            requestedProfile?.let { put("profile", it) }
                        },
                    ).getOrElse { error ->
                        preferredLiveId?.let { liveId ->
                            claimedBackground?.let { backgroundTurns.putIfAbsent(liveId, it) }
                        }
                        throw error
                    }
                } catch (error: Throwable) {
                    synchronized(recoveryEventLock) { recoveryEvents = null }
                    throw error
                }
            }

            try {
                requireConfirmedSessionProfile(response, requestedProfile)
            } catch (error: GatewayPreflightException) {
                synchronized(recoveryEventLock) { recoveryEvents = null }
                if (activeTurn === boundTurn) activeTurn = null
                boundTurn?.discardDeferredEvents()
                boundTurn?.detach()
                if (!preferredLiveId.isNullOrBlank()) {
                    claimedBackground?.let { backgroundTurns.putIfAbsent(preferredLiveId, it) }
                }
                liveSessionId = null
                storedSessionId = null
                liveSessionProfile = null
                throw error
            }

            val recoveredLiveId = response.stringField("session_id")
                ?: run {
                    synchronized(recoveryEventLock) { recoveryEvents = null }
                    if (!preferredLiveId.isNullOrBlank()) {
                        claimedBackground?.let { backgroundTurns.putIfAbsent(preferredLiveId, it) }
                    }
                    throw GatewayRpcException("session recovery returned no session_id")
                }
            liveSessionId = recoveredLiveId
            storedSessionId = storedId
            liveSessionProfile = requestedProfile
            updateCancelledDrainLiveSession(storedId, recoveredLiveId)
            applySessionResultInfo(response)

            val inflight = (response["inflight"] as? JsonObject)?.let { value ->
                GatewayInflightTurn(
                    user = value.stringField("user").orEmpty(),
                    assistant = value.stringField("assistant").orEmpty(),
                    streaming = value.booleanField("streaming") == true,
                    corrections = (value["corrections"] as? JsonArray)
                        ?.mapNotNull { correction ->
                            (correction as? JsonPrimitive)
                                ?.contentOrNull
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?.take(MAX_RECOVERED_CORRECTION_CHARS)
                        }
                        ?.take(MAX_RECOVERED_CORRECTIONS)
                        .orEmpty(),
                    status = value.stringField("status"),
                    error = value.stringField("error"),
                    recoverable = value.booleanField("recoverable") == true,
                )
            }
            val queued = (response["queued"] as? JsonObject)?.let { value ->
                value.stringField("user")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::GatewayQueuedTurn)
            }
            val running = response.booleanField("running") == true || inflight?.streaming == true
            val autoContinue = (response["auto_continue"] as? JsonObject)?.let { value ->
                val attempt = value.stringField("attempt")?.toIntOrNull()
                    ?: (value["attempt"] as? JsonPrimitive)?.intOrNull
                if (attempt != null && attempt > 0) {
                    GatewayAutoContinue(
                        attempt = attempt,
                        interruptedAt = value.stringField("interrupted_at")?.toDoubleOrNull(),
                    )
                } else {
                    null
                }
            }

            if (running || autoContinue != null) {
                if (boundTurn == null || boundTurn.ended) {
                    boundTurn = GatewayTurn(
                        callbacks = dispatchOn(callbacks),
                        dedupeAdjacentMessageStarts = true,
                        androidOwned = true,
                    ).also { turn ->
                        turn.markRecoveredStarted()
                        activeTurn = turn
                    }
                }
                val buffered = synchronized(recoveryEventLock) {
                    recoveryEvents
                        ?.filter { it.sessionId == recoveredLiveId }
                        .orEmpty()
                        .also { recoveryEvents = null }
                }
                buffered.forEach { event -> boundTurn?.onEvent(event.type, event.payload) }
                queued?.let { queuedTurn ->
                    queuedTurnProvider?.invoke(queuedTurn)?.let { registration ->
                        boundTurn.installQueuedSuccessor(registration)
                    }
                }
                boundTurn.releaseDeferredEvents()
                claimedBackground?.pendingAsk?.let { ask ->
                    boundTurn.restoreInteraction(ask)
                }
                boundTurn.armWatchdog()
            } else if (queued != null) {
                synchronized(recoveryEventLock) { recoveryEvents = null }
                // A queued-only snapshot belongs to the NEXT turn. Never let
                // its events flow through the completed checkpoint's mapper.
                val priorBoundTurn = boundTurn
                boundTurn = null
                val registration = queuedTurnProvider?.invoke(queued)
                if (registration != null) {
                    val queuedTurn = GatewayTurn(
                        callbacks = dispatchOn(registration.callbacks),
                        dedupeAdjacentMessageStarts = true,
                        androidOwned = true,
                    )
                    // recoverTurn is resumed on its caller's coroutine context;
                    // ChatViewModel calls it from Main, so this admission runs
                    // atomically with the checkpoint handoff. Posting back
                    // through bindInboundTurn would deadlock waiting on the
                    // same paused Main dispatcher during cold-start recovery.
                    if (registration.onHandle(queuedTurn)) {
                        priorBoundTurn?.redirectDeferredEventsTo(queuedTurn)
                        boundTurn = queuedTurn
                        activeTurn = queuedTurn
                        queuedTurn.armWatchdog()
                        priorBoundTurn?.detach()
                    } else {
                        priorBoundTurn?.discardDeferredEvents()
                        priorBoundTurn?.detach()
                    }
                } else {
                    priorBoundTurn?.discardDeferredEvents()
                    priorBoundTurn?.detach()
                }
            } else {
                synchronized(recoveryEventLock) { recoveryEvents = null }
                if (boundTurn != null) {
                    if (activeTurn === boundTurn) activeTurn = null
                    boundTurn.discardDeferredEvents()
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
                queued = queued,
                autoContinue = autoContinue,
                handle = (if (boundTurn?.ended == true) activeTurn else boundTurn)
                    ?.takeUnless { it.ended },
            )
        }
    }

    /**
     * Inject [text] into the in-flight turn. Current upstream exposes this as
     * `session.redirect`, which can redirect an active model turn while keeping
     * valid work/context. Older gateways only expose `session.steer`; fall back
     * to that legacy RPC only when the redirect method is absent/unsupported so
     * old installs still queue the correction instead of dropping it.
     */
    suspend fun steer(text: String): SteerResult {
        val sid = liveSessionId ?: return SteerResult.Failed
        val params = buildJsonObject {
            put("session_id", sid)
            put("text", text)
        }
        val redirect = rpc("session.redirect", params)
        val result = if (redirect.isLegacyRedirectUnsupported()) {
            Log.i(TAG, "session.redirect unsupported — falling back to session.steer (session=$storedSessionId)")
            rpc("session.steer", params)
        } else {
            redirect
        }
        val outcome = when (result.getOrNull()?.stringField("status")) {
            "redirected", "queued" -> SteerResult.Queued
            "rejected" -> SteerResult.Rejected
            else -> SteerResult.Failed
        }
        Log.i(TAG, "Active-turn correction → $outcome (session=$storedSessionId)")
        return outcome
    }

    /**
     * Compress the live gateway session through the dedicated upstream RPC.
     * `/compress` is intentionally not sent through generic slash execution on
     * modern gateways because `session.compress` returns authoritative transcript,
     * usage, title/model/session-info, and compute-host isolation metadata.
     */
    suspend fun compressSession(focusTopic: String? = null): Result<GatewayCompressResult> {
        val sid = liveSessionId
            ?: return Result.failure(GatewayRpcException("no live session"))
        val params = buildJsonObject {
            put("session_id", sid)
            focusTopic?.trim()?.takeIf { it.isNotBlank() }?.let { put("focus_topic", it) }
        }
        val direct = rpc("session.compress", params, timeoutMs = COMPRESS_RPC_TIMEOUT_MS)
        val result = if (direct.exceptionOrNull().isMethodNotFound()) {
            val legacyCommand = "/compress" + focusTopic
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { " $it" }
                .orEmpty()
            slashExec(legacyCommand).map { slashResult ->
                buildJsonObject {
                    put("status", "legacy")
                    slashResult.stringField("output")?.let { put("output", it) }
                }
            }
        } else {
            direct
        }
        return result.map { payload ->
            applySessionResultInfo(payload)
            payload.toGatewayCompressResult()
        }
    }

    private fun Result<JsonObject>.isLegacyRedirectUnsupported(): Boolean {
        val error = exceptionOrNull() ?: return false
        val message = error.message.orEmpty()
        return error.isMethodNotFound() ||
            (error as? GatewayRpcException)?.code == 4010 ||
            message.contains("does not support active-turn redirect", ignoreCase = true)
    }

    private fun JsonObject.toGatewayCompressResult(): GatewayCompressResult =
        GatewayCompressResult(
            status = stringField("status") ?: if (
                (this["compressed"] as? JsonPrimitive)?.booleanOrNull == false
            ) {
                "noop"
            } else {
                "completed"
            },
            output = stringField("output") ?: stringField("message"),
            removed = (this["removed"] as? JsonPrimitive)?.intOrNull,
            beforeMessages = (this["before_messages"] as? JsonPrimitive)?.intOrNull,
            afterMessages = (this["after_messages"] as? JsonPrimitive)?.intOrNull,
            beforeTokens = (this["before_tokens"] as? JsonPrimitive)?.intOrNull,
            afterTokens = (this["after_tokens"] as? JsonPrimitive)?.intOrNull,
            usage = GatewayEventMapper.parseGatewayUsage(this["usage"] as? JsonObject),
            info = this["info"] as? JsonObject,
            messages = (this["messages"] as? JsonArray)?.let { messages ->
                runCatching {
                    json.decodeFromJsonElement(
                        ListSerializer(MessageItem.serializer()),
                        messages,
                    )
                }.getOrElse {
                    Log.w(TAG, "session.compress returned unreadable messages", it)
                    emptyList()
                }
            }.orEmpty(),
        )

    /** Answer a [GatewayAsk.Kind.CLARIFY] ask. */
    suspend fun respondClarify(requestId: String, answer: String): Result<GatewayAskResponse> {
        val respondingTurn = activeTurn
        return rpc(
            "clarify.respond",
            buildJsonObject {
                put("request_id", requestId)
                put("answer", answer)
            },
        ).map {
            it.gatewayAskResponse().also {
                respondingTurn?.acknowledgeInteraction(GatewayAskExpiry(GatewayAsk.Kind.CLARIFY, requestId))
            }
        }
    }

    /**
     * Answer a [GatewayAsk.Kind.SUDO] ask. The password must NEVER be logged
     * or persisted — it exists only inside this outbound frame.
     */
    suspend fun respondSudo(requestId: String, password: String): Result<GatewayAskResponse> {
        val respondingTurn = activeTurn
        return rpc(
            "sudo.respond",
            buildJsonObject {
                put("request_id", requestId)
                put("password", password)
            },
        ).map {
            it.gatewayAskResponse().also {
                respondingTurn?.acknowledgeInteraction(GatewayAskExpiry(GatewayAsk.Kind.SUDO, requestId))
            }
        }
    }

    /**
     * Answer a [GatewayAsk.Kind.SECRET] ask. Empty [value] = skip (upstream
     * returns `skipped: true` to the tool). The value must NEVER be logged
     * or persisted — it exists only inside this outbound frame.
     */
    suspend fun respondSecret(requestId: String, value: String): Result<GatewayAskResponse> {
        val respondingTurn = activeTurn
        return rpc(
            "secret.respond",
            buildJsonObject {
                put("request_id", requestId)
                put("value", value)
            },
        ).map {
            it.gatewayAskResponse().also {
                respondingTurn?.acknowledgeInteraction(GatewayAskExpiry(GatewayAsk.Kind.SECRET, requestId))
            }
        }
    }

    /**
     * Answer a [GatewayAsk.Kind.APPROVAL] ask — correlated by the live
     * session, not a request id. [choice] is "approve" or "deny"; [all]
     * resolves every pending approval on the session at once.
     */
    suspend fun respondApproval(choice: String, all: Boolean = false): Result<GatewayAskResponse> {
        val respondingTurn = activeTurn
        val sid = liveSessionId
            ?: return Result.failure(GatewayRpcException("no live session"))
        return rpc(
            "approval.respond",
            buildJsonObject {
                put("session_id", sid)
                put("choice", choice)
                put("all", all)
            },
        ).map {
            it.gatewayAskResponse().also {
                respondingTurn?.acknowledgeInteraction(GatewayAskExpiry(GatewayAsk.Kind.APPROVAL, null))
            }
        }
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
     * Official upstream Nous usage bars. Current hosts may not expose this
     * additive method yet; callers should treat JSON-RPC method-not-found as
     * capability absence and use the optional Relay enhancement when paired.
     */
    suspend fun usageBars(): Result<JsonObject> {
        try {
            connectMutex.withLock { ensureConnected() }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return rpc("usage.bars", JsonObject(emptyMap()))
    }

    /**
     * Create a schedule through upstream's authenticated `cron.manage` RPC.
     * No Relay scheduler or compatibility endpoint is involved.
     */
    suspend fun createCronJob(draft: CronCreationDraft): Result<JsonObject> {
        val validated = draft.validated().getOrElse { return Result.failure(it) }
        try {
            connectMutex.withLock { ensureConnected() }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return rpc(
            "cron.manage",
            buildJsonObject {
                put("action", "add")
                put("name", validated.name)
                put("schedule", validated.schedule)
                put("prompt", validated.prompt)
                validated.repeat?.let { put("repeat", it) }
                validated.profile?.let { put("profile", it) }
            },
        )
    }

    /** Current Hermes-owned profile roster; older gateways fail soft to the Dashboard/Relay list. */
    suspend fun listProfiles(): Result<List<Profile>> {
        if (profileListSupported == false) {
            return Result.failure(GatewayProfileManagementUnsupportedException("profiles.list"))
        }
        try {
            connectMutex.withLock { ensureConnected() }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val response = rpc(
            "profiles.list",
            buildJsonObject { put("include_sessions", false) },
        )
        if (response.exceptionOrNull().isMethodNotFound()) {
            profileListSupported = false
            return Result.failure(GatewayProfileManagementUnsupportedException("profiles.list"))
        }
        return response.mapCatching { payload ->
            (payload["profiles"] as? JsonArray).orEmpty().mapNotNull { raw ->
                val row = raw as? JsonObject ?: return@mapNotNull null
                val name = row.stringField("name")?.trim()?.takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null
                val uiMeta = (row["ui_meta"] as? JsonObject)
                    ?.takeIf(::isSafeProfileUiMeta)
                    ?: JsonObject(emptyMap())
                Profile(
                    name = name,
                    model = row.stringField("model").orEmpty(),
                    provider = row.stringField("provider").orEmpty(),
                    description = row.stringField("description").orEmpty(),
                    skillCount = (row["skill_count"] as? JsonPrimitive)?.intOrNull ?: 0,
                    isDefault = (row["is_default"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    hasAvatar = (row["has_avatar"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    uiMeta = uiMeta,
                )
            }
        }.onSuccess { profileListSupported = true }
    }

    /**
     * Rich Bot Mode roster from the upstream Gateway. Kept separate from
     * [listProfiles] because session previews and room projections are useful
     * to the messenger surface but needlessly expensive for ordinary profile
     * selectors.
     */
    suspend fun listBotModeRoster(): Result<BotModeRoster> {
        if (profileListSupported == false) {
            return Result.failure(GatewayProfileManagementUnsupportedException("profiles.list"))
        }
        try {
            connectMutex.withLock { ensureConnected() }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val response = rpc(
            "profiles.list",
            buildJsonObject { put("include_sessions", true) },
        )
        if (response.exceptionOrNull().isMethodNotFound()) {
            profileListSupported = false
            return Result.failure(GatewayProfileManagementUnsupportedException("profiles.list"))
        }
        return response.mapCatching(::parseBotModeRoster)
            .onSuccess { profileListSupported = true }
    }

    /**
     * Resolve the profile's one canonical hidden `Bot Chat`, creating it only
     * after an authoritative exact-title lookup returned no row. Lookup errors
     * fail closed so a transient connection problem can never fork the bot's
     * durable conversation.
     */
    suspend fun ensureCanonicalBotChat(profileName: String): Result<BotChatTarget> = runCatching {
        val profile = profileName.trim().takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("profile name required")
        connectMutex.withLock {
            ensureConnected()
            val existing = rpc(
                "session.list",
                buildJsonObject {
                    put("profile", profile)
                    put("title", BOT_CHAT_TITLE)
                    put("include_hidden", true)
                    put("limit", 200)
                },
            ).getOrElse { error ->
                throw GatewayPreflightException(
                    "Could not check $profile's Bot Chat registry: ${error.message}",
                )
            }
            val row = (existing["sessions"] as? JsonArray)
                ?.firstOrNull() as? JsonObject
            if (row != null) {
                val stored = row.stringField("id")?.takeIf(String::isNotBlank)
                    ?: throw GatewayPreflightException("Bot Chat registry returned no session id")
                val resolved = row.stringField("resolved_id")?.takeIf(String::isNotBlank) ?: stored
                return@withLock BotChatTarget(storedSessionId = stored, resolvedSessionId = resolved)
            }

            if (hasActiveTurn()) {
                throw GatewayPreflightException("Wait for the current Hermes turn to finish before creating Bot Chat")
            }
            val created = rpc(
                "session.create",
                buildJsonObject {
                    put("cols", DEFAULT_COLS)
                    put("source", sessionSource)
                    put("profile", profile)
                    put("title", BOT_CHAT_TITLE)
                    put("hidden", true)
                },
            ).getOrElse { error ->
                throw GatewayPreflightException("Bot Chat creation failed: ${error.message}")
            }
            requireConfirmedSessionProfile(created, profile)
            val live = created.stringField("session_id")
                ?: throw GatewayPreflightException("Bot Chat creation returned no session id")
            val stored = created.stringField("stored_session_id") ?: live

            // `session.create` is lazy. Title the live runtime immediately so
            // the durable exact-title registry exists before navigation or a
            // second tap; newer upstream materializes the row here.
            rpc(
                "session.title",
                buildJsonObject {
                    put("session_id", live)
                    put("title", BOT_CHAT_TITLE)
                },
            ).getOrElse { error ->
                throw GatewayPreflightException("Bot Chat could not be materialized: ${error.message}")
            }
            BotChatTarget(storedSessionId = stored, resolvedSessionId = stored)
        }
    }

    /** Create through the Gateway so auth behavior is explicit and server-owned. */
    suspend fun createProfile(request: GatewayProfileCreateRequest): Result<GatewayProfileCreateResult> {
        if (profileCreateSupported == false) {
            return Result.failure(GatewayProfileManagementUnsupportedException("profiles.create"))
        }
        val name = request.name.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("profile name required"))
        if ((request.model == null) != (request.provider == null)) {
            return Result.failure(IllegalArgumentException("provider and model must be supplied together"))
        }
        try {
            connectMutex.withLock { ensureConnected() }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val response = rpc(
            "profiles.create",
            buildJsonObject {
                put("name", name)
                request.description?.trim()?.takeIf(String::isNotEmpty)?.let { put("description", it) }
                request.cloneFrom?.trim()?.takeIf(String::isNotEmpty)?.let { put("clone_from", it) }
                put("clone_all", request.cloneAll)
                put("no_skills", request.noSkills)
                request.soul?.let { put("soul", it) }
                request.model?.trim()?.takeIf(String::isNotEmpty)?.let { put("model", it) }
                request.provider?.trim()?.takeIf(String::isNotEmpty)?.let { put("provider", it) }
                put("mirror_credentials", request.authChoice != GatewayProfileAuthChoice.Isolated)
                put("share_auth", request.authChoice == GatewayProfileAuthChoice.Shared)
            },
        )
        if (response.exceptionOrNull().isMethodNotFound()) {
            profileCreateSupported = false
            return Result.failure(GatewayProfileManagementUnsupportedException("profiles.create"))
        }
        return response.mapCatching { payload ->
            val returnedName = payload.stringField("name")
                ?: throw GatewayRpcException("profiles.create returned no profile name")
            if (returnedName != name) throw GatewayRpcException("profiles.create returned a different profile")
            val mirrored = payload["mirrored"] as? JsonObject ?: JsonObject(emptyMap())
            val auth = mirrored["auth"] as? JsonPrimitive
            GatewayProfileCreateResult(
                name = returnedName,
                soulWritten = (payload["soul_written"] as? JsonPrimitive)?.booleanOrNull ?: false,
                modelSet = (payload["model_set"] as? JsonPrimitive)?.booleanOrNull ?: false,
                mirroredEnvironment = (mirrored["env"] as? JsonPrimitive)?.booleanOrNull ?: false,
                mirroredAuth = auth?.contentOrNull,
                modelInherited = (mirrored["model_inherited"] as? JsonPrimitive)?.booleanOrNull ?: false,
                voiceMirrored = (mirrored["voice"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }.onSuccess { profileCreateSupported = true }
    }

    suspend fun getProfileAvatar(profileName: String): Result<GatewayProfileAsset?> {
        if (profileGetAssetSupported == false) {
            return Result.failure(GatewayProfileManagementUnsupportedException("profiles.get_asset"))
        }
        val name = profileName.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("profile name required"))
        try {
            connectMutex.withLock { ensureConnected() }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val response = rpc(
            "profiles.get_asset",
            buildJsonObject { put("name", name); put("asset", "avatar") },
        )
        if (response.exceptionOrNull().isMethodNotFound()) {
            profileGetAssetSupported = false
            return Result.failure(GatewayProfileManagementUnsupportedException("profiles.get_asset"))
        }
        return response.mapCatching { payload ->
            if ((payload["found"] as? JsonPrimitive)?.booleanOrNull != true) return@mapCatching null
            val declaredMime = payload.stringField("mime")
                ?: throw GatewayRpcException("profiles.get_asset returned no mime")
            val encoded = payload.stringField("data")
                ?: throw GatewayRpcException("profiles.get_asset returned no data")
            val (dataMime, base64) = splitImageData(encoded)
            if (dataMime != null && dataMime != declaredMime) {
                throw GatewayRpcException("profiles.get_asset mime mismatch")
            }
            val bytes = runCatching { Base64.getDecoder().decode(base64) }
                .getOrElse { throw GatewayRpcException("profiles.get_asset returned malformed base64") }
            if (bytes.size > PROFILE_AVATAR_MAX_BYTES) {
                throw GatewayRpcException("profiles.get_asset returned an oversized avatar")
            }
            val actualMime = imageMime(bytes)
                ?: throw GatewayRpcException("profiles.get_asset returned an unsupported image")
            if (actualMime != declaredMime) throw GatewayRpcException("profiles.get_asset magic mismatch")
            val declaredSize = (payload["size"] as? JsonPrimitive)?.intOrNull
            if (declaredSize != null && declaredSize != bytes.size) {
                throw GatewayRpcException("profiles.get_asset size mismatch")
            }
            GatewayProfileAsset(bytes, actualMime)
        }.onSuccess { profileGetAssetSupported = true }
    }

    suspend fun setProfileAvatar(profileName: String, bytes: ByteArray): Result<Int> {
        val mime = imageMime(bytes)
            ?: return Result.failure(IllegalArgumentException("avatar must be PNG, JPEG, or WebP"))
        if (bytes.size > PROFILE_AVATAR_MAX_BYTES) {
            return Result.failure(IllegalArgumentException("avatar exceeds the 2,000,000 byte limit"))
        }
        return setProfileAvatarPayload(
            profileName,
            expectedSize = bytes.size,
            params = buildJsonObject {
                put("name", profileName.trim())
                put("asset", "avatar")
                put("data", "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}")
            },
        )
    }

    suspend fun clearProfileAvatar(profileName: String): Result<Int> = setProfileAvatarPayload(
        profileName,
        expectedSize = 0,
        params = buildJsonObject {
            put("name", profileName.trim())
            put("asset", "avatar")
            put("clear", true)
        },
    )

    private suspend fun setProfileAvatarPayload(
        profileName: String,
        expectedSize: Int,
        params: JsonObject,
    ): Result<Int> {
        if (profileSetAssetSupported == false) {
            return Result.failure(GatewayProfileManagementUnsupportedException("profiles.set_asset"))
        }
        if (profileName.trim().isEmpty()) {
            return Result.failure(IllegalArgumentException("profile name required"))
        }
        try {
            connectMutex.withLock { ensureConnected() }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val response = rpc("profiles.set_asset", params)
        if (response.exceptionOrNull().isMethodNotFound()) {
            profileSetAssetSupported = false
            return Result.failure(GatewayProfileManagementUnsupportedException("profiles.set_asset"))
        }
        return response.mapCatching { payload ->
            if ((payload["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
                throw GatewayRpcException("profiles.set_asset was not acknowledged")
            }
            val size = (payload["size"] as? JsonPrimitive)?.intOrNull
                ?: throw GatewayRpcException("profiles.set_asset returned no size")
            if (size != expectedSize) throw GatewayRpcException("profiles.set_asset size mismatch")
            size
        }.onSuccess { profileSetAssetSupported = true }
    }

    private fun splitImageData(value: String): Pair<String?, String> {
        if (!value.startsWith("data:")) return null to value
        val marker = ";base64,"
        val split = value.indexOf(marker)
        if (split <= 5) throw GatewayRpcException("profiles.get_asset returned a malformed data URL")
        return value.substring(5, split) to value.substring(split + marker.length)
    }

    private fun imageMime(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
        ) -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"
        else -> null
    }

    /**
     * Capability probe and authoritative editor snapshot. A method-not-found
     * response is sticky for this client so older Hermes builds keep using the
     * existing Relay inspector without repeatedly sending unsupported RPCs.
     */
    override suspend fun describeProfile(
        profileName: String,
    ): Result<GatewayProfileDescription> {
        if (profileDescribeSupported == false) {
            return Result.failure(GatewayProfileEditorUnsupportedException())
        }
        val name = profileName.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("profile name required"))
        try {
            connectMutex.withLock { ensureConnected() }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val response = rpc(
            "profiles.describe",
            buildJsonObject { put("name", name) },
        )
        val error = response.exceptionOrNull()
        if (error.isMethodNotFound()) {
            profileDescribeSupported = false
            return Result.failure(GatewayProfileEditorUnsupportedException())
        }
        return response.mapCatching { payload ->
            parseProfileDescription(payload, expectedName = name)
        }.onSuccess {
            profileDescribeSupported = true
        }
    }

    /** Apply only fields explicitly present in [patch]; requires a successful describe first. */
    override suspend fun configureProfile(
        profileName: String,
        patch: GatewayProfilePatch,
    ): Result<GatewayProfileConfigureResult> {
        if (profileDescribeSupported != true || profileConfigureSupported == false) {
            return Result.failure(GatewayProfileEditorUnsupportedException())
        }
        val name = profileName.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException("profile name required"))
        if ((patch.provider == null) != (patch.model == null)) {
            return Result.failure(IllegalArgumentException("provider and model must be saved together"))
        }
        if (patch.uiMeta != null && !isSafeProfileUiMeta(patch.uiMeta)) {
            return Result.failure(IllegalArgumentException("ui_meta must contain only small preference/reference metadata"))
        }
        val requested = patch.requestedSections
        if (requested.isEmpty()) return Result.success(GatewayProfileConfigureResult(emptySet(), emptySet()))
        val params = buildJsonObject {
            put("name", name)
            patch.description?.let { put("description", it) }
            patch.soul?.let { put("soul", it) }
            patch.provider?.let { put("provider", it) }
            patch.model?.let { put("model", it) }
            patch.disabledSkills?.let { names ->
                put("disabled_skills", JsonArray(names.map(::JsonPrimitive)))
            }
            patch.enabledToolsets?.let { names ->
                put("enabled_toolsets", JsonArray(names.map(::JsonPrimitive)))
            }
            patch.enabledMcpServers?.let { names ->
                put("enabled_mcp_servers", JsonArray(names.map(::JsonPrimitive)))
            }
            patch.uiMeta?.let { put("ui_meta", it) }
        }
        val response = rpc("profiles.configure", params)
        if (response.exceptionOrNull().isMethodNotFound()) {
            profileConfigureSupported = false
            return Result.failure(GatewayProfileEditorUnsupportedException())
        }
        return response.mapCatching { payload ->
            val appliedObject = payload["applied"] as? JsonObject
                ?: throw GatewayRpcException("profiles.configure returned no applied map")
            val applied = requested.filterTo(linkedSetOf()) { section ->
                (appliedObject[section.wireName] as? JsonPrimitive)?.booleanOrNull == true
            }
            GatewayProfileConfigureResult(requested = requested, applied = applied)
        }.onSuccess {
            profileConfigureSupported = true
        }
    }

    private fun parseProfileDescription(
        payload: JsonObject,
        expectedName: String,
    ): GatewayProfileDescription {
        val name = payload.stringField("name")
            ?: throw GatewayRpcException("profiles.describe returned no profile name")
        if (name != expectedName) {
            throw GatewayRpcException("profiles.describe returned a different profile")
        }
        val model = payload["model"] as? JsonObject ?: JsonObject(emptyMap())
        val skills = (payload["skills"] as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val skillName = obj.stringField("name")?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            GatewayProfileSkill(
                name = skillName,
                enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
        }
        val toolsets = (payload["toolsets"] as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val toolsetName = obj.stringField("name")?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            GatewayProfileToolset(
                name = toolsetName,
                description = obj.stringField("description").orEmpty(),
                toolCount = (obj["tool_count"] as? JsonPrimitive)?.intOrNull ?: 0,
                enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
        }
        return GatewayProfileDescription(
            name = name,
            description = payload.stringField("description").orEmpty(),
            soul = payload.stringField("soul").orEmpty(),
            provider = model.stringField("provider").orEmpty(),
            model = model.stringField("default").orEmpty(),
            skills = skills,
            toolsets = toolsets,
            toolsetsPinned = (payload["toolsets_pinned"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    /**
     * Fetch the upstream gateway's cropped preview for a Petdex pet.
     *
     * A missing thumbnail is represented by a successful `null`, matching the
     * gateway's fail-open `{ "ok": false }` response. RPC errors, including
     * method-not-found on older upstream gateways, remain failures so callers
     * can distinguish an unavailable capability from a missing image.
     */
    suspend fun petThumbnail(
        slug: String,
        spritesheetUrl: String? = null,
        profile: String? = currentSessionProfile(),
    ): Result<String?> {
        val normalizedSlug = slug.trim()
        if (!PETDEX_SLUG.matches(normalizedSlug)) {
            return Result.failure(IllegalArgumentException("invalid Petdex slug"))
        }

        val normalizedUrl = spritesheetUrl?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedUrl != null && !isTrustedPetdexAssetUrl(normalizedUrl)) {
            return Result.failure(IllegalArgumentException("invalid Petdex spritesheet URL"))
        }

        try {
            connectMutex.withLock { ensureConnected() }
        } catch (e: Exception) {
            return Result.failure(e)
        }

        return rpc(
            "pet.thumb",
            buildJsonObject {
                put("slug", normalizedSlug)
                normalizedUrl?.let { put("url", it) }
                profile?.trim()?.takeIf { it.isNotEmpty() }?.let { put("profile", it) }
            },
        ).mapCatching { response ->
            val ok = (response["ok"] as? JsonPrimitive)?.booleanOrNull
                ?: throw GatewayRpcException("pet.thumb returned an invalid response")
            val responseSlug = (response["slug"] as? JsonPrimitive)?.contentOrNull
            if (responseSlug != normalizedSlug) {
                throw GatewayRpcException("pet.thumb returned a mismatched slug")
            }
            if (!ok) return@mapCatching null

            val dataUri = (response["dataUri"] as? JsonPrimitive)?.contentOrNull
            if (dataUri == null || !isValidPetThumbnailDataUri(dataUri)) {
                throw GatewayRpcException("pet.thumb returned an invalid thumbnail")
            }
            dataUri
        }
    }

    /** Fetch the active profile-scoped Hermes pet and its renderer contract. */
    suspend fun petInfo(
        profile: String? = currentSessionProfile(),
        knownRevision: String? = null,
    ): Result<GatewayPetInfo> {
        try {
            connectMutex.withLock { ensureConnected() }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return rpc(
            "pet.info",
            buildJsonObject {
                profile?.trim()?.takeIf { it.isNotEmpty() }?.let { put("profile", it) }
                knownRevision?.trim()?.takeIf { it.isNotEmpty() }?.let { put("knownRevision", it) }
            },
        ).mapCatching(::parseGatewayPetInfo)
    }

    /** List adoptable pets for the active Hermes profile. */
    suspend fun petGallery(
        profile: String? = currentSessionProfile(),
        localOnly: Boolean = false,
    ): Result<GatewayPetGallery> {
        try {
            connectMutex.withLock { ensureConnected() }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return rpc(
            "pet.gallery",
            buildJsonObject {
                put("localOnly", localOnly)
                profile?.trim()?.takeIf { it.isNotEmpty() }?.let { put("profile", it) }
            },
        ).mapCatching(::parseGatewayPetGallery)
    }

    /** Install if necessary and activate one pet in the selected Hermes profile. */
    suspend fun selectPet(
        slug: String,
        profile: String? = currentSessionProfile(),
    ): Result<Unit> = mutatePet("pet.select", slug, profile)

    /** Disable the active pet without deleting it from the selected Hermes profile. */
    suspend fun disablePet(profile: String? = currentSessionProfile()): Result<Unit> =
        mutatePet("pet.disable", slug = null, profile = profile)

    private suspend fun mutatePet(method: String, slug: String?, profile: String?): Result<Unit> {
        val normalizedSlug = slug?.trim()
        if (normalizedSlug != null && !PETDEX_SLUG.matches(normalizedSlug)) {
            return Result.failure(IllegalArgumentException("invalid Petdex slug"))
        }
        try {
            connectMutex.withLock { ensureConnected() }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return rpc(
            method,
            buildJsonObject {
                normalizedSlug?.let { put("slug", it) }
                profile?.trim()?.takeIf { it.isNotEmpty() }?.let { put("profile", it) }
            },
            timeoutMs = PET_MUTATION_TIMEOUT_MS,
        ).mapCatching { response ->
            if ((response["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
                throw GatewayRpcException("$method returned an invalid response")
            }
        }
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

    /**
     * Fetch authoritative in-memory execution states from current upstream
     * Hermes. `session.active_list` is process-wide: it accepts only an optional
     * current runtime id and does not profile-filter its rows. Accordingly this
     * transport returns rows unscoped and never derives activity from REST
     * `is_active` or stamps the selected profile onto a row.
     */
    suspend fun listActiveSessions(): GatewayActiveSessionsResult {
        if (_activeSessionCapability.value == GatewayActiveSessionCapability.Unsupported) {
            return GatewayActiveSessionsResult.Unsupported
        }
        try {
            connectMutex.withLock { ensureConnected() }
        } catch (error: Exception) {
            return GatewayActiveSessionsResult.TransientFailure(error)
        }
        val livenessProbe = captureActiveTurnLivenessProbe()
        val result = rpc(
            "session.active_list",
            buildJsonObject {
                liveSessionId?.let { put("current_session_id", it) }
            },
        )
        val error = result.exceptionOrNull()
        if (error.isMethodNotFound()) {
            _activeSessionCapability.value = GatewayActiveSessionCapability.Unsupported
            return GatewayActiveSessionsResult.Unsupported
        }
        if (error != null) {
            return GatewayActiveSessionsResult.TransientFailure(error)
        }
        _activeSessionCapability.value = GatewayActiveSessionCapability.Supported
        return try {
            val payload = result.getOrThrow()
            val rows = payload["sessions"] as? JsonArray
                ?: throw GatewayRpcException("session.active_list returned no sessions array")
            val sessions = rows.map(::parseGatewayActiveSession)
            reconcileActiveTurnFromSnapshot(livenessProbe, sessions)
            GatewayActiveSessionsResult.Success(sessions)
        } catch (parseError: Exception) {
            GatewayActiveSessionsResult.TransientFailure(parseError)
        }
    }

    /**
     * Capture only a locally submitted/recovered turn. Merely observing an
     * exact session through the shared Gateway socket never grants Android
     * authority to settle Desktop/TUI work.
     */
    private fun captureActiveTurnLivenessProbe(): ActiveTurnLivenessProbe? {
        val turn = activeTurn ?: return null
        val generation = turn.captureLivenessGeneration() ?: return null
        val storedId = storedSessionId ?: return null
        val liveId = liveSessionId ?: return null
        return ActiveTurnLivenessProbe(turn, storedId, liveId, generation)
    }

    /**
     * `session.active_list` is process-wide, but a row naming both identifiers
     * already owned by this client is authoritative for that exact runtime.
     * Fence the delayed snapshot by turn identity and progress generation so an
     * old idle result cannot settle a newer turn or race newer live events.
     */
    private fun reconcileActiveTurnFromSnapshot(
        probe: ActiveTurnLivenessProbe?,
        sessions: List<GatewayActiveSession>,
    ) {
        probe ?: return
        if (activeTurn !== probe.turn ||
            storedSessionId != probe.storedSessionId ||
            liveSessionId != probe.liveSessionId
        ) return
        val exact = sessions.singleOrNull { row ->
            row.runtimeSessionId == probe.liveSessionId &&
                row.storedSessionId == probe.storedSessionId
        } ?: return
        if (exact.status != GatewayActiveSessionStatus.Idle) return
        if (probe.turn.settleFromAuthoritativeSessionState(
                running = false,
                source = "session.active_list",
                expectedProgressGeneration = probe.progressGeneration,
            )
        ) {
            if (activeTurn === probe.turn) activeTurn = null
            if (!AppForegroundTracker.isForeground.value) scheduleBackgroundClose()
        }
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
     * List personalities through upstream's slash completer. Unlike the
     * dashboard config schema, this resolves the CLI config that contains both
     * built-in and profile-defined personalities, matching `/personality` in
     * the desktop and TUI.
     */
    suspend fun personalityOptions(): Result<List<String>> {
        if (webSocket == null || readySignal?.isCompleted != true) {
            return Result.failure(GatewayRpcException("not connected"))
        }
        return rpc(
            "complete.slash",
            buildJsonObject { put("text", "/personality ") },
        ).map(::parseGatewayPersonalityOptions)
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
            val providers = normalizeGatewayModelProviders(
                (result["providers"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    parseGatewayModelProvider(obj)
                },
            )
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
    suspend fun setModel(
        value: String,
        confirmSelection: Boolean = false,
    ): Result<JsonObject> =
        rpc(
            "config.set",
            buildJsonObject {
                put("key", "model")
                put("value", value)
                if (confirmSelection) put("confirm_expensive_model", true)
                liveSessionId?.let { put("session_id", it) }
            },
        )

    /**
     * React to the newest message for a role without guessing a transcript row
     * id. This follows the upstream gateway contract, which resolves the row
     * atomically inside the active session. A null emoji removes the reaction.
     */
    suspend fun reactToMessage(rowId: Long?, role: String, emoji: String?): Result<JsonObject> {
        require(role == "user" || role == "assistant") { "unsupported reaction role" }
        val sid = liveSessionId
            ?: return Result.failure(GatewayRpcException("no live session"))
        return rpc(
            "message.react",
            buildJsonObject {
                put("session_id", sid)
                if (rowId != null) put("row_id", rowId) else put("newest_role", role)
                if (emoji == null) put("emoji", JsonNull) else put("emoji", emoji)
                put("author", "user")
            },
        )
    }

    /** Redirect one running child agent without interrupting the parent turn. */
    suspend fun steerSubagent(subagentId: String, text: String): Result<JsonObject> {
        val sessionId = liveSessionId
            ?: return Result.failure(IllegalStateException("No live gateway session"))
        if (subagentId.isBlank() || text.isBlank()) {
            return Result.failure(IllegalArgumentException("Subagent and instruction are required"))
        }
        return rpc(
            "subagent.steer",
            buildJsonObject {
                put("session_id", sessionId)
                put("subagent_id", subagentId)
                put("text", text.trim())
            },
        )
    }

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
     * Read the profile-persisted approval policy added in gateway contract v3.
     * Older gateways either reject the key or return no recognized value; both
     * downgrade this optional control without affecting chat or per-session YOLO.
     */
    suspend fun getApprovalMode(): Result<GatewayApprovalMode> {
        if (_approvalModeCapability.value == GatewayApprovalModeCapability.Unsupported) {
            return Result.failure(approvalModeUnsupported())
        }
        if (currentSessionProfile() != null) {
            return Result.failure(approvalModeRequiresLaunchProfile())
        }
        if (webSocket == null || readySignal?.isCompleted != true) {
            try {
                connectMutex.withLock { ensureConnected() }
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        val response = rpc(
            "config.get",
            buildJsonObject { put("key", "approvals.mode") },
        )
        response.exceptionOrNull()?.let { error ->
            if (error.isApprovalModeUnsupported()) {
                _approvalModeCapability.value = GatewayApprovalModeCapability.Unsupported
            }
            return Result.failure(error)
        }
        val mode = GatewayApprovalMode.fromWire(response.getOrThrow().stringField("value"))
            ?: run {
                _approvalModeCapability.value = GatewayApprovalModeCapability.Unsupported
                return Result.failure(approvalModeUnsupported())
            }
        _approvalModeCapability.value = GatewayApprovalModeCapability.Supported
        _serverApprovalMode.value = mode
        return Result.success(mode)
    }

    /** Persist the selected approval policy for the active gateway profile. */
    suspend fun setApprovalMode(mode: GatewayApprovalMode): Result<GatewayApprovalMode> {
        if (_approvalModeCapability.value == GatewayApprovalModeCapability.Unsupported) {
            return Result.failure(approvalModeUnsupported())
        }
        if (currentSessionProfile() != null) {
            return Result.failure(approvalModeRequiresLaunchProfile())
        }
        if (webSocket == null || readySignal?.isCompleted != true) {
            try {
                connectMutex.withLock { ensureConnected() }
            } catch (e: Exception) {
                return Result.failure(e)
            }
        }
        val response = rpc(
            "config.set",
            buildJsonObject {
                put("key", "approvals.mode")
                put("value", mode.wireValue)
            },
        )
        response.exceptionOrNull()?.let { error ->
            if (error.isApprovalModeUnsupported()) {
                _approvalModeCapability.value = GatewayApprovalModeCapability.Unsupported
            }
            return Result.failure(error)
        }
        val authoritative =
            GatewayApprovalMode.fromWire(response.getOrThrow().stringField("value"))
                ?: run {
                    _approvalModeCapability.value = GatewayApprovalModeCapability.Unsupported
                    return Result.failure(approvalModeUnsupported())
                }
        _approvalModeCapability.value = GatewayApprovalModeCapability.Supported
        _serverApprovalMode.value = authoritative
        return Result.success(authoritative)
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
        settledTurnDrain = null
        unsolicitedTurnProvider = null
        coldPrewarmSessionReadyListener = null
        unmatchedTurnCompleteListener = null
        backgroundInteractionListener = null
        processEventListener = null
        sessionDirectoryInvalidationListener = null
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

        // A just-died pooled socket can poison the first WebSocket upgrade, so
        // that narrow transport failure gets one fresh-ticket retry. A ticket
        // 5xx and transport failures are transient (notably while the
        // Dashboard or Android's active network settles). Auth rejection and
        // rate limiting stay single-attempt; a bounded ticket timeout gets one
        // fresh attempt instead of leaving cold start permanently disconnected.
        var lastFailure = "gateway connect failed"
        var terminalStage: GatewayConnectFailureStage? = null
        var terminalRetryAfterCooldown = false
        for (attempt in 0 until CONNECT_ATTEMPTS) {
            try {
                connectOnce()
                connectCooldownUntil = 0L
                return
            } catch (e: GatewayConnectAttemptException) {
                lastFailure = e.message ?: lastFailure
                terminalStage = e.stage
                terminalRetryAfterCooldown = e.retryAfterCooldown
                Log.w(
                    TAG,
                    "Gateway connect attempt ${attempt + 1}/$CONNECT_ATTEMPTS failed " +
                        "(stage=${e.stage.logName}, retryable=${e.retryable}): $lastFailure",
                )
                if (!e.retryable) break
            }
        }
        when (terminalStage) {
            GatewayConnectFailureStage.TicketAuth,
            GatewayConnectFailureStage.UpgradeAuth -> onGatewaySignInRequired()
            GatewayConnectFailureStage.Ticket,
            GatewayConnectFailureStage.Upgrade -> {
                // A timeout is not a definitive availability verdict. Keep
                // the Gateway unresolved so the visible-chat owner remains
                // on this transport and can retry after the cooldown.
                if (!terminalRetryAfterCooldown) onGatewayUnreachable()
            }
            GatewayConnectFailureStage.Unsupported -> onGatewayUnsupported()
            null -> Unit
        }
        if (terminalRetryAfterCooldown && !hasEverReachedReady) {
            coldStartFailureEpisodes += 1
        }
        val coldStartBudgetExhausted = terminalRetryAfterCooldown &&
            !hasEverReachedReady &&
            coldStartFailureEpisodes >= COLD_START_FAILURE_EPISODE_LIMIT
        if (coldStartBudgetExhausted) onGatewayUnreachable()
        _reconnectDisposition.value = if (
            terminalRetryAfterCooldown && !coldStartBudgetExhausted
        ) {
            GatewayReconnectDisposition.Retryable
        } else {
            GatewayReconnectDisposition.Terminal
        }
        val backoffCeiling = if (hasEverReachedReady) {
            CONNECT_FAILURE_COOLDOWN_MS
        } else {
            (CONNECT_FAILURE_COOLDOWN_MS shl
                (coldStartFailureEpisodes - 1).coerceIn(0, 3))
                .coerceAtMost(MAX_CONNECT_FAILURE_COOLDOWN_MS)
        }
        connectCooldownUntil = maxOf(
            connectCooldownUntil,
            System.currentTimeMillis() + fullJitterDelayMs(
                backoffCeiling,
                reconnectJitterUnit(),
            ),
        )
        _connectionState.value = GatewayConnectionState.Idle
        throw GatewayPreflightException(lastFailure)
    }

    private suspend fun connectOnce() {
        val connectStart = System.nanoTime()
        _processCapability.value = GatewayProcessCapability.Unknown
        _activeSessionCapability.value = GatewayActiveSessionCapability.Unknown
        _approvalModeCapability.value = GatewayApprovalModeCapability.Unknown
        _connectionState.value = GatewayConnectionState.MintingTicket
        val ticket = dashboardClient.requestWsTicket().getOrElse { e ->
            val statusCode = (e as? DashboardHttpException)?.statusCode
            val authFailure = statusCode in setOf(401, 403)
            val rateLimited = statusCode == 429
            if (rateLimited) {
                connectCooldownUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
            }
            val transportFailure = e is java.io.IOException && e !is javax.net.ssl.SSLException
            throw GatewayConnectAttemptException(
                message = "ws-ticket mint failed: ${e.message}",
                stage = if (authFailure) {
                    GatewayConnectFailureStage.TicketAuth
                } else {
                    GatewayConnectFailureStage.Ticket
                },
                retryable = !authFailure && !rateLimited &&
                    (statusCode in 500..599 || (statusCode == null && transportFailure)),
                retryAfterCooldown = rateLimited ||
                    (!authFailure && (statusCode in 500..599 ||
                        (statusCode == null && transportFailure))),
            )
        }
        val ticketMs = (System.nanoTime() - connectStart) / 1_000_000
        val url = dashboardClient.gatewayWebSocketUrl(
            ticket = ticket.ticket,
            profile = currentSessionProfile(),
        )
            ?: throw GatewayConnectAttemptException(
                "could not build /api/ws URL",
                GatewayConnectFailureStage.Unsupported,
                retryable = false,
            )

        _connectionState.value = GatewayConnectionState.Connecting
        val ready = CompletableDeferred<Unit>()
        readySignal = ready
        val socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            createListener(ready),
        )
        webSocket = socket

        _connectionState.value = GatewayConnectionState.AwaitingReady
        val readyResult: Result<Unit>? = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            runCatching { ready.await() }
        }
        val readyFailure = readyResult?.exceptionOrNull()
            ?: if (readyResult == null) {
                GatewayConnectAttemptException(
                    "gateway.ready never arrived",
                    GatewayConnectFailureStage.Upgrade,
                    retryable = true,
                )
            } else {
                null
            }
        if (readyFailure != null) {
            socket.cancel()
            webSocket = null
            throw (readyFailure as? GatewayConnectAttemptException
                ?: GatewayConnectAttemptException(
                    "gateway connection failed: ${readyFailure.message}",
                    GatewayConnectFailureStage.Upgrade,
                    retryable = true,
                ))
        }
        // Split the cold-connect cost so a slow ticket mint (HTTP) is told
        // apart from a slow WS upgrade + gateway.ready (socket/TLS) on device.
        val wsMs = (System.nanoTime() - connectStart) / 1_000_000 - ticketMs
        Log.i(TAG, "Gateway connected (/api/ws ready) — ticket=${ticketMs}ms ws=${wsMs}ms")
        hasEverReachedReady = true
        coldStartFailureEpisodes = 0
        _reconnectDisposition.value = GatewayReconnectDisposition.None
        _connectionState.value = GatewayConnectionState.Ready
        onGatewayReady()
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
                put("source", sessionSource)
                // Android already hydrates the visible transcript through the
                // profile-scoped Dashboard REST owner. Match official Desktop's
                // bounded resume contract: register the live runtime now and let
                // Gateway hydrate model history off the RPC response path instead
                // of synchronously reading and returning the same transcript.
                // Older Gateways ignore these additive parameters.
                put("defer_history", true)
                put("omit_messages", true)
                requestedProfile?.let { put("profile", it) }
            },
        )
        val result = resumed.getOrNull()?.takeIf { response ->
            runCatching { requireConfirmedSessionProfile(response, requestedProfile) }
                .onFailure { Log.w(TAG, "Gateway prewarm rejected profile scope: ${it.message}") }
                .isSuccess
        }
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
            applySessionResultInfo(result)
        }
    }

    /**
     * Apply connection-level session info (model / provider / reasoning effort /
     * personality / yolo / fast / context usage / project) into the `_server*`
     * state flows.
     * Shared by the `session.info` event handler and the `session.resume` RPC
     * result — the resume response embeds the same `info` object, so reopening a
     * session can paint its real model up front rather than waiting for a turn.
     */
    private fun applySessionInfo(info: JsonObject) {
        val contract = (info["desktop_contract"] as? JsonPrimitive)?.intOrNull
        if (contract != null && contract < 3) {
            _approvalModeCapability.value = GatewayApprovalModeCapability.Unsupported
        }
        GatewayApprovalMode.fromWire(info.stringField("approval_mode"))?.let { mode ->
            _approvalModeCapability.value = GatewayApprovalModeCapability.Supported
            _serverApprovalMode.value = mode
        }
        if (info.containsKey("personality")) {
            _serverPersonality.value =
                (info.stringField("personality") ?: "").ifBlank { "none" }
        }
        val model = info.stringField("model")?.takeIf { it.isNotBlank() }
        val provider = info.stringField("provider")?.takeIf { it.isNotBlank() }
        if (info.containsKey("model")) {
            // session.info/session.resume is an identity snapshot. An absent
            // provider must clear the prior session's provider instead of
            // making a resumed turn look coherently bound to stale state.
            _serverModel.value = model
            _serverProvider.value = provider
            _serverModelIdentity.value = if (model != null && provider != null) {
                GatewayModelIdentity(model = model, provider = provider)
            } else {
                null
            }
        }
        // reasoning effort: ignore "" (reasoning disabled) so it can't clobber
        // the chip; display mode is config.get-only, not here.
        val reasoningEffort = info.stringField("reasoning_effort")?.takeIf { it.isNotBlank() }
        reasoningEffort?.let { _serverReasoningEffort.value = it }
        if (info.containsKey("model")) {
            _serverReasoningIdentity.value = if (
                model != null && provider != null && reasoningEffort != null
            ) GatewayReasoningIdentity(
                identity = GatewayModelIdentity(model = model, provider = provider),
                effort = reasoningEffort,
            ) else null
        }
        // credential_warning: present only when the provider key is missing/
        // invalid. ABSENT means healthy — clear to null so it self-resolves.
        _serverCredentialWarning.value =
            info.stringField("credential_warning")?.takeIf { it.isNotBlank() }
        (info["yolo"] as? JsonPrimitive)?.booleanOrNull?.let { _serverYolo.value = it }
        (info["fast"] as? JsonPrimitive)?.booleanOrNull?.let { _serverFast.value = it }
        _serverProject.value = (info["project"] as? JsonObject)?.let { project ->
            project.stringField("name")
                ?.takeIf { it.isNotBlank() }
                ?.let { name ->
                    GatewaySessionProject(
                        id = project.stringField("id")?.takeIf { it.isNotBlank() },
                        slug = project.stringField("slug")?.takeIf { it.isNotBlank() },
                        name = name,
                        primaryPath = project.stringField("primary_path")?.takeIf { it.isNotBlank() },
                    )
                }
        }
        if (info.containsKey("tools")) {
            val groups = info["tools"] as? JsonObject
            _serverTools.value = groups
                ?.values
                ?.asSequence()
                ?.mapNotNull { it as? JsonArray }
                ?.flatMap { it.asSequence() }
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet()
        }
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

    /** Apply a session create/resume result without leaking metadata from the prior session. */
    private fun applySessionResultInfo(result: JsonObject) {
        _serverProject.value = null
        _serverTools.value = null
        (result["info"] as? JsonObject)?.let { applySessionInfo(it) }
    }

    /**
     * A named profile is safe to bind only when Hermes echoes that exact owner
     * in the authoritative session result. Current multiplex gateways report
     * `info.profile_name` on create/resume. Missing metadata means an older
     * gateway may have ignored `params.profile`; a different name means the
     * requested profile disappeared or resolved to the launch profile.
     */
    private fun requireConfirmedSessionProfile(result: JsonObject, requestedProfile: String?) {
        val expected = requestedProfile?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val actual = (result["info"] as? JsonObject)
            ?.stringField("profile_name")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (actual != expected) {
            val detail = actual?.let { "Hermes returned profile '$it'" }
                ?: "Hermes did not confirm profile ownership"
            throw GatewayPreflightException(
                "$detail; refusing to use it as selected profile '$expected'",
            )
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

    private fun parseGatewayActiveSession(
        element: kotlinx.serialization.json.JsonElement,
    ): GatewayActiveSession {
        val row = element as? JsonObject
            ?: throw GatewayRpcException("session.active_list returned a non-object row")
        val runtimeId = row.stringField("id")?.takeIf(String::isNotBlank)
            ?: throw GatewayRpcException("session.active_list row returned no runtime id")
        val storedId = row.stringField("session_key")?.takeIf(String::isNotBlank)
            ?: throw GatewayRpcException("session.active_list row returned no session key")
        val status = GatewayActiveSessionStatus.fromWire(row.stringField("status"))
            ?: throw GatewayRpcException("session.active_list row returned an unknown status")
        val lastActive = (row["last_active"] as? JsonPrimitive)?.doubleOrNull
            ?: throw GatewayRpcException("session.active_list row returned no last_active")
        return GatewayActiveSession(
            runtimeSessionId = runtimeId,
            storedSessionId = storedId,
            status = status,
            lastActiveEpochSeconds = lastActive,
            profile = row.stringField("profile")?.trim()?.takeIf(String::isNotEmpty),
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
            settledTurnDrain = null
        }
        if (
            liveSessionId != null &&
            storedSessionId == requestedStoredId &&
            requestedStoredId != null &&
            liveSessionProfile == requestedProfile
        ) {
            awaitSessionReadyIfRequired(liveSessionId!!)
            return
        }

        if (requestedStoredId != null) {
            val resumed = rpc(
                "session.resume",
                buildJsonObject {
                    put("session_id", requestedStoredId)
                    put("cols", DEFAULT_COLS)
                    put("source", sessionSource)
                    requestedProfile?.let { put("profile", it) }
                },
            )
            val result = resumed.getOrNull()
            val resumeError = resumed.exceptionOrNull()
            val live = result?.stringField("session_id")
            if (live != null) {
                try {
                    requireConfirmedSessionProfile(result, requestedProfile)
                } catch (error: GatewayPreflightException) {
                    throw GatewayAuthoritativeResumeException(
                        error.message ?: "Hermes resumed this session in a different profile",
                    )
                }
                liveSessionId = live
                storedSessionId = requestedStoredId
                liveSessionProfile = requestedProfile
                updateCancelledDrainLiveSession(requestedStoredId, live)
                applySessionResultInfo(result)
                awaitSessionReadyIfRequired(live, result)
                return
            }
            throw GatewayAuthoritativeResumeException(
                resumeError?.message ?: "Hermes could not resume this session",
            )
        }

        val created = rpc(
            "session.create",
            buildJsonObject {
                put("cols", DEFAULT_COLS)
                put("source", sessionSource)
                if (!newSessionTitle.isNullOrBlank()) put("title", newSessionTitle)
                requestedProfile?.let { put("profile", it) }
                // Only non-model draft settings may ride session.create. Model
                // and provider always pass through confirmation-aware config.set
                // before they become active.
                currentSessionModel()?.let { sm ->
                    sm.reasoningEffort?.takeIf { it.isNotBlank() }?.let { put("reasoning_effort", it) }
                    sm.fast?.let { put("fast", it) }
                }
            },
        ).getOrElse { e ->
            throw GatewayPreflightException("session.create failed: ${e.message}")
        }
        val live = created.stringField("session_id")
            ?: throw GatewayPreflightException("session.create returned no session_id")
        requireConfirmedSessionProfile(created, requestedProfile)
        val stored = created.stringField("stored_session_id") ?: live
        liveSessionId = live
        storedSessionId = stored
        liveSessionProfile = requestedProfile
        if (cancelledTurnDrain?.storedSessionId != stored) cancelledTurnDrain = null
        if (settledTurnDrain?.storedSessionId != stored) settledTurnDrain = null
        turn.callbacks.onSessionId(stored)
        awaitSessionReadyIfRequired(live, created)
    }

    /**
     * Wait for current upstream's authoritative deferred-build edge instead of
     * racing a lazy session into compute-host turn isolation. Without this
     * barrier, the parent runtime can claim the durable lease before the child
     * rechecks it, causing the child to reject itself as a second live owner.
     */
    private suspend fun awaitSessionReadyIfRequired(
        liveId: String,
        sessionResult: JsonObject? = null,
    ) {
        val lazy = (sessionResult?.get("info") as? JsonObject)?.booleanField("lazy") == true
        if (lazy) lazyLiveSessions += liveId
        if (liveId !in lazyLiveSessions) return
        if (readyLiveSessions.remove(liveId)) {
            lazyLiveSessions.remove(liveId)
            return
        }
        val waiter = sessionReadyWaiters.computeIfAbsent(liveId) { CompletableDeferred() }
        if (readyLiveSessions.remove(liveId)) waiter.complete(Unit)
        try {
            withTimeout(sessionReadyTimeoutMs) { waiter.await() }
            lazyLiveSessions.remove(liveId)
        } catch (error: Exception) {
            throw GatewayPreflightException(
                error.message ?: "Hermes session initialization timed out",
            )
        } finally {
            sessionReadyWaiters.remove(liveId, waiter)
        }
    }

    private fun failSessionReadyWaiters(message: String) {
        sessionReadyWaiters.values.forEach {
            it.completeExceptionally(GatewayRpcException(message))
        }
        sessionReadyWaiters.clear()
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
                val wasReady = _connectionState.value == GatewayConnectionState.Ready
                val closeFailure = if (!wasReady) preReadyCloseFailure(code, reason) else null
                if (!ready.isCompleted) {
                    ready.completeExceptionally(closeFailure!!)
                }
                if (wasReady && code == 4401) onGatewaySignInRequired()
                if (wasReady && code == 4403) onGatewayUnreachable()
                onSocketDown(
                    "closing: $code $reason",
                    disposition = when {
                        wasReady && code !in setOf(4401, 4403) ->
                            GatewayReconnectDisposition.Retryable
                        closeFailure?.retryAfterCooldown == true ->
                            GatewayReconnectDisposition.Retryable
                        else -> GatewayReconnectDisposition.Terminal
                    },
                )
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (this@GatewayChatClient.webSocket === webSocket) {
                val wasReady = _connectionState.value == GatewayConnectionState.Ready
                val closeFailure = if (!wasReady) preReadyCloseFailure(code, reason) else null
                if (!ready.isCompleted) {
                    ready.completeExceptionally(closeFailure!!)
                }
                if (wasReady && code == 4401) onGatewaySignInRequired()
                if (wasReady && code == 4403) onGatewayUnreachable()
                onSocketDown(
                    "closed: $code $reason",
                    disposition = when {
                        wasReady && code !in setOf(4401, 4403) ->
                            GatewayReconnectDisposition.Retryable
                        closeFailure?.retryAfterCooldown == true ->
                            GatewayReconnectDisposition.Retryable
                        else -> GatewayReconnectDisposition.Terminal
                    },
                )
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (this@GatewayChatClient.webSocket !== webSocket) return
            val connectFailure = when (response?.code) {
                404 -> {
                    // No /api/ws on this build (or embedded chat disabled) —
                    // sticky downgrade so auto-resolution stops picking gateway.
                    Log.w(TAG, "Gateway WS upgrade rejected (${response.code}) — marking unsupported")
                    onGatewayUnsupported()
                    GatewayConnectAttemptException(
                        "gateway websocket is unsupported",
                        GatewayConnectFailureStage.Unsupported,
                        retryable = false,
                    )
                }
                401, 403 -> GatewayConnectAttemptException(
                    "gateway websocket authentication was rejected",
                    GatewayConnectFailureStage.UpgradeAuth,
                    retryable = false,
                )
                429 -> {
                    connectCooldownUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                    GatewayConnectAttemptException(
                        "gateway websocket rate limited",
                        GatewayConnectFailureStage.Upgrade,
                        retryable = false,
                        retryAfterCooldown = true,
                    )
                }
                else -> GatewayConnectAttemptException(
                    "gateway websocket upgrade failed: ${t.message}",
                    GatewayConnectFailureStage.Upgrade,
                    retryable = true,
                )
            }
            if (!ready.isCompleted) ready.completeExceptionally(connectFailure)
            onSocketDown(
                "failure: ${t.message}",
                disposition = if (connectFailure.retryAfterCooldown) {
                    GatewayReconnectDisposition.Retryable
                } else {
                    GatewayReconnectDisposition.Terminal
                },
            )
        }
    }

    private fun preReadyCloseFailure(code: Int, reason: String): GatewayConnectAttemptException {
        val safeReason = reason.take(160).ifBlank { "closed before gateway.ready" }
        return when (code) {
            4401 -> GatewayConnectAttemptException(
                "gateway authentication was rejected ($safeReason)",
                GatewayConnectFailureStage.UpgradeAuth,
                retryable = false,
            )
            4403 -> GatewayConnectAttemptException(
                "gateway origin or access guard rejected the connection ($safeReason)",
                GatewayConnectFailureStage.Upgrade,
                retryable = false,
            )
            else -> GatewayConnectAttemptException(
                "gateway closed before ready (code=$code, $safeReason)",
                GatewayConnectFailureStage.Upgrade,
                retryable = code in setOf(1001, 1011, 1012, 1013),
            )
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

        if (type == "sessions.changed") {
            callbackDispatcher { sessionDirectoryInvalidationListener?.invoke() }
            return
        }

        // Lazy create/resume returns before its AIAgent exists. The deferred
        // build publishes exact-session session.info when it is ready. Record
        // that edge before live-session routing so a fast build cannot race
        // the RPC response and strand the first submit.
        if (type == "session.info" && !eventSessionId.isNullOrBlank() &&
            payload?.booleanField("lazy") != true
        ) {
            readyLiveSessions += eventSessionId
            sessionReadyWaiters.remove(eventSessionId)?.complete(Unit)
        }

        // Upstream emits session.reclaimed process-wide, so it is identified
        // by payload rather than params.session_id. Retire only an exact live
        // runtime we own; preserve the durable id so the next send resumes it.
        if (type == "session.reclaimed") {
            val reclaimedLiveId = payload?.stringField("session_id")
            val reclaimedStoredId = payload?.stringField("stored_session_id")
            val reason = payload?.stringField("reason")
            val supportedReason = reason in setOf("idle_timeout", "lru_evict", "ws_orphan_reap")
            if (!reclaimedLiveId.isNullOrBlank() && supportedReason) {
                childWatches.remove(reclaimedLiveId)?.let { registration ->
                    notifyChildWatchFailure(registration, "Gateway reclaimed the child watch")
                }
                val background = backgroundTurns.remove(reclaimedLiveId)
                if (background != null) {
                    callbackDispatcher {
                        unmatchedTurnCompleteListener?.invoke(
                            GatewayBackgroundTurnCompletion(
                                storedSessionId = reclaimedStoredId?.takeIf(String::isNotBlank)
                                    ?: background.storedSessionId,
                                liveSessionId = reclaimedLiveId,
                                profile = background.profile,
                                expectedAssistantText = null,
                            ),
                        )
                    }
                }
                if (reclaimedLiveId == liveSessionId) {
                    liveSessionId = null
                    reclaimedStoredId?.takeIf(String::isNotBlank)?.let { storedSessionId = it }
                    val turn = activeTurn
                    if (turn != null && !turn.ended) {
                        activeTurn = null
                        turn.failFromTransport("Gateway reclaimed the inactive session")
                    }
                }
            }
            return
        }

        // read_terminal is a renderer query, not a user decision. Android has
        // no xterm pane on the Gateway chat surface, so mirror upstream
        // desktop's no-live-pane behavior and answer with empty text instead
        // of blocking the agent for the server's 30-second timeout.
        if (type == "terminal.read.request") {
            val requestId = payload?.stringField("request_id")
            val ownedSession = !eventSessionId.isNullOrBlank() &&
                (eventSessionId == liveSessionId || backgroundTurns.containsKey(eventSessionId))
            if (!requestId.isNullOrBlank() && ownedSession) {
                scope.launch {
                    rpc(
                        "terminal.read.respond",
                        buildJsonObject {
                            put("request_id", requestId)
                            put("text", "")
                        },
                    ).onFailure {
                        Log.w(TAG, "terminal.read.respond failed: ${it.message}")
                    }
                }
            }
            return
        }

        // A lazy child watcher is a second session on this shared socket. Route
        // it before the main-session recovery/foreign-session gates and require
        // the exact live id returned by its own session.resume acknowledgement.
        val childWatch = eventSessionId?.let(childWatches::get)
        if (childWatch != null) {
            if (childWatches[eventSessionId] === childWatch) {
                childWatch.mapper.onEvent(type, payload)
            }
            return
        }

        val capturedForPendingChildWatch = !eventSessionId.isNullOrBlank() &&
            eventSessionId != liveSessionId &&
            !backgroundTurns.containsKey(eventSessionId) &&
            capturePendingChildWatchEvent(ChildWatchEvent(eventSessionId, type, payload))
        if (capturedForPendingChildWatch) return

        // A cold session.resume may schedule auto-continue before its RPC
        // response reaches Android. The recovery buffer is an ownership gate,
        // not an observational copy: an event is either claimed here for
        // replay or routed live below, never both. The resume response drains
        // and closes the gate under this same lock, so later frames route live.
        // Already-owned sibling sessions retain their background routing.
        val claimedByRecovery = !eventSessionId.isNullOrBlank() &&
            !backgroundTurns.containsKey(eventSessionId) &&
            synchronized(recoveryEventLock) {
                recoveryEvents?.let { buffered ->
                    if (buffered.size >= MAX_RECOVERY_BUFFERED_EVENTS) {
                        buffered.removeAt(0)
                    }
                    buffered += RecoveryEvent(eventSessionId, type, payload)
                    true
                } ?: false
            }
        if (claimedByRecovery) return

        // A profile/session switch may leave an upstream turn running while a
        // different profile becomes visible. Its events must never paint the
        // new transcript, but the terminal event still needs to reconcile the
        // original durable session so the answer is waiting when the user
        // switches back.
        val backgroundTurn = eventSessionId?.let(backgroundTurns::get)
        if (backgroundTurn != null) {
            val interactionRequest = GatewayEventMapper.interactionRequest(type, payload)
            if (interactionRequest != null) {
                val previous = backgroundTurn.pendingAsk
                backgroundTurn.pendingAsk = interactionRequest
                if (previous?.kind != interactionRequest.kind ||
                    previous.requestId != interactionRequest.requestId
                ) {
                    callbackDispatcher {
                        backgroundInteractionListener?.invoke(
                            GatewayBackgroundInteractionEvent.Requested(
                                storedSessionId = backgroundTurn.storedSessionId,
                                profile = backgroundTurn.profile,
                                ask = interactionRequest,
                            ),
                        )
                    }
                }
                return
            }

            val expiry = GatewayEventMapper.interactionExpiry(type, payload)
            val pendingAsk = backgroundTurn.pendingAsk
            val explicitlyExpired = expiry != null && pendingAsk != null &&
                pendingAsk.kind == expiry.kind &&
                (pendingAsk.kind == GatewayAsk.Kind.APPROVAL ||
                    pendingAsk.requestId == expiry.requestId)
            // Ordinary turn activity is not a decision acknowledgement. It can
            // be replayed or buffered. Only an authoritative expiry retires a
            // detached ask; an explicit response is retired by its foreground VM.
            if (explicitlyExpired) {
                backgroundTurn.pendingAsk = null
                callbackDispatcher {
                    backgroundInteractionListener?.invoke(
                        GatewayBackgroundInteractionEvent.Expired(
                            storedSessionId = backgroundTurn.storedSessionId,
                            profile = backgroundTurn.profile,
                            ask = pendingAsk,
                        ),
                    )
                }
            }

            if (type == "message.complete" || type == "error") {
                backgroundTurns.remove(eventSessionId, backgroundTurn)
                val expectedText = if (type == "message.complete") payload?.stringField("text") else null
                callbackDispatcher {
                    unmatchedTurnCompleteListener?.invoke(
                        GatewayBackgroundTurnCompletion(
                            storedSessionId = backgroundTurn.storedSessionId,
                            liveSessionId = eventSessionId,
                            profile = backgroundTurn.profile,
                            expectedAssistantText = expectedText,
                        ),
                    )
                }
                if (!AppForegroundTracker.isForeground.value) scheduleBackgroundClose()
            }
            return
        }

        // `session.info` is connection-level (personality / model / context
        // usage), emitted on a config change even with no turn in flight. Capture
        // the active personality here — for our own session only — so a
        // `/personality`, desktop, or TUI change keeps the app in sync. Falls
        // through to the turn dispatch below so an in-flight turn still sees it.
        if (type == "session.info" &&
            (eventSessionId == null || eventSessionId == liveSessionId)
        ) {
            // Connection-level session info (model / provider / effort / persona /
            // yolo / fast / usage) — shared with the session.resume result via
            // applySessionInfo so both paths stay in lockstep.
            payload?.let { applySessionInfo(it) }
            val ownedTurn = activeTurn
            if (!eventSessionId.isNullOrBlank() &&
                eventSessionId == liveSessionId &&
                ownedTurn?.settleFromAuthoritativeSessionState(
                    running = payload?.booleanField("running"),
                    source = "session.info",
                ) == true
            ) {
                if (activeTurn === ownedTurn) activeTurn = null
                if (!AppForegroundTracker.isForeground.value) scheduleBackgroundClose()
                return
            }
        }

        // Foreign-session events (another client's chat on the same gateway) are not ours.
        if (eventSessionId != null && liveSessionId != null && eventSessionId != liveSessionId) {
            return
        }
        dispatchProcessEvent(type, payload, eventSessionId)
        if (consumeCancelledTurnEvent(type, eventSessionId)) return
        if (consumeSettledTurnTerminal(type, eventSessionId)) return
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
                    unmatchedTurnCompleteListener?.invoke(
                        GatewayBackgroundTurnCompletion(
                            storedSessionId = storedId,
                            liveSessionId = eventSessionId,
                            profile = liveSessionProfile,
                            expectedAssistantText = expectedText,
                        ),
                    )
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

    private fun onSocketDown(
        reason: String,
        disposition: GatewayReconnectDisposition = GatewayReconnectDisposition.Retryable,
    ) {
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
        _activeSessionCapability.value = GatewayActiveSessionCapability.Unknown
        _approvalModeCapability.value = GatewayApprovalModeCapability.Unknown
        _reconnectDisposition.value = disposition
        _connectionState.value = GatewayConnectionState.Idle
        pendingRpcs.values.forEach {
            it.completeExceptionally(GatewayRpcException("gateway connection lost"))
        }
        pendingRpcs.clear()
        failSessionReadyWaiters("gateway connection lost")
        lazyLiveSessions.clear()
        readyLiveSessions.clear()
        failChildWatches("Child watch disconnected from the gateway")
        val turn = activeTurn
        if (turn == null) {
            if (backgroundTurns.isNotEmpty() && !backgroundRejoinInProgress) {
                backgroundRejoinInProgress = true
                scope.launch {
                    try {
                        attemptBackgroundTurnRejoin()
                    } finally {
                        backgroundRejoinInProgress = false
                    }
                }
            }
            return
        }
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

    @Volatile
    private var backgroundRejoinInProgress = false

    /** Keep the shared event socket attached while detached sibling turns run. */
    private suspend fun attemptBackgroundTurnRejoin() {
        val deadline = System.currentTimeMillis() + midTurnRejoinWindowMs
        var backoffMs = 500L
        while (backgroundTurns.isNotEmpty() && System.currentTimeMillis() < deadline) {
            val reconnected = try {
                connectMutex.withLock {
                    connectCooldownUntil = 0L
                    ensureConnected()
                }
                true
            } catch (e: Exception) {
                Log.d(TAG, "Background-turn reconnect retry failed: ${e.message}")
                false
            }
            if (reconnected) {
                Log.i(TAG, "Gateway socket rejoined for ${backgroundTurns.size} detached turn(s)")
                return
            }
            delay(fullJitterDelayMs(backoffMs, reconnectJitterUnit()))
            backoffMs = (backoffMs * 2).coerceAtMost(5_000L)
        }
    }

    /**
     * Recover an in-flight turn after a mid-turn socket loss by reconnecting
     * the socket and rebinding [preservedLiveId] to the new transport.
     *
     * Current upstream detaches a live session from a closed WebSocket; a new
     * socket receives no tail until `session.activate` attaches that exact live
     * id. Older gateways do not expose the method, so method-not-found alone
     * retains the legacy bare-socket behavior. We never call `session.resume`
     * here: resuming a durable id can create a different live runtime and
     * orphan the turn already executing server-side.
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
                if (preservedLiveId == null) {
                    true
                } else {
                    // Bind before activation so a tail event racing the RPC ack
                    // still matches the original active turn.
                    liveSessionId = preservedLiveId
                    val activated = rpc(
                        "session.activate",
                        buildJsonObject { put("session_id", preservedLiveId) },
                    )
                    when {
                        activated.isSuccess -> {
                            activated.getOrNull()?.let { result ->
                                applySessionResultInfo(result)
                                turn.settleFromAuthoritativeSessionState(
                                    running = result.booleanField("running"),
                                    source = "session.activate",
                                )
                            }
                            true
                        }
                        activated.exceptionOrNull().isMethodNotFound() -> {
                            Log.i(
                                TAG,
                                "session.activate unsupported during mid-turn rejoin — " +
                                    "using legacy socket recovery",
                            )
                            true
                        }
                        else -> {
                            Log.d(
                                TAG,
                                "Mid-turn session.activate retry failed: " +
                                    activated.exceptionOrNull()?.message,
                            )
                            false
                        }
                    }
                }
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
                    "Gateway socket rejoined mid-turn (session=$storedSessionId) — " +
                        "rebound live session, awaiting tail",
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
            delay(fullJitterDelayMs(backoffMs, reconnectJitterUnit()))
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
        failSessionReadyWaiters("gateway socket closed")
        lazyLiveSessions.clear()
        readyLiveSessions.clear()
        attachMethodForSocket = null
        commandsCatalogCache = null
        _processCapability.value = GatewayProcessCapability.Unknown
        _activeSessionCapability.value = GatewayActiveSessionCapability.Unknown
        _approvalModeCapability.value = GatewayApprovalModeCapability.Unknown
        _reconnectDisposition.value = GatewayReconnectDisposition.None
        _connectionState.value = GatewayConnectionState.Idle
        failChildWatches("Child watch closed with the gateway socket")
    }

    private fun scheduleBackgroundClose() {
        // Opt-in keep-alive: the foreground service holds the process up, so
        // never tear the socket down on background while it's on.
        if (keepAliveInBackground) return
        backgroundCloseJob?.cancel()
        backgroundCloseJob = scope.launch {
            delay(BACKGROUND_CLOSE_GRACE_MS)
            if (activeTurn == null && backgroundTurns.isEmpty() && childWatches.isEmpty() &&
                !AppForegroundTracker.isForeground.value
            ) {
                closeSocket("app backgrounded")
            }
        }
    }

    private fun failChildWatches(message: String) {
        if (childWatches.isEmpty()) return
        val registrations = childWatches.values.toSet()
        childWatches.clear()
        registrations.forEach { notifyChildWatchFailure(it, message) }
    }

    private fun notifyChildWatchFailure(
        registration: ChildWatchRegistration,
        message: String,
    ) {
        callbackDispatcher { registration.callbacks.onResumeFailure(message) }
    }

    private fun capturePendingChildWatchEvent(event: ChildWatchEvent): Boolean {
        var captured = false
        pendingChildWatchOpens.values.forEach { pending ->
            if (pending.capture(event)) captured = true
        }
        return captured
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
        val size = maxOf(
            attachment.sizeBytes ?: 0L,
            decodedBase64Size(attachment.base64),
        )
        val limit = when {
            mime.startsWith("image/") -> ATTACH_IMAGE_MAX_BYTES
            mime == "application/pdf" -> ATTACH_PDF_MAX_BYTES
            else -> ATTACH_FILE_MAX_BYTES
        }
        if (size <= 0L) {
            return Result.failure(GatewayRpcException("attachment is empty"))
        }
        if (size > limit) {
            return Result.failure(
                GatewayRpcException(
                    "attachment is too large (${size / (1024 * 1024)} MB; max ${limit / (1024 * 1024)} MB)",
                ),
            )
        }
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

    private suspend fun cleanupStagedAttachments(paths: List<String>) {
        val sid = liveSessionId ?: return
        paths.asReversed().distinct().forEach { path ->
            val removed = rpc(
                "image.detach",
                buildJsonObject {
                    put("session_id", sid)
                    put("path", path)
                },
                timeoutMs = ATTACH_RPC_TIMEOUT_MS,
            )
            if (removed.isFailure) {
                Log.w(TAG, "Could not clean up staged attachment: ${removed.exceptionOrNull()?.message}")
            }
        }
    }

    private fun GatewayAttachment.requiresPromptReference(): Boolean {
        val mime = contentType.substringBefore(';').trim().lowercase()
        return !mime.startsWith("image/") && mime != "application/pdf"
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
    private fun watchdogTimeoutFor(eventType: String, payload: JsonObject? = null): Long = when {
        eventType == "clarify.request" || eventType == "secret.request" -> ASK_CLARIFY_SECRET_TIMEOUT_MS
        eventType == "sudo.request" -> ASK_SUDO_TIMEOUT_MS
        eventType == "approval.request" -> ASK_UNBOUNDED_TIMEOUT_MS
        eventType == "status.update" &&
            payload?.stringField("kind") == "compacting" -> compactingTimeoutMs
        else -> turnIdleTimeoutMs
    }

    private inner class GatewayTurn(
        val callbacks: GatewayTurnCallbacks,
        dedupeAdjacentMessageStarts: Boolean = false,
        deferEvents: Boolean = false,
        private val androidOwned: Boolean = false,
        private val onTransportAccepted: () -> Unit = { },
    ) : ActiveTurnHandle {
        private val mapper = GatewayEventMapper(callbacks, dedupeAdjacentMessageStarts)
        val pendingInteraction: GatewayAsk?
            get() = mapper.currentInteraction
        fun restoreInteraction(ask: GatewayAsk) {
            mapper.restoreInteraction(ask)
        }
        fun acknowledgeInteraction(expiry: GatewayAskExpiry) {
            mapper.acknowledgeInteraction(expiry)
        }
        private val deferredEventLock = Any()
        private val deferredEvents = mutableListOf<Pair<String, JsonObject?>>()
        private var eventsDeferred = deferEvents
        private var redirectedTo: GatewayTurn? = null
        private var queuedSuccessor: Pair<GatewayInboundTurnRegistration, GatewayTurn>? = null

        /** t0 = construction ≈ sendTurn entry (the moment the user sent). */
        val tracer = TurnLatencyTracer("gateway")

        @Volatile
        var cancelled = false
            private set

        private val rejoinAttempts = java.util.concurrent.atomic.AtomicInteger(0)
        private val transportAccepted = AtomicBoolean(false)
        private val progressGeneration = java.util.concurrent.atomic.AtomicLong(0L)

        fun markTransportAccepted() {
            if (transportAccepted.compareAndSet(false, true)) {
                callbackDispatcher(onTransportAccepted)
            }
        }

        @Volatile
        private var reconcileRequired = false

        /**
         * A replacement WebSocket does not replay a `message.complete` frame
         * emitted while the old socket was detached. This is distinct from
         * cancellation: the server finished the turn and authoritative history
         * must settle it without routing through a transport error.
         */
        @Volatile
        private var settledWithoutTerminalFrame = false

        /**
         * True if this socket loss should be answered with a rejoin attempt.
         * Mark reconciliation before reconnecting so a terminal event arriving
         * immediately after `gateway.ready` cannot race ahead of the signal.
         */
        fun beginRejoin(): Boolean {
            val shouldRejoin = !ended && rejoinAttempts.incrementAndGet() <= MAX_TURN_REJOINS
            if (shouldRejoin) {
                reconcileRequired = true
                transportRecoveryStarted = true
            }
            return shouldRejoin
        }

        /**
         * A socket loss after `prompt.submit` makes server acceptance ambiguous
         * even when no turn event or RPC ack reached Android. Once recovery has
         * started, the caller must not resubmit through SSE.
         */
        @Volatile
        var transportRecoveryStarted = false
            private set

        private var watchdog: Job? = null

        val ended: Boolean get() = mapper.turnEnded || cancelled || settledWithoutTerminalFrame

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
            val redirect = synchronized(deferredEventLock) {
                if (eventsDeferred) {
                    deferredEvents += type to payload
                    return
                }
                redirectedTo
            }
            if (redirect != null) {
                redirect.onEvent(type, payload)
                return
            }
            processEvent(type, payload)
        }

        private fun processEvent(type: String, payload: JsonObject?) {
            if (settledWithoutTerminalFrame) return
            if (type != "session.info") {
                started = true
                progressGeneration.incrementAndGet()
                markTransportAccepted()
            }
            tracer.mark("ttfe")
            if (type == "message.delta" || type == "reasoning.delta" || type == "thinking.delta") {
                tracer.mark("ttft")
            }
            // Reset on every event — long tool runs keep the turn alive.
            // Ask requests block with no further events, so they arm with
            // their own (longer) duration via watchdogTimeoutFor.
            armWatchdog(watchdogTimeoutFor(type, payload))
            // Queue this immediately before the terminal callbacks. Both are
            // marshalled through the same dispatcher, preserving callback order
            // even when the WebSocket reader and reconnect coroutine differ.
            if (type == "message.complete" && reconcileRequired) {
                callbacks.onReconcileRequired()
            }
            mapper.onEvent(type, payload)
            if (mapper.turnEnded) {
                disarmWatchdog()
                tracer.done()
                handoffQueuedSuccessor()
            }
        }

        /**
         * Use upstream's session state as a terminal backstop only after this
         * exact turn has proved it went live. A pre-start `running=false`
         * heartbeat can race `prompt.submit` and is not a completion boundary.
         */
        fun captureLivenessGeneration(): Long? =
            if (androidOwned && started && !ended) progressGeneration.get() else null

        fun settleFromAuthoritativeSessionState(
            running: Boolean?,
            source: String,
            expectedProgressGeneration: Long? = null,
        ): Boolean {
            if (running != false || !started) return false
            val settled = synchronized(deferredEventLock) {
                if (ended ||
                    (expectedProgressGeneration != null &&
                        progressGeneration.get() != expectedProgressGeneration)
                ) {
                    false
                } else {
                    settledWithoutTerminalFrame = true
                    reconcileRequired = true
                    true
                }
            }
            if (!settled) return false

            disarmWatchdog()
            armSettledTurnDrain()
            Log.i(TAG, "Gateway turn settled from $source after missing terminal frame")
            callbacks.onReconcileRequired()
            callbacks.onComplete()
            tracer.done("history-reconcile")
            handoffQueuedSuccessor()
            return true
        }

        /**
         * Preserve a queued prompt reported beside an in-flight recovery as a
         * distinct next turn. Its mapper starts deferred so events that race
         * the resume acknowledgement cannot paint the completing prior turn.
         */
        fun installQueuedSuccessor(registration: GatewayInboundTurnRegistration) {
            synchronized(deferredEventLock) {
                if (queuedSuccessor == null) {
                    queuedSuccessor = registration to GatewayTurn(
                        callbacks = dispatchOn(registration.callbacks),
                        dedupeAdjacentMessageStarts = true,
                        deferEvents = true,
                        androidOwned = true,
                    )
                }
            }
        }

        private fun handoffQueuedSuccessor() {
            val successor = synchronized(deferredEventLock) {
                queuedSuccessor?.also {
                    queuedSuccessor = null
                    redirectedTo = it.second
                }
            } ?: return
            val (registration, turn) = successor

            // Claim socket ownership immediately so the next message.start is
            // buffered by this exact successor instead of being admitted as a
            // generic unsolicited turn. UI admission is ordered after the
            // prior turn's terminal callbacks on the shared dispatcher.
            activeTurn = turn
            callbackDispatcher {
                if (registration.onHandle(turn)) {
                    turn.releaseDeferredEvents()
                    turn.armWatchdog()
                } else {
                    turn.discardDeferredEvents()
                    turn.detach()
                }
            }
        }

        fun releaseDeferredEvents() {
            val pending = synchronized(deferredEventLock) {
                eventsDeferred = false
                deferredEvents.toList().also { deferredEvents.clear() }
            }
            pending.forEach { (type, payload) -> onEvent(type, payload) }
        }

        fun redirectDeferredEventsTo(target: GatewayTurn) {
            val pending = synchronized(deferredEventLock) {
                eventsDeferred = false
                redirectedTo = target
                deferredEvents.toList().also { deferredEvents.clear() }
            }
            pending.forEach { (type, payload) -> target.onEvent(type, payload) }
        }

        fun discardDeferredEvents() {
            synchronized(deferredEventLock) {
                eventsDeferred = false
                redirectedTo = null
                deferredEvents.clear()
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

    private fun armSettledTurnDrain() {
        val storedId = storedSessionId ?: return
        val liveId = liveSessionId ?: return
        settledTurnDrain = SettledTurnDrain(storedId, liveId)
    }

    /** Consume one late terminal from a turn already settled by session state. */
    private fun consumeSettledTurnTerminal(type: String, eventSessionId: String?): Boolean {
        val drain = settledTurnDrain ?: return false
        if (eventSessionId != drain.liveSessionId) return false
        if (type == "message.start") {
            if (settledTurnDrain === drain) settledTurnDrain = null
            return false
        }
        if (type != "message.complete" && type != "error") return false
        if (settledTurnDrain === drain) settledTurnDrain = null
        Log.d(TAG, "Ignored late terminal for gateway turn settled from session state")
        return true
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
    private fun dispatchOn(
        callbacks: GatewayTurnCallbacks,
        stillCurrent: () -> Boolean = { true },
    ) = GatewayTurnCallbacks(
        onSessionId = { v -> dispatchIfCurrent(stillCurrent) { callbacks.onSessionId(v) } },
        onStart = { dispatchIfCurrent(stillCurrent) { callbacks.onStart() } },
        onTextDelta = { v -> dispatchIfCurrent(stillCurrent) { callbacks.onTextDelta(v) } },
        onInterimMessage = { text, alreadyStreamed ->
            dispatchIfCurrent(stillCurrent) { callbacks.onInterimMessage(text, alreadyStreamed) }
        },
        onInterimReconciled = { text ->
            dispatchIfCurrent(stillCurrent) { callbacks.onInterimReconciled(text) }
        },
        onThinkingDelta = { v -> dispatchIfCurrent(stillCurrent) { callbacks.onThinkingDelta(v) } },
        onToolCallStart = { id, name, args ->
            dispatchIfCurrent(stillCurrent) { callbacks.onToolCallStart(id, name, args) }
        },
        onToolCallDone = { a, b -> dispatchIfCurrent(stillCurrent) { callbacks.onToolCallDone(a, b) } },
        onToolCallFailed = { a, b -> dispatchIfCurrent(stillCurrent) { callbacks.onToolCallFailed(a, b) } },
        onToolOutputRisk = { v -> dispatchIfCurrent(stillCurrent) { callbacks.onToolOutputRisk(v) } },
        onTurnComplete = { dispatchIfCurrent(stillCurrent) { callbacks.onTurnComplete() } },
        onReconcileRequired = { dispatchIfCurrent(stillCurrent) { callbacks.onReconcileRequired() } },
        onComplete = { dispatchIfCurrent(stillCurrent) { callbacks.onComplete() } },
        onUsage = { v -> dispatchIfCurrent(stillCurrent) { callbacks.onUsage(v) } },
        onError = { v -> dispatchIfCurrent(stillCurrent) { callbacks.onError(v) } },
        onToolGenerating = { v -> dispatchIfCurrent(stillCurrent) { callbacks.onToolGenerating(v) } },
        onSubagentEvent = { v -> dispatchIfCurrent(stillCurrent) { callbacks.onSubagentEvent(v) } },
        onMoaReference = { v -> dispatchIfCurrent(stillCurrent) { callbacks.onMoaReference(v) } },
        onInteractionRequest = { v -> dispatchIfCurrent(stillCurrent) { callbacks.onInteractionRequest(v) } },
        onInteractionExpired = { v -> dispatchIfCurrent(stillCurrent) { callbacks.onInteractionExpired(v) } },
        onResumeFailure = { v -> dispatchIfCurrent(stillCurrent) { callbacks.onResumeFailure(v) } },
        onFailure = { v -> dispatchIfCurrent(stillCurrent) { callbacks.onFailure(v) } },
        // MUST be wrapped like every other member: GatewayTurnCallbacks gives
        // onStatusUpdate a default no-op, so omitting it here silently swallows
        // EVERY gateway status line — the ❌ terminal-error lifecycle update
        // included. Without it markError never fires, the turn isn't badged
        // "Error", and onComplete's history reload wipes the error bubble (the
        // "reply appears then vanishes" bug).
        onStatusUpdate = { kind, text ->
            dispatchIfCurrent(stillCurrent) { callbacks.onStatusUpdate(kind, text) }
        },
        onStatusClear = { kind -> dispatchIfCurrent(stillCurrent) { callbacks.onStatusClear(kind) } },
        onNoticeShow = { notice -> dispatchIfCurrent(stillCurrent) { callbacks.onNoticeShow(notice) } },
        onNoticeClear = { key -> dispatchIfCurrent(stillCurrent) { callbacks.onNoticeClear(key) } },
        onSubmitRejected = { message ->
            dispatchIfCurrent(stillCurrent) { callbacks.onSubmitRejected(message) }
        },
    )

    private fun dispatchIfCurrent(stillCurrent: () -> Boolean, callback: () -> Unit) {
        callbackDispatcher {
            if (stillCurrent()) callback()
        }
    }
}

internal fun parseGatewayPersonalityOptions(result: JsonObject): List<String> =
    (result["items"] as? JsonArray)
        .orEmpty()
        .mapNotNull { item ->
            (item as? JsonObject)
                ?.stringField("text")
                ?.trim()
                ?.removePrefix("/personality")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        it.lowercase() !in setOf("none", "default", "neutral")
                }
        }
        .distinctBy { it.lowercase() }

/** Outcome of an active-turn correction — Rejected and Failed both mean "queue locally instead". */
enum class SteerResult {
    /** Server accepted the active-turn correction. */
    Queued,

    /** Server reachable but no active turn is available to correct. */
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
    val sizeBytes: Long? = null,
)

/** Connect/auth/submit failed before the turn started; the caller retains transport ownership. */
internal class GatewayPreflightException(message: String) : Exception(message)

/** Attachment bytes were not safely bound to a Gateway turn; never silently fall through to SSE. */
internal class GatewayAttachmentPreflightException(message: String) : Exception(message)

/** One connect attempt failed; only a transient WebSocket upgrade may retry immediately. */
internal class GatewayConnectAttemptException(
    message: String,
    val stage: GatewayConnectFailureStage,
    val retryable: Boolean,
    val retryAfterCooldown: Boolean = retryable,
) : Exception(message)

internal enum class GatewayConnectFailureStage(val logName: String) {
    TicketAuth("ticket_auth"),
    Ticket("ticket"),
    UpgradeAuth("upgrade_auth"),
    Upgrade("upgrade"),
    Unsupported("unsupported"),
}

/** Server intentionally refused a durable resume; never create/fallback into a context-free turn. */
internal class GatewayAuthoritativeResumeException(message: String) : Exception(message)

/** [code] is the JSON-RPC error code when the failure came from the server (e.g. 4018, -32601). */
internal class GatewayRpcException(message: String, val code: Int? = null) : Exception(message)

private const val JSONRPC_METHOD_NOT_FOUND = -32601
private const val ATTACH_IMAGE_MAX_BYTES = 25L * 1024L * 1024L
private const val ATTACH_PDF_MAX_BYTES = 50L * 1024L * 1024L
private const val ATTACH_FILE_MAX_BYTES = 50L * 1024L * 1024L
private val AUTHORITATIVE_PROMPT_SUBMIT_REJECTIONS = setOf(
    4004, // malformed truncation target
    4018, // durable/ordinal target is no longer present
    4028, // first-turn truncate requires explicit empty-history confirmation
    4029, // every destructive truncate requires explicit confirmation
    4030, // durable row id and client ordinal disagree
    4090, // active-session capacity policy
    5008, // durable truncation could not be persisted
    5070, // initial session persistence failed: storage full
    5071, // other authoritative initial session persistence failure
)
private const val MAX_RECOVERED_CORRECTIONS = 32
private const val MAX_RECOVERED_CORRECTION_CHARS = 32_768
private const val PET_THUMB_DATA_PREFIX = "data:image/png;base64,"

internal fun decodedBase64Size(value: String): Long {
    val compactLength = value.count { !it.isWhitespace() }
    if (compactLength == 0) return 0L
    val padding = value.trimEnd().takeLast(2).count { it == '=' }
    return (compactLength.toLong() * 3L / 4L - padding).coerceAtLeast(0L)
}

private fun JsonObject.attachedImagePaths(): List<String> {
    val direct = stringField("path")?.let(::listOf).orEmpty()
    val pages = (this["pages"] as? JsonArray).orEmpty().mapNotNull { page ->
        (page as? JsonObject)?.stringField("path")
    }
    return direct + pages
}
private const val MAX_PET_THUMB_BASE64_CHARS = 512 * 1024
private val PETDEX_SLUG = Regex("[a-z0-9][a-z0-9-]{0,127}")
private val STANDARD_BASE64 = Regex("[A-Za-z0-9+/]*={0,2}")

private fun isTrustedPetdexAssetUrl(raw: String): Boolean {
    val url = raw.toHttpUrlOrNull() ?: return false
    return url.scheme == "https" &&
        url.host == "assets.petdex.dev" &&
        url.port == 443 &&
        url.username.isEmpty() &&
        url.password.isEmpty()
}

private fun isValidPetThumbnailDataUri(raw: String): Boolean {
    if (!raw.startsWith(PET_THUMB_DATA_PREFIX)) return false
    val payload = raw.substring(PET_THUMB_DATA_PREFIX.length)
    return payload.isNotEmpty() &&
        payload.length <= MAX_PET_THUMB_BASE64_CHARS &&
        payload.length % 4 == 0 &&
        STANDARD_BASE64.matches(payload)
}

private const val MAX_PET_SPRITESHEET_BYTES = 32L * 1024L * 1024L
private const val PET_MUTATION_TIMEOUT_MS = 120_000L
private const val MAX_PET_GALLERY_ITEMS = 10_000
private const val MAX_PET_STATE_ROWS = 64

data class GatewayPetInfo(
    val enabled: Boolean,
    val slug: String? = null,
    val displayName: String? = null,
    val mime: String? = null,
    val spritesheet: ByteArray? = null,
    val spritesheetRevision: String? = null,
    val spritesheetUnchanged: Boolean = false,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val framesPerState: Int = 0,
    val framesByState: Map<String, Int> = emptyMap(),
    val framesByRow: Map<String, Int> = emptyMap(),
    val loopMs: Int = 0,
    val scale: Float = 1f,
    val stateRows: List<String> = emptyList(),
)

data class GatewayPetGalleryItem(
    val slug: String,
    val displayName: String,
    val installed: Boolean,
    val spritesheetUrl: String?,
    val curated: Boolean,
    val generated: Boolean,
)

data class GatewayPetGallery(
    val enabled: Boolean,
    val active: String?,
    val pets: List<GatewayPetGalleryItem>,
)

private fun parseGatewayPetInfo(response: JsonObject): GatewayPetInfo {
    val enabled = (response["enabled"] as? JsonPrimitive)?.booleanOrNull
        ?: throw GatewayRpcException("pet.info returned an invalid response")
    if (!enabled) return GatewayPetInfo(enabled = false)

    val slug = response.stringField("slug")?.takeIf(PETDEX_SLUG::matches)
        ?: throw GatewayRpcException("pet.info returned an invalid slug")
    val revision = response.stringField("spritesheetRevision")?.takeIf { it.length <= 256 }
        ?: throw GatewayRpcException("pet.info returned no spritesheet revision")
    val unchanged = (response["spritesheetUnchanged"] as? JsonPrimitive)?.booleanOrNull == true
    val mime = response.stringField("mime")?.takeIf { it == "image/png" || it == "image/webp" }
    val encoded = response.stringField("spritesheetBase64")
    val spritesheet = when {
        encoded != null -> {
            if (decodedBase64Size(encoded) > MAX_PET_SPRITESHEET_BYTES ||
                encoded.length % 4 != 0 || !STANDARD_BASE64.matches(encoded)
            ) throw GatewayRpcException("pet.info returned an invalid spritesheet")
            runCatching { Base64.getDecoder().decode(encoded) }
                .getOrElse { throw GatewayRpcException("pet.info returned an invalid spritesheet") }
        }
        unchanged -> null
        else -> throw GatewayRpcException("pet.info returned no spritesheet")
    }
    if (spritesheet != null && mime == null) {
        throw GatewayRpcException("pet.info returned an invalid spritesheet mime")
    }

    fun requiredDimension(name: String, max: Int): Int =
        (response[name] as? JsonPrimitive)?.intOrNull
            ?.takeIf { it in 1..max }
            ?: throw GatewayRpcException("pet.info returned an invalid $name")

    val rows = (response["stateRows"] as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { name ->
            name.isNotEmpty() && name.length <= 64
        }
    }
    if (rows.isEmpty() || rows.size > MAX_PET_STATE_ROWS) {
        throw GatewayRpcException("pet.info returned invalid state rows")
    }
    fun frameCounts(name: String): Map<String, Int> {
        val values = response[name] as? JsonObject ?: return emptyMap()
        if (values.size > MAX_PET_STATE_ROWS) throw GatewayRpcException("pet.info returned too many frame counts")
        return values.mapNotNull { (key, value) ->
            val count = (value as? JsonPrimitive)?.intOrNull
            if (key.length <= 64 && count != null && count in 1..120) key to count else null
        }.toMap()
    }
    return GatewayPetInfo(
        enabled = true,
        slug = slug,
        displayName = response.stringField("displayName")?.take(256) ?: slug,
        mime = mime,
        spritesheet = spritesheet,
        spritesheetRevision = revision,
        spritesheetUnchanged = unchanged,
        frameWidth = requiredDimension("frameW", 4096),
        frameHeight = requiredDimension("frameH", 4096),
        framesPerState = requiredDimension("framesPerState", 120),
        framesByState = frameCounts("framesByState"),
        framesByRow = frameCounts("framesByRow"),
        loopMs = requiredDimension("loopMs", 60_000),
        scale = response.stringField("scale")?.toFloatOrNull()?.coerceIn(0.1f, 3f) ?: 1f,
        stateRows = rows,
    )
}

private fun parseGatewayPetGallery(response: JsonObject): GatewayPetGallery {
    val enabled = (response["enabled"] as? JsonPrimitive)?.booleanOrNull
        ?: throw GatewayRpcException("pet.gallery returned an invalid response")
    val active = response.stringField("active")?.takeIf(PETDEX_SLUG::matches)
    val rawPets = (response["pets"] as? JsonArray).orEmpty()
    if (rawPets.size > MAX_PET_GALLERY_ITEMS) {
        throw GatewayRpcException("pet.gallery returned too many pets")
    }
    val pets = rawPets.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val slug = item.stringField("slug")?.takeIf(PETDEX_SLUG::matches) ?: return@mapNotNull null
        val rawUrl = item.stringField("spritesheetUrl")?.trim().orEmpty()
        GatewayPetGalleryItem(
            slug = slug,
            displayName = item.stringField("displayName")?.take(256)?.ifBlank { slug } ?: slug,
            installed = (item["installed"] as? JsonPrimitive)?.booleanOrNull == true,
            spritesheetUrl = rawUrl.takeIf { it.isNotEmpty() && isTrustedPetdexAssetUrl(it) },
            curated = (item["curated"] as? JsonPrimitive)?.booleanOrNull == true,
            generated = (item["generated"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }
    return GatewayPetGallery(enabled = enabled, active = active, pets = pets)
}

data class GatewayCompressResult(
    val status: String,
    val output: String? = null,
    val removed: Int? = null,
    val beforeMessages: Int? = null,
    val afterMessages: Int? = null,
    val beforeTokens: Int? = null,
    val afterTokens: Int? = null,
    val usage: UsageInfo? = null,
    val info: JsonObject? = null,
    val messages: List<MessageItem> = emptyList(),
) {
    val effectiveUsage: UsageInfo?
        get() = usage ?: GatewayEventMapper.parseGatewayUsage(info?.get("usage") as? JsonObject)

    val title: String?
        get() = info?.stringField("title")?.takeIf { it.isNotBlank() }

    val isAuthoritative: Boolean
        get() = messages.isNotEmpty()
}

internal fun parseBotModeRoster(payload: JsonObject): BotModeRoster {
    val rawRows = (payload["profiles"] as? JsonArray).orEmpty()
    val rows = rawRows.mapNotNull { it as? JsonObject }
    val bots = rows.mapNotNull(::parseBotRosterEntry)
    val defaultRow = rows.firstOrNull {
        (it["is_default"] as? JsonPrimitive)?.booleanOrNull == true
    } ?: rows.firstOrNull { it.stringField("name") == "default" }
    return BotModeRoster(
        bots = bots,
        groups = parseBotGroupRooms(defaultRow),
        botModeProtocolSupported =
            (payload["bot_mode_protocol"] as? JsonPrimitive)?.booleanOrNull == true,
    )
}

private fun parseBotRosterEntry(row: JsonObject): BotRosterEntry? {
    val name = row.stringField("name")?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val uiMeta = (row["ui_meta"] as? JsonObject)
        ?.takeIf { it.toString().toByteArray(Charsets.UTF_8).size <= 65_536 }
        ?: JsonObject(emptyMap())
    val botMeta = uiMeta["hermes-bots"] as? JsonObject
    val title = botMeta?.stringField("title")?.trim()?.take(128).orEmpty()
    val displayName = title.takeIf(String::isNotBlank)
        ?: row.stringField("display_name")?.trim()?.takeIf(String::isNotBlank)?.take(128)
        ?: name
    return BotRosterEntry(
        profile = Profile(
            name = name,
            model = row.stringField("model").orEmpty(),
            provider = row.stringField("provider").orEmpty(),
            description = row.stringField("description")?.take(512).orEmpty(),
            skillCount = (row["skill_count"] as? JsonPrimitive)?.intOrNull ?: 0,
            isDefault = (row["is_default"] as? JsonPrimitive)?.booleanOrNull ?: false,
            hasAvatar = (row["has_avatar"] as? JsonPrimitive)?.booleanOrNull ?: false,
        ),
        displayName = displayName,
        botTitle = title,
        hidden = (botMeta?.get("hidden") as? JsonPrimitive)?.booleanOrNull == true,
        lastSession = parseBotSessionSummary(row["last_session"] as? JsonObject),
        workerSession = parseBotSessionSummary(row["worker_session"] as? JsonObject),
        canonicalSession = parseBotSessionSummary(row["canonical_session"] as? JsonObject),
    )
}

private fun parseBotSessionSummary(row: JsonObject?): BotSessionSummary? {
    row ?: return null
    val id = row.stringField("id")?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return BotSessionSummary(
        id = id,
        resolvedId = row.stringField("resolved_id")?.trim()?.takeIf(String::isNotEmpty) ?: id,
        title = row.stringField("title")?.take(256).orEmpty(),
        rootTitle = row.stringField("root_title")?.take(256).orEmpty(),
        preview = row.stringField("preview")?.take(512).orEmpty(),
        startedAtMs = normalizeHermesEpoch(row.longField("started_at")),
        lastActiveAtMs = normalizeHermesEpoch(row.longField("last_active")),
        messageCount = (row["message_count"] as? JsonPrimitive)?.intOrNull ?: 0,
    )
}

private fun parseBotGroupRooms(defaultRow: JsonObject?): List<BotGroupRoom> {
    val uiMeta = defaultRow?.get("ui_meta") as? JsonObject ?: return emptyList()
    if (uiMeta.toString().toByteArray(Charsets.UTF_8).size > 65_536) return emptyList()
    val snapshot = uiMeta["hermes-bots-groups"] as? JsonObject ?: return emptyList()
    val rooms = snapshot["rooms"] as? JsonObject ?: return emptyList()
    return rooms.entries.take(64).mapNotNull { (key, raw) ->
        val room = raw as? JsonObject ?: return@mapNotNull null
        val name = room.stringField("name")?.trim()?.takeIf(String::isNotEmpty)?.take(128)
            ?: key.substringAfter(':').take(128)
        val members = (room["members"] as? JsonArray).orEmpty().take(6).mapNotNull { memberRaw ->
            val member = memberRaw as? JsonObject ?: return@mapNotNull null
            val memberName = member.stringField("name")?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            BotGroupMember(
                name = memberName.take(128),
                handle = member.stringField("handle")?.take(128),
                connectionId = member.stringField("connectionId")?.take(128),
                connectionLabel = member.stringField("connectionLabel")?.take(128),
            )
        }
        val messages = (room["log"] as? JsonArray).orEmpty().takeLast(16).mapNotNull { messageRaw ->
            val message = messageRaw as? JsonObject ?: return@mapNotNull null
            val from = message["from"] as? JsonObject ?: JsonObject(emptyMap())
            val text = message.stringField("text")?.trim()?.takeIf(String::isNotEmpty)?.take(1_200)
                ?: return@mapNotNull null
            BotGroupMessage(
                id = message.stringField("id")?.take(160),
                senderName = from.stringField("name")?.trim()?.takeIf(String::isNotEmpty)?.take(128)
                    ?: "Bot",
                senderKind = from.stringField("kind")?.take(32) ?: "member",
                senderSource = from.stringField("source")?.take(128),
                text = text,
                atMs = normalizeHermesEpoch(message.longField("at")),
            )
        }
        BotGroupRoom(
            key = key.take(256),
            roomId = room.stringField("roomId")?.take(128),
            name = name,
            revision = room.longField("revision"),
            members = members,
            messages = messages,
        )
    }.sortedByDescending(BotGroupRoom::latestActivityAtMs)
}

private fun JsonObject.longField(key: String): Long =
    (get(key) as? JsonPrimitive)?.longOrNull
        ?: (get(key) as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
        ?: 0L

private fun normalizeHermesEpoch(value: Long): Long = when {
    value <= 0L -> 0L
    value < 10_000_000_000L -> value * 1_000L
    else -> value
}

private fun Throwable?.isMethodNotFound(): Boolean {
    val rpcError = this as? GatewayRpcException ?: return false
    if (rpcError.code == JSONRPC_METHOD_NOT_FOUND) return true
    val msg = rpcError.message ?: return false
    return msg.contains("method not found", ignoreCase = true) ||
        msg.contains("unknown method", ignoreCase = true)
}

private fun Throwable?.isAuthoritativePromptSubmitRejection(): Boolean =
    (this as? GatewayRpcException)?.code in AUTHORITATIVE_PROMPT_SUBMIT_REJECTIONS

private fun Throwable?.isApprovalModeUnsupported(): Boolean {
    val rpcError = this as? GatewayRpcException ?: return false
    val message = rpcError.message.orEmpty()
    return rpcError.code == JSONRPC_METHOD_NOT_FOUND ||
        rpcError.code == 4002 ||
        message.contains("approval mode", ignoreCase = true) &&
        (
            message.contains("unknown", ignoreCase = true) ||
                message.contains("unsupported", ignoreCase = true)
        )
}

private fun approvalModeUnsupported(): GatewayRpcException =
    GatewayRpcException(
        "profile approval modes are not supported by this gateway",
        JSONRPC_METHOD_NOT_FOUND,
    )

private fun approvalModeRequiresLaunchProfile(): GatewayRpcException =
    GatewayRpcException(
        "profile approval mode is read-only for multiplexed non-launch profiles",
    )

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
