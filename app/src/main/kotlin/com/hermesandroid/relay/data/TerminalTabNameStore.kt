package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Friendly-name store for terminal tabs, keyed by the stable wire-side
 * `session_name` (e.g. `hermes-<deviceId>-tabN`). Cosmetic-only — the name
 * never crosses the wire; tmux reattach + server-side bookkeeping still use
 * the opaque session name.
 *
 * Keyed on `session_name` because that's also what tmux keys on — if the
 * user detaches, restarts the app, and reattaches, the same name re-binds
 * to the same tmux session. Kill explicitly clears the name (via
 * [clearName]) because the underlying session is destroyed; detach leaves
 * the name intact as a "come back to this later" hint.
 */
class TerminalTabNameStore(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.relayDataStore)

    companion object {
        private val KEY_NAMES_JSON = stringPreferencesKey("terminal_tab_names_json")
        private val JSON = Json { ignoreUnknownKeys = true }
    }

    val namesFlow: Flow<Map<String, String>> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_NAMES_JSON] ?: return@map emptyMap()
        runCatching { JSON.decodeFromString<Map<String, String>>(raw) }
            .getOrDefault(emptyMap())
    }

    /** Set or clear (null / blank) the friendly name for [sessionName]. */
    suspend fun setName(sessionName: String, displayName: String?) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_NAMES_JSON]
                ?.let { runCatching { JSON.decodeFromString<Map<String, String>>(it) }.getOrNull() }
                ?: emptyMap()
            val next = if (displayName.isNullOrBlank()) {
                current - sessionName
            } else {
                current + (sessionName to displayName.trim().take(MAX_NAME_LEN))
            }
            prefs[KEY_NAMES_JSON] = JSON.encodeToString(next)
        }
    }

    /** Remove the entry for a session that's being forcibly retired (e.g. kill). */
    suspend fun clearName(sessionName: String) = setName(sessionName, null)
}

private const val MAX_NAME_LEN = 40
