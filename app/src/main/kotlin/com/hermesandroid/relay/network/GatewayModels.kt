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
 * Callback set for one gateway turn. Shapes intentionally mirror the SSE
 * callback lambdas in ChatViewModel.startStream() so the gateway branch can
 * forward to the exact same ChatHandler mutations.
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
     * Server-side interactive ask (clarify/approval/sudo/secret) that blocks
     * the turn until answered on another surface or the turn is cancelled.
     * MVP surfaces these as a display-only system notice (desktop CLI v0.1
     * precedent — no RPC response is sent).
     */
    val onInteractionRequest: (kind: String, detail: String) -> Unit,
)
