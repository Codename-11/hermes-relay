package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatActivityProjectionTest {
    private fun record(
        id: String = "record",
        sourceId: String = "delegation-a",
        phase: ChatActivityPhase = ChatActivityPhase.COMPLETE,
        at: Long = 20,
    ) = ChatActivityRecord(
        id, "connection::profile", "session", ChatActivityKind.SUBAGENTS,
        sourceId, "Research", phase, 10, at,
        children = listOf(ChatActivityChild("child-a", "child-session", phase = phase)),
        taskCount = 1,
    )

    private fun message(id: String, at: Long = 30) =
        ChatMessage(id, MessageRole.ASSISTANT, "Reply $id", at)

    private fun completion(id: String = "server-row", source: String? = "delegation:delegation-a") =
        message(id).copy(
            role = MessageRole.SYSTEM,
            content = "1 background task completed",
            activitySourceId = source,
            activityTaskCount = 1,
        )

    private fun project(messages: List<ChatMessage>, records: List<ChatActivityRecord>) =
        projectChatActivityReceipts(messages, records, "connection::profile", "session")

    @Test
    fun `canonical completion preserves identity and original payload`() {
        val row = completion().copy(uiKey = "live-key", rowId = 77)
        val result = project(listOf(row), listOf(record())).single()
        assertEquals(row, result.copy(activityRecord = null))
        assertEquals("record", result.activityRecord?.id)
    }

    @Test
    fun `completion wake replaces synthetic receipt without dropping ordinary messages`() {
        val before = project(listOf(message("first", 1)), listOf(record()))
        assertEquals(listOf("first", "activity:record"), before.map { it.id })
        val after = project(before + completion() + message("wake", 40), listOf(record()))
        assertEquals(listOf("first", "server-row", "wake"), after.map { it.id })
        assertEquals(after, project(after, listOf(record())))
    }

    @Test
    fun `duplicate group completion rows retain separate identities and live sibling state`() {
        val running = record(phase = ChatActivityPhase.RUNNING).copy(
            children = listOf(
                ChatActivityChild("done", phase = ChatActivityPhase.COMPLETE),
                ChatActivityChild("working", phase = ChatActivityPhase.RUNNING),
            ),
            taskCount = 2,
        )
        val result = project(listOf(completion("group-one"), completion("group-two")), listOf(running))
        assertEquals(listOf("group-one", "group-two"), result.map { it.id })
        result.forEach {
            assertEquals(ChatActivityPhase.RUNNING, it.activityRecord?.phase)
            assertEquals(ChatActivityPhase.RUNNING, it.activityRecord?.children?.last()?.phase)
        }
    }

    @Test
    fun `missing and unknown delegation IDs never select archive by count or recency`() {
        listOf("unavailable:server-row", "delegation:", "delegation:other").forEach { source ->
            val result = project(listOf(completion(source = source)), listOf(record()))
            val fallback = result.single { it.id == "server-row" }.activityRecord!!
            assertTrue(fallback.children.isEmpty())
            assertEquals("canonical:server-row", fallback.id)
            assertEquals(1, fallback.taskCount)
            assertEquals(2, result.size)
        }
    }

    @Test
    fun `incomplete metadata exposes unknown detail without fabricated task count`() {
        val row = completion(source = "unavailable:server-row").copy(activityTaskCount = null)
        val fallback = project(listOf(row), emptyList()).single().activityRecord!!
        assertEquals(ChatActivityPhase.UNKNOWN, fallback.phase)
        assertEquals(0, fallback.taskCount)
        assertTrue(fallback.children.isEmpty())
    }

    @Test
    fun `partial failure count never labels every missing child failed`() {
        val row = completion().copy(activityTaskCount = 3, activityFailedCount = 1)
        val result = project(listOf(row), emptyList()).single().activityRecord!!
        assertEquals(ChatActivityPhase.UNKNOWN, result.phase)
        assertEquals(3, result.taskCount)
        assertTrue(result.children.isEmpty())
    }

    @Test
    fun `archive ownership excludes sibling profiles and sessions`() {
        val result = project(
            listOf(completion()),
            listOf(record().copy(scopeKey = "connection::other"), record().copy(sessionId = "other")),
        )
        assertEquals(1, result.size)
        assertTrue(result.single().activityRecord!!.children.isEmpty())
    }

    @Test
    fun `same snapshot identity chooses newest and restored unknown remains unknown`() {
        val result = project(emptyList(), listOf(
            record(phase = ChatActivityPhase.COMPLETE, at = 15),
            record(phase = ChatActivityPhase.UNKNOWN, at = 20),
        )).single()
        assertEquals(ChatActivityPhase.UNKNOWN, result.activityRecord?.phase)
        assertEquals(ChatActivityPhase.UNKNOWN, result.activityRecord?.children?.single()?.phase)
    }

    @Test
    fun `running archive has no synthetic receipt and timestamp merge preserves history order`() {
        val rows = listOf(message("first", 40), message("second", 5), message("third", 50))
        val result = project(rows, listOf(record(), record(id = "running", phase = ChatActivityPhase.RUNNING)))
        assertEquals(listOf("activity:record", "first", "second", "third"), result.map { it.id })
        assertEquals(rows, result.filter { !it.clientOnly })
    }

    private fun processRow() = message("process-row").copy(
        role = MessageRole.USER,
        content = "[IMPORTANT: Background process proc-1 completed normally (exit code 0).\nCommand: build\nOutput:\nOK]",
    )

    private fun processRecord(id: String, startedAt: String) = record(id, "proc-1").copy(
        kind = ChatActivityKind.PROCESS,
        processId = "proc-1",
        processStartedAt = startedAt,
        children = emptyList(),
    )

    @Test
    fun `exact process ID joins receipt and retains authoritative output in source row`() {
        val row = processRow()
        val result = project(listOf(row), listOf(processRecord("process", "start-1"))).single()
        assertEquals(row, result.copy(activityRecord = null))
        assertEquals("process", result.activityRecord?.id)
        assertEquals("proc-1", result.activityRecord?.processId)
    }

    @Test
    fun `reused process ID does not choose a generation by timestamps`() {
        val result = project(listOf(processRow()), listOf(
            processRecord("generation-1", "start-1"),
            processRecord("generation-2", "start-2").copy(updatedAt = 25),
        ))
        val fallback = result.single { it.id == "process-row" }.activityRecord!!
        assertEquals("canonical:process-row", fallback.id)
        assertNull(fallback.processStartedAt)
        assertEquals(ChatActivityPhase.COMPLETE, fallback.phase)
        assertEquals(0, fallback.exitCode)
    }

    @Test
    fun `canonical process headline supplies terminal outcome without a retained process`() {
        listOf(
            Triple("completed normally (exit code 0).", ChatActivityPhase.COMPLETE, 0),
            Triple("exited (exit code 1).", ChatActivityPhase.FAILED, 1),
            Triple("terminated by Hermes (exit code -15, SIGTERM).", ChatActivityPhase.CANCELLED, -15),
        ).forEach { (status, phase, code) ->
            val row = processRow().copy(content = "[IMPORTANT: Background process proc-1 $status\nOutput:\nresult]")
            val receipt = project(listOf(row), emptyList()).single().activityRecord!!
            assertEquals(phase, receipt.phase)
            assertEquals(code, receipt.exitCode)
        }
    }

    @Test
    fun `watch and malformed headlines cannot borrow exit codes from output`() {
        listOf(
            "matched watch pattern \"done\".",
            "exited (exit code ?).",
            "exited (exit code 0). trailing text",
            "exited (exit code 99999999999999999999).",
        ).forEach { status ->
            val row = processRow().copy(
                content = "[IMPORTANT: Background process proc-1 $status\nOutput:\nBackground process proc-1 completed normally (exit code 0).]",
            )
            val receipt = project(listOf(row), emptyList()).single().activityRecord!!
            assertEquals(ChatActivityPhase.UNKNOWN, receipt.phase)
            assertNull(receipt.exitCode)
        }
    }

    @Test
    fun `ordinary important messages never become activity receipts`() {
        val row = message("important").copy(role = MessageRole.USER, content = "[IMPORTANT: Read this carefully]")
        assertEquals(listOf(row), project(listOf(row), emptyList()))
    }

    @Test
    fun `missing owner clears previously projected detail and synthetic rows`() {
        val projected = project(listOf(completion()), listOf(record()))
        val result = projectChatActivityReceipts(projected, listOf(record()), null, "session")
        assertEquals(1, result.size)
        assertNull(result.single().activityRecord)
        assertFalse(result.single().clientOnly)
    }
}
