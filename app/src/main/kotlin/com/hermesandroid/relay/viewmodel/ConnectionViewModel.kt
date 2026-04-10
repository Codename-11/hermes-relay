package com.hermesandroid.relay.viewmodel

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.auth.AuthManager
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.DataManager
import com.hermesandroid.relay.data.relayDataStore
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.ConnectivityObserver
import com.hermesandroid.relay.network.ChatMode
import com.hermesandroid.relay.network.ConnectionManager
import com.hermesandroid.relay.network.ConnectionState
import com.hermesandroid.relay.network.HermesApiClient
import com.hermesandroid.relay.network.handlers.ChatHandler
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    private val connectionManager = ConnectionManager(multiplexer)
    val authManager = AuthManager(application, multiplexer, viewModelScope)

    // Data management
    val dataManager = DataManager(application)

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

    private val _apiClient = MutableStateFlow<HermesApiClient?>(null)
    val apiClient: StateFlow<HermesApiClient?> = _apiClient.asStateFlow()

    private val _chatMode = MutableStateFlow(ChatMode.DISCONNECTED)
    val chatMode: StateFlow<ChatMode> = _chatMode.asStateFlow()

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

    // Onboarding state
    private val _onboardingCompleted = MutableStateFlow(true) // default true to avoid flash
    val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted.asStateFlow()

    // Pairing code from AuthManager
    val pairingCode: StateFlow<String> = authManager.pairingCode

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

    // Streaming endpoint: "sessions" = /api/sessions/{id}/chat/stream, "runs" = /v1/runs
    val streamingEndpoint: StateFlow<String> = application.relayDataStore.data
        .map { it[KEY_STREAMING_ENDPOINT] ?: "sessions" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "sessions")

    fun setStreamingEndpoint(endpoint: String) {
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { prefs ->
                prefs[KEY_STREAMING_ENDPOINT] = endpoint
            }
        }
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

    init {
        // Wire multiplexer to connection manager (for relay/bridge/terminal)
        multiplexer.setSendCallback { envelope ->
            connectionManager.send(envelope)
        }

        // Auto-authenticate on relay connect
        multiplexer.setOnConnectedCallback {
            authManager.authenticate()
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

        // Periodic health check — only runs when an API client is configured
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                if (_apiClient.value != null) {
                    checkApiHealth()
                }
            }
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
            val client = HermesApiClient(baseUrl = url, apiKey = key)
            _apiClient.value = client
            oldClient?.shutdown()
            _apiServerReachable.value = client.checkHealth()

            // Detect chat mode
            val mode = client.detectChatMode()
            _chatMode.value = mode
        } else {
            _apiClient.value = null
            oldClient?.shutdown()
            _apiServerReachable.value = false
            _chatMode.value = ChatMode.DISCONNECTED
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
        connectionManager.connect(url)
    }

    fun connectRelay() {
        connectionManager.connect(_relayUrl.value)
    }

    fun disconnectRelay() {
        connectionManager.disconnect()
    }

    fun updateRelayUrl(url: String) {
        _relayUrl.value = url
        viewModelScope.launch {
            getApplication<Application>().relayDataStore.edit { preferences ->
                preferences[KEY_RELAY_URL] = url
            }
        }
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

    fun clearSession() {
        authManager.clearSession()
    }

    override fun onCleared() {
        super.onCleared()
        connectionManager.shutdown()
        _apiClient.value?.shutdown()
    }
}
