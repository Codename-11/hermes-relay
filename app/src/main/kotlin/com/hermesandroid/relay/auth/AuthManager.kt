package com.hermesandroid.relay.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.models.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed class AuthState {
    data object Unpaired : AuthState()
    data object Pairing : AuthState()
    data class Paired(val token: String) : AuthState()
    data class Failed(val reason: String) : AuthState()
}

class AuthManager(
    private val context: Context,
    private val multiplexer: ChannelMultiplexer,
    private val scope: CoroutineScope
) : ChannelMultiplexer.ChannelHandler {

    companion object {
        private const val PREFS_NAME = "hermes_companion_auth"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_API_KEY = "api_server_key"
        private const val PAIRING_CODE_LENGTH = 6
        private val PAIRING_CODE_CHARS = ('A'..'Z') + ('0'..'9')
    }

    private val json = Json { ignoreUnknownKeys = true }

    // Thread-safe lazy-init crypto on first access (off main thread)
    private var _prefs: SharedPreferences? = null
    private val prefsMutex = Mutex()
    private suspend fun prefs(): SharedPreferences {
        _prefs?.let { return it }
        return prefsMutex.withLock {
            // Double-check inside lock
            _prefs?.let { return it }
            withContext(Dispatchers.IO) {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { _prefs = it }
            }
        }
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unpaired)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _pairingCode = MutableStateFlow(generatePairingCode())
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

    private val _profiles = MutableStateFlow<List<String>>(emptyList())
    val profiles: StateFlow<List<String>> = _profiles.asStateFlow()

    init {
        // Register as system channel handler for auth messages
        multiplexer.registerHandler("system", this)

        // Check for existing session token off main thread
        scope.launch {
            val existingToken = prefs().getString(KEY_SESSION_TOKEN, null)
            if (existingToken != null) {
                _authState.value = AuthState.Paired(existingToken)
            }
        }
    }

    private suspend fun getDeviceId(): String {
        val p = prefs()
        val existing = p.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val newId = java.util.UUID.randomUUID().toString()
        p.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    /**
     * Send auth envelope when connection is established.
     */
    fun authenticate() {
        scope.launch {
            val currentState = _authState.value
            val deviceId = getDeviceId()
            val payload = when (currentState) {
                is AuthState.Paired -> {
                    buildJsonObject {
                        put("session_token", currentState.token)
                        put("device_id", deviceId)
                        put("device_name", android.os.Build.MODEL)
                    }
                }
                else -> {
                    _authState.value = AuthState.Pairing
                    buildJsonObject {
                        put("pairing_code", _pairingCode.value)
                        put("device_id", deviceId)
                        put("device_name", android.os.Build.MODEL)
                    }
                }
            }

            multiplexer.send(
                Envelope(
                    channel = "system",
                    type = "auth",
                    payload = payload
                )
            )
        }
    }

    override fun onMessage(envelope: Envelope) {
        when (envelope.type) {
            "auth.ok" -> handleAuthOk(envelope)
            "auth.fail" -> handleAuthFail(envelope)
        }
    }

    fun regeneratePairingCode() {
        _pairingCode.value = generatePairingCode()
    }

    fun clearSession() {
        scope.launch {
            prefs().edit().remove(KEY_SESSION_TOKEN).apply()
            _authState.value = AuthState.Unpaired
            _pairingCode.value = generatePairingCode()
        }
    }

    // --- API Key storage (for direct Hermes API Server auth) ---

    suspend fun getApiKey(): String? {
        return prefs().getString(KEY_API_KEY, null)
    }

    suspend fun setApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) {
            prefs().edit().remove(KEY_API_KEY).apply()
        } else {
            prefs().edit().putString(KEY_API_KEY, trimmed).apply()
        }
    }

    suspend fun clearApiKey() {
        prefs().edit().remove(KEY_API_KEY).apply()
    }

    val isPaired: Boolean
        get() = _authState.value is AuthState.Paired

    private fun handleAuthOk(envelope: Envelope) {
        scope.launch {
            try {
                val payload = envelope.payload
                val token = payload["session_token"]?.jsonPrimitive?.contentOrNull

                if (token != null) {
                    prefs().edit().putString(KEY_SESSION_TOKEN, token).apply()
                    _authState.value = AuthState.Paired(token)
                }

                val profilesArray = payload["profiles"]?.jsonArray
                if (profilesArray != null) {
                    _profiles.value = profilesArray.map { it.jsonPrimitive.content }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleAuthFail(envelope: Envelope) {
        try {
            val reason = envelope.payload["reason"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
            _authState.value = AuthState.Failed(reason)
        } catch (e: Exception) {
            _authState.value = AuthState.Failed("Authentication failed")
        }
    }

    private fun generatePairingCode(): String {
        return (1..PAIRING_CODE_LENGTH)
            .map { PAIRING_CODE_CHARS.random() }
            .joinToString("")
    }
}
