package com.hermesandroid.relay.viewmodel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.AppAnalytics
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.AttachmentState
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.ChatSession
import com.hermesandroid.relay.data.MediaSettings
import com.hermesandroid.relay.data.MediaSettingsRepository
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.RealtimeConversationContextMessage
import com.hermesandroid.relay.data.RealtimeTurnTrace
import com.hermesandroid.relay.data.ToolCallEvent
import com.hermesandroid.relay.data.VoiceIntentTrace
import com.hermesandroid.relay.data.HermesCard
import com.hermesandroid.relay.data.HermesCardAction
import com.hermesandroid.relay.data.HermesCardField
import com.hermesandroid.relay.data.HermesCardInput
import com.hermesandroid.relay.network.ActiveTurnHandle
import com.hermesandroid.relay.network.GatewayAsk
import com.hermesandroid.relay.network.GatewayChatClient
import com.hermesandroid.relay.network.GatewayConnectionState
import com.hermesandroid.relay.network.GatewayModelProvider
import com.hermesandroid.relay.network.GatewayAttachment
import com.hermesandroid.relay.network.GatewayRpcException
import com.hermesandroid.relay.network.GatewayTurnCallbacks
import com.hermesandroid.relay.network.HermesApiClient
import com.hermesandroid.relay.network.RelayHttpClient
import com.hermesandroid.relay.network.RealtimeVoiceEvent
import com.hermesandroid.relay.network.SteerResult
import com.hermesandroid.relay.network.handlers.ChatHandler
import com.hermesandroid.relay.network.handlers.LocalDispatchResult
import com.hermesandroid.relay.network.handlers.formatPhoneActionResult
import com.hermesandroid.relay.network.models.MessageItem
import com.hermesandroid.relay.network.models.SessionItem
import com.hermesandroid.relay.network.models.SkillInfo
import com.hermesandroid.relay.network.models.UsageInfo
import com.hermesandroid.relay.notifications.TurnCompleteNotifier
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
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.sse.EventSource
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

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
    private var firstTokenNotified = false
    private var toolHistoryJob: Job? = null
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
    }

    /** Callback to persist session ID — set by RelayApp */
    var onSessionChanged: ((String?) -> Unit)? = null

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
        _errorEvents.tryEmit(classifyError(t, context = context))
    }

    // --- Message queue ---
    private val _queuedMessages = MutableStateFlow<List<String>>(emptyList())
    val queuedMessages: StateFlow<List<String>> = _queuedMessages.asStateFlow()

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

    /** Current gateway model from `model.options`, used when no Android override is active. */
    private val _gatewayCurrentModel = MutableStateFlow("")
    val gatewayCurrentModel: StateFlow<String> = _gatewayCurrentModel.asStateFlow()

    /** Current gateway provider slug from `model.options`, paired with [gatewayCurrentModel]. */
    private val _gatewayCurrentProvider = MutableStateFlow("")
    val gatewayCurrentProvider: StateFlow<String> = _gatewayCurrentProvider.asStateFlow()

    /** Gateway reasoning effort for this session/global agent config. */
    private val _selectedReasoningEffort = MutableStateFlow(DEFAULT_REASONING_EFFORT)
    val selectedReasoningEffort: StateFlow<String> = _selectedReasoningEffort.asStateFlow()

    private val _reasoningDisplay = MutableStateFlow<String?>(null)

    /**
     * Refresh the gateway's curated provider/model list (`model.options`).
     * Connects the gateway on demand. No-op without a gateway client.
     */
    fun refreshModelOptions() {
        val gateway = gatewayClient ?: run {
            android.util.Log.i("ChatViewModel", "refreshModelOptions: no gateway client")
            return
        }
        viewModelScope.launch {
            gateway.modelOptions().fold(
                onSuccess = {
                    _modelProviders.value = it.providers
                    _gatewayCurrentModel.value = it.currentModel
                    _gatewayCurrentProvider.value = it.currentProvider
                    android.util.Log.i(
                        "ChatViewModel",
                        "model.options: ${it.providers.size} providers, " +
                            "${it.providers.sumOf { p -> p.models.size }} models, current=${it.currentModel}",
                    )
                },
                onFailure = {
                    android.util.Log.w("ChatViewModel", "model.options failed: ${it.message}")
                },
            )
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
        _selectedModelOverride.value = model?.takeIf { it.isNotBlank() }
        val gateway = gatewayClient
        val handler = chatHandler
        if (model.isNullOrBlank()) {
            if (streamingEndpoint == "gateway" && gateway != null && handler != null) {
                val defaultModel = (effectiveProfileProvider() ?: selectedProfileProvider())
                    ?.model
                    ?.takeIf { it.isNotBlank() }
                    ?: _serverModelName.value.takeIf { it.isNotBlank() }
                if (!defaultModel.isNullOrBlank()) {
                    viewModelScope.launch {
                        gateway.prewarm(handler.currentSessionId.value)
                        gateway.setModel(defaultModel).fold(
                            onSuccess = { result ->
                                val resolved = result.stringValue("value") ?: defaultModel
                                val warning = result.stringValue("warning")?.takeIf { it.isNotBlank() }
                                _gatewayCurrentModel.value = resolved
                                _gatewayCurrentProvider.value = ""
                                handler.addSystemNotice(
                                    listOfNotNull(
                                        "Using server default model ($resolved).",
                                        warning,
                                    ).joinToString("\n\n"),
                                )
                                gateway.modelOptions().onSuccess {
                                    _modelProviders.value = it.providers
                                    _gatewayCurrentModel.value = it.currentModel
                                    _gatewayCurrentProvider.value = it.currentProvider
                                }
                            },
                            onFailure = { e ->
                                handler.addSystemNotice(
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
            // Upstream model-switch flag string: `<model> [--provider <slug>]`.
            val value = if (!provider.isNullOrBlank()) "$model --provider $provider" else model
            viewModelScope.launch {
                // Warm a session so the switch is session-scoped (config.set
                // also works sessionless, falling back to the global default).
                gateway.prewarm(handler.currentSessionId.value)
                // Dispatch via config.set (the `_apply_model_switch` path) — NOT
                // the `/model` slash path, whose command.dispatch fallback
                // reports a spurious "not a quick/plugin/skill command" failure.
                gateway.setModel(value).fold(
                    onSuccess = { result ->
                        val resolved = result.stringValue("value") ?: model
                        val warning = result.stringValue("warning")?.takeIf { it.isNotBlank() }
                        _gatewayCurrentModel.value = resolved
                        _gatewayCurrentProvider.value = provider.orEmpty()
                        handler.addSystemNotice(
                            listOfNotNull("Model switched to $resolved.", warning).joinToString("\n\n"),
                        )
                        gateway.modelOptions().onSuccess {
                            _modelProviders.value = it.providers
                            _gatewayCurrentModel.value = it.currentModel
                            _gatewayCurrentProvider.value = it.currentProvider
                        }
                    },
                    onFailure = { e ->
                        handler.addSystemNotice("Couldn't switch model: ${e.message ?: "unknown error"}")
                    },
                )
            }
        }
        refreshActiveAgentName()
    }

    /**
     * Switch the gateway reasoning effort for this session through `config.set`.
     * The chip owns optimistic UI; failures are surfaced in chat.
     */
    fun selectReasoningEffort(value: String) {
        val normalized = normalizeReasoningEffort(value)
        _selectedReasoningEffort.value = normalized
        val gateway = gatewayClient ?: return
        val handler = chatHandler
        if (streamingEndpoint != "gateway" || handler == null) return
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
        // Mirror the desktop's hot-swap: switching the SELECTED profile (done by
        // the sheet's selectProfile call just before this) already drives
        // RelayApp's profile-context switch, which cancels any in-flight turn and
        // resets the thread (fresh draft, or the new profile's last session). We
        // must NOT also createNewChat() here — that second reset races the context
        // switch (the "reply typing, then a new chat appears" jank). All we do is
        // drop the live gateway session so the NEXT turn's session.create rebinds
        // the agent to the new profile (pulled live via sessionProfileProvider),
        // like the desktop spawning the profile's backend lazily on the next send.
        gateway.clearSession()
        // The profile brings its own model — refresh the picker's "current".
        viewModelScope.launch {
            gateway.modelOptions().onSuccess {
                _modelProviders.value = it.providers
                _gatewayCurrentModel.value = it.currentModel
                _gatewayCurrentProvider.value = it.currentProvider
            }
            gateway.getReasoningSettings().onSuccess {
                _selectedReasoningEffort.value = normalizeReasoningEffort(it.effort)
                _reasoningDisplay.value = it.display
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

    fun updateGatewayClient(client: GatewayChatClient?) {
        val changed = gatewayClient !== client
        gatewayClient = client
        // Bind each gateway session.create/resume to the currently-selected
        // profile (pulled live) — the upstream gateway builds the agent from it.
        client?.sessionProfileProvider = { AgentDisplay.profileRequestName(selectedProfileProvider()?.name) }
        when {
            client == null -> {
                _serverCommands.value = emptyList()
                _modelProviders.value = emptyList()
                _gatewayCurrentModel.value = ""
                _gatewayCurrentProvider.value = ""
                _selectedReasoningEffort.value = DEFAULT_REASONING_EFFORT
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
            }
            changed -> {
                _serverCommands.value = emptyList()
                _modelProviders.value = emptyList()
                _gatewayCurrentModel.value = ""
                _gatewayCurrentProvider.value = ""
                _selectedReasoningEffort.value = DEFAULT_REASONING_EFFORT
                _reasoningDisplay.value = null
            }
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
        gatewayClient?.prewarm(chatHandler?.currentSessionId?.value)
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
    private var selectedProfileProvider: () -> Profile? = { null }
    private var effectiveProfileProvider: () -> Profile? = { selectedProfileProvider() }
    private var displayProfileProvider: () -> Profile? = {
        effectiveProfileProvider() ?: selectedProfileProvider()
    }
    private var displayAliasProvider: () -> String? = { null }
    private var activeProfileContextKey: String? = null

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
     * own DB) and falling back to the shared api_server transcript otherwise or on
     * failure.
     */
    private suspend fun loadSessionHistory(sessionId: String): List<MessageItem> {
        if (streamingEndpoint == "gateway") {
            val scoped = profileMessageLoader?.invoke(sessionId)
            if (scoped != null) {
                scoped.getOrNull()?.let { return it }
            }
        }
        return apiClient?.getMessages(sessionId) ?: emptyList()
    }

    fun selectPersonality(name: String) {
        _selectedPersonality.value = name
        refreshActiveAgentName()
    }

    /** The display name of the currently active personality (for chat bubbles). */
    val activePersonalityName: String
        get() {
            val selected = _selectedPersonality.value
            return if (selected == "default") _defaultPersonality.value else selected
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

    val sessions: StateFlow<List<ChatSession>>
        get() = chatHandler?.sessions ?: _emptySessions

    val error: StateFlow<String?>
        get() = chatHandler?.error ?: _emptyError

    val currentSessionId: StateFlow<String?>
        get() = chatHandler?.currentSessionId ?: _emptySessionId

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
        this.chatHandler = chatHandler
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
        if (!activeStreamIsGateway) {
            activeStream?.cancel()
            activeStream = null
        }
        this.apiClient = client
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
                historyLoadGeneration.incrementAndGet()
                sessionRefreshGeneration.incrementAndGet()
                intentionallyCancelled = true
                activeStream?.cancel()
                activeStream = null
                sessionRefreshJob?.cancel()
                _isLoadingSessions.value = false
                activeProfileContextKey = null
                _queuedMessages.value = emptyList()
                _pendingAttachments.value = emptyList()
                _steerableTurn.value = false
                _steerNotice.value = null
                _pendingAsk.value = null
                _contextUsage.value = null
                pendingTruncateOrdinal = null
                chatHandler?.let { handler ->
                    handler.clearMessages()
                    handler.clearSessions()
                    handler.setSessionId(null)
                }
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
            _initialChatSettled.value = true
            return
        }

        activeStream?.let { stream ->
            intentionallyCancelled = true
            stream.cancel()
        }
        activeStream = null
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
        pendingTruncateOrdinal = null
        handler.clearSessions()
        handler.setSessionId(sessionId)
        handler.clearMessages()
        if (sessionId != null) {
            onSessionChanged?.invoke(sessionId)
        }

        if (sessionId == null || client == null) {
            _isLoadingHistory.value = false
            _initialChatSettled.value = true
            return
        }

        _isLoadingHistory.value = true
        viewModelScope.launch {
            try {
                val messages = loadSessionHistory(sessionId)
                if (
                    historyLoadGeneration.get() == loadGeneration &&
                    activeProfileContextKey == contextKey &&
                    handler.currentSessionId.value == sessionId
                ) {
                    handler.loadMessageHistory(messages)
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
                            // Profile-scoped list failed — fall back to the shared list.
                            apiClient?.listSessionsResult()?.onSuccess { handler.updateSessions(it) }
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

    fun createNewChat() {
        val client = apiClient ?: return
        val handler = chatHandler ?: return

        // Cancel any in-flight stream
        activeStream?.cancel()
        activeStream = null
        val loadGeneration = historyLoadGeneration.incrementAndGet()

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
                        _pendingAsk.value = null
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

    fun switchSession(sessionId: String) {
        apiClient ?: return
        val handler = chatHandler ?: return

        // Cancel any in-flight stream
        intentionallyCancelled = true
        activeStream?.cancel()
        activeStream = null
        val loadGeneration = historyLoadGeneration.incrementAndGet()

        handler.setSessionId(sessionId)
        handler.clearMessages()
        _contextUsage.value = null
        _pendingAsk.value = null
        onSessionChanged?.invoke(sessionId)
        AppAnalytics.onSessionSwitched()

        // Load message history (profile-scoped on gateway so a non-default
        // profile's sessions resolve against their own DB).
        _isLoadingHistory.value = true
        viewModelScope.launch {
            val messages = loadSessionHistory(sessionId)
            if (
                historyLoadGeneration.get() == loadGeneration &&
                handler.currentSessionId.value == sessionId
            ) {
                handler.loadMessageHistory(messages)
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
            onSessionChanged?.invoke(null)
        }

        viewModelScope.launch {
            val success = client.deleteSession(sessionId)
            if (!success && removedSession != null) {
                // Restore on failure
                handler.addSession(removedSession)
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        val client = apiClient ?: return
        val handler = chatHandler ?: return

        // Optimistic rename
        handler.renameSessionLocal(sessionId, newTitle)

        viewModelScope.launch {
            client.renameSession(sessionId, newTitle)
        }
    }

    // --- Message sending ---

    fun sendMessage(text: String) {
        if (text.isBlank()) return

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
    private fun presentInteractionAsk(handler: ChatHandler, ask: GatewayAsk) {
        val now = System.currentTimeMillis()
        val cardKey = ask.requestId
            ?: "approval-${handler.currentSessionId.value ?: "session"}-$now"
        val expiresAt = ask.timeoutSeconds.takeIf { it > 0 }?.let { now + it * 1_000L }
        val card = when (ask.kind) {
            GatewayAsk.Kind.APPROVAL -> HermesCard(
                type = HermesCard.BuiltInTypes.ASK_APPROVAL,
                title = "Approval requested",
                accent = HermesCard.Accents.WARNING,
                fields = listOf(HermesCardField("Command", ask.text)),
                actions = listOf(
                    HermesCardAction(
                        label = "Approve",
                        value = "approve",
                        style = HermesCardAction.Styles.PRIMARY,
                        mode = HermesCardAction.Modes.SUBMIT_ASK,
                    ),
                    HermesCardAction(
                        label = "Deny",
                        value = "deny",
                        style = HermesCardAction.Styles.DANGER,
                        mode = HermesCardAction.Modes.SUBMIT_ASK,
                    ),
                ),
                id = cardKey,
            )

            GatewayAsk.Kind.CLARIFY -> HermesCard(
                type = HermesCard.BuiltInTypes.ASK_CLARIFY,
                title = "Hermes needs clarification",
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
                title = "Elevated permission requested",
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
                        label = "Deny",
                        value = "",
                        style = HermesCardAction.Styles.DANGER,
                        mode = HermesCardAction.Modes.SUBMIT_ASK,
                    ),
                ),
            )

            GatewayAsk.Kind.SECRET -> HermesCard(
                type = HermesCard.BuiltInTypes.ASK_SECRET,
                title = "Secret requested",
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
                        label = "Skip",
                        value = "",
                        style = HermesCardAction.Styles.SECONDARY,
                        mode = HermesCardAction.Modes.SUBMIT_ASK,
                    ),
                ),
            )
        }
        val messageId = "ask-$cardKey"
        handler.appendAskCardMessage(messageId, card)
        _pendingAsk.value = PendingAsk(ask = ask, messageId = messageId, cardKey = cardKey)
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
                onSuccess = {
                    // Collapse only after the server confirms — a failed RPC
                    // must leave the card answerable for a retry.
                    handler.recordCardDispatch(pending.messageId, cardKey, stampValue)
                    if (_pendingAsk.value === pending) _pendingAsk.value = null
                },
                onFailure = { e ->
                    answeredAskIds.remove(cardKey)
                    emitError(e, context = "send_message")
                },
            )
        }
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

    fun clearQueue() {
        _queuedMessages.value = emptyList()
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

    fun applyRealtimeAgentEvent(
        assistantMessageId: String,
        event: RealtimeVoiceEvent,
        showDetailedTrace: Boolean = false,
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
                if (!name.isNullOrBlank() && !name.equals("hermes", ignoreCase = true)) {
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
            }
            "hermes.tool.started" -> {
                realtimeAgentHermesBacked[assistantMessageId] = true
                val name = event.toolName?.takeIf { it.isNotBlank() } ?: "hermes"
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
            }
            "hermes.run.completed" -> {
                // Hermes completion means the tool result is ready; provider narration ends the turn.
                Unit
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
                handler.onStreamComplete(assistantMessageId)
                realtimeAgentUserMessages.remove(assistantMessageId)
                realtimeAgentInputTranscripts.remove(assistantMessageId)
                realtimeAgentProviderBadges.remove(assistantMessageId)
                realtimeAgentToolCallIds.remove(assistantMessageId)
                realtimeAgentHermesBacked.remove(assistantMessageId)
                realtimeAgentProviderIds.remove(assistantMessageId)
                realtimeAgentModels.remove(assistantMessageId)
                realtimeAgentVoices.remove(assistantMessageId)
                activeStream = null
            }
            "hermes.run.cancelled" -> {
                handler.replaceMessageContent(assistantMessageId, "Cancelled.")
                handler.onStreamComplete(assistantMessageId)
                realtimeAgentUserMessages.remove(assistantMessageId)
                realtimeAgentInputTranscripts.remove(assistantMessageId)
                realtimeAgentProviderBadges.remove(assistantMessageId)
                realtimeAgentToolCallIds.remove(assistantMessageId)
                realtimeAgentHermesBacked.remove(assistantMessageId)
                realtimeAgentProviderIds.remove(assistantMessageId)
                realtimeAgentModels.remove(assistantMessageId)
                realtimeAgentVoices.remove(assistantMessageId)
                activeStream = null
            }
            "voice.error" -> {
                handler.onStreamError(event.message ?: "Realtime agent failed")
                realtimeAgentUserMessages.remove(assistantMessageId)
                realtimeAgentInputTranscripts.remove(assistantMessageId)
                realtimeAgentProviderBadges.remove(assistantMessageId)
                realtimeAgentToolCallIds.remove(assistantMessageId)
                realtimeAgentHermesBacked.remove(assistantMessageId)
                realtimeAgentProviderIds.remove(assistantMessageId)
                realtimeAgentModels.remove(assistantMessageId)
                realtimeAgentVoices.remove(assistantMessageId)
                activeStream = null
            }
        }
    }

    /**
         * Kick off an SSE chat turn against the selected chat endpoint.
     *
     * **System-message precedence (Pass 3, 2026-04-18):**
     *
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
    private fun startStream(
        client: HermesApiClient,
        handler: ChatHandler,
        sessionId: String,
        message: String,
        assistantMessageId: String,
        attachments: List<Attachment>? = null,
        interfaceContextPrompt: String? = null,
    ) {
        // Resolve the active profile pick once — used below for both
        // modelOverride and the system_message precedence rule.
        val selectedProfile = selectedProfileProvider()
        val useIsolatedProfileApi = selectedProfile?.hasIsolatedApi == true

        // Build persona prompt following the precedence rule documented on
        // this function's KDoc. A profile's systemMessage wins over a
        // selected personality when both are set.
        val selected = _selectedPersonality.value
        val profileSystemMessage = selectedProfile
            ?.systemMessage
            ?.takeIf { !useIsolatedProfileApi && it.isNotBlank() }
        val personaPrompt: String? = when {
            profileSystemMessage != null -> profileSystemMessage
            selected != "default" && selected != _defaultPersonality.value ->
                // Non-default personality selected — send its system prompt
                // to override the server default.
                personalityPrompts[selected]
            else -> null
        }
        // === PHASE3-status: dynamic phone-status block ===
        val appContext = buildPromptBlock(appContextSettings, capturePhoneSnapshot())
        val systemMsg = listOfNotNull(personaPrompt, appContext, interfaceContextPrompt)
            .joinToString("\n\n")
            .ifBlank { null }
        // === END PHASE3-status ===

        // Set agent name for display on chat bubbles. The selected/effective
        // Hermes profile is the active agent identity; personality is only a
        // fallback when no profile metadata is available.
        handler.activeAgentName = currentAgentDisplayName()

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

        // Show placeholder "thinking" message immediately — filled when first delta arrives
        handler.addPlaceholderMessage(
            ChatMessage(
                id = assistantMessageId,
                role = MessageRole.ASSISTANT,
                content = "",
                timestamp = System.currentTimeMillis(),
                isStreaming = true,
                agentName = handler.activeAgentName
            )
        )

        // Shared callbacks for both endpoints
        val onMessageStartedCb = { serverMsgId: String ->
            // Replace the placeholder's ID so subsequent deltas/tool calls attach
            // to it instead of creating a duplicate orphan bubble with streaming dots.
            // Only replaces empty+streaming messages (the placeholder), not completed turns.
            handler.replaceMessageId(currentMessageId, serverMsgId)
            currentMessageId = serverMsgId
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
        }
        val onToolCallDoneCb = { toolCallId: String, resultPreview: String? ->
            handler.onToolCallComplete(currentMessageId, toolCallId, resultPreview)
        }
        val onToolCallFailedCb = { toolCallId: String, errorMsg: String? ->
            handler.onToolCallFailed(currentMessageId, toolCallId, errorMsg)
        }
        // Turn complete — one assistant message finished, but the run may continue
        val onTurnCompleteCb = {
            handler.onTurnComplete(currentMessageId)
        }
        val onCompleteCb = {
            handler.onStreamComplete(currentMessageId)
            AppAnalytics.onStreamComplete(lastInputTokens, lastOutputTokens)
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
            maybeNotifyTurnComplete(handler, currentMessageId)

            // v0.4.1 polish: auto-return to Hermes-Relay if the bridge
            // moved the foreground app during this run. No-op when the
            // LLM already called `android_return_to_hermes` itself (in
            // that case the tracker's internal flag was cleared by the
            // /return_to_hermes dispatch's respond()). See BridgeRunTracker
            // KDoc for the full contract.
            com.hermesandroid.relay.bridge.BridgeRunTracker.notifyRunCompleted()

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
            if (sid != null && (streamingEndpoint == "sessions" || streamingEndpoint == "gateway")) {
                viewModelScope.launch {
                    // Profile-aware read: a gateway turn on a non-default profile
                    // persists into THAT profile's own state.db, so the bare
                    // api_server `/api/sessions/{id}/messages` 404s → emptyList()
                    // → a silent wipe of the just-finished turn. loadSessionHistory
                    // prefers the `?profile=` dashboard loader on gateway connections.
                    val serverMessages = loadSessionHistory(sid)
                    handler.loadMessageHistory(serverMessages)
                    // Re-sync the drawer now that the turn is persisted server-side.
                    // The only other auto-refresh fires ~160ms after session creation
                    // (RelayApp) — mid-stream, BEFORE the new session's first message
                    // is persisted, so a brand-new chat would otherwise stay missing
                    // from the drawer (carried only by the optimistic row) until a
                    // manual reload. By message.complete the dashboard list includes it.
                    refreshSessions()
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
                }
            }
        }
        val onErrorCb = { errorMsg: String ->
            if (intentionallyCancelled) {
                intentionallyCancelled = false
                // Cancellation (user Stop / session switch): suppress the
                // error banner — but STILL finalize the streaming UI so the
                // turn can never wedge "streaming forever" behind a dead Stop
                // button if a cancel and a transport error race.
                handler.messages.value.findLast { it.isStreaming }
                    ?.let { handler.onStreamComplete(it.id) }
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
                val sid = handler.currentSessionId.value
                if (sid != null && (streamingEndpoint == "sessions" || streamingEndpoint == "gateway")) {
                    viewModelScope.launch {
                        runCatching {
                            // Profile-aware read — see onCompleteCb: a bare
                            // getMessages 404s for a non-default-profile session
                            // and silently empties the transcript.
                            val serverMessages = loadSessionHistory(sid)
                            handler.loadMessageHistory(serverMessages)
                        }
                    }
                }
            }
            activeStream = null
            _queuedMessages.value = emptyList()
            _steerableTurn.value = false
            _steerNotice.value = null
            clearPendingAsk(approvalStamp = "deny")
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
        val modelOverride: String? = when {
            useIsolatedProfileApi -> null
            // An explicit in-chat model pick wins over the profile's model.
            !_selectedModelOverride.value.isNullOrBlank() -> _selectedModelOverride.value
            else -> selectedProfile?.model?.takeIf { it.isNotBlank() }
        }
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
            warnIfAttachmentsDropped(endpoint)
            return when (endpoint) {
            "runs" -> client.sendRunStream(
                    message = message,
                    systemMessage = systemMsg,
                    attachments = attachments,
                    voiceIntentMessages = voiceIntentMessages,
                    onSessionId = { sid ->
                        handler.setSessionId(sid)
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
        // Remember whether this turn runs on the gateway client (vs an SSE
        // EventSource) so a mid-turn route handoff doesn't cancel it — only the
        // `else` branch below dispatches on the gateway.
        activeStreamIsGateway = streamingEndpoint == "gateway" && gateway != null
        activeStream = when {
            streamingEndpoint != "gateway" -> dispatchSse(streamingEndpoint)

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
                            onSessionChanged?.invoke(sid)
                        },
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
                        },
                        onSubagentEvent = { event ->
                            handler.onSubagentEvent(currentMessageId, event)
                        },
                        onInteractionRequest = { ask ->
                            presentInteractionAsk(handler, ask)
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
        // bare text) — leave traces unsynced so the next SSE turn sends them.
        if (voiceIntentMessages != null && streamingEndpoint != "gateway") {
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
        activeStream?.cancel()
        activeStream = null
        _queuedMessages.value = emptyList()
        _steerableTurn.value = false
        _steerNotice.value = null
        // Gateway cancel issues session.interrupt, which force-denies any
        // blocked approval server-side — stamp the card to match.
        clearPendingAsk(approvalStamp = "deny")
        AppAnalytics.onStreamCancelled()
        chatHandler?.let { handler ->
            val streamingMsg = handler.messages.value.findLast { it.isStreaming }
            if (streamingMsg != null) {
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
                            cachedUri = uri.toString()
                        )
                    }
                } catch (e: Exception) {
                    // Classifier produces a specific label (disk full, bad
                    // URI, permission, …) for both the in-card text and the
                    // global snackbar — same event, two surfaces.
                    val human = classifyError(e, context = "media_fetch")
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
                val human = classifyError(err, context = "media_fetch")
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
        super.onCleared()
        activeStream?.cancel()
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
