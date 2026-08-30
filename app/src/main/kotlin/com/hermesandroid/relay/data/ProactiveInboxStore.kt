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
    /** Owning saved connection. Null only for entries written by older builds. */
    val connectionId: String? = null,
    /** Relay proved this row came from its bounded offline queue. */
    val arrivedWhileAway: Boolean = false,
    /** Exact Android notification slot, when recorded by the receiving build. */
    val notificationId: Int? = null,
)

private val Context.proactiveInboxStore: DataStore<Preferences> by
    preferencesDataStore(name = "proactive_inbox")

private val INBOX_JSON = stringPreferencesKey("entries_json")

/** Bound the inbox so a chatty agent can't grow the on-disk blob without limit. */
private const val MAX_ENTRIES = 100

/**
 * DataStore-backed durable log of agent-initiated messages. Entries are kept
 * newest-first, deduped by id (so a re-delivered message doesn't double up), and
 * capped at [MAX_ENTRIES]. Survives app restart.
 *
 * Demoted (2026-06-29): once a phone gateway session exists, it is the durable
 * history. Outbound agent messages arrive before that session exists, so this
 * bounded store also backs the provisional Thread until the user's first reply
 * promotes it to a real `source=phone` session.
 */
class ProactiveInboxRepository internal constructor(
    private val store: DataStore<Preferences>,
) {

    constructor(context: Context) : this(context.proactiveInboxStore)

    private val json = Json { ignoreUnknownKeys = true }

    val entries: Flow<List<ProactiveInboxEntry>> =
        store.data.map { prefs -> decode(prefs[INBOX_JSON]) }

    suspend fun add(entry: ProactiveInboxEntry) {
        store.edit { prefs ->
            val current = decode(prefs[INBOX_JSON]).toMutableList()
            current.removeAll { it.id == entry.id }
            current.add(0, entry)
            while (current.size > MAX_ENTRIES) current.removeAt(current.lastIndex)
            prefs[INBOX_JSON] = json.encodeToString(current.toList())
        }
    }

    suspend fun clear() {
        store.edit { it.remove(INBOX_JSON) }
    }

    /**
     * Remove one provisional Thread owned by one saved connection.
     *
     * This only edits the bounded local inbox. A promoted Thread is server
     * history and is deliberately outside this repository, so this operation
     * can never delete it. Legacy entries without a connection owner are
     * removed with the active row because they are rendered in that row; rows
     * explicitly owned by another connection remain isolated.
     */
    suspend fun removeThread(
        chatId: String,
        connectionId: String,
    ): List<ProactiveInboxEntry> {
        val normalizedChatId = chatId.ifBlank { "phone" }
        var removed = emptyList<ProactiveInboxEntry>()
        store.edit { prefs ->
            val current = decode(prefs[INBOX_JSON])
            removed = current.filter {
                (it.connectionId == null || it.connectionId == connectionId) &&
                    (it.chatId ?: "phone") == normalizedChatId
            }
            if (removed.isNotEmpty()) {
                val retained = current.filterNot { it in removed }
                if (retained.isEmpty()) {
                    prefs.remove(INBOX_JSON)
                } else {
                    prefs[INBOX_JSON] = json.encodeToString(retained)
                }
            }
        }
        return removed
    }

    private fun decode(raw: String?): List<ProactiveInboxEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ProactiveInboxEntry>>(raw)
        }.getOrDefault(emptyList())
    }
}
