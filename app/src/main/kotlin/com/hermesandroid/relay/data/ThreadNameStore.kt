package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.threadNameDataStore by preferencesDataStore(name = "thread_names")
private val KEY_NAMES = stringPreferencesKey("names_json")

/**
 * Persists user-chosen agent **Thread** names (`sessionId` → name) so a named
 * Thread keeps its name across app restarts — the user's name is authoritative
 * (Discord-style), overriding the gateway's async auto-title which would
 * otherwise clobber it. Applied to the drawer via
 * [com.hermesandroid.relay.network.upstream.ChatHandler.setUserThreadNames].
 */
class ThreadNameStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val ser = MapSerializer(String.serializer(), String.serializer())

    private fun decode(raw: String?): Map<String, String> =
        raw?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() } ?: emptyMap()

    val names: Flow<Map<String, String>> = context.threadNameDataStore.data.map { prefs ->
        decode(prefs[KEY_NAMES])
    }

    suspend fun setName(sessionId: String, name: String) {
        val id = sessionId.trim()
        val value = name.trim()
        if (id.isBlank() || value.isBlank()) return
        context.threadNameDataStore.edit { prefs ->
            prefs[KEY_NAMES] = json.encodeToString(ser, decode(prefs[KEY_NAMES]) + (id to value))
        }
    }
}
