package com.hermesandroid.relay.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Persists one independent [SupervisedModePolicy] per Hermes connection. */
class SupervisedModeStore private constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.relayDataStore)

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val serializer = MapSerializer(String.serializer(), SupervisedModePolicy.serializer())

    fun policyFlow(connectionId: String): Flow<SupervisedModePolicy> =
        dataStore.data.map { preferences ->
            val decoded = decode(preferences[KEY_POLICIES])
            if (decoded.corrupt) {
                // A malformed persisted policy must never silently reopen the
                // unrestricted app. Enabled + unconfigured renders the
                // supervised recovery surface until an authenticated user
                // repairs or clears the policy.
                SupervisedModePolicy(enabled = true)
            } else {
                decoded.policies[connectionId]?.normalized() ?: SupervisedModePolicy()
            }
        }

    suspend fun setPolicy(connectionId: String, policy: SupervisedModePolicy) {
        require(connectionId.isNotBlank()) { "connectionId must not be blank" }
        dataStore.edit { preferences ->
            val policies = decode(preferences[KEY_POLICIES]).policies.toMutableMap()
            policies[connectionId] = policy.normalized()
            preferences[KEY_POLICIES] = json.encodeToString(serializer, policies)
        }
    }

    suspend fun updatePolicy(
        connectionId: String,
        transform: (SupervisedModePolicy) -> SupervisedModePolicy,
    ) {
        require(connectionId.isNotBlank()) { "connectionId must not be blank" }
        dataStore.edit { preferences ->
            val policies = decode(preferences[KEY_POLICIES]).policies.toMutableMap()
            val current = policies[connectionId]?.normalized() ?: SupervisedModePolicy()
            policies[connectionId] = transform(current).normalized()
            preferences[KEY_POLICIES] = json.encodeToString(serializer, policies)
        }
    }

    suspend fun setEnabled(connectionId: String, enabled: Boolean) {
        updatePolicy(connectionId) { it.copy(enabled = enabled) }
    }

    suspend fun clear(connectionId: String) {
        dataStore.edit { preferences ->
            val policies = decode(preferences[KEY_POLICIES]).policies.toMutableMap()
            policies.remove(connectionId)
            if (policies.isEmpty()) {
                preferences.remove(KEY_POLICIES)
            } else {
                preferences[KEY_POLICIES] = json.encodeToString(serializer, policies)
            }
        }
    }

    /** Clear supervised policies without disturbing unrelated app settings. */
    suspend fun clearAll() {
        dataStore.edit { preferences -> preferences.remove(KEY_POLICIES) }
    }

    private fun decode(raw: String?): DecodeResult {
        if (raw.isNullOrBlank()) return DecodeResult(emptyMap(), corrupt = false)
        return try {
            DecodeResult(json.decodeFromString(serializer, raw), corrupt = false)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to decode supervised-mode policies; failing closed", error)
            DecodeResult(emptyMap(), corrupt = true)
        }
    }

    private data class DecodeResult(
        val policies: Map<String, SupervisedModePolicy>,
        val corrupt: Boolean,
    )

    internal companion object {
        private const val TAG = "SupervisedModeStore"
        private val KEY_POLICIES = stringPreferencesKey("supervised_mode_policies_v1")

        fun forTesting(dataStore: DataStore<Preferences>): SupervisedModeStore =
            SupervisedModeStore(dataStore)
    }
}
