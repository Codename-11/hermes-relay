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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One agent-initiated message as persisted in the Hermes inbox.
 *
 * Deliberately separate from the wire model
 * ([com.hermesandroid.relay.network.relay.ProactiveMessage]) so the on-disk
 * shape doesn't track protocol changes — only the user-facing fields persist.
 */
@Serializable
data class ProactiveInboxEntry(
    val id: String,
    val title: String,
    val text: String,
    /** Epoch millis the message was received (server `sent_at` when present). */
    val receivedAt: Long,
    /**
     * Conversation the message belongs to (server `chat_id`). Carried so an
     * inbox reply (Phase 2c) continues the same thread. Nullable + defaulted
     * so blobs persisted before 2c still decode (kotlinx tolerates the absent
     * field).
     */
    val chatId: String? = null,
)

private val Context.proactiveInboxStore: DataStore<Preferences> by
    preferencesDataStore(name = "proactive_inbox")

private val INBOX_JSON = stringPreferencesKey("entries_json")

/** Bound the inbox so a chatty agent can't grow the on-disk blob without limit. */
private const val MAX_ENTRIES = 100

/**
 * DataStore-backed store for the "Hermes" inbox of agent-initiated messages.
 * Entries are kept newest-first, deduped by id (so a re-delivered message
 * doesn't double up), and capped at [MAX_ENTRIES]. Survives app restart.
 *
 * Phase 3 may grow this (read/unread, per-profile filtering); for Phase 2a it
 * is a flat capped log feeding [com.hermesandroid.relay.ui.screens.HermesInboxScreen].
 */
class ProactiveInboxRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    val entries: Flow<List<ProactiveInboxEntry>> =
        context.proactiveInboxStore.data.map { prefs -> decode(prefs[INBOX_JSON]) }

    suspend fun add(entry: ProactiveInboxEntry) {
        context.proactiveInboxStore.edit { prefs ->
            val current = decode(prefs[INBOX_JSON]).toMutableList()
            current.removeAll { it.id == entry.id }
            current.add(0, entry)
            while (current.size > MAX_ENTRIES) current.removeAt(current.lastIndex)
            prefs[INBOX_JSON] = json.encodeToString(current.toList())
        }
    }

    suspend fun clear() {
        context.proactiveInboxStore.edit { it.remove(INBOX_JSON) }
    }

    private fun decode(raw: String?): List<ProactiveInboxEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ProactiveInboxEntry>>(raw)
        }.getOrDefault(emptyList())
    }
}
