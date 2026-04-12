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
// === PHASE3-γ: bridge channel wiring ===
import com.hermesandroid.relay.accessibility.BridgeStatusReporter
import com.hermesandroid.relay.accessibility.ScreenCapture
import com.hermesandroid.relay.network.handlers.BridgeCommandHandler
// === END PHASE3-γ ===
import com.hermesandroid.relay.util.MediaCacheWriter
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    // AuthManager owns the CertPinStore; ConnectionManager takes a snapshot
    // of the store's current pins on every connect so re-pair wipes land.
    // AuthManager must be constructed before ConnectionManager so the pin
    // store is available for the certificate pinner.
    val authManager = AuthManager(application, multiplexer, viewModelScope)
    // Pass hasPairContext as the reconnect gate — the auto-reconnect loop
    // inside ConnectionManager bypasses ConnectionViewModel.connectRelay's
    // primary gate, so we plumb the same AuthManager check directly into
    // the internal scheduler. Without this, clearSession leaves a live
    // reconnect loop that fires stale auth envelopes and rate-limits us.
    private val connectionManager = ConnectionManager(
        multiplexer,
        authManager.certPinStore,
        reconnectGate = { authManager.hasPairContext }
    )

    // Data management
    val dataManager = DataManager(application)

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
    val authState: StateFlow<AuthState> = authManager.authState
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

    // Pairing code from AuthManager
    val pairingCode: StateFlow<String> = authManager.pairingCode

    // Paired-session snapshot (expires_at, grants, transport hint) from AuthManager.
    // Exposed straight through so SettingsScreen + PairedDevicesScreen can
    // render expiry + grant chips without poking at prefs.
    val currentPairedSession: StateFlow<PairedSession?> = authManager.currentPairedSession

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

    // === PHASE3-γ: bridge channel wiring ===
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

    // === PHASE3-ζ: safety manager + overlay wiring ===
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
    // === END PHASE3-ζ ===

    private val bridgeCommandHandler = BridgeCommandHandler(
        multiplexer = multiplexer,
        scope = viewModelScope,
        screenCapture = screenCapture,
        // === PHASE3-ζ: safety enforcement ===
        safetyManager = bridgeSafetyManager,
        // === END PHASE3-ζ ===
    )

    val bridgeStatusReporter = BridgeStatusReporter(
        context = application,
        multiplexer = multiplexer,
        scope = viewModelScope,
    )
    // === END PHASE3-γ (plus ζ wiring above) ===

    init {
        // Wire multiplexer to connection manager (for relay/bridge/terminal)
        multiplexer.setSendCallback { envelope ->
            connectionManager.send(envelope)
        }

        // Auto-authenticate on relay connect
        multiplexer.setOnConnectedCallback {
            authManager.authenticate()
        }

        // === PHASE3-γ: bridge handler registration ===
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
        // === END PHASE3-γ ===

        // === PHASE3-ε-followup: notification companion multiplexer wiring ===
        // The bound NotificationListenerService instance buffers up to 50
        // envelopes in its own pendingEnvelopes queue while this slot is
        // null, so wiring it from here (rather than at service-bind time)
        // is safe — the buffer drains on the next onNotificationPosted
        // once the slot is set. Set unconditionally; the multiplexer's
        // own sendCallback gating handles the relay-disconnected case.
        com.hermesandroid.relay.notifications.HermesNotificationCompanion
            .multiplexer = multiplexer
        // === END PHASE3-ε-followup ===

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
                // Wipe any TOFU pin for the new host + apply the code so the
                // next WSS auth envelope rides the fresh server-issued code
                // instead of the locally-generated fallback.
                authManager.applyServerIssuedCodeAndReset(
                    code = relay.code,
                    relayUrl = relay.url,
                )
                authManager.setPendingGrants(relay.grants)
            }

            // Stash TTL for the next authenticate() call. Done unconditionally
            // so even relay-less QRs persist the user's chosen TTL for the
            // next pair attempt.
            authManager.setPendingTtlSeconds(ttlSeconds)

            // Kick the WSS handshake now if we have a relay. AuthManager is
            // holding a fresh server-issued code so the pair-context gate on
            // connectRelay will let it through.
            payload.relay?.let { relay ->
                disconnectRelay()
                connectRelay(relay.url)
            }

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
            oldClient?.shutdown()
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
            oldClient?.shutdown()
            _apiServerReachable.value = false
            _apiServerHealth.value = HealthStatus.Unknown
            _chatMode.value = ChatMode.DISCONNECTED
            _serverCapabilities.value = ServerCapabilities.DISCONNECTED
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
            _apiClient.value?.shutdown()
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
                profiles = authManager.profiles.value,
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
        _apiClient.value?.shutdown()
        tailscaleDetector.shutdown()
    }
}
