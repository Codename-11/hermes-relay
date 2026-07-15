package com.hermesandroid.relay.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable, client-owned snapshot of one in-flight chat turn.
 *
 * Hermes history is authoritative once a turn finishes, but it cannot recreate
 * transient UI that existed before persistence (live reasoning, a running tool,
 * an interactive ask, or the latest lifecycle line). This checkpoint bridges
 * that gap across Activity recreation and process death. It deliberately stores
 * no entered secret/approval response; only the server-issued ask is retained.
 */
@Serializable
data class ChatTurnCheckpoint(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val contextKey: String,
    val sessionId: String,
    val liveSessionId: String? = null,
    val transport: String,
    val user: ChatTurnUserCheckpoint,
    val assistant: ChatTurnAssistantCheckpoint,
    val turnStatus: String? = null,
    val priorUserMessageCount: Int,
    val baselineAssistantCount: Int,
    val pendingAsk: ChatTurnAskCheckpoint? = null,
    val startedAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val CURRENT_SCHEMA = 1
        const val MAX_AGE_MS = 24L * 60L * 60L * 1_000L
    }
}

@Serializable
data class ChatTurnUserCheckpoint(
    val id: String,
    val content: String,
    val timestamp: Long,
)

@Serializable
data class ChatTurnAssistantCheckpoint(
    val id: String,
    val content: String = "",
    val timestamp: Long,
    val isStreaming: Boolean = true,
    val thinkingContent: String = "",
    val isThinkingStreaming: Boolean = false,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val estimatedCost: Double? = null,
    val agentName: String? = null,
    val badges: List<String> = emptyList(),
    val cards: List<HermesCard> = emptyList(),
    val cardDispatches: List<HermesCardDispatch> = emptyList(),
    val toolCalls: List<ChatTurnToolCheckpoint> = emptyList(),
    val backgroundTask: ChatTurnBackgroundTaskCheckpoint? = null,
)

@Serializable
data class ChatTurnToolCheckpoint(
    val id: String? = null,
    val name: String,
    val result: String? = null,
    val success: Boolean? = null,
    val isComplete: Boolean = false,
    val error: String? = null,
    val runId: String? = null,
    val provenance: String? = null,
    val startedAt: Long,
    val completedAt: Long? = null,
    val isGenerating: Boolean = false,
    val taskIndex: Int? = null,
    val taskLabel: String? = null,
    val outputRisk: String? = null,
    val outputRiskFindings: List<String> = emptyList(),
    val outputRiskRedacted: Boolean = false,
)

@Serializable
data class ChatTurnBackgroundTaskCheckpoint(
    val id: String,
    val title: String,
    val tier: String,
    val phase: String,
    val statusLine: String? = null,
    val completedToolCount: Int = 0,
    val queuedCount: Int = 0,
    val startedAt: Long,
)

@Serializable
data class ChatTurnAskCheckpoint(
    val kind: String,
    val requestId: String? = null,
    val text: String,
    val choices: List<String>? = null,
    val smartDenied: Boolean = false,
    val envVar: String? = null,
    val timeoutSeconds: Int,
    val messageId: String,
    val cardKey: String,
    /** Original receive time, used to preserve an ask's expiry after reopen. */
    val receivedAt: Long,
)

interface ChatTurnCheckpointStore {
    suspend fun read(): ChatTurnCheckpoint?
    suspend fun readAll(): List<ChatTurnCheckpoint> = listOfNotNull(read())
    suspend fun read(contextKey: String, sessionId: String): ChatTurnCheckpoint? =
        readAll()
            .filter { it.contextKey == contextKey && it.sessionId == sessionId }
            .maxByOrNull(ChatTurnCheckpoint::updatedAt)
    suspend fun write(checkpoint: ChatTurnCheckpoint)
    suspend fun remove(contextKey: String, sessionId: String) {
        if (read()?.let { it.contextKey == contextKey && it.sessionId == sessionId } == true) {
            clear()
        }
    }
    suspend fun clear()
}

class DataStoreChatTurnCheckpointStore(
    private val dataStore: DataStore<Preferences>,
    private val now: () -> Long = System::currentTimeMillis,
) : ChatTurnCheckpointStore {
    constructor(context: Context) : this(context.applicationContext.relayDataStore)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    override suspend fun read(): ChatTurnCheckpoint? =
        readAll().maxByOrNull(ChatTurnCheckpoint::updatedAt)

    override suspend fun readAll(): List<ChatTurnCheckpoint> {
        val preferences = runCatching { dataStore.data.first() }.getOrNull() ?: return emptyList()
        val decoded = decode(preferences)
        val valid = decoded.filter(::isValid)
            .distinctBy { it.contextKey to it.sessionId }
        if (valid.size != decoded.size ||
            (preferences[KEY_CHECKPOINT_SET] == null && preferences[KEY_CHECKPOINT] != null)
        ) {
            // Cleanup/migration is best-effort. A read must still return the
            // valid subset if DataStore's atomic rewrite is briefly unavailable.
            runCatching { replaceAll(valid) }
        }
        return valid
    }

    override suspend fun read(contextKey: String, sessionId: String): ChatTurnCheckpoint? =
        readAll().firstOrNull { it.contextKey == contextKey && it.sessionId == sessionId }

    override suspend fun write(checkpoint: ChatTurnCheckpoint) {
        dataStore.edit { preferences ->
            val merged = mergeChatTurnCheckpoints(
                existing = decode(preferences),
                checkpoint = checkpoint,
                now = now(),
                limit = MAX_CHECKPOINTS,
            )
            preferences[KEY_CHECKPOINT_SET] = json.encodeToString(
                ChatTurnCheckpointSet(checkpoints = merged),
            )
            preferences.remove(KEY_CHECKPOINT)
        }
    }

    override suspend fun remove(contextKey: String, sessionId: String) {
        dataStore.edit { preferences ->
            val remaining = removeChatTurnCheckpoint(
                decode(preferences),
                contextKey,
                sessionId,
            )
            if (remaining.isEmpty()) {
                preferences.remove(KEY_CHECKPOINT_SET)
            } else {
                preferences[KEY_CHECKPOINT_SET] = json.encodeToString(
                    ChatTurnCheckpointSet(checkpoints = remaining),
                )
            }
            preferences.remove(KEY_CHECKPOINT)
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_CHECKPOINT)
            preferences.remove(KEY_CHECKPOINT_SET)
        }
    }

    private fun decode(preferences: Preferences): List<ChatTurnCheckpoint> {
        val current = preferences[KEY_CHECKPOINT_SET]?.let { raw ->
            runCatching { json.decodeFromString<ChatTurnCheckpointSet>(raw) }.getOrNull()
        }
        if (current?.schemaVersion == ChatTurnCheckpointSet.CURRENT_SCHEMA) {
            return current.checkpoints
        }
        return preferences[KEY_CHECKPOINT]?.let { raw ->
            listOfNotNull(runCatching { json.decodeFromString<ChatTurnCheckpoint>(raw) }.getOrNull())
        }.orEmpty()
    }

    private fun isValid(checkpoint: ChatTurnCheckpoint): Boolean =
        checkpoint.schemaVersion == ChatTurnCheckpoint.CURRENT_SCHEMA &&
            now() - checkpoint.updatedAt <= ChatTurnCheckpoint.MAX_AGE_MS

    private suspend fun replaceAll(checkpoints: List<ChatTurnCheckpoint>) {
        dataStore.edit { preferences ->
            if (checkpoints.isEmpty()) {
                preferences.remove(KEY_CHECKPOINT_SET)
            } else {
                preferences[KEY_CHECKPOINT_SET] = json.encodeToString(
                    ChatTurnCheckpointSet(checkpoints = checkpoints),
                )
            }
            preferences.remove(KEY_CHECKPOINT)
        }
    }

    private companion object {
        const val MAX_CHECKPOINTS = 16
        val KEY_CHECKPOINT = stringPreferencesKey("chat_inflight_turn_checkpoint_v1")
        val KEY_CHECKPOINT_SET = stringPreferencesKey("chat_inflight_turn_checkpoints_v2")
    }
}

internal fun mergeChatTurnCheckpoints(
    existing: List<ChatTurnCheckpoint>,
    checkpoint: ChatTurnCheckpoint,
    now: Long,
    limit: Int = 16,
): List<ChatTurnCheckpoint> =
    (existing.filterNot {
        it.contextKey == checkpoint.contextKey && it.sessionId == checkpoint.sessionId
    } + checkpoint)
        .filter {
            it.schemaVersion == ChatTurnCheckpoint.CURRENT_SCHEMA &&
                now - it.updatedAt <= ChatTurnCheckpoint.MAX_AGE_MS
        }
        .sortedByDescending(ChatTurnCheckpoint::updatedAt)
        .take(limit)

internal fun removeChatTurnCheckpoint(
    existing: List<ChatTurnCheckpoint>,
    contextKey: String,
    sessionId: String,
): List<ChatTurnCheckpoint> = existing.filterNot {
    it.contextKey == contextKey && it.sessionId == sessionId
}

@Serializable
private data class ChatTurnCheckpointSet(
    val schemaVersion: Int = CURRENT_SCHEMA,
    val checkpoints: List<ChatTurnCheckpoint>,
) {
    companion object {
        const val CURRENT_SCHEMA = 1
    }
}
