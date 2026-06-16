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
 * Local-only display aliases for agent profiles.
 *
 * These names are phone UI labels. They are never sent to Hermes and are keyed
 * by connection + profile context so the server-default agent can be called
 * something different on each configured Hermes host.
 */
class ProfileDisplayAliasStore(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.profileDisplayAliasesDataStore)

    companion object {
        private const val PREFIX = "profile_alias__"

        private fun keyName(connectionId: String, profileName: String?): String =
            "$PREFIX${connectionId}__${AgentDisplay.profileSessionKey(profileName)}"

        private fun keyFor(connectionId: String, profileName: String?) =
            stringPreferencesKey(keyName(connectionId, profileName))

        private fun connectionPrefix(connectionId: String): String =
            "$PREFIX${connectionId}__"
    }

    suspend fun setAlias(connectionId: String, profileName: String?, alias: String?) {
        dataStore.edit { prefs ->
            val key = keyFor(connectionId, profileName)
            if (alias.isNullOrBlank()) {
                prefs.remove(key)
            } else {
                prefs[key] = alias
            }
        }
    }

    fun aliasFlow(connectionId: String, profileName: String?): Flow<String?> {
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
        dataStore.edit { prefs -> prefs.clear() }
    }
}

internal val Context.profileDisplayAliasesDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "profile_display_aliases")
