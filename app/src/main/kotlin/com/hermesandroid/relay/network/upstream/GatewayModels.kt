package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.network.upstream.models.UsageInfo

/**
 * Shared types for the Gateway chat transport — upstream hermes-agent's
 * `tui_gateway` JSON-RPC-over-WebSocket surface at the dashboard's `/api/ws`.
 *
 * This is the same wire protocol the official hermes-desktop client and the
 * Ink TUI speak (reference shapes vendored in `desktop/src/gatewayTypes.ts`).
 * It is the only upstream surface that streams reasoning live
 * (`reasoning.delta` / `thinking.delta`) — the api_server SSE paths only
 * deliver reasoning after generation completes.
 */

/**
 * Why the Gateway chat transport is or isn't usable right now. Mirrors
 * [com.hermesandroid.relay.viewmodel.StandardVoiceAvailability] — both ride
 * the dashboard surface and share the same probe — minus the audio-route
 * requirement (`/api/ws` ships with every embedded-chat dashboard build).
 */
enum class GatewayAvailability {
    /** No probe has completed yet (startup, connection switch). */
    Unknown,

    /** Dashboard reachable and authenticated (or auth not required). */
    Ready,

    /** Dashboard reachable and gated, but no signed-in session — Manage sign-in unlocks it. */
    SignInRequired,

    /** Dashboard URL configured but `/api/status` did not answer. */
    Unreachable,

    /**
     * Runtime sticky downgrade: the WS upgrade or ticket mint was rejected in
     * a way that says this server build has no usable `/api/ws` (404 on the
     * route, dashboard build predating the embedded chat). Cleared on
     * connection switch / fresh probe cycle.
     */
    Unsupported,
}

/** Lifecycle of the gateway WebSocket, exposed for diagnostics. */
enum class GatewayConnectionState {
    Idle,
    MintingTicket,
    Connecting,
    AwaitingReady,
    Ready,
}

/**
 * Streaming-endpoint resolution with the gateway tier — pure so the matrix
 * is unit-testable without an AndroidViewModel. ConnectionViewModel
 * delegates here with its live state.
 *
 * Manual picks pass through untouched (ChatViewModel handles per-turn
 * fallback when a "gateway" pick can't serve a send); "auto" prefers the
 * gateway only when the dashboard probe says [GatewayAvailability.Ready],
 * otherwise it falls back to the capability-preferred SSE endpoint.
 */
fun resolveStreamingEndpointPreference(
    preference: String,
    gateway: GatewayAvailability,
    capabilities: ServerCapabilities,
): String = when (preference) {
    "sessions", "completions", "runs", "gateway" -> preference
    else -> if (gateway == GatewayAvailability.Ready) {
        "gateway"
    } else {
        capabilities.preferredChatEndpoint()
    }
}

/**
 * Cancellable handle for one in-flight chat turn, regardless of transport.
 * SSE turns wrap their [okhttp3.sse.EventSource]; gateway turns wrap a
 * `session.interrupt` dispatch. Replaces the raw `EventSource?` field in
 * ChatViewModel so both transports share the cancel/teardown sites.
 */
fun interface ActiveTurnHandle {
    fun cancel()

    /**
     * Release this client's callbacks without interrupting server-side work.
     * Gateway turns override this for process/UI teardown; transports that
     * cannot be reattached retain their existing cancel behavior.
     */
    fun detach() = cancel()
}

/** Partial text checkpoint returned by current upstream Hermes on live resume. */
data class GatewayInflightTurn(
    val user: String,
    val assistant: String,
    val streaming: Boolean,
)

/** Result of reattaching Android to an existing durable Gateway session. */
data class GatewaySessionRecovery(
    val storedSessionId: String,
    val liveSessionId: String,
    val running: Boolean,
    val status: String?,
    val inflight: GatewayInflightTurn?,
    /** Non-null only when subsequent turn events are bound to [GatewayTurnCallbacks]. */
    val handle: ActiveTurnHandle?,
)

/**
 * One server-side interactive ask. The agent thread upstream is BLOCKED
 * until the matching respond RPC arrives, the ask times out (resolves to ""
 * server-side), or the turn is cancelled (`session.interrupt` force-releases
 * pending asks and force-denies approvals). Built by [GatewayEventMapper]
 * from the four `*.request` events; answered via the
 * [GatewayChatClient] `respond*` helpers.
 */
data class GatewayAsk(
    val kind: Kind,
    /**
     * Correlates the answer with the blocked server thread. Null ONLY for
     * [Kind.APPROVAL] — upstream approvals correlate per-session, not
     * per-request (`approval.respond` carries `session_id` instead).
     */
    val requestId: String?,
    /** Question / command / prompt — whatever the ask wants the user to read. */
    val text: String,
    /** Clarify-only: server-suggested answers. */
    val choices: List<String>? = null,
    /** Secret-only: the env var the value will be stored under. */
    val envVar: String? = null,
    /**
     * Upstream blocking timeout (clarify/secret 300s, sudo 120s). 0 means no
     * countdown — approvals are session-scoped and never expire on their own.
     */
    val timeoutSeconds: Int,
) {
    enum class Kind { CLARIFY, APPROVAL, SUDO, SECRET }
}

/**
 * Server-side expiry of one blocking gateway interaction. Sudo/secret asks
 * correlate by [requestId]; approvals remain session-scoped and therefore
 * carry no request id.
 */
data class GatewayAskExpiry(
    val kind: GatewayAsk.Kind,
    val requestId: String?,
)

/** Outcome returned by the gateway's `*.respond` RPCs. */
enum class GatewayAskResponse { ACCEPTED, EXPIRED }

/**
 * One `subagent.*` lifecycle event, emitted on the PARENT session. Lifecycle
 * per task: START → (THINKING | TOOL | PROGRESS)* → COMPLETE. Field
 * availability varies by phase — [toolName]/[preview] ride TOOL,
 * [status]/[summary]/[durationSeconds] ride COMPLETE — and older emitters
 * omit everything beyond the three defaults-bearing fields.
 */
data class GatewaySubagentEvent(
    val phase: Phase,
    val taskIndex: Int,
    val taskCount: Int,
    val goal: String,
    val status: String? = null,
    val summary: String? = null,
    val toolName: String? = null,
    val preview: String? = null,
    val durationSeconds: Double? = null,
) {
    enum class Phase { START, THINKING, TOOL, PROGRESS, COMPLETE }
}

/**
 * One session-owned background process returned by the upstream gateway's
 * `process.list` RPC. The registry calls its process id `session_id`; Android
 * exposes it as [id] so it cannot be confused with either the stored chat id or
 * the gateway's live, per-connection session id.
 *
 * [outputPreview] is the registry's short preview, while [outputTail] is the
 * gateway's larger (currently 4,000-character) snapshot used to recover output
 * missed while the WebSocket was unavailable. Unknown/new fields are ignored
 * by the parser so this remains compatible with older and newer gateways.
 */
data class GatewayProcess(
    val id: String,
    val command: String,
    val cwd: String? = null,
    val pid: Long? = null,
    val startedAt: String? = null,
    val uptimeSeconds: Long = 0L,
    val status: String,
    val outputPreview: String? = null,
    val outputTail: String? = null,
    val exitCode: Int? = null,
    val detached: Boolean = false,
    val notifyOnComplete: Boolean = false,
    val sessionScoped: Boolean = false,
    val watchPatterns: List<String> = emptyList(),
    val watchHit: Boolean = false,
) {
    val isRunning: Boolean get() = status.equals("running", ignoreCase = true)
}

/** Whether this gateway socket supports the session-scoped process RPCs. */
enum class GatewayProcessCapability {
    /** Not probed on this socket yet (or no socket is currently connected). */
    Unknown,

    /** A `process.list` / `process.kill` call succeeded. */
    Supported,

    /** The gateway returned JSON-RPC method-not-found for the process surface. */
    Unsupported,
}

/**
 * Connection-level background-process events. These are deliberately separate
 * from [GatewayTurnCallbacks]: output and completion notifications can arrive
 * while no app-initiated turn is active.
 */
sealed interface GatewayProcessEvent {
    enum class Trigger { TOOL_COMPLETE, STATUS_UPDATE, MESSAGE_COMPLETE }

    /** The process snapshot may have changed and should be refreshed. */
    data class Invalidated(val trigger: Trigger) : GatewayProcessEvent

    /** Live output from `agent.terminal.output`. */
    data class Output(val processId: String, val chunk: String) : GatewayProcessEvent

    /** The agent requested that its read-only terminal view be closed. */
    data class TerminalClosed(val processId: String) : GatewayProcessEvent
}

/**
 * One provider from the gateway `model.options` RPC — the curated, authenticated
 * provider/model list the upstream desktop + TUI model picker uses (NOT the
 * api_server `/v1/models`, which collapses to a single generic agent alias).
 */
data class GatewayModelProvider(
    val name: String,
    val slug: String,
    val models: List<String>,
    val isCurrent: Boolean,
    val warning: String?,
    // Picker hints from upstream `model.options` (build_models_payload,
    // picker_hints=True). Default to "usable" so older servers that omit them
    // don't gray everything out.
    val authenticated: Boolean = true,
    /** Paid models the current account can't pick (free-tier / no credits). */
    val unavailableModels: List<String> = emptyList(),
    val freeTier: Boolean = false,
    val totalModels: Int = 0,
)

/** Result of the gateway `model.options` RPC. */
data class GatewayModelOptions(
    val providers: List<GatewayModelProvider>,
    val currentModel: String,
    val currentProvider: String,
)

/**
 * The explicit in-chat overrides to bind onto a gateway `session.create` as the
 * new session's PER-SESSION overrides. Matches the upstream desktop client,
 * whose `session.create` carries `model`/`provider`/`reasoning_effort`/`fast`
 * (tui_gateway honors them → `session_model_override` / `create_reasoning_override`
 * / `create_service_tier_override`; verified `tui_gateway/server.py:4175-4191`).
 * Supplied live by ChatViewModel from the picker + safety/speed controls.
 *
 * Every field is nullable = "no explicit override for this new chat", so the
 * fresh session inherits the profile / server default rather than the picker
 * (or a stale local value) silently clobbering it. Crucially this keeps these
 * picks OFF the sessionless `config.set` path, which upstream applies as GLOBAL
 * writes (and `yolo` even leaks to other sessions via `os.environ`).
 *
 * [model] is the model id (e.g. `grok-4.3`); [provider] is the authenticated
 * provider slug (e.g. `xai`). [reasoningEffort] is the upstream effort string
 * (`low`/`medium`/`high`/…). [fast] pins the priority service tier when true.
 * Note `yolo` is intentionally absent — upstream `session.create` does NOT
 * accept it as a per-session override, so it is applied post-create instead.
 */
data class GatewaySessionModel(
    val model: String?,
    val provider: String?,
    val reasoningEffort: String? = null,
    val fast: Boolean? = null,
)

/** Result of the gateway `config.get {key:"reasoning"}` RPC. */
data class GatewayReasoningSettings(
    val effort: String,
    val display: String?,
)

/**
 * Callback set for one gateway turn. Shapes intentionally mirror the SSE
 * callback lambdas in ChatViewModel.startStream() so the gateway branch can
 * forward to the exact same ChatHandler mutations.
 *
 * Every member is a REQUIRED constructor param on purpose: GatewayChatClient
 * `dispatchOn` must wrap each one onto the main thread, and a defaulted
 * member would compile unwrapped — running on the OkHttp reader thread.
 */
class GatewayTurnCallbacks(
    /** Stored (DB) session id — fired on session create/rotate so the drawer + persistence stay correct. */
    val onSessionId: (String) -> Unit,
    /** A gateway `message.start` opened an assistant response for this turn. */
    val onStart: () -> Unit,
    val onTextDelta: (String) -> Unit,
    val onThinkingDelta: (String) -> Unit,
    val onToolCallStart: (toolCallId: String, toolName: String) -> Unit,
    val onToolCallDone: (toolCallId: String, resultPreview: String?) -> Unit,
    val onToolCallFailed: (toolCallId: String, errorMsg: String?) -> Unit,
    val onTurnComplete: () -> Unit,
    val onComplete: () -> Unit,
    val onUsage: (UsageInfo?) -> Unit,
    val onError: (String) -> Unit,
    /**
     * `tool.generating` — the model is still writing this tool's arguments.
     * Carries the tool name when upstream sent one. The next `tool.start`
     * for the same name adopts the "preparing" placeholder (per name, FIFO).
     */
    val onToolGenerating: (toolName: String?) -> Unit,
    /** `subagent.*` lifecycle on the parent session — feeds the subagent lanes. */
    val onSubagentEvent: (GatewaySubagentEvent) -> Unit,
    /**
     * Server-side interactive ask (clarify/approval/sudo/secret) that blocks
     * the turn until answered via the matching respond RPC or the turn is
     * cancelled.
     */
    val onInteractionRequest: (GatewayAsk) -> Unit,
    /** Server declared a pending interaction expired; clear only the matching card. */
    val onInteractionExpired: (GatewayAskExpiry) -> Unit,
    /**
     * Gateway `status.update` lifecycle line — model fallback, retries, and
     * errors (often emoji-prefixed: 🔄 fallback, ⏳ retry, ❌ error). Default
     * no-op so non-gateway/legacy constructors don't need to provide it.
     */
    val onStatusUpdate: (kind: String?, text: String) -> Unit = { _, _ -> },
    /** Clear a transient status only when [kind] still owns the visible status slot. */
    val onStatusClear: (kind: String) -> Unit = { _ -> },
)

/**
 * UI registration for one server-initiated gateway turn.
 *
 * Background-process completion is converted upstream into a normal assistant
 * turn on the originating session. It has no matching client [GatewayChatClient.sendTurn]
 * call, so the client asks the active conversation for callbacks when the first
 * `message.start` arrives. [onHandle] binds the resulting cancellable turn into
 * the same Stop/steer lifecycle as a locally submitted turn.
 */
class GatewayInboundTurnRegistration(
    val callbacks: GatewayTurnCallbacks,
    /** Main-thread admission. False leaves the server turn unbound for history recovery. */
    val onHandle: (ActiveTurnHandle) -> Boolean,
)
