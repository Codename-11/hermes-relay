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
    val envVar: String? = null,
    val timeoutSeconds: Int,
    val messageId: String,
    val cardKey: String,
    /** Original receive time, used to preserve an ask's expiry after reopen. */
    val receivedAt: Long,
)

interface ChatTurnCheckpointStore {
    suspend fun read(): ChatTurnCheckpoint?
    suspend fun write(checkpoint: ChatTurnCheckpoint)
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

    override suspend fun read(): ChatTurnCheckpoint? {
        val raw = runCatching { dataStore.data.first()[KEY_CHECKPOINT] }.getOrNull()
            ?: return null
        val checkpoint = runCatching { json.decodeFromString<ChatTurnCheckpoint>(raw) }.getOrNull()
        if (checkpoint == null ||
            checkpoint.schemaVersion != ChatTurnCheckpoint.CURRENT_SCHEMA ||
            now() - checkpoint.updatedAt > ChatTurnCheckpoint.MAX_AGE_MS
        ) {
            // Cleanup is best-effort. In particular, Windows can briefly keep
            // the just-read preferences file open and reject DataStore's atomic
            // temp-file rename; an invalid checkpoint must still read as null.
            runCatching { clear() }
            return null
        }
        return checkpoint
    }

    override suspend fun write(checkpoint: ChatTurnCheckpoint) {
        dataStore.edit { preferences ->
            preferences[KEY_CHECKPOINT] = json.encodeToString(checkpoint)
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences -> preferences.remove(KEY_CHECKPOINT) }
    }

    private companion object {
        val KEY_CHECKPOINT = stringPreferencesKey("chat_inflight_turn_checkpoint_v1")
    }
}
