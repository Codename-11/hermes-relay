package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.ChatActivityKind
import com.hermesandroid.relay.data.ChatActivityPhase
import com.hermesandroid.relay.data.ChatActivityRecord
import com.hermesandroid.relay.data.ChatActivityStore
import com.hermesandroid.relay.data.InMemoryChatActivityStore
import com.hermesandroid.relay.network.upstream.GatewayProcess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatActivityControllerTest {
    private val now = 100_000L

    @Test
    fun completedChildrenRemainWhenNewParentTurnPrunesActiveList() = runTest {
        val controller = ChatActivityController(this, clock = { now })
        controller.selectSession("scope", "session")
        controller.captureSubagents(listOf(child("first", SubagentActivityPhase.COMPLETED)))
        val original = controller.records.value.single()
        controller.captureSubagents(emptyList())
        controller.captureSubagents(listOf(child("second", SubagentActivityPhase.THINKING).copy(turnId = "next-turn")))
        val record = controller.records.value.single()
        assertEquals(original.id, record.id)
        assertEquals(original.createdAt, record.createdAt)
        assertEquals(listOf("first", "second"), record.children.map { it.id })
        assertEquals(ChatActivityPhase.RUNNING, record.phase)
        controller.captureSubagents(listOf(child("second", SubagentActivityPhase.COMPLETED)))
        assertEquals(ChatActivityPhase.COMPLETE, controller.records.value.single().phase)
    }

    @Test
    fun groupsOnlyByExactDelegationAndSkipsAnonymousChildren() = runTest {
        val controller = ChatActivityController(this, clock = { now })
        controller.selectSession("scope", "session")
        controller.captureSubagents(listOf(
            child("a").copy(delegationId = null), child("b").copy(delegationId = null),
            child("c").copy(delegationId = "different"),
            child("anonymous").copy(subagentId = null, childSessionId = null),
        ))
        assertEquals(3, controller.records.value.size)
        assertEquals(3, controller.records.value.map { it.id }.distinct().size)
    }

    @Test
    fun uncertaintyAndExactFailuresDriveAggregateWithoutPersistingEventBodies() = runTest {
        val controller = ChatActivityController(this, clock = { now })
        controller.selectSession("scope", "session")
        controller.captureSubagents(listOf(
            child("a", SubagentActivityPhase.FAILED), child("b", SubagentActivityPhase.ENDED_WITH_PARENT),
        ))
        assertEquals(ChatActivityPhase.UNKNOWN, controller.records.value.single().phase)
        controller.captureSubagents(listOf(child("b", SubagentActivityPhase.COMPLETED)))
        assertEquals(ChatActivityPhase.FAILED, controller.records.value.single().phase)
    }

    @Test
    fun processIdReuseCreatesSeparateHistoryAndDisappearanceBecomesUnknown() = runTest {
        val controller = ChatActivityController(this, clock = { now })
        controller.selectSession("scope", "session")
        controller.captureProcesses(listOf(process("old")))
        val original = controller.records.value.single()
        controller.captureProcesses(listOf(process("new")))
        val records = controller.records.value
        assertEquals(2, records.size)
        assertEquals(ChatActivityPhase.UNKNOWN, records.first { it.id == original.id }.phase)
        assertEquals(ChatActivityPhase.RUNNING, records.first { it.processStartedAt == "new" }.phase)
        controller.captureProcesses(listOf(process("new").copy(status = "exited", exitCode = 0)))
        assertEquals(ChatActivityPhase.COMPLETE, controller.records.value.first { it.processStartedAt == "new" }.phase)
        assertTrue(controller.records.value.none { it.title.contains("private") })
    }

    @Test
    fun identityEncodingSeparatesNullAndLiteralStartValues() = runTest {
        val controller = ChatActivityController(this, clock = { now })
        controller.selectSession("scope", "session")
        controller.captureProcesses(listOf(process(null), process("null")))
        assertEquals(2, controller.records.value.size)
        assertNotEquals(controller.records.value[0].id, controller.records.value[1].id)
    }

    @Test
    fun staleReadCannotPublishIntoAnotherSession() = runTest {
        val gate = CompletableDeferred<List<ChatActivityRecord>>()
        val store = RecordingStore { _, session -> if (session == "old") gate.await() else emptyList() }
        val controller = ChatActivityController(this, store) { now }
        controller.selectSession("scope", "old")
        runCurrent()
        controller.selectSession("scope", "new")
        controller.captureSubagents(listOf(child("new-child")))
        gate.complete(listOf(record("old")))
        advanceUntilIdle()
        assertEquals(listOf("new"), controller.records.value.map { it.sessionId })
    }

    @Test
    fun delayedRestoreDoesNotOverwriteFreshLiveState() = runTest {
        val gate = CompletableDeferred<List<ChatActivityRecord>>()
        val store = RecordingStore { _, _ -> gate.await() }
        val controller = ChatActivityController(this, store) { now }
        controller.selectSession("scope", "session")
        runCurrent()
        controller.captureProcesses(listOf(process("same")))
        val fresh = controller.records.value.single()
        gate.complete(listOf(fresh.copy(phase = ChatActivityPhase.UNKNOWN)))
        advanceUntilIdle()
        assertEquals(ChatActivityPhase.RUNNING, controller.records.value.single().phase)
    }

    @Test
    fun storeBindingMigratesMemoryAndFencesPreviousLoads() = runTest {
        val memory = InMemoryChatActivityStore { now }
        val controller = ChatActivityController(this, memory) { now }
        controller.selectSession("scope", "session")
        advanceUntilIdle()
        controller.captureSubagents(listOf(child("a", SubagentActivityPhase.COMPLETED)))
        advanceUntilIdle()
        val durable = RecordingStore { _, _ -> emptyList() }
        controller.bindStore(durable)
        advanceUntilIdle()
        assertEquals(1, controller.records.value.size)
        assertEquals(controller.records.value, durable.written)
    }

    @Test
    fun unchangedSnapshotsDoNotWriteAndChangedMetadataUsesMonotonicRevision() = runTest {
        val store = RecordingStore { _, _ -> emptyList() }
        val controller = ChatActivityController(this, store) { now }
        controller.selectSession("scope", "session")
        advanceUntilIdle()
        controller.captureSubagents(listOf(child("a")))
        advanceUntilIdle()
        controller.captureSubagents(listOf(child("a")))
        advanceUntilIdle()
        assertEquals(1, store.written.size)
        controller.captureSubagents(listOf(child("a", SubagentActivityPhase.COMPLETED)))
        advanceUntilIdle()
        assertEquals(2, store.written.size)
        assertTrue(store.written.last().updatedAt > store.written.first().updatedAt)
    }

    @Test
    fun removeFencesPendingLoadAndRemovesOnlyRequestedOwner() = runTest {
        val gate = CompletableDeferred<List<ChatActivityRecord>>()
        val store = RecordingStore { _, _ -> gate.await() }
        val controller = ChatActivityController(this, store) { now }
        controller.selectSession("scope", "session")
        runCurrent()
        controller.removeSession("scope", "session")
        gate.complete(listOf(record("session")))
        advanceUntilIdle()
        assertTrue(controller.records.value.isEmpty())
        assertEquals(listOf("scope" to "session"), store.removed)
    }

    @Test
    fun lateDelegationMigratesExactFallbackAndRemovesDurableDuplicate() = runTest {
        val store = RecordingStore { _, _ -> emptyList() }
        val controller = ChatActivityController(this, store) { now }
        controller.selectSession("scope", "session")
        advanceUntilIdle()
        controller.captureSubagents(listOf(child("a").copy(delegationId = null)))
        val fallback = controller.records.value.single()
        controller.captureSubagents(listOf(child("a")))
        advanceUntilIdle()
        val grouped = controller.records.value.single()
        assertNotEquals(fallback.id, grouped.id)
        assertEquals(fallback.createdAt, grouped.createdAt)
        assertEquals(1, grouped.children.size)
        assertEquals(listOf(fallback.id), store.removedRecords)
    }

    @Test
    fun childSessionIdentityEnrichesWithoutDuplicatingChild() = runTest {
        val controller = ChatActivityController(this, clock = { now })
        controller.selectSession("scope", "session")
        controller.captureSubagents(listOf(child("a").copy(subagentId = null)))
        controller.captureSubagents(listOf(child("a")))
        val children = controller.records.value.single().children
        assertEquals(1, children.size)
        assertEquals("a", children.single().id)
    }

    @Test
    fun declaredUnobservedChildrenPreventFalseCompletion() = runTest {
        val controller = ChatActivityController(this, clock = { now })
        controller.selectSession("scope", "session")
        controller.captureSubagents(listOf(child("a", SubagentActivityPhase.COMPLETED).copy(taskCount = 3)))
        assertEquals(3, controller.records.value.single().taskCount)
        assertEquals(ChatActivityPhase.UNKNOWN, controller.records.value.single().phase)
    }

    @Test
    fun deletionRejectsLateCapturesAndSameOwnerReselectionUntilSwitchAway() = runTest {
        val controller = ChatActivityController(this, clock = { now })
        controller.selectSession("scope", "session")
        controller.captureSubagents(listOf(child("a")))
        controller.removeSession("scope", "session")
        controller.captureSubagents(listOf(child("late")))
        controller.selectSession("scope", "session")
        controller.captureProcesses(listOf(process("late")))
        assertTrue(controller.records.value.isEmpty())
        controller.selectSession("scope", "other")
        controller.selectSession("scope", "session")
        controller.captureSubagents(listOf(child("new")))
        assertEquals(1, controller.records.value.size)
    }

    @Test
    fun connectionLossDowngradesOnlyRunningRecordsAndChildren() = runTest {
        val controller = ChatActivityController(this, clock = { now })
        controller.selectSession("scope", "session")
        controller.captureSubagents(listOf(child("a"), child("b", SubagentActivityPhase.COMPLETED)))
        controller.captureProcesses(listOf(process("now")))
        controller.markUnavailable()
        assertTrue(controller.records.value.all { it.phase == ChatActivityPhase.UNKNOWN })
        val children = controller.records.value.first { it.kind == ChatActivityKind.SUBAGENTS }.children
        assertEquals(ChatActivityPhase.UNKNOWN, children.first { it.id == "a" }.phase)
        assertEquals(ChatActivityPhase.COMPLETE, children.first { it.id == "b" }.phase)
    }

    @Test
    fun freshChildAfterConnectionLossDoesNotReviveUnobservedSibling() = runTest {
        val controller = ChatActivityController(this, clock = { now })
        controller.selectSession("scope", "session")
        controller.captureSubagents(listOf(child("a"), child("b")))
        controller.markUnavailable()
        controller.captureSubagents(listOf(child("a", SubagentActivityPhase.PROGRESS)))
        val record = controller.records.value.single()
        assertEquals(ChatActivityPhase.RUNNING, record.phase)
        assertEquals(ChatActivityPhase.RUNNING, record.children.first { it.id == "a" }.phase)
        assertEquals(ChatActivityPhase.UNKNOWN, record.children.first { it.id == "b" }.phase)
    }

    @Test
    fun explicitZeroProcessExitCompletesUnknownStatusButDoesNotOverrideCancellationOrRunning() = runTest {
        val controller = ChatActivityController(this, clock = { now })
        controller.selectSession("scope", "session")
        controller.captureProcesses(listOf(
            process("zero").copy(status = "unknown", exitCode = 0),
            process("cancelled").copy(status = "cancelled", exitCode = 0),
            process("running").copy(status = "running", exitCode = 0),
        ))
        val phases = controller.records.value.associate { it.processStartedAt to it.phase }
        assertEquals(ChatActivityPhase.COMPLETE, phases["zero"])
        assertEquals(ChatActivityPhase.CANCELLED, phases["cancelled"])
        assertEquals(ChatActivityPhase.RUNNING, phases["running"])
    }

    private fun child(id: String, phase: SubagentActivityPhase = SubagentActivityPhase.THINKING) = SubagentActivity(
        laneId = 1, turnId = "turn", taskIndex = 1, taskCount = 2, goal = "Review", subagentId = id,
        childSessionId = "session-$id", phase = phase, delegationId = "delegation",
    )

    private fun process(start: String?) = GatewayProcess(
        id = "process", command = "private command", cwd = "/private/path", startedAt = start,
        status = "running", outputTail = "private output", sessionScoped = true,
    )

    private fun record(session: String) = ChatActivityRecord(
        id = "old-record", scopeKey = "scope", sessionId = session, kind = ChatActivityKind.SUBAGENTS,
        sourceId = "source", title = "Subagents", phase = ChatActivityPhase.COMPLETE,
        createdAt = now, updatedAt = now,
    )

    private class RecordingStore(
        val reader: suspend (String, String) -> List<ChatActivityRecord>,
    ) : ChatActivityStore {
        val written = mutableListOf<ChatActivityRecord>()
        val removed = mutableListOf<Pair<String, String>>()
        val removedRecords = mutableListOf<String>()
        override suspend fun read(scopeKey: String, sessionId: String) = reader(scopeKey, sessionId)
        override suspend fun upsert(record: ChatActivityRecord) { written += record }
        override suspend fun removeRecord(scopeKey: String, sessionId: String, id: String) { removedRecords += id }
        override suspend fun removeSession(scopeKey: String, sessionId: String) { removed += scopeKey to sessionId }
    }
}
