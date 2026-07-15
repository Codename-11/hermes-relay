package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatTurnCheckpointStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStoreChatTurnCheckpointStore
    private var now = 10_000L

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = tempFolder.newFile("chat_checkpoint_${System.nanoTime()}.preferences_pb")
        file.delete()
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
        store = DataStoreChatTurnCheckpointStore(dataStore) { now }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun fullRichTurn_roundTrips() = runTest {
        val checkpoint = sampleCheckpoint()

        store.write(checkpoint)

        assertEquals(checkpoint, store.read())
    }

    @Test
    fun corruptJson_isDiscarded() = runTest {
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("chat_inflight_turn_checkpoint_v1")] = "{broken"
        }

        assertNull(store.read())
        assertNull(store.read())
    }

    @Test
    fun staleCheckpoint_isDiscarded() = runTest {
        val checkpoint = sampleCheckpoint().copy(updatedAt = now)
        store.write(checkpoint)
        now += ChatTurnCheckpoint.MAX_AGE_MS + 1L

        assertNull(store.read())
        assertNull(store.read())
    }

    @Test
    fun legacySingleCheckpoint_isReadDuringMigration() = runTest {
        val checkpoint = sampleCheckpoint()
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("chat_inflight_turn_checkpoint_v1")] =
                Json.encodeToString(checkpoint)
        }

        assertEquals(checkpoint, store.read())
    }

    @Test
    fun multipleRunningSessions_mergeAndRemoveIndependently() = runTest {
        val first = sampleCheckpoint()
        val second = sampleCheckpoint().copy(
            contextKey = "connection-a::writer",
            sessionId = "stored-99",
            liveSessionId = "live-99",
            updatedAt = now + 1L,
        )

        val merged = mergeChatTurnCheckpoints(listOf(first), second, now + 1L)
        assertEquals(listOf(second, first), merged)
        assertEquals(
            listOf(second),
            removeChatTurnCheckpoint(merged, first.contextKey, first.sessionId),
        )
    }

    private fun sampleCheckpoint() = ChatTurnCheckpoint(
        contextKey = "connection-a/profile-default",
        sessionId = "stored-42",
        liveSessionId = "live-42",
        transport = "gateway",
        user = ChatTurnUserCheckpoint("user-1", "research this", 1_000L),
        assistant = ChatTurnAssistantCheckpoint(
            id = "assistant-1",
            content = "Working on it",
            timestamp = 1_001L,
            thinkingContent = "I should inspect the source",
            isThinkingStreaming = true,
            agentName = "Hermes",
            toolCalls = listOf(
                ChatTurnToolCheckpoint(
                    id = "tool-1",
                    name = "terminal",
                    isComplete = false,
                    startedAt = 1_002L,
                ),
            ),
            backgroundTask = ChatTurnBackgroundTaskCheckpoint(
                id = "run-1",
                title = "Research",
                tier = "durable",
                phase = BackgroundTaskPhase.RUNNING.name,
                statusLine = "Checking sources",
                startedAt = 1_003L,
            ),
        ),
        turnStatus = "Running terminal",
        priorUserMessageCount = 3,
        baselineAssistantCount = 3,
        pendingAsk = ChatTurnAskCheckpoint(
            kind = "APPROVAL",
            text = "Allow command?",
            timeoutSeconds = 0,
            messageId = "ask-1",
            cardKey = "approval-1",
            receivedAt = 1_004L,
        ),
        startedAt = 1_001L,
        updatedAt = now,
    )
}
