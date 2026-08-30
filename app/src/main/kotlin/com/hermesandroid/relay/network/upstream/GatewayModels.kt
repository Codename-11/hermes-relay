package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.network.upstream.models.UsageInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

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

    /** The `/api/ws` socket completed `gateway.ready` for the active route. */
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

/** Whether an idle Gateway may be retried automatically by a visible Chat surface. */
enum class GatewayReconnectDisposition {
    None,
    Retryable,
    Terminal,
}

/** Profile-persisted approval policy introduced by upstream gateway contract v3. */
enum class GatewayApprovalMode(val wireValue: String) {
    Manual("manual"),
    Smart("smart"),
    Off("off");

    companion object {
        fun fromWire(value: String?): GatewayApprovalMode? = when (value?.trim()?.lowercase()) {
            "manual" -> Manual
            "smart" -> Smart
            "off" -> Off
            else -> null
        }
    }
}

/** Whether this gateway exposes the contract-v3 profile approval-mode RPCs. */
enum class GatewayApprovalModeCapability {
    Unknown,
    Supported,
    Unsupported,
}

/**
 * Streaming-endpoint resolution with the gateway tier — pure so the matrix
 * is unit-testable without an AndroidViewModel. ConnectionViewModel
 * delegates here with its live state.
 *
 * Manual picks pass through untouched (ChatViewModel handles per-turn
 * fallback when a "gateway" pick can't serve a send); "auto" prefers the
 * gateway while the dashboard probe is unresolved or ready. A capability-
 * preferred SSE fallback is selected only after a definitive unavailable,
 * unsupported, or sign-in-required verdict.
 */
fun resolveStreamingEndpointPreference(
    preference: String,
    gateway: GatewayAvailability,
    capabilities: ServerCapabilities,
): String = when (preference) {
    "sessions", "completions", "runs", "gateway" -> preference
    else -> if (
        gateway == GatewayAvailability.Ready ||
        gateway == GatewayAvailability.Unknown
    ) {
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
    /** Accepted active-turn redirects in display order; additive on newer Hermes. */
    val corrections: List<String> = emptyList(),
    val status: String? = null,
    val error: String? = null,
    val recoverable: Boolean = false,
)

/** A next-turn prompt accepted by upstream while the current turn was busy. */
data class GatewayQueuedTurn(
    val user: String,
)

/** A fresh crash marker caused `session.resume` to schedule one continuation. */
data class GatewayAutoContinue(
    val attempt: Int,
    val interruptedAt: Double?,
)

/** Optional project identity attached to newer upstream session metadata. */
data class GatewaySessionProject(
    val id: String?,
    val slug: String?,
    val name: String,
    val primaryPath: String?,
)

/** Result of reattaching Android to an existing durable Gateway session. */
data class GatewaySessionRecovery(
    val storedSessionId: String,
    val liveSessionId: String,
    val running: Boolean,
    val status: String?,
    val inflight: GatewayInflightTurn?,
    val queued: GatewayQueuedTurn?,
    /** Non-null only when subsequent turn events are bound to [GatewayTurnCallbacks]. */
    val handle: ActiveTurnHandle?,
    val autoContinue: GatewayAutoContinue? = null,
) {
    /** Whether upstream still owes this client live turn events. */
    val hasPendingWork: Boolean
        get() = running || queued != null || autoContinue != null
}

/** A detached sibling turn reached its terminal event on the shared Gateway socket. */
data class GatewayBackgroundTurnCompletion(
    val storedSessionId: String,
    val liveSessionId: String,
    val profile: String?,
    val expectedAssistantText: String?,
)

/** Input lifecycle from a deliberately detached Gateway turn. */
sealed interface GatewayBackgroundInteractionEvent {
    val storedSessionId: String
    val profile: String?
    val ask: GatewayAsk

    data class Requested(
        override val storedSessionId: String,
        override val profile: String?,
        override val ask: GatewayAsk,
    ) : GatewayBackgroundInteractionEvent

    /** An authoritative upstream `*.expire` event ended this request. */
    data class Expired(
        override val storedSessionId: String,
        override val profile: String?,
        override val ask: GatewayAsk,
    ) : GatewayBackgroundInteractionEvent
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
    /** Server-advertised answers for clarify and approval requests. */
    val choices: List<String>? = null,
    /** Clarify-only: several advertised choices may be returned together. */
    val multiSelect: Boolean = false,
    /** Approval-only: the smart observer denied and the owner may override once. */
    val smartDenied: Boolean = false,
    /** Secret-only: the env var the value will be stored under. */
    val envVar: String? = null,
    /**
     * Server-advertised blocking timeout. 0 means no client countdown; the
     * authoritative `*.expire` event still retires the interaction.
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

/** Deterministic, non-low risk metadata emitted after a tool returns output. */
data class GatewayToolOutputRisk(
    val toolCallId: String,
    val toolName: String,
    val risk: String,
    val findings: List<String>,
    val redacted: Boolean,
)

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
    val subagentId: String? = null,
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

/** Authoritative execution state reported by upstream `session.active_list`. */
enum class GatewayActiveSessionStatus(val wireValue: String) {
    Idle("idle"),
    Starting("starting"),
    Working("working"),
    Waiting("waiting");

    companion object {
        fun fromWire(value: String?): GatewayActiveSessionStatus? = when (value?.trim()?.lowercase()) {
            "idle" -> Idle
            "starting" -> Starting
            "working" -> Working
            "waiting" -> Waiting
            else -> null
        }
    }
}

/**
 * One in-memory runtime returned by upstream `session.active_list`.
 *
 * The RPC is process-wide in current upstream Hermes. Its rows do not normally
 * identify their profile, so [profile] stays null unless a future gateway
 * explicitly sends one. Callers must resolve [storedSessionId] against their
 * own profile-scoped session registry and fail closed when ownership is
 * ambiguous; the transport never synthesizes profile attribution.
 */
data class GatewayActiveSession(
    /** Per-process runtime id used by live Gateway events and session RPCs. */
    val runtimeSessionId: String,
    /** Durable history id (`session_key`) used by the REST/session database. */
    val storedSessionId: String,
    val status: GatewayActiveSessionStatus,
    /** Unix epoch seconds from upstream's in-memory runtime record. */
    val lastActiveEpochSeconds: Double,
    /** Future-compatible only; null for the current upstream contract. */
    val profile: String? = null,
)

/** Exact owner already known by this client for a foreground or detached runtime. */
data class GatewayKnownSessionOwner(
    val storedSessionId: String,
    val profile: String?,
)

/** Whether the current Gateway socket exposes `session.active_list`. */
enum class GatewayActiveSessionCapability {
    Unknown,
    Supported,
    Unsupported,
}

/**
 * Result of one process-wide live-session snapshot request. Unsupported is
 * intentionally distinct from transport/protocol failure so callers can use
 * another source only for older gateways, while failures remain Unknown.
 */
sealed interface GatewayActiveSessionsResult {
    data class Success(val sessions: List<GatewayActiveSession>) : GatewayActiveSessionsResult
    data object Unsupported : GatewayActiveSessionsResult
    data class TransientFailure(val error: Throwable) : GatewayActiveSessionsResult
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
    /** Per-model capability rows keyed by the exact model id. */
    val capabilities: Map<String, GatewayModelCapabilities> = emptyMap(),
)

/** Shared tolerant parser for the gateway RPC and API-server REST twins. */
internal fun parseGatewayModelProvider(obj: JsonObject): GatewayModelProvider? {
    val slug = (obj["slug"] as? JsonPrimitive)?.contentOrNull
        ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val capabilities = (obj["capabilities"] as? JsonObject).orEmpty().mapNotNull { (model, raw) ->
        val row = raw as? JsonObject ?: return@mapNotNull null
        val effortsElement = row["reasoning_efforts"]
        val efforts = if (effortsElement is JsonArray) {
            effortsElement
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()
        } else {
            null
        }
        val modelId = model.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        modelId to GatewayModelCapabilities(
            reasoning = (row["reasoning"] as? JsonPrimitive)?.booleanOrNull,
            reasoningEfforts = efforts,
            reasoningEffortsExact =
                (row["reasoning_efforts_exact"] as? JsonPrimitive)?.booleanOrNull,
        )
    }.toMap()
    return GatewayModelProvider(
        name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: slug,
        slug = slug,
        models = (obj["models"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            .distinct(),
        isCurrent = (obj["is_current"] as? JsonPrimitive)?.booleanOrNull ?: false,
        warning = (obj["warning"] as? JsonPrimitive)?.contentOrNull,
        authenticated = (obj["authenticated"] as? JsonPrimitive)?.booleanOrNull ?: true,
        unavailableModels = (obj["unavailable_models"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            .distinct(),
        freeTier = (obj["free_tier"] as? JsonPrimitive)?.booleanOrNull ?: false,
        totalModels = (obj["total_models"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
        capabilities = capabilities,
    )
}

/**
 * Publish one coherent row per provider identity.
 *
 * Dynamic catalogs and compatibility payloads can repeat a provider row or a
 * model inside that row. Provider slugs are case-insensitive upstream, while
 * model ids remain exact request values. Merge only equal provider slugs so a
 * model intentionally offered by two different providers stays selectable.
 */
internal fun normalizeGatewayModelProviders(
    providers: List<GatewayModelProvider>,
): List<GatewayModelProvider> {
    val normalized = linkedMapOf<String, GatewayModelProvider>()
    providers.forEach { raw ->
        val slug = raw.slug.trim()
        if (slug.isEmpty()) return@forEach
        val models = raw.models.map(String::trim).filter(String::isNotEmpty).distinct()
        val unavailable = raw.unavailableModels
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        val capabilities = raw.capabilities.mapNotNull { (model, capability) ->
            model.trim().takeIf(String::isNotEmpty)?.let { it to capability }
        }.toMap()
        val row = raw.copy(
            name = raw.name.trim().ifEmpty { slug },
            slug = slug,
            models = models,
            unavailableModels = unavailable,
            totalModels = maxOf(raw.totalModels, models.size),
            capabilities = capabilities,
        )
        val identity = slug.lowercase()
        val existing = normalized[identity]
        normalized[identity] = if (existing == null) {
            row
        } else {
            val mergedModels = (existing.models + row.models).distinct()
            existing.copy(
                models = mergedModels,
                isCurrent = existing.isCurrent || row.isCurrent,
                warning = existing.warning ?: row.warning,
                authenticated = existing.authenticated || row.authenticated,
                unavailableModels = (existing.unavailableModels + row.unavailableModels).distinct(),
                freeTier = existing.freeTier || row.freeTier,
                totalModels = maxOf(existing.totalModels, row.totalModels, mergedModels.size),
                capabilities = mergeGatewayModelCapabilities(existing.capabilities, row.capabilities),
            )
        }
    }
    return normalized.values.toList()
}

private fun mergeGatewayModelCapabilities(
    existing: Map<String, GatewayModelCapabilities>,
    incoming: Map<String, GatewayModelCapabilities>,
): Map<String, GatewayModelCapabilities> {
    val merged = existing.toMutableMap()
    incoming.forEach { (model, next) ->
        val current = merged[model]
        merged[model] = if (current == null) {
            next
        } else {
            GatewayModelCapabilities(
                reasoning = next.reasoning ?: current.reasoning,
                reasoningEfforts = next.reasoningEfforts ?: current.reasoningEfforts,
                reasoningEffortsExact = next.reasoningEffortsExact ?: current.reasoningEffortsExact,
            )
        }
    }
    return merged
}

data class GatewayMoaReference(
    val index: Int?,
    val count: Int?,
    val label: String,
    val text: String,
    val available: Boolean = true,
)

/** Result of the gateway `model.options` RPC. */
data class GatewayModelOptions(
    val providers: List<GatewayModelProvider>,
    val currentModel: String,
    val currentProvider: String,
)

/** Coherent model identity from a single `session.info` payload. */
data class GatewayModelIdentity(val model: String, val provider: String)

/** Model identity and effort observed together in one `session.info` payload. */
data class GatewayReasoningIdentity(
    val identity: GatewayModelIdentity,
    val effort: String,
)

/** Reject provider catalogs that completed after a profile/context switch. */
internal fun isCurrentModelOptionsResponse(
    requestGeneration: Long,
    currentGeneration: Long,
    requestProfileKey: String,
    currentProfileKey: String,
): Boolean =
    requestGeneration == currentGeneration && requestProfileKey == currentProfileKey

/**
 * Selects the identity a model-options response may publish into chat UI state.
 * Catalog-only requests populate picker choices without changing session identity.
 */
internal fun modelOptionsIdentityToPublish(
    catalogOnly: Boolean,
    hasLiveSession: Boolean,
    sessionIdentity: GatewayModelIdentity?,
    options: GatewayModelOptions,
): GatewayModelIdentity? = when {
    catalogOnly -> null
    hasLiveSession && sessionIdentity != null -> sessionIdentity
    else -> GatewayModelIdentity(options.currentModel, options.currentProvider)
}

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
 * (`low`/`medium`/`high`/…). [fast] follows the contract-v4 tri-state: `true`
 * pins priority, `false` explicitly pins normal, and `null` omits the field so
 * the profile's service tier is inherited.
 * Note `yolo` is intentionally absent — upstream `session.create` does NOT
 * accept it as a per-session override, so it is applied post-create instead.
 */
data class GatewaySessionModel(
    val model: String?,
    val provider: String?,
    val reasoningEffort: String? = null,
    val fast: Boolean? = null,
)

/** Structured terminal failure carried by Gateway `message.complete`. */
data class GatewayTurnFailure(
    val error: String,
    val recoverable: Boolean,
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
    /**
     * Gateway `message.interim` sealed an attempted assistant message before
     * the terminal `message.complete`. When [alreadyStreamed] is false, [text]
     * has not arrived through `message.delta` and should be rendered before
     * sealing the current assistant segment.
     */
    val onInterimMessage: (text: String, alreadyStreamed: Boolean) -> Unit = { _, _ -> },
    /**
     * The terminal text is equal/prefix-related to the sealed interim, so the
     * existing segment should be replaced in place instead of opening a second
     * assistant bubble.
     */
    val onInterimReconciled: (text: String) -> Unit = { _ -> },
    val onThinkingDelta: (String) -> Unit,
    val onToolCallStart: (toolCallId: String, toolName: String, argsPreview: String?) -> Unit,
    val onToolCallDone: (toolCallId: String, resultPreview: String?) -> Unit,
    val onToolCallFailed: (toolCallId: String, errorMsg: String?) -> Unit,
    /** Attach deterministic output-risk metadata to the matching tool card. */
    val onToolOutputRisk: (GatewayToolOutputRisk) -> Unit = { _ -> },
    val onTurnComplete: () -> Unit,
    /**
     * Fired before [onComplete] when this turn rejoined after a socket gap.
     * Events emitted while the socket was unavailable are not replayed, so
     * the caller must reconcile the durable transcript after completion.
     */
    val onReconcileRequired: () -> Unit,
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
    /** Successful MoA advisor output for a transient labelled reference block. */
    val onMoaReference: (GatewayMoaReference) -> Unit,
    /**
     * Server-side interactive ask (clarify/approval/sudo/secret) that blocks
     * the turn until answered via the matching respond RPC or the turn is
     * cancelled.
     */
    val onInteractionRequest: (GatewayAsk) -> Unit,
    /** Server declared a pending interaction expired; clear only the matching card. */
    val onInteractionExpired: (GatewayAskExpiry) -> Unit,
    /** Existing durable session could not be rebound; no prompt was submitted. */
    val onResumeFailure: (String) -> Unit = { _ -> },
    /** Terminal `message.complete {status:"error"}` without prose inspection. */
    val onFailure: (GatewayTurnFailure) -> Unit = { _ -> },
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
