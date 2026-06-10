package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Per-connection, per-Hermes-profile last active chat session.
 *
 * This is intentionally separate from [ProfileSelectionStore]. Selection says
 * which agent is active; this store says which chat session belongs to that
 * agent on that connection. Null profile name is the explicit Server default
 * context.
 */
class ProfileSessionStore(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.profileSessionsDataStore)

    companion object {
        private const val PREFIX = "profile_session__"

        private fun keyName(connectionId: String, profileName: String?): String =
            "$PREFIX${connectionId}__${AgentDisplay.profileSessionKey(profileName)}"

        private fun keyFor(connectionId: String, profileName: String?) =
            stringPreferencesKey(keyName(connectionId, profileName))

        private fun connectionPrefix(connectionId: String): String =
            "$PREFIX${connectionId}__"
    }

    suspend fun setSessionId(
        connectionId: String,
        profileName: String?,
        sessionId: String?,
    ) {
        dataStore.edit { prefs ->
            val key = keyFor(connectionId, profileName)
            if (sessionId.isNullOrBlank()) {
                prefs.remove(key)
            } else {
                prefs[key] = sessionId
            }
        }
    }

    fun sessionIdFlow(connectionId: String, profileName: String?): Flow<String?> {
        val key = keyFor(connectionId, profileName)
        return dataStore.data.map { prefs -> prefs[key] }
    }

    suspend fun clearConnection(connectionId: String) {
        val prefix = connectionPrefix(connectionId)
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(prefix) }
                .forEach { prefs.remove(it) }
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}

internal val Context.profileSessionsDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "profile_sessions")
