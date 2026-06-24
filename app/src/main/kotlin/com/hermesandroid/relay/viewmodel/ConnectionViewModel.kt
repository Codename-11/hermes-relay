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
import com.hermesandroid.relay.ui.theme.AppThemes
import com.hermesandroid.relay.ui.components.avatar.PetImporter
import com.hermesandroid.relay.ui.components.avatar.PetImportResult
import com.hermesandroid.relay.ui.components.avatar.PetLoader
import com.hermesandroid.relay.ui.components.avatar.SphereAvatar
import com.hermesandroid.relay.auth.PairedDeviceInfo
import com.hermesandroid.relay.auth.PairedSession
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.DataManager
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.displayLabel
import com.hermesandroid.relay.data.MediaSettingsRepository
import com.hermesandroid.relay.data.PairingPreferences
import com.hermesandroid.relay.data.RelayEndpoint
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.ConnectionStore
import com.hermesandroid.relay.data.ConnectionValidation
import com.hermesandroid.relay.data.BuildFlavor
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.SessionTransport
import com.hermesandroid.relay.data.relayDataStore
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.util.TailscaleDetector
import com.hermesandroid.relay.network.relay.ChannelMultiplexer
import com.hermesandroid.relay.network.shared.ConnectivityObserver
import com.hermesandroid.relay.network.upstream.ChatMode
import com.hermesandroid.relay.network.relay.ConnectionManager
import com.hermesandroid.relay.network.relay.ConnectionState
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.DashboardChatDisplaySettings
import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.network.upstream.models.SessionItem
import com.hermesandroid.relay.network.upstream.DashboardAuthSession
import com.hermesandroid.relay.network.upstream.DashboardCookieStore
import com.hermesandroid.relay.network.upstream.DashboardStatus
import com.hermesandroid.relay.network.shared.EndpointResolver
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.data.KEY_GATEWAY_KEEP_ALIVE
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.GatewayKeepAliveService
import com.hermesandroid.relay.network.upstream.HermesApiClient
import com.hermesandroid.relay.network.shared.RouteProbeOutcome
import com.hermesandroid.relay.network.shared.ProfileApiUrlResolver
import com.hermesandroid.relay.network.upstream.ServerCapabilities
import com.hermesandroid.relay.network.relay.RelayHttpClient
import com.hermesandroid.relay.network.relay.RelayUrlDeriver
import com.hermesandroid.relay.network.relay.RelayVoiceClient
import com.hermesandroid.relay.network.relay.VoiceHandoffEvent
import com.hermesandroid.relay.network.upstream.ChatHandler
// === PHASE3-accessibility: bridge channel wiring ===
import com.hermesandroid.relay.accessibility.BridgeStatusReporter
import com.hermesandroid.relay.accessibility.ScreenCapture
import com.hermesandroid.relay.network.relay.BridgeCommandHandler
// === END PHASE3-accessibility ===
import com.hermesandroid.relay.util.MediaCacheWriter
import com.hermesandroid.relay.viewmodel.connection.PairingController
import com.hermesandroid.relay.viewmodel.connection.ProfileController
import com.hermesandroid.relay.viewmodel.connection.UpstreamTransportController
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private data class RelayUiInputs(
    val auth: AuthState,
    val conn: ConnectionState,
    val url: String,
    val configured: Boolean,
)

/**
 * Why the standard (no-plugin) voice route is or isn't usable right now.
 * Drives the mic gate, the Auto route ordering, and the Voice Settings
 * status line + CTA ("Sign in via Manage" / "Update hermes-agent").
 */
enum class StandardVoiceAvailability {
    /** No probe has completed yet (startup, connection switch). */
    Unknown,

    /** Dashboard reachable, authenticated (or auth not required), audio routes present. */
    Ready,

    /** Dashboard reachable and gated, but no signed-in session — Manage sign-in unlocks it. */
    SignInRequired,

    /** Dashboard URL configured but `/api/status` did not answer. */
    Unreachable,

    /** Dashboard answered but has no audio routes (`/api/audio`) — hermes-agent build too old. */
    Unsupported,
}

/**
 * Coarse connection state for the chat empty-state, derived in
 * [ConnectionViewModel.chatConnectState]. Lets the UI hold a neutral
 * "Connecting…" placeholder during cold-start hydration instead of flashing
 * the "Connect to Hermes" CTA before we know whether anything is configured.
 */
enum class ChatConnectState {
    /** Store not hydrated yet, or an active connection is still coming up. */
    Connecting,

    /** Chat client built and the API server is reachable. */
    Ready,

    /** Hydration complete and no connection is configured — show the CTA. */
    NeedsConnection,
}

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // API Server (direct chat)
        private val KEY_API_SERVER_URL = stringPreferencesKey("api_server_url")
        private const val DEFAULT_API_URL = "http://localhost:8642"

        // Relay Server (bridge/terminal)
        private val KEY_RELAY_URL = stringPreferencesKey("relay_url")
        private val KEY_SERVER_URL = stringPreferencesKey("server_url") // legacy migration
        private const val DEFAULT_RELAY_URL = "ws://localhost:8767"

        // How long the derived [relayUiState] shows `Connecting` before
        // promoting a Paired-but-Disconnected pose to `Stale`. Tuned to
        // cover a typical WSS handshake (~500-2000 ms) plus a cushion so
        // we don't flash "Stale" during a normal post-resume reconnect,
        // while still surfacing a tap-to-retry affordance when the
        // reconnect genuinely fails (server down, bad network).
        private const val RELAY_RECONNECT_GRACE_MS = 5_000L

        /**
         * Placeholder label written by [beginAddConnection] before the
         * user has scanned a QR. The pair-success watcher treats a
         * connection whose label is still this exact string as
         * "unlabeled" and auto-renames to the API-host-derived default
         * once pairing completes. Exposed as a constant so tests and
         * the watcher share one source of truth.
         */
        const val PLACEHOLDER_LABEL = "New connection…"

        // Shared
        private val KEY_THEME = stringPreferencesKey("theme")
        // Selected app theme id (palette/personality). Orthogonal to KEY_THEME,
        // which is the light/dark/auto mode axis honored by BOTH-mode themes.
        private val KEY_APP_THEME = stringPreferencesKey("app_theme")
        // Selected sphere skin id. "auto" (SphereRegistry.AUTO_ID) follows the
        // active theme's preferred skin; any other id pins a specific skin.
        private val KEY_SPHERE_SKIN = stringPreferencesKey("sphere_skin")
        // Selected agent avatar id. "sphere" (SphereAvatar.id) is the default
        // built-in; any other id selects a loaded user "pet" by id.
        private val KEY_AGENT_AVATAR = stringPreferencesKey("agent_avatar")
        private val KEY_PET_SPEED = floatPreferencesKey("pet_speed")
        private val KEY_PET_STABILIZE = booleanPreferencesKey("pet_stabilize")
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
        private val KEY_SHOW_SYSTEM_MESSAGES = booleanPreferencesKey("show_system_messages")
        private val KEY_MAX_ATTACHMENT_MB = intPreferencesKey("max_attachment_mb")
        private val KEY_MAX_MESSAGE_LENGTH = intPreferencesKey("max_message_length")

        // Animation
        private val KEY_ANIMATION_ENABLED = booleanPreferencesKey("animation_enabled")
        private val KEY_ANIMATION_BEHIND_CHAT = booleanPreferencesKey("animation_behind_chat")
        private val KEY_CHAT_RECENT_PROMPTS = booleanPreferencesKey("chat_recent_prompts")

        // Chat scroll behavior
        private val KEY_SMOOTH_AUTO_SCROLL = booleanPreferencesKey("smooth_auto_scroll")
        private val KEY_CLOSE_DRAWER_ON_SEND = booleanPreferencesKey("close_drawer_on_send")
        private val KEY_KEEP_COMPOSER_FOCUSED_ON_SEND =
            booleanPreferencesKey("keep_composer_focused_on_send")

        // Turn-complete notification ("Notify when Hermes finishes")
        private val KEY_NOTIFY_TURN_COMPLETE = booleanPreferencesKey("notify_turn_complete")

        // One-shot "Live voice conversation" hint on the input bar's voice slot
        private val KEY_VOICE_HINT_SEEN = booleanPreferencesKey("voice_mode_hint_seen")
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
        // Sentinel: replaced the moment the active connection hydrates
        // (restorePersistedActiveConnectionContext), so skip the eager keyset
        // decrypt it would otherwise do just to be thrown away.
        eagerHydrate = false,
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
    // OkHttp used by [endpointResolver] for HEAD /health probes. Keep it
    // distinct from the relay HTTP client (long read timeout for media
    // downloads) so probe timeouts stay tight and don't pick up a 2-minute
    // stream inheritance from the shared pool. See ADR 24.
    private val endpointProbeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    private val endpointResolver = EndpointResolver(httpClient = endpointProbeClient)

    private val connectionManager = ConnectionManager(
        multiplexer,
        authManager.certPinStore,
        reconnectGate = { authManager.hasPairContext },
        context = application,
        endpointResolver = endpointResolver,
        endpointCandidatesProvider = { activeRouteCandidatesSnapshot() },
        // Pull the active device id through AuthManager — it's the same id
        // PairingPreferences keys the endpoint list on. Nullable wrapper
        // because AuthManager.getOrCreateDeviceId() is suspending.
        deviceIdProvider = { runCatching { authManager.getOrCreateDeviceId() }.getOrNull() },
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
        relayUrlProvider = { effectiveRelayUrlSnapshot() },
        sessionTokenProvider = {
            // AuthManager holds the paired session token in EncryptedSharedPrefs.
            // Pull it out via the authState StateFlow snapshot — if we're not
            // paired yet, return null and the fetch fails with a clean error.
            (authManager.authState.value as? AuthState.Paired)?.token
        },
        pairedTokenSnapshot = {
            // Same synchronous paired-token read the fetch uses, so the media-
            // capability badge agrees with whether /media/by-path can actually
            // fetch (the token is wiped on relay restart until we re-pair).
            (authManager.authState.value as? AuthState.Paired)?.token
        },
    )

    // Pairing-management collaborator — owns the paired-devices list
    // (GET /sessions) and the insecure-ack DataStore flags. Extracted from
    // this ViewModel (ADR 34 decomposition); the public getters/functions
    // below delegate here unchanged.
    private val pairingController = PairingController(
        context = application,
        scope = viewModelScope,
        relayHttpClient = relayHttpClient,
    )

    // Upstream dashboard/gateway transport collaborator — owns the per-connection
    // dashboard cookie stores, the consolidated DashboardApiClient factory, the
    // cached GatewayChatClient + its availability tier, and the capability-driven
    // streaming-endpoint resolution. The providers read live ViewModel state
    // lazily so resolution follows the active LAN/Tailscale route.
    private val upstreamTransport = UpstreamTransportController(
        context = application,
        activeConnectionIdProvider = { connectionStore.activeConnectionId.value },
        dashboardUrlProvider = { activeDashboardUrl() },
        gatewayKeepAliveProvider = { gatewayKeepAlive.value },
        // Lets the dashboard cookie store ride the connection's token keyset
        // (one keyset build instead of two on cold start).
        tokenStoreKeyProvider = { cid ->
            connectionStore.connections.value.firstOrNull { it.id == cid }?.tokenStoreKey
        },
    )

    // Agent-profiles collaborator — owns the merged profile list, the
    // per-connection selected-profile state machine + its persistence stores,
    // the profile display alias, and the per-profile last-session restore.
    // Lifecycle hooks are driven by this ViewModel's init observers (below).
    private val profileController = ProfileController(
        context = application,
        scope = viewModelScope,
        authManagerFlow = _authManagerFlow,
        activeConnectionId = connectionStore.activeConnectionId,
        activeDashboardUrlProvider = { activeDashboardUrl() },
        dashboardClientFactory = { cid, url -> upstreamTransport.dashboardClientFor(cid, url) },
        streamingEndpointProvider = { streamingEndpoint.value },
        gatewayAvailabilityProvider = { upstreamTransport.gatewayAvailability.value },
        setLastSessionId = { _lastSessionId.value = it },
        legacyDefaultSessionId = {
            getApplication<Application>().relayDataStore.data.first()[KEY_LAST_SESSION_ID]
        },
        rebuildChatApiClient = { rebuildChatApiClient() },
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
    private val _connectionHandoffStatus = MutableStateFlow<ConnectionHandoffStatus?>(null)
    val connectionHandoffStatus: StateFlow<ConnectionHandoffStatus?> =
        _connectionHandoffStatus.asStateFlow()
    private var connectionHandoffClearJob: Job? = null
    private val _serverChatDisplaySettings =
        MutableStateFlow<DashboardChatDisplaySettings?>(null)

    /**
     * ADR 24 — [relayUiState] bundled with the currently-active endpoint
     * role so connection chips can render "Connected · Tailscale" etc.
     * New screens should prefer [relayRowState] over [relayUiState] when
     * they want to surface the transport role; existing `== RelayUiState.X`
     * comparisons keep working off [relayUiState].
     */
    val relayRowState: StateFlow<RelayRowState> = combine(
        _relayUiState,
        connectionManager.activeEndpoint,
    ) { phase, endpoint ->
        RelayRowState(phase = phase, activeEndpointRole = endpoint?.role)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        RelayRowState(phase = RelayUiState.NotConfigured, activeEndpointRole = null),
    )

    /** Currently-active endpoint, for the Endpoints card. */
    val activeEndpoint = connectionManager.activeEndpoint

    private fun activeRouteCandidatesSnapshot(): List<EndpointCandidate> {
        val activeId = connectionStore.activeConnectionId.value ?: return emptyList()
        return connectionStore.connections.value
            .firstOrNull { it.id == activeId }
            ?.routeCandidates
            .orEmpty()
    }

    private fun normalizeStandardRouteCandidates(
        candidates: List<EndpointCandidate>,
    ): List<EndpointCandidate> {
        return candidates.mapNotNull { candidate ->
            val relayUrl = candidate.relay.url.takeIf { it.isNotBlank() }
                ?: Connection.deriveDefaultRelayUrl(candidate.api.url)
                ?: return@mapNotNull null
            val transportHint = when {
                relayUrl.startsWith("wss://", ignoreCase = true) -> "wss"
                relayUrl.startsWith("ws://", ignoreCase = true) -> "ws"
                else -> candidate.relay.transportHint
            }
            candidate.copy(
                relay = RelayEndpoint(
                    url = relayUrl,
                    transportHint = transportHint,
                ),
            )
        }
    }

    /**
     * Rebuild the primary route candidate from freshly-saved URLs while
     * preserving the active connection's extra routes (priority > 0 — e.g.
     * the setup wizard's Tailscale URL, or extra endpoints from a pairing
     * payload). Editing the API or Relay URL used to call
     * [Connection.buildRouteCandidates] with no extras, silently collapsing
     * the stored list to a single candidate and killing roaming.
     */
    private fun mergedRouteCandidates(
        apiServerUrl: String,
        relayUrl: String,
        extraApiUrls: List<Pair<String, String>> = emptyList(),
    ): List<EndpointCandidate> = Connection.mergeRouteCandidates(
        rebuilt = Connection.buildRouteCandidates(
            apiServerUrl = apiServerUrl,
            relayUrl = relayUrl,
            extraApiUrls = extraApiUrls,
        ),
        existing = activeConnection.value?.routeCandidates.orEmpty(),
    )

    private fun effectiveApiServerUrlSnapshot(): String =
        connectionManager.activeEndpoint.value?.api?.url ?: _apiServerUrl.value

    private fun effectiveRelayUrlSnapshot(): String =
        connectionManager.activeEndpoint.value?.relay?.url ?: autoRelayUrlSnapshot()

    private fun autoRelayUrlSnapshot(): String {
        val savedRelay = _relayUrl.value
        val savedApi = _apiServerUrl.value
        return if (RelayUrlDeriver.isAutoManagedRelayUrl(savedRelay, savedApi)) {
            RelayUrlDeriver.deriveFromApiUrl(savedApi) ?: savedRelay
        } else {
            savedRelay
        }
    }

    private fun isRelayConfiguredFor(connection: Connection?, auth: AuthState): Boolean {
        if (auth is AuthState.Paired) return true
        if (connection?.pairedAt != null) return true
        val relayUrl = connection?.relayUrl?.trim().orEmpty()
        return relayUrl.isNotBlank() &&
            !RelayUrlDeriver.isAutoManagedRelayUrl(relayUrl, connection?.apiServerUrl.orEmpty())
    }

    private fun activeRelayConfiguredSnapshot(): Boolean =
        isRelayConfiguredFor(activeConnection.value, authState.value)

    /**
     * Signal stream for `profiles.updated` pushes — emits when the
     * server's in-memory profile list has changed in a way the user
     * should know about (a different set of names or a different
     * count). The UI layer collects this flow to show a brief
     * "Profiles updated" snackbar.
     *
     * Sourced from [AuthManager.profilesUpdatedEvents] so the filter
     * logic ("actually changed") is centralised there rather than
     * duplicated per-subscriber.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val profilesUpdatedEvents: kotlinx.coroutines.flow.SharedFlow<Unit> =
        _authManagerFlow
            .flatMapLatest { it.profilesUpdatedEvents }
            .shareIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                replay = 0,
            )

    @OptIn(ExperimentalCoroutinesApi::class)
    val authState: StateFlow<AuthState> = _authManagerFlow
        .flatMapLatest { it.authState }
        .stateIn(viewModelScope, SharingStarted.Eagerly, authManager.authState.value)

    @OptIn(ExperimentalCoroutinesApi::class)
    val apiKeyPresent: StateFlow<Boolean> = _authManagerFlow
        .flatMapLatest { it.apiKeyPresent }
        .stateIn(viewModelScope, SharingStarted.Eagerly, authManager.apiKeyPresent.value)

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

    private val _chatApiClient = MutableStateFlow<HermesApiClient?>(null)
    val chatApiClient: StateFlow<HermesApiClient?> = _chatApiClient.asStateFlow()
    private var profileChatApiClient: HermesApiClient? = null
    private var profileChatApiClientUrl: String? = null
    private var profileChatApiClientKey: String? = null

    // Chat mode + per-endpoint capability snapshot — owned by
    // [upstreamTransport]; getters delegate. `rebuildApiClient()` pushes the
    // freshly-probed snapshot via `setCapabilitiesAndMode`.
    val chatMode: StateFlow<ChatMode> get() = upstreamTransport.chatMode

    val serverCapabilities: StateFlow<ServerCapabilities> get() = upstreamTransport.serverCapabilities

    /**
     * Standard (no-plugin) voice rides the upstream **dashboard web server**
     * (`/api/audio/transcribe` + `/api/audio/speak`, the hermes-desktop voice
     * contract) — not the API server, whose current upstream advertises
     * `audio_api: false`. Availability therefore tracks dashboard state:
     * reachability via the public `/api/status`, auth via `/api/auth/me`
     * against the same per-connection cookie store Manage signs in with, and
     * route presence via a HEAD probe (405 = exists, 404 = build too old).
     */
    private val _standardVoiceAvailability =
        MutableStateFlow(StandardVoiceAvailability.Unknown)
    val standardVoiceAvailability: StateFlow<StandardVoiceAvailability> =
        _standardVoiceAvailability.asStateFlow()

    private val _standardAudioApiReachable = MutableStateFlow(false)
    val standardAudioApiReachable: StateFlow<Boolean> = _standardAudioApiReachable.asStateFlow()

    /**
     * Gateway chat transport (tui_gateway over the dashboard's `/api/ws`)
     * availability. Piggybacks on [probeStandardVoice] — same surface, same
     * `/api/status` + `/api/auth/me` checks — minus the audio-route HEAD:
     * `/api/ws` ships with every embedded-chat dashboard build, so route
     * absence is only discovered (and made sticky) at WS-upgrade time via
     * [markGatewayUnsupported].
     */
    // Gateway availability + cached gateway client + per-connection dashboard
    // cookie stores are owned by [upstreamTransport]; these getters/functions
    // preserve the public surface and the private-call sites unchanged.
    val gatewayAvailability: StateFlow<GatewayAvailability> get() = upstreamTransport.gatewayAvailability

    fun markGatewayUnsupported() = upstreamTransport.markGatewayUnsupported()

    private fun updateGatewayAvailability(probed: GatewayAvailability) =
        upstreamTransport.updateGatewayAvailability(probed)

    fun activeGatewayChatClient(): GatewayChatClient? = upstreamTransport.activeGatewayChatClient()

    /**
     * Dashboard URL for the active connection **on the currently-resolved
     * route** — snapshot twin of [effectiveDashboardUrl], which it delegates
     * to. Standard voice and the availability probe read this per call, so
     * an auto-managed dashboard URL follows LAN/Tailscale handoffs the same
     * way Manage does; an explicit dashboard override stays pinned. (This
     * used to read the persisted `resolvedDashboardUrl`, which kept voice
     * aimed at the LAN host after the resolver had moved chat to Tailscale.)
     */
    fun activeDashboardUrl(): String? =
        effectiveDashboardUrl.value.takeIf { it.isNotBlank() }

    /**
     * Cookie store for [connectionId] — ONE instance per connection,
     * process-wide. Every dashboard-surface consumer (Manage, standard
     * voice, connection validation, app-start pre-warm) must come through
     * here: each EncryptedDashboardCookieStore instance lazily builds its
     * own Keystore-backed prefs, and that build serializes through a
     * process-global Tink lock — N instances for the same file means N
     * multi-second lock holds instead of one.
     */
    fun dashboardCookieStoreFor(connectionId: String): DashboardCookieStore =
        upstreamTransport.dashboardCookieStoreFor(connectionId)

    /**
     * Cookie store for the active connection — the same encrypted store the
     * Manage tab's sign-in flow writes, so a dashboard session established
     * there authenticates voice (and any other dashboard-surface client).
     */
    fun activeDashboardCookieStore(): DashboardCookieStore? =
        upstreamTransport.activeDashboardCookieStore()

    // Chat is ready when a chat-routed API client exists and the base server is reachable.
    val chatReady: StateFlow<Boolean> = combine(_chatApiClient, _apiServerReachable) { client, reachable ->
        client != null && reachable
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Three-way gate for the chat empty-state, so a cold start doesn't flash
     * the loud "Connect to Hermes" CTA while DataStore is still hydrating.
     *
     * - [ChatConnectState.Ready] — chat client built + server reachable.
     * - [ChatConnectState.Connecting] — either the connection store hasn't
     *   hydrated yet (we don't yet know if anything is configured), OR an
     *   active connection exists but chat isn't reachable yet (cold connect /
     *   route resolve). Show a quiet spinner, never the connect button.
     * - [ChatConnectState.NeedsConnection] — hydration finished and there is
     *   genuinely no connection to use. The only state that shows the CTA.
     *
     * Seeds [ChatConnectState.Connecting] so the very first composed frame —
     * before any flow emits — is the neutral state, not the CTA.
     */
    val chatConnectState: StateFlow<ChatConnectState> = combine(
        connectionStore.isHydrated,
        activeConnection,
        chatReady,
    ) { hydrated, active, ready ->
        when {
            ready -> ChatConnectState.Ready
            !hydrated -> ChatConnectState.Connecting
            active != null -> ChatConnectState.Connecting
            else -> ChatConnectState.NeedsConnection
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatConnectState.Connecting)
    // NOTE: [relayReady] / [voiceReady] are declared below the [_relayUrl]
    // MutableStateFlow,
    // further down this file, because Kotlin class-body initializers run
    // top-to-bottom and a forward reference to _relayUrl here would read
    // a null backing field at construction time. Look for the `val
    // relayReady:` / `voiceReady:` declarations near [_relayUrl].

    // --- Relay URL ---
    private val _relayUrl = MutableStateFlow(DEFAULT_RELAY_URL)
    val relayUrl: StateFlow<String> = _relayUrl.asStateFlow()

    /**
     * Runtime route for chat/API traffic. The persisted API URL remains the
     * connection's base config; a resolver-selected endpoint temporarily wins
     * so paired devices can roam between LAN, Tailscale, and operator VPN
     * routes without rewriting stored settings.
     */
    val effectiveApiServerUrl: StateFlow<String> = combine(
        _apiServerUrl,
        connectionManager.activeEndpoint,
    ) { savedUrl, endpoint ->
        endpoint?.api?.url ?: savedUrl
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_API_URL)

    /**
     * Whether a chat turn is currently streaming — mirrored from
     * [com.hermesandroid.relay.viewmodel.ChatViewModel.isStreaming] by RelayApp.
     * While true, an [effectiveApiServerUrl] route change DEFERS its chat-client
     * rebuild: rebuilding mid-turn replaces the client and cancels the in-flight
     * turn, whereas the gateway socket rides a transient route blip via its own
     * reconnect (keeping the live session). The deferred rebuild applies once
     * the turn ends.
     */
    private val _chatStreaming = MutableStateFlow(false)

    fun setChatStreaming(streaming: Boolean) {
        _chatStreaming.value = streaming
    }

    /** A route change arrived mid-turn and its chat-client rebuild was deferred. */
    @Volatile
    private var pendingApiClientRebuild = false

    /**
     * Runtime route for relay HTTP calls and WSS-adjacent helpers. Relay
     * control still requires the paired relay session token; this only
     * chooses which reachable host carries those authenticated requests.
     */
    val effectiveRelayUrl: StateFlow<String> = combine(
        _relayUrl,
        _apiServerUrl,
        connectionManager.activeEndpoint,
    ) { savedRelayUrl, savedApiUrl, endpoint ->
        endpoint?.relay?.url ?: if (RelayUrlDeriver.isAutoManagedRelayUrl(savedRelayUrl, savedApiUrl)) {
            RelayUrlDeriver.deriveFromApiUrl(savedApiUrl) ?: savedRelayUrl
        } else {
            savedRelayUrl
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_RELAY_URL)

    /**
     * True only when Relay is an intentional part of the active connection:
     * a paired session exists, the connection has previously paired Relay,
     * or the saved Relay URL is a manual override. Auto-derived same-host
     * `:8767` URLs support setup hints, but they are not enough to nag
     * standard API/dashboard users about Relay health on app resume.
     */
    val relayConfigured: StateFlow<Boolean> = combine(
        activeConnection,
        authState,
    ) { connection, auth ->
        isRelayConfiguredFor(connection, auth)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Runtime dashboard route. Explicit dashboard overrides stay on the
     * persisted connection, but auto-managed dashboard URLs follow the active
     * API route so Manage can move from LAN to Tailscale with Chat.
     */
    val effectiveDashboardUrl: StateFlow<String> = combine(
        activeConnection,
        connectionManager.activeEndpoint,
    ) { connection, endpoint ->
        when {
            connection == null -> ""
            endpoint != null &&
                Connection.isAutoManagedDashboardUrl(connection.dashboardUrl, connection.apiServerUrl) ->
                endpoint.dashboard?.url
                    ?: Connection.deriveDefaultDashboardUrl(endpoint.api.url).orEmpty()
            else -> connection.resolvedDashboardUrl
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Relay is ready when we have a configured URL, the WSS socket is
     * Connected, AND the AuthManager is in a Paired state. All three
     * matter:
     *  - URL blank → nothing to connect to (post-teardown state after
     *    removing the last connection).
     *  - WSS not Connected → transport is down; bridge command send would
     *    throw immediately.
     *  - authState not Paired → the server would reject bridge calls at
     *    the bearer-token check, so even a live socket won't help.
     *
     * Consumers:
     *  - BridgeScreen — surfaces a "Relay not connected" nag banner so
     *    the user doesn't flip the master toggle on expecting commands
     *    to flow.
     * Symmetric in shape to [chatReady] above so call sites read
     * consistently; intentionally does NOT collapse with chatReady
     * because the two features have orthogonal network dependencies —
     * Chat runs over direct HTTP to the API server while Bridge rides the
     * relay WSS. Voice has its own [voiceReady] gate because it uses Relay
     * HTTP routes and can authenticate with a Hermes API key.
     */
    val relayReady: StateFlow<Boolean> = combine(
        connectionManager.connectionState,
        authState,
        effectiveRelayUrl,
        relayConfigured,
    ) { connState, auth, url, configured ->
        configured &&
            url.isNotBlank() &&
            connState == ConnectionState.Connected &&
            auth is AuthState.Paired
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Standard voice follows official Hermes Desktop: STT/TTS go through the
     * dashboard web server's `/api/audio` routes. Ready means the dashboard
     * answered `/api/status`, the cookie session satisfies its auth gate (or
     * none is required), and the audio routes exist on this build — the
     * `/api/status` discovery endpoint is designed for preflight, unlike the
     * old API-server HEAD probe this replaces.
     */
    val standardVoiceReady: StateFlow<Boolean> = standardVoiceAvailability
        .map { it == StandardVoiceAvailability.Ready }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Non-null (the active endpoint's display label, e.g. `"Tailscale"`)
     * when the resolver has moved the dashboard surface off the connection's
     * persisted URL. Dashboard session cookies are host-scoped, so a sign-in
     * performed on the LAN host does not authenticate the Tailscale host —
     * every dashboard-riding surface (Manage, standard voice) uses this to
     * say *which* route it's on and why sign-in state didn't follow.
     */
    val dashboardRouteMovedHint: StateFlow<String?> = combine(
        effectiveDashboardUrl,
        activeConnection,
        connectionManager.activeEndpoint,
    ) { dashboardUrl, connection, endpoint ->
        val persisted = connection?.resolvedDashboardUrl?.trim()?.trimEnd('/').orEmpty()
        val effective = dashboardUrl.trim().trimEnd('/')
        if (effective.isBlank() || persisted.isBlank() || effective.equals(persisted, ignoreCase = true)) {
            null
        } else {
            endpoint?.displayLabel() ?: "fallback"
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Route context for the standard-voice sign-in gate. Non-null only when
     * voice is gated on dashboard sign-in AND the route has moved (see
     * [dashboardRouteMovedHint]) — the UI uses this to explain that a
     * one-time sign-in *on this route* unlocks voice, instead of a bare
     * "sign-in required" that looks broken to someone who already signed in
     * at home.
     */
    val standardVoiceSignInRouteHint: StateFlow<String?> = combine(
        standardVoiceAvailability,
        dashboardRouteMovedHint,
    ) { availability, hint ->
        hint.takeIf { availability == StandardVoiceAvailability.SignInRequired }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Relay voice remains available for users who paired or explicitly
     * configured Relay, and for Relay-only realtime/provider paths.
     */
    val relayVoiceReady: StateFlow<Boolean> = combine(
        authState,
        apiKeyPresent,
        effectiveRelayUrl,
        relayConfigured,
    ) { auth, hasApiKey, url, configured ->
        configured && url.isNotBlank() && (auth is AuthState.Paired || hasApiKey)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val voiceReady: StateFlow<Boolean> = combine(
        standardVoiceReady,
        relayVoiceReady,
    ) { standardReady, relayReady ->
        standardReady || relayReady
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Backward compat: expose as serverUrl for any remaining references
    @Deprecated("Use relayUrl or apiServerUrl", replaceWith = ReplaceWith("relayUrl"))
    val serverUrl: StateFlow<String> = _relayUrl

    // Backward compat: expose relay state as connectionState
    @Deprecated("Use relayConnectionState", replaceWith = ReplaceWith("relayConnectionState"))
    val connectionState: StateFlow<ConnectionState> = relayConnectionState

    // Theme preference — light/dark/auto mode axis.
    val theme: StateFlow<String> = application.relayDataStore.data
        .map { preferences ->
            preferences[KEY_THEME] ?: "auto"
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    // Selected app theme id (palette identity). Defaults to the Hermes Relay
    // brand. Resolved against AppThemes.byId at the Compose theme root.
    val appTheme: StateFlow<String> = application.relayDataStore.data
        .map { preferences ->
            preferences[KEY_APP_THEME] ?: AppThemes.DEFAULT_ID
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppThemes.DEFAULT_ID)

    // Selected sphere skin id ("auto" follows the theme). Resolved against
    // SphereRegistry + loaded user skins at the Compose root.
    val sphereSkin: StateFlow<String> = application.relayDataStore.data
        .map { preferences ->
            preferences[KEY_SPHERE_SKIN] ?: "auto"
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    // Selected agent avatar id ("sphere" = the built-in orb; any other id is a
    // loaded user pet). Resolved against [SphereAvatar] + PetLoader.loadPets() at
    // the Compose root; an unknown id (pet removed) falls back to the sphere.
    val agentAvatar: StateFlow<String> = application.relayDataStore.data
        .map { preferences ->
            preferences[KEY_AGENT_AVATAR] ?: "sphere"
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "sphere")

    // Bumped to force the Compose root to re-scan the pets/ directory — after an
    // in-app import or delete, or when the Appearance screen opens (so a pack
    // added out-of-band, e.g. via adb, shows without an app restart). RelayApp
    // keys the avatar produceState on this.
    private val _avatarsRefreshTick = MutableStateFlow(0)
    val avatarsRefreshTick: StateFlow<Int> = _avatarsRefreshTick.asStateFlow()

    // One-shot, user-facing results of avatar add/remove for a snackbar.
    private val _avatarEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val avatarEvents: SharedFlow<String> = _avatarEvents.asSharedFlow()

    fun refreshAgentAvatars() {
        _avatarsRefreshTick.value = _avatarsRefreshTick.value + 1
    }

    // Global pet playback-speed multiplier (1.0 = authored fps). Tunable in
    // Appearance; applied to the active pet's clips via LocalPetPlaybackSpeed.
    // The sphere avatar ignores it.
    val petSpeed: StateFlow<Float> = application.relayDataStore.data
        .map { preferences -> preferences[KEY_PET_SPEED] ?: 1f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)

    // Re-center each pet frame on its own content (cancels AI-sheet drift).
    // Default on; consumed by PetAvatar via LocalPetStabilize.
    val petStabilize: StateFlow<Boolean> = application.relayDataStore.data
        .map { preferences -> preferences[KEY_PET_STABILIZE] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

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

    // --- Agent profiles (Pass 2) — owned by [profileController] -------------
    //
    // The merged profile list, the per-connection selected-profile state
    // machine + its persistence stores, the display alias, and the per-profile
    // last-session restore now live in ProfileController; these getters/
    // functions delegate. The state machine's lifecycle hooks are driven by
    // this ViewModel's init observers (connection switch / active-connection
    // change / agent-profile arrival / gateway-availability settle) further
    // down, calling profileController.* in their original order.

    val agentProfiles: StateFlow<List<Profile>> get() = profileController.agentProfiles

    fun refreshDashboardProfiles() = profileController.refreshDashboardProfiles()

    suspend fun listProfileScopedSessions(limit: Int = 200): Result<List<SessionItem>>? =
        profileController.listProfileScopedSessions(limit)

    suspend fun loadProfileScopedMessages(sessionId: String): Result<List<MessageItem>>? =
        profileController.loadProfileScopedMessages(sessionId)

    /**
     * Delete a session scoped to the ACTIVE PROFILE via the dashboard
     * `DELETE /api/sessions/{id}?profile=` surface — the write twin of
     * [listProfileScopedSessions]. A non-default profile's sessions live in that
     * profile's own `state.db`, so the unscoped api_server delete leaves the row
     * intact and the next profile-scoped list resurrects it. Resolves the active
     * connection + dashboard URL + profile name exactly as the lister does;
     * returns `false` when there's no dashboard surface so the caller can fall
     * back to the shared api_server delete.
     */
    suspend fun deleteProfileScopedSession(sessionId: String): Boolean {
        val connectionId = activeConnectionId.value ?: return false
        val dashboardUrl = activeDashboardUrl() ?: return false
        val profileName = AgentDisplay.profileRequestName(profileController.selectedProfile.value?.name)
        return upstreamTransport.dashboardClientFor(connectionId, dashboardUrl)
            .deleteSession(sessionId, profileName)
            .isSuccess
    }

    val selectedProfile: StateFlow<Profile?> get() = profileController.selectedProfile

    /**
     * True once the active connection's persisted profile selection has settled,
     * so cold-start profile-scoped reads (e.g. the session drawer + restored
     * session context) don't race the restore and load the server-default
     * profile. See [ProfileController.selectionSettled].
     */
    val profileSelectionSettled: StateFlow<Boolean> get() = profileController.selectionSettled

    val profileDisplayAlias: StateFlow<String?> get() = profileController.profileDisplayAlias

    fun setProfileDisplayAlias(alias: String?) = profileController.setProfileDisplayAlias(alias)

    /** The active profile's local agent-icon path (client-side, never sent to Hermes). */
    val profileIcon: StateFlow<String?> get() = profileController.profileIcon

    fun setProfileIcon(uri: Uri) = profileController.setProfileIcon(uri)

    fun clearProfileIcon() = profileController.clearProfileIcon()

    fun selectProfile(profile: Profile?) = profileController.selectProfile(profile)

    // --- Profile lock (per-connection pin to one profile) ------------------
    //
    // When set, the profile pickers/switchers across the app collapse to a
    // single locked state; only the dedicated Settings control still lists
    // every profile (to change the lock target or unlock). `lockedProfileName`
    // is the raw stored token (the SERVER_DEFAULT_PROFILE_KEY sentinel means
    // "locked to Server default"); `isProfileLocked` is the convenience boolean.

    val lockedProfileName: StateFlow<String?> get() = profileController.lockedProfileName

    val isProfileLocked: StateFlow<Boolean> get() = profileController.isProfileLocked

    /** Lock the active connection to [profile] (null = Server default). */
    fun lockProfile(profile: Profile?) {
        viewModelScope.launch { profileController.lockProfile(profile) }
    }

    /** Remove the active connection's profile lock. */
    fun unlockProfile() {
        viewModelScope.launch { profileController.unlockProfile() }
    }

    // --- Paired devices list (GET /sessions) -------------------------------
    //
    // Loaded on-demand from PairedDevicesScreen. Owned by [pairingController];
    // these getters preserve the public surface unchanged.

    val pairedDevices: StateFlow<List<PairedDeviceInfo>> get() = pairingController.pairedDevices
    val pairedDevicesLoading: StateFlow<Boolean> get() = pairingController.pairedDevicesLoading
    val pairedDevicesError: StateFlow<String?> get() = pairingController.pairedDevicesError

    // --- Tailscale detection (informational) -------------------------------
    //
    // Purely for UI labeling. Does NOT auto-change TTLs or flip insecure mode.
    // Exposed to SettingsScreen + SessionTtlPickerDialog.
    private val tailscaleDetector = TailscaleDetector(
        context = application,
        scope = viewModelScope,
        relayUrlProvider = { effectiveRelayUrlSnapshot() },
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

    private val connectionHealthStatus: StateFlow<ConnectionStatusSnapshot?> = combine(
        activeConnection,
        relayRowState,
        apiServerHealth,
        relayServerHealth,
        networkStatus,
    ) { activeConnection, relayRow, apiHealth, relayHealth, network ->
        buildGlobalConnectionStatus(
            handoff = null,
            activeConnection = activeConnection,
            relayRow = relayRow,
            apiHealth = apiHealth,
            relayHealth = relayHealth,
            network = network,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val globalConnectionStatus: StateFlow<ConnectionStatusSnapshot?> = combine(
        connectionHandoffStatus,
        connectionHealthStatus,
    ) { handoff, healthStatus ->
        handoff?.asConnectionStatusSnapshot() ?: healthStatus
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Splash readiness — true once initial DataStore load + onboarding check is done
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val localShowThinking = application.relayDataStore.data
        .map { it[KEY_SHOW_THINKING] ?: true }

    // Reasoning visibility follows the Hermes dashboard config when available.
    // The local DataStore value remains an offline/legacy fallback.
    val showThinking: StateFlow<Boolean> = combine(
        localShowThinking,
        _serverChatDisplaySettings,
    ) { local, server ->
        server?.showReasoning ?: local
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowThinking(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_SHOW_THINKING] = enabled
            }
        }
    }

    private val localToolDisplay = application.relayDataStore.data
        .map { normalizeToolDisplayMode(it[KEY_TOOL_DISPLAY]) ?: "detailed" }

    // Tool call display mode: "off", "compact", "detailed". Prefer the
    // server display.tool_progress config so Android matches desktop.
    val toolDisplay: StateFlow<String> = combine(
        localToolDisplay,
        _serverChatDisplaySettings,
    ) { local, server ->
        server?.toolDisplay ?: local
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "detailed")

    fun setToolDisplay(mode: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_TOOL_DISPLAY] = normalizeToolDisplayMode(mode) ?: "detailed"
            }
        }
    }

    private fun normalizeToolDisplayMode(mode: String?): String? =
        when (mode?.trim()?.lowercase()) {
            "off", "compact", "detailed" -> mode.trim().lowercase()
            else -> null
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

    // Streaming endpoint preference. Four values:
    //   "auto"     — pick based on per-endpoint capability detection (default
    //                for new installs as of v0.3.0). Resolves to "sessions"
    //                when the server has /api/sessions/{id}/chat/stream
    //                (native upstream or legacy fork), then "completions" when
    //                /v1/chat/completions is available.
    //   "sessions" — force /api/sessions/{id}/chat/stream
    //   "completions" — force /v1/chat/completions with stream=true
    //   "runs"     — force /v1/runs for servers known to stream that route
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

    // --- Opt-in "keep gateway connected in background" (sideload) ---

    /**
     * Off by default. When on, the gateway socket stays open in the background
     * (the process is held up by [GatewayKeepAliveService]) until the app is
     * killed, so replies stay instant instead of paying a cold rejoin.
     */
    val gatewayKeepAlive: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_GATEWAY_KEEP_ALIVE] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setGatewayKeepAlive(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_GATEWAY_KEEP_ALIVE] = enabled
            }
        }
    }

    init {
        // Drive the keep-alive: flip the active client's no-background-close
        // flag and start/stop the foreground service. Both flavors — the
        // GatewayKeepAliveService is declared in the main manifest (Play permits
        // this Home-Assistant-class persistent-connection use case). Mirrors
        // BridgeViewModel's masterToggle → BridgeForegroundService driver.
        viewModelScope.launch {
            gatewayKeepAlive.collect { enabled ->
                upstreamTransport.applyGatewayKeepAlive(enabled)
                val ctx = getApplication<Application>()
                if (enabled) runCatching { GatewayKeepAliveService.start(ctx) }
                else runCatching { GatewayKeepAliveService.stop(ctx) }
            }
        }
    }

    /**
     * Resolve the user's `streamingEndpoint` preference to a concrete value
     * based on the latest capability probe. Returns a concrete endpoint
     * (never "auto"). Used by ChatViewModel right before kicking off a stream.
     *
     * - "sessions" / "completions" / "runs" pass through unchanged (manual override wins).
     * - "auto" → reads `serverCapabilities.value.preferredChatEndpoint()`.
     */
    fun resolveStreamingEndpoint(preference: String): String =
        upstreamTransport.resolveStreamingEndpoint(preference)

    /**
     * Capability-resolved SSE endpoint, ignoring the gateway tier — wired to
     * [ChatViewModel.sseFallbackEndpoint] for per-turn gateway fallbacks.
     */
    fun resolveSseStreamingEndpoint(): String = upstreamTransport.resolveSseStreamingEndpoint()

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

    // Show server-injected role:system steering markers ("[System: …]" model /
    // personality-change notes) in the transcript. Default off (TUI/desktop
    // parity); on for debugging. Synced to ChatHandler.showSystemMarkers.
    val showSystemMessages: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_SHOW_SYSTEM_MESSAGES] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setShowSystemMessages(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_SHOW_SYSTEM_MESSAGES] = enabled
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

    /**
     * Show recent-prompt recall chips above the composer. OFF by default —
     * it's an opt-in convenience, not something to surface unprompted.
     */
    val chatRecentPromptsEnabled: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_CHAT_RECENT_PROMPTS] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setChatRecentPromptsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_CHAT_RECENT_PROMPTS] = enabled
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

    // Close the session drawer after a successful send. Default ON because
    // sending should return focus to the live conversation; users who use the
    // drawer as a pinned session navigator can keep it open.
    val closeDrawerOnSend: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_CLOSE_DRAWER_ON_SEND] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setCloseDrawerOnSend(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_CLOSE_DRAWER_ON_SEND] = enabled
            }
        }
    }

    // Keep the text composer focused after send. Default ON matches mobile
    // chat convention: quick follow-up messages should not require retapping
    // the input. Turning it off drops the keyboard after a successful send.
    val keepComposerFocusedOnSend: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_KEEP_COMPOSER_FOCUSED_ON_SEND] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setKeepComposerFocusedOnSend(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_KEEP_COMPOSER_FOCUSED_ON_SEND] = enabled
            }
        }
    }

    // Turn-complete notification (default ON). RelayApp mirrors this into
    // ChatViewModel.notifyOnTurnComplete; ChatSettingsScreen owns the toggle
    // + the POST_NOTIFICATIONS runtime request on first enable.
    val notifyTurnComplete: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_NOTIFY_TURN_COMPLETE] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setNotifyTurnComplete(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_NOTIFY_TURN_COMPLETE] = enabled
            }
        }
    }

    // One-shot voice hint on the input bar. Initial stateIn value is TRUE
    // (treated as already-seen) so returning users never get a flash of the
    // hint while DataStore hydrates; fresh installs flip to false once the
    // (absent) preference loads and the hint shows exactly once.
    val voiceHintSeen: StateFlow<Boolean> = application.relayDataStore.data
        .map { it[KEY_VOICE_HINT_SEEN] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setVoiceHintSeen(seen: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_VOICE_HINT_SEEN] = seen
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
    private val screenCapture = if (BuildFlavor.isSideload) ScreenCapture(
        context = application,
        httpClient = relayOkHttp,
        relayUrlProvider = { effectiveRelayUrlSnapshot() },
        sessionTokenProvider = {
            (authManager.authState.value as? AuthState.Paired)?.token
        },
        mediaProjectionProvider = {
            com.hermesandroid.relay.accessibility.MediaProjectionHolder.projection
        },
    ) else null

    // === PHASE3-safety-rails: safety manager + overlay wiring ===
    // Process-wide singletons — install() is idempotent and the overlay
    // host wires itself into ConfirmationOverlayHost.instance so the
    // safety manager can reach it without a hard ref.
    private val bridgeSafetyManager =
        if (BuildFlavor.isSideload) com.hermesandroid.relay.bridge.BridgeSafetyManager.install(
            context = application,
            scope = viewModelScope,
        ).also {
            com.hermesandroid.relay.bridge.BridgeStatusOverlay.install(application)
        } else null

    /** Exposed for BridgeScreen → safety summary card. */
    val bridgeSafety: com.hermesandroid.relay.bridge.BridgeSafetyManager? get() = bridgeSafetyManager
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
    val bridgeCommandHandler: BridgeCommandHandler = BridgeCommandHandler(
        multiplexer = multiplexer,
        scope = viewModelScope,
        screenCapture = screenCapture,
        relayHttpClient = relayHttpClient,
        mediaCacheWriter = mediaCacheWriter,
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
    private fun createAuthManagerForConnectionId(connectionId: String): AuthManager {
        val connection = connectionStore.connections.value.firstOrNull { it.id == connectionId }
        return AuthManager(
            context = getApplication<Application>(),
            multiplexer = multiplexer,
            scope = viewModelScope,
            connectionId = connectionId,
            tokenStoreKey = connection?.tokenStoreKey,
        )
    }

    private fun installAuthManager(am: AuthManager) {
        authManager = am
        // Push into the flow so the flatMapLatest chains on authState /
        // pairingCode / currentPairedSession repoint to the new manager.
        // Without this, existing Compose collectors stay bound to the
        // previous AuthManager's backing flows forever.
        _authManagerFlow.value = am
    }

    private suspend fun restorePersistedActiveConnectionContext(connection: Connection) {
        if (connectionStore.activeConnectionId.value != connection.id) return

        val restoredAuth = createAuthManagerForConnectionId(connection.id)
        installAuthManager(restoredAuth)

        val restoredRelayUrl = if (
            RelayUrlDeriver.isAutoManagedRelayUrl(connection.relayUrl, connection.apiServerUrl)
        ) {
            RelayUrlDeriver.deriveFromApiUrl(connection.apiServerUrl) ?: connection.relayUrl
        } else {
            connection.relayUrl
        }

        _apiServerUrl.value = connection.apiServerUrl
        _relayUrl.value = restoredRelayUrl
        connectionManager.setManualRoleOverride(connection.preferredRouteRole)
        getApplication<Application>().relayDataStore.edit { prefs ->
            prefs[KEY_API_SERVER_URL] = connection.apiServerUrl
            prefs[KEY_RELAY_URL] = restoredRelayUrl
        }
        connectionManager.refreshActiveEndpoint()
        rebuildApiClient()

        withTimeoutOrNull(ConnectionSwitchCoordinator.AUTH_HYDRATE_TIMEOUT_MS) {
            restoredAuth.authState.first { it is AuthState.Paired || it is AuthState.Failed }
        }

        if (
            connectionStore.activeConnectionId.value == connection.id &&
            restoredAuth.hasPairContext &&
            restoredRelayUrl.isNotBlank()
        ) {
            connectionManager.connect(restoredRelayUrl)
        }
    }

    private val connectionSwitchCoordinator = ConnectionSwitchCoordinator(
        connectionStore = connectionStore,
        connectionManager = connectionManager,
        scope = viewModelScope,
        authManagerFactory = { cid -> createAuthManagerForConnectionId(cid) },
        installAuthManager = { am -> installAuthManager(am) },
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

    fun recordVoiceHandoff(event: VoiceHandoffEvent) {
        val detail = when {
            !event.previousRoute.isNullOrBlank() && !event.nextRoute.isNullOrBlank() ->
                "${event.previousRoute} -> ${event.nextRoute}"
            !event.route.isNullOrBlank() && !event.detail.isNullOrBlank() ->
                "${event.route} / ${event.detail}"
            !event.route.isNullOrBlank() -> event.route
            else -> event.detail?.take(96)
        }
        recordConnectionHandoff(
            title = event.label,
            route = event.nextRoute ?: event.route,
            detail = detail,
            active = event.active,
            success = event.success,
        )
    }

    private fun recordConnectionHandoff(
        title: String,
        route: String?,
        detail: String?,
        active: Boolean,
        success: Boolean,
    ) {
        val cleanTitle = title.trim().takeIf { it.isNotBlank() } ?: "Connection changed"
        val cleanRoute = route?.trim()?.takeIf { it.isNotBlank() }
        val cleanDetail = detail?.trim()?.takeIf { it.isNotBlank() }?.take(120)
        val entry = ConnectionHandoffTraceEntry(
            label = cleanTitle,
            detail = cleanDetail,
        )
        val now = System.currentTimeMillis()
        connectionHandoffClearJob?.cancel()
        val previousEntries = _connectionHandoffStatus.value?.entries.orEmpty()
        val nextEntries = if (previousEntries.lastOrNull() == entry) {
            previousEntries
        } else {
            (previousEntries + entry).takeLast(4)
        }
        _connectionHandoffStatus.value = ConnectionHandoffStatus(
            title = cleanTitle,
            route = cleanRoute,
            active = active,
            success = success,
            entries = nextEntries,
            updatedAtMs = now,
        )
        connectionHandoffClearJob = viewModelScope.launch {
            delay(
                when {
                    // Live, in-progress handoff: keep the spinner up as a backstop
                    // until it resolves to success/error (which then clears fast).
                    active -> 30_000L
                    // Resolved states auto-dismiss within 5s — anything longer
                    // reads as a stuck overlay.
                    success -> 5_000L
                    else -> 5_000L
                }
            )
            if (_connectionHandoffStatus.value?.updatedAtMs == now) {
                _connectionHandoffStatus.value = null
            }
        }
    }

    private fun buildGlobalConnectionStatus(
        handoff: ConnectionHandoffStatus?,
        activeConnection: Connection?,
        relayRow: RelayRowState,
        apiHealth: HealthStatus,
        relayHealth: HealthStatus,
        network: ConnectivityObserver.Status,
    ): ConnectionStatusSnapshot? {
        handoff?.let { return it.asConnectionStatusSnapshot() }

        if (activeConnection == null || activeConnection.apiServerUrl.isBlank()) {
            return ConnectionStatusSnapshot(
                title = "No Hermes connection",
                actionLabel = "Connect",
                tone = ConnectionStatusTone.Warning,
                entries = listOf(
                    ConnectionHandoffTraceEntry(
                        label = "Setup",
                        detail = "Add a Vanilla Hermes API/dashboard connection",
                    ),
                ),
            )
        }

        if (network is ConnectivityObserver.Status.Lost ||
            network is ConnectivityObserver.Status.Unavailable
        ) {
            return ConnectionStatusSnapshot(
                title = "No internet connection",
                actionLabel = "Connections",
                tone = ConnectionStatusTone.Warning,
                entries = listOf(
                    ConnectionHandoffTraceEntry(
                        label = "Network",
                        detail = "Waiting for Android to report an internet route",
                    ),
                ),
            )
        }

        val route = displayEndpointRole(relayRow.activeEndpointRole)
        val probeEntries = buildGlobalConnectionProbeEntries(
            relayRow = relayRow,
            apiHealth = apiHealth,
            relayHealth = relayHealth,
            route = route,
        )

        return when {
            apiHealth == HealthStatus.Unreachable -> {
                // Diagnose, don't just report: for a single-route connection
                // the most likely cause is "phone left the server's network
                // and there's no remote route to roam to" — say so and point
                // at the fix. Multi-route connections already roam; tell the
                // user every route was tried instead.
                val routeCount = activeConnection.routeCandidates.size
                val routesEntry = if (routeCount <= 1) {
                    ConnectionHandoffTraceEntry(
                        label = "Routes",
                        detail = if (tailscaleDetector.isTailscaleDetected.value) {
                            "Phone is on Tailscale — add your server's Tailscale " +
                                "URL under Connections → Routes"
                        } else {
                            "Away from the server's network? Add a Tailscale or " +
                                "public route under Connections → Routes"
                        },
                    )
                } else {
                    ConnectionHandoffTraceEntry(
                        label = "Routes",
                        detail = "None of the $routeCount configured routes " +
                            "responded — fallbacks are retried automatically",
                    )
                }
                ConnectionStatusSnapshot(
                    title = "Hermes API unreachable",
                    route = route,
                    actionLabel = "Connections",
                    tone = ConnectionStatusTone.Warning,
                    entries = listOf(
                        ConnectionHandoffTraceEntry(
                            label = "API",
                            detail = "Chat and profile calls may not be available",
                        ),
                        routesEntry,
                    ),
                )
            }

            relayRow.phase == RelayUiState.Connecting &&
                apiHealth != HealthStatus.Reachable -> ConnectionStatusSnapshot(
                title = "Connecting to Hermes",
                route = route,
                actionLabel = "Connections",
                active = true,
                tone = ConnectionStatusTone.Info,
                entries = probeEntries,
            )

            apiHealth == HealthStatus.Probing ||
                (relayHealth == HealthStatus.Probing &&
                    apiHealth != HealthStatus.Reachable) ->
                ConnectionStatusSnapshot(
                    title = "Checking Hermes connection",
                    route = route,
                    actionLabel = "Connections",
                    active = true,
                    tone = ConnectionStatusTone.Info,
                    entries = probeEntries,
                )

            apiHealth != HealthStatus.Reachable &&
                (relayRow.phase == RelayUiState.Stale ||
                (relayHealth == HealthStatus.Unreachable &&
                    relayRow.phase != RelayUiState.Connected)) -> ConnectionStatusSnapshot(
                    title = "Relay unreachable",
                    route = route,
                    actionLabel = "Connections",
                    tone = ConnectionStatusTone.Warning,
                    entries = listOfNotNull(
                        route?.let {
                            ConnectionHandoffTraceEntry(
                                label = "Route",
                                detail = "Last route: $it",
                            )
                        },
                        ConnectionHandoffTraceEntry(
                            label = "Status",
                            detail = "Waiting for reconnect or a network change",
                        ),
                    ),
                )

            else -> null
        }
    }

    private fun buildGlobalConnectionProbeEntries(
        relayRow: RelayRowState,
        apiHealth: HealthStatus,
        relayHealth: HealthStatus,
        route: String?,
    ): List<ConnectionHandoffTraceEntry> = buildList {
        route?.let {
            add(
                ConnectionHandoffTraceEntry(
                    label = "Route",
                    detail = it,
                    state = ConnectionStepState.Done,
                )
            )
        }
        when (apiHealth) {
            HealthStatus.Probing -> add(
                ConnectionHandoffTraceEntry(
                    label = "API",
                    detail = "Checking Hermes health",
                    state = ConnectionStepState.Active,
                )
            )
            HealthStatus.Unreachable -> add(
                ConnectionHandoffTraceEntry(
                    label = "API",
                    detail = "Health check failed",
                    state = ConnectionStepState.Failed,
                )
            )
            HealthStatus.Reachable -> add(
                ConnectionHandoffTraceEntry(
                    label = "API",
                    detail = "Ready",
                    state = ConnectionStepState.Done,
                )
            )
            HealthStatus.Unknown -> Unit
        }
        when (relayHealth) {
            HealthStatus.Probing -> add(
                ConnectionHandoffTraceEntry(
                    label = "Relay",
                    detail = "Checking relay health",
                    state = ConnectionStepState.Active,
                )
            )
            HealthStatus.Unreachable -> add(
                ConnectionHandoffTraceEntry(
                    label = "Relay",
                    detail = "Health check failed",
                    state = ConnectionStepState.Failed,
                )
            )
            HealthStatus.Reachable -> add(
                ConnectionHandoffTraceEntry(
                    label = "Relay",
                    detail = "Ready",
                    state = ConnectionStepState.Done,
                )
            )
            HealthStatus.Unknown -> Unit
        }
        if (relayRow.phase == RelayUiState.Connecting) {
            add(
                ConnectionHandoffTraceEntry(
                    label = "Session",
                    detail = "Opening relay socket",
                    state = ConnectionStepState.Active,
                )
            )
        }
    }.takeLast(3)

    private fun displayEndpointRole(role: String?): String? {
        val cleaned = role?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when (cleaned.lowercase()) {
            "lan" -> "LAN"
            "tailscale" -> "Tailscale"
            "public" -> "Public"
            else -> cleaned
        }
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
     * Pre-create a placeholder [Connection] and switch to it BEFORE the
     * Pair wizard runs, so [applyPairingPayload]'s token write (which
     * targets the currently-active [authManager]) lands in the new
     * connection's EncryptedSharedPrefs instead of the outgoing one's.
     *
     * This replaces the earlier "pair then create" flow that left the
     * fresh connection in an unpaired state because the token had been
     * written to the wrong store. See [addConnectionFromPairing]'s KDoc
     * for the historical limitation; this method is the structural fix.
     *
     * The placeholder starts with empty URLs — [applyPairingPayload]
     * will overwrite them via [updateApiServerUrl] / [updateRelayUrl]
     * on the newly-active Connection once the QR is scanned. Label is
     * [PLACEHOLDER_LABEL] until the pair completes; on auth.ok the
     * pair-success watcher (same `viewModelScope.launch` block that
     * calls `markPaired`) detects the placeholder label and renames
     * to the API-host-derived default. Callers that abandon the
     * wizard must invoke [discardPlaceholderConnection] to remove
     * the empty record.
     */
    /**
     * Serializes concurrent `Add connection` flows. Without this, a fast
     * double-tap on the Connections FAB (two `connectionSwitchScope.launch`
     * blocks in [ui.RelayApp]) would create two placeholders and switch
     * to the second one, leaving the first as a blank orphan — which the
     * `init`-time orphan sweep would only pick up on the NEXT app launch.
     *
     * The mutex also enables the in-body "reuse existing placeholder"
     * short-circuit below — two racing callers share the same id instead
     * of each creating their own.
     */
    private val addConnectionMutex = Mutex()

    /**
     * @param preAllocatedId when non-null, use this id for the placeholder
     *   instead of generating a fresh UUID. Lets the caller navigate to
     *   [ui.screens.PairScreen] synchronously with a known id and run the
     *   DataStore-heavy placeholder creation + switch in the background —
     *   the Pair wizard's reactive state picks up the active connection
     *   by the time the user has framed a QR.
     *
     *   When a connection with this id already exists (re-entry from a
     *   double-tap racing the first call), this call becomes a no-op
     *   switch and returns the same id — safe to invoke twice for the
     *   same pre-allocated id.
     *
     *   When null, falls back to the original placeholder-reuse scan
     *   (for any legacy caller that still wants the old behavior).
     */
    suspend fun beginAddConnection(preAllocatedId: String? = null): String = addConnectionMutex.withLock {
        // Fast path for the pre-allocated-id caller (RelayApp's
        // onAddConnection). If a connection with this id already exists
        // in the store, we're the second call of a double-tap — just
        // ensure the switch landed and return. Otherwise create the
        // placeholder under the caller-supplied id so navigation and
        // persistence converge on the same handle.
        if (preAllocatedId != null) {
            val existing = connectionStore.connections.value.firstOrNull { it.id == preAllocatedId }
            if (existing != null) {
                android.util.Log.i(
                    "ConnectionViewModel",
                    "beginAddConnection: pre-allocated id=$preAllocatedId already exists, ensuring switch",
                )
                if (connectionStore.activeConnectionId.value != existing.id) {
                    switchConnection(existing.id).join()
                }
                return@withLock existing.id
            }

            val reusable = connectionStore.connections.value.firstOrNull { c ->
                c.pairedAt == null &&
                    c.apiServerUrl.isBlank() &&
                    c.label == PLACEHOLDER_LABEL
            }
            if (reusable != null) {
                android.util.Log.i(
                    "ConnectionViewModel",
                    "beginAddConnection: reusing placeholder id=${reusable.id} " +
                        "instead of pre-allocated id=$preAllocatedId",
                )
                if (connectionStore.activeConnectionId.value != reusable.id) {
                    switchConnection(reusable.id).join()
                }
                return@withLock reusable.id
            }

            val placeholder = Connection(
                id = preAllocatedId,
                label = PLACEHOLDER_LABEL,
                apiServerUrl = "",
                relayUrl = "",
                tokenStoreKey = Connection.buildTokenStoreKey(preAllocatedId),
                pairedAt = null,
                lastActiveSessionId = null,
                transportHint = null,
                expiresAt = null,
            )
            connectionStore.addConnection(placeholder)
            switchConnection(preAllocatedId).join()
            return@withLock preAllocatedId
        }

        // Legacy path: reuse an existing unpaired placeholder from a
        // prior aborted attempt if one is lying around. Cheaper than
        // creating a second placeholder + expecting the init-time
        // orphan sweep to clean it up later, and correctly idempotent
        // under rapid double-tap: both callers converge on the same
        // id, the second `switchConnection` is a no-op (coordinator
        // short-circuits when id == activeConnectionId), and we
        // return the same string both times.
        val existing = connectionStore.connections.value.firstOrNull { c ->
            c.pairedAt == null &&
                c.apiServerUrl.isBlank() &&
                c.label == PLACEHOLDER_LABEL
        }
        if (existing != null) {
            android.util.Log.i(
                "ConnectionViewModel",
                "beginAddConnection: reusing existing placeholder id=${existing.id}",
            )
            if (connectionStore.activeConnectionId.value != existing.id) {
                switchConnection(existing.id).join()
            }
            return@withLock existing.id
        }

        val id = java.util.UUID.randomUUID().toString()
        val placeholder = Connection(
            id = id,
            label = PLACEHOLDER_LABEL,
            apiServerUrl = "",
            relayUrl = "",
            tokenStoreKey = Connection.buildTokenStoreKey(id),
            pairedAt = null,
            lastActiveSessionId = null,
            transportHint = null,
            expiresAt = null,
        )
        connectionStore.addConnection(placeholder)
        // Join so the fresh authManager is bound before the caller
        // navigates into the Pair wizard — otherwise applyPairingPayload
        // could fire against the outgoing auth store if the user scans
        // faster than the switch coroutine completes.
        switchConnection(id).join()
        id
    }

    /**
     * Ensure first-run/onboarding setup has a real active [Connection] row
     * before credentials are written. Settings → Add connection already calls
     * [beginAddConnection] before showing the wizard, but onboarding embeds the
     * wizard directly; without this guard an API-only QR could configure the
     * live client and load profiles while leaving no durable connection card.
     */
    suspend fun ensureActiveConnectionForSetup(
        apiServerUrl: String = "",
        relayUrl: String = "",
        routeCandidates: List<EndpointCandidate>? = null,
    ): String = addConnectionMutex.withLock {
        val activeId = connectionStore.activeConnectionId.value
        val active = activeId?.let { id ->
            connectionStore.connections.value.firstOrNull { it.id == id }
        }
        if (active != null) return@withLock active.id

        val reusable = connectionStore.connections.value.firstOrNull { c ->
            c.pairedAt == null &&
                c.apiServerUrl.isBlank() &&
                c.label == PLACEHOLDER_LABEL
        } ?: connectionStore.connections.value.firstOrNull()

        if (reusable != null) {
            android.util.Log.i(
                "ConnectionViewModel",
                "ensureActiveConnectionForSetup: activating existing connection id=${reusable.id}",
            )
            switchConnection(reusable.id).join()
            return@withLock reusable.id
        }

        val trimmedApiUrl = apiServerUrl.trim()
        val trimmedRelayUrl = relayUrl.trim()
            .ifBlank { Connection.deriveDefaultRelayUrl(trimmedApiUrl).orEmpty() }
        val id = java.util.UUID.randomUUID().toString()
        val routes = routeCandidates
            ?.takeIf { it.isNotEmpty() }
            ?: trimmedApiUrl.takeIf { it.isNotBlank() }?.let {
                Connection.buildRouteCandidates(
                    apiServerUrl = trimmedApiUrl,
                    relayUrl = trimmedRelayUrl,
                )
            }.orEmpty()
        val connection = Connection(
            id = id,
            label = trimmedApiUrl.takeIf { it.isNotBlank() }
                ?.let(Connection::extractDefaultLabel)
                ?: PLACEHOLDER_LABEL,
            apiServerUrl = trimmedApiUrl,
            relayUrl = trimmedRelayUrl,
            tokenStoreKey = Connection.buildTokenStoreKey(id),
            dashboardUrl = Connection.deriveDefaultDashboardUrl(trimmedApiUrl),
            routeCandidates = routes,
            pairedAt = null,
            lastActiveSessionId = null,
            transportHint = null,
            expiresAt = null,
        )
        connectionStore.addConnection(connection)
        switchConnection(id).join()
        android.util.Log.i(
            "ConnectionViewModel",
            "ensureActiveConnectionForSetup: created first-run connection id=$id api=$trimmedApiUrl",
        )
        id
    }

    /**
     * Remove a placeholder [Connection] created by [beginAddConnection]
     * when the user cancels before a successful pair. No-op when the
     * connection has been paired (pairedAt != null) or already has a
     * non-empty URL — i.e. the pair partially succeeded and the record
     * is now real. Also no-op when [connectionId] isn't found.
     *
     * Called from the Pair route's onCancel handler. Safe to call on
     * every cancel without checking state; the internal guard handles
     * the "real connection" case.
     */
    suspend fun discardPlaceholderConnection(connectionId: String) {
        val existing = connectionStore.connections.value.firstOrNull { it.id == connectionId }
            ?: return
        if (existing.pairedAt != null) return
        // An unpaired connection with a populated API URL means the
        // scan hit applyPairingPayload (which writes the URL) but the
        // handshake didn't complete to Paired. We still remove it —
        // the user pressed cancel, the record is garbage.
        removeConnection(connectionId)
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
        val removed = connectionStore.connections.value.firstOrNull { it.id == connectionId }
            ?: return
        val wasActive = connectionId == activeConnectionId.value
        val removedDeviceId = readStoredDeviceIdForRemoval(removed, wasActive)
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
            } else {
                // No successor to switch into — the removed connection
                // WAS the last one. Before the teardown was wired here,
                // `removeConnection` in this branch only cleared the
                // store entry, which left the API client, the WSS socket,
                // and the reachable/health flags alive and pointed at
                // the just-removed URL: status chips kept saying "Paired
                // · Reachable" for a ghost connection.
                //
                // teardownActive() runs the transport half of
                // switchConnection (stream cancel → voice stop → WSS
                // disconnect → switch-event emit) against the active
                // coordinator mutex, so a concurrent Add-connection flow
                // from the UI serialises cleanly.
                connectionSwitchCoordinator.teardownActive().join()

                // Clear the in-memory AuthManager state for the
                // about-to-be-removed connection. Without this the
                // Session row keeps reading `AuthState.Paired(token)`
                // (which the ConnectionManager.disconnect() call inside
                // teardownActive doesn't touch — it only wipes the WSS
                // socket) and the UI shows a green "Paired" dot for a
                // ghost connection. `clearSession()` also nulls
                // `currentPairedSession`, which the relay UI state
                // resolver needs in order to flip its row off
                // Connected.
                authManager.clearSession()

                // URL flows + persisted DataStore entries point at the
                // removed URL. Blank them out so the 30 s periodic
                // health probes (which only run when `_apiClient.value
                // != null` and `_relayUrl.value.isNotBlank()`) stop
                // firing, and cold relaunch doesn't re-seed connection
                // 0 from stale legacy keys.
                _apiServerUrl.value = ""
                _relayUrl.value = ""
                getApplication<Application>().relayDataStore.edit { prefs ->
                    prefs[KEY_API_SERVER_URL] = ""
                    prefs[KEY_RELAY_URL] = ""
                    prefs.remove(KEY_LAST_SESSION_ID)
                }
                // rebuildApiClient() with blank URL nulls _apiClient,
                // flips _apiServerReachable / _apiServerHealth /
                // _chatMode / _serverCapabilities to their disconnected
                // poses (see the `else` branch of rebuildApiClient).
                // This is what actually drives the status badges back
                // to "Not configured".
                rebuildApiClient()
            }
        }
        scrubConnectionArtifacts(removed, removedDeviceId)
        connectionStore.removeConnection(connectionId)
        // Clear the persisted profile selection for the removed connection
        // AFTER the switch-away above has finished. Ordering matters: if
        // we cleared first, any in-flight hydration from the just-swapped
        // AuthManager could race with the delete. Safe because
        // ProfileSelectionStore is a separate DataStore file from
        // ConnectionStore's EncryptedSharedPrefs.
        profileController.profileSelectionStore.clear(connectionId)
        profileController.profileLockStore.clear(connectionId)
        profileController.profileSessionStore.clearConnection(connectionId)
        profileController.profileDisplayAliasStore.clearConnection(connectionId)
        profileController.profileIconStore.clearConnection(connectionId)
    }

    private suspend fun readStoredDeviceIdForRemoval(
        connection: Connection,
        wasActive: Boolean,
    ): String? {
        if (wasActive) {
            runCatching { authManager.getExistingDeviceId() }
                .getOrNull()
                ?.let { return it }
        }
        return runCatching {
            AuthManager.readStoredDeviceId(getApplication(), connection.tokenStoreKey)
        }.getOrNull()
    }

    private suspend fun scrubConnectionArtifacts(
        connection: Connection,
        deviceId: String?,
    ) {
        if (deviceId != null) {
            runCatching {
                PairingPreferences.removeDeviceEndpoints(getApplication(), deviceId)
            }.onFailure {
                android.util.Log.w(
                    "ConnectionViewModel",
                    "removeConnection: failed to remove endpoints for ${connection.id}: ${it.message}",
                )
            }
        }
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

        // (The networkStatus → revalidate() collector lives further down in
        // this init block, next to the other health-probe wiring — a second
        // identical copy used to sit here; one is enough.)

        // === PHASE3-accessibility: bridge handler registration ===
        // Device Control commands are sideload-only. Google Play still
        // registers the bridge channel so direct route probes fail closed with
        // a clear 403 instead of waiting for a command timeout.
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
        if (BuildFlavor.isSideload) {
            viewModelScope.launch {
                com.hermesandroid.relay.accessibility.HermesAccessibilityService
                    .masterEnabledFlow(application)
                    .distinctUntilChanged()
                    .drop(1)
                    .collect {
                        bridgeStatusReporter.pushNow()
                    }
            }
        }
        // === END PHASE3-status ===

        if (BuildFlavor.isSideload) {
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
                        val envelope = com.hermesandroid.relay.network.relay.models.Envelope(
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
        }
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
                effectiveRelayUrl,
                relayConfigured,
            ) { auth, conn, url, configured -> RelayUiInputs(auth, conn, url, configured) }
                .collect { inputs ->
                    pendingStaleJob?.cancel()
                    pendingStaleJob = null
                    _relayUiState.value = when {
                        !inputs.configured || inputs.url.isBlank() -> RelayUiState.NotConfigured
                        inputs.conn == ConnectionState.Connected -> RelayUiState.Connected
                        inputs.conn == ConnectionState.Connecting ||
                            inputs.conn == ConnectionState.Reconnecting ->
                            RelayUiState.Connecting
                        inputs.auth is AuthState.Paired &&
                            inputs.conn == ConnectionState.Disconnected -> {
                            // Start the grace-window timer. If the WSS
                            // doesn't come up within RELAY_RECONNECT_GRACE_MS,
                            // we promote to Stale so the UI stops lying
                            // ("Connecting…" forever) and surfaces a
                            // tap-to-retry affordance.
                            pendingStaleJob = launch {
                                delay(RELAY_RECONNECT_GRACE_MS)
                                _relayUiState.value = RelayUiState.Stale
                                DiagnosticsLog.record(
                                    category = DiagnosticCategory.Relay,
                                    severity = DiagnosticSeverity.Warning,
                                    title = "Relay stale",
                                    detail = "Paired session is present, but the live relay socket did not connect",
                                    url = inputs.url,
                                )
                            }
                            RelayUiState.Connecting
                        }
                        // The relay rejected our token (revoked, or wiped by a
                        // relay restart). Reconnecting won't help — surface a
                        // distinct "pair again" state instead of a generic
                        // Disconnected the user can't act on.
                        inputs.auth is AuthState.Failed -> RelayUiState.Expired
                        else -> RelayUiState.Disconnected
                    }
                }
        }

        viewModelScope.launch {
            var previousState: ConnectionState? = null
            var previousRole: String? = null
            combine(
                relayConnectionState,
                connectionManager.activeEndpoint,
            ) { state, endpoint -> state to endpoint?.role }
                .distinctUntilChanged()
                .collect { (state, role) ->
                    val priorState = previousState
                    val priorRole = previousRole
                    previousState = state
                    previousRole = role
                    if (priorState == null) return@collect

                    when {
                        state == ConnectionState.Reconnecting -> {
                            recordConnectionHandoff(
                                title = "Connection changed",
                                route = displayEndpointRole(role ?: priorRole),
                                detail = "Trying relay route",
                                active = true,
                                success = false,
                            )
                        }
                        state == ConnectionState.Connected &&
                            priorState == ConnectionState.Reconnecting -> {
                            recordConnectionHandoff(
                                title = "Connection restored",
                                route = displayEndpointRole(role),
                                detail = "Relay path ready",
                                active = false,
                                success = true,
                            )
                        }
                        state == ConnectionState.Connected &&
                            priorState != ConnectionState.Connected -> {
                            recordConnectionHandoff(
                                title = "Connected to Hermes",
                                route = displayEndpointRole(role),
                                detail = "Relay path ready",
                                active = false,
                                success = true,
                            )
                        }
                        state == ConnectionState.Connected &&
                            !priorRole.isNullOrBlank() &&
                            !role.isNullOrBlank() &&
                            !priorRole.equals(role, ignoreCase = true) -> {
                            val from = displayEndpointRole(priorRole)
                            val to = displayEndpointRole(role)
                            recordConnectionHandoff(
                                title = "Connection route changed",
                                route = to,
                                detail = listOfNotNull(from, to).joinToString(" -> "),
                                active = false,
                                success = true,
                            )
                        }
                        state == ConnectionState.Disconnected &&
                            priorState == ConnectionState.Connected -> {
                            recordConnectionHandoff(
                                title = "Connection interrupted",
                                route = displayEndpointRole(priorRole),
                                detail = "Looking for another route",
                                active = true,
                                success = false,
                            )
                        }
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
                    // Stale-emission guard: during a connection switch,
                    // `currentPairedSession` can briefly carry the OUTGOING
                    // connection's session while `activeConnectionId` has
                    // already flipped to the incoming id (flatMapLatest re-
                    // subscribes asynchronously). Without this check we'd
                    // `markPaired` the new placeholder connection using the
                    // old connection's session data — stamping pairedAt on
                    // a Connection that still has blank URLs, which then
                    // locks out the real rename-on-pair path below because
                    // current.pairedAt is no longer null.
                    //
                    // A real pair payload writes the API URL (via
                    // `applyPairingPayload` → per-Connection store mirror)
                    // BEFORE the WSS handshake that emits auth.ok, so by
                    // the time a genuine session lands, apiServerUrl is
                    // always populated. A blank URL here therefore implies
                    // a premature/stale fire — safe to ignore.
                    if (current.apiServerUrl.isBlank()) {
                        android.util.Log.d(
                            "ConnectionVM",
                            "pair-success watcher: skipping stale emission " +
                                "(connId=$connId has blank apiServerUrl — " +
                                "likely cross-connection flow leak during switch)",
                        )
                        return@collect
                    }
                    connectionStore.markPaired(
                        connectionId = connId,
                        pairedAtMillis = System.currentTimeMillis(),
                        transportHint = paired.transportHint,
                        // PairedSession.expiresAt is epoch seconds; the
                        // store docs are explicit that it expects millis —
                        // passing seconds would render as "Paired decades ago".
                        expiresAtMillis = paired.expiresAt?.let { it * 1000L },
                    )

                    // Duplicate-server merge. Pairing to a server the user
                    // already has a connection to (e.g. `Add connection` →
                    // scan the same QR twice across different sessions)
                    // would otherwise produce two cards on the Connections
                    // screen pointing at the same API URL. The new pair is
                    // authoritative (fresh session token + TTL), so we
                    // collapse by deleting the older duplicate.
                    //
                    // Deferred to AFTER markPaired rather than folded into
                    // `applyPairingPayload`'s URL-mirror step on purpose:
                    // if the WSS handshake had failed between the URL
                    // write and auth.ok, an applyPairingPayload-side merge
                    // would have already deleted the user's working
                    // connection and replaced it with an unpaired ghost.
                    // Running here guarantees the new session is good
                    // before we touch the old record.
                    //
                    // Label carry-over: if the old duplicate had a
                    // user-customized label (i.e. not the placeholder),
                    // prefer it over the host-derived default the rename
                    // path would otherwise pick. Preserves user intent
                    // across a re-scan of the same server. Resolved into
                    // `carriedLabel` so the rename block below can fall
                    // through to a single `updateConnection` call.
                    var carriedLabel: String? = null
                    val duplicates = connectionStore.connections.value
                        .filter { other ->
                            other.id != connId &&
                                other.apiServerUrl.isNotBlank() &&
                                other.apiServerUrl == current.apiServerUrl
                        }
                    for (duplicate in duplicates) {
                        // Take the first custom label we encounter. If
                        // several duplicates all have custom labels (would
                        // indicate the user has been renaming same-URL
                        // entries for a while), we pick the first in
                        // store order rather than trying to reconcile —
                        // a one-line snackbar on the caller could surface
                        // this if it turns out to be a real footgun later.
                        if (carriedLabel == null && duplicate.label != PLACEHOLDER_LABEL) {
                            carriedLabel = duplicate.label
                        }
                        android.util.Log.i(
                            "ConnectionVM",
                            "pair-success dedupe: merging duplicate " +
                                "id=${duplicate.id} (same apiServerUrl as active " +
                                "connId=$connId) — preserveLabel=$carriedLabel",
                        )
                        // Direct store call rather than the public
                        // ConnectionViewModel.removeConnection — the latter
                        // does a switch-first if the target is active,
                        // which would thrash here since the duplicate is
                        // by construction NOT the active connection. Also
                        // clears the per-connection profile pick so the
                        // removed connection's EncryptedPrefs + route list
                        // + profile pointer go together.
                        scrubConnectionArtifacts(
                            duplicate,
                            readStoredDeviceIdForRemoval(duplicate, wasActive = false),
                        )
                        connectionStore.removeConnection(duplicate.id)
                        profileController.profileSelectionStore.clear(duplicate.id)
                        profileController.profileLockStore.clear(duplicate.id)
                        profileController.profileSessionStore.clearConnection(duplicate.id)
                    }

                    // Auto-rename the placeholder label created by
                    // [beginAddConnection]. We only touch the label when
                    // it's still the exact placeholder string — if the
                    // user typed a custom name during pairing we leave
                    // it alone. Label source preference:
                    //   1. [carriedLabel] from a de-duped predecessor, so
                    //      a re-pair to the same server keeps the user's
                    //      prior custom name.
                    //   2. Otherwise the host-derived default — same
                    //      formula as [Connection.extractDefaultLabel]
                    //      used for legacy-seeded connections.
                    if (current.label == PLACEHOLDER_LABEL &&
                        current.apiServerUrl.isNotBlank()
                    ) {
                        val newLabel = carriedLabel
                            ?: Connection.extractDefaultLabel(current.apiServerUrl)
                        val refreshed = connectionStore.connections.value
                            .firstOrNull { it.id == connId }
                        if (refreshed != null) {
                            connectionStore.updateConnection(
                                refreshed.copy(label = newLabel),
                            )
                        }
                    }

                    // ADR 24 — on fresh pair, endpoints land in DataStore
                    // inside handleAuthOk. The initial connect() call
                    // ran BEFORE that persistence and therefore gave up
                    // on the resolver (no endpoints stored yet) → set
                    // activeEndpoint to null. Kick a re-probe now that
                    // the candidate list is on disk; probeAndReconnect
                    // only swaps the socket if the winner's URL differs
                    // from the currently-connected URL, so for a
                    // same-endpoint pair (the common case — LAN won
                    // during pair, LAN still wins post-pair) this is a
                    // zero-disruption activeEndpoint flow update.
                    connectionManager.probeAndReconnect()
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
                val hasLegacyConnectionState =
                    prefs[KEY_API_SERVER_URL] != null ||
                        prefs[KEY_RELAY_URL] != null ||
                        prefs[KEY_SERVER_URL] != null ||
                        prefs[KEY_LAST_SESSION_ID] != null
                val legacyApiUrl = if (hasLegacyConnectionState) {
                    prefs[KEY_API_SERVER_URL] ?: DEFAULT_API_URL
                } else {
                    null
                }
                val legacyRelayUrl = if (hasLegacyConnectionState) {
                    prefs[KEY_RELAY_URL]
                        ?: prefs[KEY_SERVER_URL]
                        ?: DEFAULT_RELAY_URL
                } else {
                    null
                }
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

        // Defensive: sweep orphaned placeholders left behind by a prior
        // "Add connection" flow the user abandoned via system back / gesture
        // back (the pre-fix code path only cleaned up on explicit Cancel).
        // An orphan is an unpaired connection with no URL ever written AND
        // the exact PLACEHOLDER_LABEL — that tuple cannot be produced by any
        // real pairing, so it's safe to delete unconditionally.
        //
        // Runs once on VM init AFTER the legacy migration completes so a
        // freshly-seeded connection (which also has pairedAt == null until
        // its first auth.ok) isn't misidentified — legacy seed uses the
        // host-derived default label, never PLACEHOLDER_LABEL.
        //
        // If the currently-active id points at a placeholder we're about to
        // remove, switch to whichever real connection comes first in the
        // list before deleting so we don't leave activeConnectionId pointing
        // at a dead record.
        viewModelScope.launch {
            try {
                // Let the legacy seed land first — it's a short
                // writeMutex-guarded path, typically < 50ms.
                val connections = connectionStore.connections.first()
                val orphans = connections.filter {
                    it.pairedAt == null &&
                        it.apiServerUrl.isBlank() &&
                        it.label == PLACEHOLDER_LABEL
                }
                if (orphans.isEmpty()) return@launch

                val activeId = connectionStore.activeConnectionId.value
                if (activeId != null && orphans.any { it.id == activeId }) {
                    val successor = connections.firstOrNull {
                        it.id != activeId && it !in orphans
                    }
                    if (successor != null) {
                        switchConnection(successor.id).join()
                    }
                }
                for (orphan in orphans) {
                    android.util.Log.i(
                        "ConnectionViewModel",
                        "Removing orphan placeholder connection id=${orphan.id}",
                    )
                    connectionStore.removeConnection(orphan.id)
                }
            } catch (e: Exception) {
                android.util.Log.w(
                    "ConnectionViewModel",
                    "Orphan placeholder sweep failed: ${e.message}",
                )
            }
        }

        // Cold-start context restore. The field initializer above has to build
        // an AuthManager before ConnectionStore has hydrated, so it starts on
        // the legacy token store. Once the persisted active connection is known,
        // rebuild against that connection's tokenStoreKey so session/API-key
        // state survives process death.
        viewModelScope.launch {
            try {
                val connection = activeConnection.first { it != null } ?: return@launch
                restorePersistedActiveConnectionContext(connection)
            } catch (e: Exception) {
                android.util.Log.w(
                    "ConnectionViewModel",
                    "restorePersistedActiveConnectionContext failed: ${e.message}",
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

                // Last-session restore is profile-scoped now. Keep that
                // state owned by refreshLastSessionForProfile(); otherwise
                // any unrelated relayDataStore emission can overwrite an
                // active profile's session with the legacy default id.

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
                // the DataStore flow. apiKeyForClientBuild() skips the Tink
                // crypto-init wait entirely on known-key-less connections —
                // this launch is the FIRST client build at cold start, so
                // awaiting the full decrypt here used to hold chat, health,
                // and capabilities hostage to the StrongBox keyset marathon.
                val currentUrl = effectiveApiServerUrlSnapshot()
                launch {
                    val currentKey = apiKeyForClientBuild()
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
        //
        // Escalation: two consecutive Unreachable probes kick a full route
        // re-resolve with the probe cache cleared. This is the safety net for
        // network changes the NetworkCallback never saw (e.g. an always-on
        // VPN keeping "internet available" true through a Wi-Fi → cell
        // handoff) — without it, an open app keeps probing the dead LAN URL
        // forever. The effectiveApiServerUrl collector below rebuilds the
        // HTTP clients when the resolved route actually moves.
        viewModelScope.launch {
            var consecutiveApiFailures = 0
            while (true) {
                delay(30_000)
                if (_apiClient.value == null) {
                    consecutiveApiFailures = 0
                    continue
                }
                probeApiHealth()
                if (_apiServerHealth.value == HealthStatus.Unreachable) {
                    consecutiveApiFailures++
                    if (consecutiveApiFailures >= 2) {
                        consecutiveApiFailures = 0
                        connectionManager.refreshActiveEndpoint(clearProbeCache = true)
                    }
                } else {
                    consecutiveApiFailures = 0
                }
            }
        }

        // Fast-retry burst on an Unreachable verdict. The periodic loop
        // above ticks every 30s — a single transient miss (cold-start race
        // with the route resolver, Wi-Fi still settling, mid-route-swap)
        // used to park "offline" for a full tick, which the startup gate
        // (and the demo-video camera) measured as a 6–28s wildly variable
        // launch against the same healthy LAN server. One bounded burst per
        // failure episode: three quick re-probes, re-armed only by a
        // Reachable verdict — a genuinely down server fails one burst and
        // settles back to the 30s cadence (where the 2-consecutive-failures
        // escalation still owns route re-resolution). StateFlow dedup means
        // repeat Unreachable verdicts can't re-trigger the burst.
        viewModelScope.launch {
            var burstArmed = true
            _apiServerHealth.collect { verdict ->
                when (verdict) {
                    HealthStatus.Reachable -> burstArmed = true
                    HealthStatus.Unreachable -> {
                        if (!burstArmed) return@collect
                        burstArmed = false
                        for (retryDelayMs in listOf(2_500L, 5_000L, 7_500L)) {
                            delay(retryDelayMs)
                            if (_apiServerHealth.value != HealthStatus.Unreachable) {
                                return@collect
                            }
                            if (_apiClient.value == null) return@collect
                            probeApiHealth()
                        }
                    }
                    else -> Unit
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
                if (activeRelayConfiguredSnapshot() && effectiveRelayUrlSnapshot().isNotBlank()) {
                    probeRelayHealth()
                } else {
                    _relayServerHealth.value = HealthStatus.Unknown
                }
            }
        }

        // Runtime endpoint changes should move all HTTP clients, not only
        // the WSS relay socket. Stored URLs remain untouched; this rebuilds
        // chat/API against the currently resolved route.
        viewModelScope.launch {
            effectiveApiServerUrl
                .drop(1)
                .distinctUntilChanged()
                .collect {
                    if (_chatStreaming.value) {
                        // Rebuilding the chat client mid-turn replaces it and
                        // CANCELS the in-flight turn. The gateway socket rides a
                        // transient route blip via its own reconnect (keeping the
                        // live session), so defer the rebuild until the turn ends.
                        pendingApiClientRebuild = true
                        android.util.Log.i(
                            "ConnectionViewModel",
                            "route changed mid-turn — deferring chat client rebuild",
                        )
                    } else {
                        rebuildApiClient()
                    }
                }
        }

        // Apply a route change that was deferred because a turn was streaming.
        // (StateFlow already conflates/dedups, so no distinctUntilChanged.)
        viewModelScope.launch {
            _chatStreaming
                .collect { streaming ->
                    if (!streaming && pendingApiClientRebuild) {
                        pendingApiClientRebuild = false
                        android.util.Log.i(
                            "ConnectionViewModel",
                            "turn ended — applying deferred chat client rebuild",
                        )
                        rebuildApiClient()
                    }
                }
        }

        viewModelScope.launch {
            combine(effectiveRelayUrl, relayConfigured) { url, configured -> url to configured }
                .drop(1)
                .distinctUntilChanged()
                .collect { (url, configured) ->
                    if (!configured || url.isBlank()) {
                        _relayServerHealth.value = HealthStatus.Unknown
                    } else {
                        _relayServerHealth.value = HealthStatus.Probing
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

        // Drop the current Profile object as soon as a switch begins. The
        // persisted destination name is loaded only after activeConnectionId
        // changes, then resolved by the agentProfiles collector below.
        viewModelScope.launch {
            connectionSwitchEvents.collect {
                // Profile + pending state + dashboard list are per-connection —
                // drop them so the pending persisted name can't resolve against
                // the previous connection's profiles before the new list arrives.
                profileController.resetForConnectionSwitch()
                // Gateway state is per-connection: drop the sticky
                // Unsupported verdict and tear down the old socket so the
                // next probe/send evaluates the new connection fresh.
                upstreamTransport.resetGatewayForConnectionSwitch()
            }
        }

        // Profile selections are persisted per connection. Loading just the
        // name here keeps the UI/send path in "server default" until this
        // active connection's profile list arrives and can resolve it.
        viewModelScope.launch {
            activeConnectionId.collect { connectionId ->
                val connection = connectionId?.let { cid ->
                    connectionStore.connections.value.firstOrNull { it.id == cid }
                }
                connectionManager.setManualRoleOverride(connection?.preferredRouteRole)
                profileController.clearSelectedProfile()
                _lastSessionId.value = null
                profileController.setPendingConnectionId(connectionId)
                profileController.setPendingName(
                    connectionId?.let { cid ->
                        profileController.profileSelectionStore.selectedProfileFlow(cid).first()
                    }
                )
                profileController.resolvePendingProfileFrom(profileController.agentProfiles.value)
                profileController.refreshLastSessionForProfile(
                    connectionId,
                    profileController.selectedProfile.value?.name,
                )
                val apiRouteBefore = effectiveApiServerUrlSnapshot()
                connectionManager.refreshActiveEndpoint()
                if (effectiveApiServerUrlSnapshot() != apiRouteBefore) {
                    rebuildApiClient()
                }
                rebuildChatApiClient()
                // Hydrate the host's agent profiles eagerly (not lazily on
                // agent-sheet open). On a dashboard/gateway connection the relay
                // `auth.ok` profile list is empty, so without this the persisted
                // profile selection can't resolve until the user opens the picker
                // — the header shows the default agent on cold start, then visibly
                // snaps to the real profile (and re-scopes the chat) the moment the
                // sheet fetches the list. Best-effort; the agentProfiles collector
                // resolves the pending name once the list lands.
                refreshDashboardProfiles()
            }
        }

        // Resolve or refresh the selected Profile only from the active
        // connection's current agentProfiles list. A missing persisted name
        // remains pending so it can recover if the server advertises it later.
        viewModelScope.launch {
            profileController.agentProfiles.collect { list ->
                if (profileController.resolvePendingProfileFrom(list)) {
                    profileController.refreshLastSessionForProfile(
                        activeConnectionId.value,
                        profileController.selectedProfile.value?.name,
                    )
                    rebuildChatApiClient()
                }
            }
        }

        // Re-resolve when the per-connection profile lock changes — locking,
        // unlocking, and the lock flow repointing after a connection switch all
        // funnel through here so the active profile always reflects the lock
        // target (or holds null + a banner when it's missing). resolvePending
        // is lock-aware, so on unlock it falls back to the persisted selection.
        viewModelScope.launch {
            profileController.lockedProfileName.collect {
                if (profileController.resolvePendingProfileFrom(
                        profileController.agentProfiles.value,
                    )
                ) {
                    profileController.refreshLastSessionForProfile(
                        activeConnectionId.value,
                        profileController.selectedProfile.value?.name,
                    )
                    rebuildChatApiClient()
                }
            }
        }

        // Cold-start restore timing: the gateway probe is async, so the first
        // refreshLastSessionForProfile at connection-activate can run while
        // availability is still Unknown — [activeSessionTransport] defers, leaving
        // no session restored. Re-run once the probe settles, but only when
        // nothing has been restored or started yet, so we never yank a session
        // the user is already in.
        viewModelScope.launch {
            upstreamTransport.gatewayAvailability.collect { availability ->
                if (availability == GatewayAvailability.Unknown) return@collect
                if (_lastSessionId.value != null) return@collect
                val connectionId = activeConnectionId.value ?: return@collect
                profileController.refreshLastSessionForProfile(
                    connectionId,
                    profileController.selectedProfile.value?.name,
                )
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
            val apiRouteBefore = effectiveApiServerUrlSnapshot()
            // Clear the probe cache: revalidate() fires on resume / network
            // change, where a cached-reachable entry for the route we just
            // walked away from would win the resolve for up to 60s.
            connectionManager.refreshActiveEndpoint(clearProbeCache = true)
            if (effectiveApiServerUrlSnapshot() != apiRouteBefore) {
                rebuildApiClient()
            }
            // Flip to Probing immediately so the UI doesn't flash whatever
            // stale value the previous session left in the flow.
            if (_apiClient.value != null) {
                _apiServerHealth.value = HealthStatus.Probing
            }
            val shouldProbeRelay = activeRelayConfiguredSnapshot() &&
                effectiveRelayUrlSnapshot().isNotBlank()
            if (shouldProbeRelay) {
                _relayServerHealth.value = HealthStatus.Probing
            } else {
                _relayServerHealth.value = HealthStatus.Unknown
            }

            // Fire both probes in parallel — neither blocks the other.
            val apiProbe = launch { probeApiHealth() }
            val relayProbe = if (shouldProbeRelay) {
                launch { probeRelayHealth() }
            } else {
                null
            }
            apiProbe.join()
            relayProbe?.join()

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
            probeStandardVoice()
            return
        }
        val ok = client.checkHealth()
        _apiServerReachable.value = ok
        _apiServerHealth.value = if (ok) HealthStatus.Reachable else HealthStatus.Unreachable
        // Standard voice lives on the dashboard surface, not the API server,
        // so its probe runs regardless of API health.
        probeStandardVoice()
    }

    /**
     * Probe the standard (dashboard-surface) voice route and update
     * [standardVoiceAvailability]. Cheap: GET `/api/status` (public by
     * design), GET `/api/auth/me` only when the dashboard is gated, then a
     * HEAD existence check on the audio route (405 = present, 404 = absent).
     * Also refreshes the persisted dashboard status snapshot when it
     * materially changed, so the Manage header stays honest without the user
     * visiting the tab.
     */
    private suspend fun probeStandardVoice() {
        val connectionId = connectionStore.activeConnectionId.value
        val dashboardUrl = activeDashboardUrl()
        if (connectionId == null || dashboardUrl.isNullOrBlank()) {
            _standardVoiceAvailability.value = StandardVoiceAvailability.Unknown
            _standardAudioApiReachable.value = false
            _serverChatDisplaySettings.value = null
            updateGatewayAvailability(GatewayAvailability.Unknown)
            return
        }
        val client = upstreamTransport.dashboardClientForActive(dashboardUrl)
        try {
            val status = client.getStatus().getOrNull()
            if (status == null) {
                _standardVoiceAvailability.value = StandardVoiceAvailability.Unreachable
                _standardAudioApiReachable.value = false
                _serverChatDisplaySettings.value = null
                updateGatewayAvailability(GatewayAvailability.Unreachable)
                recordDashboardStatusIfChanged(connectionId, status = null, session = null)
                return
            }
            val session = if (status.authRequired) client.currentSession().getOrNull() else null
            val authed = !status.authRequired || session?.authenticated == true
            recordDashboardStatusIfChanged(connectionId, status, session)
            refreshChatDisplaySettings(client, authed)
            // Gateway chat shares the voice probe's dashboard checks; it has
            // no audio-route requirement.
            updateGatewayAvailability(
                if (authed) GatewayAvailability.Ready else GatewayAvailability.SignInRequired,
            )
            val availability = when {
                !authed -> StandardVoiceAvailability.SignInRequired
                client.audioRoutesPresent() -> StandardVoiceAvailability.Ready
                else -> StandardVoiceAvailability.Unsupported
            }
            _standardVoiceAvailability.value = availability
            _standardAudioApiReachable.value = availability == StandardVoiceAvailability.Ready
            if (availability == StandardVoiceAvailability.SignInRequired) {
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Voice,
                    severity = DiagnosticSeverity.Warning,
                    title = "Vanilla Hermes voice needs dashboard sign-in",
                    detail = "Dashboard sessions are per-host — a sign-in from another " +
                        "route does not carry over; sign in once via Manage on this one",
                    url = dashboardUrl,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Defense-in-depth: this runs in a viewModelScope (Main) coroutine,
            // so an unexpected throw from any probe sub-call would crash the
            // app (see the currentSession() stale-connection crash). A probe
            // failure must only degrade the UI, never be fatal.
            android.util.Log.w("ConnectionVM", "probeStandardVoice failed: ${e.message}")
            _standardVoiceAvailability.value = StandardVoiceAvailability.Unreachable
            _standardAudioApiReachable.value = false
            _serverChatDisplaySettings.value = null
            updateGatewayAvailability(GatewayAvailability.Unreachable)
        } finally {
            client.shutdown()
        }
    }

    private suspend fun refreshChatDisplaySettings(
        client: DashboardApiClient,
        authenticated: Boolean,
    ) {
        if (!authenticated) {
            _serverChatDisplaySettings.value = null
            return
        }
        client.getChatDisplaySettings().fold(
            onSuccess = { _serverChatDisplaySettings.value = it },
            onFailure = { _serverChatDisplaySettings.value = null },
        )
    }

    /**
     * [recordDashboardStatus] persists to the ConnectionStore; the voice probe
     * runs on every health cycle, so gate the write on a material change to
     * avoid chatty DataStore commits that would only refresh a timestamp.
     */
    private fun recordDashboardStatusIfChanged(
        connectionId: String,
        status: DashboardStatus?,
        session: DashboardAuthSession?,
    ) {
        val previous = connectionStore.connections.value
            .firstOrNull { it.id == connectionId }
            ?.dashboardLastStatus
        val reachable = status != null
        val materiallySame = previous != null &&
            previous.reachable == reachable &&
            previous.authRequired == status?.authRequired &&
            previous.authenticated == session?.authenticated
        if (!materiallySame) {
            recordDashboardStatus(status = status, session = session, reachable = reachable)
        }
    }

    /**
     * Run a single relay /health probe and update [relayServerHealth].
     * Uses the existing [RelayHttpClient.probeHealth] path (unauthenticated,
     * 3s timeout, no impact on the rate limiter). Distinct from
     * [testRelayReachable] which is the user-facing Save & Test action.
     */
    private suspend fun probeRelayHealth(force: Boolean = false) {
        if (!force && !activeRelayConfiguredSnapshot()) {
            _relayServerHealth.value = HealthStatus.Unknown
            return
        }
        val url = effectiveRelayUrlSnapshot()
        if (url.isBlank()) {
            _relayServerHealth.value = HealthStatus.Unknown
            return
        }
        val result = relayHttpClient.probeHealth(url, logSuccess = false)
        _relayServerHealth.value = if (result.isSuccess) {
            HealthStatus.Reachable
        } else {
            HealthStatus.Unreachable
        }
    }

    suspend fun verifyRelayForVoice(): Result<Unit> {
        if (!activeRelayConfiguredSnapshot()) {
            _relayServerHealth.value = HealthStatus.Unknown
            DiagnosticsLog.record(
                category = DiagnosticCategory.Voice,
                severity = DiagnosticSeverity.Warning,
                title = "Voice blocked",
                detail = "Relay is not configured for this connection",
            )
            return Result.failure(IllegalStateException("Relay is not configured for this connection"))
        }
        val url = effectiveRelayUrlSnapshot()
        if (url.isBlank()) {
            _relayServerHealth.value = HealthStatus.Unknown
            DiagnosticsLog.record(
                category = DiagnosticCategory.Voice,
                severity = DiagnosticSeverity.Error,
                title = "Voice blocked",
                detail = "Relay URL is not configured",
            )
            return Result.failure(IllegalStateException("Relay URL is not configured"))
        }

        _relayServerHealth.value = HealthStatus.Probing
        DiagnosticsLog.record(
            category = DiagnosticCategory.Voice,
            severity = DiagnosticSeverity.Info,
            title = "Checking relay for voice",
            url = url,
        )
        val result = relayHttpClient.probeHealth(url)
        _relayServerHealth.value = if (result.isSuccess) {
            HealthStatus.Reachable
        } else {
            HealthStatus.Unreachable
        }
        return result.map { Unit }
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
        // ADR 24 — stage the endpoint-candidate list so AuthManager persists
        // it under the paired device's id once `auth.ok` lands. `endpoints`
        // is always non-null + non-empty coming out of `parseHermesPairingQr`
        // (v1/v2 QRs get a synthesized priority-0 candidate); we pass
        // through whatever v3 emitted verbatim.
        authManager.setPendingEndpoints(payload.endpoints)

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

            // Persist route candidates before the first WSS attempt. On a
            // no-LAN pair, waiting until auth.ok means the socket tries the
            // LAN relay URL from the QR, never receives auth.ok, and therefore
            // never stores the Tailscale fallback it needed to connect.
            payload.endpoints?.takeIf { it.isNotEmpty() }?.let { endpoints ->
                try {
                    val ctx = getApplication<Application>()
                    val deviceId = authManager.getOrCreateDeviceId()
                    PairingPreferences.setDeviceEndpoints(ctx, deviceId, endpoints)
                    android.util.Log.i(
                        "ConnectionVM",
                        "applyPairingPayload: pre-persisted ${endpoints.size} endpoint(s) " +
                            "for device=$deviceId roles=${endpoints.map { it.role }}"
                    )
                } catch (e: Exception) {
                    android.util.Log.w(
                        "ConnectionVM",
                        "applyPairingPayload: endpoint pre-persist failed; " +
                            "initial connect will use relay.url (${e.message})"
                    )
                }
            }

            // ADR 24 — auto-stamp / clear PairingPreferences.insecureReason
            // based on the resolved endpoint's role so the Transport Security
            // badge reads correctly even when the user paired from a plain
            // LAN QR directly (and thus never had to toggle the insecure-ack
            // dialog that would otherwise be the only writer of this key).
            //
            //  - Secure (wss/https) → clear any stale stored reason so a
            //    post-Tailscale-upgrade connection doesn't keep showing
            //    "Insecure (LAN)" from a prior plain-LAN pair.
            //  - Plain + role=lan       → stamp "lan_only"
            //  - Plain + role=tailscale → stamp "tailscale_vpn" (rare — usually
            //    wss on Tailscale, but possible)
            //  - Plain + role=public / other / absent → leave blank; the user
            //    should consciously ack via the insecure dialog for those.
            //
            // Only overwrite when the stored reason is currently blank so we
            // never clobber a user-selected choice.
            run {
                val ctx = getApplication<Application>()
                val relayUrl = payload.relay?.url
                val isSecure = relayUrl?.let {
                    it.startsWith("wss://") || it.startsWith("https://")
                } ?: false
                if (isSecure) {
                    // Clear any stale "Insecure (LAN)" stamp left over from
                    // a prior plain pair on the same Connection.
                    if (insecureReason.value.isNotBlank()) {
                        PairingPreferences.setInsecureReason(ctx, "")
                    }
                } else if (relayUrl != null && insecureReason.value.isBlank()) {
                    // Find the candidate whose relay.url matches what we're
                    // about to connect to; fall back to the first candidate
                    // (priority-0) if the payload has endpoints but none
                    // match exactly.
                    val matched = payload.endpoints?.firstOrNull {
                        it.relay.url == relayUrl
                    } ?: payload.endpoints?.firstOrNull()
                    val autoReason = when (matched?.role?.lowercase()) {
                        "lan" -> "lan_only"
                        "tailscale" -> "tailscale_vpn"
                        else -> null // public / unknown / absent → let user ack
                    }
                    if (autoReason != null) {
                        PairingPreferences.setInsecureReason(ctx, autoReason)
                    }
                }
            }

            // Mirror the just-applied URLs into the active Connection's
            // store entry. [updateApiServerUrl] / [updateRelayUrl] above
            // only touch the APP-WIDE flows (_apiServerUrl, _relayUrl) +
            // the legacy relayDataStore keys — they do NOT write
            // Connection.apiServerUrl / Connection.relayUrl in the
            // ConnectionStore. Without this mirror:
            //   - connections list renders "New connection…" + blank
            //     endpoints forever (the rename-on-pair guard at the
            //     markPaired site checks current.apiServerUrl.isNotBlank
            //     and fails silently since the per-Connection field is
            //     still "");
            //   - a subsequent switch-away + switch-back reads
            //     Connection.apiServerUrl back into _apiServerUrl (step 8
            //     of ConnectionSwitchCoordinator) and clobbers the live
            //     URL with an empty string, breaking chat.
            // Must happen BEFORE connectRelay below so the auth.ok that
            // lands from that handshake sees populated URLs when the
            // pair-success watcher reconciles.
            val activeId = connectionStore.activeConnectionId.value
            if (activeId != null) {
                val current = connectionStore.connections.value
                    .firstOrNull { it.id == activeId }
                if (current != null) {
                    val newRelayUrl = payload.relay?.url ?: current.relayUrl
                    val payloadDashboardUrl = payload.dashboardUrl
                        ?.trim()
                        ?.trimEnd('/')
                        ?.takeIf { it.isNotBlank() }
                    val newDashboardUrl = payloadDashboardUrl
                        ?: if (
                            Connection.isAutoManagedDashboardUrl(current.dashboardUrl, current.apiServerUrl)
                        ) {
                            Connection.deriveDefaultDashboardUrl(payload.serverUrl)
                        } else {
                            current.dashboardUrl
                        }
                    val needsUpdate = current.apiServerUrl != payload.serverUrl ||
                        current.relayUrl != newRelayUrl ||
                        current.dashboardUrl != newDashboardUrl ||
                        current.routeCandidates != payload.endpoints.orEmpty()
                    if (needsUpdate) {
                        connectionStore.updateConnection(
                            current.copy(
                                apiServerUrl = payload.serverUrl,
                                relayUrl = newRelayUrl,
                                dashboardUrl = newDashboardUrl,
                                routeCandidates = payload.endpoints.orEmpty(),
                                preferredRouteRole = current.preferredRouteRole
                                    ?.takeIf { preferred ->
                                        payload.endpoints.orEmpty().any {
                                            it.role.equals(preferred, ignoreCase = true)
                                        }
                                    },
                            )
                        )
                    }
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
        // Same bare-host normalization as the wizard: `192.168.1.10`
        // becomes `http://192.168.1.10:8642`; explicit schemes pass verbatim.
        val trimmed = Connection.normalizeApiUrlInput(url)
        val previousApiUrl = _apiServerUrl.value
        val previousRelayUrl = _relayUrl.value
        val derivedRelayUrl = RelayUrlDeriver.deriveFromApiUrl(trimmed)
        val refreshedRelayUrl = derivedRelayUrl?.takeIf {
            RelayUrlDeriver.isAutoManagedRelayUrl(previousRelayUrl, previousApiUrl)
        }

        _apiServerUrl.value = trimmed
        if (refreshedRelayUrl != null) {
            _relayUrl.value = refreshedRelayUrl
            _relayServerHealth.value = if (activeRelayConfiguredSnapshot()) {
                HealthStatus.Probing
            } else {
                HealthStatus.Unknown
            }
        }
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_API_SERVER_URL] = trimmed
                if (refreshedRelayUrl != null) {
                    preferences[KEY_RELAY_URL] = refreshedRelayUrl
                }
            }
            persistActiveConnectionUrls(
                apiServerUrl = trimmed,
                relayUrl = if (refreshedRelayUrl != null) {
                    refreshedRelayUrl
                } else {
                    _relayUrl.value
                },
                routeCandidates = mergedRouteCandidates(
                    apiServerUrl = trimmed,
                    relayUrl = refreshedRelayUrl ?: _relayUrl.value,
                ),
            )
            connectionManager.refreshActiveEndpoint()
            rebuildApiClient()
            if (refreshedRelayUrl != null) {
                probeRelayHealth()
            }
        }
    }

    data class StandardApiSetupResult(
        val ok: Boolean,
        val message: String,
        val apiReachable: Boolean = ok,
        val dashboardReachable: Boolean? = null,
        val dashboardSignInRequired: Boolean = false,
        val dashboardAuthenticated: Boolean? = null,
        /** Standard (dashboard-surface) voice readiness, probed in the same pass. */
        val voiceAvailability: StandardVoiceAvailability = StandardVoiceAvailability.Unknown,
        val relayPaired: Boolean = false,
        /**
         * True when the saved connection has at least one fallback route
         * (priority > 0 — Tailscale, public, custom VPN). Drives the setup
         * result card's "Remote" readiness line so a LAN-only connection is
         * called out at the moment of maximum user attention, not discovered
         * the first time the phone leaves home.
         */
        val remoteRouteConfigured: Boolean = false,
    )

    fun recordDashboardStatus(
        status: DashboardStatus?,
        session: DashboardAuthSession? = null,
        reachable: Boolean = status != null,
        gatewayTicketAvailable: Boolean? = null,
        message: String? = status?.message,
    ) {
        val connectionId = connectionStore.activeConnectionId.value ?: return
        viewModelScope.launch {
            connectionStore.setDashboardStatus(
                connectionId = connectionId,
                status = com.hermesandroid.relay.data.DashboardConnectionStatus(
                    checkedAtMillis = System.currentTimeMillis(),
                    reachable = reachable,
                    authRequired = status?.authRequired,
                    authProviders = status?.authProviders.orEmpty(),
                    authenticated = session?.authenticated,
                    authProvider = session?.provider,
                    gatewayTicketAvailable = gatewayTicketAvailable,
                    message = message,
                ),
            )
        }
    }

    /**
     * Re-run the standard-voice probe outside the periodic health cycle.
     * Call after dashboard sign-in/sign-out so the mic gate and Voice
     * Settings status react immediately instead of on the next probe tick.
     */
    fun refreshStandardVoice() {
        viewModelScope.launch { probeStandardVoice() }
    }

    fun clearDashboardSession(onComplete: (() -> Unit)? = null) {
        val connectionId = connectionStore.activeConnectionId.value ?: return
        val active = connectionStore.connections.value.firstOrNull { it.id == connectionId }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dashboardCookieStoreFor(connectionId).clear()
            }
            connectionStore.setDashboardStatus(
                connectionId = connectionId,
                status = com.hermesandroid.relay.data.DashboardConnectionStatus(
                    checkedAtMillis = System.currentTimeMillis(),
                    reachable = active?.dashboardLastStatus?.reachable ?: false,
                    authRequired = active?.dashboardLastStatus?.authRequired,
                    authProviders = active?.dashboardLastStatus?.authProviders.orEmpty(),
                    authenticated = false,
                    authProvider = null,
                    gatewayTicketAvailable = false,
                    message = "Dashboard session cleared",
                ),
            )
            probeStandardVoice()
            onComplete?.invoke()
        }
    }

    /**
     * Persist the standard Hermes API/dashboard connection without requiring
     * a Relay pairing session. This is the first-run path for Chat + Manage:
     * save the API URL/key, refresh the derived dashboard URL, then verify
     * both `/health` and `/api/sessions` so missing API keys surface clearly.
     */
    fun saveStandardApiConnection(
        apiUrl: String,
        apiKey: String,
        tailscaleApiUrl: String = "",
        dashboardUrl: String = "",
        routeCandidatesOverride: List<EndpointCandidate>? = null,
        onResult: (StandardApiSetupResult) -> Unit,
    ) {
        // Bare host/IP input gets http:// and the surface's default port —
        // typing `192.168.1.10` or a Tailscale `100.x.y.z` without a scheme
        // is the common case and used to either block the wizard or silently
        // drop the route. Explicitly-schemed URLs pass through verbatim.
        val trimmedApiUrl = Connection.normalizeApiUrlInput(apiUrl)
        val trimmedTailscaleApiUrl = Connection.normalizeApiUrlInput(tailscaleApiUrl)
        val trimmedDashboardUrl = Connection.normalizeApiUrlInput(
            dashboardUrl,
            defaultPort = Connection.DEFAULT_DASHBOARD_PORT,
        )
        if (trimmedApiUrl.isBlank()) {
            onResult(
                StandardApiSetupResult(
                    ok = false,
                    message = "API server URL is required",
                    apiReachable = false,
                    relayPaired = authState.value is AuthState.Paired,
                ),
            )
            return
        }

        val previousApiUrl = _apiServerUrl.value
        val previousRelayUrl = _relayUrl.value
        val derivedRelayUrl = RelayUrlDeriver.deriveFromApiUrl(trimmedApiUrl)
        val refreshedRelayUrl = derivedRelayUrl?.takeIf {
            RelayUrlDeriver.isAutoManagedRelayUrl(previousRelayUrl, previousApiUrl)
        }

        _apiServerUrl.value = trimmedApiUrl
        val routeRelayUrl = refreshedRelayUrl ?: derivedRelayUrl ?: _relayUrl.value
        // No override → rebuild from the typed URLs but keep any stored
        // extra routes: the wizard does NOT pre-fill the Tailscale field, so
        // a blank field on a re-run means "unchanged", not "remove it".
        val routeCandidates = routeCandidatesOverride
            ?.takeIf { it.isNotEmpty() }
            ?.let { normalizeStandardRouteCandidates(it) }
            ?: mergedRouteCandidates(
                apiServerUrl = trimmedApiUrl,
                relayUrl = routeRelayUrl,
                extraApiUrls = listOfNotNull(
                    trimmedTailscaleApiUrl
                        .takeIf { it.isNotBlank() && !it.equals(trimmedApiUrl, ignoreCase = true) }
                        ?.let { "tailscale" to it },
                ),
            )
        if (refreshedRelayUrl != null) {
            _relayUrl.value = refreshedRelayUrl
            _relayServerHealth.value = if (activeRelayConfiguredSnapshot()) {
                HealthStatus.Probing
            } else {
                HealthStatus.Unknown
            }
        }

        viewModelScope.launch {
            ensureActiveConnectionForSetup(
                apiServerUrl = trimmedApiUrl,
                relayUrl = routeRelayUrl,
                routeCandidates = routeCandidates,
            )
            if (apiKey.isNotBlank()) {
                authManager.setApiKey(apiKey.trim())
            }

            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_API_SERVER_URL] = trimmedApiUrl
                if (refreshedRelayUrl != null) {
                    preferences[KEY_RELAY_URL] = refreshedRelayUrl
                }
            }

            persistActiveConnectionUrls(
                apiServerUrl = trimmedApiUrl,
                relayUrl = refreshedRelayUrl ?: _relayUrl.value,
                dashboardUrlOverride = trimmedDashboardUrl,
                routeCandidates = routeCandidates,
            )

            val activeId = connectionStore.activeConnectionId.value
            val active = activeId?.let { id ->
                connectionStore.connections.value.firstOrNull { it.id == id }
            }
            if (
                active != null &&
                active.label == PLACEHOLDER_LABEL &&
                active.apiServerUrl.isNotBlank()
            ) {
                connectionStore.updateConnection(
                    active.copy(label = Connection.extractDefaultLabel(trimmedApiUrl)),
                )
            }

            connectionManager.refreshActiveEndpoint()
            rebuildApiClient()
            if (refreshedRelayUrl != null) {
                probeRelayHealth()
            }

            val client = _apiClient.value
            if (client == null) {
                _apiServerReachable.value = false
                _apiServerHealth.value = HealthStatus.Unknown
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Api,
                    severity = DiagnosticSeverity.Warning,
                    title = "Vanilla Hermes setup skipped",
                    detail = "No API client configured",
                    url = effectiveApiServerUrlSnapshot(),
                )
                onResult(
                    StandardApiSetupResult(
                        ok = false,
                        message = "No API client configured",
                        apiReachable = false,
                        relayPaired = authState.value is AuthState.Paired,
                    ),
                )
                return@launch
            }

            _apiServerHealth.value = HealthStatus.Probing
            DiagnosticsLog.record(
                category = DiagnosticCategory.Api,
                severity = DiagnosticSeverity.Info,
                title = "Testing standard Hermes connection",
                url = effectiveApiServerUrlSnapshot(),
            )

            val health = client.checkHealthDetailed()
            if (health is com.hermesandroid.relay.network.upstream.HealthCheckResult.Unhealthy) {
                _apiServerReachable.value = false
                _apiServerHealth.value = HealthStatus.Unreachable
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Api,
                    severity = DiagnosticSeverity.Error,
                    title = "Vanilla Hermes setup health failed",
                    detail = health.message,
                    url = effectiveApiServerUrlSnapshot(),
                )
                onResult(
                    StandardApiSetupResult(
                        ok = false,
                        message = health.message,
                        apiReachable = false,
                        relayPaired = authState.value is AuthState.Paired,
                    ),
                )
                return@launch
            }

            val sessions = client.checkSessionsAuthDetailed()
            val reachable = sessions is com.hermesandroid.relay.network.upstream.HealthCheckResult.Healthy
            _apiServerReachable.value = reachable
            _apiServerHealth.value = if (reachable) HealthStatus.Reachable else HealthStatus.Unreachable
            var dashboardSignInRequired = false
            var dashboardReachable: Boolean? = null
            var dashboardAuthenticated: Boolean? = null
            var voiceAvailability = StandardVoiceAvailability.Unknown
            if (reachable) {
                val connectionId = connectionStore.activeConnectionId.value
                val dashboardUrlForProbe = connectionId?.let { id ->
                    connectionStore.connections.value
                        .firstOrNull { it.id == id }
                        ?.resolvedDashboardUrl
                        ?.takeIf { it.isNotBlank() }
                }
                if (connectionId != null && dashboardUrlForProbe != null) {
                    val dashboardClient =
                        upstreamTransport.dashboardClientFor(connectionId, dashboardUrlForProbe)
                    try {
                        val status = dashboardClient.getStatus().getOrNull()
                        val session = if (status?.authRequired == true) {
                            dashboardClient.currentSession().getOrNull()
                        } else {
                            null
                        }
                        dashboardSignInRequired =
                            status?.authRequired == true && session?.authenticated != true
                        dashboardReachable = status != null
                        dashboardAuthenticated = session?.authenticated
                        // Voice rides this same surface — settle availability in
                        // the same pass so the wizard's capability card and the
                        // mic gate are correct the moment setup completes.
                        voiceAvailability = when {
                            status == null -> StandardVoiceAvailability.Unreachable
                            dashboardSignInRequired -> StandardVoiceAvailability.SignInRequired
                            dashboardClient.audioRoutesPresent() -> StandardVoiceAvailability.Ready
                            else -> StandardVoiceAvailability.Unsupported
                        }
                        _standardVoiceAvailability.value = voiceAvailability
                        _standardAudioApiReachable.value =
                            voiceAvailability == StandardVoiceAvailability.Ready
                        recordDashboardStatus(
                            status = status,
                            session = session,
                            reachable = status != null,
                        )
                    } finally {
                        dashboardClient.shutdown()
                    }
                }
            }
            val message = when (sessions) {
                is com.hermesandroid.relay.network.upstream.HealthCheckResult.Healthy -> {
                    if (dashboardSignInRequired) {
                        "Connected to Hermes API. Dashboard sign-in required for Manage."
                    } else {
                        "Connected to Hermes API and sessions"
                    }
                }
                is com.hermesandroid.relay.network.upstream.HealthCheckResult.Unhealthy ->
                    sessions.message
            }
            DiagnosticsLog.record(
                category = DiagnosticCategory.Api,
                severity = if (reachable) DiagnosticSeverity.Info else DiagnosticSeverity.Error,
                title = if (reachable) "Vanilla Hermes connection ok" else "Vanilla Hermes auth failed",
                detail = message,
                url = effectiveApiServerUrlSnapshot(),
            )
            onResult(
                StandardApiSetupResult(
                    ok = reachable,
                    message = message,
                    apiReachable = reachable,
                    dashboardReachable = dashboardReachable,
                    dashboardSignInRequired = dashboardSignInRequired,
                    dashboardAuthenticated = dashboardAuthenticated,
                    voiceAvailability = voiceAvailability,
                    relayPaired = authState.value is AuthState.Paired,
                    remoteRouteConfigured = routeCandidates.any { it.priority > 0 },
                ),
            )
        }
    }

    data class ApiVoiceSetupResult(
        val apiReachable: Boolean,
        val relayUrl: String?,
        val relayAutoDerived: Boolean,
        val voiceConfigReachable: Boolean,
        val voiceConfigError: String?,
        val voiceRoute: String,
    )

    /**
     * Persist API URL/key, resolve the optional Relay URL, then verify the
     * best available stable voice route. Standard Hermes audio is preferred;
     * Relay voice is only probed as a fallback when Relay is intentionally
     * configured for the active connection.
     */
    fun saveApiAndProbeVoice(
        apiUrl: String,
        apiKey: String,
        manualRelayUrlOverride: String?,
        onResult: (ApiVoiceSetupResult) -> Unit,
    ) {
        val trimmedApiUrl = apiUrl.trim()
        val trimmedOverride = manualRelayUrlOverride?.trim().orEmpty()
        val derivedRelayUrl = RelayUrlDeriver.deriveFromApiUrl(trimmedApiUrl)
        val nextRelayUrl = trimmedOverride.ifBlank { derivedRelayUrl.orEmpty() }
        val usingAutoRelay = trimmedOverride.isBlank()
        val shouldProbeRelay = trimmedOverride.isNotBlank() || activeRelayConfiguredSnapshot()

        _apiServerUrl.value = trimmedApiUrl
        if (nextRelayUrl.isNotBlank()) {
            _relayUrl.value = nextRelayUrl
            _relayServerHealth.value = if (shouldProbeRelay) {
                HealthStatus.Probing
            } else {
                HealthStatus.Unknown
            }
        }

        viewModelScope.launch {
            if (apiKey.isNotBlank()) {
                authManager.setApiKey(apiKey.trim())
            }
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_API_SERVER_URL] = trimmedApiUrl
                if (nextRelayUrl.isNotBlank()) {
                    preferences[KEY_RELAY_URL] = nextRelayUrl
                }
            }
            persistActiveConnectionUrls(
                apiServerUrl = trimmedApiUrl,
                relayUrl = nextRelayUrl,
                routeCandidates = mergedRouteCandidates(
                    apiServerUrl = trimmedApiUrl,
                    relayUrl = nextRelayUrl,
                ),
            )

            connectionManager.refreshActiveEndpoint()
            rebuildApiClient()
            val apiReachable = _apiServerReachable.value

            val standardVoiceResult = if (_standardAudioApiReachable.value) {
                Result.success(Unit)
            } else {
                Result.failure(
                    IllegalStateException(
                        when (_standardVoiceAvailability.value) {
                            StandardVoiceAvailability.SignInRequired ->
                                "Vanilla Hermes voice needs dashboard sign-in (Manage tab)"
                            StandardVoiceAvailability.Unsupported ->
                                "This Hermes build has no dashboard audio routes"
                            else -> "Vanilla Hermes voice is not available"
                        },
                    ),
                )
            }
            val voiceResult = if (standardVoiceResult.isSuccess) {
                standardVoiceResult
            } else if (nextRelayUrl.isNotBlank() && shouldProbeRelay) {
                RelayVoiceClient(
                    context = getApplication(),
                    okHttpClient = relayOkHttp,
                    relayUrlProvider = { nextRelayUrl },
                    sessionTokenProvider = {
                        (authManager.authState.value as? AuthState.Paired)?.token
                    },
                    apiBearerTokenProvider = { authManager.getApiKey() },
                ).getVoiceConfig()
            } else {
                standardVoiceResult
            }

            if (standardVoiceResult.isFailure && nextRelayUrl.isNotBlank() && shouldProbeRelay) {
                _relayServerHealth.value = if (voiceResult.isSuccess) {
                    HealthStatus.Reachable
                } else {
                    HealthStatus.Unreachable
                }
            }

            onResult(
                ApiVoiceSetupResult(
                    apiReachable = apiReachable,
                    relayUrl = nextRelayUrl.ifBlank { null },
                    relayAutoDerived = usingAutoRelay,
                    voiceConfigReachable = voiceResult.isSuccess,
                    voiceConfigError = voiceResult.exceptionOrNull()?.message,
                    voiceRoute = when {
                        standardVoiceResult.isSuccess -> "standard"
                        voiceResult.isSuccess -> "relay"
                        else -> "none"
                    },
                ),
            )
        }
    }

    fun updateApiKey(key: String) {
        viewModelScope.launch {
            authManager.setApiKey(key)
            rebuildApiClient()
        }
    }

    suspend fun getApiKey(): String? = authManager.getApiKey()

    /**
     * The bearer key for client construction, WITHOUT paying the Keystore
     * decrypt when this connection is known key-less (the common local
     * setup). [AuthManager.apiKeyKnownAbsent] is a plain-prefs hint that
     * defaults to "unknown ⇒ wait", so keyed connections always take the
     * real [AuthManager.getApiKey] path. This is what keeps the API client
     * — and everything behind it: health, capabilities, chat history —
     * from queueing behind a multi-second StrongBox keyset marathon at
     * cold start (measured 15s on an S25 Ultra before this fast path).
     */
    private suspend fun apiKeyForClientBuild(): String =
        if (authManager.apiKeyKnownAbsent()) "" else authManager.getApiKey() ?: ""

    fun checkApiHealth() {
        viewModelScope.launch {
            val client = _apiClient.value
            _apiServerReachable.value = client?.checkHealth() == true
        }
    }

    fun testApiConnection(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val client = _apiClient.value
            if (client == null) {
                _apiServerReachable.value = false
                _apiServerHealth.value = HealthStatus.Unknown
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Api,
                    severity = DiagnosticSeverity.Warning,
                    title = "API test skipped",
                    detail = "No API client configured",
                    url = effectiveApiServerUrlSnapshot(),
                )
                onResult(false, "No API client configured")
                return@launch
            }

            _apiServerHealth.value = HealthStatus.Probing
            DiagnosticsLog.record(
                category = DiagnosticCategory.Api,
                severity = DiagnosticSeverity.Info,
                title = "Testing API connection",
                url = effectiveApiServerUrlSnapshot(),
            )
            val health = client.checkHealthDetailed()
            if (health is com.hermesandroid.relay.network.upstream.HealthCheckResult.Unhealthy) {
                _apiServerReachable.value = false
                _apiServerHealth.value = HealthStatus.Unreachable
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Api,
                    severity = DiagnosticSeverity.Error,
                    title = "API health failed",
                    detail = health.message,
                    url = effectiveApiServerUrlSnapshot(),
                )
                onResult(false, health.message)
                return@launch
            }

            val sessions = client.checkSessionsAuthDetailed()
            val reachable = sessions is com.hermesandroid.relay.network.upstream.HealthCheckResult.Healthy
            _apiServerReachable.value = reachable
            _apiServerHealth.value = if (reachable) HealthStatus.Reachable else HealthStatus.Unreachable
            val message = when (sessions) {
                is com.hermesandroid.relay.network.upstream.HealthCheckResult.Healthy ->
                    "Connection OK - health and sessions auth passed"
                is com.hermesandroid.relay.network.upstream.HealthCheckResult.Unhealthy ->
                    sessions.message
            }
            DiagnosticsLog.record(
                category = DiagnosticCategory.Api,
                severity = if (reachable) DiagnosticSeverity.Info else DiagnosticSeverity.Error,
                title = if (reachable) "API connection ok" else "API auth failed",
                detail = message,
                url = effectiveApiServerUrlSnapshot(),
            )
            onResult(reachable, message)
        }
    }

    private suspend fun rebuildApiClient() {
        val url = effectiveApiServerUrlSnapshot()
        val key = apiKeyForClientBuild()

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
            upstreamTransport.setCapabilitiesAndMode(caps)
            probeStandardVoice()
        } else {
            _apiClient.value = null
            shutdownClientOffMain(oldClient)
            _apiServerReachable.value = false
            _apiServerHealth.value = HealthStatus.Unknown
            upstreamTransport.resetCapabilitiesAndMode()
            probeStandardVoice()
        }
        rebuildChatApiClient()
    }

    private suspend fun rebuildChatApiClient() {
        val baseApiUrl = ProfileApiUrlResolver.normalize(effectiveApiServerUrlSnapshot())
        val profileApiUrl = ProfileApiUrlResolver.resolveForConnection(
            profileApiUrl = profileController.selectedProfile.value?.apiServerUrl,
            baseApiUrl = baseApiUrl,
        )
        val baseClient = _apiClient.value
        val key = apiKeyForClientBuild()

        if (profileApiUrl == null || profileApiUrl == baseApiUrl) {
            val oldProfileClient = profileChatApiClient
            profileChatApiClient = null
            profileChatApiClientUrl = null
            profileChatApiClientKey = null
            _chatApiClient.value = baseClient
            shutdownClientOffMain(oldProfileClient)
            return
        }

        val existingProfileClient = profileChatApiClient
        if (
            existingProfileClient != null &&
            profileChatApiClientUrl == profileApiUrl &&
            profileChatApiClientKey == key
        ) {
            _chatApiClient.value = existingProfileClient
            return
        }

        val nextProfileClient = HermesApiClient(baseUrl = profileApiUrl, apiKey = key)
        profileChatApiClient = nextProfileClient
        profileChatApiClientUrl = profileApiUrl
        profileChatApiClientKey = key
        _chatApiClient.value = nextProfileClient
        shutdownClientOffMain(existingProfileClient)
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
        val trimmed = url.trim()
        _relayUrl.value = trimmed
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_RELAY_URL] = trimmed
            }
            persistActiveConnectionUrls(
                apiServerUrl = _apiServerUrl.value,
                relayUrl = trimmed,
                routeCandidates = mergedRouteCandidates(
                    apiServerUrl = _apiServerUrl.value,
                    relayUrl = trimmed,
                ),
            )
        }
        connectRelayInternal(trimmed)
    }

    fun connectRelay() {
        connectRelayInternal(effectiveRelayUrlSnapshot())
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
            DiagnosticsLog.record(
                category = DiagnosticCategory.Session,
                severity = DiagnosticSeverity.Warning,
                title = "Relay connect skipped",
                detail = "No paired session or pending pair code",
                url = url,
            )
            return
        }
        DiagnosticsLog.record(
            category = DiagnosticCategory.Relay,
            severity = DiagnosticSeverity.Info,
            title = "Relay connect requested",
            url = url,
        )
        connectionManager.connect(url)
    }

    fun disconnectRelay() {
        DiagnosticsLog.record(
            category = DiagnosticCategory.Relay,
            severity = DiagnosticSeverity.Info,
            title = "Relay disconnect requested",
            url = effectiveRelayUrlSnapshot(),
        )
        connectionManager.disconnect()
    }

    // --- ADR 24 — multi-endpoint exposure ------------------------------------

    /**
     * Observe the per-device endpoint-candidate list. Emits an empty list
     * for freshly-upgraded installs (no `endpoints` persisted yet) or for
     * pre-ADR-24 v1/v2 QRs whose synthesizer produced a single candidate
     * — in which case the UI renders a one-row card.
     *
     * The device id is looked up via [AuthManager.getOrCreateDeviceId] so
     * the flow hot-rebinds when the active connection swaps.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeDeviceEndpoints(): kotlinx.coroutines.flow.Flow<List<EndpointCandidate>> {
        return activeConnection.flatMapLatest { connection ->
            val savedRoutes = connection?.routeCandidates.orEmpty()
            if (savedRoutes.isNotEmpty()) {
                kotlinx.coroutines.flow.flowOf(savedRoutes)
            } else {
                kotlinx.coroutines.flow.flow {
                    val deviceId = runCatching { authManager.getOrCreateDeviceId() }.getOrNull()
                    if (deviceId == null) {
                        emit(emptyList())
                        return@flow
                    }
                    emitAll(
                        PairingPreferences.getDeviceEndpoints(getApplication(), deviceId)
                    )
                }
            }
        }
    }

    /**
     * Add or replace an extra fallback route on the active connection — the
     * standard path's manual equivalent of a v3 pairing QR's `endpoints`
     * array. The primary route (priority 0) mirrors the connection's main
     * API URL and is edited through the URL fields / wizard, never here.
     *
     * Legacy candidate sources (per-device PairingPreferences from old QR
     * pairings, or a bare single-URL config) are seeded onto the connection
     * first, so an edit never hides routes the card was already showing.
     *
     * @param original non-null = edit-in-place: the matching stored entry is
     *   replaced and keeps its priority; null = append after the last route.
     * @param onResult called with a user-facing error string, or null on
     *   success — drives the dialog's inline error text.
     */
    fun saveExtraRoute(
        role: String,
        apiUrl: String,
        original: EndpointCandidate? = null,
        onResult: (String?) -> Unit,
    ) {
        if (original?.priority == 0) {
            onResult("The primary route mirrors the connection's API URL — edit that instead")
            return
        }
        viewModelScope.launch {
            val current = activeConnectionSnapshot()
            if (current == null) {
                onResult("No active connection")
                return@launch
            }
            // Accept bare hosts/IPs — http:// is assumed (see
            // [Connection.normalizeApiUrlInput]); the port defaults to 8642
            // downstream in [Connection.endpointCandidateFromApiUrl].
            val trimmedUrl = Connection.normalizeApiUrlInput(apiUrl)
            val existing = seedRouteCandidates(current)
            if (existing.isEmpty()) {
                onResult("Set the connection's API server URL first")
                return@launch
            }
            val withoutOriginal = if (original != null) {
                existing.filterNot { it.sameRouteAs(original) }
            } else {
                existing
            }
            val candidate = Connection.endpointCandidateFromApiUrl(
                role = role.trim().ifBlank { Connection.inferRouteRole(trimmedUrl) },
                priority = original?.priority
                    ?: ((withoutOriginal.maxOfOrNull { it.priority } ?: 0) + 1),
                apiServerUrl = trimmedUrl,
                relayUrl = Connection.deriveDefaultRelayUrl(trimmedUrl).orEmpty(),
            )
            if (candidate == null) {
                onResult(
                    "Enter the API server URL — e.g. 100.64.0.1 or " +
                        "http://host:8642 (http/https only; port defaults to 8642)",
                )
                return@launch
            }
            val collision = withoutOriginal.firstOrNull {
                it.api.host.equals(candidate.api.host, ignoreCase = true) &&
                    it.api.port == candidate.api.port
            }
            if (collision != null) {
                onResult(
                    if (collision.priority == 0) {
                        "That host is already the primary route"
                    } else {
                        "The ${collision.displayLabel()} route already uses that host"
                    },
                )
                return@launch
            }
            val next = (withoutOriginal + candidate)
                .sortedWith(compareBy<EndpointCandidate> { it.priority }.thenBy { it.role })
            connectionStore.updateConnection(current.copy(routeCandidates = next))
            // Full probe cycle (not a bare refresh) so the just-saved route
            // immediately shows a reachability verdict in the Routes card.
            probeNow()
            onResult(null)
        }
    }

    /**
     * Remove an extra fallback route. The primary route (priority 0) is
     * protected — it mirrors the connection's API URL. Clears a preferred-
     * route override that pointed at the removed route, mirroring
     * [persistActiveConnectionUrls]' stale-preference handling.
     */
    fun removeExtraRoute(candidate: EndpointCandidate, onResult: (String?) -> Unit = {}) {
        if (candidate.priority == 0) {
            onResult("The primary route can't be removed — edit the connection's API URL instead")
            return
        }
        viewModelScope.launch {
            val current = activeConnectionSnapshot()
            if (current == null) {
                onResult("No active connection")
                return@launch
            }
            val existing = seedRouteCandidates(current)
            val next = existing.filterNot { it.sameRouteAs(candidate) }
            if (next.size == existing.size) {
                onResult(null)
                return@launch
            }
            val preferredNowStale = current.preferredRouteRole != null &&
                next.none { it.role.equals(current.preferredRouteRole, ignoreCase = true) }
            connectionStore.updateConnection(
                current.copy(
                    routeCandidates = next,
                    preferredRouteRole = if (preferredNowStale) null else current.preferredRouteRole,
                ),
            )
            if (preferredNowStale) {
                connectionManager.setManualRoleOverride(null)
            }
            // Same visible probe cycle as saveExtraRoute — removing the
            // active route should immediately re-resolve and show where the
            // app landed.
            probeNow()
            onResult(null)
        }
    }

    private fun activeConnectionSnapshot(): Connection? {
        val activeId = connectionStore.activeConnectionId.value ?: return null
        return connectionStore.connections.value.firstOrNull { it.id == activeId }
    }

    /**
     * The connection's stored candidates, or — mirroring
     * [observeDeviceEndpoints]' fallback chain — the per-device pairing
     * endpoints, or a primary synthesized from the saved URLs. Whatever the
     * Routes card is currently displaying is what an edit starts from.
     */
    private suspend fun seedRouteCandidates(current: Connection): List<EndpointCandidate> {
        current.routeCandidates.takeIf { it.isNotEmpty() }?.let { return it }
        val fromPairing = runCatching {
            val deviceId = authManager.getOrCreateDeviceId()
            PairingPreferences.getDeviceEndpoints(getApplication(), deviceId).first()
        }.getOrDefault(emptyList())
        if (fromPairing.isNotEmpty()) return fromPairing
        val apiUrl = current.apiServerUrl.takeIf { it.isNotBlank() } ?: return emptyList()
        return Connection.buildRouteCandidates(
            apiServerUrl = apiUrl,
            relayUrl = current.relayUrl,
        )
    }

    private fun EndpointCandidate.sameRouteAs(other: EndpointCandidate): Boolean =
        role.equals(other.role, ignoreCase = true) &&
            api.host.equals(other.api.host, ignoreCase = true) &&
            api.port == other.api.port

    /**
     * Sticky route policy — the Routes card's "Prefer this route". Persists
     * [Connection.preferredRouteRole] (restored as the live override on every
     * connection load / app start) AND installs it via
     * [ConnectionManager.setManualRoleOverride], then kicks [probeNow] so the
     * swap takes effect immediately if the route is reachable. Reachability
     * still gates: an unreachable preferred route falls back through strict
     * priority. Passing `null` clears both the persisted preference and the
     * live override.
     *
     * For a one-time switch that should NOT survive disconnects or restarts,
     * use [useRouteNow] instead.
     */
    fun setPreferredEndpointRole(role: String?) {
        connectionManager.setManualRoleOverride(role)
        viewModelScope.launch {
            val activeId = connectionStore.activeConnectionId.value ?: return@launch
            val current = connectionStore.connections.value.firstOrNull { it.id == activeId }
                ?: return@launch
            connectionStore.updateConnection(
                current.copy(preferredRouteRole = role?.takeIf { it.isNotBlank() }),
            )
        }
        probeNow()
    }

    /**
     * One-time route switch — the Routes card's "Use now". Installs a
     * transient [ConnectionManager.setManualRoleOverride] and re-probes, but
     * does NOT persist anything: the switch dies on the next explicit
     * disconnect and is replaced by the persisted preference (if any) on the
     * next connection load. Passing `null` cancels a manual switch and
     * restores the sticky preference, or full automatic resolution when no
     * preference is set.
     *
     * "Use now" used to route through [setPreferredEndpointRole], so a
     * one-time switch silently became a persistent preference — this split
     * keeps "act now" and "policy" separate.
     */
    fun useRouteNow(role: String?) {
        connectionManager.setManualRoleOverride(
            role ?: activeConnection.value?.preferredRouteRole,
        )
        probeNow()
    }

    /**
     * Live transient override — what [useRouteNow] / preference restoration
     * installed. The Routes card compares this against the persisted
     * preference to label the current route automatic / preferred / manual.
     */
    val manualRouteOverride: StateFlow<String?>
        get() = connectionManager.manualRoleOverrideFlow

    /** The persisted sticky preference only — never the transient override. */
    fun getPreferredEndpointRole(): String? =
        activeConnection.value?.preferredRouteRole

    /**
     * Route-probe lifecycle for the Routes card. [Probing] disables the
     * Re-check affordances and shows progress; [Done] carries the winner (or
     * null when every saved route failed its probe) so the UI can say "no
     * route reachable" out loud instead of sitting on "Resolving" forever.
     */
    sealed interface RouteProbeStatus {
        data object Idle : RouteProbeStatus
        data object Probing : RouteProbeStatus
        data class Done(
            val winner: EndpointCandidate?,
            val atMillis: Long,
        ) : RouteProbeStatus
    }

    private val _routeProbeStatus = MutableStateFlow<RouteProbeStatus>(RouteProbeStatus.Idle)
    val routeProbeStatus: StateFlow<RouteProbeStatus> = _routeProbeStatus.asStateFlow()

    /** Last probe verdict per route — see [EndpointResolver.probeOutcomes]. */
    val routeProbeOutcomes: StateFlow<Map<String, RouteProbeOutcome>> =
        endpointResolver.probeOutcomes

    /** Key into [routeProbeOutcomes] for one route row. */
    fun routeOutcomeKey(candidate: EndpointCandidate): String =
        EndpointResolver.cacheKey(candidate)

    // Set when probeNow() is requested while a probe is already in flight
    // (e.g. "Use now" tapped mid-Re-check) — the override the caller just
    // installed must still win, so the finished probe immediately re-runs
    // once instead of silently dropping the request. Main-thread confined.
    private var routeProbeRerunRequested = false

    /** User-triggered re-probe — Endpoints card's "Probe now" row action. */
    fun probeNow() {
        if (_routeProbeStatus.value is RouteProbeStatus.Probing) {
            routeProbeRerunRequested = true
            return
        }
        _routeProbeStatus.value = RouteProbeStatus.Probing
        viewModelScope.launch {
            try {
                val apiRouteBefore = effectiveApiServerUrlSnapshot()
                // Await the actual resolve (LAN timing out can take 4s+)
                // instead of the old fixed 100ms guess, which always lost the
                // race and left the health probes pointed at the stale route.
                val winner = connectionManager.probeAndReconnectNow()
                if (effectiveApiServerUrlSnapshot() != apiRouteBefore) {
                    rebuildApiClient()
                }
                probeApiHealth()
                probeRelayHealth()
                _routeProbeStatus.value = RouteProbeStatus.Done(
                    winner = winner,
                    atMillis = System.currentTimeMillis(),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                _routeProbeStatus.value = RouteProbeStatus.Idle
                throw e
            } catch (e: Exception) {
                // Never strand the UI in Probing — surface "nothing won" and
                // let the per-route outcomes explain the details.
                _routeProbeStatus.value = RouteProbeStatus.Done(
                    winner = null,
                    atMillis = System.currentTimeMillis(),
                )
                DiagnosticsLog.record(
                    category = DiagnosticCategory.Endpoint,
                    severity = DiagnosticSeverity.Warning,
                    title = "Route re-check failed",
                    detail = e.javaClass.simpleName,
                )
            }
            if (routeProbeRerunRequested) {
                routeProbeRerunRequested = false
                probeNow()
            }
        }
    }

    /**
     * Fetch the TOFU SPKI pin stored for this endpoint's host:port, if any.
     * Used by the Endpoints card's "View pin" row action. Returns null when
     * no pin has been recorded (not-yet-TOFU'd host) or when the cert store
     * is unavailable.
     */
    suspend fun lookupEndpointPin(candidate: com.hermesandroid.relay.data.EndpointCandidate): String? {
        val hostPort = "${candidate.api.host.lowercase()}:${candidate.api.port}"
        val pins = PairingPreferences.getTofuPins(getApplication())
        return pins[hostPort]
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
        val relayUrl = effectiveRelayUrlSnapshot()
        val hasUrl = relayUrl.isNotBlank()
        if (paired && disconnected && hasUrl) {
            connectionManager.connect(relayUrl)
        }
    }

    fun updateRelayUrl(url: String) {
        val trimmed = url.trim()
        _relayUrl.value = trimmed
        // The new URL hasn't been verified yet — flip to Probing so the
        // health badge doesn't show stale Reachable/Unreachable from the
        // old URL while the next periodic tick (or revalidate()) lands.
        _relayServerHealth.value = HealthStatus.Probing
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_RELAY_URL] = trimmed
            }
            persistActiveConnectionUrls(
                apiServerUrl = _apiServerUrl.value,
                relayUrl = trimmed,
                routeCandidates = mergedRouteCandidates(
                    apiServerUrl = _apiServerUrl.value,
                    relayUrl = trimmed,
                ),
            )
            // Kick a fresh probe right now rather than waiting up to 30s
            // for the periodic loop.
            probeRelayHealth(force = true)
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
            persistActiveConnectionUrls(
                apiServerUrl = _apiServerUrl.value,
                relayUrl = trimmed,
                routeCandidates = mergedRouteCandidates(
                    apiServerUrl = _apiServerUrl.value,
                    relayUrl = trimmed,
                ),
            )
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

    private suspend fun persistActiveConnectionUrls(
        apiServerUrl: String,
        relayUrl: String,
        dashboardUrlOverride: String? = null,
        routeCandidates: List<EndpointCandidate>? = null,
        preferredRouteRole: String? = null,
    ) {
        val activeId = connectionStore.activeConnectionId.value ?: return
        val current = connectionStore.connections.value.firstOrNull { it.id == activeId } ?: return
        val nextRouteCandidates = routeCandidates ?: current.routeCandidates
        val nextPreferredRouteRole = when {
            preferredRouteRole != null -> preferredRouteRole.takeIf { it.isNotBlank() }
            routeCandidates != null &&
                current.preferredRouteRole != null &&
                nextRouteCandidates.none {
                    it.role.equals(current.preferredRouteRole, ignoreCase = true)
                } -> null
            else -> current.preferredRouteRole
        }
        val nextDashboardUrl = when {
            dashboardUrlOverride != null -> {
                dashboardUrlOverride
                    .trim()
                    .trimEnd('/')
                    .takeIf { it.isNotBlank() }
                    ?: Connection.deriveDefaultDashboardUrl(apiServerUrl)
            }
            Connection.isAutoManagedDashboardUrl(current.dashboardUrl, current.apiServerUrl) -> {
                Connection.deriveDefaultDashboardUrl(apiServerUrl)
            }
            else -> current.dashboardUrl
        }
        if (
            current.apiServerUrl == apiServerUrl &&
            current.relayUrl == relayUrl &&
            current.dashboardUrl == nextDashboardUrl &&
            current.routeCandidates == nextRouteCandidates &&
            current.preferredRouteRole == nextPreferredRouteRole
        ) {
            return
        }
        connectionStore.updateConnection(
            current.copy(
                apiServerUrl = apiServerUrl,
                relayUrl = relayUrl,
                dashboardUrl = nextDashboardUrl,
                routeCandidates = nextRouteCandidates,
                preferredRouteRole = nextPreferredRouteRole,
            ),
        )
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

    /** Dev/test hook (Developer options → Test harness): show What's New now. */
    fun showWhatsNewNow() {
        _showWhatsNew.value = true
    }

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
        val connectionId = activeConnectionId.value
        val profileName = profileController.selectedProfile.value?.name
        viewModelScope.launch {
            if (connectionId != null) {
                if (sessionId != null) {
                    // Bucket by the id's own namespace: the prefix is the server's
                    // ground truth about which transport can resume it, robust to a
                    // turn that fell back from gateway to SSE.
                    val transport = SessionTransport.forSessionId(sessionId)
                    profileController.profileSessionStore.setSessionId(
                        connectionId,
                        profileName,
                        transport,
                        sessionId,
                    )
                } else {
                    // A null clears only the ACTIVE transport's slot, and only when
                    // that transport is known. A null while the gateway probe is
                    // still pending (transport == null) — or right after a switch,
                    // when availability is reset to Unknown — is a deferred-restore
                    // transient forwarded by switchProfileContext, NOT a user clear;
                    // clearing then would wipe a still-valid session before the
                    // availability collector restores it.
                    profileController.activeSessionTransport()?.let { transport ->
                        profileController.profileSessionStore.setSessionId(
                            connectionId,
                            profileName,
                            transport,
                            null,
                        )
                    }
                }
            }
            if (profileName == null) {
                getApplication<Application>().relayDataStore.edit { preferences ->
                    if (sessionId != null) {
                        preferences[KEY_LAST_SESSION_ID] = sessionId
                    } else {
                        preferences.remove(KEY_LAST_SESSION_ID)
                    }
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

    fun setAppTheme(themeId: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_APP_THEME] = themeId
            }
        }
    }

    fun setSphereSkin(skinId: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_SPHERE_SKIN] = skinId
            }
        }
    }

    fun setAgentAvatar(avatarId: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_AGENT_AVATAR] = avatarId
            }
        }
    }

    /** Set the global pet playback-speed multiplier (clamped 0.5×–1.5×). */
    fun setPetSpeed(speed: Float) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_PET_SPEED] = speed.coerceIn(0.5f, 1.5f)
            }
        }
    }

    /** Toggle per-frame stabilization (auto-recenter) for pets. */
    fun setPetStabilize(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_PET_STABILIZE] = enabled
            }
        }
    }

    /** Import a pet from a user-picked `.zip` pack or a single image, then refresh. */
    fun importPet(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                PetImporter.importUri(getApplication<Application>(), uri)
            }
            when (result) {
                is PetImportResult.Success -> {
                    refreshAgentAvatars()
                    _avatarEvents.tryEmit("Imported “${result.label}”")
                }
                is PetImportResult.Failure -> _avatarEvents.tryEmit(result.reason)
            }
        }
    }

    /** Delete a user pet by id; if it was the selected avatar, fall back to the sphere. */
    fun deleteUserAvatar(avatarId: String, label: String) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                PetLoader.deletePet(getApplication<Application>(), avatarId)
            }
            if (deleted) {
                if (agentAvatar.value == avatarId) setAgentAvatar(SphereAvatar.id)
                refreshAgentAvatars()
                _avatarEvents.tryEmit("Removed “$label”")
            } else {
                _avatarEvents.tryEmit("Couldn’t remove “$label”")
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
            authManager.clearSession()
            authManager.clearApiKey()
            dataManager.resetAppData()
            profileController.profileSelectionStore.clearAll()
            profileController.profileLockStore.clearAll()
            profileController.profileSessionStore.clearAll()
            _apiServerUrl.value = ""
            _relayUrl.value = ""
            rebuildApiClient()
            shutdownClientOffMain(profileChatApiClient)
            profileChatApiClient = null
            profileChatApiClientUrl = null
            profileChatApiClientKey = null
            profileController.clearSelectionState()
            _lastSessionId.value = null
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
            if (backup.connections.isNotEmpty()) {
                dataManager.restoreConnectionBackup(backup)
                connectionStore.activeConnection.value?.let { restored ->
                    restorePersistedActiveConnectionContext(restored)
                }
            } else {
                // Prefer v2 fields, fall back to v1 serverUrl for relay
                val importedRelayUrl = backup.relayUrl ?: backup.serverUrl
                importedRelayUrl?.let { updateRelayUrl(it) }
                backup.apiServerUrl?.let { updateApiServerUrl(it) }
            }
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
    //
    // Owned by [pairingController]; these functions preserve the public
    // surface unchanged.

    fun loadPairedDevices() = pairingController.loadPairedDevices()

    suspend fun revokeDevice(tokenPrefix: String): Boolean =
        pairingController.revokeDevice(tokenPrefix)

    suspend fun extendDevice(tokenPrefix: String, ttlSeconds: Long): Boolean =
        pairingController.extendDevice(tokenPrefix, ttlSeconds)

    suspend fun revokeChannelGrant(
        tokenPrefix: String,
        channel: String,
    ): Boolean = pairingController.revokeChannelGrant(tokenPrefix, channel)

    // --- Insecure-ack helpers ---------------------------------------------
    //
    // Owned by [pairingController]; getters/function preserve the public
    // surface unchanged. [applyPairingPayload] reads [insecureReason].value.

    val insecureAckSeen: StateFlow<Boolean> get() = pairingController.insecureAckSeen

    val insecureReason: StateFlow<String> get() = pairingController.insecureReason

    fun setInsecureAckComplete(reason: String) = pairingController.setInsecureAckComplete(reason)

    override fun onCleared() {
        super.onCleared()
        // onCleared runs on the main thread, but every client's shutdown()
        // routes ConnectionPool.evictAll() (a synchronous TLS socket close /
        // network write) off the main thread internally via
        // shutdownOffMainThread, so these direct calls can't trip
        // NetworkOnMainThreadException on live SSL sockets.
        connectionManager.shutdown()
        _apiClient.value?.shutdown()
        profileChatApiClient?.shutdown()
        tailscaleDetector.shutdown()
        // Release the cached VirtualDisplay + ImageReader + HandlerThread
        // built by ScreenCapture on the first /screenshot call. Without
        // this, a process-rare VM teardown would leak the capture pipeline
        // until the OS cleans up on exit.
        runCatching { screenCapture?.releaseCache() }
    }
}
