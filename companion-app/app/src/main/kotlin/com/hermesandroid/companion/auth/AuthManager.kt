package com.hermesandroid.companion.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.hermesandroid.companion.network.ChannelMultiplexer
import com.hermesandroid.companion.network.models.Envelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    context: Context,
    private val multiplexer: ChannelMultiplexer
) : ChannelMultiplexer.ChannelHandler {

    companion object {
        private const val PREFS_NAME = "hermes_companion_auth"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val PAIRING_CODE_LENGTH = 6
        private val PAIRING_CODE_CHARS = ('A'..'Z') + ('0'..'9')
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unpaired)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _pairingCode = MutableStateFlow(generatePairingCode())
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

    private val _profiles = MutableStateFlow<List<String>>(emptyList())
    val profiles: StateFlow<List<String>> = _profiles.asStateFlow()

    private val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (existing != null) return existing
            val newId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            return newId
        }

    init {
        // Check for existing session token
        val existingToken = prefs.getString(KEY_SESSION_TOKEN, null)
        if (existingToken != null) {
            _authState.value = AuthState.Paired(existingToken)
        }

        // Register as system channel handler for auth messages
        multiplexer.registerHandler("system", this)
    }

    /**
     * Send auth envelope when connection is established.
     */
    fun authenticate() {
        val currentState = _authState.value
        val payload = when (currentState) {
            is AuthState.Paired -> {
                // Re-auth with existing token
                buildJsonObject {
                    put("session_token", currentState.token)
                    put("device_id", deviceId)
                    put("device_name", android.os.Build.MODEL)
                }
            }
            else -> {
                // Auth with pairing code
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
        prefs.edit().remove(KEY_SESSION_TOKEN).apply()
        _authState.value = AuthState.Unpaired
        _pairingCode.value = generatePairingCode()
    }

    val isPaired: Boolean
        get() = _authState.value is AuthState.Paired

    private fun handleAuthOk(envelope: Envelope) {
        try {
            val payload = envelope.payload
            val token = payload["session_token"]?.jsonPrimitive?.contentOrNull

            if (token != null) {
                prefs.edit().putString(KEY_SESSION_TOKEN, token).apply()
                _authState.value = AuthState.Paired(token)
            }

            // Extract profiles if present
            val profilesArray = payload["profiles"]?.jsonArray
            if (profilesArray != null) {
                _profiles.value = profilesArray.map { it.jsonPrimitive.content }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
