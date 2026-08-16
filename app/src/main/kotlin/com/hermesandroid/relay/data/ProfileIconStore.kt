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
 * Per-profile icon cache. Local fallback paths and Hermes-owned avatar cache
 * paths use separate keys so syncing either side never silently overwrites the other.
 *
 * Stores a **file path** to an image that was copied into app storage (not a SAF
 * content URI, so it survives without a persistable-permission grant). Like the
 * name alias, these are phone-UI labels only: never sent to Hermes, and keyed by
 * connection + profile context so the same server-default agent can wear a
 * different face on each configured host.
 */
class ProfileIconStore(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.profileIconsDataStore)

    companion object {
        private const val PREFIX = "profile_icon__"
        private const val SERVER_PREFIX = "profile_avatar_server__"

        private fun keyName(connectionId: String, profileName: String?): String =
            "$PREFIX${connectionId}__${AgentDisplay.profileSessionKey(profileName)}"

        private fun keyFor(connectionId: String, profileName: String?) =
            stringPreferencesKey(keyName(connectionId, profileName))

        private fun connectionPrefix(connectionId: String): String =
            "$PREFIX${connectionId}__"

        private fun serverKeyFor(connectionId: String, profileName: String) =
            stringPreferencesKey("$SERVER_PREFIX${connectionId}__${AgentDisplay.profileSessionKey(profileName)}")

        private fun serverConnectionPrefix(connectionId: String): String =
            "$SERVER_PREFIX${connectionId}__"
    }

    suspend fun setIcon(connectionId: String, profileName: String?, path: String?) {
        dataStore.edit { prefs ->
            val key = keyFor(connectionId, profileName)
            if (path.isNullOrBlank()) {
                prefs.remove(key)
            } else {
                prefs[key] = path
            }
        }
    }

    fun iconFlow(connectionId: String, profileName: String?): Flow<String?> {
        val key = keyFor(connectionId, profileName)
        return dataStore.data.map { prefs -> prefs[key] }
    }

    suspend fun setServerAvatar(connectionId: String, profileName: String, path: String?) {
        dataStore.edit { prefs ->
            val key = serverKeyFor(connectionId, profileName)
            if (path.isNullOrBlank()) prefs.remove(key) else prefs[key] = path
        }
    }

    fun serverAvatarFlow(connectionId: String, profileName: String): Flow<String?> {
        val key = serverKeyFor(connectionId, profileName)
        return dataStore.data.map { prefs -> prefs[key] }
    }

    suspend fun clearConnection(connectionId: String) {
        val prefixes = listOf(connectionPrefix(connectionId), serverConnectionPrefix(connectionId))
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { key -> prefixes.any(key.name::startsWith) }
                .forEach { prefs.remove(it) }
        }
    }

    suspend fun clearAll() {
        dataStore.edit { prefs -> prefs.clear() }
    }
}

internal val Context.profileIconsDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "profile_icons")
