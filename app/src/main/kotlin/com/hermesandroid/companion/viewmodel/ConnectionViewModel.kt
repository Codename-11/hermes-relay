package com.hermesandroid.companion.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.companion.auth.AuthManager
import com.hermesandroid.companion.auth.AuthState
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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "companion_settings")

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private val KEY_SERVER_URL = stringPreferencesKey("server_url")
        private val KEY_THEME = stringPreferencesKey("theme")
        private const val DEFAULT_URL = "wss://localhost:8767"
    }

    // Core networking components
    val multiplexer = ChannelMultiplexer()
    val chatHandler = ChatHandler()
    private val connectionManager = ConnectionManager(multiplexer)
    val authManager = AuthManager(application, multiplexer)

    // Connection state
    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val authState: StateFlow<AuthState> = authManager.authState

    // Server URL
    private val _serverUrl = MutableStateFlow(DEFAULT_URL)
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    // Theme preference
    val theme: StateFlow<String> = application.dataStore.data
        .map { preferences ->
            preferences[KEY_THEME] ?: "auto"
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")

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

        // Load saved server URL
        viewModelScope.launch {
            application.dataStore.data.collect { preferences ->
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
            getApplication<Application>().dataStore.edit { preferences ->
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
            getApplication<Application>().dataStore.edit { preferences ->
                preferences[KEY_SERVER_URL] = url
            }
        }
    }

    fun setTheme(theme: String) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { preferences ->
                preferences[KEY_THEME] = theme
            }
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
