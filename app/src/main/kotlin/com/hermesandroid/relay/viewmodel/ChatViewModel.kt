package com.hermesandroid.relay.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.AppAnalytics
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.AttachmentState
import com.hermesandroid.relay.data.BackgroundTaskPhase
import com.hermesandroid.relay.data.BackgroundTaskState
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.ChatSession
import com.hermesandroid.relay.data.ChatTurnAskCheckpoint
import com.hermesandroid.relay.data.ChatTurnAssistantCheckpoint
import com.hermesandroid.relay.data.ChatTurnBackgroundTaskCheckpoint
import com.hermesandroid.relay.data.ChatTurnCheckpoint
import com.hermesandroid.relay.data.ChatTurnCheckpointStore
import com.hermesandroid.relay.data.ChatTurnToolCheckpoint
import com.hermesandroid.relay.data.ChatTurnUserCheckpoint
import com.hermesandroid.relay.data.DataStoreChatTurnCheckpointStore
import com.hermesandroid.relay.data.DemoContent
import com.hermesandroid.relay.data.MediaSettings
import com.hermesandroid.relay.data.MediaSettingsRepository
import com.hermesandroid.relay.data.MessageDeliveryStatus
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.RealtimeConversationContextMessage
import com.hermesandroid.relay.data.RealtimeTurnTrace
import com.hermesandroid.relay.data.ToolCallEvent
import com.hermesandroid.relay.data.VoiceIntentTrace
import com.hermesandroid.relay.data.HermesCard
import com.hermesandroid.relay.data.HermesCardAction
import com.hermesandroid.relay.data.HermesCardDispatch
import com.hermesandroid.relay.data.HermesCardField
import com.hermesandroid.relay.data.HermesCardInput
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.network.upstream.ActiveTurnHandle
import com.hermesandroid.relay.network.upstream.GatewayAsk
import com.hermesandroid.relay.network.upstream.GatewayAskExpiry
import com.hermesandroid.relay.network.upstream.GatewayAskResponse
import com.hermesandroid.relay.network.upstream.GatewayBackgroundTurnCompletion
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.GatewayConnectionState
import com.hermesandroid.relay.network.upstream.GatewayInboundTurnRegistration
import com.hermesandroid.relay.network.upstream.GatewayModelProvider
import com.hermesandroid.relay.network.upstream.GatewayProcess
import com.hermesandroid.relay.network.upstream.GatewayProcessCapability
import com.hermesandroid.relay.network.upstream.GatewayProcessEvent
import com.hermesandroid.relay.network.upstream.GatewaySessionModel
import com.hermesandroid.relay.network.upstream.GatewayAttachment
import com.hermesandroid.relay.network.upstream.GatewayRpcException
import com.hermesandroid.relay.network.upstream.GatewayTurnCallbacks
import com.hermesandroid.relay.network.upstream.HermesApiClient
import com.hermesandroid.relay.network.relay.ProactiveMessage
import com.hermesandroid.relay.network.relay.RelayHttpClient
import com.hermesandroid.relay.network.relay.RealtimeVoiceEvent
import com.hermesandroid.relay.network.upstream.SteerResult
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.shared.LocalDispatchResult
import com.hermesandroid.relay.network.upstream.formatPhoneActionResult
import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.network.upstream.models.SessionItem
import com.hermesandroid.relay.network.upstream.models.SkillInfo
import com.hermesandroid.relay.network.upstream.models.UsageInfo
import com.hermesandroid.relay.notifications.TurnCompleteNotifier
import com.hermesandroid.relay.ui.components.ServerImageResult
import com.hermesandroid.relay.ui.components.SlashCommand
import com.hermesandroid.relay.voice.RealtimeTurnSyncBuilder
import com.hermesandroid.relay.voice.VoiceIntentSyncBuilder
import com.hermesandroid.relay.util.AppContextSettings
import com.hermesandroid.relay.util.AppForegroundTracker
import com.hermesandroid.relay.util.HumanError
import com.hermesandroid.relay.util.MediaCacheWriter
import com.hermesandroid.relay.util.PhoneSnapshot
import com.hermesandroid.relay.util.buildPromptBlock
import com.hermesandroid.relay.util.classifyError
import com.hermesandroid.relay.util.isConnectivityError
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.sse.EventSource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Absolute per-session context-window usage in tokens — the data behind the
 * desktop-style "used / max" context bar. [fraction] is the fill 0..1.
 */
data class ContextWindowUsage(
    val usedTokens: Int,
    val maxTokens: Int,
) {
    val fraction: Float
        get() = if (maxTokens > 0) (usedTokens.toFloat() / maxTokens).coerceIn(0f, 1f) else 0f
}

class ChatViewModel : ViewModel() {

    private var apiClient: HermesApiClient? = null
    private var chatHandler: ChatHandler? = null

    /**
     * The in-flight chat turn, transport-agnostic: SSE turns wrap their
     * [EventSource], gateway turns wrap a `session.interrupt` dispatch.
     * All cancel/teardown sites operate on this handle.
     */
    private var activeStream: ActiveTurnHandle? = null

    /**
     * True when [activeStream] is a GATEWAY turn (vs an SSE EventSource). A
     * gateway turn runs on the gateway client, which survives a same-connection
     * route blip via its own reconnect — so [updateApiClient] (a route handoff
     * that rebuilds the HTTP API client) must NOT cancel it. An SSE turn is
     * bound to the old HTTP client and still must be cancelled there.
     */
    private var activeStreamIsGateway = false
    private var intentionallyCancelled = false

    /**
     * Answer-recovery poller for a sessions-endpoint turn whose SSE transport
     * died while the server kept running the turn (issue #166) — see
     * [ChatStreamRecovery] and [startAnswerRecovery]. At most one per turn;
     * null while idle.
     */
    private var streamRecovery: ChatStreamRecovery? = null

    /** Durable UI checkpoints for recoverable, session-backed turns. */
    private var chatTurnCheckpointStore: ChatTurnCheckpointStore? = null
    private var activeTurnCheckpointSeed: ActiveTurnCheckpointSeed? = null
    private data class TurnCheckpointKey(val contextKey: String, val sessionId: String)
    private val backgroundTurnCheckpoints =
        ConcurrentHashMap<TurnCheckpointKey, ChatTurnCheckpoint>()
    private var checkpointWriteJob: Job? = null
    private var checkpointStatusJob: Job? = null
    private var checkpointForegroundJob: Job? = null
    private var checkpointRecoveryJob: Job? = null
    private var lastCheckpointWriteAtMs = 0L
    private val checkpointGeneration = AtomicLong(0L)
    private val checkpointMutex = Mutex()

    private data class ActiveTurnCheckpointSeed(
        var contextKey: String?,
        var sessionId: String,
        var liveSessionId: String?,
        var transport: String,
        val userMessageId: String,
        val userText: String,
        val userTimestamp: Long,
        var assistantMessageId: String,
        val assistantTimestamp: Long,
        val priorUserMessageCount: Int,
        val baselineAssistantCount: Int,
        val startedAt: Long,
    )

    /** Test seam for the recovery poll cadence — production uses the defaults. */
    internal var recoveryTimingOverride: ChatStreamRecovery.Timing? = null

    private val _recoveringAnswer = MutableStateFlow(false)

    /**
     * True while [streamRecovery] polls for a dropped turn's answer — drives
     * the "Reconnecting to your answer…" copy on the streaming placeholder.
     */
    val recoveringAnswer: StateFlow<Boolean> = _recoveringAnswer.asStateFlow()

    private var firstTokenNotified = false
    private var toolHistoryJob: Job? = null
    private var backgroundProcessSessionJob: Job? = null
    private var connectionSwitchJob: Job? = null
    private var sessionRefreshJob: Job? = null
    private val historyLoadGeneration = AtomicInteger(0)
    private val sessionRefreshGeneration = AtomicInteger(0)
    private val realtimeAgentUserMessages = mutableMapOf<String, String>()
    private val realtimeAgentInputTranscripts = mutableMapOf<String, StringBuilder>()
    private val realtimeAgentProviderBadges = mutableMapOf<String, String>()
    private val realtimeAgentToolCallIds = mutableMapOf<String, MutableSet<String>>()
    private val realtimeAgentHermesBacked = mutableMapOf<String, Boolean>()
    private val realtimeAgentProviderIds = mutableMapOf<String, String>()
    private val realtimeAgentModels = mutableMapOf<String, String>()
    private val realtimeAgentVoices = mutableMapOf<String, String>()
    private val realtimeAgentProgressKeys = mutableMapOf<String, String>()
    /** Background run ownership survives newer persistent-session Voice turns. */
    private val realtimeAgentRunOwners = mutableMapOf<String, String>()
    /** Delivery events omit run_id, so the completed run retains one owner. */
    private var realtimeAgentPendingDeliveryOwner: String? = null
    private val terminalRealtimeAgentTurnIdsLock = Any()
    private val terminalRealtimeAgentTurnIds = LinkedHashSet<String>()
    private var nextInterfaceContextPrompt: String? = null

    // --- Media dependencies (wired via initializeMedia from RelayApp) ---
    private var relayHttpClient: RelayHttpClient? = null
    private var mediaSettingsRepo: MediaSettingsRepository? = null
    private var mediaCacheWriter: MediaCacheWriter? = null
    private var appContext: Context? = null

    /**
     * Marker used in [Attachment.errorMessage] when a fetch is deferred to
     * manual download (cellular + auto-fetch-on-cellular off). The UI uses
     * [Attachment.state] == [AttachmentState.LOADING] plus this exact string
     * to render a "Tap to download" CTA instead of a spinner.
     *
     * Encoded as a plain string rather than a new enum value to keep the data
     * class surface small — the UI already switches on (state, errorMessage)
     * for the FAILED/LOADED cases.
     */
    companion object {
        // === PHASE3-status: APP_CONTEXT_PROMPT removed ===
        // The old static one-liner used to live here. Replaced by the
        // dynamic block built in PhoneStatusPromptBuilder.buildPromptBlock()
        // from `appContextSettings` + a freshly-read PhoneSnapshot. See
        // `send()` below for the construction site.
        // === END PHASE3-status ===
        const val MEDIA_TAP_TO_DOWNLOAD = "Tap to download"

        /** Upper bound on the rolling tool-call history flow. */
        const val TOOL_CALL_HISTORY_LIMIT = 10

        private const val BACKGROUND_TASK_TITLE_LIMIT = 64
        private const val CHECKPOINT_WRITE_INTERVAL_MS = 750L
        private const val MAX_CHECKPOINT_TEXT_CHARS = 200_000
        private const val MAX_CHECKPOINT_TOOL_RESULT_CHARS = 20_000

        /**
         * One-line capability nudge appended to the SSE `system_message` when a
         * relay route is configured, so the agent knows it can surface images/
         * files by absolute server path (the client fetches them over the relay
         * `/media/by-path` channel and renders them inline). SSE-only: the
         * gateway transport has no per-turn system slot, so this can't ride a
         * gateway turn — there the upstream prompt_builder's own `MEDIA:`
         * instruction plus the client-side render fallback cover it.
         */
        const val RELAY_MEDIA_HINT =
            "Media display: this client can render images and files you reference by " +
                "absolute server path. To show one to the user, put its absolute path on " +
                "its own line as `MEDIA:/absolute/path`, or use a markdown image " +
                "`![description](/absolute/path)`. The client fetches it over the secure " +
                "relay channel and shows it inline."
    }

    /** Callback to persist session ID — set by RelayApp */
    var onSessionChanged: ((String?) -> Unit)? = null

    /**
     * Send a user message into an agent **Thread** (a `source=phone` session)
     * over the relay proactive channel instead of the normal chat transport —
     * set by RelayApp to [ConnectionViewModel.sendProactiveReply]. `(text,
     * chatId, replyTo, messageId)`; `messageId` is the user bubble's id so the
     * relay's ack can settle it. Null when no relay/ConnectionViewModel is wired.
     */
    var onProactiveReply: ((String, String?, String?, String) -> Unit)? = null

    /**
     * A "+ New Thread" the user just created + named, before its first message
     * is sent. The first send routes to [PendingThread.chatId] (which makes the
     * gateway create the `source=phone` session keyed by it); we then poll for +
     * switch to that real session and apply the chosen name.
     */
    private data class PendingThread(val chatId: String, val name: String)
    private var pendingThread: PendingThread? = null

    /**
     * A "+ New Thread" whose first message has been sent — we're now polling for
     * the gateway-created `source=phone` session to switch to it. [knownIds] is
     * the set of phone-session ids that existed BEFORE the send, so the new one
     * is found by *difference* (the session `id` is a timestamp and the sessions
     * API exposes neither `chat_id` nor `session_key`, so we can't match by id).
     */
    private data class CreatingThread(
        val chatId: String,
        val name: String,
        val knownIds: Set<String>,
    )
    private var creatingThread: CreatingThread? = null

    /**
     * `sessionId` → phone-platform `chat_id`, learned for threads this app
     * created ([switchToCreatedThread]) or received a message in
     * ([injectThreadMessage]). Routes a reply to the right thread, since the
     * sessions API doesn't return `chat_id`. Unknown → null → the relay/adapter's
     * home channel ("phone"). In-memory (lost on restart) — the proper fix
     * exposes `chat_id` on `/api/sessions` upstream (see TODO).
     */
    private val threadChatIds = mutableMapOf<String, String>()

    /**
     * Persist a user-chosen Thread name (sessionId → name) — set by RelayApp to
     * [com.hermesandroid.relay.viewmodel.ConnectionViewModel.saveThreadName].
     * Null when no relay/ConnectionViewModel is wired.
     */
    var onSaveThreadName: ((String, String) -> Unit)? = null

    /**
     * Latest persisted Thread names, re-applied to the handler on every load and
     * on [initialize] so a handler created after the DataStore load still picks
     * them up (the user's name overrides the gateway auto-title in the drawer).
     */
    private var persistedThreadNames: Map<String, String> = emptyMap()

    fun applyPersistedThreadNames(names: Map<String, String>) {
        persistedThreadNames = names
        chatHandler?.setUserThreadNames(names)
    }

    /**
     * Seed the reply-routing map from the relay's `/phone/threads` (the
     * `session_id → chat_id` the API omits). Authoritative over the in-memory
     * learned map, so any Thread routes its replies to the right conversation —
     * including one this app didn't create, or any Thread after a restart.
     */
    fun seedThreadChatIds(map: Map<String, String>) {
        threadChatIds.putAll(map)
    }

    // --- Human-readable error events ---
    // One-shot events consumed by ChatScreen via snackbar. Shape mirrors
    // other VMs for consistency; DROP_OLDEST so a burst of errors never
    // stalls the emitter.
    private val _errorEvents = MutableSharedFlow<HumanError>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errorEvents: SharedFlow<HumanError> = _errorEvents.asSharedFlow()

    private fun emitError(t: Throwable?, context: String?) {
        val human = classifyError(t, context = context, ctx = appContext)
        // Cold-start / reconnect bootstrap (session-list load, session create)
        // runs without the user asking and on every reconnect. A "can't reach
        // the server" failure there is non-actionable noise — the themed
        // connection banner + startup sphere already surface the unreachable
        // state. Keep the diagnostics record (classifyError above) but suppress
        // the redundant, scary "server isn't accepting connections" snackbar
        // that used to flash from the bottom on first load. Actionable failures
        // (auth rejected, server error) and all interactive contexts
        // (send_message, …) still surface normally.
        if ((context == "load_sessions" || context == "create_session") &&
            isConnectivityError(t)
        ) {
            return
        }
        _errorEvents.tryEmit(human)
    }

    // --- Message queue ---
    private val _queuedMessages = MutableStateFlow<List<String>>(emptyList())
    val queuedMessages: StateFlow<List<String>> = _queuedMessages.asStateFlow()

    // --- Recent prompts (mobile-friendly history recall — no physical up-arrow
    // on a soft keyboard, so the composer surfaces these as tappable chips) ---
    private val _recentPrompts = MutableStateFlow<List<String>>(emptyList())
    val recentPrompts: StateFlow<List<String>> = _recentPrompts.asStateFlow()

    private fun recordRecentPrompt(text: String) {
        val t = text.trim()
        if (t.isBlank() || t.startsWith("/")) return // skip blanks + slash commands
        _recentPrompts.update { prev ->
            (listOf(t) + prev.filterNot { it == t }).take(RECENT_PROMPTS_LIMIT)
        }
    }

    /**
     * Recent tool-call timeline for the Stats-for-Nerds + Timeline views.
     *
     * Derived from [ChatHandler.messages] once [initialize] runs. We keep
     * the last [TOOL_CALL_HISTORY_LIMIT] tool calls across ALL assistant
     * messages in the current session so the stats panel can show a
     * stable rolling window even as the chat scrolls past older turns.
     *
     * Updated on every [messages] emission — each update is O(N * T) in
     * messages × tool-calls-per-message, but T is small and the whole
     * recomputation is cheap compared to the Compose recomposition pass
     * that reads this flow anyway. No separate per-event dispatcher is
     * needed.
     */
    private val _toolCallHistory =
        MutableStateFlow<List<ToolCallEvent>>(emptyList())
    val toolCallHistory: StateFlow<List<ToolCallEvent>> =
        _toolCallHistory.asStateFlow()

    // --- Pending attachments ---
    private val _pendingAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    val pendingAttachments: StateFlow<List<Attachment>> = _pendingAttachments.asStateFlow()

    fun addAttachment(attachment: Attachment) {
        _pendingAttachments.update { it + attachment }
    }

    fun removeAttachment(index: Int) {
        _pendingAttachments.update { list ->
            list.filterIndexed { i, _ -> i != index }
        }
    }

    fun clearAttachments() {
        _pendingAttachments.value = emptyList()
    }

    // Server-side personality selection
    private val _selectedPersonality = MutableStateFlow("default")
    val selectedPersonality: StateFlow<String> = _selectedPersonality.asStateFlow()

    private val _personalityNames = MutableStateFlow<List<String>>(emptyList())
    val personalityNames: StateFlow<List<String>> = _personalityNames.asStateFlow()

    /** Default personality name from server (config.display.personality) */
    private val _defaultPersonality = MutableStateFlow("")
    val defaultPersonality: StateFlow<String> = _defaultPersonality.asStateFlow()

    /** Personality name → system prompt. Used to send the right prompt when switching. */
    private var personalityPrompts: Map<String, String> = emptyMap()

    /** Flat model ids from `GET /v1/models` (SSE fallback source; gateway uses [modelProviders]). */
    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    /**
     * Curated provider→model groups from the gateway `model.options` RPC — the
     * SAME source the upstream desktop/TUI picker uses (grok / kimi / gpt-5.5 …
     * grouped by authenticated provider). The api_server `/v1/models` only
     * returns a generic agent alias, so on the gateway transport THIS is the
     * real switchable-model list.
     */
    private val _modelProviders = MutableStateFlow<List<GatewayModelProvider>>(emptyList())
    val modelProviders: StateFlow<List<GatewayModelProvider>> = _modelProviders.asStateFlow()

    /** True only during an explicit user-requested dynamic model catalog refresh. */
    private val _modelOptionsRefreshing = MutableStateFlow(false)
    val modelOptionsRefreshing: StateFlow<Boolean> = _modelOptionsRefreshing.asStateFlow()

    /** Current gateway model from `model.options`, used when no Android override is active. */
    private val _gatewayCurrentModel = MutableStateFlow("")
    val gatewayCurrentModel: StateFlow<String> = _gatewayCurrentModel.asStateFlow()

    /** Current gateway provider slug from `model.options`, paired with [gatewayCurrentModel]. */
    private val _gatewayCurrentProvider = MutableStateFlow("")
    val gatewayCurrentProvider: StateFlow<String> = _gatewayCurrentProvider.asStateFlow()

    /**
     * Gateway reasoning effort for this session's agent config; null = UNKNOWN
     * (not yet confirmed by `session.info`, or reset on a profile/connection
     * switch). Honesty contract, same as [_yoloEnabled]/[_fastEnabled]: the chip
     * shows a default while null and `session.create` OMITS `reasoning_effort`
     * when null so the new profile's own default wins (rather than the previous
     * profile's effort leaking in). A non-null value is an explicit/confirmed
     * pick that rides the next `session.create` as a per-session override.
     */
    private val _selectedReasoningEffort = MutableStateFlow<String?>(null)
    val selectedReasoningEffort: StateFlow<String?> = _selectedReasoningEffort.asStateFlow()

    private val _reasoningDisplay = MutableStateFlow<String?>(null)

    /** Effective YOLO (approval-bypass) state for the active gateway session; null = unknown. */
    private val _yoloEnabled = MutableStateFlow<Boolean?>(null)
    val yoloEnabled: StateFlow<Boolean?> = _yoloEnabled.asStateFlow()

    /** Fast-mode (priority tier) state for the active gateway session; null = unknown. */
    private val _fastEnabled = MutableStateFlow<Boolean?>(null)
    val fastEnabled: StateFlow<Boolean?> = _fastEnabled.asStateFlow()

    /**
     * Pending YOLO pick made BEFORE a live session exists (brand-new chat).
     * Upstream `session.create` does NOT accept yolo as a per-session override,
     * and a sessionless `config.set yolo` writes `os.environ["HERMES_YOLO_MODE"]`
     * PROCESS-GLOBALLY (server.py:7562-7569) — leaking approval-bypass to every
     * other session. So the pick is stashed here, the global write is skipped,
     * and it is applied session-scoped once the first send creates the session
     * (see [applyPendingYoloAfterSessionCreate]). Main-thread only. Cleared on
     * consume + every profile/connection/chat switch alongside [_yoloEnabled].
     */
    private var pendingYolo: Boolean? = null

    /**
     * Refresh the gateway's curated provider/model list (`model.options`).
     * Connects the gateway on demand. No-op without a gateway client.
     * [refresh] is the explicit upstream refresh path for dynamic/custom-provider catalogs;
     * automatic picker opens stay on the cheap cached path.
     */
    fun refreshModelOptions(refresh: Boolean = false) {
        val gateway = gatewayClient ?: run {
            android.util.Log.i("ChatViewModel", "refreshModelOptions: no gateway client")
            if (refresh) _modelOptionsRefreshing.value = false
            return
        }
        if (refresh && _modelOptionsRefreshing.value) return
        if (refresh) _modelOptionsRefreshing.value = true
        viewModelScope.launch {
            gateway.modelOptions(refresh = refresh).fold(
                onSuccess = {
                    _modelProviders.value = it.providers
                    _gatewayCurrentModel.value = it.currentModel
                    _gatewayCurrentProvider.value = it.currentProvider
                    android.util.Log.i(
                        "ChatViewModel",
                        "model.options${if (refresh) " refresh" else ""}: ${it.providers.size} providers, " +
                            "${it.providers.sumOf { p -> p.models.size }} models, current=${it.currentModel}",
                    )
                },
                onFailure = {
                    android.util.Log.w("ChatViewModel", "model.options failed: ${it.message}")
                    if (refresh) {
                        _transientNotice.tryEmit("Couldn't refresh models: ${it.message ?: "unknown error"}")
                    }
                },
            )
            if (refresh) _modelOptionsRefreshing.value = false
        }
    }

    /** Refresh the gateway reasoning effort backing the compact input control. */
    fun refreshReasoningSettings() {
        val gateway = gatewayClient ?: run {
            android.util.Log.i("ChatViewModel", "refreshReasoningSettings: no gateway client")
            return
        }
        viewModelScope.launch {
            gateway.getReasoningSettings().fold(
                onSuccess = {
                    _selectedReasoningEffort.value = normalizeReasoningEffort(it.effort)
                    _reasoningDisplay.value = it.display
                },
                onFailure = {
                    android.util.Log.w("ChatViewModel", "config.get reasoning failed: ${it.message}")
                },
            )
        }
    }

    /**
     * User's explicit model pick from the in-chat picker, or null = "use the
     * profile's model / server default". Wins over the profile model on SSE
     * turns; on the gateway it's applied immediately via a `/model` dispatch
     * (the gateway carries no per-turn model field). Session-scoped, like the
     * gateway's own `/model`.
     */
    private val _selectedModelOverride = MutableStateFlow<String?>(null)
    val selectedModelOverride: StateFlow<String?> = _selectedModelOverride.asStateFlow()

    /**
     * Authenticated provider slug (e.g. `xai`) that pairs with
     * [_selectedModelOverride] on the gateway. Needed so a fresh chat's
     * `session.create` can carry `provider` alongside `model` (see
     * [GatewayChatClient.sessionModelProvider]); SSE turns resolve the provider
     * server-side from the model, so they don't read this. Cleared whenever the
     * model override is cleared.
     */
    private val _selectedProviderOverride = MutableStateFlow<String?>(null)

    fun fetchModels() {
        val client = apiClient ?: return
        viewModelScope.launch { _availableModels.value = client.getModels() }
    }

    /**
     * Switch the active model from the in-chat picker. On the gateway this
     * dispatches `/model <model> --provider <slug>` (matching the desktop
     * picker — `--provider` selects the authenticated provider) and surfaces
     * the model-info confirmation card; on SSE the override rides the next
     * turn's request body. Pass null to clear back to the profile / server
     * default.
     */
    fun selectModel(model: String?, provider: String? = null) {
        _selectedModelOverride.value = AgentDisplay.requestModelName(model)
        // Keep the provider paired with the model pick so a fresh chat's
        // session.create can bind both (gateway needs the provider to resolve
        // the authenticated account; e.g. grok-4.3 via `xai`). Cleared when the
        // model is cleared so the next new session falls back to the default.
        _selectedProviderOverride.value =
            provider?.takeIf { it.isNotBlank() && !model.isNullOrBlank() }
        val gateway = gatewayClient
        val handler = chatHandler
        if (model.isNullOrBlank()) {
            if (streamingEndpoint == "gateway" && gateway != null && handler != null) {
                // Never send a generic agent alias ("hermes-agent") as a model —
                // the server rejects it (HTTP 400 → fallback). Resolving to null
                // here skips the setModel below, leaving the gateway on its true
                // server-configured default.
                val defaultModel = AgentDisplay.requestModelName(
                    (effectiveProfileProvider() ?: selectedProfileProvider())?.model
                        ?: _serverModelName.value,
                )
                if (!defaultModel.isNullOrBlank()) {
                    viewModelScope.launch {
                        // Await a live session so the reset is session-scoped; if
                        // there is none, the cleared override already resolves to
                        // the server default on the next session.create — skip the
                        // global config.set.
                        if (!gateway.prewarmAwait(handler.currentSessionId.value)) {
                            refreshActiveAgentName()
                            return@launch
                        }
                        gateway.setModel(defaultModel).fold(
                            onSuccess = { result ->
                                val resolved = result.stringValue("value") ?: defaultModel
                                val warning = result.stringValue("warning")?.takeIf { it.isNotBlank() }
                                _gatewayCurrentModel.value = resolved
                                _gatewayCurrentProvider.value = ""
                                // Pill update is the confirmation — no bubble.
                                // Surface only a server warning, ephemerally.
                                warning?.let { _transientNotice.tryEmit(it) }
                                gateway.modelOptions().onSuccess {
                                    _modelProviders.value = it.providers
                                    _gatewayCurrentModel.value = it.currentModel
                                    _gatewayCurrentProvider.value = it.currentProvider
                                }
                            },
                            onFailure = { e ->
                                _transientNotice.tryEmit(
                                    "Couldn't switch to server default model: ${e.message ?: "unknown error"}",
                                )
                            },
                        )
                    }
                }
            }
            refreshActiveAgentName()
            return
        }
        if (streamingEndpoint == "gateway" && gateway != null && handler != null) {
            // Defensive: never push a generic agent alias ("hermes-agent") as a
            // model — it's not a real model and the server 400s on it.
            val sendModel = AgentDisplay.requestModelName(model)
            if (sendModel == null) {
                refreshActiveAgentName()
                return
            }
            // Upstream model-switch flag string: `<model> [--provider <slug>]`.
            val value = if (!provider.isNullOrBlank()) "$sendModel --provider $provider" else sendModel
            viewModelScope.launch {
                // Resolve the live session FIRST (suspending), so the switch is
                // applied SESSION-SCOPED. A fresh chat pre-creates a session, so
                // racing the fire-and-forget prewarm made config.set run with no
                // session_id — a GLOBAL write that never reached the turn's
                // session, leaving it on the server default.
                val hasSession = gateway.prewarmAwait(handler.currentSessionId.value)
                if (!hasSession) {
                    // Genuinely sessionless (brand-new chat, no stored session):
                    // skip the global config.set. The pick is held in
                    // _selectedModelOverride and rides the next session.create as
                    // a per-session override (sessionModelProvider / ensureSession).
                    refreshActiveAgentName()
                    return@launch
                }
                // Dispatch via config.set (the `_apply_model_switch` path) — NOT
                // the `/model` slash path, whose command.dispatch fallback
                // reports a spurious "not a quick/plugin/skill command" failure.
                gateway.setModel(value).fold(
                    onSuccess = { result ->
                        val resolved = result.stringValue("value") ?: model
                        val warning = result.stringValue("warning")?.takeIf { it.isNotBlank() }
                        _gatewayCurrentModel.value = resolved
                        _gatewayCurrentProvider.value = provider.orEmpty()
                        // Pill update is the confirmation — no system bubble.
                        // Surface only a server warning, ephemerally.
                        warning?.let { _transientNotice.tryEmit(it) }
                        gateway.modelOptions().onSuccess {
                            _modelProviders.value = it.providers
                            _gatewayCurrentModel.value = it.currentModel
                            _gatewayCurrentProvider.value = it.currentProvider
                        }
                    },
                    onFailure = { e ->
                        _transientNotice.tryEmit("Couldn't switch model: ${e.message ?: "unknown error"}")
                    },
                )
            }
        }
        refreshActiveAgentName()
    }

    /**
     * Switch the gateway reasoning effort for this session through `config.set`.
     * The chip owns optimistic UI; failures are surfaced in chat.
     *
     * For a brand-new chat (no live session yet) the `config.set` is SKIPPED:
     * upstream applies a sessionless reasoning write to GLOBAL config
     * (`_write_config_key`, server.py:7619). The optimistic value set here
     * instead rides the next `session.create` as a per-session
     * `reasoning_effort` override (see [updateGatewayClient]'s
     * sessionModelProvider) — mirroring how [selectModel] defers to
     * `session.create` for a fresh chat.
     */
    fun selectReasoningEffort(value: String) {
        val normalized = normalizeReasoningEffort(value)
        _selectedReasoningEffort.value = normalized
        val gateway = gatewayClient ?: return
        val handler = chatHandler
        if (streamingEndpoint != "gateway" || handler == null) return
        if (!hasLiveGatewaySession()) return
        viewModelScope.launch {
            gateway.prewarm(handler.currentSessionId.value)
            gateway.setReasoning(normalized).fold(
                onSuccess = { result ->
                    _selectedReasoningEffort.value = normalizeReasoningEffort(
                        result.stringValue("value") ?: normalized,
                    )
                },
                onFailure = { e ->
                    handler.addSystemNotice(
                        "Couldn't switch reasoning effort: ${e.message ?: "unknown error"}",
                    )
                    refreshReasoningSettings()
                },
            )
        }
    }

    /**
     * Toggle per-session approval bypass (YOLO). Session-scoped + ephemeral the
     * way the desktop does it — never persists global auto-approve. Optimistic;
     * rolls back + notices on failure. The effective state also tracks live via
     * `session.info` ([startGatewayStateSync]). Gateway-only.
     */
    fun setYolo(enabled: Boolean) {
        val previous = _yoloEnabled.value
        _yoloEnabled.value = enabled
        val gateway = gatewayClient ?: run { _yoloEnabled.value = previous; return }
        val handler = chatHandler
        if (streamingEndpoint != "gateway" || handler == null) {
            _yoloEnabled.value = previous
            return
        }
        if (!hasLiveGatewaySession()) {
            // Brand-new chat: a sessionless `config.set yolo` writes
            // os.environ["HERMES_YOLO_MODE"] PROCESS-GLOBALLY (server.py:7562),
            // leaking approval-bypass into every other session. Upstream
            // session.create can't carry yolo either, so keep the optimistic UI
            // value, stash the pick, and apply it session-scoped once the first
            // send creates the session.
            pendingYolo = enabled
            return
        }
        pendingYolo = null
        viewModelScope.launch {
            // The session-scoped flag needs a live session — warm it first.
            gateway.prewarm(handler.currentSessionId.value)
            // A session/connection switch during the (possibly slow) prewarm may
            // have swapped the client or nulled the state — don't write stale.
            if (gatewayClient !== gateway) return@launch
            gateway.setYolo(enabled).fold(
                onSuccess = { _yoloEnabled.value = it },
                onFailure = { e ->
                    // Only roll back if we still own the optimistic value (a
                    // mid-flight switch may have already nulled it).
                    if (_yoloEnabled.value == enabled) _yoloEnabled.value = previous
                    handler.addSystemNotice(
                        "Couldn't ${if (enabled) "enable" else "disable"} YOLO: ${e.message ?: "gateway error"}",
                    )
                },
            )
        }
    }

    /**
     * Toggle fast mode (priority service tier). Optimistic; rolls back + notices
     * on failure — including the upstream capability gate (a model with no fast
     * tier rejects it). Gateway-only; live state tracks via `session.info`.
     */
    fun setFast(enabled: Boolean) {
        val previous = _fastEnabled.value
        _fastEnabled.value = enabled
        val gateway = gatewayClient ?: run { _fastEnabled.value = previous; return }
        val handler = chatHandler
        if (streamingEndpoint != "gateway" || handler == null) {
            _fastEnabled.value = previous
            return
        }
        if (!hasLiveGatewaySession()) {
            // Brand-new chat: a sessionless `config.set fast` is a GLOBAL write
            // upstream (_write_config_key, server.py:7446). Keep the optimistic
            // value — it rides the next session.create as a per-session `fast`
            // override (priority service tier), not a global mutation.
            return
        }
        viewModelScope.launch {
            gateway.prewarm(handler.currentSessionId.value)
            if (gatewayClient !== gateway) return@launch
            gateway.setFast(enabled).fold(
                onSuccess = { _fastEnabled.value = it },
                onFailure = { e ->
                    if (_fastEnabled.value == enabled) _fastEnabled.value = previous
                    handler.addSystemNotice(
                        "Couldn't switch fast mode: ${e.message ?: "gateway error"}",
                    )
                },
            )
        }
    }

    /**
     * True when there is a live (or resumable) gateway session that a
     * `config.set` would target. A brand-new chat has no session id yet, so a
     * `config.set` there runs SESSIONLESS — which upstream applies as a GLOBAL
     * write (reasoning/fast → `_write_config_key`; yolo → process-global
     * `os.environ`). Callers defer such writes to the next `session.create`
     * (reasoning/fast) or to [applyPendingYoloAfterSessionCreate] (yolo).
     */
    private fun hasLiveGatewaySession(): Boolean =
        !chatHandler?.currentSessionId?.value.isNullOrBlank()

    /**
     * Apply a YOLO pick that was made before this chat had a session. Fired from
     * the gateway turn's `onSessionId` (the first send just created the
     * session), so the `config.set yolo` is now SESSION-SCOPED — never the
     * global env leak the brand-new-chat path deliberately skipped. Best-effort:
     * it races the turn's first tool, but it can no longer cross into other
     * sessions, which is the bug being fixed.
     */
    private fun applyPendingYoloAfterSessionCreate() {
        val pending = pendingYolo ?: return
        pendingYolo = null
        val gateway = gatewayClient ?: return
        if (streamingEndpoint != "gateway") return
        viewModelScope.launch {
            if (gatewayClient !== gateway) return@launch
            gateway.setYolo(pending).fold(
                onSuccess = { _yoloEnabled.value = it },
                onFailure = { e ->
                    chatHandler?.addSystemNotice(
                        "Couldn't apply YOLO to the new chat: ${e.message ?: "gateway error"}",
                    )
                },
            )
        }
    }

    /**
     * Switch the active Hermes profile from the in-chat picker. Verified against
     * upstream `tui_gateway`: a profile is a FULL agent (its own HERMES_HOME/db,
     * model, SOUL, personality, skills), sessions are PROFILE-BOUND, and the
     * agent is built once at `session.create` from the session's profile — a
     * live session never adopts a new profile (which is why the header read
     * "Gary" while the running agent still answered "Victor"). There is no
     * profile-switch RPC; the desktop passes `profile` on `session.create` /
     * `session.resume`. So: bind the new profile for the next session and start
     * a FRESH chat — the next turn's `session.create` carries it, building the
     * new profile's agent. SSE turns already carry the profile per-request as
     * `profileName`. [profile] = the new pick; null = the default profile.
     */
    fun activateGatewayProfile(profile: Profile?) {
        val gateway = gatewayClient ?: return
        if (streamingEndpoint != "gateway") return
        // A profile switch is a UI/context detach, not a Stop action. Preserve
        // an in-flight upstream turn and reconcile it into its original durable
        // session when it finishes, while freeing this client to bind the newly
        // selected profile immediately.
        gateway.backgroundActiveTurn()
        // Mirror the desktop's hot-swap: switching the SELECTED profile (done by
        // the sheet's selectProfile call just before this) already drives
        // RelayApp's profile-context switch, which detaches an in-flight Gateway
        // turn and resets the thread (fresh draft, or the new profile's last session). We
        // must NOT also createNewChat() here — that second reset races the context
        // switch (the "reply typing, then a new chat appears" jank). All we do is
        // drop the live gateway session so the NEXT turn's session.create rebinds
        // the agent to the new profile (pulled live via sessionProfileProvider),
        // like the desktop spawning the profile's backend lazily on the next send.
        gateway.clearSession()
        // The selected profile changes before RelayApp's coalesced context
        // switch lands. Clear the old profile's process snapshot immediately
        // so it can never linger under the new profile header.
        selectBackgroundProcessSession(null)
        // Session-scoped YOLO/Fast/reasoning + the personality overlay don't
        // carry across the profile's fresh session — null them so a stale flag
        // or persona can't show (or be injected) until the new session.info
        // re-seeds. Same discipline for all four; the collectors reconcile on
        // the first turn.
        _yoloEnabled.value = null
        _fastEnabled.value = null
        pendingYolo = null
        // Reset the reasoning chip to UNKNOWN rather than optimistically fetching
        // it: a sessionless config.get right after clearSession reads the
        // LAUNCH/global profile's effort, not the newly-selected profile's. Let
        // session.info confirm it on the first turn (see the dropped
        // getReasoningSettings below).
        _selectedReasoningEffort.value = null
        _reasoningDisplay.value = null
        // The personality overlay is per-profile. On SSE, leaving a stale pick
        // here would inject the previous profile's overlay onto the new
        // profile's first turn (composeInjectedContext); reset to default and let
        // applyServerPersonality / the serverPersonality collector re-seed.
        _selectedPersonality.value = "default"
        // A profile defines its own model, so switching profiles retires any
        // explicit in-chat model pick — otherwise the old pick would leak onto
        // the new profile's sessions and the picker pill would disagree with
        // what the agent actually runs. The next session.create then binds the
        // profile's own model.
        _selectedModelOverride.value = null
        _selectedProviderOverride.value = null
        // Optimistically seed the picker "current" from the profile's own model
        // so the header/status strip switch instantly instead of showing the
        // previous profile's model until the modelOptions round-trip lands; the
        // round-trip below confirms/corrects it with server truth.
        profile?.model?.takeIf { it.isNotBlank() }?.let {
            _gatewayCurrentModel.value = it
            _gatewayCurrentProvider.value = ""
        }
        // The profile brings its own model — refresh the picker's "current".
        // Reasoning effort is intentionally NOT fetched here: a sessionless
        // config.get would read the launch/global profile's effort (wrong
        // scope). It's left unknown above and confirmed by session.info on the
        // first turn — the same honesty discipline yolo/fast use.
        viewModelScope.launch {
            gateway.modelOptions().onSuccess {
                _modelProviders.value = it.providers
                _gatewayCurrentModel.value = it.currentModel
                _gatewayCurrentProvider.value = it.currentProvider
            }
        }
        refreshActiveAgentName()
    }

    /** Model name from the server's /api/config response (e.g. "claude-opus-4-6") */
    private val _serverModelName = MutableStateFlow("")
    val serverModelName: StateFlow<String> = _serverModelName.asStateFlow()

    // === PHASE3-status: dynamic app-context settings ===
    /**
     * Granular phone-status settings. Written from RelayApp via a collect
     * on the five `appContext*` StateFlows in ConnectionViewModel. Read on
     * every send() to build the system-prompt block.
     *
     * Defaults match ConnectionViewModel's default values — master on,
     * bridgeState on, currentApp/battery off (privacy), safetyStatus on.
     */
    var appContextSettings: AppContextSettings = AppContextSettings()
    // === END PHASE3-status ===

    // Declared before [streamingEndpoint] so its setter can safely touch the
    // backing field on first assignment (Kotlin initializers bypass the setter,
    // but ordering it first removes any doubt).
    private val _serverAutoTitles = MutableStateFlow(false)

    /**
     * Whether the active chat transport auto-generates session titles on the
     * server. True only for the gateway (`/api/ws`) path. Drives the subtle
     * "chats aren't auto-named here" hint in the session drawer.
     */
    val serverAutoTitles: StateFlow<Boolean> = _serverAutoTitles.asStateFlow()

    /**
     * Streaming endpoint to use for the next chat turn. Always one of
     * "sessions", "completions", or "runs" — never "auto", since the auto-resolver in
     * ConnectionViewModel.resolveStreamingEndpoint() collapses "auto" to
     * a concrete value before this field is written from RelayApp.
     *
     * Defaults to "completions" so that a fresh ChatViewModel (before
     * RelayApp pushes the resolved value) prefers an EventSource-compatible
     * OpenAI chat path instead of assuming `/v1/runs` is an SSE stream.
     */
    var streamingEndpoint: String = "completions"
        set(value) {
            field = value
            // Only the gateway transport auto-names sessions server-side
            // (tui_gateway runs the turn in a HermesCLI child that calls
            // agent.title_generator.maybe_auto_title). The api_server SSE/runs/
            // completions surfaces never do — see ChatHandler.updateSessions
            // and the drawer note. Mirror the capability so the UI can explain
            // why chats stay untitled on those transports (issue #133).
            _serverAutoTitles.value = value == "gateway"
        }

    /**
     * SSE endpoint used when a "gateway" turn can't run (gateway unreachable,
     * sign-in expired, attachments present). Wired from RelayApp alongside
     * [streamingEndpoint] as the capability-resolved SSE preference; never
     * "auto" or "gateway".
     */
    var sseFallbackEndpoint: String = "completions"

    /**
     * Gateway chat transport (dashboard `/api/ws` — live thinking). Owned and
     * rebuilt by ConnectionViewModel; this VM only dispatches turns on it.
     */
    private var gatewayClient: GatewayChatClient? = null
    private var gatewayHistoryReconcileJob: Job? = null
    private var gatewayProcessSource: GatewayProcessSource? = null
    private val gatewayProcessController = GatewayProcessController(viewModelScope)

    /** Session-scoped upstream shell processes shown beside the composer. */
    val backgroundProcesses: StateFlow<List<GatewayProcess>> = gatewayProcessController.processes

    /** Feature-detection state for gateways older than the process RPC surface. */
    val backgroundProcessCapability: StateFlow<GatewayProcessCapability> =
        gatewayProcessController.capability

    val backgroundProcessesLoading: StateFlow<Boolean> = gatewayProcessController.loading
    val stoppingProcessIds: StateFlow<Set<String>> = gatewayProcessController.stoppingProcessIds

    fun refreshBackgroundProcesses() {
        gatewayProcessController.refresh()
    }

    fun stopBackgroundProcess(processId: String) {
        gatewayProcessController.stop(processId) { message ->
            _transientNotice.tryEmit(message)
        }
    }

    fun dismissBackgroundProcess(processId: String) {
        gatewayProcessController.dismiss(processId)
    }

    fun updateGatewayClient(client: GatewayChatClient?) {
        val previousClient = gatewayClient
        val changed = previousClient !== client
        if (changed) {
            previousClient?.setUnsolicitedTurnProvider(null)
            previousClient?.setColdPrewarmSessionReadyListener(null)
            previousClient?.setUnmatchedTurnCompleteListener(null)
        }
        gatewayClient = client
        if (changed) {
            gatewayProcessSource = client?.let(::GatewayChatProcessSource)
            gatewayProcessController.bind(
                newSource = gatewayProcessSource,
                sessionId = chatHandler?.currentSessionId?.value,
                scopeKey = activeProfileContextKey,
            )
        }
        // Bind each gateway session.create/resume to the currently-selected
        // profile (pulled live) — the upstream gateway builds the agent from it.
        client?.sessionProfileProvider = { AgentDisplay.profileRequestName(selectedProfileProvider()?.name) }
        // Bind the in-chat picks onto each fresh session.create so a brand-new
        // chat actually runs on the picked model/provider AND the chosen
        // reasoning effort / fast tier (not the global default) — and so setting
        // effort/fast before the first message rides session.create instead of a
        // sessionless config.set (a GLOBAL write upstream). Pulled live; each
        // field null when not explicitly set, so the profile/server default
        // wins. Mid-session switches still go through setModel/setReasoning/
        // setFast (config.set). yolo is intentionally absent — upstream
        // session.create doesn't accept it (applied post-create instead).
        client?.sessionModelProvider = {
            val model = _selectedModelOverride.value?.takeIf { it.isNotBlank() }
            val provider = _selectedProviderOverride.value?.takeIf { it.isNotBlank() }
            val effort = _selectedReasoningEffort.value?.takeIf { it.isNotBlank() }
            // Only pin fast when explicitly ON (upstream treats fast=false the
            // same as omitting → profile default tier), so a null/false leaves
            // the profile's own service tier intact.
            val fast = _fastEnabled.value?.takeIf { it }
            if (model != null || effort != null || fast != null) {
                GatewaySessionModel(
                    model = model,
                    provider = provider,
                    reasoningEffort = effort,
                    fast = fast,
                )
            } else {
                null
            }
        }
        client?.setUnsolicitedTurnProvider { storedSessionId ->
            createGatewayInboundTurnRegistration(client, storedSessionId)
        }
        client?.setColdPrewarmSessionReadyListener { storedSessionId ->
            scheduleGatewayHistoryReconcile(storedSessionId)
            if (
                gatewayClient === client &&
                chatHandler?.currentSessionId?.value == storedSessionId
            ) {
                gatewayProcessController.sessionReady(storedSessionId)
            }
        }
        client?.setUnmatchedTurnCompleteListener { completion ->
            settleBackgroundTurnCheckpoint(completion)
            scheduleGatewayHistoryReconcile(
                storedSessionId = completion.storedSessionId,
                expectedAssistantText = completion.expectedAssistantText,
            )
        }
        // (Re)bind the session.info state sync to the live client so server-side
        // personality/model changes drive the UI. Cancelled when the client drops.
        if (changed) {
            gatewayStateSyncJob?.cancel()
            gatewayStateSyncJob = null
            client?.let { startGatewayStateSync(it) }
        }
        when {
            client == null -> {
                _serverCommands.value = emptyList()
                _modelProviders.value = emptyList()
                _gatewayCurrentModel.value = ""
                _gatewayCurrentProvider.value = ""
                // null = unknown (honest); refreshReasoningSettings/session.info
                // re-seed it. Never a stale default that could ride session.create.
                _selectedReasoningEffort.value = null
                _reasoningDisplay.value = null
            }
            // Catalog fetch must never cold-open /api/ws — only fetch over an
            // already-ready socket. Otherwise the first completed gateway
            // turn populates it (see onCompleteCb in startStream).
            client.connectionState.value == GatewayConnectionState.Ready &&
                (changed || _serverCommands.value.isEmpty()) -> {
                fetchServerCommands(client)
                refreshModelOptions()
                refreshReasoningSettings()
                // Seed the active personality over the already-ready socket so the
                // picker reflects server truth without waiting for the first turn.
                seedServerPersonality(client)
            }
            changed -> {
                _serverCommands.value = emptyList()
                _modelProviders.value = emptyList()
                _gatewayCurrentModel.value = ""
                _gatewayCurrentProvider.value = ""
                // null = unknown (honest); refreshReasoningSettings/session.info
                // re-seed it. Never a stale default that could ride session.create.
                _selectedReasoningEffort.value = null
                _reasoningDisplay.value = null
            }
        }
        if (changed && client != null && streamRecovery != null &&
            AppForegroundTracker.isForeground.value
        ) {
            prewarmGateway()
        }
    }

    /** Remove the detached sibling's recovery snapshot after server completion. */
    private fun settleBackgroundTurnCheckpoint(completion: GatewayBackgroundTurnCompletion) {
        val profileKey = AgentDisplay.profileSessionKey(completion.profile)
        val matching = backgroundTurnCheckpoints.keys.filter { key ->
            key.sessionId == completion.storedSessionId &&
                key.contextKey.substringAfterLast("::") == profileKey
        }
        if (matching.isEmpty()) return
        matching.forEach(backgroundTurnCheckpoints::remove)
        chatTurnCheckpointStore?.let { store ->
            viewModelScope.launch {
                checkpointMutex.withLock {
                    matching.forEach { key ->
                        runCatching { store.remove(key.contextKey, key.sessionId) }
                    }
                }
            }
        }
    }

    /**
     * Build one UI turn for a server-initiated gateway response. Upstream uses
     * this path when a background process finishes: it injects a synthetic user
     * event and starts an ordinary assistant turn without a phone `sendTurn()`.
     */
    private fun createGatewayInboundTurnRegistration(
        client: GatewayChatClient,
        storedSessionId: String,
    ): GatewayInboundTurnRegistration? {
        val handler = chatHandler ?: return null
        fun matchesAdmissionContext(): Boolean =
            gatewayClient === client &&
                streamingEndpoint == "gateway" &&
                chatHandler === handler &&
                handler.currentSessionId.value == storedSessionId

        val messageId = "gateway-inbound-${UUID.randomUUID()}"
        var baselineAssistantCount = 0
        var started = false
        var accepted = false
        var boundHandle: ActiveTurnHandle? = null
        var inputTokens: Int? = null
        var outputTokens: Int? = null

        fun ownsTranscriptSession(): Boolean =
            chatHandler === handler && handler.currentSessionId.value == storedSessionId

        fun ownsBoundTurn(): Boolean =
            accepted && boundHandle != null && activeStream === boundHandle && ownsTranscriptSession()

        fun acceptsEvent(): Boolean = started && ownsBoundTurn()

        fun settleBoundTurnState() {
            if (activeStream === boundHandle) {
                activeStream = null
                _steerableTurn.value = false
                _steerNotice.value = null
            }
            accepted = false
            boundHandle = null
        }

        val callbacks = GatewayTurnCallbacks(
            onSessionId = { },
            onStart = {
                if (!started && ownsBoundTurn()) {
                    started = true
                    cancelAnswerRecovery()
                    intentionallyCancelled = false
                    firstTokenNotified = false
                    handler.activeAgentName = currentAgentDisplayName()
                    handler.addPlaceholderMessage(
                        ChatMessage(
                            id = messageId,
                            role = MessageRole.ASSISTANT,
                            content = "",
                            timestamp = System.currentTimeMillis(),
                            isStreaming = true,
                            agentName = handler.activeAgentName,
                        ),
                    )
                }
            },
            onTextDelta = { delta ->
                if (acceptsEvent()) {
                    if (!firstTokenNotified) {
                        firstTokenNotified = true
                        AppAnalytics.onFirstTokenReceived()
                    }
                    handler.onTextDelta(messageId, delta)
                }
            },
            onThinkingDelta = { delta ->
                if (acceptsEvent()) handler.onThinkingDelta(messageId, delta)
            },
            onToolCallStart = { toolCallId, toolName ->
                if (acceptsEvent()) handler.onToolCallStart(messageId, toolCallId, toolName)
            },
            onToolCallDone = { toolCallId, preview ->
                if (acceptsEvent()) handler.onToolCallComplete(messageId, toolCallId, preview)
            },
            onToolCallFailed = { toolCallId, error ->
                if (acceptsEvent()) handler.onToolCallFailed(messageId, toolCallId, error)
            },
            onTurnComplete = {
                if (acceptsEvent()) handler.onTurnComplete(messageId)
            },
            onComplete = {
                val canWriteTranscript = acceptsEvent()
                val expectedText = handler.messages.value
                    .lastOrNull { it.id == messageId }
                    ?.content
                    ?.takeIf { it.isNotBlank() }
                if (canWriteTranscript) {
                    finalizeTurnSideEffects(handler, messageId)
                    AppAnalytics.onStreamComplete(inputTokens, outputTokens)
                    scheduleGatewayHistoryReconcile(
                        storedSessionId = storedSessionId,
                        expectedAssistantText = expectedText,
                    )
                    drainQueue()
                } else {
                    settleBoundTurnState()
                }
            },
            onUsage = { usage ->
                if (acceptsEvent() && usage != null) {
                    inputTokens = usage.resolvedInputTokens
                    outputTokens = usage.resolvedOutputTokens
                    handler.onUsageReceived(
                        messageId,
                        inputTokens,
                        outputTokens,
                        usage.resolvedTotalTokens,
                        null,
                    )
                }
            },
            onError = { message ->
                val canWriteTranscript = acceptsEvent()
                if (canWriteTranscript) {
                    AppAnalytics.onStreamError()
                    handler.onStreamError(message)
                    emitError(Exception(message), context = "send_message")
                    settleBoundTurnState()
                    _queuedMessages.value = emptyList()
                    clearPendingAsk(approvalStamp = "deny")
                    scheduleGatewayHistoryReconcile(
                        storedSessionId = storedSessionId,
                        baselineAssistantCount = baselineAssistantCount,
                    )
                } else {
                    settleBoundTurnState()
                }
            },
            onToolGenerating = { name ->
                if (acceptsEvent()) handler.onToolGenerating(messageId, name)
            },
            onSubagentEvent = { event ->
                if (acceptsEvent()) handler.onSubagentEvent(messageId, event)
            },
            onInteractionRequest = { ask ->
                if (acceptsEvent()) presentInteractionAsk(handler, ask)
            },
            onInteractionExpired = { expiry ->
                if (acceptsEvent()) expirePendingAsk(expiry)
            },
            onStatusUpdate = { kind, text ->
                if (acceptsEvent()) {
                    handler.setTurnStatus(text, kind)
                    if (text.trimStart().startsWith("❌")) handler.markError(messageId)
                }
            },
            onStatusClear = { kind ->
                if (acceptsEvent()) handler.clearTurnStatus(kind)
            },
        )
        return GatewayInboundTurnRegistration(
            callbacks = callbacks,
            onHandle = { handle ->
                if (matchesAdmissionContext() && activeStream == null && !handler.isStreaming.value) {
                    // Admission and its transcript baseline are both captured
                    // on the callback/main dispatcher. Reading activeStream or
                    // messages on the WebSocket thread can observe a local
                    // turn before its queued completion callback has settled.
                    baselineAssistantCount = handler.messages.value.count {
                        it.role == MessageRole.ASSISTANT && !it.clientOnly
                    }
                    boundHandle = handle
                    accepted = true
                    activeStream = handle
                    activeStreamIsGateway = true
                    _steerableTurn.value = true
                    true
                } else {
                    false
                }
            },
        )
    }

    /**
     * Reconcile one server-initiated Gateway turn through persisted history.
     * Waits for Chat to become idle and rejects a response if the local
     * transcript changed during the HTTP read, preventing stale history from
     * erasing a newer turn. [expectedAssistantText] also waits through the
     * small message.complete→persistence gap; [baselineAssistantCount] serves
     * the same purpose after a transport error whose final text is not known
     * locally. A count avoids comparing raw persisted MEDIA/CARD markers with
     * their stripped live rendering.
     */
    private fun scheduleGatewayHistoryReconcile(
        storedSessionId: String,
        expectedAssistantText: String? = null,
        baselineAssistantCount: Int? = null,
    ) {
        val handler = chatHandler ?: return
        if (chatHandler !== handler || handler.currentSessionId.value != storedSessionId) return
        gatewayHistoryReconcileJob?.cancel()
        gatewayHistoryReconcileJob = viewModelScope.launch {
            val expected = expectedAssistantText?.trim()?.takeIf { it.isNotEmpty() }
            // A locally-started SSE/realtime turn may own Chat when the
            // background completion arrives. Waiting for that turn must not
            // consume the bounded persistence-retry budget below: foreground
            // turns routinely last longer than that small server-write gap.
            while (activeStream != null || handler.isStreaming.value) {
                if (chatHandler !== handler || handler.currentSessionId.value != storedSessionId) {
                    return@launch
                }
                delay(100L)
            }
            repeat(20) {
                if (chatHandler !== handler || handler.currentSessionId.value != storedSessionId) {
                    return@launch
                }
                if (activeStream != null || handler.isStreaming.value) {
                    // A new local turn started after the initial idle wait.
                    // Keep this recovery pending until Chat is available again,
                    // without spending a persistence attempt.
                    while (activeStream != null || handler.isStreaming.value) {
                        if (chatHandler !== handler ||
                            handler.currentSessionId.value != storedSessionId
                        ) {
                            return@launch
                        }
                        delay(100L)
                    }
                }

                val transcriptSnapshot = handler.messages.value
                val serverMessages = loadGatewaySessionHistory(storedSessionId)
                if (chatHandler !== handler || handler.currentSessionId.value != storedSessionId) {
                    return@launch
                }
                if (activeStream != null ||
                    handler.isStreaming.value ||
                    handler.messages.value !== transcriptSnapshot
                ) {
                    delay(100L)
                    return@repeat
                }

                val serverAssistantContents = serverMessages
                    .filter { it.role.equals("assistant", ignoreCase = true) }
                    .mapNotNull { it.contentText?.trim() }
                val authoritativeTurnPresent = when {
                    expected != null -> serverAssistantContents.any { it == expected }
                    baselineAssistantCount != null ->
                        serverAssistantContents.size > baselineAssistantCount
                    else -> serverMessages.isNotEmpty()
                }
                if (!authoritativeTurnPresent) {
                    if (expected == null && baselineAssistantCount == null) {
                        gatewayHistoryReconcileJob = null
                        return@launch
                    }
                    delay(250L)
                    return@repeat
                }

                handler.loadMessageHistory(serverMessages)
                refreshSessions()
                scheduleTitleReconcile(storedSessionId)
                gatewayHistoryReconcileJob = null
                return@launch
            }
            gatewayHistoryReconcileJob = null
        }
    }

    /** One-shot `config.get personality` over a ready socket → drives the collector. */
    private fun seedServerPersonality(client: GatewayChatClient) {
        viewModelScope.launch {
            // getPersonality() updates serverPersonality, which startGatewayStateSync
            // observes — so no need to apply the result here directly.
            client.getPersonality()
        }
    }

    /**
     * Warm the gateway socket (and resume the current session) when the chat
     * surface is visible and the gateway is the resolved transport, so the
     * first send is warm instead of paying the cold connect + `session.resume`
     * on the send path. No-op without a gateway client; idempotent when warm.
     * Driven by a foreground/visibility effect in ChatScreen.
     */
    fun prewarmGateway() {
        val client = gatewayClient
        val handler = chatHandler ?: return
        val sessionId = handler.currentSessionId.value
        selectBackgroundProcessSession(sessionId)
        if (sessionId == null) {
            client?.prewarm(null)
        } else {
            // Preserve the original warm-up path before persistence wiring is
            // available (early composition and JVM tests). Production installs
            // the store from initializeMedia before Chat becomes ready.
            if (chatTurnCheckpointStore == null) {
                val gateway = client ?: return
                // GatewayChatClient owns an IO scope, so this can progress even
                // while a paused/blocked UI dispatcher is being recreated.
                // Its cold-ready listener performs history/process refresh.
                gateway.prewarm(sessionId)
                return
            }
            if (activeStream == null && (streamRecovery == null || client != null)) {
                if (checkpointRecoveryJob?.isActive == true) return
                checkpointRecoveryJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val claimed = recoverPersistedTurnIfNeeded(client, handler, sessionId)
                    if (!claimed &&
                        gatewayClient === client &&
                        chatHandler === handler &&
                        handler.currentSessionId.value == sessionId
                    ) {
                        if (client?.prewarmAwait(sessionId) == true) {
                            gatewayProcessController.sessionReady(sessionId)
                        }
                    }
                    checkpointRecoveryJob = null
                }
                return
            }
            // prewarm() only emits the existing "cold ready" callback when it
            // had to resume. An already-live session still needs its initial
            // process snapshot when Chat opens, so confirm it explicitly.
            viewModelScope.launch {
                if (
                    client?.prewarmAwait(sessionId) == true &&
                    gatewayClient === client &&
                    chatHandler === handler &&
                    handler.currentSessionId.value == sessionId
                ) {
                    gatewayProcessController.sessionReady(sessionId)
                }
            }
        }
    }

    // === Gateway desktop-parity state ===

    /**
     * The live server-side interactive ask (clarify/approval/sudo/secret).
     * One at a time — the upstream agent thread blocks until the answer
     * arrives, so a second ask can't exist while the first is pending.
     * Cleared on answer, turn end, cancel, and connection switch.
     */
    private val _pendingAsk = MutableStateFlow<PendingAsk?>(null)
    val pendingAsk: StateFlow<PendingAsk?> = _pendingAsk.asStateFlow()

    /** Ask cardKeys with a respond RPC in flight — blocks double-taps until it settles. */
    private val answeredAskIds = mutableSetOf<String>()

    /**
     * Context-window fill fraction (0..1) from gateway usage events; null
     * when the server's context compressor is absent (no `context_max`).
     * Session-cumulative by definition — feeds ContextMeterBar + the
     * subtitle "NN% ctx" suffix, never per-message token displays.
     */
    private val _contextUsage = MutableStateFlow<Float?>(null)
    val contextUsage: StateFlow<Float?> = _contextUsage.asStateFlow()

    /**
     * Absolute per-session context-window tokens (`used` / `max`) from the same
     * gateway usage events that drive [contextUsage] — exposed so the context
     * bar can show real token counts (`31k / 200k`) like the desktop, not just
     * a percent. Null until the server reports a `context_used` + `context_max`
     * for the active session; reset on every session switch / new / clear so it
     * is strictly per-session.
     */
    private val _contextWindow = MutableStateFlow<ContextWindowUsage?>(null)
    val contextWindow: StateFlow<ContextWindowUsage?> = _contextWindow.asStateFlow()

    /** Server slash-command catalog (`commands.catalog`) — 4th allCommands source. */
    private val _serverCommands = MutableStateFlow<List<SlashCommand>>(emptyList())
    val serverCommands: StateFlow<List<SlashCommand>> = _serverCommands.asStateFlow()

    /**
     * True while the in-flight turn is actually running on the gateway
     * transport (not an SSE fallback) — the only state in which
     * `session.steer` can land. Drives the STEER trailing slot.
     */
    private val _steerableTurn = MutableStateFlow(false)
    val steerableTurn: StateFlow<Boolean> = _steerableTurn.asStateFlow()

    /**
     * One-line caption feedback after a steer attempt fell back to the
     * queue ("Queued — delivers after this turn"). Cleared at turn end.
     */
    private val _steerNotice = MutableStateFlow<String?>(null)
    val steerNotice: StateFlow<String?> = _steerNotice.asStateFlow()

    /**
     * One-shot ephemeral notices (e.g. a model-switch warning or error) that
     * ChatScreen surfaces as a transient snackbar — never a persistent chat
     * bubble. Model switches confirm via the pill updating, not a bubble.
     */
    private val _transientNotice = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val transientNotice: SharedFlow<String> = _transientNotice.asSharedFlow()

    /**
     * Composer prefill requests from server command dispatch
     * (`{type:"prefill"}` — e.g. `/undo`). ChatScreen collects and sets the
     * input text.
     */
    private val _composerPrefill = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val composerPrefill: SharedFlow<String> = _composerPrefill.asSharedFlow()

    /**
     * One-shot request to open the personality picker — emitted when a bare
     * `/personality` (no argument) is sent. Picker commands aren't raw-forwarded
     * on mobile (there's no inline arg-expansion popover like the desktop's), so
     * the bare command opens the agent sheet's personality section instead.
     * ChatScreen collects this and shows the sheet.
     */
    private val _openPersonalityPicker = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val openPersonalityPicker: SharedFlow<Unit> = _openPersonalityPicker.asSharedFlow()

    /**
     * One-shot request to open the model picker — emitted for a bare `/model`
     * (the sibling picker command). `/model <args>` stays a real switch the
     * gateway applies. ChatScreen collects this and shows the model sheet.
     */
    private val _openModelPicker = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val openModelPicker: SharedFlow<Unit> = _openModelPicker.asSharedFlow()

    /**
     * "Notify when Hermes finishes" setting — mirrored from
     * ConnectionViewModel's DataStore flow by RelayApp, same pattern as
     * [appContextSettings]. Default ON matches the DataStore default.
     */
    var notifyOnTurnComplete: Boolean = true

    /**
     * Edit-and-regenerate: 0-based USER ordinal armed by
     * [regenerateFromMessage] and consumed by the next [startStream]
     * gateway dispatch as `truncate_before_user_ordinal`.
     */
    private var pendingTruncateOrdinal: Int? = null

    /**
     * Provider for the active agent-profile pick — wired from [RelayApp] at
     * composition time so this VM stays ignorant of [ConnectionViewModel].
     *
     * `null` return means "no pick — let the server fall back to its
     * configured default model" and the send pipeline leaves `modelOverride`
     * off the wire. A non-null [Profile] with a non-blank `model` causes
     * the request body to carry `"model": profile.model` for that turn.
     *
     * Default provider returns `null` so a fresh VM (before RelayApp has
     * wired the flow) behaves identically to pre-profile-picker installs.
     */
    /**
     * Demo / Explore mode wiring. [demoModeProvider] reads
     * [com.hermesandroid.relay.viewmodel.ConnectionViewModel.isDemoMode];
     * [demoChatHandlerProvider] supplies the shared [ChatHandler] carrying
     * the canned transcript. Both are needed because in demo there is no API
     * client, so [initialize] never runs and this VM's own [chatHandler]
     * stays null. Wired unconditionally from RelayApp (not inside the
     * client-gated init effect).
     */
    private var demoModeProvider: () -> Boolean = { false }
    private var demoChatHandlerProvider: () -> ChatHandler? = { chatHandler }

    fun setDemoModeWiring(isDemo: () -> Boolean, handler: () -> ChatHandler?) {
        demoModeProvider = isDemo
        demoChatHandlerProvider = handler
    }

    private var selectedProfileProvider: () -> Profile? = { null }
    private var effectiveProfileProvider: () -> Profile? = { selectedProfileProvider() }
    private var displayProfileProvider: () -> Profile? = {
        effectiveProfileProvider() ?: selectedProfileProvider()
    }
    private var displayAliasProvider: () -> String? = { null }
    private var activeProfileContextKey: String? = null

    /** Process ownership is profile+session scoped; stored IDs alone are not globally unique. */
    private fun selectBackgroundProcessSession(
        sessionId: String?,
        scopeKey: String? = activeProfileContextKey,
    ) {
        gatewayProcessController.selectSession(sessionId, scopeKey)
    }

    /**
     * Wire the agent-profile provider. The provider is typically a lambda
     * that reads from
     * [com.hermesandroid.relay.viewmodel.ConnectionViewModel.selectedProfile]'s
     * `.value` — giving us a fresh pick on every send without holding a
     * direct reference to the other VM. Idempotent; later calls replace
     * the stored provider.
     */
    fun setSelectedProfileProvider(provider: () -> Profile?) {
        selectedProfileProvider = provider
        refreshActiveAgentName()
    }

    fun setEffectiveProfileProvider(provider: () -> Profile?) {
        effectiveProfileProvider = provider
        refreshActiveAgentName()
    }

    fun setDisplayProfileProvider(provider: () -> Profile?) {
        displayProfileProvider = provider
        refreshActiveAgentName(relabelGenericMessages = true)
    }

    fun setDisplayAliasProvider(provider: () -> String?) {
        displayAliasProvider = provider
        refreshActiveAgentName(relabelGenericMessages = true)
    }

    fun refreshAgentDisplayName(relabelGenericMessages: Boolean = false) {
        refreshActiveAgentName(relabelGenericMessages = relabelGenericMessages)
    }

    /**
     * Supplies the drawer's profile-scoped session list for gateway connections
     * (dashboard `/api/sessions?profile=<active>`). Returns `null` when the
     * connection has no dashboard session, so [refreshSessions] falls back to the
     * shared api_server list. Wired from RelayApp to
     * [com.hermesandroid.relay.viewmodel.ConnectionViewModel.listProfileScopedSessions].
     */
    private var profileSessionLister: (suspend () -> Result<List<SessionItem>>?)? = null

    fun setProfileSessionLister(lister: suspend () -> Result<List<SessionItem>>?) {
        profileSessionLister = lister
    }

    /**
     * Deletes a session scoped to the active profile on gateway connections
     * (dashboard `DELETE /api/sessions/{id}?profile=`). The write twin of
     * [profileSessionLister]: a non-default profile's row lives in that profile's
     * own DB, so the unscoped api_server delete leaves it behind and the next
     * profile-scoped list resurrects it. Returns `true` on success. Wired from
     * RelayApp to
     * [com.hermesandroid.relay.viewmodel.ConnectionViewModel.deleteProfileScopedSession].
     */
    var profileSessionDeleter: (suspend (String) -> Boolean)? = null

    /**
     * Renames a session scoped to the active profile on gateway connections
     * (dashboard `PATCH /api/sessions/{id}?profile=`). The write twin of
     * [profileSessionDeleter]: without it, a rename on a non-default gateway
     * profile patches the shared api_server DB and the new title never lands in
     * the profile's own state.db. Returns `true` on success. Wired from RelayApp
     * to [com.hermesandroid.relay.viewmodel.ConnectionViewModel.renameProfileScopedSession].
     */
    var profileSessionRenamer: (suspend (String, String) -> Boolean)? = null

    /**
     * Loads a session's transcript scoped to the active profile (dashboard
     * `/api/sessions/{id}/messages?profile=`). Twin of [profileSessionLister]:
     * once the drawer lists a non-default profile's sessions, opening one must
     * read that profile's own DB. Returns `null` off the dashboard surface.
     */
    private var profileMessageLoader: (suspend (String) -> Result<List<MessageItem>>?)? = null

    fun setProfileMessageLoader(loader: suspend (String) -> Result<List<MessageItem>>?) {
        profileMessageLoader = loader
    }

    /**
     * Transcript for [sessionId], preferring the profile-scoped dashboard path on
     * gateway connections (so non-default-profile sessions resolve against their
     * own DB). The shared api_server transcript is used only when no scoped
     * dashboard surface exists; a failed scoped read is never cross-profile truth.
     */
    private suspend fun loadSessionHistory(
        sessionId: String,
        requireProfileScope: Boolean = false,
    ): List<MessageItem> {
        if (streamingEndpoint == "gateway") {
            return loadGatewaySessionHistory(sessionId, requireProfileScope)
        }
        return apiClient?.getMessages(sessionId) ?: emptyList()
    }

    /**
     * Read a Gateway-owned session through the active profile even if the live
     * transport has just downgraded to SSE. Detached completion belongs to the
     * profile/session that created it; consulting the mutable endpoint here can
     * otherwise fall through to the shared API database.
     */
    private suspend fun loadGatewaySessionHistory(
        sessionId: String,
        requireProfileScope: Boolean = false,
    ): List<MessageItem> {
        val scoped = profileMessageLoader?.invoke(sessionId)
        if (scoped != null) {
            // A gateway profile owns a distinct state.db. Never fall through to
            // the launch/default API database when its scoped read fails: an
            // empty/default transcript is not authoritative for this session.
            return if (requireProfileScope) scoped.getOrThrow() else scoped.getOrElse { emptyList() }
        }
        return apiClient?.getMessages(sessionId) ?: emptyList()
    }

    /**
     * Pick a personality. On the gateway this is server-owned the way the
     * desktop + TUI do it: the value is pushed via `config.set` (which persists
     * `display.personality` + applies the overlay live to the session), and the
     * picker selection is then reconciled from the server's `session.info` /
     * `config.get` truth by [startGatewayStateSync]. On the SSE fallbacks there is
     * no server-side session personality, so the pick only drives the per-turn
     * system-prompt injection in [startStream]. Pass "none" (or "default") to
     * clear the overlay.
     */
    fun selectPersonality(name: String) {
        val previous = _selectedPersonality.value
        _selectedPersonality.value = name
        refreshActiveAgentName()
        if (streamingEndpoint == "gateway") {
            val gateway = gatewayClient ?: return
            viewModelScope.launch {
                gateway.setPersonality(personalityConfigValue(name)).onFailure { e ->
                    // Server rejected it (e.g. unknown personality) — roll the
                    // optimistic pick back and surface why. The notice now
                    // survives the post-turn reconcile (system-notice preserve).
                    _selectedPersonality.value = previous
                    refreshActiveAgentName()
                    chatHandler?.addSystemNotice(
                        "Couldn't set personality: ${e.message ?: "gateway error"}"
                    )
                }
                // On success the session.info echo + collector confirm the value.
            }
        }
    }

    /** App selection → upstream `config.set` value. default/none/neutral all clear. */
    private fun personalityConfigValue(name: String): String =
        if (AgentDisplay.isClearedPersonality(name)) "none" else name.trim().lowercase()

    /**
     * Mirror the gateway's active personality (`session.info` / `config.get`) into
     * the picker selection so a change made via `/personality`, the desktop, or
     * the TUI is reflected — and so the app stops injecting a stale overlay.
     */
    private fun applyServerPersonality(value: String) {
        val mapped = if (AgentDisplay.isClearedPersonality(value)) "none" else value.trim().lowercase()
        if (_selectedPersonality.value != mapped) {
            _selectedPersonality.value = mapped
            refreshActiveAgentName()
        }
    }

    private var gatewayStateSyncJob: Job? = null

    /**
     * Last credential_warning already surfaced as a system notice, so the
     * recurring `session.info` echoes (one per model/personality/profile switch
     * + periodic refresh) don't re-post the same warning. Reset when the warning
     * goes absent and on client change so a re-occurrence is shown again.
     */
    private var lastSurfacedCredentialWarning: String? = null

    /**
     * Track the active client's `session.info`-derived state (personality, model,
     * provider, reasoning effort, credential warning, YOLO, fast) so a change made
     * via a slash command, the desktop, or the TUI reflects in the app without an
     * app reload. The collectors only observe flows (never cold-open the socket);
     * the one-shot seed in [updateGatewayClient] is gated on a ready socket.
     */
    private fun startGatewayStateSync(client: GatewayChatClient) {
        gatewayStateSyncJob?.cancel()
        lastSurfacedCredentialWarning = null
        gatewayStateSyncJob = viewModelScope.launch {
            launch {
                client.serverPersonality.collect { value ->
                    if (gatewayClient !== client || value == null) return@collect
                    applyServerPersonality(value)
                }
            }
            launch {
                client.serverModel.collect { value ->
                    if (gatewayClient !== client || value.isNullOrBlank()) return@collect
                    if (_gatewayCurrentModel.value != value) _gatewayCurrentModel.value = value
                }
            }
            launch {
                client.serverProvider.collect { value ->
                    if (gatewayClient !== client || value.isNullOrBlank()) return@collect
                    if (_gatewayCurrentProvider.value != value) _gatewayCurrentProvider.value = value
                }
            }
            launch {
                client.serverReasoningEffort.collect { value ->
                    // Ignore blank (upstream "" = reasoning disabled — never
                    // clobber the chip). Compare on the normalized value, the
                    // same form the optimistic setter + config.get refresh store.
                    if (gatewayClient !== client || value.isNullOrBlank()) return@collect
                    val normalized = normalizeReasoningEffort(value)
                    if (_selectedReasoningEffort.value != normalized) {
                        _selectedReasoningEffort.value = normalized
                    }
                }
            }
            launch {
                client.serverCredentialWarning.collect { warning ->
                    if (gatewayClient !== client) return@collect
                    if (warning.isNullOrBlank()) {
                        // Key fixed / provider switched — reset so a future
                        // recurrence is surfaced again. (Identity already guarded
                        // above so a torn-down old client can't reset the new one.)
                        lastSurfacedCredentialWarning = null
                        return@collect
                    }
                    // One notice per DISTINCT warning — session.info repeats it
                    // on every config echo.
                    if (warning == lastSurfacedCredentialWarning) return@collect
                    lastSurfacedCredentialWarning = warning
                    chatHandler?.addSystemNotice("⚠ $warning")
                }
            }
            launch {
                client.serverYolo.collect { value ->
                    if (gatewayClient !== client || value == null) return@collect
                    if (_yoloEnabled.value != value) _yoloEnabled.value = value
                }
            }
            launch {
                client.serverFast.collect { value ->
                    if (gatewayClient !== client || value == null) return@collect
                    if (_fastEnabled.value != value) _fastEnabled.value = value
                }
            }
            launch {
                // Paint the context bar from session.info (emitted on resume) so
                // it doesn't wait for the first turn's usage event.
                client.serverContext.collect { ctx ->
                    if (gatewayClient !== client || ctx == null) return@collect
                    val (used, max) = ctx
                    if (max > 0) {
                        _contextWindow.value = ContextWindowUsage(usedTokens = used, maxTokens = max)
                        _contextUsage.value = (used.toFloat() / max).coerceIn(0f, 1f)
                    }
                }
            }
        }
    }

    /** The display name of the currently active personality (for chat bubbles). */
    val activePersonalityName: String
        get() {
            val selected = _selectedPersonality.value
            // "none"/"neutral" are cleared-overlay aliases — fall to the base
            // identity, not the literal word.
            return if (AgentDisplay.isClearedPersonality(selected)) _defaultPersonality.value else selected
        }

    private val _isLoadingHistory = MutableStateFlow(false)
    val isLoadingHistory: StateFlow<Boolean> = _isLoadingHistory.asStateFlow()

    /**
     * One-way startup latch: the first profile/session context application
     * has concluded — last session's history loaded, or there was nothing to
     * restore, or the load failed. RelayApp's startup gate holds the sphere
     * splash on this so the chat surface never flashes its empty "start
     * chatting" state while the previous conversation is still inbound.
     */
    private val _initialChatSettled = MutableStateFlow(false)
    val initialChatSettled: StateFlow<Boolean> = _initialChatSettled.asStateFlow()

    private val _availableSkills = MutableStateFlow<List<SkillInfo>>(emptyList())
    val availableSkills: StateFlow<List<SkillInfo>> = _availableSkills.asStateFlow()

    // Cached fallback StateFlows to avoid creating new instances on each access
    private val _emptyMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _emptyStreaming = MutableStateFlow(false)
    private val _emptySessions = MutableStateFlow<List<ChatSession>>(emptyList())
    private val _emptyError = MutableStateFlow<String?>(null)
    private val _emptySessionId = MutableStateFlow<String?>(null)
    private val _emptyTurnStatus = MutableStateFlow<String?>(null)

    // Delegated to ChatHandler
    val messages: StateFlow<List<ChatMessage>>
        get() = chatHandler?.messages ?: _emptyMessages

    /**
     * True while an SSE chat turn is in flight (between request-start and
     * `run.completed` / `stream-end`). Consumed by the chat UI (Stop vs Send
     * button, StreamingDots) AND by the agent/connection info sheet to gate
     * mid-stream side-effects:
     *
     *   - [com.hermesandroid.relay.ui.components.ConnectionInfoSheet] — disables
     *     the profile + personality pickers (`enabled = !isStreaming`) so a
     *     radio tap during an in-flight turn can't race the already-dispatched
     *     request. The sheet may also surface a short subtitle banner
     *     ("Streaming — profile locked until response completes"); this
     *     StateFlow is the authoritative source for that gate.
     *
     * Derived from [ChatHandler.isStreaming], so it flips true on every
     * send path (runs, sessions, compat) and flips false on any terminal
     * event (complete / error / cancel).
     */
    val isStreaming: StateFlow<Boolean>
        get() = chatHandler?.isStreaming ?: _emptyStreaming

    /** Latest gateway lifecycle status for the in-flight turn (or null). */
    val turnStatus: StateFlow<String?>
        get() = chatHandler?.turnStatus ?: _emptyTurnStatus

    val sessions: StateFlow<List<ChatSession>>
        get() = chatHandler?.sessions ?: _emptySessions

    val error: StateFlow<String?>
        get() = chatHandler?.error ?: _emptyError

    val currentSessionId: StateFlow<String?>
        get() = chatHandler?.currentSessionId ?: _emptySessionId

    /**
     * Inject an agent-initiated ("proactive") message into the active session
     * so it continues that conversation (the `phone` platform's
     * `surfacing="session"` path). Local-only bubble that survives the history
     * reconcile; no-op when no session is active. Small, localized entry point —
     * the routing decision lives in
     * [com.hermesandroid.relay.network.relay.ProactiveMessageHandler].
     */
    fun injectProactiveMessage(text: String) {
        chatHandler?.addProactiveMessage(text)
    }

    /**
     * Settle a Thread reply bubble when the relay acks it
     * (`proactive.reply.ack`) — wired by RelayApp to the proactive handler's
     * `onReplyAck`. [clientMsgId] is the user bubble's id (the app stamped it on
     * the reply). Any non-"failed" status is treated as DELIVERED (the relay
     * buffered the reply for the agent).
     */
    fun onProactiveReplyAck(clientMsgId: String, status: String) {
        val resolved = if (status.equals("failed", ignoreCase = true)) {
            MessageDeliveryStatus.FAILED
        } else {
            MessageDeliveryStatus.DELIVERED
        }
        chatHandler?.updateDeliveryStatus(clientMsgId, resolved)
    }

    /**
     * Render an inbound agent message inline in the open Thread when it belongs
     * there (the unified-Threads live path) — wired to the proactive handler's
     * `injectIntoThread`. Returns true when shown in-thread, so the handler
     * suppresses the notification + inbox entry. Matches the open Thread (or a
     * pending "+ New Thread" draft) by chat_id, falling back to "accept" when
     * either side has no parseable chat_id (the single home thread).
     */
    fun injectThreadMessage(msg: ProactiveMessage): Boolean {
        val handler = chatHandler ?: return false
        val msgChatId = msg.chatId?.takeIf { it.isNotBlank() }
        // A freshly-created thread whose real session we're still switching to:
        // show the agent's first reply in the draft view now (the switch
        // reconciles it from history). Covers the gap before currentSessionId is
        // set, so the very first reply doesn't fall through to a notification.
        creatingThread?.let { creating ->
            if (msgChatId == null || msgChatId == creating.chatId) {
                handler.addAgentThreadMessage(msg.text, msg.messageId, msg.title)
                return true
            }
        }
        val activeId = handler.currentSessionId.value ?: return false
        val active = handler.sessions.value.firstOrNull { it.sessionId == activeId } ?: return false
        if (active.source != "phone") return false
        // Match by the learned chat_id when known; otherwise accept (we can't read
        // a session's chat_id from the API, so default to showing it in the open
        // phone thread). Learn the mapping from the message for reply routing.
        val knownChatId = threadChatIds[activeId]
        val belongs = knownChatId == null || msgChatId == null || knownChatId == msgChatId
        if (!belongs) return false
        if (msgChatId != null) threadChatIds[activeId] = msgChatId
        handler.addAgentThreadMessage(msg.text, msg.messageId, msg.title)
        return true
    }

    fun realtimeAgentContextMessages(maxMessages: Int = 14): List<RealtimeConversationContextMessage> {
        val handler = chatHandler ?: return emptyList()
        return handler.messages.value
            .asSequence()
            .filter { !it.isStreaming }
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .mapNotNull { msg ->
                val content = msg.content.trim()
                if (content.isBlank()) return@mapNotNull null
                val source = when {
                    msg.realtimeTurn != null -> "realtime_agent"
                    msg.badges.any { it.equals("Realtime Agent", ignoreCase = true) } -> "realtime_agent"
                    else -> "hermes_chat"
                }
                RealtimeConversationContextMessage(
                    role = msg.role,
                    content = content.take(1_500),
                    source = source,
                )
            }
            .toList()
            .takeLast(maxMessages)
    }

    /**
     * The handler currently bound by [initialize]. Lets the caller tell a
     * client-instance swap (route handoff / reconnect — same chat) apart from
     * a genuine re-bind (new handler), so a handoff can take the cheap
     * [updateApiClient] path and avoid re-initializing / repainting.
     */
    val boundHandler: ChatHandler? get() = chatHandler

    fun initialize(apiClient: HermesApiClient, chatHandler: ChatHandler) {
        this.apiClient = apiClient
        if (this.chatHandler !== chatHandler) {
            checkpointStatusJob?.cancel()
            checkpointStatusJob = null
        }
        this.chatHandler = chatHandler
        ensureCheckpointObservers()
        selectBackgroundProcessSession(chatHandler.currentSessionId.value)
        backgroundProcessSessionJob?.cancel()
        backgroundProcessSessionJob = viewModelScope.launch {
            // ChatHandler is the single source of truth for session ownership.
            // Collecting it catches every path (drawer switch, new send, voice
            // sync, profile/connection change) without relying on a growing set
            // of mirrored setSessionId call sites.
            chatHandler.currentSessionId.collect { sessionId ->
                selectBackgroundProcessSession(sessionId)
            }
        }
        // A handler created after the persisted Thread names loaded still gets
        // them, so a named Thread keeps its name across restart / reconnect.
        chatHandler.setUserThreadNames(persistedThreadNames)
        fetchSkills()
        fetchPersonalities()
        fetchModels()
        // Keep the tool-call history in sync with the active chat handler's
        // messages. Subscribed on every initialize() call so a replaced
        // handler (connection switch) picks up fresh events without leaking
        // the previous connection's tail.
        toolHistoryJob?.cancel()
        toolHistoryJob = viewModelScope.launch {
            chatHandler.messages.collect { msgs ->
                val events = msgs
                    .asSequence()
                    .flatMap { msg -> msg.toolCalls.asSequence() }
                    .map { tc ->
                        ToolCallEvent(
                            id = tc.id ?: "${tc.name}-${tc.startedAt}",
                            name = tc.name,
                            startedAtMs = tc.startedAt,
                            completedAtMs = tc.completedAt,
                            isComplete = tc.isComplete,
                            success = tc.success,
                            resultSummary = tc.result,
                            errorSummary = tc.error,
                        )
                    }
                    .toList()
                    // Newest-first ordering for the UI — tool calls are
                    // rare enough that re-sorting on every update is cheap.
                    .sortedByDescending { it.completedAtMs ?: it.startedAtMs }
                    .take(TOOL_CALL_HISTORY_LIMIT)
                _toolCallHistory.value = events
                scheduleCheckpointWrite()
            }
        }
    }

    /**
     * Bind a [ChatHandler] for offline Demo / Explore mode, *without* the
     * network-touching fetches [initialize] performs (skills / personalities /
     * models all hit the server). Demo has no API client, so we only need the
     * [messages] delegation to point at the handler that holds the canned
     * transcript ([com.hermesandroid.relay.network.upstream.ChatHandler.loadDemoTranscript]).
     *
     * Called from [RelayApp][com.hermesandroid.relay.ui.RelayApp] the moment
     * demo mode is entered, before navigating to Chat, so the chat surface
     * renders the demo conversation through the real composables. Safe to call
     * repeatedly; re-subscribes the tool-call history collector.
     */
    fun bindDemoHandler(handler: ChatHandler) {
        this.chatHandler = handler
        backgroundProcessSessionJob?.cancel()
        selectBackgroundProcessSession(null)
        toolHistoryJob?.cancel()
        toolHistoryJob = viewModelScope.launch {
            handler.messages.collect { msgs ->
                _toolCallHistory.value = msgs
                    .asSequence()
                    .flatMap { msg -> msg.toolCalls.asSequence() }
                    .map { tc ->
                        ToolCallEvent(
                            id = tc.id ?: "${tc.name}-${tc.startedAt}",
                            name = tc.name,
                            startedAtMs = tc.startedAt,
                            completedAtMs = tc.completedAt,
                            isComplete = tc.isComplete,
                            success = tc.success,
                            resultSummary = tc.result,
                            errorSummary = tc.error,
                        )
                    }
                    .toList()
                    .sortedByDescending { it.completedAtMs ?: it.startedAtMs }
                    .take(TOOL_CALL_HISTORY_LIMIT)
            }
        }
    }

    /**
     * Wire inbound-media dependencies. Called from [RelayApp][com.hermesandroid.relay.ui.RelayApp]
     * once after the singleton services are constructed.
     *
     * Separated from [initialize] because the media pipeline doesn't require
     * an active API client to be meaningful — the fetch path is independent
     * of chat streaming state and uses a different auth token entirely.
     *
     * Safe to call multiple times (rewires the ChatHandler callbacks).
     */
    fun initializeMedia(
        context: Context,
        relayHttpClient: RelayHttpClient,
        mediaSettingsRepo: MediaSettingsRepository,
        mediaCacheWriter: MediaCacheWriter
    ) {
        this.appContext = context.applicationContext
        if (chatTurnCheckpointStore == null) {
            chatTurnCheckpointStore = DataStoreChatTurnCheckpointStore(context.applicationContext)
        }
        ensureCheckpointObservers()
        this.relayHttpClient = relayHttpClient
        this.mediaSettingsRepo = mediaSettingsRepo
        this.mediaCacheWriter = mediaCacheWriter

        // Wire ChatHandler callbacks so streaming media markers flow here.
        chatHandler?.let { handler ->
            handler.onMediaAttachmentRequested = { messageId, token ->
                onMediaAttachmentRequested(messageId, token)
            }
            handler.onMediaBarePathRequested = { messageId, originalPath ->
                onMediaBarePathRequested(messageId, originalPath)
            }
        }
    }

    /** JVM-test seam; production is wired to the app-wide relay DataStore. */
    internal fun setChatTurnCheckpointStore(store: ChatTurnCheckpointStore?) {
        chatTurnCheckpointStore = store
        ensureCheckpointObservers()
    }

    fun fetchSkills() {
        val client = apiClient ?: return
        viewModelScope.launch {
            val skills = client.getSkills()
            _availableSkills.value = skills
        }
    }

    fun updateApiClient(client: HermesApiClient) {
        // A route handoff / reconnect rebuilds the HTTP API client. An SSE turn
        // is bound to the OLD client, so it must be cancelled. A GATEWAY turn
        // is NOT — it runs on the gateway client (which survives a
        // same-connection route blip and reconnects its own socket, keeping the
        // live session), so cancelling here would needlessly kill a recoverable
        // turn. Leave it running; it completes on the gateway client.
        val droppedSseCheckpoint = if (!activeStreamIsGateway) buildTurnCheckpoint() else null
        if (!activeStreamIsGateway) {
            activeStream?.cancel()
            activeStream = null
        }
        this.apiClient = client
        if (droppedSseCheckpoint != null) {
            chatHandler?.let { handler ->
                startCheckpointHistoryRecovery(
                    handler,
                    droppedSseCheckpoint,
                    "The chat route changed while the reply was in flight.",
                )
            }
        }
        fetchSkills()
        fetchPersonalities()
        fetchModels()
    }

    /**
     * Multi-connection: subscribe to
     * [com.hermesandroid.relay.viewmodel.ConnectionViewModel.connectionSwitchEvents]
     * so a connection switch wipes per-connection chat state (in-flight
     * stream, message list, session id, queued sends) before the rebuilt
     * API client starts serving the new connection.
     *
     * Idempotent — called once at RelayApp composition time. The collect
     * runs on [viewModelScope] so it's torn down with the VM.
     */
    fun observeConnectionSwitches(events: SharedFlow<String>) {
        connectionSwitchJob?.cancel()
        connectionSwitchJob = viewModelScope.launch {
            events.collect { newConnectionId ->
                clearTurnCheckpoint()
                historyLoadGeneration.incrementAndGet()
                sessionRefreshGeneration.incrementAndGet()
                intentionallyCancelled = true
                activeStream?.cancel()
                activeStream = null
                cancelAnswerRecovery(settleUi = false)
                sessionRefreshJob?.cancel()
                _isLoadingSessions.value = false
                activeProfileContextKey = null
                _queuedMessages.value = emptyList()
                _pendingAttachments.value = emptyList()
                _steerableTurn.value = false
                _steerNotice.value = null
                _pendingAsk.value = null
                _contextUsage.value = null
                _contextWindow.value = null
                _yoloEnabled.value = null
                _fastEnabled.value = null
                pendingYolo = null
                // Effort + personality belong to the old connection's agent —
                // reset to unknown/default so neither a stale chip nor a stale
                // SSE persona overlay carries into the new connection.
                _selectedReasoningEffort.value = null
                _reasoningDisplay.value = null
                _selectedPersonality.value = "default"
                pendingTruncateOrdinal = null
                chatHandler?.let { handler ->
                    handler.clearMessages()
                    handler.clearSessions()
                    handler.setSessionId(null)
                }
                selectBackgroundProcessSession(null)
                // Forward the null session id to the persisted
                // last-session-id slot so the old connection's session
                // doesn't bleed into the new connection on next launch.
                onSessionChanged?.invoke(null)
            }
        }
    }

    fun switchProfileContext(contextKey: String, sessionId: String?) {
        val client = apiClient
        val handler = chatHandler ?: return
        val isInitialContextBinding = activeProfileContextKey == null
        handler.activeAgentName = currentAgentDisplayName()
        if (
            activeProfileContextKey == contextKey &&
            handler.currentSessionId.value == sessionId
        ) {
            _initialChatSettled.value = true
            return
        }
        if (
            activeProfileContextKey == null &&
            sessionId != null &&
            handler.currentSessionId.value == sessionId
        ) {
            activeProfileContextKey = contextKey
            activeTurnCheckpointSeed?.contextKey = contextKey
            scheduleCheckpointWrite(immediate = true)
            selectBackgroundProcessSession(sessionId, contextKey)
            _initialChatSettled.value = true
            return
        }

        // Initial cold-start binding is not a user switch. Its persisted
        // session may own the in-flight checkpoint we are about to recover.
        if (!isInitialContextBinding) releaseTurnForNavigation(handler)
        cancelAnswerRecovery(settleUi = false)
        val loadGeneration = historyLoadGeneration.incrementAndGet()
        sessionRefreshGeneration.incrementAndGet()
        sessionRefreshJob?.cancel()
        _isLoadingSessions.value = false
        activeProfileContextKey = contextKey
        _queuedMessages.value = emptyList()
        _pendingAttachments.value = emptyList()
        _steerableTurn.value = false
        _steerNotice.value = null
        _pendingAsk.value = null
        _contextUsage.value = null
        _contextWindow.value = null
        _yoloEnabled.value = null
        _fastEnabled.value = null
        pendingYolo = null
        // SSE profile switch: reset the reasoning chip to unknown (a sessionless
        // config.get reads the wrong profile's effort) and the personality to
        // default so composeInjectedContext can't inject the previous profile's
        // overlay onto this profile's first SSE turn. The serverReasoningEffort /
        // serverPersonality collectors (gateway) and session.info reconcile after
        // the first turn; on pure SSE the new profile's own SOUL carries instead.
        _selectedReasoningEffort.value = null
        _reasoningDisplay.value = null
        _selectedPersonality.value = "default"
        // The agent display name was stamped above with the pre-reset persona —
        // recompute it now that the overlay is cleared so the header/bubbles read
        // the new profile's base identity, not the old persona.
        handler.activeAgentName = currentAgentDisplayName()
        pendingTruncateOrdinal = null
        handler.clearSessions()
        handler.setSessionId(sessionId)
        selectBackgroundProcessSession(sessionId, contextKey)
        if (sessionId != null) {
            onSessionChanged?.invoke(sessionId)
        }

        if (sessionId == null || client == null) {
            // Fresh draft (or no client to load from) — there's nothing to hold
            // for, so clear the previous transcript now and show the empty state.
            handler.clearMessages()
            _isLoadingHistory.value = false
            _initialChatSettled.value = true
            return
        }

        // Hold the previous transcript on screen while the new profile/session
        // history loads, then swap it atomically with loadMessageHistory(). The
        // old synchronous clearMessages() above is what made a switch read as a
        // "rebuild": the list blanked to an empty/"Loading messages…" state and
        // then repopulated. Holding it means the LazyColumn's per-item
        // animateItem() cross-fades the old bubbles out as the new ones come in.
        // The generation guard still drops a superseded load.
        _isLoadingHistory.value = true
        viewModelScope.launch {
            val stillCurrent = {
                historyLoadGeneration.get() == loadGeneration &&
                    activeProfileContextKey == contextKey &&
                    handler.currentSessionId.value == sessionId
            }
            try {
                val recovered = if (streamingEndpoint == "gateway") {
                    recoverPersistedTurnIfNeeded(gatewayClient, handler, sessionId)
                } else {
                    false
                }
                if (!recovered) {
                    val messages = loadSessionHistory(sessionId)
                    if (stillCurrent()) {
                        handler.loadMessageHistory(messages)
                        if (streamingEndpoint == "gateway") gatewayClient?.prewarm(sessionId)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Don't strand the previous profile's transcript under the new
                // session id if the load throws — clear it (still guarded so a
                // superseded switch can't wipe a newer one's content).
                if (stillCurrent()) {
                    handler.clearMessages()
                }
            } finally {
                // finally (not tail code) so a throwing fetch can't strand
                // the loading flag true or hold the startup gate hostage.
                if (historyLoadGeneration.get() == loadGeneration) {
                    _isLoadingHistory.value = false
                }
                _initialChatSettled.value = true
            }
        }
    }

    private fun fetchPersonalities() {
        val client = apiClient ?: return
        viewModelScope.launch {
            val config = client.getPersonalities()
            _personalityNames.value = config.names
            _defaultPersonality.value = config.defaultName
            personalityPrompts = config.prompts
            _serverModelName.value = config.modelName
            refreshActiveAgentName(relabelGenericMessages = true)
        }
    }

    /**
     * Re-pull server-supplied personality data — the list + configured default
     * (`/api/config`) and, on the gateway, the active value (`config.get`). Safe
     * to call whenever the personality UI becomes visible (agent sheet open) so a
     * personality added/removed/changed server-side shows up without an app
     * reload. The active selection also tracks live via `session.info`
     * ([startGatewayStateSync]); this covers the LIST + cross-session changes.
     */
    fun refreshPersonalities() {
        fetchPersonalities()
        if (streamingEndpoint == "gateway") {
            val client = gatewayClient
            if (client != null && client.connectionState.value == GatewayConnectionState.Ready) {
                viewModelScope.launch { client.getPersonality() }
            }
        }
    }

    /**
     * Re-pull the server's skill catalog (GET /api/skills) so a skill
     * added/removed server-side surfaces without an app reload. Fetched once
     * otherwise (initialize / API-client update). Cheap idempotent GET.
     */
    fun refreshSkills() {
        fetchSkills()
    }

    /**
     * Re-pull the SSE-fallback model list (GET /v1/models). The gateway's curated
     * groups refresh via [refreshModelOptions]; this covers [availableModels] used
     * when no gateway model.options groups exist. Fetched once otherwise.
     */
    fun refreshModels() {
        fetchModels()
    }

    // --- Session management ---

    private val _isLoadingSessions = MutableStateFlow(false)

    /** True while the drawer's session list is being fetched (drives the spinner). */
    val isLoadingSessions: StateFlow<Boolean> = _isLoadingSessions.asStateFlow()

    fun refreshSessions() {
        val handler = chatHandler ?: return
        val generation = sessionRefreshGeneration.incrementAndGet()
        sessionRefreshJob?.cancel()
        sessionRefreshJob = viewModelScope.launch {
            // Treat refreshes over an existing list as quiet background syncs.
            // The drawer keeps rendering the current rows instead of flashing
            // through a loading state whenever it opens or a turn completes.
            _isLoadingSessions.value = handler.sessions.value.isEmpty()
            try {
                // On the gateway, scope the drawer to the ACTIVE PROFILE via the
                // dashboard `/api/sessions?profile=` surface (it opens that
                // profile's own state.db, exactly like the desktop sidebar). The
                // gateway `session.list` RPC can't scope — it always reads the
                // launch profile's DB — so it's deliberately not used here. The
                // api_server `/api/sessions` (one shared DB, no profile concept)
                // is the fallback for connections without a Manage/dashboard
                // session.
                val scoped = if (streamingEndpoint == "gateway") profileSessionLister?.invoke() else null
                val result = scoped ?: apiClient?.listSessionsResult()
                result?.fold(
                    onSuccess = { sessions -> handler.updateSessions(sessions) },
                    onFailure = { error ->
                        if (scoped != null) {
                            // The shared API list belongs to the launch/default
                            // database. Preserve the current profile's rows and
                            // surface the scoped failure instead of leaking a
                            // different profile into the drawer.
                            emitError(error, context = "load_profile_sessions")
                        } else {
                            emitError(error, context = "load_sessions")
                        }
                    },
                )
            } finally {
                if (sessionRefreshGeneration.get() == generation) {
                    _isLoadingSessions.value = false
                }
            }
        }
    }

    private var titleReconcileJob: Job? = null

    /**
     * Re-sync the drawer a couple of times shortly after a turn completes so a
     * title the server writes *after* the response lands replaces the
     * optimistic first-message preview.
     *
     * The server titles a session in a fire-and-forget background thread once
     * the first exchange finishes (upstream agent.title_generator), and it
     * never pushes a rename event — the only way to observe the new title is to
     * re-list. A single post-turn [refreshSessions] races ahead of that write
     * and reads the row before its title (and its flushed message_count/model)
     * settle. Gated to the gateway transport: the api_server SSE/runs surfaces
     * never auto-title, so retrying there would just re-fetch the same null.
     * Cancel-and-replace keeps at most one reconcile in flight regardless of
     * how fast turns complete.
     */
    private fun scheduleTitleReconcile(sessionId: String?) {
        if (sessionId.isNullOrBlank() || streamingEndpoint != "gateway") return
        titleReconcileJob?.cancel()
        titleReconcileJob = viewModelScope.launch {
            for (delayMs in longArrayOf(3_000L, 7_000L)) {
                delay(delayMs)
                refreshSessions()
            }
        }
    }

    fun createNewChat() {
        val client = apiClient ?: return
        val handler = chatHandler ?: return

        // Gateway turns continue as detached siblings; SSE remains exclusive.
        releaseTurnForNavigation(handler)
        cancelAnswerRecovery(settleUi = false)
        val loadGeneration = historyLoadGeneration.incrementAndGet()
        selectBackgroundProcessSession(null)

        // Gateway transport: a new chat is a fresh DRAFT with NO session id.
        // Pre-creating an api_server session would hand the next turn a concrete
        // id, forcing ensureSession down the session.resume branch (the api_ id
        // resumes against the shared launch state.db on the default profile) —
        // which BYPASSES the model/provider/effort/fast binding that only runs on
        // session.create, so the new chat silently runs the DEFAULT model while
        // the picker still shows the last pick. Instead drop the gateway session
        // and null the id; the next send hits ensureSession(null) ->
        // session.create, binding the carried-over model from currentSessionModel().
        // SSE still needs a concrete id, so it keeps pre-creating below. Mirrors
        // the desktop's lazy-create-on-first-send. _selectedModelOverride and the
        // provider/effort picks are intentionally PRESERVED so they carry + bind.
        if (streamingEndpoint == "gateway" && gatewayClient != null) {
            gatewayClient?.clearSession()
            handler.setSessionId(null)
            handler.clearMessages()
            _contextUsage.value = null
            _contextWindow.value = null
            _pendingAsk.value = null
            _yoloEnabled.value = null
            _fastEnabled.value = null
            pendingYolo = null
            onSessionChanged?.invoke(null)
            AppAnalytics.onSessionCreated()
            return
        }

        viewModelScope.launch {
            val selectedProfile = selectedProfileProvider()
            val useIsolatedProfileApi = selectedProfile?.hasIsolatedApi == true
            client.createSessionResult(
                profileName = if (useIsolatedProfileApi) null else selectedProfile?.name,
                model = if (useIsolatedProfileApi) {
                    null
                } else {
                    selectedProfile?.model?.takeIf { it.isNotBlank() }
                },
            ).fold(
                onSuccess = { session ->
                    if (historyLoadGeneration.get() == loadGeneration) {
                        val nowMs = System.currentTimeMillis()
                        val chatSession = ChatSession(
                            sessionId = session.id,
                            title = session.title ?: "New Chat",
                            model = session.model,
                            updatedAt = nowMs,
                            startedAt = nowMs,
                            lastActivityAt = nowMs,
                        )
                        handler.addSession(chatSession)
                        handler.setSessionId(session.id)
                        handler.clearMessages()
                        _contextUsage.value = null
                        _contextWindow.value = null
                        _pendingAsk.value = null
                        _yoloEnabled.value = null
                        _fastEnabled.value = null
                        // Drop any YOLO stashed for the previous draft so it
                        // can't apply to this fresh chat's session.
                        pendingYolo = null
                        onSessionChanged?.invoke(session.id)
                        AppAnalytics.onSessionCreated()
                    }
                },
                onFailure = { error ->
                    if (historyLoadGeneration.get() == loadGeneration) {
                        emitError(error, context = "create_session")
                    }
                }
            )
        }
    }

    /**
     * Start a user-created agent **Thread** (Discord-style "+ New Thread"): mint
     * a fresh phone-platform `chat_id`, blank the chat to a draft, and stash it
     * as [pendingThread]. The first message the user sends opens the conversation
     * on that `chat_id` (the gateway creates the `source=phone` session keyed by
     * it), after which [switchToCreatedThread] swaps the draft for the real
     * session. Gated on relay pairing + "Let Hermes message me" by the caller.
     */
    fun startNewThread(name: String) {
        val handler = chatHandler ?: return
        releaseTurnForNavigation(handler)
        cancelAnswerRecovery(settleUi = false)
        historyLoadGeneration.incrementAndGet()
        val slug = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24)
        val chatId = "t-" + slug.ifBlank { "thread" } + "-" +
            java.util.UUID.randomUUID().toString().take(6)
        pendingThread = PendingThread(chatId = chatId, name = name.trim())
        // Blank draft — the first send routes to the new thread (handled in
        // sendMessageInternal's pendingThread branch).
        gatewayClient?.clearSession()
        handler.setSessionId(null)
        selectBackgroundProcessSession(null)
        handler.clearMessages()
        _contextUsage.value = null
        _contextWindow.value = null
        _pendingAsk.value = null
        onSessionChanged?.invoke(null)
    }

    /**
     * After a "+ New Thread" first send, poll the session list until the gateway
     * has created the new `source=phone` session, then switch to it (loading its
     * history) and apply the user's chosen name. The new session is found by
     * *difference* — the `source=phone` session id not present before the send —
     * because the sessions API exposes neither `chat_id` nor `session_key` (the
     * id is just a timestamp). Records sessionId → chat_id so later replies in
     * this thread route correctly. Best-effort: if it doesn't appear within the
     * window the thread still exists and shows in the drawer's Threads filter.
     */
    private fun switchToCreatedThread() {
        val creating = creatingThread ?: return
        viewModelScope.launch {
            for (delayMs in longArrayOf(900L, 1300L, 1800L, 2500L, 3500L, 4500L)) {
                delay(delayMs)
                refreshSessions()
                delay(400L) // let the refresh job land in the sessions flow
                val match = chatHandler?.sessions?.value?.firstOrNull {
                    it.source == "phone" && it.sessionId !in creating.knownIds
                }
                if (match != null) {
                    threadChatIds[match.sessionId] = creating.chatId
                    creatingThread = null
                    // The user's name is authoritative (Discord-style): apply it
                    // as a local override so the server's async auto-titler can't
                    // clobber it, and also best-effort rename the server session
                    // for other surfaces.
                    if (creating.name.isNotBlank()) {
                        chatHandler?.setUserThreadName(match.sessionId, creating.name)
                        onSaveThreadName?.invoke(match.sessionId, creating.name)
                        if (match.title != creating.name) {
                            renameSession(match.sessionId, creating.name)
                        }
                    }
                    switchSession(match.sessionId)
                    return@launch
                }
            }
            creatingThread = null // gave up — it still appears in the drawer
        }
    }

    fun switchSession(sessionId: String) {
        apiClient ?: return
        val handler = chatHandler ?: return

        // Keep a Gateway sibling alive and detach its callbacks. SSE remains a
        // single exclusive stream and is interrupted on navigation.
        releaseTurnForNavigation(handler)
        cancelAnswerRecovery(settleUi = false)
        val loadGeneration = historyLoadGeneration.incrementAndGet()

        handler.setSessionId(sessionId)
        selectBackgroundProcessSession(sessionId)
        handler.clearMessages()
        _contextUsage.value = null
        _contextWindow.value = null
        _pendingAsk.value = null
        _yoloEnabled.value = null
        _fastEnabled.value = null
        // The switched-to session owns its own YOLO state (session.info
        // reconciles) — drop any pick stashed for a different draft.
        pendingYolo = null
        onSessionChanged?.invoke(sessionId)
        AppAnalytics.onSessionSwitched()
        // Recover a retained live turn before doing an ordinary history load.
        // This avoids a concurrent list fetch wiping the restored streaming
        // placeholder after session.activate has rebound its callbacks.
        _isLoadingHistory.value = true
        viewModelScope.launch {
            val recovered = if (streamingEndpoint == "gateway") {
                recoverPersistedTurnIfNeeded(gatewayClient, handler, sessionId)
            } else {
                false
            }
            if (!recovered) {
                val messages = loadSessionHistory(sessionId)
                if (
                    historyLoadGeneration.get() == loadGeneration &&
                    handler.currentSessionId.value == sessionId
                ) {
                    handler.loadMessageHistory(messages)
                    if (streamingEndpoint == "gateway") gatewayClient?.prewarm(sessionId)
                }
            }
            if (historyLoadGeneration.get() == loadGeneration) {
                _isLoadingHistory.value = false
            }
        }
    }

    /**
     * Resume a session by ID (e.g., from persisted last session).
     * Only loads history, doesn't create a new session.
     */
    fun resumeSession(sessionId: String) {
        switchSession(sessionId)
    }

    fun deleteSession(sessionId: String) {
        val client = apiClient ?: return
        val handler = chatHandler ?: return

        // Save reference before removing (for rollback on failure)
        val removedSession = handler.sessions.value.find { it.sessionId == sessionId }

        // Optimistic removal
        handler.removeSession(sessionId)
        if (handler.currentSessionId.value == null) {
            selectBackgroundProcessSession(null)
        }
        if (handler.currentSessionId.value == null) {
            onSessionChanged?.invoke(null)
        }

        viewModelScope.launch {
            // On the gateway, the session lives in the ACTIVE PROFILE's own
            // state.db, so it must be deleted through the dashboard
            // `/api/sessions/{id}?profile=` surface — the same scoping
            // refreshSessions() uses for the listing. The unscoped api_server
            // delete leaves a non-default profile's row intact and the next
            // profile-scoped list resurrects it. Off the gateway (one shared
            // api_server DB, no profiles) the plain delete is correct. A missing
            // gateway deleter is a wiring failure, not permission to cross DBs.
            val success = if (streamingEndpoint == "gateway") {
                profileSessionDeleter?.invoke(sessionId) ?: false
            } else {
                client.deleteSession(sessionId)
            }
            if (success) {
                // Re-fetch so a server that still has the row can't leave it
                // resurrected in the drawer; mirrors session create's refresh.
                refreshSessions()
            } else if (removedSession != null) {
                // Restore on failure
                handler.addSession(removedSession)
                emitError(
                    IllegalStateException("Profile-scoped session delete failed"),
                    context = "delete_profile_session",
                )
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        val client = apiClient ?: return
        val handler = chatHandler ?: return

        val previousTitle = handler.sessions.value.find { it.sessionId == sessionId }?.title
        // Optimistic rename
        handler.renameSessionLocal(sessionId, newTitle)

        viewModelScope.launch {
            // On the gateway, the session lives in the ACTIVE PROFILE's own
            // state.db, so the rename must go through the dashboard
            // `PATCH /api/sessions/{id}?profile=` surface — the write twin of the
            // scoped list/delete. The unscoped api_server rename patches the
            // shared DB, so a non-default profile's title would silently never
            // persist. Off the gateway (one shared api_server DB, no profiles)
            // the plain rename is correct. A missing gateway renamer is a wiring
            // failure, not permission to cross databases.
            if (streamingEndpoint == "gateway") {
                val scoped = profileSessionRenamer?.invoke(sessionId, newTitle)
                if (scoped != true) {
                    previousTitle?.let { handler.renameSessionLocal(sessionId, it) }
                    emitError(
                        IllegalStateException("Profile-scoped session rename failed"),
                        context = "rename_profile_session",
                    )
                }
            } else {
                client.renameSession(sessionId, newTitle)
            }
        }
    }

    // --- Message sending ---

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        recordRecentPrompt(text)

        // Demo / Explore mode: there is no server, but a silently dead Send
        // button reads as broken. Echo the user's text and answer with an
        // honest canned notice through the real pipeline — clientOnly
        // bubbles, zero network, wiped with the rest of the transcript on
        // demo exit (exitDemoMode → clearMessages).
        if (demoModeProvider()) {
            val demoHandler = demoChatHandlerProvider() ?: return
            val now = System.currentTimeMillis()
            demoHandler.addUserMessage(
                ChatMessage(
                    id = "demo-composer-user-${java.util.UUID.randomUUID()}",
                    role = MessageRole.USER,
                    content = text.trim(),
                    timestamp = now,
                    clientOnly = true,
                ),
            )
            demoHandler.addUserMessage(
                DemoContent.composerReply(
                    id = "demo-composer-reply-${java.util.UUID.randomUUID()}",
                    nowMs = now + 1L,
                ),
            )
            return
        }

        val client = apiClient ?: return
        val handler = chatHandler ?: return

        // Server slash commands (gateway transport only) execute via
        // slash.exec / command.dispatch instead of becoming a prompt.
        if (activeStream == null && maybeHandleServerSlashCommand(text.trim())) return

        // Mid-turn: steer on the gateway transport, queue everywhere else.
        if (activeStream != null) {
            if (_steerableTurn.value && streamingEndpoint == "gateway") {
                steerActiveTurn(text.trim())
            } else {
                _queuedMessages.update { it + text.trim() }
            }
            return
        }

        sendMessageInternal(client, handler, text)
    }

    /**
     * Inject [text] into the in-flight gateway turn via `session.steer`.
     * Accepted steers land in the next tool batch's last result — the
     * server records them inside a tool result, NOT as a user message, so
     * we add a local `steer-` bubble that [ChatHandler.loadMessageHistory]
     * preserves. Rejected/failed steers fall back to the existing queue
     * with a one-line caption so the send never silently vanishes.
     */
    private fun steerActiveTurn(text: String) {
        val handler = chatHandler ?: return
        val gateway = gatewayClient
        if (gateway == null) {
            _queuedMessages.update { it + text }
            return
        }
        viewModelScope.launch {
            when (gateway.steer(text)) {
                SteerResult.Queued -> {
                    handler.addUserMessage(
                        ChatMessage(
                            id = "steer-${UUID.randomUUID()}",
                            role = MessageRole.USER,
                            content = text,
                            timestamp = System.currentTimeMillis(),
                            // Steered text lands in a server-side tool result,
                            // never as a user message, so this echo has no server
                            // row — keep it across the post-turn reload.
                            clientOnly = true,
                        )
                    )
                }
                SteerResult.Rejected, SteerResult.Failed -> {
                    if (activeStream != null) {
                        _queuedMessages.update { it + text }
                        _steerNotice.value = "Queued — delivers after this turn"
                    } else {
                        // Turn ended while the steer RPC was in flight —
                        // send it as a normal next-turn prompt instead.
                        val client = apiClient
                        if (client != null) sendMessageInternal(client, handler, text)
                    }
                }
            }
        }
    }

    fun sendVoiceMessage(text: String, interfaceContextPrompt: String) {
        if (text.isBlank()) return
        nextInterfaceContextPrompt = interfaceContextPrompt.takeIf { it.isNotBlank() }
        sendMessage(text)
    }

    /**
     * Append a local-only voice-intent trace to chat history. Used by
     * VoiceViewModel so phone-control utterances ("open Chrome", "text
     * Sam") leave a visible record in the chat scroll instead of vanishing
     * into a side channel. Local-only — does not hit the server, does not
     * call the LLM, does not stream. See [ChatHandler.appendLocalVoiceIntentTrace]
     * for the full design + why this isn't enough on its own to give the
     * LLM context for follow-up turns (server session sync is v0.4.1).
     */
    fun recordVoiceIntent(
        userText: String,
        actionDescription: String,
        voiceIntent: VoiceIntentTrace? = null,
    ) {
        val handler = chatHandler ?: return
        handler.appendLocalVoiceIntentTrace(userText, actionDescription, voiceIntent)
    }

    /**
     * Append a second trace bubble showing the REAL outcome of a voice
     * intent dispatch after the safety modal resolves and the phone-side
     * executor returns. Called by [VoiceViewModel] via the
     * `onDispatchResult` callback wired into the sideload voice handler's
     * factory. Pre-0.4.0 there was no post-dispatch feedback at all — the
     * handler was fire-and-forget and the user never knew whether an SMS
     * actually sent until they opened the Messages app. See
     * [LocalDispatchResult] for the shape of the captured outcome.
     *
     * Renders as markdown per category so the "AGENT" bubble in voice
     * mode (or the full markdown bubble in chat) reads naturally:
     *
     *  - success                  → **Send SMS — sent**
     *  - 403 user_denied          → **Send SMS — cancelled by you**
     *  - 403 bridge_disabled      → **Send SMS — agent control is off**\n...
     *  - 403 permission_denied    → **Send SMS — permission needed**\n...
     *  - 5xx / dispatch_exception → **Send SMS — failed**\n...
     */
    fun recordVoiceIntentResult(
        intentLabel: String,
        result: LocalDispatchResult,
        voiceIntent: VoiceIntentTrace? = null,
    ) {
        val handler = chatHandler ?: return
        // Delegate to the package-level formatter in ChatHandler.kt so
        // voice-mode and chat-mode android_* completions render the same
        // markdown for the same outcome. `agentName` defaults to
        // "Voice action" here (voice origin); chat parity uses
        // "Phone action" via the ChatHandler-side caller.
        val description = formatPhoneActionResult(intentLabel, result)
        handler.appendLocalVoiceIntentResult(
            description = description,
            voiceIntent = voiceIntent,
        )
    }

    /**
     * Handle a tap on a rich card action button. Records the dispatch so
     * the card collapses into its "chose: X" state, then routes the
     * action value per [com.hermesandroid.relay.data.HermesCardAction.mode]:
     *
     *  - [com.hermesandroid.relay.data.HermesCardAction.Modes.SEND_TEXT]
     *    (default): sends [action.value] as a new user message. For an
     *    `approval_request` card with `value = "approve"`, the agent sees
     *    the literal "approve" in its next turn and reacts accordingly.
     *  - [com.hermesandroid.relay.data.HermesCardAction.Modes.SLASH_COMMAND]:
     *    still routes through `sendMessage` — slash commands are plain
     *    text to the server (`/approve` is just text starting with a `/`),
     *    so there's no separate code path to carve out.
     *  - [com.hermesandroid.relay.data.HermesCardAction.Modes.OPEN_URL]:
     *    handled at the UI layer via
     *    [com.hermesandroid.relay.ui.components.handleCardActionExternally]
     *    because launching an Intent needs a Context. The UI layer
     *    records the dispatch via this method BEFORE launching the URL,
     *    so the card collapses even if the browser launch fails.
     */
    fun dispatchCardAction(
        messageId: String,
        cardKey: String,
        action: com.hermesandroid.relay.data.HermesCardAction,
    ) {
        val handler = chatHandler ?: return
        // Ask answers route straight to the gateway respond RPCs —
        // answerAsk records its own (sanitized) dispatch stamp, so don't
        // double-stamp here.
        if (action.mode == com.hermesandroid.relay.data.HermesCardAction.Modes.SUBMIT_ASK) {
            answerAsk(messageId, cardKey, action.value)
            return
        }
        handler.recordCardDispatch(messageId, cardKey, action.value)
        when (action.mode) {
            com.hermesandroid.relay.data.HermesCardAction.Modes.OPEN_URL -> {
                // UI layer launched the intent — nothing further to send
                // to the server. If we later want the LLM to SEE that the
                // user followed the link, the caller can pass a tiny
                // synthetic text on its own.
            }
            else -> sendMessage(action.value)
        }
    }

    // === Gateway interactive asks ===

    /**
     * Build the local ask card for a gateway interaction request and track
     * it as the pending ask. Card id (= cardKey) is the ask's `request_id`,
     * or `approval-<sid>-<ts>` for approvals (which correlate per-session).
     * Secrets/passwords never touch chat content: the cards carry input
     * slots whose submissions flow through [answerAsk] only.
     */
    private fun presentInteractionAsk(
        handler: ChatHandler,
        ask: GatewayAsk,
        restored: ChatTurnAskCheckpoint? = null,
    ) {
        val now = restored?.receivedAt ?: System.currentTimeMillis()
        val cardKey = restored?.cardKey ?: ask.requestId
            ?: "approval-${handler.currentSessionId.value ?: "session"}-$now"
        val expiresAt = ask.timeoutSeconds.takeIf { it > 0 }?.let { now + it * 1_000L }
        val card = when (ask.kind) {
            GatewayAsk.Kind.APPROVAL -> HermesCard(
                type = HermesCard.BuiltInTypes.ASK_APPROVAL,
                title = (appContext?.getString(R.string.chat_approval_title) ?: "Approval requested"),
                accent = HermesCard.Accents.WARNING,
                fields = listOf(HermesCardField("Command", ask.text)),
                actions = listOf(
                    HermesCardAction(
                        label = appContext?.getString(R.string.chat_approval_approve) ?: "Approve",
                        value = "approve",
                        style = HermesCardAction.Styles.PRIMARY,
                        mode = HermesCardAction.Modes.SUBMIT_ASK,
                    ),
                    HermesCardAction(
                        label = appContext?.getString(R.string.chat_approval_deny) ?: "Deny",
                        value = "deny",
                        style = HermesCardAction.Styles.DANGER,
                        mode = HermesCardAction.Modes.SUBMIT_ASK,
                    ),
                ),
                id = cardKey,
            )

            GatewayAsk.Kind.CLARIFY -> HermesCard(
                type = HermesCard.BuiltInTypes.ASK_CLARIFY,
                title = (appContext?.getString(R.string.chat_approval_clarify_title) ?: "Hermes needs clarification"),
                body = ask.text,
                accent = HermesCard.Accents.INFO,
                id = cardKey,
                input = HermesCardInput(
                    kind = if (ask.choices.isNullOrEmpty()) {
                        HermesCardInput.Kinds.TEXT
                    } else {
                        HermesCardInput.Kinds.CHOICE
                    },
                    choices = ask.choices.orEmpty(),
                    allowFreeText = true,
                    expiresAtMillis = expiresAt,
                ),
            )

            GatewayAsk.Kind.SUDO -> HermesCard(
                type = HermesCard.BuiltInTypes.ASK_SUDO,
                title = (appContext?.getString(R.string.chat_approval_sudo_title) ?: "Elevated permission requested"),
                body = ask.text.takeIf { it != "Elevated permissions requested" },
                accent = HermesCard.Accents.DANGER,
                id = cardKey,
                input = HermesCardInput(
                    kind = HermesCardInput.Kinds.SECRET,
                    masked = true,
                    holdToConfirm = true,
                    expiresAtMillis = expiresAt,
                ),
                // Empty password = decline upstream — give the deny path a
                // button (same wire shape as SECRET's Skip).
                actions = listOf(
                    HermesCardAction(
                        label = appContext?.getString(R.string.chat_approval_deny) ?: "Deny",
                        value = "",
                        style = HermesCardAction.Styles.DANGER,
                        mode = HermesCardAction.Modes.SUBMIT_ASK,
                    ),
                ),
            )

            GatewayAsk.Kind.SECRET -> HermesCard(
                type = HermesCard.BuiltInTypes.ASK_SECRET,
                title = (appContext?.getString(R.string.chat_approval_secret_title) ?: "Secret requested"),
                subtitle = ask.envVar?.let { "Stored as $it" },
                body = ask.text,
                accent = HermesCard.Accents.WARNING,
                id = cardKey,
                input = HermesCardInput(
                    kind = HermesCardInput.Kinds.SECRET,
                    masked = true,
                    expiresAtMillis = expiresAt,
                ),
                // Empty value = skip upstream — expose it as a plain action
                // so the wire's skip path has a button.
                actions = listOf(
                    HermesCardAction(
                        label = appContext?.getString(R.string.chat_approval_skip) ?: "Skip",
                        value = "",
                        style = HermesCardAction.Styles.SECONDARY,
                        mode = HermesCardAction.Modes.SUBMIT_ASK,
                    ),
                ),
            )
        }
        val messageId = restored?.messageId ?: "ask-$cardKey"
        handler.appendAskCardMessage(messageId, card)
        _pendingAsk.value = PendingAsk(
            ask = ask,
            messageId = messageId,
            cardKey = cardKey,
            receivedAt = now,
        )
        scheduleCheckpointWrite(immediate = true)
    }

    /**
     * Answer the pending gateway ask. Routes per kind to the matching
     * respond RPC; collapses the card via [ChatHandler.recordCardDispatch]
     * only after the RPC succeeds — a failed respond leaves the card live
     * for a retry. The stamp is SANITIZED: non-empty sudo/secret values are
     * recorded as [HermesCardInput.SECRET_PROVIDED_STAMP] (never the real
     * value), and ask dispatches are excluded from [CardDispatchSyncBuilder]
     * entirely. Taps on a stale card (ask already resolved/expired) get a
     * system notice instead of a dead RPC.
     */
    fun answerAsk(messageId: String, cardKey: String, value: String) {
        val handler = chatHandler ?: return
        val pending = _pendingAsk.value
        if (pending == null || pending.cardKey != cardKey) {
            handler.addSystemNotice("This request is no longer active.")
            return
        }
        val gateway = gatewayClient
        if (gateway == null) {
            emitError(Exception("Gateway is not connected"), context = "send_message")
            return
        }
        // In-flight guard: one respond RPC per card at a time.
        if (!answeredAskIds.add(cardKey)) return
        val ask = pending.ask
        val stampValue = when (ask.kind) {
            // Empty sudo password = decline — stamp matches the Deny action
            // value so the collapse row shows the action label.
            GatewayAsk.Kind.SUDO ->
                if (value.isEmpty()) "" else HermesCardInput.SECRET_PROVIDED_STAMP
            GatewayAsk.Kind.SECRET ->
                if (value.isEmpty()) "" else HermesCardInput.SECRET_PROVIDED_STAMP
            else -> value
        }
        viewModelScope.launch {
            val requestId = ask.requestId
            val result = when (ask.kind) {
                GatewayAsk.Kind.APPROVAL -> gateway.respondApproval(choice = value)
                GatewayAsk.Kind.CLARIFY ->
                    requestId?.let { gateway.respondClarify(it, value) }
                        ?: Result.failure(GatewayRpcException("ask has no request id"))
                GatewayAsk.Kind.SUDO ->
                    requestId?.let { gateway.respondSudo(it, value) }
                        ?: Result.failure(GatewayRpcException("ask has no request id"))
                GatewayAsk.Kind.SECRET ->
                    requestId?.let { gateway.respondSecret(it, value) }
                        ?: Result.failure(GatewayRpcException("ask has no request id"))
            }
            result.fold(
                onSuccess = { response ->
                    if (response == GatewayAskResponse.EXPIRED) {
                        expirePendingAsk(
                            GatewayAskExpiry(
                                kind = ask.kind,
                                requestId = ask.requestId,
                            ),
                        )
                        return@fold
                    }
                    // Collapse only after the server confirms — a failed RPC
                    // must leave the card answerable for a retry.
                    handler.recordCardDispatch(pending.messageId, cardKey, stampValue)
                    if (_pendingAsk.value === pending) {
                        _pendingAsk.value = null
                        scheduleCheckpointWrite(immediate = true)
                    }
                },
                onFailure = { e ->
                    answeredAskIds.remove(cardKey)
                    emitError(e, context = "send_message")
                },
            )
        }
    }

    /**
     * Collapse only the server-expired interaction. Request-scoped asks must
     * match exactly; approvals are session-scoped and match by kind. This also
     * handles late `*.respond` RPCs that return `{status:"expired"}` or
     * `{resolved:0}` before the expiry event reaches the socket.
     */
    private fun expirePendingAsk(expiry: GatewayAskExpiry) {
        val pending = _pendingAsk.value ?: return
        if (pending.ask.kind != expiry.kind) return
        if (expiry.kind != GatewayAsk.Kind.APPROVAL) {
            val requestId = expiry.requestId?.takeIf { it.isNotBlank() } ?: return
            if (pending.ask.requestId != requestId) return
        }
        _pendingAsk.value = null
        answeredAskIds.remove(pending.cardKey)
        scheduleCheckpointWrite(immediate = true)
        chatHandler?.recordCardDispatch(
            pending.messageId,
            pending.cardKey,
            HermesCardDispatch.EXPIRED_STAMP,
        )
    }

    /**
     * Drop pending-ask UI state when the turn is torn down, stamping a
     * still-open approval card with [approvalStamp] so its buttons don't
     * outlive the ask — "deny" after an interrupt (`session.interrupt`
     * force-denies server-side), "Resolved" when the turn completed without
     * an answer. Timed asks self-collapse via their countdown.
     */
    private fun clearPendingAsk(approvalStamp: String) {
        val pending = _pendingAsk.value ?: return
        _pendingAsk.value = null
        scheduleCheckpointWrite(immediate = true)
        if (pending.ask.kind == GatewayAsk.Kind.APPROVAL) {
            chatHandler?.recordCardDispatch(pending.messageId, pending.cardKey, approvalStamp)
        }
    }

    // === Edit & regenerate (gateway only) ===

    /**
     * Edit-and-resend: rerun the conversation from the user message
     * [userMessageId] with [newText]. Computes the 0-based ordinal of that
     * message among role==USER messages (excluding phone-local traces the
     * server never saw), truncates the local list from it, and dispatches a
     * gateway turn carrying `truncate_before_user_ordinal`. Local/server
     * divergence self-heals via the post-turn history reload.
     *
     * @return false when the edit could not be dispatched (turn in flight,
     *   non-gateway endpoint, missing client, ordinal failure) — the caller
     *   must keep the edit state so no text is lost.
     */
    fun regenerateFromMessage(userMessageId: String, newText: String): Boolean {
        if (newText.isBlank()) return false
        val client = apiClient ?: return false
        val handler = chatHandler ?: return false
        if (streamingEndpoint != "gateway" || gatewayClient == null) return false
        if (activeStream != null) return false
        val snapshot = handler.messages.value
        // At the local cap the oldest messages were trimmed — the computed
        // USER ordinal may undercount the server's and truncate wrong.
        if (snapshot.size >= ChatHandler.MAX_MESSAGES) {
            handler.addSystemNotice("This conversation is too long to edit safely from the phone.")
            return false
        }
        val target = snapshot.firstOrNull { it.id == userMessageId } ?: return false
        if (target.role != MessageRole.USER) return false
        val ordinal = snapshot
            .filter { it.role == MessageRole.USER }
            .filterNot { it.id.startsWith("voice-intent-") || it.id.startsWith("steer-") }
            .indexOfFirst { it.id == userMessageId }
        if (ordinal < 0) return false
        handler.truncateMessagesFrom(userMessageId)
        pendingTruncateOrdinal = ordinal
        sendMessageInternal(client, handler, newText)
        return true
    }

    // === Server slash commands (gateway transport) ===

    private fun fetchServerCommands(client: GatewayChatClient) {
        viewModelScope.launch {
            val catalog = client.commandsCatalog().getOrNull() ?: return@launch
            // The client may have been swapped while the RPC was in flight.
            if (gatewayClient !== client) return@launch
            _serverCommands.value = parseCommandsCatalog(catalog)
        }
    }

    /**
     * True when [text] looks like a gateway slash command. In that case it
     * executes via slash.exec / command.dispatch, or returns a status bubble
     * explaining why it cannot run. We fetch the server catalog at send time
     * so a cold chat can still run commands before the first completed turn.
     */
    private fun maybeHandleServerSlashCommand(text: String): Boolean {
        if (!text.startsWith("/")) return false
        val rawName = text.removePrefix("/").substringBefore(' ').trim()
        val normalizedName = normalizeSlashCommandName(rawName) ?: return false
        val handler = chatHandler ?: return true

        // `/personality` is a picker command upstream (model/skin/personality) —
        // the desktop + TUI never raw-forward it: a bare command opens the picker
        // and `/personality <name>` applies via config.set. Mirror that here
        // instead of slash.exec (which on mobile dead-ends and only leaves a
        // fragile notice). Handled before the gateway-route gate so it also
        // works on the SSE fallbacks via the per-turn injection path.
        if (normalizedName == "personality") {
            val arg = text.substringAfter(' ', "").trim()
            if (arg.isBlank()) {
                _openPersonalityPicker.tryEmit(Unit)
            } else if (AgentDisplay.isClearedPersonality(arg)) {
                selectPersonality("none")
                handler.addSystemNotice("Personality cleared — no overlay.")
            } else {
                selectPersonality(arg.lowercase())
                handler.addSystemNotice(
                    "Personality → ${arg.trim().replaceFirstChar { it.uppercase() }}"
                )
            }
            return true
        }

        // `/model` is the sibling picker command: a bare `/model` opens the model
        // picker (like the desktop), while `/model <args>` stays a real switch the
        // gateway applies via slash.exec below.
        if (normalizedName == "model" && text.substringAfter(' ', "").isBlank()) {
            _openModelPicker.tryEmit(Unit)
            return true
        }

        if (streamingEndpoint != "gateway") {
            handler.addSystemNotice("Slash commands are available when chat is using the Hermes gateway route.")
            return true
        }

        val gateway = gatewayClient
        if (gateway == null) {
            handler.addSystemNotice("Slash commands need the Hermes gateway connection. Check Manage sign-in and connection status.")
            return true
        }

        mobileBlockedSlashNotice(normalizedName)?.let { notice ->
            handler.addSystemNotice(notice)
            return true
        }

        viewModelScope.launch {
            val commands = currentOrRefreshedServerCommands(gateway, handler) ?: return@launch
            val known = commands.any {
                normalizeSlashCommandName(it.command.removePrefix("/").substringBefore(' ')) == normalizedName
            }
            if (!known) {
                handler.addSystemNotice("/$rawName is not available on this Hermes gateway. Use /commands to browse supported commands.")
                return@launch
            }
            runServerSlashCommand(gateway, handler, text, depth = 0)
        }
        return true
    }

    private suspend fun currentOrRefreshedServerCommands(
        gateway: GatewayChatClient,
        handler: ChatHandler,
    ): List<SlashCommand>? {
        val current = _serverCommands.value
        if (current.isNotEmpty()) return current
        val catalog = gateway.commandsCatalog(connectIfNeeded = true).getOrElse { e ->
            handler.addSystemNotice("Slash commands are unavailable: ${e.message ?: "command catalog could not be loaded"}")
            return null
        }
        if (gatewayClient !== gateway) return null
        val parsed = parseCommandsCatalog(catalog)
        _serverCommands.value = parsed
        return parsed
    }

    /**
     * Upstream dispatch order: `slash.exec` first; error 4018 (pending-input
     * / skill / blocked command) falls through to `command.dispatch`, whose
     * result union routes per type — exec/plugin/skill output becomes a
     * system notice, `send` re-enters the normal send path (notice first),
     * `prefill` lands in the composer, `alias` re-dispatches its target.
     */
    private suspend fun runServerSlashCommand(
        gateway: GatewayChatClient,
        handler: ChatHandler,
        commandLine: String,
        depth: Int,
    ) {
        if (depth > 2) {
            handler.addSystemNotice("Command alias loop detected: $commandLine")
            return
        }
        val name = commandLine.removePrefix("/").substringBefore(' ')

        val exec = gateway.slashExec(commandLine)
        exec.onSuccess { result ->
            val output = result.stringValue("output") ?: "(no output)"
            val warning = result.stringValue("warning")
            handler.addSystemNotice(listOfNotNull(output, warning).joinToString("\n\n"))
            return
        }
        val failure = exec.exceptionOrNull()
        val code = (failure as? GatewayRpcException)?.code
        // 4018 = "use command.dispatch"; "no live session" happens before
        // the first gateway turn — command.dispatch works sessionless for
        // quick/plugin commands, so fall through for that too.
        val fallThrough = code == 4018 ||
            failure?.message?.contains("no live session", ignoreCase = true) == true
        if (!fallThrough) {
            handler.addSystemNotice("/$name failed: ${failure?.message ?: "unknown error"}")
            return
        }

        val arg = commandLine.substringAfter(' ', "").trim().takeIf { it.isNotBlank() }
        gateway.commandDispatch(name, arg).fold(
            onSuccess = { result ->
                when (result.stringValue("type")) {
                    "exec", "plugin" ->
                        handler.addSystemNotice(result.stringValue("output") ?: "(no output)")
                    "skill" ->
                        handler.addSystemNotice(result.stringValue("message") ?: "(no output)")
                    "alias" -> {
                        val target = result.stringValue("target")
                        if (target != null) {
                            val line = if (target.startsWith("/")) target else "/$target"
                            runServerSlashCommand(gateway, handler, line, depth + 1)
                        }
                    }
                    "send" -> {
                        result.stringValue("notice")?.let { handler.addSystemNotice(it) }
                        result.stringValue("message")?.let { sendMessage(it) }
                    }
                    "prefill" -> {
                        result.stringValue("notice")?.let { handler.addSystemNotice(it) }
                        result.stringValue("message")?.let { _composerPrefill.tryEmit(it) }
                    }
                    else ->
                        handler.addSystemNotice(result.stringValue("output") ?: "Command completed.")
                }
            },
            onFailure = { e ->
                handler.addSystemNotice("/$name failed: ${e.message ?: "unknown error"}")
            },
        )
    }

    // === Turn-complete notification ===

    /**
     * Post the one-shot "Hermes finished" notification when the turn ends
     * while the app is backgrounded. Never fires for cancelled streams
     * (errors don't reach this path at all — they end via onErrorCb).
     */
    private fun maybeNotifyTurnComplete(handler: ChatHandler, messageId: String) {
        val ctx = appContext ?: return
        if (!notifyOnTurnComplete) return
        if (intentionallyCancelled) return
        if (AppForegroundTracker.isForeground.value) return
        val msg = handler.messages.value.lastOrNull {
            it.id == messageId && it.role == MessageRole.ASSISTANT
        } ?: handler.messages.value.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return
        val toolCount = msg.toolCalls.size
        val durationSeconds = ((System.currentTimeMillis() - msg.timestamp) / 1_000L)
            .takeIf { it > 0 }
        TurnCompleteNotifier.notifyTurnComplete(
            context = ctx,
            agentName = handler.activeAgentName,
            responseText = msg.content.trim().ifBlank { "Hermes finished responding." },
            toolCount = toolCount,
            durationSeconds = durationSeconds,
        )
    }

    // === Dropped-stream answer recovery (issue #166) ===

    /** Rebind lightweight checkpoint observers whenever the active handler changes. */
    private fun ensureCheckpointObservers() {
        val handler = chatHandler
        if (handler != null && checkpointStatusJob == null) {
            checkpointStatusJob = viewModelScope.launch {
                handler.turnStatus.collect { scheduleCheckpointWrite() }
            }
        }
        if (checkpointForegroundJob == null) {
            checkpointForegroundJob = viewModelScope.launch {
                AppForegroundTracker.isForeground.collect { foreground ->
                    if (!foreground) scheduleCheckpointWrite(immediate = true)
                }
            }
        }
    }

    private fun beginTurnCheckpoint(
        handler: ChatHandler,
        sessionId: String,
        transport: String,
        userMessageId: String,
        userText: String,
        assistantMessageId: String,
        assistantTimestamp: Long,
    ) {
        val userMessage = handler.messages.value.lastOrNull { it.id == userMessageId }
        val snapshot = handler.messages.value
        val generation = checkpointGeneration.incrementAndGet()
        checkpointWriteJob?.cancel()
        activeTurnCheckpointSeed = ActiveTurnCheckpointSeed(
            contextKey = activeProfileContextKey,
            sessionId = sessionId,
            liveSessionId = null,
            transport = transport,
            userMessageId = userMessageId,
            userText = userMessage?.content ?: userText,
            userTimestamp = userMessage?.timestamp ?: System.currentTimeMillis(),
            assistantMessageId = assistantMessageId,
            assistantTimestamp = assistantTimestamp,
            priorUserMessageCount = (snapshot.count { it.role == MessageRole.USER } - 1)
                .coerceAtLeast(0),
            baselineAssistantCount = snapshot.count {
                it.role == MessageRole.ASSISTANT && !it.clientOnly
            },
            startedAt = assistantTimestamp,
        )
        // Remove a prior turn for THIS session immediately. Detached sibling
        // turns retain their own durable checkpoints while this chat starts a
        // new one.
        chatTurnCheckpointStore?.let { store ->
            viewModelScope.launch {
                checkpointMutex.withLock {
                    if (generation == checkpointGeneration.get()) {
                        val contextKey = activeTurnCheckpointSeed?.contextKey
                        if (contextKey != null) {
                            runCatching { store.remove(contextKey, sessionId) }
                        }
                    }
                }
            }
        }
    }

    private fun adoptTurnCheckpoint(checkpoint: ChatTurnCheckpoint) {
        checkpointGeneration.incrementAndGet()
        checkpointWriteJob?.cancel()
        activeTurnCheckpointSeed = ActiveTurnCheckpointSeed(
            contextKey = checkpoint.contextKey,
            sessionId = checkpoint.sessionId,
            liveSessionId = checkpoint.liveSessionId,
            transport = checkpoint.transport,
            userMessageId = checkpoint.user.id,
            userText = checkpoint.user.content,
            userTimestamp = checkpoint.user.timestamp,
            assistantMessageId = checkpoint.assistant.id,
            assistantTimestamp = checkpoint.assistant.timestamp,
            priorUserMessageCount = checkpoint.priorUserMessageCount,
            baselineAssistantCount = checkpoint.baselineAssistantCount,
            startedAt = checkpoint.startedAt,
        )
    }

    private fun updateTurnCheckpointSession(sessionId: String) {
        activeTurnCheckpointSeed?.let { seed ->
            seed.sessionId = sessionId
            seed.liveSessionId = gatewayClient?.currentLiveSessionId(sessionId)
            scheduleCheckpointWrite(immediate = true)
        }
    }

    private fun updateTurnCheckpointAssistantId(messageId: String) {
        activeTurnCheckpointSeed?.assistantMessageId = messageId
        scheduleCheckpointWrite(immediate = true)
    }

    private fun updateTurnCheckpointTransport(transport: String) {
        activeTurnCheckpointSeed?.transport = transport
        scheduleCheckpointWrite()
    }

    private fun buildTurnCheckpoint(): ChatTurnCheckpoint? {
        val seed = activeTurnCheckpointSeed ?: return null
        val handler = chatHandler ?: return null
        if (seed.transport != "gateway" && seed.transport != "sessions") return null
        val contextKey = seed.contextKey ?: activeProfileContextKey ?: return null
        val sessionId = seed.sessionId.takeIf { it.isNotBlank() } ?: return null
        if (handler.currentSessionId.value != sessionId) return null
        val assistant = handler.messages.value.lastOrNull { it.id == seed.assistantMessageId }
            ?: handler.messages.value.lastOrNull {
                it.role == MessageRole.ASSISTANT && it.isStreaming && !it.clientOnly
            }
            ?: return null
        if (assistant.id != seed.assistantMessageId) seed.assistantMessageId = assistant.id
        val pending = _pendingAsk.value
        val now = System.currentTimeMillis()
        return ChatTurnCheckpoint(
            contextKey = contextKey,
            sessionId = sessionId,
            liveSessionId = gatewayClient?.currentLiveSessionId(sessionId) ?: seed.liveSessionId,
            transport = seed.transport,
            user = ChatTurnUserCheckpoint(
                id = seed.userMessageId,
                content = seed.userText.take(MAX_CHECKPOINT_TEXT_CHARS),
                timestamp = seed.userTimestamp,
            ),
            assistant = ChatTurnAssistantCheckpoint(
                id = assistant.id,
                content = assistant.content.take(MAX_CHECKPOINT_TEXT_CHARS),
                timestamp = assistant.timestamp,
                isStreaming = assistant.isStreaming,
                thinkingContent = assistant.thinkingContent.take(MAX_CHECKPOINT_TEXT_CHARS),
                isThinkingStreaming = assistant.isThinkingStreaming,
                inputTokens = assistant.inputTokens,
                outputTokens = assistant.outputTokens,
                totalTokens = assistant.totalTokens,
                estimatedCost = assistant.estimatedCost,
                agentName = assistant.agentName,
                badges = assistant.badges,
                cards = assistant.cards,
                cardDispatches = assistant.cardDispatches,
                toolCalls = assistant.toolCalls.map { tool ->
                    ChatTurnToolCheckpoint(
                        id = tool.id,
                        name = tool.name,
                        result = tool.result?.take(MAX_CHECKPOINT_TOOL_RESULT_CHARS),
                        success = tool.success,
                        isComplete = tool.isComplete,
                        error = tool.error?.take(MAX_CHECKPOINT_TOOL_RESULT_CHARS),
                        runId = tool.runId,
                        provenance = tool.provenance,
                        startedAt = tool.startedAt,
                        completedAt = tool.completedAt,
                        isGenerating = tool.isGenerating,
                        taskIndex = tool.taskIndex,
                        taskLabel = tool.taskLabel,
                    )
                },
                backgroundTask = assistant.backgroundTask?.let { task ->
                    ChatTurnBackgroundTaskCheckpoint(
                        id = task.id,
                        title = task.title,
                        tier = task.tier,
                        phase = task.phase.name,
                        statusLine = task.statusLine,
                        completedToolCount = task.completedToolCount,
                        queuedCount = task.queuedCount,
                        startedAt = task.startedAt,
                    )
                },
            ),
            turnStatus = handler.turnStatus.value,
            priorUserMessageCount = seed.priorUserMessageCount,
            baselineAssistantCount = seed.baselineAssistantCount,
            pendingAsk = pending?.let { ask ->
                ChatTurnAskCheckpoint(
                    kind = ask.ask.kind.name,
                    requestId = ask.ask.requestId,
                    text = ask.ask.text,
                    choices = ask.ask.choices,
                    envVar = ask.ask.envVar,
                    timeoutSeconds = ask.ask.timeoutSeconds,
                    messageId = ask.messageId,
                    cardKey = ask.cardKey,
                    receivedAt = ask.receivedAt,
                )
            },
            startedAt = seed.startedAt,
            updatedAt = now,
        )
    }

    private fun scheduleCheckpointWrite(immediate: Boolean = false) {
        val store = chatTurnCheckpointStore ?: return
        if (activeTurnCheckpointSeed == null) return
        if (!immediate && checkpointWriteJob?.isActive == true) return
        val generation = checkpointGeneration.get()
        if (immediate) checkpointWriteJob?.cancel()
        val delayMs = if (immediate) {
            0L
        } else {
            (CHECKPOINT_WRITE_INTERVAL_MS -
                (System.currentTimeMillis() - lastCheckpointWriteAtMs)).coerceAtLeast(0L)
        }
        checkpointWriteJob = viewModelScope.launch {
            if (delayMs > 0L) delay(delayMs)
            val checkpoint = buildTurnCheckpoint() ?: return@launch
            checkpointMutex.withLock {
                if (generation == checkpointGeneration.get() && activeTurnCheckpointSeed != null) {
                    if (runCatching { store.write(checkpoint) }.isSuccess) {
                        lastCheckpointWriteAtMs = System.currentTimeMillis()
                    }
                }
            }
            checkpointWriteJob = null
        }
    }

    private fun clearTurnCheckpoint() {
        val key = activeTurnCheckpointSeed?.let { seed ->
            seed.contextKey?.let { TurnCheckpointKey(it, seed.sessionId) }
        }
        activeTurnCheckpointSeed = null
        val generation = checkpointGeneration.incrementAndGet()
        checkpointWriteJob?.cancel()
        checkpointWriteJob = null
        if (key != null) backgroundTurnCheckpoints.remove(key)
        val store = chatTurnCheckpointStore ?: return
        viewModelScope.launch {
            checkpointMutex.withLock {
                if (generation == checkpointGeneration.get() && activeTurnCheckpointSeed == null) {
                    if (key != null) runCatching { store.remove(key.contextKey, key.sessionId) }
                }
            }
        }
    }

    /** Drop visible ownership while preserving a detached sibling's checkpoint. */
    private fun releaseActiveTurnCheckpoint() {
        activeTurnCheckpointSeed = null
        checkpointGeneration.incrementAndGet()
        checkpointWriteJob?.cancel()
        checkpointWriteJob = null
    }

    /**
     * Navigate away from the visible turn. Gateway can detach and multiplex;
     * SSE cannot, so it retains the existing interrupt/cancel behavior.
     */
    private fun releaseTurnForNavigation(handler: ChatHandler) {
        val gateway = gatewayClient
        val canBackground = streamingEndpoint == "gateway" &&
            activeStreamIsGateway && activeStream != null && gateway != null
        val checkpoint = if (canBackground) buildTurnCheckpoint() else null
        if (canBackground && gateway.backgroundActiveTurn()) {
            if (checkpoint != null) {
                val key = TurnCheckpointKey(checkpoint.contextKey, checkpoint.sessionId)
                backgroundTurnCheckpoints[key] = checkpoint
                chatTurnCheckpointStore?.let { store ->
                    viewModelScope.launch {
                        checkpointMutex.withLock { runCatching { store.write(checkpoint) } }
                    }
                }
            }
            activeStream?.detach()
            activeStream = null
            activeStreamIsGateway = false
            releaseActiveTurnCheckpoint()
            handler.clearStreamingStatus()
            _steerableTurn.value = false
            _steerNotice.value = null
            intentionallyCancelled = false
            return
        }

        clearTurnCheckpoint()
        intentionallyCancelled = true
        activeStream?.cancel()
        activeStream = null
        activeStreamIsGateway = false
    }

    /** Last-chance synchronous flush before the ViewModel scope is cancelled. */
    private fun flushTurnCheckpointForTeardown() {
        val store = chatTurnCheckpointStore ?: return
        val checkpoint = buildTurnCheckpoint() ?: return
        checkpointWriteJob?.cancel()
        runCatching {
            runBlocking(kotlinx.coroutines.Dispatchers.IO) { store.write(checkpoint) }
        }
    }

    private fun restorePendingAsk(handler: ChatHandler, checkpoint: ChatTurnCheckpoint) {
        val saved = checkpoint.pendingAsk ?: return
        val expiresAt = saved.timeoutSeconds.takeIf { it > 0 }
            ?.let { saved.receivedAt + it * 1_000L }
        if (expiresAt != null && expiresAt <= System.currentTimeMillis()) return
        val kind = runCatching { GatewayAsk.Kind.valueOf(saved.kind) }.getOrNull() ?: return
        presentInteractionAsk(
            handler = handler,
            ask = GatewayAsk(
                kind = kind,
                requestId = saved.requestId,
                text = saved.text,
                choices = saved.choices,
                envVar = saved.envVar,
                timeoutSeconds = saved.timeoutSeconds,
            ),
            restored = saved,
        )
    }

    private fun ownsTurnCheckpoint(checkpoint: ChatTurnCheckpoint, handler: ChatHandler): Boolean {
        val seed = activeTurnCheckpointSeed ?: return false
        return chatHandler === handler &&
            handler.currentSessionId.value == checkpoint.sessionId &&
            activeProfileContextKey == checkpoint.contextKey &&
            seed.sessionId == checkpoint.sessionId &&
            seed.assistantMessageId == checkpoint.assistant.id
    }

    /**
     * Restore and, when upstream still owns the worker, reattach to the exact
     * live Gateway turn. Returns true when a checkpoint owned this prewarm.
     */
    private suspend fun recoverPersistedTurnIfNeeded(
        client: GatewayChatClient?,
        handler: ChatHandler,
        sessionId: String,
    ): Boolean {
        val contextKey = activeProfileContextKey ?: return false
        val key = TurnCheckpointKey(contextKey, sessionId)
        val checkpoint = backgroundTurnCheckpoints.remove(key)
            ?: chatTurnCheckpointStore?.let { store ->
                runCatching { store.read(contextKey, sessionId) }.getOrNull()
            }
            ?: return false
        if (checkpoint.transport !in setOf("gateway", "sessions")) return false
        if (activeStream != null) return true
        if (streamRecovery != null) {
            if (checkpoint.transport == "gateway" && client != null) {
                // Upgrade a history-only cold-start recovery as soon as the
                // Gateway client becomes available; live events are preferable
                // and the same checkpoint/history guard still covers gaps.
                cancelAnswerRecovery(settleUi = false)
            } else {
                return true
            }
        }

        adoptTurnCheckpoint(checkpoint)
        val initialHistory = runCatching { loadSessionHistory(sessionId) }.getOrDefault(emptyList())
        if (!ownsTurnCheckpoint(checkpoint, handler)) return true
        if (initialHistory.isNotEmpty()) handler.loadMessageHistory(initialHistory)
        handler.restoreInFlightTurn(checkpoint)

        DiagnosticsLog.record(
            category = DiagnosticCategory.Api,
            severity = DiagnosticSeverity.Info,
            title = "Restoring an unfinished chat turn",
            detail = "session=$sessionId transport=${checkpoint.transport}",
        )

        if (checkpoint.transport == "gateway" && client != null) {
            val callbacks = recoveredGatewayCallbacks(handler, checkpoint)
            val recovery = client.recoverTurn(
                storedId = sessionId,
                preferredLiveId = checkpoint.liveSessionId,
                callbacks = callbacks,
            ).getOrNull()
            if (!ownsTurnCheckpoint(checkpoint, handler)) {
                recovery?.handle?.detach()
                return true
            }
            if (recovery?.running == true && recovery.handle != null) {
                activeStream = recovery.handle
                activeStreamIsGateway = true
                activeTurnCheckpointSeed?.liveSessionId = recovery.liveSessionId
                handler.restoreInFlightTurn(
                    checkpoint = checkpoint,
                    upstreamAssistantText = recovery.inflight?.assistant,
                )
                handler.setTurnStatus(
                    checkpoint.turnStatus?.takeIf { it.isNotBlank() }
                        ?: "Reconnected — Hermes is still working…",
                )
                restorePendingAsk(handler, checkpoint)
                scheduleCheckpointWrite(immediate = true)
                gatewayProcessController.sessionReady(sessionId)
                return true
            }

            // A non-running live payload is authoritative. If the final
            // assistant row is already durable, settle immediately instead of
            // showing a 15-second two-poll recovery delay.
            val durableAssistantCount = initialHistory.count {
                it.role.equals("assistant", ignoreCase = true)
            }
            if (recovery != null && durableAssistantCount > checkpoint.baselineAssistantCount) {
                handler.loadMessageHistory(initialHistory)
                finalizeTurnSideEffects(handler, checkpoint.assistant.id)
                refreshSessions()
                scheduleTitleReconcile(sessionId)
                drainQueue()
                return true
            }
        }

        startCheckpointHistoryRecovery(
            handler = handler,
            checkpoint = checkpoint,
            cause = "The live turn could not be reattached; waiting for persisted history.",
        )
        return true
    }

    private fun recoveredGatewayCallbacks(
        handler: ChatHandler,
        checkpoint: ChatTurnCheckpoint,
    ): GatewayTurnCallbacks {
        val messageId = checkpoint.assistant.id
        fun owns(): Boolean = ownsTurnCheckpoint(checkpoint, handler)
        return GatewayTurnCallbacks(
            onSessionId = { },
            onStart = { },
            onTextDelta = { delta ->
                if (owns()) handler.onTextDelta(messageId, delta)
            },
            onThinkingDelta = { delta ->
                if (owns()) handler.onThinkingDelta(messageId, delta)
            },
            onToolCallStart = { toolCallId, toolName ->
                if (owns()) {
                    handler.onToolCallStart(messageId, toolCallId, toolName)
                    scheduleCheckpointWrite(immediate = true)
                }
            },
            onToolCallDone = { toolCallId, preview ->
                if (owns()) {
                    handler.onToolCallComplete(messageId, toolCallId, preview)
                    scheduleCheckpointWrite(immediate = true)
                }
            },
            onToolCallFailed = { toolCallId, error ->
                if (owns()) {
                    handler.onToolCallFailed(messageId, toolCallId, error)
                    scheduleCheckpointWrite(immediate = true)
                }
            },
            onTurnComplete = {
                if (owns()) {
                    handler.onTurnComplete(messageId)
                    scheduleCheckpointWrite(immediate = true)
                }
            },
            onComplete = {
                if (owns()) {
                    finalizeTurnSideEffects(handler, messageId)
                    val expectedSessionId = checkpoint.sessionId
                    viewModelScope.launch {
                        val history = loadSessionHistory(expectedSessionId)
                        if (handler.currentSessionId.value == expectedSessionId && history.isNotEmpty()) {
                            handler.loadMessageHistory(history)
                            refreshSessions()
                            scheduleTitleReconcile(expectedSessionId)
                        }
                        drainQueue()
                    }
                }
            },
            onUsage = { usage ->
                if (owns() && usage != null) {
                    handler.onUsageReceived(
                        messageId,
                        usage.resolvedInputTokens,
                        usage.resolvedOutputTokens,
                        usage.resolvedTotalTokens,
                        null,
                    )
                }
            },
            onError = { error ->
                if (owns()) {
                    activeStream = null
                    _steerableTurn.value = false
                    _steerNotice.value = null
                    val latest = buildTurnCheckpoint() ?: checkpoint
                    startCheckpointHistoryRecovery(handler, latest, error)
                }
            },
            onToolGenerating = { name ->
                if (owns()) {
                    handler.onToolGenerating(messageId, name)
                    scheduleCheckpointWrite(immediate = true)
                }
            },
            onSubagentEvent = { event ->
                if (owns()) {
                    handler.onSubagentEvent(messageId, event)
                    scheduleCheckpointWrite(immediate = true)
                }
            },
            onInteractionRequest = { ask ->
                if (owns()) presentInteractionAsk(handler, ask)
            },
            onInteractionExpired = { expiry ->
                if (owns()) expirePendingAsk(expiry)
            },
            onStatusUpdate = { kind, text ->
                if (owns()) {
                    handler.setTurnStatus(text, kind)
                    if (text.trimStart().startsWith("❌")) handler.markError(messageId)
                }
            },
            onStatusClear = { kind ->
                if (owns()) handler.clearTurnStatus(kind)
            },
        )
    }

    /** Bounded durable-history fallback shared by process restore and Gateway loss. */
    private fun startCheckpointHistoryRecovery(
        handler: ChatHandler,
        checkpoint: ChatTurnCheckpoint,
        cause: String,
    ) {
        cancelAnswerRecovery(settleUi = false)
        if (activeTurnCheckpointSeed == null) adoptTurnCheckpoint(checkpoint)
        activeStream = null
        _recoveringAnswer.value = true
        handler.restoreInFlightTurn(checkpoint)
        handler.setTurnStatus("Reconnecting to the active turn…")
        scheduleCheckpointWrite(immediate = true)
        DiagnosticsLog.record(
            category = DiagnosticCategory.Api,
            severity = DiagnosticSeverity.Warning,
            title = "Live chat reattach unavailable — recovering from history",
            detail = cause,
        )

        val recovery = ChatStreamRecovery(
            scope = viewModelScope,
            fetchHistory = { loadSessionHistory(checkpoint.sessionId, requireProfileScope = true) },
            timing = recoveryTimingOverride ?: ChatStreamRecovery.Timing(),
        )
        streamRecovery = recovery
        recovery.start(
            pendingUserText = checkpoint.user.content,
            priorUserMessageCount = checkpoint.priorUserMessageCount,
            onIntermediateHistory = { items ->
                if (streamRecovery === recovery && ownsTurnCheckpoint(checkpoint, handler)) {
                    handler.loadMessageHistory(items)
                    handler.restoreInFlightTurn(checkpoint)
                    handler.setTurnStatus("Reconnecting to the active turn…")
                }
            },
            onRecovered = { items ->
                if (streamRecovery === recovery) {
                    streamRecovery = null
                    _recoveringAnswer.value = false
                    if (handler.currentSessionId.value == checkpoint.sessionId) {
                        handler.loadMessageHistory(items)
                        finalizeTurnSideEffects(handler, checkpoint.assistant.id)
                        refreshSessions()
                        scheduleTitleReconcile(checkpoint.sessionId)
                        drainQueue()
                    }
                }
            },
            onGaveUp = { reason ->
                if (streamRecovery === recovery) {
                    streamRecovery = null
                    _recoveringAnswer.value = false
                    val message = when (reason) {
                        ChatStreamRecovery.GiveUpReason.RUN_NOT_FOUND ->
                            "The unfinished message was not found on the server — please resend."
                        ChatStreamRecovery.GiveUpReason.TIMED_OUT ->
                            "The unfinished reply did not complete in the recovery window."
                        ChatStreamRecovery.GiveUpReason.HISTORY_UNAVAILABLE ->
                            appContext?.getString(R.string.chat_profile_history_unavailable)
                                ?: "The active profile's conversation history could not be reached. Reconnect and try again."
                    }
                    clearTurnCheckpoint()
                    AppAnalytics.onStreamError()
                    handler.onStreamError(message)
                    clearTurnCheckpoint()
                    emitError(Exception(message), context = "send_message")
                    _queuedMessages.value = emptyList()
                    clearPendingAsk(approvalStamp = "Resolved")
                }
            },
        )
    }

    /**
     * Terminal side effects every successfully finished turn shares — the
     * normal stream completion ([startStream]'s onCompleteCb) and a recovered
     * dropped-stream turn ([startAnswerRecovery]) both end here, so recovery
     * finalizes with exactly the completion semantics.
     */
    private fun finalizeTurnSideEffects(handler: ChatHandler, messageId: String) {
        handler.onStreamComplete(messageId)
        clearTurnCheckpoint()
        activeStream = null
        _steerableTurn.value = false
        _steerNotice.value = null
        // Turn over — any blocked ask has been resolved server-side
        // (answer, timeout, or interrupt). Timed cards self-collapse;
        // an unanswered approval gets a neutral "Resolved" stamp so its
        // buttons don't dead-end in "no longer active" notices.
        clearPendingAsk(approvalStamp = "Resolved")

        // Notify when the turn finished while the app is backgrounded —
        // never for cancelled streams; errors end via onErrorCb instead.
        maybeNotifyTurnComplete(handler, messageId)

        // v0.4.1 polish: auto-return to Hermes-Relay if the bridge
        // moved the foreground app during this run. No-op when the
        // LLM already called `android_return_to_hermes` itself (in
        // that case the tracker's internal flag was cleared by the
        // /return_to_hermes dispatch's respond()). See BridgeRunTracker
        // KDoc for the full contract.
        com.hermesandroid.relay.bridge.BridgeRunTracker.notifyRunCompleted()
    }

    /**
     * Stop any in-flight answer recovery — and ALWAYS settle the handler's
     * streaming/turn-status state when a poller was actually running.
     *
     * When recovery is live there is NO live [activeStream] (it was nulled
     * when the poller started), so nothing else fires onStreamComplete /
     * onStreamError to clear the "Reconnecting to your answer…" caption and
     * the global streaming flag. If abort left them set the chat would wedge
     * in streaming mode (dead Stop button, frozen caption) until process
     * death (issue #166).
     *
     * [settleUi] chooses HOW to settle:
     *  - `true` (a new send about to add its own placeholder) finalizes the
     *    leftover streaming placeholder into a completed bubble.
     *  - `false` (abandon paths — session/profile switch, new chat/thread,
     *    connection switch, user Stop, straggler-completion guard) drops the
     *    global streaming/turn-status flags SILENTLY: no error badge, and no
     *    placeholder finalize that could fight a subsequent loadMessageHistory
     *    or hide the message from cancelStream's Stopped-badge findLast. Those
     *    callers clear or reload the transcript themselves.
     */
    private fun cancelAnswerRecovery(settleUi: Boolean = true) {
        val hadRecovery = streamRecovery != null
        streamRecovery?.cancel()
        streamRecovery = null
        _recoveringAnswer.value = false
        if (!hadRecovery) return
        chatHandler?.let { handler ->
            if (settleUi) {
                val streaming = handler.messages.value.findLast { it.isStreaming }
                if (streaming != null) handler.onStreamComplete(streaming.id)
                else handler.clearStreamingStatus()
            } else {
                handler.clearStreamingStatus()
            }
        }
    }

    /**
     * Issue #166: on slow-model / delegating-skill turns the phone's SSE
     * socket dies (screen-off, Doze, Wi-Fi power-save) long before the server
     * finishes — but upstream api_server keeps executing the run after the
     * SSE writer dies and PERSISTS the final answer to the session store. So
     * a sessions-endpoint transport error must not finalize the turn as an
     * error: poll the session transcript (native upstream
     * `/api/sessions/{id}/messages` — standard-path safe) until the answer
     * lands, reconciling through the normal [ChatHandler.loadMessageHistory]
     * path, then finish with the same side effects as a normal completion.
     * On cap expiry or a run that never started, fall back to the existing
     * error UI.
     */
    private fun startAnswerRecovery(
        handler: ChatHandler,
        sessionId: String,
        pendingUserText: String,
        placeholderMessageId: String,
        cause: String,
    ) {
        buildTurnCheckpoint()?.let { checkpoint ->
            startCheckpointHistoryRecovery(handler, checkpoint, cause)
            return
        }
        cancelAnswerRecovery(settleUi = false)
        _recoveringAnswer.value = true
        handler.setTurnStatus(appContext?.getString(R.string.chat_approval_reconnecting) ?: "Reconnecting to your answer…")
        DiagnosticsLog.record(
            category = DiagnosticCategory.Api,
            severity = DiagnosticSeverity.Warning,
            title = (appContext?.getString(R.string.chat_approval_stream_dropped) ?: "Chat stream dropped — recovering the answer in the background"),
            detail = cause,
        )
        // Positional invariant for the anchor (issue #166): how many user-role
        // rows the client knew about BEFORE this send. handler.messages already
        // holds the in-flight pair (the just-added pending user message + the
        // streaming assistant placeholder), so subtract the one pending user
        // row. The pending send, once persisted, must land as the
        // (priorUserCount+1)-th user row — this stops a short repeated prompt
        // ("yes"/"continue") from anchoring on a stale identical earlier row.
        val priorUserCount = (
            handler.messages.value.count { it.role == MessageRole.USER } - 1
        ).coerceAtLeast(0)
        val recovery = ChatStreamRecovery(
            scope = viewModelScope,
            fetchHistory = { loadSessionHistory(sessionId, requireProfileScope = true) },
            timing = recoveryTimingOverride ?: ChatStreamRecovery.Timing(),
        )
        streamRecovery = recovery
        recovery.start(
            pendingUserText = pendingUserText,
            priorUserMessageCount = priorUserCount,
            onIntermediateHistory = { items ->
                if (streamRecovery === recovery && handler.currentSessionId.value == sessionId) {
                    // Progressive recovery: surface already-persisted rows as
                    // they appear. The reload drops the (never-persisted)
                    // streaming placeholder, so re-add one — with a stable id —
                    // to keep the reconnecting indicator alive until the
                    // answer lands.
                    handler.loadMessageHistory(items)
                    handler.addPlaceholderMessage(
                        ChatMessage(
                            id = "recovering-$placeholderMessageId",
                            role = MessageRole.ASSISTANT,
                            content = "",
                            timestamp = System.currentTimeMillis(),
                            isStreaming = true,
                            agentName = handler.activeAgentName,
                        )
                    )
                }
            },
            onRecovered = { items ->
                if (streamRecovery === recovery) {
                    streamRecovery = null
                    _recoveringAnswer.value = false
                    if (handler.currentSessionId.value == sessionId) {
                        // Server-authoritative reconcile — replaces the
                        // placeholder with the recovered answer (the same
                        // reload path a normal sessions completion uses).
                        handler.loadMessageHistory(items)
                    }
                    finalizeTurnSideEffects(handler, placeholderMessageId)
                    refreshSessions()
                    scheduleTitleReconcile(sessionId)
                    drainQueue()
                }
            },
            onGaveUp = { reason ->
                if (streamRecovery === recovery) {
                    streamRecovery = null
                    _recoveringAnswer.value = false
                    val message = when (reason) {
                        ChatStreamRecovery.GiveUpReason.RUN_NOT_FOUND ->
                            "Connection dropped before the server received this message — please resend."
                        ChatStreamRecovery.GiveUpReason.TIMED_OUT ->
                            "Lost the connection mid-reply and the answer never arrived — check the server and try again."
                        ChatStreamRecovery.GiveUpReason.HISTORY_UNAVAILABLE ->
                            appContext?.getString(R.string.chat_profile_history_unavailable)
                                ?: "The active profile's conversation history could not be reached. Reconnect and try again."
                    }
                    AppAnalytics.onStreamError()
                    handler.onStreamError(message)
                    emitError(Exception(message), context = "send_message")
                    _queuedMessages.value = emptyList()
                    // Parity with the sibling stream-error branch: the turn is
                    // over server-side, so force-deny any still-blocked approval
                    // card instead of leaving its buttons dead-ended.
                    clearPendingAsk(approvalStamp = "deny")
                }
            },
        )
    }

    fun clearQueue() {
        _queuedMessages.value = emptyList()
    }

    /** Drop a single queued message by index (no-op if out of range). */
    fun removeQueuedAt(index: Int) {
        _queuedMessages.update { list ->
            if (index in list.indices) list.toMutableList().apply { removeAt(index) } else list
        }
    }

    /**
     * Pull a queued message out for editing: removes it and returns its text so
     * the composer can prefill it. Null if the index is out of range.
     */
    fun takeQueuedForEdit(index: Int): String? {
        val current = _queuedMessages.value
        if (index !in current.indices) return null
        _queuedMessages.update { list ->
            if (index in list.indices) list.toMutableList().apply { removeAt(index) } else list
        }
        return current[index]
    }

    private fun drainQueue() {
        val client = apiClient ?: return
        val handler = chatHandler ?: return
        val next = _queuedMessages.value.firstOrNull() ?: return
        _queuedMessages.update { it.drop(1) }
        sendMessageInternal(client, handler, next)
    }

    private fun sendMessageInternal(client: HermesApiClient, handler: ChatHandler, text: String) {
        AppAnalytics.onMessageSent()
        val interfaceContextPrompt = nextInterfaceContextPrompt
        nextInterfaceContextPrompt = null

        // Snapshot and clear pending attachments
        val attachments = _pendingAttachments.value.ifEmpty { null }
        _pendingAttachments.value = emptyList()

        val messageId = UUID.randomUUID().toString()

        // Add user message locally (with attachments for display)
        handler.addUserMessage(
            ChatMessage(
                id = messageId,
                role = MessageRole.USER,
                content = text.trim(),
                timestamp = System.currentTimeMillis(),
                attachments = attachments ?: emptyList()
            )
        )
        handler.setLastSentMessage(text.trim())

        val assistantMessageId = UUID.randomUUID().toString()
        val sessionId = handler.currentSessionId.value

        // User-created Thread: the first message of a "+ New Thread" opens a new
        // source=phone gateway session keyed by the minted chat_id. Route it over
        // the proactive channel, snapshot the existing phone-session ids, then
        // poll for the NEW one (by difference) and switch to it.
        pendingThread?.let { pending ->
            pendingThread = null
            val send = onProactiveReply
            if (send != null) {
                handler.updateDeliveryStatus(messageId, MessageDeliveryStatus.SENDING)
                val knownIds = handler.sessions.value
                    .filter { it.source == "phone" }
                    .map { it.sessionId }
                    .toSet()
                creatingThread = CreatingThread(pending.chatId, pending.name, knownIds)
                send(text.trim(), pending.chatId, null, messageId)
                switchToCreatedThread()
            } else {
                handler.updateDeliveryStatus(messageId, MessageDeliveryStatus.FAILED)
            }
            return
        }

        // Agent Thread (existing source=phone session): the user is replying
        // inside a proactive conversation, so route the turn over the relay
        // proactive channel (continues that thread's gateway session) instead of
        // a normal chat send. The user bubble was already added above — mark it
        // SENDING and stamp its id as the reply's message_id so the relay's ack
        // can settle it. The chat_id comes from the learned map (the API doesn't
        // expose it); unknown → null → the relay/adapter's home channel ("phone").
        val activeThread = handler.sessions.value.firstOrNull { it.sessionId == sessionId }
        if (activeThread?.source == "phone") {
            val send = onProactiveReply
            if (send != null) {
                handler.updateDeliveryStatus(messageId, MessageDeliveryStatus.SENDING)
                send(text.trim(), threadChatIds[activeThread.sessionId], null, messageId)
            } else {
                handler.updateDeliveryStatus(messageId, MessageDeliveryStatus.FAILED)
            }
            return
        }

        // Optimistic drawer preview: a chat created via "New Chat" still reads
        // "New Chat"/untitled in the drawer until the server auto-titles it after
        // the turn. Stamp it with the first user message now so the row is
        // meaningful immediately (mirrors the SSE-create path's auto-title).
        sessionId?.takeIf { it.isNotBlank() }?.let { sid ->
            val row = handler.sessions.value.firstOrNull { it.sessionId == sid }
            if (row != null && (row.title.isNullOrBlank() || row.title == "New Chat")) {
                val preview = text.trim().take(50).let { if (text.trim().length > 50) "$it…" else it }
                if (preview.isNotBlank()) handler.renameSessionLocal(sid, preview)
            }
        }

        // runs/completions are sessionless on our side; gateway creates and
        // persists its own session via session.create (no /api/sessions
        // pre-create — the server's DB row appears on the first prompt).
        if (streamingEndpoint == "runs" || streamingEndpoint == "completions" || streamingEndpoint == "gateway") {
            startStream(
                client,
                handler,
                sessionId ?: "",
                text.trim(),
                assistantMessageId,
                messageId,
                attachments,
                interfaceContextPrompt,
            )
        } else if (sessionId != null) {
            startStream(
                client,
                handler,
                sessionId,
                text.trim(),
                assistantMessageId,
                messageId,
                attachments,
                interfaceContextPrompt,
            )
        } else {
            viewModelScope.launch {
                val selectedProfile = selectedProfileProvider()
                val useIsolatedProfileApi = selectedProfile?.hasIsolatedApi == true
                client.createSessionResult(
                    profileName = if (useIsolatedProfileApi) null else selectedProfile?.name,
                    model = if (useIsolatedProfileApi) {
                        null
                    } else {
                        selectedProfile?.model?.takeIf { it.isNotBlank() }
                    },
                ).fold(
                    onSuccess = { session ->
                        val nowMs = System.currentTimeMillis()
                        val chatSession = ChatSession(
                            sessionId = session.id,
                            title = null,
                            model = session.model,
                            updatedAt = nowMs,
                            startedAt = nowMs,
                            lastActivityAt = nowMs,
                        )
                        handler.addSession(chatSession)
                        handler.setSessionId(session.id)
                        onSessionChanged?.invoke(session.id)
                        startStream(
                            client,
                            handler,
                            session.id,
                            text.trim(),
                            assistantMessageId,
                            messageId,
                            attachments,
                            interfaceContextPrompt,
                        )

                        // Auto-title: use first ~50 chars of user message
                        val autoTitle = text.trim().take(50).let {
                            if (text.length > 50) "$it..." else it
                        }
                        client.renameSession(session.id, autoTitle)
                        handler.renameSessionLocal(session.id, autoTitle)
                    },
                    onFailure = { error ->
                        val message = error.message?.let { "Failed to create chat session: $it" }
                            ?: "Failed to create chat session"
                        handler.onStreamError(message)
                        emitError(error, context = "send_message")
                    }
                )
            }
        }
    }

    fun startRealtimeAgentTurn(userText: String, chatSessionId: String?): String {
        val handler = chatHandler ?: return UUID.randomUUID().toString()
        AppAnalytics.onMessageSent()
        val trimmed = userText.trim().ifBlank { "Listening..." }
        val userMessageId = UUID.randomUUID().toString()
        val assistantMessageId = "realtime-agent-${UUID.randomUUID()}"
        synchronized(terminalRealtimeAgentTurnIdsLock) {
            terminalRealtimeAgentTurnIds.remove(assistantMessageId)
        }
        realtimeAgentUserMessages[assistantMessageId] = userMessageId
        realtimeAgentInputTranscripts[assistantMessageId] = StringBuilder()
        realtimeAgentToolCallIds[assistantMessageId] = mutableSetOf()
        realtimeAgentHermesBacked[assistantMessageId] = false
        realtimeAgentProgressKeys.remove(assistantMessageId)
        handler.activeAgentName = currentAgentDisplayName()
        handler.addUserMessage(
            ChatMessage(
                id = userMessageId,
                role = MessageRole.USER,
                content = trimmed,
                timestamp = System.currentTimeMillis(),
            )
        )
        handler.setLastSentMessage(trimmed)
        chatSessionId?.takeIf { it.isNotBlank() }?.let {
            handler.setSessionId(it)
            onSessionChanged?.invoke(it)
        }
        handler.addPlaceholderMessage(
            ChatMessage(
                id = assistantMessageId,
                role = MessageRole.ASSISTANT,
                content = "",
                timestamp = System.currentTimeMillis(),
                isStreaming = true,
                agentName = handler.activeAgentName,
                badges = listOf("Realtime Agent"),
            )
        )
        return assistantMessageId
    }

    /**
     * Settle a realtime turn that failed before the relay could emit a
     * `voice.error`. Transport submission failures happen below the event
     * layer, so without this explicit terminal path the placeholder remains
     * streaming forever.
     */
    fun failRealtimeAgentTurn(assistantMessageId: String, message: String) {
        synchronized(terminalRealtimeAgentTurnIdsLock) {
            val handler = chatHandler ?: return
            removeRealtimeAgentUserPlaceholder(
                handler = handler,
                assistantMessageId = assistantMessageId,
            )
            clearRealtimeAgentTurnTracking(assistantMessageId, quarantine = true)
            val existing = handler.messages.value
                .firstOrNull { it.id == assistantMessageId }
                ?.content
                ?.trim()
                .orEmpty()
            if (existing.isBlank()) {
                handler.replaceMessageContent(assistantMessageId, message)
            }
            handler.markError(assistantMessageId)
            handler.onStreamError(message)
            activeStream = null
        }
    }

    /** Locally settle the realtime placeholder when the voice stop action wins. */
    fun cancelRealtimeAgentTurnLocally(assistantMessageId: String) {
        synchronized(terminalRealtimeAgentTurnIdsLock) {
            val handler = chatHandler ?: return
            if (handler.messages.value.none { it.id == assistantMessageId }) {
                clearRealtimeAgentTurnTracking(assistantMessageId, quarantine = true)
                activeStream = null
                return
            }
            removeRealtimeAgentUserPlaceholder(
                handler = handler,
                assistantMessageId = assistantMessageId,
            )
            clearRealtimeAgentTurnTracking(assistantMessageId, quarantine = true)
            val existing = handler.messages.value
                .firstOrNull { it.id == assistantMessageId }
                ?.content
                ?.trim()
                .orEmpty()
            if (existing.isBlank()) {
                handler.replaceMessageContent(assistantMessageId, "Cancelled.")
            } else {
                handler.markStopped(assistantMessageId)
            }
            handler.onStreamComplete(assistantMessageId)
            activeStream = null
        }
    }

    /**
     * Remove a transcript that was consumed as a phone-local Voice command.
     * Unlike a cancelled Hermes turn, this exchange never belonged in server
     * conversation history and must not render as `pause` → `Cancelled.`.
     */
    fun discardRealtimeAgentLocalCommandTurn(assistantMessageId: String) {
        synchronized(terminalRealtimeAgentTurnIdsLock) {
            val handler = chatHandler ?: return
            val userMessageId = realtimeAgentUserMessages[assistantMessageId]
            val previousUserText = handler.messages.value
                .lastOrNull {
                    it.role == MessageRole.USER &&
                        it.id != userMessageId &&
                        it.content.isNotBlank() &&
                        !it.content.equals("Listening...", ignoreCase = true)
                }
                ?.content
            userMessageId?.let(handler::removeMessage)
            handler.removeMessage(assistantMessageId)
            if (previousUserText != null) {
                handler.setLastSentMessage(previousUserText)
            } else {
                handler.clearLastSentMessage()
            }
            clearRealtimeAgentTurnTracking(assistantMessageId, quarantine = true)
            activeStream = null
        }
    }

    private fun removeRealtimeAgentUserPlaceholder(
        handler: ChatHandler,
        assistantMessageId: String,
    ) {
        val userMessageId = realtimeAgentUserMessages[assistantMessageId] ?: return
        val current = handler.messages.value
            .firstOrNull { it.id == userMessageId }
            ?.content
            ?.trim()
        if (current == "Listening...") {
            handler.removeMessage(userMessageId)
        }
    }

    private fun clearRealtimeAgentTurnTracking(
        assistantMessageId: String,
        quarantine: Boolean = false,
    ) {
        if (quarantine) synchronized(terminalRealtimeAgentTurnIdsLock) {
            terminalRealtimeAgentTurnIds.add(assistantMessageId)
            while (terminalRealtimeAgentTurnIds.size > 64) {
                val oldest = terminalRealtimeAgentTurnIds.iterator().next()
                terminalRealtimeAgentTurnIds.remove(oldest)
            }
        }
        realtimeAgentUserMessages.remove(assistantMessageId)
        realtimeAgentInputTranscripts.remove(assistantMessageId)
        realtimeAgentProviderBadges.remove(assistantMessageId)
        realtimeAgentToolCallIds.remove(assistantMessageId)
        realtimeAgentHermesBacked.remove(assistantMessageId)
        realtimeAgentProviderIds.remove(assistantMessageId)
        realtimeAgentModels.remove(assistantMessageId)
        realtimeAgentVoices.remove(assistantMessageId)
        realtimeAgentProgressKeys.remove(assistantMessageId)
    }

    private fun realtimeBadges(
        assistantMessageId: String,
        provider: String? = null,
        voice: String? = null,
        hasHermes: Boolean,
        hasTool: Boolean,
    ): List<String> = buildList {
        realtimeProviderBadge(provider, voice)?.let {
            realtimeAgentProviderBadges[assistantMessageId] = it
        }
        add("Realtime Agent")
        if (hasHermes) add("Hermes")
        if (hasTool) add("Tool")
        realtimeAgentProviderBadges[assistantMessageId]?.let { add(it) }
    }

    private fun realtimeProviderBadge(provider: String?, voice: String?): String? {
        val normalizedProvider = provider
            ?.takeIf { it.isNotBlank() }
            ?.replace("_realtime", "")
            ?.uppercase()
        val normalizedVoice = voice?.takeIf { it.isNotBlank() }
        return when {
            normalizedProvider != null && normalizedVoice != null -> "$normalizedProvider $normalizedVoice"
            normalizedProvider != null -> normalizedProvider
            normalizedVoice != null -> normalizedVoice
            else -> null
        }
    }

    private fun backgroundTaskTitle(handler: ChatHandler, assistantMessageId: String): String {
        val userMessageId = realtimeAgentUserMessages[assistantMessageId]
        val objective = handler.messages.value
            .firstOrNull { it.id == userMessageId }
            ?.content
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeUnless { it.equals("Listening...", ignoreCase = true) }
            .orEmpty()
        if (objective.isBlank()) return "Background task"
        return if (objective.length <= BACKGROUND_TASK_TITLE_LIMIT) {
            objective
        } else {
            objective.take(BACKGROUND_TASK_TITLE_LIMIT - 1).trimEnd() + "…"
        }
    }

    private fun backgroundToolStatus(toolName: String): String {
        val label = toolName
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (label.isBlank()) "Working…" else "Running $label…"
    }

    fun applyRealtimeAgentEvent(
        assistantMessageId: String,
        event: RealtimeVoiceEvent,
        showDetailedTrace: Boolean = false,
    ) {
        synchronized(terminalRealtimeAgentTurnIdsLock) {
            val eventOwner = realtimeAgentEventOwner(assistantMessageId, event)
            if (eventOwner in terminalRealtimeAgentTurnIds) return
            applyRealtimeAgentEventLocked(eventOwner, event, showDetailedTrace)
        }
    }

    private fun realtimeAgentEventOwner(
        latestAssistantMessageId: String,
        event: RealtimeVoiceEvent,
    ): String {
        if (event.type == "hermes.run.promoted") return latestAssistantMessageId
        event.runId
            ?.takeIf { it.isNotBlank() }
            ?.let(realtimeAgentRunOwners::get)
            ?.let { return it }
        if (!event.delivery.isNullOrBlank()) {
            return realtimeAgentPendingDeliveryOwner ?: latestAssistantMessageId
        }
        return latestAssistantMessageId
    }

    private fun clearRealtimeAgentBackgroundOwnership(assistantMessageId: String) {
        realtimeAgentRunOwners.entries.removeAll { it.value == assistantMessageId }
        if (realtimeAgentPendingDeliveryOwner == assistantMessageId) {
            realtimeAgentPendingDeliveryOwner = null
        }
    }

    private fun applyRealtimeAgentEventLocked(
        assistantMessageId: String,
        event: RealtimeVoiceEvent,
        showDetailedTrace: Boolean,
    ) {
        val handler = chatHandler ?: return
        val hermesSessionId = when {
            !event.chatSessionId.isNullOrBlank() -> event.chatSessionId
            event.type.startsWith("hermes.") && !event.sessionId.isNullOrBlank() -> event.sessionId
            else -> null
        }
        hermesSessionId?.let {
            handler.setSessionId(it)
            onSessionChanged?.invoke(it)
        }

        when (event.type) {
            "voice.session.ready", "voice.response.started" -> {
                event.provider?.takeIf { it.isNotBlank() }?.let {
                    realtimeAgentProviderIds[assistantMessageId] = it
                }
                event.model?.takeIf { it.isNotBlank() }?.let {
                    realtimeAgentModels[assistantMessageId] = it
                }
                event.voice?.takeIf { it.isNotBlank() }?.let {
                    realtimeAgentVoices[assistantMessageId] = it
                }
                handler.setMessageBadges(
                    assistantMessageId,
                    realtimeBadges(
                        assistantMessageId = assistantMessageId,
                        provider = event.provider,
                        voice = event.voice,
                        hasHermes = false,
                        hasTool = false,
                    ),
                )
                if (event.type == "voice.response.started") {
                    handler.updateBackgroundTask(assistantMessageId) { task ->
                        if (task.phase == BackgroundTaskPhase.DELIVERING) {
                            task.copy(statusLine = "Delivering the answer…")
                        } else {
                            task
                        }
                    }
                }
            }
            "hermes.message.started" -> Unit
            "voice.input_transcript.delta" -> {
                val userMessageId = realtimeAgentUserMessages[assistantMessageId] ?: return
                val transcript = event.delta?.takeIf { it.isNotBlank() } ?: return
                val accumulated = realtimeAgentInputTranscripts
                    .getOrPut(assistantMessageId) { StringBuilder() }
                    .append(transcript)
                    .toString()
                handler.replaceMessageContent(userMessageId, accumulated)
                handler.setLastSentMessage(accumulated)
            }
            "voice.input_transcript.final" -> {
                val userMessageId = realtimeAgentUserMessages[assistantMessageId] ?: return
                val transcript = event.text?.takeIf { it.isNotBlank() } ?: return
                realtimeAgentInputTranscripts[assistantMessageId] = StringBuilder(transcript)
                handler.replaceMessageContent(userMessageId, transcript)
                handler.setLastSentMessage(transcript)
            }
            "voice.response.delta" -> {
                val delta = event.delta ?: return
                if (event.source == "hermes") {
                    // Hermes' raw streamed answer is broker input for the
                    // provider-native summary, not chat-bubble content. The
                    // clean timeline surface is the tool card plus the final
                    // provider response; full raw traces stay in relay logs.
                    return
                } else {
                    handler.onTextDelta(assistantMessageId, delta)
                }
            }
            "hermes.tool.delta" -> {
                realtimeAgentHermesBacked[assistantMessageId] = true
                val name = event.toolName?.takeIf { it.isNotBlank() }
                // `_`-prefixed tools are internal machinery (upstream hides
                // them from every tool surface). The gateway streams drafting
                // text as a `_thinking` pseudo-tool that only ever emits
                // deltas — no tool.completed — so a pill created for it spins
                // "running" forever. Its text still feeds the detailed
                // thinking trace below; it just never becomes a ToolCall row.
                if (!name.isNullOrBlank() &&
                    !name.equals("hermes", ignoreCase = true) &&
                    !name.startsWith("_")
                ) {
                    val callId = event.toolCallId?.takeIf { it.isNotBlank() } ?: name
                    val seen = realtimeAgentToolCallIds
                        .getOrPut(assistantMessageId) { mutableSetOf() }
                    if (seen.add(callId)) {
                        handler.setMessageBadges(
                            assistantMessageId,
                            realtimeBadges(
                                assistantMessageId = assistantMessageId,
                                hasHermes = true,
                                hasTool = true,
                            ),
                        )
                        handler.onToolCallStart(
                            messageId = assistantMessageId,
                            toolCallId = callId,
                            toolName = name,
                            runId = event.runId,
                            provenance = "Hermes tool result summarized by provider voice",
                        )
                    }
                }
                if (showDetailedTrace) {
                    realtimeProgressThinkingLine(event)?.let { message ->
                        appendRealtimeThinkingStatus(
                            handler = handler,
                            assistantMessageId = assistantMessageId,
                            key = event.statusKey ?: event.toolCallId ?: event.toolName ?: message,
                            message = message,
                        )
                    }
                }
                return
            }
            "hermes.run.started" -> {
                realtimeAgentHermesBacked[assistantMessageId] = true
                handler.setMessageBadges(
                    assistantMessageId,
                    realtimeBadges(
                        assistantMessageId = assistantMessageId,
                        hasHermes = true,
                        hasTool = false,
                    ),
                )
            }
            "hermes.run.progress" -> {
                realtimeAgentHermesBacked[assistantMessageId] = true
                handler.setMessageBadges(
                    assistantMessageId,
                    realtimeBadges(
                        assistantMessageId = assistantMessageId,
                        hasHermes = true,
                        hasTool = false,
                    ),
                )
                if (showDetailedTrace) {
                    realtimeProgressThinkingLine(event)?.let { message ->
                        appendRealtimeThinkingStatus(
                            handler = handler,
                            assistantMessageId = assistantMessageId,
                            key = event.statusKey ?: message,
                            message = message,
                        )
                    }
                }
                handler.updateBackgroundTask(assistantMessageId) { task ->
                    task.copy(
                        phase = if (task.phase == BackgroundTaskPhase.WAITING) {
                            BackgroundTaskPhase.RUNNING
                        } else {
                            task.phase
                        },
                        statusLine = event.message?.takeIf { it.isNotBlank() }
                            ?: event.activeToolName
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::backgroundToolStatus)
                            ?: task.statusLine,
                        completedToolCount = event.completedToolCount
                            ?: task.completedToolCount,
                    )
                }
            }
            "hermes.tool.started" -> {
                realtimeAgentHermesBacked[assistantMessageId] = true
                val name = event.toolName?.takeIf { it.isNotBlank() } ?: "hermes"
                // Same `_`-internal-tool guard as hermes.tool.delta above —
                // defensive here (the relay currently only sends started for
                // real tools), since an unpaired started would pin a pill.
                if (name.startsWith("_")) return
                val callId = event.toolCallId?.takeIf { it.isNotBlank() } ?: name
                realtimeAgentToolCallIds
                    .getOrPut(assistantMessageId) { mutableSetOf() }
                    .add(callId)
                handler.setMessageBadges(
                    assistantMessageId,
                    realtimeBadges(
                        assistantMessageId = assistantMessageId,
                        hasHermes = true,
                        hasTool = true,
                    ),
                )
                handler.onToolCallStart(
                    messageId = assistantMessageId,
                    toolCallId = callId,
                    toolName = name,
                    runId = event.runId,
                    provenance = "Hermes tool result summarized by provider voice",
                )
            }
            "hermes.tool.completed" -> {
                realtimeAgentHermesBacked[assistantMessageId] = true
                val callId = event.toolCallId?.takeIf { it.isNotBlank() }
                    ?: event.toolName?.takeIf { it.isNotBlank() }
                    ?: "hermes"
                val seen = realtimeAgentToolCallIds
                    .getOrPut(assistantMessageId) { mutableSetOf() }
                if (callId !in seen) {
                    val name = event.toolName?.takeIf { it.isNotBlank() } ?: callId
                    seen.add(callId)
                    handler.onToolCallStart(
                        messageId = assistantMessageId,
                        toolCallId = callId,
                        toolName = name,
                        runId = event.runId,
                        provenance = "Hermes tool result summarized by provider voice",
                    )
                }
                handler.onToolCallComplete(
                    messageId = assistantMessageId,
                    toolCallId = callId,
                    resultPreview = event.resultPreview
                        ?.let(::compactRealtimeToolResultPreview)
                        ?.takeIf { showDetailedTrace },
                    provenance = "Provider-generated spoken summary after Hermes result",
                )
            }
            "hermes.tool.failed" -> {
                realtimeAgentHermesBacked[assistantMessageId] = true
                val callId = event.toolCallId?.takeIf { it.isNotBlank() }
                    ?: event.toolName?.takeIf { it.isNotBlank() }
                    ?: "hermes"
                val seen = realtimeAgentToolCallIds
                    .getOrPut(assistantMessageId) { mutableSetOf() }
                if (callId !in seen) {
                    val name = event.toolName?.takeIf { it.isNotBlank() } ?: callId
                    seen.add(callId)
                    handler.onToolCallStart(
                        messageId = assistantMessageId,
                        toolCallId = callId,
                        toolName = name,
                        runId = event.runId,
                        provenance = "Hermes tool result summarized by provider voice",
                    )
                }
                handler.onToolCallFailed(assistantMessageId, callId, event.message ?: event.resultPreview)
            }
            "hermes.confirmation.requested" -> {
                realtimeAgentHermesBacked[assistantMessageId] = true
                val prompt = event.message ?: "Waiting for confirmation"
                handler.setMessageBadges(
                    assistantMessageId,
                    realtimeBadges(
                        assistantMessageId = assistantMessageId,
                        hasHermes = true,
                        hasTool = true,
                    ),
                )
                if (showDetailedTrace) {
                    handler.onThinkingDelta(assistantMessageId, prompt)
                }
                handler.updateBackgroundTask(assistantMessageId) { task ->
                    task.copy(
                        phase = BackgroundTaskPhase.WAITING,
                        statusLine = prompt,
                    )
                }
            }
            // Hermes completion means the tool result is ready; provider narration ends the turn.
            "hermes.run.completed" -> Unit
            "hermes.run.promoted" -> {
                realtimeAgentHermesBacked[assistantMessageId] = true
                val taskId = event.runId?.takeIf { it.isNotBlank() }
                    ?: "background-$assistantMessageId"
                realtimeAgentRunOwners[taskId] = assistantMessageId
                handler.setBackgroundTask(
                    assistantMessageId,
                    BackgroundTaskState(
                        id = taskId,
                        title = backgroundTaskTitle(handler, assistantMessageId),
                        tier = event.tier?.takeIf { it.isNotBlank() } ?: "promoted",
                        queuedCount = event.queuedCount ?: 0,
                    ),
                )
            }
            "hermes.run.queued" -> {
                handler.updateBackgroundTask(assistantMessageId) { task ->
                    task.copy(queuedCount = event.queuedCount ?: task.queuedCount)
                }
            }
            "hermes.run.background_completed" -> {
                if (event.success == false) {
                    clearRealtimeAgentBackgroundOwnership(assistantMessageId)
                } else {
                    realtimeAgentPendingDeliveryOwner = assistantMessageId
                }
                handler.updateBackgroundTask(assistantMessageId) { task ->
                    if (event.success == false) {
                        task.copy(
                            phase = BackgroundTaskPhase.FAILED,
                            statusLine = event.message?.takeIf { it.isNotBlank() }
                                ?: "Background task failed.",
                            queuedCount = event.queuedCount ?: task.queuedCount,
                        )
                    } else {
                        task.copy(
                            phase = BackgroundTaskPhase.DELIVERING,
                            statusLine = "Done — delivering the answer…",
                            queuedCount = event.queuedCount ?: task.queuedCount,
                        )
                    }
                }
            }
            "voice.response.done" -> {
                if (realtimeAgentHermesBacked[assistantMessageId] != true) {
                    val userMessageId = realtimeAgentUserMessages[assistantMessageId]
                    val snapshot = handler.messages.value
                    val userText = snapshot.firstOrNull { it.id == userMessageId }
                        ?.content
                        ?.trim()
                        .orEmpty()
                    val assistantText = snapshot.firstOrNull { it.id == assistantMessageId }
                        ?.content
                        ?.trim()
                        .orEmpty()
                    if (userText.isNotBlank() && assistantText.isNotBlank()) {
                        handler.attachRealtimeTurnTrace(
                            assistantMessageId,
                            RealtimeTurnTrace(
                                userText = userText,
                                assistantText = assistantText,
                                provider = event.provider ?: realtimeAgentProviderIds[assistantMessageId],
                                model = event.model ?: realtimeAgentModels[assistantMessageId],
                                voice = event.voice ?: realtimeAgentVoices[assistantMessageId],
                            ),
                        )
                    }
                }
                handler.updateBackgroundTask(assistantMessageId) { task ->
                    if (task.phase == BackgroundTaskPhase.DELIVERING) {
                        task.copy(
                            phase = BackgroundTaskPhase.COMPLETE,
                            statusLine = null,
                            queuedCount = 0,
                        )
                    } else {
                        task
                    }
                }
                handler.onStreamComplete(assistantMessageId)
                clearRealtimeAgentTurnTracking(assistantMessageId)
                if (!event.delivery.isNullOrBlank()) {
                    clearRealtimeAgentBackgroundOwnership(assistantMessageId)
                }
                activeStream = null
            }
            "hermes.run.cancelled" -> {
                // Don't clobber a delivered answer: the cancel confirm can
                // arrive after the summary already streamed into this bubble
                // (chip-cancel racing completion, or a stale confirm). Only a
                // bubble with no real content becomes "Cancelled."; anything
                // else keeps its text and gets the Stopped badge instead.
                handler.updateBackgroundTask(assistantMessageId) { task ->
                    task.copy(
                        phase = BackgroundTaskPhase.CANCELLED,
                        statusLine = "Cancelled.",
                    )
                }
                removeRealtimeAgentUserPlaceholder(
                    handler = handler,
                    assistantMessageId = assistantMessageId,
                )
                val existingContent = handler.messages.value
                    .firstOrNull { it.id == assistantMessageId }
                    ?.content
                    ?.trim()
                    .orEmpty()
                if (existingContent.isBlank()) {
                    handler.replaceMessageContent(assistantMessageId, "Cancelled.")
                } else {
                    handler.markStopped(assistantMessageId)
                }
                handler.onStreamComplete(assistantMessageId)
                clearRealtimeAgentBackgroundOwnership(assistantMessageId)
                clearRealtimeAgentTurnTracking(assistantMessageId, quarantine = true)
                activeStream = null
            }
            "voice.error" -> {
                handler.updateBackgroundTask(assistantMessageId) { task ->
                    task.copy(
                        phase = BackgroundTaskPhase.FAILED,
                        statusLine = event.message?.takeIf { it.isNotBlank() }
                            ?: "Voice connection failed.",
                    )
                }
                removeRealtimeAgentUserPlaceholder(
                    handler = handler,
                    assistantMessageId = assistantMessageId,
                )
                handler.onStreamError(event.message ?: "Realtime agent failed")
                if (realtimeAgentPendingDeliveryOwner == assistantMessageId) {
                    clearRealtimeAgentBackgroundOwnership(assistantMessageId)
                }
                clearRealtimeAgentTurnTracking(assistantMessageId, quarantine = true)
                activeStream = null
            }
        }
    }

    /**
         * Kick off an SSE chat turn against the selected chat endpoint.
     *
     * **System-message precedence (Pass 3, 2026-04-18):**
     *
     *  0. **Gateway transport** — the server owns the persona end-to-end: the
     *     session is bound to the selected profile (SOUL applied server-side) and
     *     the personality overlay rides `config.set`/`ephemeral_system_prompt`.
     *     The phone sends NO persona/profile prompt (only the phone-status block)
     *     so it can't double-apply. Cases 1–3 are the SSE-fallback rules.
     *  1. **Selected profile with a non-blank [Profile.systemMessage]** —
     *     profile wins outright. Profile is a richer, newer concept than
     *     personality: it bundles model + persona (from the profile's
     *     `SOUL.md`) into a single named unit, so a user who picks a profile
     *     has explicitly asked for that profile's full identity. The
     *     personality prompt is skipped in this case. If the selected profile
     *     advertises an isolated API route, the server's profile config owns
     *     SOUL/default prompt and the phone does not resend it.
     *  2. **Selected non-default personality** — send the personality's
     *     stored system prompt. This is the pre-Pass-3 path.
     *  3. **Neither selected** — no personality prompt, server uses its own
     *     configured default.
     *
     * The phone-status [appContextSettings] block is appended to whichever
     * of the above wins (or sent alone in case 3), so the LLM always sees
     * phone state regardless of persona source.
     */
    /**
     * The exact `system_message` (`ephemeral_system_prompt`) injected for a
     * turn, split into labeled blocks. Single source of truth shared by
     * [startStream] (which sends [combinedSystemMessage]) and
     * [previewInjectedContext] (which renders it in the chat audit sheet) so
     * the preview can never drift from what is actually sent.
     */
    data class InjectedContext(
        val personaPrompt: String?,
        val appContext: String?,
        val interfaceContext: String?,
        /**
         * Relay media-capability hint — tells the agent it can surface images/
         * files by absolute server path. Non-null only on SSE turns when a relay
         * route is configured (the gateway has no system slot to carry it).
         */
        val mediaCapability: String?,
        /**
         * Relay-plugin-owned server-side context blocks that are injected into
         * Hermes by the relay enhancement layer. These are audit-only on the
         * client: they are not included in [combinedSystemMessage].
         */
        val relayServerBlocks: List<Pair<String, String>>,
        /**
         * True when a relay route is configured, so the relay `/media/by-path`
         * route can fetch server-local images/files. On the GATEWAY this is how
         * media works (the client renders them inline) even though
         * [mediaCapability] — the SSE-only injected hint — is null. Carried so the
         * audit UI doesn't read "media unavailable" on the gateway when it isn't.
         */
        val relayMediaAvailable: Boolean,
        val combinedSystemMessage: String?,
        val transport: String,
        /**
         * True on the gateway path: the profile SOUL + personality overlay are
         * injected server-side (session.create + config.set), not in this
         * device's payload — so [personaPrompt] is intentionally null and the
         * audit UI labels that block "added server-side".
         */
        val personaOwnedServerSide: Boolean,
    )

    /**
     * Build the injected context for a turn. [selectedProfile] /
     * [useIsolatedProfileApi] are passed in (not re-resolved) so a caller can
     * pin one profile snapshot across systemMessage + modelOverride.
     */
    private fun composeInjectedContext(
        interfaceContextPrompt: String?,
        selectedProfile: Profile?,
        useIsolatedProfileApi: Boolean,
        relayServerBlocks: List<Pair<String, String>> = emptyList(),
    ): InjectedContext {
        val selected = _selectedPersonality.value
        val profileSystemMessage = selectedProfile
            ?.systemMessage
            ?.takeIf { !useIsolatedProfileApi && it.isNotBlank() }
        // On the gateway the server owns BOTH the profile SOUL and the
        // personality overlay (config.set → ephemeral_system_prompt), so the
        // phone resends neither (double-apply). SSE fallbacks have no
        // server-side persona state, so they carry it. Precedence otherwise:
        // profile systemMessage → selected non-default personality → none.
        val gateway = streamingEndpoint == "gateway"
        val personaPrompt: String? = when {
            gateway -> null
            profileSystemMessage != null -> profileSystemMessage
            selected != "default" && !AgentDisplay.isClearedPersonality(selected) &&
                selected != _defaultPersonality.value -> personalityPrompts[selected]
            else -> null
        }
        val appContextRaw = buildPromptBlock(appContextSettings, capturePhoneSnapshot())
        // Tell the agent it can surface images/files by absolute server path —
        // but only on SSE (the gateway carries no system_message) and only when
        // a relay route is configured (otherwise the client can't fetch them).
        val relayMediaAvailable = relayHttpClient?.mediaUrlConfigured() == true
        val mediaCapability: String? =
            RELAY_MEDIA_HINT.takeIf { !gateway && relayMediaAvailable }
        // combinedSystemMessage appends the media hint after the phone-status
        // block (a stable environment fact, like phone status) and before the
        // per-turn interface context. The per-block fields below null out blanks
        // only for display.
        val combined = listOfNotNull(personaPrompt, appContextRaw, mediaCapability, interfaceContextPrompt)
            .joinToString("\n\n")
            .ifBlank { null }
        return InjectedContext(
            personaPrompt = personaPrompt,
            appContext = appContextRaw?.takeIf { it.isNotBlank() },
            interfaceContext = interfaceContextPrompt?.takeIf { it.isNotBlank() },
            mediaCapability = mediaCapability,
            relayServerBlocks = relayServerBlocks,
            relayMediaAvailable = relayMediaAvailable,
            combinedSystemMessage = combined,
            transport = streamingEndpoint,
            personaOwnedServerSide = gateway,
        )
    }

    /**
     * Live audit snapshot of what the agent will be injected with on the next
     * turn — rendered by the chat context sheet. Per-turn voice context is null
     * here (it's set only on a spoken turn); the UI notes that.
     */
    fun previewInjectedContext(): InjectedContext {
        val profile = selectedProfileProvider()
        val relayBlocks = runCatching {
            runBlocking {
                relayHttpClient
                    ?.fetchInjectedContext()
                    ?.getOrNull()
                    ?.blocks
                    ?.map { it.name to it.text }
                    .orEmpty()
            }
        }.getOrDefault(emptyList())
        return composeInjectedContext(
            interfaceContextPrompt = null,
            selectedProfile = profile,
            useIsolatedProfileApi = profile?.hasIsolatedApi == true,
            relayServerBlocks = relayBlocks,
        )
    }

    private fun startStream(
        client: HermesApiClient,
        handler: ChatHandler,
        sessionId: String,
        message: String,
        assistantMessageId: String,
        userMessageId: String,
        attachments: List<Attachment>? = null,
        interfaceContextPrompt: String? = null,
    ) {
        // Resolve the active profile pick once — used below for both
        // modelOverride and the system_message precedence rule.
        val selectedProfile = selectedProfileProvider()
        val useIsolatedProfileApi = selectedProfile?.hasIsolatedApi == true

        // The injected system_message (persona + phone-status + per-turn
        // context) is built by composeInjectedContext so the chat-screen audit
        // sheet (previewInjectedContext) renders EXACTLY what we send here.
        // Pass the already-resolved profile so a rapid switch can't pair this
        // turn's systemMessage with another profile's model (see modelOverride).
        val systemMsg = composeInjectedContext(
            interfaceContextPrompt,
            selectedProfile,
            useIsolatedProfileApi,
        ).combinedSystemMessage

        // Set agent name for display on chat bubbles. The selected/effective
        // Hermes profile is the active agent identity; personality is only a
        // fallback when no profile metadata is available.
        handler.activeAgentName = currentAgentDisplayName()

        // A new send always aborts any in-flight dropped-stream answer
        // recovery — exactly one poller per turn (issue #166). settleUi
        // finalizes the previous turn's leftover streaming placeholder so it
        // can't pulse forever next to this turn's fresh one.
        cancelAnswerRecovery()

        // A new turn is starting: clear any leftover cancellation flag so a
        // stale `true` from a PRIOR cancelled turn (the flag is sticky — a
        // clean gateway cancel never fires onError to consume it) can't make
        // THIS turn's genuine transport error get silently swallowed, which
        // would leave the composer wedged in "streaming" behind a dead Stop.
        intentionallyCancelled = false
        firstTokenNotified = false
        var lastInputTokens: Int? = null
        var lastOutputTokens: Int? = null

        // Track the current message ID — starts with our generated ID,
        // but updates when the server sends message.started with its own ID.
        var currentMessageId = assistantMessageId

        // The SSE endpoint this turn actually dispatched on (null on a gateway
        // dispatch) — set by dispatchSse below. onErrorCb keys the dropped-
        // stream answer recovery (issue #166) on "sessions": the other
        // endpoints keep their existing error behavior.
        var dispatchedSseEndpoint: String? = null

        val assistantTimestamp = System.currentTimeMillis()
        beginTurnCheckpoint(
            handler = handler,
            sessionId = sessionId,
            transport = streamingEndpoint,
            userMessageId = userMessageId,
            userText = message,
            assistantMessageId = assistantMessageId,
            assistantTimestamp = assistantTimestamp,
        )

        // Show placeholder "thinking" message immediately — filled when first delta arrives
        handler.addPlaceholderMessage(
            ChatMessage(
                id = assistantMessageId,
                role = MessageRole.ASSISTANT,
                content = "",
                timestamp = assistantTimestamp,
                isStreaming = true,
                agentName = handler.activeAgentName,
                // Voice-mode turns carry a per-turn interface context (the
                // spoken-output hint) — the only thing that sets
                // interfaceContextPrompt today, so it marks this turn as spoken.
                // Tag the reply with a "Voice" chip (parity with "Realtime
                // Agent"); ChatHandler.loadMessageHistory preserves it across
                // the post-turn history reload.
                badges = if (interfaceContextPrompt != null) listOf("Voice") else emptyList(),
            )
        )

        // Shared callbacks for both endpoints
        val onMessageStartedCb = { serverMsgId: String ->
            // Replace the placeholder's ID so subsequent deltas/tool calls attach
            // to it instead of creating a duplicate orphan bubble with streaming dots.
            // Only replaces empty+streaming messages (the placeholder), not completed turns.
            handler.replaceMessageId(currentMessageId, serverMsgId)
            currentMessageId = serverMsgId
            updateTurnCheckpointAssistantId(serverMsgId)
        }
        val onTextDeltaCb = { delta: String ->
            if (!firstTokenNotified) {
                firstTokenNotified = true
                AppAnalytics.onFirstTokenReceived()
            }
            handler.onTextDelta(currentMessageId, delta)
        }
        val onThinkingDeltaCb = { delta: String ->
            handler.onThinkingDelta(currentMessageId, delta)
        }
        val onToolCallStartCb = { toolCallId: String, toolName: String ->
            handler.onToolCallStart(currentMessageId, toolCallId, toolName)
            scheduleCheckpointWrite(immediate = true)
        }
        val onToolCallDoneCb = { toolCallId: String, resultPreview: String? ->
            handler.onToolCallComplete(currentMessageId, toolCallId, resultPreview)
            scheduleCheckpointWrite(immediate = true)
        }
        val onToolCallFailedCb = { toolCallId: String, errorMsg: String? ->
            handler.onToolCallFailed(currentMessageId, toolCallId, errorMsg)
            scheduleCheckpointWrite(immediate = true)
        }
        // Turn complete — one assistant message finished, but the run may continue
        val onTurnCompleteCb = {
            handler.onTurnComplete(currentMessageId)
            scheduleCheckpointWrite(immediate = true)
        }
        val onCompleteCb = {
            // Double-finalize guard: if a straggler completion arrives while
            // the answer-recovery poller is running, the normal completion
            // wins — stop the poller before finalizing so the turn can't
            // finish twice.
            cancelAnswerRecovery(settleUi = false)
            finalizeTurnSideEffects(handler, currentMessageId)
            AppAnalytics.onStreamComplete(lastInputTokens, lastOutputTokens)

            // Command catalog rides the now-live socket after the first real
            // gateway turn — never a cold /api/ws open at composition.
            if (streamingEndpoint == "gateway" && _serverCommands.value.isEmpty()) {
                gatewayClient?.let { fetchServerCommands(it) }
            }
            // Curated provider/model list (desktop-parity picker) rides the
            // now-live socket too — once, on the first gateway turn.
            if (streamingEndpoint == "gateway" && _modelProviders.value.isEmpty()) {
                refreshModelOptions()
            }
            if (streamingEndpoint == "gateway") {
                refreshReasoningSettings()
            }

            // Sessions endpoint doesn't emit structured tool events during streaming —
            // tool calls are only available as JSON on the stored messages. Reload the
            // server-authoritative history to get proper message boundaries + tool_calls.
            // Gateway turns reconcile the same way: live tool events are gated by the
            // server's display.tool_progress config, so a turn that ran tools silently
            // (config off, or events lost in a mid-turn rejoin gap) still gets its tool
            // cards + persisted reasoning right after the turn — not on the next app
            // restart. By message.complete the server has persisted the turn, so the
            // REST read is authoritative.
            val sid = handler.currentSessionId.value
            // A turn that ended in an error (gateway ❌ lifecycle → "Error" badge)
            // has NO assistant message persisted server-side, so reconciling the
            // server transcript would WIPE the just-shown error bubble (the user
            // message stays, the assistant error vanishes — the disappearing-reply
            // regression). Skip the message reconcile for errored turns — keep the
            // local error visible — but still refresh the drawer + drain the queue.
            val turnErrored = handler.messages.value
                .lastOrNull { it.id == currentMessageId }
                ?.badges?.contains("Error") == true
            if (sid != null && (streamingEndpoint == "sessions" || streamingEndpoint == "gateway")) {
                viewModelScope.launch {
                    if (!turnErrored) {
                        // Profile-aware read: a gateway turn on a non-default profile
                        // persists into THAT profile's own state.db, so the bare
                        // api_server `/api/sessions/{id}/messages` 404s → emptyList()
                        // → a silent wipe of the just-finished turn. loadSessionHistory
                        // prefers the `?profile=` dashboard loader on gateway connections.
                        val serverMessages = loadSessionHistory(sid)
                        handler.loadMessageHistory(serverMessages)
                    }
                    // Re-sync the drawer now that the turn is persisted server-side.
                    // The only other auto-refresh fires ~160ms after session creation
                    // (RelayApp) — mid-stream, BEFORE the new session's first message
                    // is persisted, so a brand-new chat would otherwise stay missing
                    // from the drawer (carried only by the optimistic row) until a
                    // manual reload. By message.complete the dashboard list includes it.
                    refreshSessions()
                    scheduleTitleReconcile(sid)
                    drainQueue()
                }
                Unit
            } else {
                drainQueue()
            }
        }
        val onUsageCb = { usage: UsageInfo? ->
            if (usage != null) {
                val tokIn = usage.resolvedInputTokens
                val tokOut = usage.resolvedOutputTokens
                if (tokIn != null || tokOut != null) {
                    lastInputTokens = tokIn
                    lastOutputTokens = tokOut
                    handler.onUsageReceived(
                        currentMessageId,
                        tokIn,
                        tokOut,
                        usage.resolvedTotalTokens,
                        null
                    )
                }
                // Context-window meter (gateway only — present when the
                // server's context compressor is active). Session-cumulative
                // by design; per-message token displays above stay on the
                // same semantics they had before.
                val ctxMax = usage.contextMax
                if (ctxMax != null && ctxMax > 0) {
                    val used = usage.contextUsed
                    val percent = usage.contextPercent
                    _contextUsage.value = when {
                        used != null -> (used.toFloat() / ctxMax).coerceIn(0f, 1f)
                        percent != null -> (percent / 100f).coerceIn(0f, 1f)
                        else -> _contextUsage.value
                    }
                    // Keep the absolute token window in lockstep so the bar can
                    // render `used / max` — only when the server gave a real
                    // `context_used` (percent-only servers stay token-less).
                    if (used != null) {
                        _contextWindow.value = ContextWindowUsage(usedTokens = used, maxTokens = ctxMax)
                    }
                }
            }
        }
        val onErrorCb = { errorMsg: String ->
            val errorSessionId = handler.currentSessionId.value
            if (intentionallyCancelled) {
                intentionallyCancelled = false
                // Cancellation (user Stop / session switch): suppress the
                // error banner — but STILL finalize the streaming UI so the
                // turn can never wedge "streaming forever" behind a dead Stop
                // button if a cancel and a transport error race.
                handler.messages.value.findLast { it.isStreaming }
                    ?.let { handler.onStreamComplete(it.id) }
                activeStream = null
                _queuedMessages.value = emptyList()
                _steerableTurn.value = false
                _steerNotice.value = null
                clearPendingAsk(approvalStamp = "deny")
                clearTurnCheckpoint()
            } else if (
                dispatchedSseEndpoint == "sessions" &&
                errorSessionId != null &&
                HermesApiClient.isTransportStreamError(errorMsg)
            ) {
                // Issue #166: a transport drop on the sessions endpoint does
                // NOT mean the turn failed — upstream api_server keeps running
                // it and persists the final answer. Don't finalize as an
                // error; recover the answer by polling the transcript. The
                // send queue is deliberately KEPT: a successful recovery
                // drains it exactly like a normal completion; give-up flushes
                // it in the error fallback.
                startAnswerRecovery(
                    handler = handler,
                    sessionId = errorSessionId,
                    pendingUserText = message,
                    placeholderMessageId = currentMessageId,
                    cause = errorMsg,
                )
                activeStream = null
                _steerableTurn.value = false
                _steerNotice.value = null
            } else if (
                dispatchedSseEndpoint == null &&
                activeStreamIsGateway &&
                errorSessionId != null
            ) {
                // A Gateway mapper/socket failure does not prove the server
                // stopped. Preserve the rich local checkpoint and use the same
                // bounded persisted-history recovery as a process reopen.
                activeStream = null
                _steerableTurn.value = false
                _steerNotice.value = null
                val checkpoint = buildTurnCheckpoint()
                if (checkpoint != null) {
                    startCheckpointHistoryRecovery(handler, checkpoint, errorMsg)
                } else {
                    AppAnalytics.onStreamError()
                    handler.onStreamError(errorMsg)
                    emitError(Exception(errorMsg), context = "send_message")
                    clearTurnCheckpoint()
                }
            } else {
                AppAnalytics.onStreamError()
                handler.onStreamError(errorMsg)
                // Keep the in-place error banner AND push to the global
                // snackbar — classifier wraps the string into a throwable
                // so context-specific copy kicks in for send_message.
                emitError(Exception(errorMsg), context = "send_message")
                // Recover the server-authoritative transcript on a gateway/
                // sessions error: a turn can fail on the CLIENT (mid-turn route
                // switch, watchdog timeout) AFTER the server already finished it
                // — reload history so the completed answer still surfaces
                // instead of stranding the turn on its partial/errored state.
                if (errorSessionId != null &&
                    (streamingEndpoint == "sessions" || streamingEndpoint == "gateway")
                ) {
                    viewModelScope.launch {
                        runCatching {
                            // Profile-aware read — see onCompleteCb: a bare
                            // getMessages 404s for a non-default-profile session
                            // and silently empties the transcript.
                            val serverMessages = loadSessionHistory(errorSessionId)
                            handler.loadMessageHistory(serverMessages)
                        }
                    }
                }
                activeStream = null
                _queuedMessages.value = emptyList()
                _steerableTurn.value = false
                _steerNotice.value = null
                clearPendingAsk(approvalStamp = "deny")
                clearTurnCheckpoint()
            }
        }

        // === v0.4.1 voice-intent + v0.7.x card-dispatch session sync ===
        // Synthesize OpenAI-format `assistant` (with tool_calls) + `tool`
        // pairs from any unsynced phone-local voice intents AND rich-card
        // action dispatches in chat history. Server-side session absorbs
        // both streams so the LLM sees prior voice actions and card
        // interactions in its memory the next time the user follows up
        // via text. [hasUnsynced] on each builder short-circuits the
        // empty-array allocation on the common-case turn where no
        // synthetic traces fired.
        //
        // Both builders produce the same OpenAI message shape, so we
        // concatenate them into one JsonArray and splice through the
        // API client's single `voiceIntentMessages` parameter (name is
        // historical — param accepts any synthetic-message array now).
        // Order within each stream is preserved chronologically; between
        // streams, voice intents come first since that was the original
        // consumer. The LLM doesn't depend on cross-stream ordering —
        // cause-and-effect is preserved within each stream and the user's
        // actual turn follows both.
        val historySnapshot = handler.messages.value
        val hasVoiceIntents = VoiceIntentSyncBuilder.hasUnsynced(historySnapshot)
        val hasCardDispatches = CardDispatchSyncBuilder.hasUnsynced(historySnapshot)
        val hasRealtimeTurns = RealtimeTurnSyncBuilder.hasUnsynced(historySnapshot)
        val voiceIntentMessages = when {
            hasVoiceIntents || hasCardDispatches || hasRealtimeTurns -> {
                kotlinx.serialization.json.buildJsonArray {
                    if (hasVoiceIntents) {
                        VoiceIntentSyncBuilder.buildSyntheticMessages(historySnapshot).forEach { add(it) }
                    }
                    if (hasCardDispatches) {
                        CardDispatchSyncBuilder.buildSyntheticMessages(historySnapshot).forEach { add(it) }
                    }
                    if (hasRealtimeTurns) {
                        RealtimeTurnSyncBuilder.buildSyntheticMessages(historySnapshot).forEach { add(it) }
                    }
                }
            }
            else -> null
        }
        // === END session sync ===

        // Resolve the active agent-profile pick to a `modelOverride` string
        // for this send. `null` (or blank) means "no override — let the
        // server use its configured default model"; the API client drops
        // the field from the request body in that case. Reuses the
        // [selectedProfile] resolved at the top of this function so a
        // rapid switch doesn't give us a systemMessage from profile A but
        // a model from profile B.
        val modelOverride: String? = AgentDisplay.requestModelName(
            when {
                useIsolatedProfileApi -> null
                // An explicit in-chat model pick wins over the profile's model.
                !_selectedModelOverride.value.isNullOrBlank() -> _selectedModelOverride.value
                else -> selectedProfile?.model?.takeIf { it.isNotBlank() }
            },
        )
        val profileName: String? = if (useIsolatedProfileApi) {
            null
        } else {
            AgentDisplay.profileRequestName(selectedProfile?.name)
        }

        // Surface — instead of silently dropping — attachments that the chosen
        // SSE endpoint can't deliver to a vanilla-upstream server. Only the
        // gateway uploads files (image.attach_bytes / pdf.attach / file.attach).
        // The completions endpoint still carries images inline (OpenAI
        // image_url), but no SSE path carries non-image files, and sessions/runs
        // carry no attachments at all. Text always sends regardless.
        fun warnIfAttachmentsDropped(endpoint: String) {
            val dropped = attachments.orEmpty().filter { att ->
                if (endpoint == "completions") !att.isImage else true
            }
            if (dropped.isEmpty()) return
            val names = dropped.joinToString(", ") {
                it.fileName ?: if (it.isImage) "image" else "file"
            }
            val noun = if (dropped.size == 1) "attachment" else "attachments"
            handler.addSystemNotice(
                "⚠ Couldn't send your $noun ($names) over this connection — " +
                    "attachments are delivered over the gateway transport. " +
                    "Your message was sent as text.",
            )
        }

        // SSE dispatch shared by the three HTTP endpoints AND the gateway
        // branch's per-turn fallback (gateway unreachable / not the resolved
        // transport). Warns once per dispatch about any attachment it can't carry.
        fun dispatchSse(endpoint: String): ActiveTurnHandle {
            dispatchedSseEndpoint = endpoint
            updateTurnCheckpointTransport(endpoint)
            warnIfAttachmentsDropped(endpoint)
            return when (endpoint) {
            "runs" -> client.sendRunStream(
                    message = message,
                    systemMessage = systemMsg,
                    attachments = attachments,
                    voiceIntentMessages = voiceIntentMessages,
                    onSessionId = { sid ->
                        handler.setSessionId(sid)
                        updateTurnCheckpointSession(sid)
                        onSessionChanged?.invoke(sid)
                    },
                    onMessageStarted = onMessageStartedCb,
                    onTextDelta = onTextDeltaCb,
                    onThinkingDelta = onThinkingDeltaCb,
                    onToolCallStart = onToolCallStartCb,
                    onToolCallDone = onToolCallDoneCb,
                    onToolCallFailed = onToolCallFailedCb,
                    onTurnComplete = onTurnCompleteCb,
                    onComplete = onCompleteCb,
                    onUsage = onUsageCb,
                    onError = onErrorCb,
                    modelOverride = modelOverride,
                    profileName = profileName,
                ).asTurnHandle()
            "completions" -> client.sendChatCompletionsStream(
                    message = message,
                    systemMessage = systemMsg,
                    attachments = attachments,
                    voiceIntentMessages = voiceIntentMessages,
                    onSessionId = { /* stateless OpenAI-compatible endpoint */ },
                    onMessageStarted = onMessageStartedCb,
                    onTextDelta = onTextDeltaCb,
                    onThinkingDelta = onThinkingDeltaCb,
                    onToolCallStart = onToolCallStartCb,
                    onToolCallDone = onToolCallDoneCb,
                    onToolCallFailed = onToolCallFailedCb,
                    onTurnComplete = onTurnCompleteCb,
                    onComplete = onCompleteCb,
                    onUsage = onUsageCb,
                    onError = onErrorCb,
                    modelOverride = modelOverride,
                    profileName = profileName,
                ).asTurnHandle()
            else -> client.sendChatStream(
                    sessionId = sessionId,
                    message = message,
                    systemMessage = systemMsg,
                    attachments = attachments,
                    voiceIntentMessages = voiceIntentMessages,
                    onSessionId = { /* already set */ },
                    onMessageStarted = onMessageStartedCb,
                    onTextDelta = onTextDeltaCb,
                    onThinkingDelta = onThinkingDeltaCb,
                    onToolCallStart = onToolCallStartCb,
                    onToolCallDone = onToolCallDoneCb,
                    onToolCallFailed = onToolCallFailedCb,
                    onTurnComplete = onTurnCompleteCb,
                    onComplete = onCompleteCb,
                    onUsage = onUsageCb,
                    onError = onErrorCb,
                    modelOverride = modelOverride,
                    profileName = profileName,
                ).asTurnHandle()
            }
        }

        // Edit-and-regenerate ordinal — armed by regenerateFromMessage for
        // exactly the next turn; consumed even when the turn lands on SSE
        // (the post-turn reload reconciles divergence in that case).
        val truncateOrdinal = pendingTruncateOrdinal
        pendingTruncateOrdinal = null

        val gateway = gatewayClient
        _steerableTurn.value = false
        // Voice turns must deliver their interface + spoken-output-formatting
        // context to the model, but the gateway prompt.submit RPC has NO
        // system-message slot (it is bare text — see the else branch). Prepending
        // to the user text would persist the instruction into the transcript.
        // The SSE endpoints carry it in the non-persisted system_message field
        // instead, so force any turn that has a per-turn interface context
        // (set only by sendVoiceMessage) onto SSE. resolveSseFallback picks the
        // best available SSE route.
        //
        // Synthetic sync messages (voice intents / card dispatches / provider-
        // answered realtime turns) have the same gateway limitation — prompt.submit
        // can't carry them — but on a gateway-primary phone "leave them for the
        // next SSE turn" means *never*: the default transport is the gateway, so
        // unsynced traces would defer forever and the agent never learns what
        // happened in realtime voice. Drain them by forcing this one turn onto
        // the sessions SSE route, but ONLY when that is strictly safe:
        //  - an existing session id + the sessions fallback route (a stateless
        //    completions/runs detour would drop THIS turn from the transcript
        //    to save a trace — worse than deferring), and
        //  - the default profile (a non-default profile's gateway session lives
        //    in that profile's own state.db, which the shared api_server surface
        //    can't see — the sessions POST would 404 and fail the user's turn).
        // Cost when it fires: one turn without live gateway thinking. The synced
        // traces persist server-side, so this happens at most once per batch.
        val sseDrainEndpoint = resolveSseFallback(handler)
        val forceSseForTraceDrain =
            voiceIntentMessages != null &&
                streamingEndpoint == "gateway" &&
                profileName == null &&
                sseDrainEndpoint == "sessions"
        val effectiveEndpoint =
            if ((interfaceContextPrompt != null || forceSseForTraceDrain) &&
                streamingEndpoint == "gateway"
            ) {
                sseDrainEndpoint
            } else {
                streamingEndpoint
            }
        updateTurnCheckpointTransport(effectiveEndpoint)
        // Remember whether this turn runs on the gateway client (vs an SSE
        // EventSource) so a mid-turn route handoff doesn't cancel it — only the
        // `else` branch below dispatches on the gateway.
        activeStreamIsGateway = effectiveEndpoint == "gateway" && gateway != null
        activeStream = when {
            effectiveEndpoint != "gateway" -> dispatchSse(effectiveEndpoint)

            // Gateway turns upload ALL attachments via their typed upstream
            // RPC (image.attach_bytes / pdf.attach / file.attach), matching the
            // desktop client. Only a missing gateway client forces the per-turn
            // SSE fallback (where non-image attachments are not upstream-
            // recognized and would be dropped — graceful degradation).
            gateway == null ->
                dispatchSse(resolveSseFallback(handler))

            else -> {
                val isNewSession = handler.currentSessionId.value == null
                val autoTitle = message.take(50).let { if (message.length > 50) "$it..." else it }
                // The gateway carries NO phone-context preamble: prompt.submit
                // is bare text with no system-message slot, so anything prepended
                // here persists into the user turn (ugly on history reload +
                // visible from desktop), and its only system overlay
                // (ephemeral_system_prompt) is the personality slot. Phone
                // context rides the SSE systemMessage (invisible) + the on-demand
                // android_phone_status tool instead. See PhoneStatusPromptBuilder.
                _steerableTurn.value = true
                gateway.sendTurn(
                    sessionId = handler.currentSessionId.value,
                    text = message,
                    newSessionTitle = if (isNewSession) autoTitle else null,
                    callbacks = GatewayTurnCallbacks(
                        onSessionId = { sid ->
                            val nowMs = System.currentTimeMillis()
                            handler.addSession(
                                ChatSession(
                                    sessionId = sid,
                                    title = autoTitle,
                                    model = null,
                                    updatedAt = nowMs,
                                    startedAt = nowMs,
                                    lastActivityAt = nowMs,
                                ),
                            )
                            handler.setSessionId(sid)
                            updateTurnCheckpointSession(sid)
                            selectBackgroundProcessSession(sid)
                            gatewayProcessController.sessionReady(sid)
                            onSessionChanged?.invoke(sid)
                            // The brand-new chat now has a session — apply any
                            // YOLO toggled before the first send as a SESSION-
                            // scoped write (the no-session path deliberately
                            // skipped the global env leak).
                            applyPendingYoloAfterSessionCreate()
                        },
                        onStart = { },
                        onTextDelta = onTextDeltaCb,
                        onThinkingDelta = onThinkingDeltaCb,
                        onToolCallStart = onToolCallStartCb,
                        onToolCallDone = onToolCallDoneCb,
                        onToolCallFailed = onToolCallFailedCb,
                        onTurnComplete = onTurnCompleteCb,
                        onComplete = onCompleteCb,
                        onUsage = onUsageCb,
                        onError = onErrorCb,
                        onToolGenerating = { name ->
                            handler.onToolGenerating(currentMessageId, name)
                            scheduleCheckpointWrite(immediate = true)
                        },
                        onSubagentEvent = { event ->
                            handler.onSubagentEvent(currentMessageId, event)
                            scheduleCheckpointWrite(immediate = true)
                        },
                        onInteractionRequest = { ask ->
                            presentInteractionAsk(handler, ask)
                        },
                        onInteractionExpired = { expiry ->
                            expirePendingAsk(expiry)
                        },
                        onStatusUpdate = { kind, text ->
                            handler.setTurnStatus(text, kind)
                            // The server prefixes terminal failures with ❌ —
                            // stamp the turn so a failed reply doesn't read as
                            // a normal answer.
                            if (text.trimStart().startsWith("❌")) {
                                handler.markError(currentMessageId)
                            }
                        },
                        onStatusClear = { kind ->
                            handler.clearTurnStatus(kind)
                        },
                    ),
                    attachments = attachments.orEmpty()
                        .map { it.toGatewayAttachment() },
                    truncateBeforeUserOrdinal = truncateOrdinal,
                    onPreflightFailure = {
                        _steerableTurn.value = false
                        if (intentionallyCancelled) {
                            // User cancelled while preflight was in flight —
                            // don't resurrect the turn on SSE.
                            intentionallyCancelled = false
                            activeStream = null
                        } else {
                            // Nothing started server-side — rerun this turn on
                            // the SSE fallback. Callbacks land on the main
                            // thread, so swapping activeStream here is safe.
                            // The fallback turn is not steerable.
                            activeStream = dispatchSse(resolveSseFallback(handler))
                        }
                    },
                )
            }
        }

        // Flip syncedToServer=true on every voice-intent trace AND every
        // card dispatch now that the API client owns the request.
        // Idempotent — the handler methods skip already-synced records.
        // Done after the API client owns the request so a thrown
        // exception during request building would not falsely mark
        // traces as synced (no API call would have been made). Both
        // API-client paths above either succeed or throw synchronously;
        // the SSE callbacks fire asynchronously and never block this
        // point. Guarded per-stream so we only do the work when the
        // corresponding synthetic messages were actually sent.
        // Gateway turns can't carry synthetic messages (prompt.submit is
        // bare text) — leave traces unsynced so a later SSE turn (or the
        // forced trace-drain above) sends them. Checked against the route
        // this turn actually DISPATCHED on (effectiveEndpoint), not the
        // configured transport: a gateway-configured turn forced onto SSE
        // (voice interface context, trace drain) did carry the synthetic
        // messages, and skipping the mark there re-sent them every turn.
        // The async gateway preflight-failure fallback stays conservative:
        // its traces are marked on the NEXT turn (at-least-once delivery).
        if (voiceIntentMessages != null && effectiveEndpoint != "gateway") {
            if (hasVoiceIntents) handler.markVoiceIntentsSynced()
            if (hasCardDispatches) handler.markCardDispatchesSynced()
            if (hasRealtimeTurns) handler.markRealtimeTurnsSynced()
        }
    }

    /** Adapt an SSE [EventSource] to the transport-agnostic turn handle. */
    private fun EventSource.asTurnHandle(): ActiveTurnHandle =
        ActiveTurnHandle { this.cancel() }

    /**
     * SSE endpoint for a turn that was meant for the gateway. The sessions
     * endpoint needs an existing server session — without one, use the
     * stateless completions path instead of failing the turn.
     */
    private fun resolveSseFallback(handler: ChatHandler): String =
        if (sseFallbackEndpoint == "sessions" && handler.currentSessionId.value == null) {
            "completions"
        } else {
            sseFallbackEndpoint
        }

    private fun currentAgentDisplayName(
        effectiveProfileOverride: Profile? = null,
    ): String? {
        val selectedProfile = selectedProfileProvider()
        val effectiveProfile = effectiveProfileOverride
            ?: displayProfileProvider()
            ?: effectiveProfileProvider()
            ?: selectedProfile
        return AgentDisplay.agentName(
            profile = effectiveProfile,
            selectedPersonality = _selectedPersonality.value,
            defaultPersonality = _defaultPersonality.value,
            connectionLabel = null,
            localDisplayAlias = displayAliasProvider(),
        ).ifBlank { null }
    }

    private fun refreshActiveAgentName(
        effectiveProfileOverride: Profile? = null,
        relabelGenericMessages: Boolean = false,
    ) {
        val handler = chatHandler ?: return
        val displayName = currentAgentDisplayName(effectiveProfileOverride)
        handler.activeAgentName = displayName
        if (relabelGenericMessages) {
            handler.relabelGenericAssistantMessages(displayName)
        }
    }

    fun cancelStream() {
        intentionallyCancelled = true
        // User Stop also aborts a dropped-stream answer recovery. settleUi
        // false: the Stopped-badge block below finalizes the placeholder
        // itself (completing it here first would hide it from findLast).
        cancelAnswerRecovery(settleUi = false)
        activeStream?.cancel()
        activeStream = null
        _queuedMessages.value = emptyList()
        _steerableTurn.value = false
        _steerNotice.value = null
        // Gateway cancel issues session.interrupt, which force-denies any
        // blocked approval server-side — stamp the card to match.
        clearPendingAsk(approvalStamp = "deny")
        clearTurnCheckpoint()
        AppAnalytics.onStreamCancelled()
        chatHandler?.let { handler ->
            val streamingMsg = handler.messages.value.findLast { it.isStreaming }
            if (streamingMsg != null) {
                handler.markStopped(streamingMsg.id)
                handler.onStreamComplete(streamingMsg.id)
            }
        }
    }

    fun clearError() {
        chatHandler?.clearError()
    }

    fun retryLastMessage() {
        val lastMsg = chatHandler?.lastSentMessage?.value ?: return
        sendMessage(lastMsg)
    }

    // --- Inbound media handling ------------------------------------------------

    /**
     * Invoked when ChatHandler parses a `MEDIA:hermes-relay://<token>` marker.
     *
     * Flow:
     *  1. Insert a LOADING placeholder attachment on the matching assistant
     *     message so the user sees something immediately.
     *  2. Read current media settings (auto-fetch cap, cellular gate).
     *  3. If on cellular AND auto-fetch-on-cellular is off → leave the
     *     LOADING placeholder in place with [MEDIA_TAP_TO_DOWNLOAD] in
     *     [Attachment.errorMessage]. The UI renders a "Tap to download" CTA.
     *  4. Otherwise → kick off a background fetch, enforce the max size cap,
     *     cache the bytes via [MediaCacheWriter], and update the attachment
     *     to LOADED (or FAILED on any error).
     *
     * Note: the matching happens by (messageId + relayToken). If the same
     * token shows up twice (e.g. reconciliation pass finds it after real-time
     * already dispatched) the ChatHandler dedupes via dispatchedMediaMarkers
     * so we shouldn't see duplicate calls here.
     */
    fun onMediaAttachmentRequested(messageId: String, token: String) {
        val handler = chatHandler ?: return
        val relay = relayHttpClient
        val repo = mediaSettingsRepo
        val cache = mediaCacheWriter

        // 1. Insert LOADING placeholder.
        val placeholder = Attachment(
            contentType = "application/octet-stream",
            content = "",
            state = AttachmentState.LOADING,
            relayToken = token
        )
        appendAttachmentToMessage(handler, messageId, placeholder)

        if (relay == null || repo == null || cache == null) {
            // Media dependencies not wired yet — flip to FAILED so the UI
            // doesn't spin forever on a placeholder with no fetch in flight.
            updateAttachmentByToken(handler, messageId, token) { att ->
                att.copy(
                    state = AttachmentState.FAILED,
                    errorMessage = "Media pipeline not ready"
                )
            }
            return
        }

        viewModelScope.launch {
            val settings = repo.settings.first()

            // 2. Cellular gate.
            if (isOnCellular() && !settings.autoFetchOnCellular) {
                updateAttachmentByToken(handler, messageId, token) { att ->
                    att.copy(
                        state = AttachmentState.LOADING,
                        errorMessage = MEDIA_TAP_TO_DOWNLOAD
                    )
                }
                return@launch
            }

            // 3. Fetch + cache.
            performFetchWith(handler, messageId, token, settings) {
                relay.fetchMedia(token)
            }
        }
    }

    /**
     * Re-run the fetch for an attachment that's in the "Tap to download"
     * deferred state. Used by the inbound-media card's CTA on cellular.
     *
     * Works for both flavors of inbound attachment: if the stored key starts
     * with `/` it's an absolute path (bare-media form, use
     * [RelayHttpClient.fetchMediaByPath]); otherwise it's a relay token
     * (use [RelayHttpClient.fetchMedia]). `secrets.token_urlsafe` never
     * produces `/` so the prefix check is unambiguous.
     */
    fun manualFetchAttachment(messageId: String, attachmentIndex: Int) {
        val handler = chatHandler ?: return
        val relay = relayHttpClient ?: return
        val repo = mediaSettingsRepo ?: return
        val cache = mediaCacheWriter ?: return

        val msg = handler.messages.value.find { it.id == messageId } ?: return
        val att = msg.attachments.getOrNull(attachmentIndex) ?: return
        val fetchKey = att.relayToken ?: return

        // Flip back to a pure LOADING spinner (drop the CTA marker) so the
        // user gets immediate feedback that the download kicked off.
        updateAttachmentByToken(handler, messageId, fetchKey) { existing ->
            existing.copy(state = AttachmentState.LOADING, errorMessage = null)
        }

        viewModelScope.launch {
            val settings = repo.settings.first()
            performFetchWith(handler, messageId, fetchKey, settings) {
                if (fetchKey.startsWith("/")) {
                    relay.fetchMediaByPath(fetchKey)
                } else {
                    relay.fetchMedia(fetchKey)
                }
            }
        }
    }

    /**
     * Invoked when ChatHandler parses a bare-path `MEDIA:/abs/path` marker.
     *
     * The bare-path form is the LLM's native output — upstream
     * `agent/prompt_builder.py` explicitly instructs the model to "include
     * MEDIA:/absolute/path/to/file in your response" — so this is the
     * primary inbound-media path, not a fallback.
     *
     * Flow mirrors [onMediaAttachmentRequested]:
     *  1. Insert a LOADING placeholder with [Attachment.relayToken] set to
     *     the absolute path (serves as the stable key for state updates —
     *     since `secrets.token_urlsafe` never starts with `/`, we can
     *     disambiguate token vs path downstream by the leading `/`).
     *  2. Cellular gate (same as token path).
     *  3. Kick off a background fetch via [RelayHttpClient.fetchMediaByPath],
     *     which hits the bearer-auth'd `/media/by-path` relay route. The
     *     route enforces the same path sandbox as `/media/register`.
     *  4. On success → cache + flip to LOADED. On failure → FAILED with a
     *     user-facing message from [RelayHttpClient].
     */
    /**
     * Resolve a server-local image path — as emitted in an assistant markdown
     * image `![alt](/abs/path)` — to raw bytes via the relay's bearer-auth'd
     * `/media/by-path` route, so an inline image renders instead of degrading to
     * the "this image is on the server" notice. Returns null when no relay is
     * paired/configured (a standard no-plugin connection) or the fetch fails, so
     * the caller can fall back to that notice. Same relay route + path sandbox
     * the `MEDIA:` marker path uses ([onMediaBarePathRequested]); this just wires
     * it into the markdown-image renderer, which previously ignored the relay.
     */
    suspend fun resolveServerImage(serverPath: String): ServerImageResult {
        val relay = relayHttpClient
            ?: return ServerImageResult.Failure("Relay not configured on this connection")
        // fetchMediaByPath returns Result<MediaBytes>; fold it ONCE, right here,
        // into a non-Result type. The resolver boundary must not return
        // kotlin.Result from a suspend fun (see [ServerImageResult]).
        return relay.fetchMediaByPath(serverPath).fold(
            onSuccess = { ServerImageResult.Success(it.bytes, it.sensitive) },
            onFailure = { ServerImageResult.Failure(it.message ?: "relay fetch failed") },
        )
    }

    fun onMediaBarePathRequested(messageId: String, originalPath: String) {
        val handler = chatHandler ?: return
        val relay = relayHttpClient
        val repo = mediaSettingsRepo
        val cache = mediaCacheWriter

        val placeholder = Attachment(
            contentType = "application/octet-stream",
            content = "",
            state = AttachmentState.LOADING,
            // Reuse relayToken as a generic inbound-fetch key. Paths always
            // start with `/`, real tokens never do — downstream helpers
            // that need to distinguish can check the prefix.
            relayToken = originalPath,
            fileName = originalPath.substringAfterLast('/').ifBlank { null }
        )
        appendAttachmentToMessage(handler, messageId, placeholder)

        if (relay == null || repo == null || cache == null) {
            updateAttachmentByToken(handler, messageId, originalPath) { att ->
                att.copy(
                    state = AttachmentState.FAILED,
                    errorMessage = "Media pipeline not ready"
                )
            }
            return
        }

        viewModelScope.launch {
            val settings = repo.settings.first()

            if (isOnCellular() && !settings.autoFetchOnCellular) {
                updateAttachmentByToken(handler, messageId, originalPath) { att ->
                    att.copy(
                        state = AttachmentState.LOADING,
                        errorMessage = MEDIA_TAP_TO_DOWNLOAD
                    )
                }
                return@launch
            }

            performFetchWith(handler, messageId, originalPath, settings) {
                relay.fetchMediaByPath(originalPath)
            }
        }
    }

    /**
     * Core fetch routine. Takes a [fetch] lambda so it can serve both the
     * `hermes-relay://<token>` path ([RelayHttpClient.fetchMedia]) and the
     * bare-path `MEDIA:/abs/path` path ([RelayHttpClient.fetchMediaByPath]).
     *
     * [fetchKey] is whatever string identifies the attachment in
     * [Attachment.relayToken] — a token for the relay-hosted case, an
     * absolute path for the bare-path case. The size / cache / state
     * handling is identical; only the remote call differs.
     */
    private suspend fun performFetchWith(
        handler: ChatHandler,
        messageId: String,
        fetchKey: String,
        settings: MediaSettings,
        fetch: suspend () -> Result<RelayHttpClient.FetchedMedia>,
    ) {
        val cache = mediaCacheWriter ?: return
        val maxBytes = settings.maxInboundSizeMb.toLong().coerceAtLeast(1) * 1024L * 1024L

        val result = fetch()
        result.fold(
            onSuccess = { fetched ->
                if (fetched.bytes.size > maxBytes) {
                    val sizeMb = fetched.bytes.size / (1024.0 * 1024.0)
                    updateAttachmentByToken(handler, messageId, fetchKey) { att ->
                        att.copy(
                            state = AttachmentState.FAILED,
                            errorMessage = "File too large (%.1f MB, max %d MB)".format(
                                sizeMb, settings.maxInboundSizeMb
                            ),
                            contentType = fetched.contentType,
                            fileName = fetched.fileName ?: att.fileName,
                            fileSize = fetched.bytes.size.toLong()
                        )
                    }
                    return
                }

                try {
                    val uri = cache.cache(fetched.bytes, fetched.contentType, fetched.fileName)
                    updateAttachmentByToken(handler, messageId, fetchKey) { att ->
                        att.copy(
                            state = AttachmentState.LOADED,
                            errorMessage = null,
                            contentType = fetched.contentType,
                            fileName = fetched.fileName ?: att.fileName,
                            fileSize = fetched.bytes.size.toLong(),
                            cachedUri = uri.toString(),
                            // Carry the relay-authoritative sensitivity bit
                            // (X-Media-Sensitive header) onto the LOADED
                            // attachment so the renderer can blur per the
                            // user's blurMode. Both inbound fetch paths
                            // (token + bare-path) funnel through here, so this
                            // is the single threading point.
                            sensitive = fetched.sensitive
                        )
                    }
                } catch (e: Exception) {
                    // Classifier produces a specific label (disk full, bad
                    // URI, permission, …) for both the in-card text and the
                    // global snackbar — same event, two surfaces.
                    val human = classifyError(e, context = "media_fetch", ctx = appContext)
                    updateAttachmentByToken(handler, messageId, fetchKey) { att ->
                        att.copy(
                            state = AttachmentState.FAILED,
                            errorMessage = human.body
                        )
                    }
                    _errorEvents.tryEmit(human)
                }
            },
            onFailure = { err ->
                val human = classifyError(err, context = "media_fetch", ctx = appContext)
                updateAttachmentByToken(handler, messageId, fetchKey) { att ->
                    att.copy(
                        state = AttachmentState.FAILED,
                        errorMessage = human.body
                    )
                }
                _errorEvents.tryEmit(human)
            }
        )
    }

    /**
     * Append an attachment to a specific assistant message, matched by id.
     * No-ops if the message can't be found (e.g. it was trimmed from the
     * MAX_MESSAGES rolling buffer between the marker parse and the update).
     */
    private fun appendAttachmentToMessage(
        handler: ChatHandler,
        messageId: String,
        attachment: Attachment
    ) {
        // Direct StateFlow mutation via the handler's messages flow would be
        // cleaner, but ChatHandler exposes the flow as read-only. We piggyback
        // on the same pattern used by the tool-call callbacks: mutate in-place
        // through a handler method. For attachments there's no existing helper,
        // so we reach into the StateFlow via Kotlin's `update` reflection-free
        // pattern — except we can't, because _messages is private. Fall back
        // to a minimal helper added below.
        handler.mutateMessage(messageId) { msg ->
            if (msg.role != MessageRole.ASSISTANT) msg
            else msg.copy(attachments = msg.attachments + attachment)
        }
    }

    /**
     * Find the attachment on [messageId] whose [Attachment.relayToken] equals
     * [token] and apply [transform] to it. Used for all LOADING→LOADED/FAILED
     * transitions so the update key is stable across list shifts.
     */
    private fun updateAttachmentByToken(
        handler: ChatHandler,
        messageId: String,
        token: String,
        transform: (Attachment) -> Attachment
    ) {
        handler.mutateMessage(messageId) { msg ->
            if (msg.role != MessageRole.ASSISTANT) return@mutateMessage msg
            val idx = msg.attachments.indexOfFirst { it.relayToken == token }
            if (idx < 0) return@mutateMessage msg
            val updated = msg.attachments.toMutableList().also {
                it[idx] = transform(it[idx])
            }
            msg.copy(attachments = updated)
        }
    }

    // === PHASE3-status: cached-state snapshot for the dynamic prompt block ===
    /**
     * Construct a [PhoneSnapshot] from whatever cached state is reachable
     * *without* network calls or suspend functions. Every field is guarded
     * with `runCatching` so this is safe to call on a fresh install where
     * the Phase 3 accessibility/bridge/safety classes may not exist yet.
     *
     * Reflection is used for the Phase 3 classes (HermesAccessibilityService,
     * MediaProjectionHolder, BridgeSafetyManager, HermesNotificationCompanion)
     * so this file compiles before those classes land in the worktree. When
     * they do land, the reflective reads start returning real values with
     * zero further code changes here. Stay alert: if any of those classes
     * change their FQCN or their singleton accessor shape, update the
     * reflective lookups below.
     *
     * Privacy-sensitive fields (currentApp, batteryPercent) are gated by
     * the caller's [AppContextSettings] — this function always reads them
     * when they're cheap, but the builder drops them when the sub-toggle
     * is off. We do honour the gate for battery because `BATTERY_PROPERTY_CAPACITY`
     * may wake the battery stats service; when the sub-toggle is off we
     * simply don't read it.
     */
    private fun capturePhoneSnapshot(): PhoneSnapshot {
        val ctx = appContext ?: return PhoneSnapshot()

        // --- Accessibility service (Phase 3) ---
        // Reflective lookup of HermesAccessibilityService.instance — a
        // @Volatile companion singleton set in onServiceConnected. Kotlin
        // compiles companion object properties to either a static field on
        // the enclosing class (when @JvmStatic is used) or to a
        // Companion.getInstance() accessor. Try both shapes.
        var bridgeBound = false
        var masterEnabled = false
        var accessibilityGranted = false
        var currentAppPkg: String? = null
        runCatching {
            val cls = Class.forName("com.hermesandroid.relay.accessibility.HermesAccessibilityService")
            val instance: Any? = runCatching {
                // Shape A: @JvmStatic — static field on the outer class
                val f = cls.getDeclaredField("instance").apply { isAccessible = true }
                f.get(null)
            }.getOrNull() ?: runCatching {
                // Shape B: companion property with generated accessor
                val companionField = cls.getDeclaredField("Companion").apply { isAccessible = true }
                val companion = companionField.get(null)
                companion.javaClass.getMethod("getInstance").invoke(companion)
            }.getOrNull()

            if (instance != null) {
                bridgeBound = true
                accessibilityGranted = true
                runCatching {
                    val m = instance.javaClass.getMethod("isMasterEnabled")
                    masterEnabled = (m.invoke(instance) as? Boolean) == true
                }
                if (appContextSettings.currentApp) {
                    // Kotlin `val currentApp: String?` compiles to getCurrentApp()
                    runCatching {
                        val m = instance.javaClass.getMethod("getCurrentApp")
                        currentAppPkg = m.invoke(instance) as? String
                    }
                }
            }
        }

        // --- Screen capture (Phase 3 MediaProjectionHolder) ---
        var screenCaptureGranted = false
        runCatching {
            val cls = Class.forName("com.hermesandroid.relay.accessibility.MediaProjectionHolder")
            val instanceField = cls.getDeclaredField("INSTANCE").apply { isAccessible = true }
            val instance = instanceField.get(null)
            val m = instance.javaClass.getMethod("getProjection")
            screenCaptureGranted = m.invoke(instance) != null
        }

        // --- Overlay permission (platform API, always available) ---
        val overlayGranted = runCatching {
            android.provider.Settings.canDrawOverlays(ctx)
        }.getOrDefault(false)

        // --- Notification listener (Phase 3 HermesNotificationCompanion) ---
        val notificationsGranted = runCatching {
            val flat = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                "enabled_notification_listeners",
            ) ?: ""
            flat.contains(ctx.packageName)
        }.getOrDefault(false)

        // --- Battery (platform API, privacy-gated) ---
        val batteryPercent: Int? = if (appContextSettings.battery) {
            runCatching {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
                val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (pct != null && pct in 0..100) pct else null
            }.getOrNull()
        } else null

        // --- v0.4.1 Unattended access + screen state ---
        // Read UnattendedAccessManager's live StateFlows via direct import
        // (unlike BridgeSafetyManager which we hit reflectively because its
        // access is cross-cutting). Screen state comes from PowerManager —
        // cheap, no IPC, always accurate.
        val unattendedEnabled = com.hermesandroid.relay.bridge
            .UnattendedAccessManager.enabled.value
        val credentialLockDetected = com.hermesandroid.relay.bridge
            .UnattendedAccessManager.credentialLockDetected.value
        val screenOn = runCatching {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            pm?.isInteractive == true
        }.getOrDefault(false)

        // --- Safety manager (Phase 3 BridgeSafetyManager.peek()) ---
        var blocklistCount: Int? = null
        var destructiveVerbCount: Int? = null
        var autoDisableMinutes: Int? = null
        runCatching {
            val cls = Class.forName("com.hermesandroid.relay.bridge.BridgeSafetyManager")
            val companionField = cls.getDeclaredField("Companion").apply { isAccessible = true }
            val companion = companionField.get(null)
            val manager = companion.javaClass.getMethod("peek").invoke(companion)
            if (manager != null) {
                val settingsFlow = manager.javaClass.getMethod("getSettings").invoke(manager)
                val settings = settingsFlow?.javaClass?.getMethod("getValue")?.invoke(settingsFlow)
                if (settings != null) {
                    runCatching {
                        val blocklist = settings.javaClass.getMethod("getBlocklist").invoke(settings) as? Collection<*>
                        blocklistCount = blocklist?.size
                    }
                    runCatching {
                        val verbs = settings.javaClass.getMethod("getDestructiveVerbs").invoke(settings) as? Collection<*>
                        destructiveVerbCount = verbs?.size
                    }
                    runCatching {
                        val m = settings.javaClass.getMethod("getAutoDisableMinutes")
                        autoDisableMinutes = m.invoke(settings) as? Int
                    }
                }
            }
        }

        return PhoneSnapshot(
            bridgeBound = bridgeBound,
            masterEnabled = masterEnabled,
            accessibilityGranted = accessibilityGranted,
            screenCaptureGranted = screenCaptureGranted,
            overlayGranted = overlayGranted,
            notificationsGranted = notificationsGranted,
            unattendedEnabled = unattendedEnabled,
            credentialLockDetected = credentialLockDetected,
            screenOn = screenOn,
            currentApp = currentAppPkg,
            batteryPercent = batteryPercent,
            blocklistCount = blocklistCount,
            destructiveVerbCount = destructiveVerbCount,
            autoDisableMinutes = autoDisableMinutes,
        )
    }
    // === END PHASE3-status ===

    /**
     * True when the currently active network is cellular (metered LTE/5G).
     * Returns false on Wi-Fi, Ethernet, or when the state is unavailable.
     */
    private fun isOnCellular(): Boolean {
        val ctx = appContext ?: return false
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (_: Exception) {
            false
        }
    }

    override fun onCleared() {
        flushTurnCheckpointForTeardown()
        gatewayClient?.setUnsolicitedTurnProvider(null)
        gatewayClient?.setColdPrewarmSessionReadyListener(null)
        gatewayClient?.setUnmatchedTurnCompleteListener(null)
        gatewayHistoryReconcileJob?.cancel()
        gatewayHistoryReconcileJob = null
        backgroundProcessSessionJob?.cancel()
        backgroundProcessSessionJob = null
        gatewayProcessController.close()
        // UI/process teardown must not interrupt a server-side Gateway turn.
        // The durable checkpoint + session.activate/session.resume own reentry.
        activeStream?.detach()
        activeStream = null
        // viewModelScope teardown already cancels the poller job; this just
        // drops the reference symmetrically.
        streamRecovery?.cancel()
        streamRecovery = null
        super.onCleared()
    }

    private fun appendRealtimeThinkingStatus(
        handler: ChatHandler,
        assistantMessageId: String,
        key: String,
        message: String,
    ) {
        val normalized = message.trim().trimEnd('.')
        if (normalized.isBlank()) return
        val normalizedKey = key.trim().ifBlank { normalized }
        if (realtimeAgentProgressKeys[assistantMessageId] == normalizedKey) return
        realtimeAgentProgressKeys[assistantMessageId] = normalizedKey

        handler.mutateMessage(assistantMessageId) { msg ->
            val lastLine = msg.thinkingContent
                .lineSequence()
                .map { it.trim().trimEnd('.') }
                .filter { it.isNotBlank() }
                .lastOrNull()
            if (lastLine == normalized) {
                msg
            } else {
                val separator = if (
                    msg.thinkingContent.isBlank() ||
                    msg.thinkingContent.endsWith("\n")
                ) "" else "\n"
                msg.copy(
                    thinkingContent = msg.thinkingContent + separator + message.trim() + "\n",
                    isThinkingStreaming = true,
                )
            }
        }
    }
}

/** Production adapter for the independently-testable process controller. */
private class GatewayChatProcessSource(
    private val client: GatewayChatClient,
) : GatewayProcessSource {
    override val capability: StateFlow<GatewayProcessCapability>
        get() = client.processCapability

    override suspend fun listProcesses(): Result<List<GatewayProcess>> = client.listProcesses()

    override suspend fun killProcess(processId: String): Result<Unit> =
        client.killProcess(processId)

    override fun setEventListener(listener: ((GatewayProcessEvent) -> Unit)?) {
        client.setProcessEventListener(listener)
    }

    override fun isPollingAllowed(): Boolean = client.isBackgroundProcessPollingAllowed()
}

/**
 * One live gateway interactive ask plus where its local card lives in chat.
 * [cardKey] is the [com.hermesandroid.relay.data.HermesCard.id] — the ask's
 * `request_id`, or `approval-<sid>-<ts>` for approvals.
 */
data class PendingAsk(
    val ask: GatewayAsk,
    /** ChatMessage id of the local ask-card message (`ask-<cardKey>`). */
    val messageId: String,
    val cardKey: String,
    val receivedAt: Long = System.currentTimeMillis(),
)

/**
 * Outbound chat [Attachment] → [GatewayAttachment]. [contentType] carries the
 * routing decision (image → `image.attach_bytes`, pdf → `pdf.attach`, else →
 * `file.attach`). `ext` is the bare extension without the dot, derived from the
 * filename first and the MIME subtype second; null lets the server sniff magic
 * bytes (image path only — pdf/file uploads ignore it).
 */
private fun Attachment.toGatewayAttachment(): GatewayAttachment {
    val extFromName = fileName
        ?.substringAfterLast('.', "")
        ?.lowercase()
        ?.takeIf { ext -> ext.isNotBlank() && ext.length <= 5 && ext.all { it.isLetterOrDigit() } }
    val extFromMime = when (contentType.lowercase().substringBefore(';').trim()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp", "image/x-ms-bmp" -> "bmp"
        "application/pdf" -> "pdf"
        else -> null
    }
    return GatewayAttachment(
        name = fileName,
        base64 = content,
        ext = extFromName ?: extFromMime,
        contentType = contentType,
    )
}

/**
 * `commands.catalog` result → [SlashCommand] list. Top-level `pairs` is the
 * authoritative command set (skill commands appear ONLY there); `categories`
 * supplies grouping where the server provides it, everything else lands in
 * the palette's "server" bucket. Pure for unit-testability.
 */
internal fun parseCommandsCatalog(catalog: JsonObject): List<SlashCommand> {
    val categoryByName = mutableMapOf<String, String>()
    (catalog["categories"] as? JsonArray)?.forEach { element ->
        val obj = element as? JsonObject ?: return@forEach
        val category = obj.stringValue("name") ?: return@forEach
        (obj["pairs"] as? JsonArray)?.forEach { pairElement ->
            val pair = pairElement as? JsonArray ?: return@forEach
            val name = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@forEach
            categoryByName[name.lowercase()] = category
        }
    }
    val seen = mutableSetOf<String>()
    val out = mutableListOf<SlashCommand>()
    (catalog["pairs"] as? JsonArray)?.forEach { pairElement ->
        val pair = pairElement as? JsonArray ?: return@forEach
        val rawName = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull?.trim()
        if (rawName.isNullOrBlank()) return@forEach
        val name = if (rawName.startsWith("/")) rawName else "/$rawName"
        if (isUnsupportedMobileCommand(name, pair)) return@forEach
        if (!seen.add(name.lowercase())) return@forEach
        val description = (pair.getOrNull(1) as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        out += SlashCommand(
            command = name,
            description = description.ifBlank { "Server command" },
            category = categoryByName[name.lowercase()] ?: SlashCommand.CATEGORY_SERVER,
            source = SlashCommand.SOURCE_SERVER,
        )
    }
    return out
}

private fun isUnsupportedMobileCommand(name: String, pair: JsonArray): Boolean {
    val normalized = name.removePrefix("/").substringBefore(' ').lowercase()
    if (mobileBlockedSlashNotice(normalized) != null) return true
    val metadata = pair.drop(2).filterIsInstance<JsonObject>().firstOrNull()
    val cliOnly = metadata.booleanValue("cli_only", "cliOnly", "cli-only") == true
    val gatewayGate = metadata?.stringValue("gateway_config_gate")
        ?: metadata?.stringValue("gatewayConfigGate")
    return cliOnly && gatewayGate.isNullOrBlank()
}

private fun normalizeSlashCommandName(rawName: String): String? {
    val normalized = rawName
        .trim()
        .removePrefix("/")
        .replace('_', '-')
        .lowercase()
    if (normalized.isBlank()) return null
    val commandNameChars = normalized.all { it.isLetterOrDigit() || it == '-' }
    return normalized.takeIf { commandNameChars }
}

internal fun mobileBlockedSlashNotice(normalizedName: String): String? =
    when (normalizedName.replace('_', '-').lowercase()) {
        "update" -> "/update is only available from messaging platforms. Run `hermes update` from the terminal."
        else -> null
    }

private fun JsonObject?.booleanValue(vararg keys: String): Boolean? {
    if (this == null) return null
    keys.forEach { key ->
        val value = (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        when (value) {
            "true", "1", "yes" -> return true
            "false", "0", "no" -> return false
        }
    }
    return null
}

private val compactPreviewJson = Json { ignoreUnknownKeys = true }

private fun realtimeProgressThinkingLine(event: RealtimeVoiceEvent): String? {
    val message = (event.message ?: event.delta)
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    if (looksLikeRawRealtimeToolOutput(message)) return null
    return if (message.equals("Hermes is still working.", ignoreCase = true)) {
        "Waiting for Hermes response."
    } else {
        message.take(180)
    }
}

private fun looksLikeRawRealtimeToolOutput(line: String): Boolean {
    if (line.length > 360) return true
    val rawMarkers = listOf("{", "}", "[", "]", "Traceback", "Exception:", "\\n", "```")
    return rawMarkers.count { marker -> line.contains(marker) } >= 2
}

internal fun compactRealtimeToolResultPreview(raw: String, maxChars: Int = 700): String {
    summarizeStructuredToolPreview(raw)?.let { return it.limitPreview(maxChars) }
    val compact = raw
        .replace(Regex("\\s+"), " ")
        .trim()
    if (compact.length <= maxChars) return compact
    return compact.take(maxChars.coerceAtLeast(80)).trimEnd() + "..."
}

private fun summarizeStructuredToolPreview(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank() || trimmed.firstOrNull() !in setOf('{', '[')) return null
    val parsed = runCatching { compactPreviewJson.parseToJsonElement(trimmed) }.getOrNull()
    return when (parsed) {
        is JsonObject -> summarizeToolPreviewObject(parsed)
        is JsonArray -> "Structured result: ${parsed.size} items returned"
        else -> null
    }
}

private fun summarizeToolPreviewObject(obj: JsonObject): String? {
    val name = obj.stringValue("name") ?: obj.stringValue("tool_name")
    val description = obj.stringValue("description")
    if (!name.isNullOrBlank() && !description.isNullOrBlank()) {
        return "Loaded $name: ${description.compactWords()}"
    }

    val output = obj.stringValue("output")
    if (!output.isNullOrBlank()) {
        val compact = output.compactWords()
        return if (compact.length <= 180) {
            "Command output: $compact"
        } else {
            "Command output returned (${compact.length} chars)"
        }
    }

    val error = obj.stringValue("error") ?: obj.stringValue("message")
    if (!error.isNullOrBlank()) {
        return "Tool returned: ${error.compactWords()}"
    }

    val keys = obj.keys.take(5).joinToString(", ")
    return if (keys.isBlank()) null else "Structured result: $keys"
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

private const val RECENT_PROMPTS_LIMIT = 15
private const val DEFAULT_REASONING_EFFORT = "medium"
private val VALID_REASONING_EFFORTS = setOf("none", "minimal", "low", "medium", "high", "xhigh")

private fun normalizeReasoningEffort(value: String?): String {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return normalized.takeIf { it in VALID_REASONING_EFFORTS } ?: DEFAULT_REASONING_EFFORT
}

private fun String.compactWords(): String = replace(Regex("\\s+"), " ").trim()

private fun String.limitPreview(maxChars: Int): String {
    if (length <= maxChars) return this
    return take(maxChars.coerceAtLeast(80)).trimEnd() + "..."
}
