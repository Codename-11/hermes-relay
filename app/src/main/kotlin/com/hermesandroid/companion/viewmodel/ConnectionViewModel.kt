package com.hermesandroid.companion.viewmodel

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.companion.auth.AuthManager
import com.hermesandroid.companion.auth.AuthState
import com.hermesandroid.companion.data.DataManager
import com.hermesandroid.companion.data.companionDataStore
import com.hermesandroid.companion.network.ChannelMultiplexer
import com.hermesandroid.companion.network.ConnectionManager
import com.hermesandroid.companion.network.ConnectionState
import com.hermesandroid.companion.network.handlers.ChatHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_INSECURE_MODE = booleanPreferencesKey("insecure_mode")
        private const val DEFAULT_URL = "wss://localhost:8767"
    }

    // Core networking components
    val multiplexer = ChannelMultiplexer()
    val chatHandler = ChatHandler()
    private val connectionManager = ConnectionManager(multiplexer)
    val authManager = AuthManager(application, multiplexer, viewModelScope)

    // Data management
    val dataManager = DataManager(application)

    // Connection state
    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val authState: StateFlow<AuthState> = authManager.authState
    val insecureMode: StateFlow<Boolean> = connectionManager.insecureMode
    val isInsecureConnection: StateFlow<Boolean> = connectionManager.isInsecureConnection

    // Server URL
    private val _serverUrl = MutableStateFlow(DEFAULT_URL)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    // Theme preference
    val theme: StateFlow<String> = application.companionDataStore.data
        .map { preferences ->
            preferences[KEY_THEME] ?: "auto"
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

    // Onboarding state
    private val _onboardingCompleted = MutableStateFlow(true) // default true to avoid flash
    val onboardingCompleted: StateFlow<Boolean> = _onboardingCompleted.asStateFlow()

    // Pairing code from AuthManager
    val pairingCode: StateFlow<String> = authManager.pairingCode

    init {
        // Register chat handler
        multiplexer.registerHandler("chat", chatHandler)

        // Wire multiplexer to connection manager
        multiplexer.setSendCallback { envelope ->
            connectionManager.send(envelope)
        }

        // Auto-authenticate on connect
        multiplexer.setOnConnectedCallback {
            authManager.authenticate()
        }

        // Load saved state
        viewModelScope.launch {
            // Load onboarding state
            _onboardingCompleted.value = dataManager.isOnboardingCompleted()

            // Load saved preferences
            application.companionDataStore.data.collect { preferences ->
                // Restore insecure mode
                val insecure = preferences[KEY_INSECURE_MODE] ?: false
                connectionManager.setInsecureMode(insecure)
                val savedUrl = preferences[KEY_SERVER_URL]
                if (savedUrl != null) {
                    _serverUrl.value = savedUrl
                }
            }
        }
    }

    fun connect(url: String) {
        _serverUrl.value = url
        viewModelScope.launch {
            getApplication<Application>().companionDataStore.edit { preferences ->
                preferences[KEY_SERVER_URL] = url
            }
        }
        connectionManager.connect(url)
    }

    fun connect() {
        connectionManager.connect(_serverUrl.value)
    }

    fun disconnect() {
        connectionManager.disconnect()
    }

    fun updateServerUrl(url: String) {
        _serverUrl.value = url
        viewModelScope.launch {
            getApplication<Application>().companionDataStore.edit { preferences ->
                preferences[KEY_SERVER_URL] = url
            }
        }
    }

    fun setTheme(theme: String) {
        viewModelScope.launch {
            getApplication<Application>().companionDataStore.edit { preferences ->
                preferences[KEY_THEME] = theme
            }
        }
    }

    fun setInsecureMode(enabled: Boolean) {
        connectionManager.setInsecureMode(enabled)
        viewModelScope.launch {
            getApplication<Application>().companionDataStore.edit { preferences ->
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
            disconnect()
            dataManager.resetAppData()
            _serverUrl.value = DEFAULT_URL
        }
    }

    fun exportSettings(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val json = dataManager.exportSettings(
                serverUrl = _serverUrl.value,
                theme = theme.value,
                onboardingCompleted = _onboardingCompleted.value,
                profiles = authManager.profiles.value
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
            backup.serverUrl?.let { updateServerUrl(it) }
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
        connectionManager.disconnect()
    }
}
