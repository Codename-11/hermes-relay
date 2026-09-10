package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable
enum class ChatActivityKind { SUBAGENTS, PROCESS }

@Serializable
enum class ChatActivityPhase { RUNNING, COMPLETE, FAILED, CANCELLED, UNKNOWN }

@Serializable
data class ChatActivityChild(
    val id: String,
    val childSessionId: String? = null,
    val goal: String = "",
    val phase: ChatActivityPhase = ChatActivityPhase.UNKNOWN,
    val summary: String? = null,
)

/** Local presentation metadata and exact references, never transcripts or process output. */
@Serializable
data class ChatActivityRecord(
    val id: String,
    val scopeKey: String,
    val sessionId: String,
    val kind: ChatActivityKind,
    val sourceId: String,
    val title: String,
    val phase: ChatActivityPhase,
    val createdAt: Long,
    val updatedAt: Long,
    val children: List<ChatActivityChild> = emptyList(),
    val taskCount: Int = 0,
    val processId: String? = null,
    val processStartedAt: String? = null,
    val exitCode: Int? = null,
)

interface ChatActivityStore {
    /** Recovery is not live evidence: RUNNING becomes UNKNOWN, including child phases. */
    suspend fun read(scopeKey: String, sessionId: String): List<ChatActivityRecord>
    suspend fun upsert(record: ChatActivityRecord)
    suspend fun removeRecord(scopeKey: String, sessionId: String, id: String)
    suspend fun removeSession(scopeKey: String, sessionId: String)
}

/**
 * Bounded app-private history references in the shared settings DataStore.
 * Retains 30 days, 128 records overall, 32 per exact owner/session, and 32 children
 * per record. Titles are 160 characters; goals/summaries 512. Identity fields are
 * rejected above 512 characters (scope 2048), never truncated into another owner.
 * The complete encoded envelope is capped at 1 MiB, evicting oldest records first.
 * Five minutes of future skew allows monotonic local revisions within one clock tick.
 * All read/modify/write operations occur inside DataStore's serialized edit.
 */
class DataStoreChatActivityStore(
    private val dataStore: DataStore<Preferences>,
    private val now: () -> Long = System::currentTimeMillis,
) : ChatActivityStore {
    constructor(context: Context) : this(context.applicationContext.relayDataStore)

    override suspend fun read(scopeKey: String, sessionId: String): List<ChatActivityRecord> {
        val raw = try {
            dataStore.data.first()[CHAT_ACTIVITY_KEY]
        } catch (_: IOException) {
            return emptyList()
        }
        return boundChatActivities(decodeChatActivities(raw), now())
            .filter { it.scopeKey == scopeKey && it.sessionId == sessionId }
            .map(ChatActivityRecord::recovered)
    }

    override suspend fun upsert(record: ChatActivityRecord) {
        dataStore.edit { preferences ->
            val records = mergeChatActivity(decodeChatActivities(preferences[CHAT_ACTIVITY_KEY]), record, now())
            preferences[CHAT_ACTIVITY_KEY] = encodeChatActivities(records)
        }
    }

    override suspend fun removeSession(scopeKey: String, sessionId: String) {
        dataStore.edit { preferences ->
            val remaining = boundChatActivities(decodeChatActivities(preferences[CHAT_ACTIVITY_KEY]), now())
                .filterNot { it.scopeKey == scopeKey && it.sessionId == sessionId }
            if (remaining.isEmpty()) preferences.remove(CHAT_ACTIVITY_KEY)
            else preferences[CHAT_ACTIVITY_KEY] = encodeChatActivities(remaining)
        }
    }

    override suspend fun removeRecord(scopeKey: String, sessionId: String, id: String) {
        dataStore.edit { preferences ->
            val remaining = boundChatActivities(decodeChatActivities(preferences[CHAT_ACTIVITY_KEY]), now())
                .filterNot { it.scopeKey == scopeKey && it.sessionId == sessionId && it.id == id }
            if (remaining.isEmpty()) preferences.remove(CHAT_ACTIVITY_KEY)
            else preferences[CHAT_ACTIVITY_KEY] = encodeChatActivities(remaining)
        }
    }
}

/** Test/ephemeral implementation with the same bounds and recovery semantics. */
class InMemoryChatActivityStore(
    private val now: () -> Long = System::currentTimeMillis,
) : ChatActivityStore {
    private val mutex = Mutex()
    private var records = emptyList<ChatActivityRecord>()

    override suspend fun read(scopeKey: String, sessionId: String): List<ChatActivityRecord> = mutex.withLock {
        records = boundChatActivities(records, now())
        records.filter { it.scopeKey == scopeKey && it.sessionId == sessionId }
            .map(ChatActivityRecord::recovered)
    }

    override suspend fun upsert(record: ChatActivityRecord) = mutex.withLock {
        records = mergeChatActivity(records, record, now())
    }

    override suspend fun removeSession(scopeKey: String, sessionId: String) = mutex.withLock {
        records = boundChatActivities(records, now())
            .filterNot { it.scopeKey == scopeKey && it.sessionId == sessionId }
    }

    override suspend fun removeRecord(scopeKey: String, sessionId: String, id: String) = mutex.withLock {
        records = boundChatActivities(records, now())
            .filterNot { it.scopeKey == scopeKey && it.sessionId == sessionId && it.id == id }
    }
}

internal const val CHAT_ACTIVITY_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1_000L
private const val MAX_RECORDS = 128
private const val MAX_SESSION_RECORDS = 32
private const val MAX_PAYLOAD_BYTES = 1_048_576
private val CHAT_ACTIVITY_KEY = stringPreferencesKey("chat_activity_records_v1")
private val activityJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
private data class ChatActivityEnvelope(val version: Int = 1, val records: List<ChatActivityRecord>)

private fun encodeChatActivities(records: List<ChatActivityRecord>): String =
    activityJson.encodeToString(ChatActivityEnvelope(records = records))

internal fun decodeChatActivities(raw: String?): List<ChatActivityRecord> {
    if (raw == null || raw.length > MAX_PAYLOAD_BYTES || raw.toByteArray().size > MAX_PAYLOAD_BYTES) {
        return emptyList()
    }
    val envelope = runCatching { activityJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        ?: return emptyList()
    val version = runCatching { envelope["version"]?.jsonPrimitive?.intOrNull }.getOrNull()
    if (version != 1) return emptyList()
    val rows = envelope["records"] as? JsonArray ?: return emptyList()
    // One corrupt or newer row must not hide independently valid records.
    return rows.mapNotNull { row ->
        runCatching { activityJson.decodeFromJsonElement(ChatActivityRecord.serializer(), row) }.getOrNull()
    }
}

private fun ChatActivityRecord.identity() = Triple(scopeKey, sessionId, id)

private fun mergeChatActivity(
    existing: List<ChatActivityRecord>,
    record: ChatActivityRecord,
    now: Long,
): List<ChatActivityRecord> {
    // Sorting first also rejects a late write for an older generation of the same record.
    return boundChatActivities(listOf(record) + existing, now)
}

internal fun boundChatActivities(records: List<ChatActivityRecord>, now: Long): List<ChatActivityRecord> {
    val counts = mutableMapOf<Pair<String, String>, Int>()
    val bounded = records.mapNotNull { it.bounded(now) }
        .sortedByDescending(ChatActivityRecord::updatedAt)
        .distinctBy { it.identity() }
        .filter {
            val owner = it.scopeKey to it.sessionId
            val count = counts.getOrDefault(owner, 0)
            counts[owner] = count + 1
            count < MAX_SESSION_RECORDS
        }.take(MAX_RECORDS).toMutableList()
    while (bounded.isNotEmpty() && encodeChatActivities(bounded).toByteArray().size > MAX_PAYLOAD_BYTES) {
        bounded.removeAt(bounded.lastIndex)
    }
    return bounded
}

private fun validIdentity(value: String, max: Int = 512) = value.isNotBlank() && value.length <= max

private fun ChatActivityRecord.bounded(now: Long): ChatActivityRecord? {
    if (!validIdentity(scopeKey, 2048) || !validIdentity(sessionId) || !validIdentity(id) ||
        !validIdentity(sourceId) || (processId != null && !validIdentity(processId)) ||
        (processStartedAt != null && !validIdentity(processStartedAt)) ||
        createdAt < 0 || updatedAt < createdAt || updatedAt > now + 300_000L ||
        now - updatedAt > CHAT_ACTIVITY_MAX_AGE_MS
    ) return null
    return copy(
        title = title.take(160),
        taskCount = taskCount.coerceIn(0, 10_000),
        children = children.asSequence().filter {
            validIdentity(it.id) && (it.childSessionId == null || validIdentity(it.childSessionId))
        }.distinctBy(ChatActivityChild::id).take(32)
            .map { it.copy(goal = it.goal.take(512), summary = it.summary?.take(512)) }.toList(),
    )
}

private fun ChatActivityRecord.recovered() = copy(
    phase = if (phase == ChatActivityPhase.RUNNING) ChatActivityPhase.UNKNOWN else phase,
    children = children.map {
        if (it.phase == ChatActivityPhase.RUNNING) it.copy(phase = ChatActivityPhase.UNKNOWN) else it
    },
)
