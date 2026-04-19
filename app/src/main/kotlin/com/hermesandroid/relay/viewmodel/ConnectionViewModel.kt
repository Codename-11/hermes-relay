package com.hermesandroid.relay.viewmodel

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.auth.AuthManager
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.auth.PairedDeviceInfo
import com.hermesandroid.relay.auth.PairedSession
import com.hermesandroid.relay.data.DataManager
import com.hermesandroid.relay.data.MediaSettingsRepository
import com.hermesandroid.relay.data.PairingPreferences
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.ConnectionStore
import com.hermesandroid.relay.data.ConnectionValidation
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.relayDataStore
import com.hermesandroid.relay.util.TailscaleDetector
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.ConnectivityObserver
import com.hermesandroid.relay.network.ChatMode
import com.hermesandroid.relay.network.ConnectionManager
import com.hermesandroid.relay.network.ConnectionState
import com.hermesandroid.relay.network.HermesApiClient
import com.hermesandroid.relay.network.ServerCapabilities
import com.hermesandroid.relay.network.RelayHttpClient
import com.hermesandroid.relay.network.handlers.ChatHandler
// === PHASE3-accessibility: bridge channel wiring ===
import com.hermesandroid.relay.accessibility.BridgeStatusReporter
import com.hermesandroid.relay.accessibility.ScreenCapture
import com.hermesandroid.relay.network.handlers.BridgeCommandHandler
// === END PHASE3-accessibility ===
import com.hermesandroid.relay.util.MediaCacheWriter
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // API Server (direct chat)
        private val KEY_API_SERVER_URL = stringPreferencesKey("api_server_url")
        private const val DEFAULT_API_URL = "http://localhost:8642"

        // Relay Server (bridge/terminal)
        private val KEY_RELAY_URL = stringPreferencesKey("relay_url")
        private val KEY_SERVER_URL = stringPreferencesKey("server_url") // legacy migration
        private const val DEFAULT_RELAY_URL = "wss://localhost:8767"

        // How long the derived [relayUiState] shows `Connecting` before
        // promoting a Paired-but-Disconnected pose to `Stale`. Tuned to
        // cover a typical WSS handshake (~500-2000 ms) plus a cushion so
        // we don't flash "Stale" during a normal post-resume reconnect,
        // while still surfacing a tap-to-retry affordance when the
        // reconnect genuinely fails (server down, bad network).
        private const val RELAY_RECONNECT_GRACE_MS = 5_000L

        // Shared
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_FONT_SCALE = floatPreferencesKey("font_scale")
        const val DEFAULT_FONT_SCALE: Float = 1.0f
        private val KEY_INSECURE_MODE = booleanPreferencesKey("insecure_mode")
        private val KEY_LAST_SEEN_VERSION = stringPreferencesKey("last_seen_version")
        private val KEY_LAST_SESSION_ID = stringPreferencesKey("last_session_id")
        private val KEY_SHOW_THINKING = booleanPreferencesKey("show_thinking")
        private val KEY_TOOL_DISPLAY = stringPreferencesKey("tool_display")
        private val KEY_APP_CONTEXT = booleanPreferencesKey("app_context_prompt")
        // === PHASE3-status: granular phone-status sub-toggles ===
        // Gated by the master KEY_APP_CONTEXT. Privacy-sensitive fields
        // (current_app, battery) default false; everything else defaults true.
        private val KEY_APP_CONTEXT_BRIDGE_STATE = booleanPreferencesKey("app_context_bridge_state")
        private val KEY_APP_CONTEXT_CURRENT_APP = booleanPreferencesKey("app_context_current_app")
        private val KEY_APP_CONTEXT_BATTERY = booleanPreferencesKey("app_context_battery")
        private val KEY_APP_CONTEXT_SAFETY_STATUS = booleanPreferencesKey("app_context_safety_status")
        // === END PHASE3-status ===
        private val KEY_STREAMING_ENDPOINT = stringPreferencesKey("streaming_endpoint")
        private val KEY_PARSE_TOOL_ANNOTATIONS = booleanPreferencesKey("parse_tool_annotations")
        private val KEY_MAX_ATTACHMENT_MB = intPreferencesKey("max_attachment_mb")
        private val KEY_MAX_MESSAGE_LENGTH = intPreferencesKey("max_message_length")

        // Animation
        private val KEY_ANIMATION_ENABLED = booleanPreferencesKey("animation_enabled")
        private val KEY_ANIMATION_BEHIND_CHAT = booleanPreferencesKey("animation_behind_chat")

        // Chat scroll behavior
        private val KEY_SMOOTH_AUTO_SCROLL = booleanPreferencesKey("smooth_auto_scroll")
    }

    // --- Core networking components ---

    // Relay (bridge/terminal)
    val multiplexer = ChannelMultiplexer()
    val chatHandler = ChatHandler()

    // Multi-connection: the ConnectionStore is the source of truth for the
    // list of Hermes server connections and which one is active. Constructed
    // before AuthManager so the init-time migrateLegacyConnectionIfNeeded()
    // call can see the existing URL preferences and seed connection 0.
    val connectionStore: ConnectionStore = ConnectionStore(application)

    /**
     * Multi-connection: id of the currently-active [Connection], or null if
     * no connection has been seeded yet (cold boot before
     * migrateLegacyConnectionIfNeeded runs). Exposed through
     * [connectionStore] directly for cheap access.
     */
    val connections: StateFlow<List<Connection>> = connectionStore.connections
    val activeConnection: StateFlow<Connection?> = connectionStore.activeConnection
    val activeConnectionId: StateFlow<String?> = connectionStore.activeConnectionId

    // AuthManager owns the CertPinStore; ConnectionManager takes a snapshot
    // of the store's current pins on every connect so re-pair wipes land.
    // AuthManager must be constructed before ConnectionManager so the pin
    // store is available for the certificate pinner.
    //
    // Multi-connection: AuthManager is now a `var` — switchConnection()
    // rebuilds it bound to the newly-active connection id. Every call site
    // that touches this field goes through `this.authManager` so the
    // reconnect gate, the multiplexer sendCallback, and the
    // media-projection screen-capture token provider all read the current
    // instance after a swap.
    //
    // Initial value uses the active connection id if ConnectionStore has
    // already hydrated by the time this field initializer runs — which it
    // normally hasn't, since hydration is async. We fall back to the legacy
    // sentinel so the very first boot before migration mid-flight still
    // binds to the legacy token store. The init block below then observes
    // the first connection emission and rebuilds against the migrated id.
    var authManager: AuthManager = AuthManager(
        context = application,
        multiplexer = multiplexer,
        scope = viewModelScope,
        connectionId = AuthManager.CONNECTION_ID_LEGACY,
    )
        private set

    // Multi-connection: authState, pairingCode, and currentPairedSession
    // were previously direct references to the initial AuthManager's
    // backing flows. After a connection switch, `authManager` is replaced
    // but a captured reference still points at the OLD instance —
    // Compose's collectAsState caches the flow on first composition, so
    // consumers would show the previous connection's auth state forever.
    // We flatMapLatest over this MutableStateFlow so the public flows
    // re-subscribe to the new manager whenever installAuthManager() swaps
    // it.
    private val _authManagerFlow = MutableStateFlow(authManager)

    // Pass hasPairContext as the reconnect gate — the auto-reconnect loop
    // inside ConnectionManager bypasses ConnectionViewModel.connectRelay's
    // primary gate, so we plumb the same AuthManager check directly into
    // the internal scheduler. Without this, clearSession leaves a live
    // reconnect loop that fires stale auth envelopes and rate-limits us.
    //
    // Multi-connection: the gate reads `this.authManager.hasPairContext` on
    // every invocation so a connection switch's freshly-built AuthManager
    // is honored without having to plumb a new gate through
    // ConnectionManager. CertPinStore is process-wide (not per-connection)
    // so holding a reference to the legacy AuthManager's pin store is
    // still correct after a swap.
    private val connectionManager = ConnectionManager(
        multiplexer,
        authManager.certPinStore,
        reconnectGate = { authManager.hasPairContext }
    )

    // Data management — ConnectionStore flows through so exportSettings()
    // can include the current multi-connection snapshot in the backup blob.
    val dataManager = DataManager(application, connectionStore)

    // --- Inbound media pipeline ------------------------------------------------
    //
    // Three singletons that together let tool output emit `MEDIA:hermes-relay://<token>`
    // markers which the phone turns into inline attachments:
    //   - [mediaSettingsRepo] exposes user-tunable limits (max size, cache cap, etc.)
    //   - [mediaCacheWriter]  writes fetched bytes to `cacheDir/hermes-media/` and
    //                         hands out `content://` URIs via the FileProvider.
    //   - [relayHttpClient]   pulls bytes from `GET /media/<token>` on the relay
    //                         using the same session token as the WSS channel.
    //
    // These are owned by the ConnectionViewModel so they share lifetime with the
    // rest of the networking stack and get torn down on onCleared().
    val mediaSettingsRepo = MediaSettingsRepository(application)

    val mediaCacheWriter = MediaCacheWriter(
        context = application,
        cachedMediaCapMbProvider = {
            // Read from the current DataStore snapshot — the repo exposes a Flow,
            // but the writer calls this from a suspend context synchronously
            // during cache() and we want the latest value without blocking. Use
            // a tiny cached state that the DataStore collect loop updates.
            _cachedMediaCapMb
        }
    )

    /** Mirrored cap (MB) kept in sync with DataStore so [mediaCacheWriter] reads cheaply. */
    @Volatile
    private var _cachedMediaCapMb: Int = MediaSettingsRepository.DEFAULT_CACHED_MEDIA_CAP_MB

    /**
     * Shared OkHttp instance for the relay HTTP client. Separate from the one
     * inside [HermesApiClient] so API-server and relay connections don't
     * interfere, but configured the same way (long read timeout to handle
     * slow mobile connections + large files).
     */
    private val relayOkHttp: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(2, TimeUnit.MINUTES)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    val relayHttpClient = RelayHttpClient(
        okHttpClient = relayOkHttp,
        relayUrlProvider = { _relayUrl.value },
        sessionTokenProvider = {
            // AuthManager holds the paired session token in EncryptedSharedPrefs.
            // Pull it out via the authState StateFlow snapshot — if we're not
            // paired yet, return null and the fetch fails with a clean error.
            (authManager.authState.value as? AuthState.Paired)?.token
        }
    )

    // --- Relay connection state ---
    val relayConnectionState: StateFlow<ConnectionState> = connectionManager.connectionState

    // Resolved UI state for the relay row — the single source of truth all
    // three connection-related screens consume. Driven by a coroutine in
    // `init` that combines authState + relayConnectionState + relayUrl and
    // applies a grace window before promoting Paired-but-Disconnected to
    // Stale. See [RelayUiState] kdoc for the full rationale.
    private val _relayUiState = MutableStateFlow<RelayUiState>(RelayUiState.NotConfigured)
    val relayUiState: StateFlow<RelayUiState> = _relayUiState.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val authState: StateFlow<AuthState> = _authManagerFlow
        .flatMapLatest { it.authState }
        .stateIn(viewModelScope, SharingStarted.Eagerly, authManager.authState.value)

    val insecureMode: StateFlow<Boolean> = connectionManager.insecureMode
    val isInsecureConnection: StateFlow<Boolean> = connectionManager.isInsecureConnection

    // --- API Server state ---
    private val _apiServerUrl = MutableStateFlow(DEFAULT_API_URL)
    val apiServerUrl: StateFlow<String> = _apiServerUrl.asStateFlow()

    private val _apiServerReachable = MutableStateFlow(false)
    val apiServerReachable: StateFlow<Boolean> = _apiServerReachable.asStateFlow()

    /**
     * Tri-state health for the API server. Distinct from
     * [apiServerReachable] (a Boolean) because the UI needs to render a
     * dedicated "Probing" pose right after foreground / network change so
     * the badge doesn't flash a stale Connected/Disconnected from the last
     * session. The boolean stays in place for legacy callers; new code
     * should consume this flow.
     */
    enum class HealthStatus { Unknown, Probing, Reachable, Unreachable }

    private val _apiServerHealth = MutableStateFlow(HealthStatus.Unknown)
    val apiServerHealth: StateFlow<HealthStatus> = _apiServerHealth.asStateFlow()

    private val _relayServerHealth = MutableStateFlow(HealthStatus.Unknown)
    val relayServerHealth: StateFlow<HealthStatus> = _relayServerHealth.asStateFlow()

    private val _apiClient = MutableStateFlow<HermesApiClient?>(null)
    val apiClient: StateFlow<HermesApiClient?> = _apiClient.asStateFlow()

    private val _chatMode = MutableStateFlow(ChatMode.DISCONNECTED)
    val chatMode: StateFlow<ChatMode> = _chatMode.asStateFlow()

    // Per-endpoint capability snapshot from the most recent probe. Used by
    // ChatViewModel to resolve `streamingEndpoint = "auto"` to a concrete
    // sessions/runs choice without round-tripping to the network on every
    // send. Refreshed inside `rebuildApiClient()`.
    private val _serverCapabilities = MutableStateFlow(ServerCapabilities.DISCONNECTED)
    val serverCapabilities: StateFlow<ServerCapabilities> = _serverCapabilities.asStateFlow()

    // Chat is ready when API client exists and server is reachable
    val chatReady: StateFlow<Boolean> = combine(_apiClient, _apiServerReachable) { client, reachable ->
        client != null && reachable
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // --- Relay URL ---
    private val _relayUrl = MutableStateFlow(DEFAULT_RELAY_URL)
    val relayUrl: StateFlow<String> = _relayUrl.asStateFlow()

    // Backward compat: expose as serverUrl for any remaining references
    @Deprecated("Use relayUrl or apiServerUrl", replaceWith = ReplaceWith("relayUrl"))
    val serverUrl: StateFlow<String> = _relayUrl

    // Backward compat: expose relay state as connectionState
    @Deprecated("Use relayConnectionState", replaceWith = ReplaceWith("relayConnectionState"))
    val connectionState: StateFlow<ConnectionState> = relayConnectionState

    // Theme preference
    val theme: StateFlow<String> = application.relayDataStore.data
        .map { preferences ->
            preferences[KEY_THEME] ?: "auto"
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    // Global font scale (1.0 = system default). Applied at the Compose theme
    // root via LocalDensity.fontScale and pushed into the xterm WebView via
    // TerminalWebView's LaunchedEffect on this flow.
    val fontScale: StateFlow<Float> = application.relayDataStore.data
        .map { preferences ->
            preferences[KEY_FONT_SCALE] ?: DEFAULT_FONT_SCALE
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_FONT_SCALE)

    // Onboarding state
    private val _onboardingCompleted = MutableStateFlow(true) // default true to avoid flash
    val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted.asStateFlow()

    // Pairing code from AuthManager — see _authManagerFlow comment above for
    // why this flatMapLatches rather than referencing authManager directly.
    @OptIn(ExperimentalCoroutinesApi::class)
    val pairingCode: StateFlow<String> = _authManagerFlow
        .flatMapLatest { it.pairingCode }
        .stateIn(viewModelScope, SharingStarted.Eagerly, authManager.pairingCode.value)

    // Paired-session snapshot (expires_at, grants, transport hint) from AuthManager.
    // Exposed straight through so SettingsScreen + PairedDevicesScreen can
    // render expiry + grant chips without poking at prefs.
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentPairedSession: StateFlow<PairedSession?> = _authManagerFlow
        .flatMapLatest { it.currentPairedSession }
        .stateIn(viewModelScope, SharingStarted.Eagerly, authManager.currentPairedSession.value)

    // --- Agent profiles (Pass 2) -------------------------------------------
    //
    // Server-advertised named agent configs, flattened to a StateFlow the
    // profile picker reads. Must flatMapLatest over [_authManagerFlow] for
    // the same reason as [authState] / [pairingCode] — after a connection
    // switch the underlying [AuthManager] instance is replaced and the
    // public flow needs to repoint at the new manager's backing state.
    @OptIn(ExperimentalCoroutinesApi::class)
    val agentProfiles: StateFlow<List<Profile>> = _authManagerFlow
        .flatMapLatest { it.agentProfiles }
        .stateIn(viewModelScope, SharingStarted.Eagerly, authManager.agentProfiles.value)

    /**
     * User's current profile pick for the chat send pipeline. `null` means
     * "no explicit pick — let the server fall back to its configured
     * default profile." Reset to `null` on every connection switch (see
     * [init]) because profiles are advertised per-server and a selection
     * made against connection A has no defined meaning on connection B.
     *
     * Worker C (ChatViewModel + ChatScreen) is the sole mutator via
     * [selectProfile] and the sole reader — this class just owns the
     * StateFlow and the reset lifecycle.
     */
    private val _selectedProfile = MutableStateFlow<Profile?>(null)
    val selectedProfile: StateFlow<Profile?> = _selectedProfile.asStateFlow()

    /**
     * Set (or clear, with `null`) the active profile pick. See
     * [selectedProfile] for scope + reset semantics. Pure write — no
     * network traffic, no persistence.
     */
    fun selectProfile(profile: Profile?) {
        _selectedProfile.value = profile
    }

    // --- Paired devices list (GET /sessions) -------------------------------
    //
    // Loaded on-demand from PairedDevicesScreen. State is held here so the
    // screen can navigate away and come back without re-fetching every time.

    private val _pairedDevices = MutableStateFlow<List<PairedDeviceInfo>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDeviceInfo>> = _pairedDevices.asStateFlow()

    private val _pairedDevicesLoading = MutableStateFlow(false)
    val pairedDevicesLoading: StateFlow<Boolean> = _pairedDevicesLoading.asStateFlow()

    private val _pairedDevicesError = MutableStateFlow<String?>(null)
    val pairedDevicesError: StateFlow<String?> = _pairedDevicesError.asStateFlow()

    // --- Tailscale detection (informational) -------------------------------
    //
    // Purely for UI labeling. Does NOT auto-change TTLs or flip insecure mode.
    // Exposed to SettingsScreen + SessionTtlPickerDialog.
    private val tailscaleDetector = TailscaleDetector(
        context = application,
        scope = viewModelScope,
        relayUrlProvider = { _relayUrl.value },
    )
    val isTailscaleDetected: StateFlow<Boolean> = tailscaleDetector.isTailscaleDetected

    // What's New tracking
    private val _showWhatsNew = MutableStateFlow(false)
    val showWhatsNew: StateFlow<Boolean> = _showWhatsNew.asStateFlow()

    // Last session ID persistence
    private val _lastSessionId = MutableStateFlow<String?>(null)
    val lastSessionId: StateFlow<String?> = _lastSessionId.asStateFlow()

    // Connectivity
    private val connectivityObserver = ConnectivityObserver(application)
    val networkStatus: StateFlow<ConnectivityObserver.Status> = connectivityObserver.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectivityObserver.Status.Available)

    // Splash readiness — true once initial DataStore load + onboarding check is done
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    // Show thinking toggle
    val showThinking: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_SHOW_THINKING] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowThinking(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_SHOW_THINKING] = enabled
            }
        }
    }

    // Tool call display mode: "off", "compact", "detailed"
    val toolDisplay: StateFlow<String> = application.relayDataStore.data
        .map { it[KEY_TOOL_DISPLAY] ?: "detailed" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "detailed")

    fun setToolDisplay(mode: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_TOOL_DISPLAY] = mode
            }
        }
    }

    // App context prompt toggle
    val appContextEnabled: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_APP_CONTEXT] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAppContext(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_APP_CONTEXT] = enabled
            }
        }
    }

    // === PHASE3-status: granular sub-toggle flows + setters ===
    // Mirror the appContextEnabled pattern above. All four are gated by the
    // master toggle in PhoneStatusPromptBuilder.buildPromptBlock(), so when
    // master is off these values don't matter — we still expose them as
    // StateFlow so the ChatSettingsScreen preview card stays in sync.
    val appContextBridgeState: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_APP_CONTEXT_BRIDGE_STATE] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAppContextBridgeState(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_APP_CONTEXT_BRIDGE_STATE] = enabled
            }
        }
    }

    val appContextCurrentApp: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_APP_CONTEXT_CURRENT_APP] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAppContextCurrentApp(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_APP_CONTEXT_CURRENT_APP] = enabled
            }
        }
    }

    val appContextBattery: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_APP_CONTEXT_BATTERY] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAppContextBattery(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_APP_CONTEXT_BATTERY] = enabled
            }
        }
    }

    val appContextSafetyStatus: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_APP_CONTEXT_SAFETY_STATUS] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAppContextSafetyStatus(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_APP_CONTEXT_SAFETY_STATUS] = enabled
            }
        }
    }
    // === END PHASE3-status ===

    // Streaming endpoint preference. Three values:
    //   "auto"     — pick based on per-endpoint capability detection (default
    //                for new installs as of v0.3.0). Resolves to "sessions"
    //                when the server has /api/sessions/{id}/chat/stream
    //                (fork or upstream-merged), otherwise "runs".
    //   "sessions" — force /api/sessions/{id}/chat/stream
    //   "runs"     — force /v1/runs
    //
    // Existing users keep whatever they previously chose. Only fresh installs
    // (no value persisted yet) get the new "auto" default.
    val streamingEndpoint: StateFlow<String> = application.relayDataStore.data
        .map { it[KEY_STREAMING_ENDPOINT] ?: "auto" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    fun setStreamingEndpoint(endpoint: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_STREAMING_ENDPOINT] = endpoint
            }
        }
    }

    /**
     * Resolve the user's `streamingEndpoint` preference to a concrete value
     * based on the latest capability probe. Returns "sessions" or "runs"
     * (never "auto"). Used by ChatViewModel right before kicking off a stream.
     *
     * - "sessions" / "runs" pass through unchanged (manual override wins).
     * - "auto" → reads `serverCapabilities.value.preferredChatEndpoint()`.
     */
    fun resolveStreamingEndpoint(preference: String): String = when (preference) {
        "sessions", "runs" -> preference
        else -> _serverCapabilities.value.preferredChatEndpoint()
    }

    // Parse tool annotations from text markers toggle
    val parseToolAnnotations: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_PARSE_TOOL_ANNOTATIONS] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setParseToolAnnotations(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_PARSE_TOOL_ANNOTATIONS] = enabled
            }
        }
    }

    // Animation settings
    val animationEnabled: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_ANIMATION_ENABLED] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val animationBehindChat: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_ANIMATION_BEHIND_CHAT] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAnimationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_ANIMATION_ENABLED] = enabled
            }
        }
    }

    fun setAnimationBehindChat(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_ANIMATION_BEHIND_CHAT] = enabled
            }
        }
    }

    // Smooth auto-scroll during chat streaming.
    // When enabled, the chat list smoothly follows new tokens, tool cards, and
    // reasoning deltas as they stream in — but only while the user is at the
    // bottom of the conversation. Scrolling up to read history disables the
    // auto-follow until the user returns to the bottom (or taps the FAB).
    val smoothAutoScroll: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_SMOOTH_AUTO_SCROLL] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setSmoothAutoScroll(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_SMOOTH_AUTO_SCROLL] = enabled
            }
        }
    }

    // Max attachment size in MB (default 10)
    val maxAttachmentMb: StateFlow<Int> = application.relayDataStore.data
        .map { it[KEY_MAX_ATTACHMENT_MB] ?: 10 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 10)

    fun setMaxAttachmentMb(mb: Int) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_MAX_ATTACHMENT_MB] = mb
            }
        }
    }

    // Max message character length (default 4096)
    val maxMessageLength: StateFlow<Int> = application.relayDataStore.data
        .map { it[KEY_MAX_MESSAGE_LENGTH] ?: 4096 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 4096)

    fun setMaxMessageLength(length: Int) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_MAX_MESSAGE_LENGTH] = length
            }
        }
    }

    // === PHASE3-accessibility: bridge channel wiring ===
    // ScreenCapture needs a MediaProjection grant from the Bridge UI
    // (MediaProjectionHolder) — it's nullable here so the handler can be
    // constructed before the user has consented to screen capture.
    // [BridgeCommandHandler] will surface a 503 error for /screenshot
    // requests until [MediaProjectionHolder.projection] is non-null.
    private val screenCapture = ScreenCapture(
        context = application,
        httpClient = relayOkHttp,
        relayUrlProvider = { _relayUrl.value },
        sessionTokenProvider = {
            (authManager.authState.value as? AuthState.Paired)?.token
        },
        mediaProjectionProvider = {
            com.hermesandroid.relay.accessibility.MediaProjectionHolder.projection
        },
    )

    // === PHASE3-safety-rails: safety manager + overlay wiring ===
    // Process-wide singletons — install() is idempotent and the overlay
    // host wires itself into ConfirmationOverlayHost.instance so the
    // safety manager can reach it without a hard ref.
    private val bridgeSafetyManager =
        com.hermesandroid.relay.bridge.BridgeSafetyManager.install(
            context = application,
            scope = viewModelScope,
        ).also {
            com.hermesandroid.relay.bridge.BridgeStatusOverlay.install(application)
        }

    /** Exposed for BridgeScreen → safety summary card. */
    val bridgeSafety: com.hermesandroid.relay.bridge.BridgeSafetyManager get() = bridgeSafetyManager
    // === END PHASE3-safety-rails ===

    // v0.4.1 polish: bridge activity log sink. BridgeCommandHandler posts
    // one BridgeActivityEntry per dispatched command (except high-frequency
    // polls) which lands here and gets persisted to DataStore via
    // BridgePreferencesRepository. BridgeViewModel reads the same DataStore
    // flow to render the Activity Log card on the Bridge tab.
    private val bridgeActivityPrefsRepo =
        com.hermesandroid.relay.data.BridgePreferencesRepository(application)

    // Public so RelayApp can hand the local-dispatch entry point to
    // VoiceViewModel. The voice intent handler calls handleLocalCommand()
    // for in-process action dispatch (see BridgeCommandHandler KDoc).
    val bridgeCommandHandler = BridgeCommandHandler(
        multiplexer = multiplexer,
        scope = viewModelScope,
        screenCapture = screenCapture,
        // === PHASE3-safety-rails: safety enforcement ===
        safetyManager = bridgeSafetyManager,
        // === END PHASE3-safety-rails ===
        // === v0.4.1 polish: activity-log sink ===
        onActivity = { entry ->
            viewModelScope.launch {
                runCatching { bridgeActivityPrefsRepo.appendEntry(entry) }
                    .onFailure {
                        android.util.Log.w(
                            "ConnectionViewModel",
                            "activity log append failed: ${it.message}",
                        )
                    }
            }
        },
        // === END v0.4.1 polish ===
    )

    val bridgeStatusReporter = BridgeStatusReporter(
        context = application,
        multiplexer = multiplexer,
        scope = viewModelScope,
    )
    // === END PHASE3-accessibility (plus safety-rails wiring above) ===

    // --- Connection switch orchestration ----------------------------------
    //
    // Multi-connection v0.5.0: the coordinator owns the heavy swap sequence
    // (cancel stream → stop voice → disconnect WSS → rebuild AuthManager +
    // api client → reconnect WSS if paired). Extracted so the swap is
    // unit-testable without having to mock AndroidViewModel / DataStore.
    private val connectionSwitchCoordinator = ConnectionSwitchCoordinator(
        connectionStore = connectionStore,
        connectionManager = connectionManager,
        scope = viewModelScope,
        authManagerFactory = { cid ->
            AuthManager(
                context = application,
                multiplexer = multiplexer,
                scope = viewModelScope,
                connectionId = cid,
            )
        },
        installAuthManager = { am ->
            authManager = am
            // Push into the flow so the flatMapLatest chains on authState /
            // pairingCode / currentPairedSession repoint to the new manager.
            // Without this, existing Compose collectors stay bound to the
            // previous AuthManager's backing flows forever.
            _authManagerFlow.value = am
        },
        setApiServerUrl = { url -> _apiServerUrl.value = url },
        setRelayUrl = { url -> _relayUrl.value = url },
        persistUrls = { apiUrl, relayUrl ->
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_API_SERVER_URL] = apiUrl
                prefs[KEY_RELAY_URL] = relayUrl
            }
        },
        rebuildApiClient = { rebuildApiClient() },
    )

    /**
     * Multi-connection: emits the id of the newly-active connection right
     * after the connection teardown half of the switch completes but before
     * the rebuilt API/relay clients start serving requests. [ChatViewModel]
     * subscribes to clear its per-connection local state (messages, session
     * id, queued sends).
     */
    val connectionSwitchEvents: SharedFlow<String> = connectionSwitchCoordinator.connectionSwitchEvents

    /**
     * Multi-connection: start the full connection swap sequence. See
     * [ConnectionSwitchCoordinator] for the ordered steps and why the order
     * matters. No-ops when [connectionId] equals the current
     * [activeConnectionId]. Fires and forgets — UI watches
     * [activeConnection] for the completed state.
     */
    fun switchConnection(connectionId: String): Job =
        connectionSwitchCoordinator.switchConnection(connectionId)

    /**
     * Multi-connection: RelayApp calls this at composition time with a
     * callback that tears down [ChatViewModel]'s in-flight stream. The
     * coordinator invokes it first in the switch sequence so the stream
     * doesn't keep scribbling into `_messages` after the handler's API
     * client reference gets rebuilt under it.
     */
    fun registerStreamCancelCallback(callback: () -> Unit) {
        connectionSwitchCoordinator.registerStreamCancelCallback(callback)
    }

    /**
     * Multi-connection: RelayApp calls this at composition time with a
     * callback that stops any active voice turn. Kept as a registration
     * hook (rather than a direct VoiceViewModel field) so
     * [ConnectionViewModel] doesn't take on a hard voice dependency —
     * voice is an optional feature wired per build flavor.
     */
    fun registerVoiceStopCallback(callback: () -> Unit) {
        connectionSwitchCoordinator.registerVoiceStopCallback(callback)
    }

    // --- Connection CRUD helpers (Worker B2) ------------------------------
    //
    // These wrap [ConnectionStore] so callers (currently RelayApp's
    // ConnectionsSettings + Pair routes) don't have to reach directly into
    // the store for common mutations. Each method documents its scope and
    // v1 constraints.

    /**
     * Creates a new connection from completed pairing data, persists it via
     * [ConnectionStore], and switches the app to it. Returns the new
     * connection id.
     *
     * Called from the Pair success handler when no existing `connectionId`
     * was passed (i.e. the "Add connection" FAB flow).
     *
     * **v1 pairing-token limitation:** this method is invoked AFTER
     * [applyPairingPayload] has already run, which writes the freshly-minted
     * session token into the **currently-active** connection's
     * EncryptedSharedPreferences (via the active [authManager] instance).
     * The new connection that this method creates has its own distinct
     * [Connection.tokenStoreKey] and does NOT carry those credentials
     * across. Consequence: immediately after "Add connection → scan QR →
     * switch to new connection", the new connection is unpaired from the
     * token store's perspective and the user must re-pair it (scan a
     * second QR while the new connection is active).
     *
     * A cleaner fix would require targeting [applyPairingPayload] at a
     * specific token store BEFORE the pair runs — that's a bigger refactor
     * deferred to a follow-up. See the TODO at the top of RelayApp's Pair
     * route for the user-facing journey.
     */
    suspend fun addConnectionFromPairing(
        label: String?,
        apiServerUrl: String,
        relayUrl: String,
    ): Result<String> {
        val resolvedLabel = (label?.trim()?.takeIf { it.isNotEmpty() })
            ?: Connection.extractDefaultLabel(apiServerUrl)

        // Validate in the same order the fields appear to the user — label
        // then URLs — so the first error surfaced matches where their eye
        // would be.
        ConnectionValidation.validateLabel(resolvedLabel)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        ConnectionValidation.validateApiServerUrl(apiServerUrl)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        ConnectionValidation.validateRelayUrl(relayUrl)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        ConnectionValidation.findDuplicate(
            connections = connectionStore.connections.value,
            apiServerUrl = apiServerUrl,
            relayUrl = relayUrl,
        )?.let { dup ->
            return Result.failure(
                IllegalStateException("A connection for this server already exists (\"${dup.label}\")"),
            )
        }

        val id = java.util.UUID.randomUUID().toString()
        val connection = Connection(
            id = id,
            label = resolvedLabel,
            apiServerUrl = apiServerUrl.trim(),
            relayUrl = relayUrl.trim(),
            tokenStoreKey = Connection.buildTokenStoreKey(id),
            pairedAt = null,
            lastActiveSessionId = null,
            transportHint = null,
            expiresAt = null,
        )
        connectionStore.addConnection(connection)
        // Switch to the new connection so subsequent chat/bridge/voice
        // traffic uses its (currently empty) token store. Per the KDoc
        // above, the user will need to re-pair against this connection to
        // populate it.
        switchConnection(id)
        return Result.success(id)
    }

    /**
     * Updates the label on a stored connection. Persists via
     * [ConnectionStore.updateConnection]. Does not touch auth state; does
     * not trigger a switch. No-op when the connection id is unknown.
     */
    suspend fun renameConnection(connectionId: String, newLabel: String): Result<Unit> {
        val existing = connectionStore.connections.value.firstOrNull { it.id == connectionId }
            ?: return Result.failure(NoSuchElementException("No connection with id=$connectionId"))
        ConnectionValidation.validateLabel(newLabel)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        connectionStore.updateConnection(existing.copy(label = newLabel.trim()))
        return Result.success(Unit)
    }

    /**
     * Revokes the connection's server-side session
     * (`DELETE /sessions/{tokenPrefix}`) and clears the local auth material.
     *
     * **v1 constraint — active connection only:** if [connectionId] does
     * not match [activeConnectionId] this returns [Result.failure] with a
     * clear message and does nothing. Revoking an inactive connection
     * would require loading its
     * [com.hermesandroid.relay.auth.SessionTokenStore] out-of-band to read
     * the bearer for the DELETE call, which is a bigger change — the
     * current singleton [authManager] only reads the active connection's
     * store. Deferred; documented as a follow-up.
     *
     * On success, marks the connection as unpaired (`pairedAt = null`,
     * `expiresAt = null`, `transportHint = null`) via [ConnectionStore] so
     * the UI reflects it. Also disconnects the relay and wipes the local
     * session token via [AuthManager.clearSession].
     */
    suspend fun revokeConnection(connectionId: String): Result<Unit> {
        if (connectionId != activeConnectionId.value) {
            return Result.failure(
                IllegalStateException(
                    "revoke is limited to the active connection in v1",
                ),
            )
        }
        val existing = connectionStore.connections.value.firstOrNull { it.id == connectionId }
            ?: return Result.failure(
                IllegalStateException("connection $connectionId not found"),
            )
        // Token prefix = first 8 chars of the current session token.
        // Matches the relay's `session.token[:8]` convention (see
        // plugin/relay/server.py `token_prefix` sites).
        val paired = authManager.currentPairedSession.value
        if (paired != null) {
            val prefix = paired.token.take(8)
            val result = relayHttpClient.revokeSession(prefix)
            if (result.isFailure) {
                return Result.failure(
                    result.exceptionOrNull()
                        ?: IllegalStateException("revokeSession failed"),
                )
            }
        }
        // Clear local auth material + tear down the WSS so the reconnect
        // loop doesn't keep re-authing with the just-revoked token.
        clearSession()
        connectionStore.updateConnection(
            existing.copy(
                pairedAt = null,
                expiresAt = null,
                transportHint = null,
            ),
        )
        return Result.success(Unit)
    }

    /**
     * Removes a connection and its stored auth material. [ConnectionStore]
     * handles the [android.content.Context.deleteSharedPreferences] side
     * effect for the connection's EncryptedSharedPreferences file.
     *
     * If the removed connection was active, this switches to another
     * connection (if one exists) first so the app lands on a valid
     * context; otherwise [activeConnectionId] ends up null and the top bar
     * renders "No connection" until the user adds one via Settings.
     */
    suspend fun removeConnection(connectionId: String) {
        val wasActive = connectionId == activeConnectionId.value
        if (wasActive) {
            val other = connectionStore.connections.value.firstOrNull { it.id != connectionId }
            if (other != null) {
                // Await the full switch before deleting the store file.
                // switchConnection launches on viewModelScope; if we
                // proceeded to ConnectionStore.removeConnection (which
                // calls Context.deleteSharedPreferences) before the
                // coordinator finished tearing down the old AuthManager,
                // the old manager's in-flight init/hydrate coroutine could
                // fault reading a file that was just deleted.
                switchConnection(other.id).join()
            }
            // If no other connection exists, ConnectionStore.removeConnection
            // will clear the active pointer on its own.
        }
        connectionStore.removeConnection(connectionId)
    }

    init {
        // Wire multiplexer to connection manager (for relay/bridge/terminal)
        multiplexer.setSendCallback { envelope ->
            connectionManager.send(envelope)
        }

        // Auto-authenticate on relay connect
        multiplexer.setOnConnectedCallback {
            authManager.authenticate()
        }

        // === PHASE3-accessibility: bridge handler registration ===
        // Route every incoming `bridge` channel envelope to the command
        // handler. Registering unconditionally is safe — the handler
        // itself checks whether HermesAccessibilityService is running and
        // whether the master toggle is enabled before executing anything.
        // Start the periodic status reporter once too; it ticks every
        // 30s and is a no-op while the WSS isn't connected (multiplexer
        // just drops the envelopes).
        multiplexer.registerHandler("bridge") { envelope ->
            bridgeCommandHandler.onMessage(envelope)
        }
        bridgeStatusReporter.start()

        // === PHASE3-status: push status immediately on master toggle flip ===
        // The periodic tick is 30 s, but the relay-side cache (and the
        // agent's `android_phone_status()` tool that reads it) should see
        // the new master_enabled value right away — not up to 30 s later.
        // drop(1) skips the initial DataStore replay so we don't double
        // up with the first periodic tick on boot.
        viewModelScope.launch {
            com.hermesandroid.relay.accessibility.HermesAccessibilityService
                .masterEnabledFlow(application)
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    bridgeStatusReporter.pushNow()
                }
        }
        // === END PHASE3-status ===

        // === v0.4.1 polish: auto-return to Hermes on run.completed ===
        // BridgeRunTracker wires Chat (SSE run.completed) to Bridge
        // (foreground-shifting dispatch) via a shared singleton. When
        // both signals converge in a run, fire a local /return_to_hermes
        // so the user is never stranded on another app after the agent
        // finishes — even if the LLM forgot to call the return tool
        // itself. See BridgeRunTracker KDoc for the full contract.
        com.hermesandroid.relay.bridge.BridgeRunTracker.registerAutoReturnCallback {
            viewModelScope.launch {
                runCatching {
                    val envelope = com.hermesandroid.relay.network.models.Envelope(
                        channel = "bridge",
                        type = "bridge.command",
                        payload = kotlinx.serialization.json.buildJsonObject {
                            put(
                                "request_id",
                                kotlinx.serialization.json.JsonPrimitive(
                                    java.util.UUID.randomUUID().toString(),
                                ),
                            )
                            put("method", kotlinx.serialization.json.JsonPrimitive("POST"))
                            put("path", kotlinx.serialization.json.JsonPrimitive("/return_to_hermes"))
                            put(
                                "body",
                                kotlinx.serialization.json.buildJsonObject { },
                            )
                            put(
                                "source",
                                kotlinx.serialization.json.JsonPrimitive("auto_return"),
                            )
                        },
                    )
                    bridgeCommandHandler.handleLocalCommand(envelope)
                }.onFailure {
                    android.util.Log.w(
                        "ConnectionViewModel",
                        "auto-return dispatch failed: ${it.message}",
                    )
                }
            }
        }
        // === END v0.4.1 polish ===

        // === v0.4.1 polish: push on unattended-access toggle flip ===
        // Same rationale as the master-toggle push above — the host-side
        // agent reading `/bridge/status` needs to see the new unattended
        // state within a second of the user flipping the toggle, not up
        // to 30 s later. `UnattendedAccessManager.enabled` is a StateFlow
        // so distinctUntilChanged is implicit (per the StateFlow
        // "Operator Fusion" rule) — drop(1) still needed to skip the
        // initial value replay so we don't double up with the first
        // periodic tick on boot.
        viewModelScope.launch {
            com.hermesandroid.relay.bridge.UnattendedAccessManager.enabled
                .drop(1)
                .collect {
                    bridgeStatusReporter.pushNow()
                }
        }
        // === END v0.4.1 polish ===
        // === END PHASE3-accessibility ===

        // === PHASE3-notif-listener-followup: notification companion multiplexer wiring ===
        // The bound NotificationListenerService instance buffers up to 50
        // envelopes in its own pendingEnvelopes queue while this slot is
        // null, so wiring it from here (rather than at service-bind time)
        // is safe — the buffer drains on the next onNotificationPosted
        // once the slot is set. Set unconditionally; the multiplexer's
        // own sendCallback gating handles the relay-disconnected case.
        com.hermesandroid.relay.notifications.HermesNotificationCompanion
            .multiplexer = multiplexer
        // === END PHASE3-notif-listener-followup ===

        // Resolve [relayUiState] from the three raw inputs (authState,
        // relayConnectionState, relayUrl) with a grace-window transition
        // to Stale. Lifted here from three separate ad-hoc helpers across
        // SettingsScreen / ConnectionSettingsScreen so every screen renders
        // the relay row consistently. `pendingStaleJob` is the single
        // in-flight grace timer; each new raw-state change cancels any
        // prior timer, so we never get two "promote to Stale" jobs racing.
        viewModelScope.launch {
            var pendingStaleJob: Job? = null
            combine(
                authState,
                relayConnectionState,
                _relayUrl,
            ) { auth, conn, url -> Triple(auth, conn, url) }
                .collect { (auth, conn, url) ->
                    pendingStaleJob?.cancel()
                    pendingStaleJob = null
                    _relayUiState.value = when {
                        url.isBlank() -> RelayUiState.NotConfigured
                        conn == ConnectionState.Connected -> RelayUiState.Connected
                        conn == ConnectionState.Connecting ||
                            conn == ConnectionState.Reconnecting ->
                            RelayUiState.Connecting
                        auth is AuthState.Paired &&
                            conn == ConnectionState.Disconnected -> {
                            // Start the grace-window timer. If the WSS
                            // doesn't come up within RELAY_RECONNECT_GRACE_MS,
                            // we promote to Stale so the UI stops lying
                            // ("Connecting…" forever) and surfaces a
                            // tap-to-retry affordance.
                            pendingStaleJob = launch {
                                delay(RELAY_RECONNECT_GRACE_MS)
                                _relayUiState.value = RelayUiState.Stale
                            }
                            RelayUiState.Connecting
                        }
                        else -> RelayUiState.Disconnected
                    }
                }
        }

        // Multi-connection: stamp the active Connection with pairing metadata
        // when AuthManager surfaces a fresh PairedSession. Closes the bug
        // where Connections list showed "Not paired" even though the
        // Settings card correctly said "Paired" — previously `markPaired`
        // existed on the store but was never called after the
        // Profile → Connection rename.
        //
        // Stamp only when `pairedAt` is still null so we don't churn
        // DataStore writes on every auth reload. Re-pair flows explicitly
        // clear `pairedAt` via the revoke path (see revokeConnection),
        // so the next auth.ok will stamp again cleanly.
        viewModelScope.launch {
            combine(
                connectionStore.activeConnectionId,
                currentPairedSession,
            ) { id, paired -> id to paired }
                .distinctUntilChanged()
                .collect { (connId, paired) ->
                    if (connId == null || paired == null) return@collect
                    val current = connectionStore.connections.value
                        .firstOrNull { it.id == connId } ?: return@collect
                    if (current.pairedAt != null) return@collect
                    connectionStore.markPaired(
                        connectionId = connId,
                        pairedAtMillis = System.currentTimeMillis(),
                        transportHint = paired.transportHint,
                        // PairedSession.expiresAt is epoch seconds; the
                        // store docs are explicit that it expects millis —
                        // passing seconds would render as "Paired decades ago".
                        expiresAtMillis = paired.expiresAt?.let { it * 1000L },
                    )
                }
        }

        // Multi-connection: on cold boot, if no connections are persisted
        // yet, seed connection 0 from whatever URLs/session id the
        // pre-multi-connection install left in DataStore. Idempotent — a
        // no-op once connection 0 (or any user-created connection) is
        // already in the list.
        //
        // Runs on its own coroutine so it doesn't block the DataStore
        // collect loop below. A race where the collect-loop writes a new
        // URL value mid-migration is benign — migrateLegacyConnectionIfNeeded
        // is guarded by ConnectionStore.writeMutex and early-returns once
        // any connection exists, so a second call observing the
        // freshly-seeded connection just exits.
        viewModelScope.launch {
            try {
                val prefs = application.relayDataStore.data.first()
                val legacyApiUrl = prefs[KEY_API_SERVER_URL] ?: _apiServerUrl.value
                val legacyRelayUrl = prefs[KEY_RELAY_URL]
                    ?: prefs[KEY_SERVER_URL]
                    ?: _relayUrl.value
                val legacySessionId = prefs[KEY_LAST_SESSION_ID]
                connectionStore.migrateLegacyConnectionIfNeeded(
                    legacyApiServerUrl = legacyApiUrl,
                    legacyRelayUrl = legacyRelayUrl,
                    legacyLastSessionId = legacySessionId,
                )
            } catch (e: Exception) {
                android.util.Log.w(
                    "ConnectionViewModel",
                    "migrateLegacyConnectionIfNeeded failed: ${e.message}",
                )
            }
        }

        // Load saved state — split into fast (UI-blocking) and slow (network) paths
        viewModelScope.launch {
            _onboardingCompleted.value = dataManager.isOnboardingCompleted()

            var prevApiUrl: String? = null
            var prevApiKey: String? = null

            application.relayDataStore.data.collect { preferences ->
                // Restore insecure mode
                val insecure = preferences[KEY_INSECURE_MODE] ?: false
                connectionManager.setInsecureMode(insecure)

                // Load API server URL
                val savedApiUrl = preferences[KEY_API_SERVER_URL]
                if (savedApiUrl != null) {
                    _apiServerUrl.value = savedApiUrl
                }

                // Load relay URL (with migration from old server_url key)
                val savedRelayUrl = preferences[KEY_RELAY_URL]
                    ?: preferences[KEY_SERVER_URL] // legacy migration
                if (savedRelayUrl != null) {
                    _relayUrl.value = savedRelayUrl
                }

                // Load last session ID
                val savedSessionId = preferences[KEY_LAST_SESSION_ID]
                if (savedSessionId != null) {
                    _lastSessionId.value = savedSessionId
                }

                // Check if this is a new version → show What's New
                val currentVersion = getAppVersionName()
                val lastSeen = preferences[KEY_LAST_SEEN_VERSION]
                if (lastSeen != null && lastSeen != currentVersion) {
                    _showWhatsNew.value = true
                }

                // Mark ready after first DataStore emission (UI can render)
                if (!_isReady.value) {
                    _isReady.value = true
                }

                // Rebuild API client in a separate coroutine so it doesn't block
                // the DataStore flow (getApiKey() awaits Tink crypto init on first call)
                val currentUrl = _apiServerUrl.value
                launch {
                    val currentKey = authManager.getApiKey() ?: ""
                    if (currentUrl != prevApiUrl || currentKey != prevApiKey) {
                        prevApiUrl = currentUrl
                        prevApiKey = currentKey
                        rebuildApiClient()
                    }
                }
            }
        }

        // Periodic API health check — only runs when an API client is configured.
        // 30s cadence matches the prior loop. Updates both the legacy boolean
        // and the new tri-state HealthStatus flow so existing callers don't break.
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                if (_apiClient.value != null) {
                    probeApiHealth()
                }
            }
        }

        // Periodic relay health check — same cadence. Only fires when a relay
        // URL is configured. Does NOT touch the WSS channel; this is a pure
        // /health probe via RelayHttpClient (3s timeout, no auth needed).
        // Plugs the historical gap where relay status was only verified by
        // WSS heartbeat or manual Save & Test taps.
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                if (_relayUrl.value.isNotBlank()) {
                    probeRelayHealth()
                }
            }
        }

        // React to network changes — ConnectivityObserver was previously
        // observed but never read. Now any "network back" transition kicks
        // a fresh revalidation so badges flip from Probing to a verified
        // state without waiting for the next periodic tick.
        //
        // drop(1) skips the StateFlow's seed value (Available) so we don't
        // double-probe on first composition; the init block already triggers
        // the initial probe via rebuildApiClient().
        viewModelScope.launch {
            networkStatus
                .drop(1)
                .distinctUntilChanged()
                .collect { status ->
                    if (status is ConnectivityObserver.Status.Available) {
                        revalidate()
                    }
                }
        }

        // Mirror the media cache cap into a plain volatile field so the
        // cache writer can read it synchronously from its enforceCap() loop.
        viewModelScope.launch {
            mediaSettingsRepo.settings.collect { settings ->
                _cachedMediaCapMb = settings.cachedMediaCapMb
            }
        }

        // Reset the profile pick on every connection switch. Profiles are
        // advertised per-server in `auth.ok`, so carrying a selection
        // across a switch would dangle — connection B might not have (or
        // might have a different) profile by that name, and the chat send
        // pipeline would quietly ask the new server for a profile it
        // doesn't recognize.
        viewModelScope.launch {
            connectionSwitchEvents.collect {
                _selectedProfile.value = null
            }
        }
    }

    // --- Revalidation ----------------------------------------------------
    //
    // Single entry point for "the world might have changed — re-check
    // everything." Called from RelayApp's ON_RESUME observer, from the
    // ConnectivityObserver collector above, and any future surface that
    // wants the badges to refresh on demand. Guarded by [revalidationJob]
    // so rapid-fire resumes don't pile up parallel probes.

    @Volatile
    private var revalidationJob: Job? = null

    /**
     * Re-probe API + relay health in parallel. Both flows immediately flip
     * to [HealthStatus.Probing] so the UI can render a "checking status"
     * pose without waiting for the network round-trip — that's the fix for
     * the resume-lag flash where badges showed stale Connected/Disconnected
     * for up to 30 seconds after foregrounding.
     *
     * Idempotent: if a revalidation is already in flight, we skip and let
     * the existing one finish. Cheap enough that callers don't need to
     * debounce themselves.
     */
    fun revalidate() {
        if (revalidationJob?.isActive == true) return
        revalidationJob = viewModelScope.launch {
            // Flip to Probing immediately so the UI doesn't flash whatever
            // stale value the previous session left in the flow.
            if (_apiClient.value != null) {
                _apiServerHealth.value = HealthStatus.Probing
            }
            if (_relayUrl.value.isNotBlank()) {
                _relayServerHealth.value = HealthStatus.Probing
            }

            // Fire both probes in parallel — neither blocks the other.
            val apiProbe = launch { probeApiHealth() }
            val relayProbe = launch { probeRelayHealth() }
            apiProbe.join()
            relayProbe.join()

            // Also kick a stale-WSS reconnect — if we hold a paired session
            // but the WSS is down (the most common post-resume state), bring
            // it back without forcing the user to tap into Settings.
            reconnectIfStale()
        }
    }

    /**
     * Run a single API /health probe and update both the legacy boolean
     * and the tri-state flow. Safe to call from anywhere; no-ops cleanly
     * when the client isn't configured.
     */
    private suspend fun probeApiHealth() {
        val client = _apiClient.value
        if (client == null) {
            _apiServerHealth.value = HealthStatus.Unknown
            _apiServerReachable.value = false
            return
        }
        val ok = client.checkHealth()
        _apiServerReachable.value = ok
        _apiServerHealth.value = if (ok) HealthStatus.Reachable else HealthStatus.Unreachable
    }

    /**
     * Run a single relay /health probe and update [relayServerHealth].
     * Uses the existing [RelayHttpClient.probeHealth] path (unauthenticated,
     * 3s timeout, no impact on the rate limiter). Distinct from
     * [testRelayReachable] which is the user-facing Save & Test action.
     */
    private suspend fun probeRelayHealth() {
        val url = _relayUrl.value
        if (url.isBlank()) {
            _relayServerHealth.value = HealthStatus.Unknown
            return
        }
        val result = relayHttpClient.probeHealth(url)
        _relayServerHealth.value = if (result.isSuccess) {
            HealthStatus.Reachable
        } else {
            HealthStatus.Unreachable
        }
    }

    // --- Unified pairing apply ----------------------------------------------
    //
    // Single entry point for "user just confirmed a scanned QR + chose a TTL".
    // Used by both [com.hermesandroid.relay.ui.components.ConnectionWizard]
    // (the new shared wizard) and any other surface that wants to apply a
    // pairing payload without duplicating the apply-API-URL/apply-relay-URL/
    // apply-grants/apply-TTL/wipe-pin/connect dance.
    //
    // The payload's relay block is optional — when null, only the API server
    // side is configured (matches the legacy "API-only" QR flow).
    fun applyPairingPayload(
        payload: com.hermesandroid.relay.ui.components.HermesPairingPayload,
        ttlSeconds: Long,
    ) {
        android.util.Log.i(
            "ConnectionVM",
            "applyPairingPayload: serverUrl=${payload.serverUrl} keyPresent=${payload.key.isNotBlank()} " +
                "relayBlock=${payload.relay != null} relayUrl=${payload.relay?.url} " +
                "code=${payload.relay?.code} ttlSeconds=$ttlSeconds"
        )
        // SYNCHRONOUS RESET — must happen before this function returns so any
        // wizard / verify watcher that observes authState immediately after
        // sees Unpaired, not a stale Paired(token) from a prior install.
        // applyServerIssuedCodeAndReset writes _authState.value synchronously,
        // setPendingGrants/setPendingTtlSeconds are also sync. Putting these
        // inside the coroutine below let the wizard race ahead and trip
        // onComplete() against the stale Paired before the new pair started.
        payload.relay?.let { relay ->
            authManager.applyServerIssuedCodeAndReset(
                code = relay.code,
                relayUrl = relay.url,
            )
            authManager.setPendingGrants(relay.grants)
        }
        authManager.setPendingTtlSeconds(ttlSeconds)

        viewModelScope.launch {
            // API side — always present in any QR.
            updateApiServerUrl(payload.serverUrl)
            if (payload.key.isNotBlank()) {
                updateApiKey(payload.key)
            }

            // Relay side — only when the QR carried a relay block.
            payload.relay?.let { relay ->
                updateRelayUrl(relay.url)
                if (relay.url.startsWith("ws://")) {
                    setInsecureMode(true)
                }
            }

            // Kick the WSS handshake now if we have a relay. AuthManager is
            // holding a fresh server-issued code so the pair-context gate on
            // connectRelay will let it through.
            payload.relay?.let { relay ->
                android.util.Log.i(
                    "ConnectionVM",
                    "applyPairingPayload: disconnecting old relay + connecting to ${relay.url}"
                )
                disconnectRelay()
                connectRelay(relay.url)
            } ?: android.util.Log.w(
                "ConnectionVM",
                "applyPairingPayload: NO relay block in QR — relay/session will NOT pair"
            )

            // Fresh probe so the badges update without waiting for the next
            // periodic tick.
            revalidate()
        }
    }

    // --- API Server methods ---

    fun updateApiServerUrl(url: String) {
        _apiServerUrl.value = url
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_API_SERVER_URL] = url
            }
            rebuildApiClient()
        }
    }

    fun updateApiKey(key: String) {
        viewModelScope.launch {
            authManager.setApiKey(key)
            rebuildApiClient()
        }
    }

    fun checkApiHealth() {
        viewModelScope.launch {
            val client = _apiClient.value
            _apiServerReachable.value = client?.checkHealth() == true
        }
    }

    fun testApiConnection(onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val client = _apiClient.value
            val reachable = client?.checkHealth() == true
            _apiServerReachable.value = reachable
            onResult(reachable)
        }
    }

    private suspend fun rebuildApiClient() {
        val url = _apiServerUrl.value
        val key = authManager.getApiKey() ?: ""

        val oldClient = _apiClient.value

        if (url.isNotBlank()) {
            // Show Probing while the new client spins up so the badge has
            // a coherent in-flight pose instead of flashing the previous
            // result through.
            _apiServerHealth.value = HealthStatus.Probing
            val client = HermesApiClient(baseUrl = url, apiKey = key)
            _apiClient.value = client
            shutdownClientOffMain(oldClient)
            val ok = client.checkHealth()
            _apiServerReachable.value = ok
            _apiServerHealth.value = if (ok) HealthStatus.Reachable else HealthStatus.Unreachable

            // Probe per-endpoint capabilities. The result drives both the
            // legacy chatMode flow and the auto-resolver in ChatViewModel.
            val caps = client.probeCapabilities()
            _serverCapabilities.value = caps
            _chatMode.value = caps.toChatMode()
        } else {
            _apiClient.value = null
            shutdownClientOffMain(oldClient)
            _apiServerReachable.value = false
            _apiServerHealth.value = HealthStatus.Unknown
            _chatMode.value = ChatMode.DISCONNECTED
            _serverCapabilities.value = ServerCapabilities.DISCONNECTED
        }
    }

    /**
     * [HermesApiClient.shutdown] eventually drives OkHttp's
     * [okhttp3.ConnectionPool.evictAll], which closes live SSL sockets
     * synchronously — i.e. it performs network writes. Running that on
     * the main thread trips StrictMode's `NetworkOnMainThreadException`
     * on any keep-alive connection (observed on connection switch +
     * updateApiServerUrl). Always hand off to IO.
     */
    private suspend fun shutdownClientOffMain(client: HermesApiClient?) {
        if (client == null) return
        runCatching { withContext(Dispatchers.IO) { client.shutdown() } }
            .onFailure {
                android.util.Log.w(
                    "ConnectionVM",
                    "HermesApiClient.shutdown failed: ${it.message}",
                )
            }
    }

    // --- Relay methods ---

    fun connectRelay(url: String) {
        _relayUrl.value = url
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_RELAY_URL] = url
            }
        }
        connectRelayInternal(url)
    }

    fun connectRelay() {
        connectRelayInternal(_relayUrl.value)
    }

    /**
     * Pair-context-gated connect. WSS connect attempts without a pair
     * context (no session token, no pending server-issued code, not
     * mid-pair) are silently skipped — the auth envelope would just fail
     * and tick the relay's rate limiter toward its 5-min IP block, which
     * is a trap users can fall into by fumbling with Manual Configuration.
     *
     * Legitimate pair-context sources (any one is enough):
     *   * [AuthManager.authState] is [AuthState.Paired] → session token
     *   * [AuthManager.authState] is [AuthState.Pairing] → mid-handshake
     *   * [AuthManager.hasPairContext] → fresh server-issued code is
     *     stashed from a QR scan and about to ride the next auth envelope
     *
     * For the **reachability check** case (user wants to verify a manually-
     * typed URL before pairing), call [testRelayReachable] instead — it
     * probes `GET /health` without touching the WSS channel.
     */
    private fun connectRelayInternal(url: String) {
        if (!authManager.hasPairContext) {
            android.util.Log.i(
                "ConnectionVM",
                "connectRelay: no pair context — skipping WSS connect to avoid auth-failure rate-limit (use testRelayReachable for reachability checks)"
            )
            return
        }
        connectionManager.connect(url)
    }

    fun disconnectRelay() {
        connectionManager.disconnect()
    }

    /**
     * If we have a paired session token but the WS isn't currently open,
     * kick the relay connection back up. Called on Settings screen entry
     * and from the "Reconnect" button / tap-to-reconnect action on the
     * Relay status row.
     *
     * No-op when not paired, already connected, connecting, or reconnecting —
     * avoids duplicate connect calls that would interrupt an in-flight auth.
     */
    fun reconnectIfStale() {
        val paired = authState.value is AuthState.Paired
        val disconnected = relayConnectionState.value == ConnectionState.Disconnected
        val hasUrl = _relayUrl.value.isNotBlank()
        if (paired && disconnected && hasUrl) {
            connectionManager.connect(_relayUrl.value)
        }
    }

    fun updateRelayUrl(url: String) {
        _relayUrl.value = url
        // The new URL hasn't been verified yet — flip to Probing so the
        // health badge doesn't show stale Reachable/Unreachable from the
        // old URL while the next periodic tick (or revalidate()) lands.
        _relayServerHealth.value = HealthStatus.Probing
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_RELAY_URL] = url
            }
            // Kick a fresh probe right now rather than waiting up to 30s
            // for the periodic loop.
            probeRelayHealth()
        }
    }

    /**
     * Result of the **Save & Test** button's reachability probe. One-shot
     * state: null = idle, [RelayReachable.Probing] = in-flight,
     * [RelayReachable.Ok] = live hermes-relay responded, [RelayReachable.Fail]
     * = doesn't look right. UI reads this to render a chip / icon next to
     * the button.
     */
    sealed interface RelayReachable {
        data object Probing : RelayReachable
        data class Ok(val version: String, val clients: Int, val sessions: Int) : RelayReachable
        data class Fail(val message: String) : RelayReachable
    }

    private val _relayReachableResult = MutableStateFlow<RelayReachable?>(null)
    val relayReachableResult: StateFlow<RelayReachable?> = _relayReachableResult.asStateFlow()

    /**
     * Test if [url] points at a live hermes-relay via an unauthenticated
     * `GET /health` probe.
     *
     * Also saves [url] to the persisted relay URL — this is the "Save" half
     * of the Settings → Manual configuration → **Save & Test** button. The
     * save happens before the probe so a subsequent failure still leaves
     * the URL in place for the user to edit.
     *
     * Does NOT touch the WSS channel. Does NOT require a pair context. Does
     * NOT count against the relay's rate limiter (no auth envelope is sent).
     *
     * On return [relayReachableResult] is [RelayReachable.Ok] or
     * [RelayReachable.Fail].
     */
    fun testRelayReachable(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            _relayReachableResult.value = RelayReachable.Fail("Enter a relay URL first")
            return
        }
        // Persist the typed URL immediately — that's the "Save" half.
        _relayUrl.value = trimmed
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_RELAY_URL] = trimmed
            }
        }

        _relayReachableResult.value = RelayReachable.Probing
        viewModelScope.launch {
            val result = relayHttpClient.probeHealth(trimmed)
            _relayReachableResult.value = result.fold(
                onSuccess = { health ->
                    RelayReachable.Ok(
                        version = health.version,
                        clients = health.clients,
                        sessions = health.sessions,
                    )
                },
                onFailure = { err ->
                    RelayReachable.Fail(err.message ?: "Unknown error")
                }
            )
        }
    }

    /**
     * Clear the [relayReachableResult] state (e.g. after the user edits the
     * URL field — the previous probe result is no longer relevant).
     */
    fun clearRelayReachableResult() {
        _relayReachableResult.value = null
    }

    // Backward compat wrappers
    @Deprecated("Use connectRelay", replaceWith = ReplaceWith("connectRelay(url)"))
    fun connect(url: String) = connectRelay(url)

    @Deprecated("Use connectRelay", replaceWith = ReplaceWith("connectRelay()"))
    fun connect() = connectRelay()

    @Deprecated("Use disconnectRelay", replaceWith = ReplaceWith("disconnectRelay()"))
    fun disconnect() = disconnectRelay()

    @Deprecated("Use updateRelayUrl", replaceWith = ReplaceWith("updateRelayUrl(url)"))
    fun updateServerUrl(url: String) = updateRelayUrl(url)

    // --- What's New + Version tracking ---

    fun dismissWhatsNew() {
        _showWhatsNew.value = false
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_LAST_SEEN_VERSION] = getAppVersionName()
            }
        }
    }

    fun markVersionSeen() {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_LAST_SEEN_VERSION] = getAppVersionName()
            }
        }
    }

    private fun getAppVersionName(): String {
        return try {
            val app = getApplication<Application>()
            app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    // --- Session persistence ---

    fun saveLastSessionId(sessionId: String?) {
        _lastSessionId.value = sessionId
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                if (sessionId != null) {
                    preferences[KEY_LAST_SESSION_ID] = sessionId
                } else {
                    preferences.remove(KEY_LAST_SESSION_ID)
                }
            }
        }
    }

    // --- Shared methods ---

    fun setTheme(theme: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_THEME] = theme
            }
        }
    }

    fun setFontScale(scale: Float) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_FONT_SCALE] = scale
            }
        }
    }

    fun setInsecureMode(enabled: Boolean) {
        connectionManager.setInsecureMode(enabled)
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_INSECURE_MODE] = enabled
            }
        }
    }

    fun completeOnboarding() {
        _onboardingCompleted.value = true
        viewModelScope.launch {
            dataManager.setOnboardingCompleted(true)
        }
    }

    fun resetOnboarding() {
        viewModelScope.launch {
            dataManager.resetOnboarding()
            _onboardingCompleted.value = false
        }
    }

    fun resetAppData() {
        viewModelScope.launch {
            disconnectRelay()
            authManager.clearApiKey()
            dataManager.resetAppData()
            _apiServerUrl.value = DEFAULT_API_URL
            _relayUrl.value = DEFAULT_RELAY_URL
            shutdownClientOffMain(_apiClient.value)
            _apiClient.value = null
            _apiServerReachable.value = false
        }
    }

    fun exportSettings(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val json = dataManager.exportSettings(
                serverUrl = _relayUrl.value,
                theme = theme.value,
                onboardingCompleted = _onboardingCompleted.value,
                // Pass 2: AuthManager.sessionLabels is gone — replaced by
                // `agentProfiles: StateFlow<List<Profile>>`. The DataManager
                // param is marked @Suppress("UNUSED_PARAMETER") and isn't
                // written to the backup anyway, so an empty list keeps the
                // signature stable until the param is removed in a later pass.
                sessionLabels = emptyList(),
                apiServerUrl = _apiServerUrl.value,
                relayUrl = _relayUrl.value
            )
            onResult(json)
        }
    }

    fun writeBackupToUri(uri: Uri, backup: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = dataManager.writeBackupToUri(uri, backup)
            onResult(success)
        }
    }

    fun importFromUri(uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val jsonString = dataManager.readBackupFromUri(uri) ?: run {
                onResult(false)
                return@launch
            }
            val backup = dataManager.importSettings(jsonString) ?: run {
                onResult(false)
                return@launch
            }
            // Apply imported settings
            // Prefer v2 fields, fall back to v1 serverUrl for relay
            val importedRelayUrl = backup.relayUrl ?: backup.serverUrl
            importedRelayUrl?.let { updateRelayUrl(it) }
            backup.apiServerUrl?.let { updateApiServerUrl(it) }
            setTheme(backup.theme)
            if (backup.onboardingCompleted) {
                dataManager.setOnboardingCompleted(true)
                _onboardingCompleted.value = true
            }
            onResult(true)
        }
    }

    fun regeneratePairingCode() {
        authManager.regeneratePairingCode()
    }

    /**
     * Wipe the stored session token and tear down the relay connection.
     *
     * **Order matters**: we must stop the [ConnectionManager] reconnect loop
     * *before* wiping auth state, otherwise the auto-reconnect can fire a
     * WS handshake with whatever's left in the auth envelope (most commonly
     * the freshly-regenerated *local* pairing code, which isn't registered
     * on the relay) and trigger the rate limiter after 5 attempts — blocking
     * the device's IP for 5 minutes and making the next QR re-pair attempt
     * fail with 429.
     *
     * This is the fix for the 2026-04-11 "can't re-pair same device after
     * self-revoke" bug: user hits Revoke on their own entry in Paired
     * Devices → clearSession was called but the reconnect loop kept firing
     * with stale credentials → IP blocked → fresh QR scan bounced off the
     * rate limiter until app restart.
     */
    fun clearSession() {
        disconnectRelay()
        authManager.clearSession()
    }

    // --- Paired devices management ----------------------------------------

    /**
     * Fetch the list of paired devices from the relay. Idempotent — safe to
     * call repeatedly (e.g. on screen entry and on pull-to-refresh). Errors
     * surface through [pairedDevicesError]; a partial failure (404 = endpoint
     * not implemented) yields an empty list rather than an error.
     */
    fun loadPairedDevices() {
        viewModelScope.launch {
            _pairedDevicesLoading.value = true
            _pairedDevicesError.value = null
            val result = relayHttpClient.listSessions()
            result.fold(
                onSuccess = { list -> _pairedDevices.value = list },
                onFailure = { e ->
                    _pairedDevicesError.value = e.message ?: "Unknown error"
                }
            )
            _pairedDevicesLoading.value = false
        }
    }

    /**
     * Revoke a paired device by its token prefix. Returns `true` on success
     * (and on 404, which we treat as "already gone"). Refreshes the list
     * automatically on success.
     */
    suspend fun revokeDevice(tokenPrefix: String): Boolean {
        val result = relayHttpClient.revokeSession(tokenPrefix)
        return if (result.isSuccess) {
            // Optimistic local removal so the UI updates immediately while
            // the refresh round-trips.
            _pairedDevices.value = _pairedDevices.value.filterNot { it.tokenPrefix == tokenPrefix }
            loadPairedDevices()
            true
        } else {
            _pairedDevicesError.value = result.exceptionOrNull()?.message
            false
        }
    }

    /**
     * Extend (or update) a paired device's session TTL.
     *
     * Backs the "Extend" button on the Paired Devices card. Passes through
     * to [RelayHttpClient.extendSession] and refreshes the list on success
     * so the UI shows the new expiry immediately. Errors surface via
     * [pairedDevicesError] the same way revoke errors do.
     *
     * Grants are intentionally NOT exposed on this path — the MVP UX is
     * "pick a new session duration", and server-side re-clamping ensures
     * existing grants stay inside the (possibly new) session lifetime.
     * Callers that want to edit grants can call [RelayHttpClient.extendSession]
     * directly.
     *
     * @param ttlSeconds new session lifetime in seconds. `0` means "never
     *   expire". Must be non-negative.
     * @return `true` on success, `false` otherwise (error message in
     *   [pairedDevicesError]).
     */
    suspend fun extendDevice(tokenPrefix: String, ttlSeconds: Long): Boolean {
        val result = relayHttpClient.extendSession(tokenPrefix, ttlSeconds = ttlSeconds)
        return if (result.isSuccess) {
            loadPairedDevices()
            true
        } else {
            _pairedDevicesError.value = result.exceptionOrNull()?.message
            false
        }
    }

    // === PER-CHANNEL-REVOKE: revoke a single channel grant on a device ===
    /**
     * Revoke a single per-channel grant on a paired device without touching
     * the rest of the session.
     *
     * The relay's `PATCH /sessions/{prefix}` accepts a `grants` map of
     * `channel → seconds-from-now`. The server-side `_materialize_grants`
     * helper rebuilds the entire grants table from whatever we send (plus
     * the relay's defaults for channels we omit), so we must reconstruct
     * the FULL current grants map and only swap the target channel.
     *
     * Quirks of the relay-side encoding we have to honor:
     *  * `0` means "never expire" (NOT "expired").
     *  * Omitted channels default to `_default_grants`, which would extend
     *    a previously-shorter grant. So we re-send every existing channel
     *    explicitly, converted from absolute-epoch back to seconds-from-now.
     *  * To express "revoked", we send `1` second — the round trip alone
     *    pushes us past the expiry, and `_materialize_grants` clamps the
     *    candidate to the session lifetime so a stale `1` is harmless.
     *
     * @param tokenPrefix the device's token prefix from `PairedDeviceInfo`.
     * @param channel the channel name to revoke (`"chat"`, `"voice"`,
     *   `"terminal"`, `"bridge"`, …).
     * @return `true` on success.
     */
    suspend fun revokeChannelGrant(
        tokenPrefix: String,
        channel: String,
    ): Boolean {
        // Look up the device locally so we can rebuild the full grants
        // map from its current state. If the device list is stale we
        // bail rather than guessing.
        val device = _pairedDevices.value
            .firstOrNull { it.tokenPrefix == tokenPrefix }
            ?: return run {
                _pairedDevicesError.value = "Device not in cache — refresh and retry"
                false
            }

        val nowSec = System.currentTimeMillis() / 1000.0
        val rebuilt: MutableMap<String, Long> = mutableMapOf()
        for ((existingChannel, expiryEpoch) in device.grants) {
            if (existingChannel == channel) {
                // Target channel — encode as expired (1s from now → past
                // by the time the relay processes the PATCH).
                rebuilt[existingChannel] = 1L
            } else if (expiryEpoch == null) {
                // Existing "never expire" grant — preserve via 0.
                rebuilt[existingChannel] = 0L
            } else {
                // Existing capped grant — convert absolute epoch to
                // seconds-from-now, clamped to >= 1 so the relay
                // accepts it as a valid non-negative integer.
                val secsFromNow = (expiryEpoch - nowSec).toLong().coerceAtLeast(1L)
                rebuilt[existingChannel] = secsFromNow
            }
        }
        if (rebuilt.isEmpty()) {
            _pairedDevicesError.value = "Device has no grants to revoke"
            return false
        }
        if (channel !in rebuilt) {
            // The channel wasn't in the device's grant table — nothing
            // to do. Treat as success so the UI updates cleanly.
            return true
        }

        val result = relayHttpClient.extendSession(
            tokenPrefix = tokenPrefix,
            ttlSeconds = null,
            grants = rebuilt,
        )
        return if (result.isSuccess) {
            loadPairedDevices()
            true
        } else {
            _pairedDevicesError.value = result.exceptionOrNull()?.message
            false
        }
    }
    // === END PER-CHANNEL-REVOKE ===

    // --- Insecure-ack helpers ---------------------------------------------

    /**
     * DataStore-backed flow of whether the user has acknowledged the
     * [com.hermesandroid.relay.ui.components.InsecureConnectionAckDialog].
     */
    val insecureAckSeen: StateFlow<Boolean> =
        PairingPreferences.insecureAckSeen(application)
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val insecureReason: StateFlow<String> =
        PairingPreferences.insecureReason(application)
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setInsecureAckComplete(reason: String) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            PairingPreferences.setInsecureAckSeen(ctx, true)
            PairingPreferences.setInsecureReason(ctx, reason)
        }
    }

    override fun onCleared() {
        super.onCleared()
        connectionManager.shutdown()
        // ViewModel.onCleared runs on the main thread and viewModelScope
        // is already being cancelled — fire-and-forget the client
        // shutdown on a plain background Thread so
        // ConnectionPool.evictAll doesn't trip
        // NetworkOnMainThreadException on live SSL sockets.
        _apiClient.value?.let { client ->
            Thread({ runCatching { client.shutdown() } }, "HermesApiClient-shutdown").start()
        }
        tailscaleDetector.shutdown()
        // Release the cached VirtualDisplay + ImageReader + HandlerThread
        // built by ScreenCapture on the first /screenshot call. Without
        // this, a process-rare VM teardown would leak the capture pipeline
        // until the OS cleans up on exit.
        runCatching { screenCapture.releaseCache() }
    }
}
