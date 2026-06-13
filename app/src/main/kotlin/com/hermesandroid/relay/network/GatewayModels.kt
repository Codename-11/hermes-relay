package com.hermesandroid.relay.network

import com.hermesandroid.relay.network.models.UsageInfo

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
}

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
)
