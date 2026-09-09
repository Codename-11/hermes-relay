package com.hermesandroid.relay.data

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.Path.Companion.toPath
import okio.FileSystem

class ChatActivityStoreTest {
    @get:Rule val tempFolder = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: DataStoreChatActivityStore
    private var now = CHAT_ACTIVITY_MAX_AGE_MS * 2

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // Okio performs a real atomic replacement on Windows; File.renameTo cannot replace
        // an existing destination there. Android production keeps its shared platform store.
        dataStore = PreferenceDataStoreFactory.create(
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) {
                tempFolder.root.resolve("activity.preferences_pb").absolutePath.toPath()
            },
            scope = scope,
        )
        store = DataStoreChatActivityStore(dataStore) { now }
    }

    @After
    fun tearDown() = runBlocking { scope.coroutineContext[Job]?.cancelAndJoin(); Unit }

    @Test
    fun completedMetadataAndReferencesSurviveStoreRecreation() = runTest {
        val record = sample().copy(children = listOf(ChatActivityChild(
            id = "child", childSessionId = "child-session", goal = "Review", phase = ChatActivityPhase.COMPLETE,
            summary = "Reviewed",
        )))
        store.upsert(record)
        val recreated = DataStoreChatActivityStore(dataStore) { now }
        assertEquals(listOf(record), recreated.read("scope", "session"))
    }

    @Test
    fun recoveredRunningStateNeverClaimsLiveness() = runTest {
        store.upsert(sample().copy(
            phase = ChatActivityPhase.RUNNING,
            children = listOf(ChatActivityChild("child", phase = ChatActivityPhase.RUNNING)),
        ))
        val recovered = store.read("scope", "session").single()
        assertEquals(ChatActivityPhase.UNKNOWN, recovered.phase)
        assertEquals(ChatActivityPhase.UNKNOWN, recovered.children.single().phase)
    }

    @Test
    fun exactOwnerKeysDoNotAliasAndRemovalIsScoped() = runTest {
        val first = sample().copy(scopeKey = "a::b", sessionId = "c")
        val second = sample().copy(scopeKey = "a", sessionId = "b::c")
        store.upsert(first)
        store.upsert(second)
        assertEquals(listOf(first), store.read("a::b", "c"))
        store.removeSession("a::b", "c")
        assertTrue(store.read("a::b", "c").isEmpty())
        assertEquals(listOf(second), store.read("a", "b::c"))
    }

    @Test
    fun concurrentUpsertsPreserveDistinctRecordsAndOlderUpdatesCannotRegressState() = runTest {
        coroutineScope { repeat(20) { index -> launch { store.upsert(sample("$index")) } } }
        assertEquals(20, store.read("scope", "session").size)
        store.upsert(sample("0").copy(updatedAt = now - 1, createdAt = now - 2, phase = ChatActivityPhase.RUNNING))
        assertEquals(ChatActivityPhase.COMPLETE, store.read("scope", "session").first { it.id == "0" }.phase)
    }

    @Test
    fun invalidEnvelopeFailsClosedAndMalformedRowsDoNotHideValidSiblings() = runTest {
        val key = stringPreferencesKey("chat_activity_records_v1")
        for (raw in listOf("{broken", "{\"version\":2,\"records\":[]}", "[]")) {
            dataStore.edit { it[key] = raw }
            assertTrue(store.read("scope", "session").isEmpty())
        }
        dataStore.edit { it[key] = "{\"version\":1,\"records\":[{},${Json.encodeToString(sample())}]}" }
        assertEquals(listOf(sample()), store.read("scope", "session"))
    }

    @Test
    fun retentionExpiresWithoutRequiringAnotherWrite() = runTest {
        store.upsert(sample())
        now += CHAT_ACTIVITY_MAX_AGE_MS + 1
        assertTrue(store.read("scope", "session").isEmpty())
    }

    @Test
    fun boundedFieldsPreserveExactIdentifiersAndRejectOversizedOwners() = runTest {
        store.upsert(sample().copy(title = "t".repeat(500), children = (0..40).map {
            ChatActivityChild("child-$it", goal = "g".repeat(2000), summary = "s".repeat(2000))
        }))
        val record = store.read("scope", "session").single()
        assertEquals(160, record.title.length)
        assertEquals(32, record.children.size)
        assertEquals(512, record.children.first().goal.length)
        assertEquals(512, record.children.first().summary?.length)
        store.upsert(sample("bad").copy(scopeKey = "x".repeat(2049)))
        assertTrue(store.read("x".repeat(2048), "session").isEmpty())
    }

    @Test
    fun totalAndSessionLimitsEvictOldest() {
        val session = boundChatActivities((0..40).map {
            sample("$it").copy(createdAt = now - 100, updatedAt = now - it)
        }, now)
        assertEquals(32, session.size)
        assertEquals("0", session.first().id)
        val total = boundChatActivities((0..150).map {
            sample("$it").copy(sessionId = "session-$it", createdAt = now - 200, updatedAt = now - it)
        }, now)
        assertEquals(128, total.size)
    }

    @Test
    fun inMemoryStoreUsesSameRecoveryAndOwnershipRules() = runTest {
        val fake = InMemoryChatActivityStore { now }
        fake.upsert(sample().copy(phase = ChatActivityPhase.RUNNING))
        assertEquals(ChatActivityPhase.UNKNOWN, fake.read("scope", "session").single().phase)
        fake.removeSession("other", "session")
        assertEquals(1, fake.read("scope", "session").size)
        fake.removeSession("scope", "session")
        assertTrue(fake.read("scope", "session").isEmpty())
    }

    @Test
    fun processGenerationReferenceRoundTrips() = runTest {
        val process = sample().copy(
            kind = ChatActivityKind.PROCESS, processId = "pid-1", processStartedAt = "2026-09-07T12:00:00Z",
            exitCode = 0,
        )
        store.upsert(process)
        assertEquals(listOf(process), store.read("scope", "session"))
    }

    @Test
    fun aggregateCountDoesNotInventChildReferencesAndIsBounded() = runTest {
        store.upsert(sample().copy(taskCount = 3))
        assertEquals(3, store.read("scope", "session").single().taskCount)
        assertTrue(store.read("scope", "session").single().children.isEmpty())
        store.upsert(sample().copy(taskCount = Int.MAX_VALUE))
        assertEquals(10_000, store.read("scope", "session").single().taskCount)
        store.upsert(sample().copy(taskCount = -1))
        assertEquals(0, store.read("scope", "session").single().taskCount)
    }

    @Test
    fun shortLogicalClockSkewIsAcceptedButFarFutureRecordsAreRejected() = runTest {
        store.upsert(sample().copy(updatedAt = now + 1))
        assertEquals(now + 1, store.read("scope", "session").single().updatedAt)
        store.upsert(sample("future").copy(updatedAt = now + 300_001))
        assertEquals(1, store.read("scope", "session").size)
    }

    @Test
    fun removeRecordKeepsSiblingAndSameIdOtherOwner() = runTest {
        store.upsert(sample("a"))
        store.upsert(sample("b"))
        store.upsert(sample("a").copy(scopeKey = "other"))
        store.removeRecord("scope", "session", "a")
        assertEquals(listOf("b"), store.read("scope", "session").map { it.id })
        assertEquals(listOf("a"), store.read("other", "session").map { it.id })
    }

    private fun sample(id: String = "activity") = ChatActivityRecord(
        id = id, scopeKey = "scope", sessionId = "session", kind = ChatActivityKind.SUBAGENTS,
        sourceId = "dispatch", title = "Subagents", phase = ChatActivityPhase.COMPLETE,
        createdAt = now - 10, updatedAt = now,
    )
}
