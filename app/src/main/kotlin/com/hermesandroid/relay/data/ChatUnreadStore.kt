package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ChatCompletionReceipt(
    val contextKey: String,
    val sessionId: String,
    val turnId: String,
    val completedAt: Long,
    val unread: Boolean,
)

/** Local read receipts only; no transcript, prompt, or notification body is stored here. */
class ChatUnreadStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.chatUnreadDataStore)
    private val key = stringPreferencesKey("completion_receipts_v1")
    private val json = Json { ignoreUnknownKeys = true }
    private fun read(prefs: Preferences): List<ChatCompletionReceipt> =
        runCatching { json.decodeFromString<List<ChatCompletionReceipt>>(prefs[key] ?: "[]") }.getOrDefault(emptyList())

    val receipts: Flow<List<ChatCompletionReceipt>> = store.data.map(::read)

    suspend fun completed(contextKey: String, sessionId: String, turnId: String, completedAt: Long, visible: Boolean): Boolean {
        if (AgentDisplay.parseProfileContextKey(contextKey) == null || sessionId.isBlank() || turnId.isBlank()) return false
        var recorded = false
        store.edit { prefs ->
            val current = read(prefs)
            val old = current.firstOrNull { it.contextKey == contextKey && it.sessionId == sessionId }
            // Reading does not discard the last turn identity: a replay cannot
            // resurrect its unread badge. Older delayed completions cannot win.
            if (old?.turnId == turnId || (old != null && old.completedAt > completedAt)) return@edit
            val updated = current.filterNot { it.contextKey == contextKey && it.sessionId == sessionId } +
                ChatCompletionReceipt(contextKey, sessionId, turnId, completedAt, !visible)
            prefs[key] = json.encodeToString(updated.sortedByDescending { it.completedAt }.take(512))
            recorded = true
        }
        return recorded
    }

    suspend fun markRead(contextKey: String, sessionId: String) {
        store.edit { prefs ->
            val current = read(prefs)
            if (current.none { it.contextKey == contextKey && it.sessionId == sessionId && it.unread }) return@edit
            prefs[key] = json.encodeToString(current.map {
                if (it.contextKey == contextKey && it.sessionId == sessionId) it.copy(unread = false) else it
            })
        }
    }

    suspend fun removeConnection(connectionId: String) {
        store.edit { prefs -> prefs[key] = json.encodeToString(read(prefs).filterNot {
            AgentDisplay.parseProfileContextKey(it.contextKey)?.connectionId == connectionId
        }) }
    }

    suspend fun clear() { store.edit { it.remove(key) } }
}

internal val Context.chatUnreadDataStore: DataStore<Preferences> by preferencesDataStore(name = "chat_unread")
