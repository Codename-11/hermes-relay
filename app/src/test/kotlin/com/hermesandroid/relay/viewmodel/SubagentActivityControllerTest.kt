package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.network.upstream.GatewaySubagentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentActivityControllerTest {
    private var now = 1_000L
    private val controller = SubagentActivityController { now++ }

    @Test
    fun `open sheet keeps completed details across parent wake until dismissed`() {
        controller.selectSession("parent", "scope")
        controller.beginTurn("parent", "scope", "first")
        controller.setPreviewOpen(true)
        controller.onSessionEvent("parent", "scope", event(0, GatewaySubagentEvent.Phase.START, subagentId = "child"), null)
        controller.onSessionEvent("parent", "scope", event(0, GatewaySubagentEvent.Phase.COMPLETE, subagentId = "child", status = "completed"), null)
        controller.beginTurn("parent", "scope", "wake")
        assertEquals(SubagentActivityPhase.COMPLETED, controller.activities.value.single().phase)
        controller.setPreviewOpen(false)
        assertTrue(controller.activities.value.isEmpty())
    }

    @Test
    fun `detached child survives parent completion and a new turn with stable preview identity`() {
        controller.selectSession("parent", "scope")
        controller.beginTurn("parent", "scope", "parent-turn")
        controller.onSessionEvent("parent", "scope", event(0, GatewaySubagentEvent.Phase.START, subagentId = "child"), null)
        val key = controller.activities.value.single().stableKey
        controller.endTurn("parent-turn")
        assertFalse(controller.activities.value.single().isTerminal)
        controller.beginTurn("parent", "scope", "next-turn")
        controller.onSessionEvent("parent", "scope", event(0, GatewaySubagentEvent.Phase.PROGRESS, preview = "Still working", subagentId = "child"), null)
        assertEquals(key, controller.activities.value.single().stableKey)
        assertEquals("Still working", controller.activities.value.single().events.last().text)
        controller.onSessionEvent("parent", "scope", event(0, GatewaySubagentEvent.Phase.COMPLETE, status = "completed", subagentId = "child"), null)
        assertEquals(SubagentActivityPhase.COMPLETED, controller.activities.value.single().phase)
        controller.beginTurn("parent", "scope", "completion-wake")
        assertTrue(controller.activities.value.isEmpty())
        controller.onSessionEvent("parent", "scope", event(0, GatewaySubagentEvent.Phase.START, subagentId = "child"), null)
        assertTrue(controller.activities.value.isEmpty())
    }

    @Test
    fun `idle session accepts child start but not orphan progress or other profile`() {
        controller.selectSession("parent", "scope")
        controller.onSessionEvent("parent", "scope", event(0, GatewaySubagentEvent.Phase.PROGRESS, subagentId = "unknown"), null)
        controller.onSessionEvent("parent", "foreign", event(0, GatewaySubagentEvent.Phase.START, subagentId = "foreign"), null)
        assertTrue(controller.activities.value.isEmpty())
        controller.onSessionEvent("parent", "scope", event(0, GatewaySubagentEvent.Phase.START, subagentId = "child"), null)
        assertFalse(controller.activities.value.single().isTerminal)
        controller.interrupt()
        controller.onSessionEvent("parent", "scope", event(0, GatewaySubagentEvent.Phase.START, subagentId = "late"), null)
        assertEquals(SubagentActivityPhase.INTERRUPTED, controller.activities.value.single().phase)
    }

    @Test
    fun `concurrent batches reusing task index remain separate across turns`() {
        controller.selectSession("parent", "scope")
        controller.beginTurn("parent", "scope", "first")
        controller.onSessionEvent("parent", "scope", event(0, GatewaySubagentEvent.Phase.START, subagentId = "child-a"), null)
        controller.beginTurn("parent", "scope", "second")
        controller.onSessionEvent("parent", "scope", event(0, GatewaySubagentEvent.Phase.START, subagentId = "child-b"), null)
        controller.onSessionEvent("parent", "scope", event(0, GatewaySubagentEvent.Phase.COMPLETE, status = "completed", subagentId = "child-a"), null)
        assertEquals(2, controller.activities.value.size)
        assertFalse(controller.activities.value.single { it.subagentId == "child-b" }.isTerminal)
    }

    @Test
    fun `timeouts and unknown terminal statuses never report success`() {
        controller.selectSession("parent", "scope")
        controller.beginTurn("parent", "scope", "turn")
        listOf("timeout", "error", "unknown", "running").forEachIndexed { index, status ->
            controller.onSessionEvent("parent", "scope", event(index, GatewaySubagentEvent.Phase.START, subagentId = "child-$index"), null)
            controller.onSessionEvent("parent", "scope", event(index, GatewaySubagentEvent.Phase.COMPLETE, status = status, subagentId = "child-$index"), null)
        }
        assertTrue(controller.activities.value.all { it.phase == SubagentActivityPhase.FAILED })
    }

    @Test
    fun `child preview keeps its admitted profile after parent checkpoint is cleared`() {
        controller.selectSession("parent", "scope")
        controller.beginTurn("parent", "scope", "turn")
        val start = event(0, GatewaySubagentEvent.Phase.START, subagentId = "child")
        controller.onSessionEvent("parent", "scope", start, "default")
        controller.endTurn("turn")
        controller.onSessionEvent("parent", "scope", start.copy(phase = GatewaySubagentEvent.Phase.PROGRESS), null)
        assertEquals("default", controller.activities.value.single().profile)
    }

    @Test
    fun `interleaved children retain independent lifecycle previews`() {
        controller.selectSession("parent", "connection::default")
        controller.beginTurn("parent", "connection::default", "turn-1")
        controller.onEvent("parent", "connection::default", "turn-1", event(0, GatewaySubagentEvent.Phase.START, goal = "Research"))
        controller.onEvent("parent", "connection::default", "turn-1", event(1, GatewaySubagentEvent.Phase.START, goal = "Review"))
        controller.onEvent("parent", "connection::default", "turn-1", event(0, GatewaySubagentEvent.Phase.PROGRESS, preview = "Halfway"))
        controller.onEvent("parent", "connection::default", "turn-1", event(1, GatewaySubagentEvent.Phase.TOOL, preview = "file.kt", tool = "read_file"))
        controller.onEvent("parent", "connection::default", "turn-1", event(0, GatewaySubagentEvent.Phase.COMPLETE, status = "complete", summary = "Done"))

        val activities = controller.activities.value.sortedBy { it.taskIndex }
        assertEquals(listOf("Research", "Review"), activities.map { it.goal })
        assertEquals(SubagentActivityPhase.COMPLETED, activities[0].phase)
        assertEquals("Done", activities[0].summary)
        assertEquals(SubagentActivityPhase.TOOL, activities[1].phase)
        assertEquals("read_file", activities[1].events.last().toolName)
    }

    @Test
    fun `profile session and newer turn fence stale events`() {
        controller.selectSession("shared", "connection::alpha")
        controller.beginTurn("shared", "connection::alpha", "turn-old")
        controller.onEvent("shared", "connection::alpha", "turn-old", event(0, GatewaySubagentEvent.Phase.START, goal = "Old"))
        controller.selectSession("shared", "connection::beta")
        controller.onEvent("shared", "connection::alpha", "turn-old", event(0, GatewaySubagentEvent.Phase.PROGRESS, preview = "stale"))
        assertTrue(controller.activities.value.isEmpty())

        controller.beginTurn("shared", "connection::beta", "turn-new")
        controller.onEvent("shared", "connection::beta", "turn-new", event(0, GatewaySubagentEvent.Phase.START, goal = "New"))
        controller.beginTurn("shared", "connection::beta", "turn-newer")
        controller.onEvent("shared", "connection::beta", "turn-newer", event(0, GatewaySubagentEvent.Phase.START, goal = "Newest"))
        controller.onEvent("shared", "connection::beta", "turn-new", event(0, GatewaySubagentEvent.Phase.PROGRESS, preview = "late"))
        assertEquals(listOf("Newest"), controller.activities.value.map { it.goal })
    }

    @Test
    fun `terminal truth distinguishes failure interruption and missing terminal`() {
        controller.selectSession("parent", "scope")
        controller.beginTurn("parent", "scope", "turn-1")
        controller.onEvent("parent", "scope", "turn-1", event(0, GatewaySubagentEvent.Phase.START))
        controller.onEvent("parent", "scope", "turn-1", event(0, GatewaySubagentEvent.Phase.COMPLETE, status = "interrupted"))
        assertEquals(SubagentActivityPhase.INTERRUPTED, controller.activities.value.single().phase)

        controller.beginTurn("parent", "scope", "turn-2")
        controller.onEvent("parent", "scope", "turn-2", event(0, GatewaySubagentEvent.Phase.START))
        controller.onEvent("parent", "scope", "turn-2", event(0, GatewaySubagentEvent.Phase.COMPLETE, status = "failed"))
        assertEquals(SubagentActivityPhase.FAILED, controller.activities.value.single().phase)

        controller.beginTurn("parent", "scope", "turn-3")
        controller.onEvent("parent", "scope", "turn-3", event(0, GatewaySubagentEvent.Phase.START))
        controller.endTurn("turn-3")
        assertEquals(SubagentActivityPhase.ENDED_WITH_PARENT, controller.activities.value.single().phase)
        assertTrue(controller.activities.value.single().partialAfterGap)
    }

    @Test
    fun `reconnect marks only live activity partial and late events do not reopen terminal child`() {
        controller.selectSession("parent", "scope")
        controller.beginTurn("parent", "scope", "turn")
        controller.onConnectionReady(true)
        controller.onEvent("parent", "scope", "turn", event(0, GatewaySubagentEvent.Phase.START))
        controller.onConnectionReady(false)
        controller.onConnectionReady(true)
        assertTrue(controller.activities.value.single().partialAfterGap)

        controller.onEvent("parent", "scope", "turn", event(0, GatewaySubagentEvent.Phase.COMPLETE, status = "complete"))
        val revision = controller.activities.value.single().revision
        controller.onEvent("parent", "scope", "turn", event(0, GatewaySubagentEvent.Phase.PROGRESS, preview = "late"))
        assertEquals(revision, controller.activities.value.single().revision)
    }

    @Test
    fun `event history is sanitized coalesced and bounded`() {
        controller.selectSession("parent", "scope")
        controller.beginTurn("parent", "scope", "turn")
        controller.onEvent("parent", "scope", "turn", event(0, GatewaySubagentEvent.Phase.START, goal = "\u001B[31mSecret\u0000"))
        repeat(SubagentActivityController.MAX_EVENTS_PER_CHILD + 10) { index ->
            controller.onEvent(
                "parent",
                "scope",
                "turn",
                event(0, GatewaySubagentEvent.Phase.PROGRESS, preview = "update-$index"),
            )
        }
        controller.onEvent("parent", "scope", "turn", event(0, GatewaySubagentEvent.Phase.PROGRESS, preview = "same"))
        controller.onEvent("parent", "scope", "turn", event(0, GatewaySubagentEvent.Phase.PROGRESS, preview = "same"))

        val activity = controller.activities.value.single()
        assertEquals("Secret", activity.goal)
        assertTrue(activity.truncated)
        assertTrue(activity.events.size <= SubagentActivityController.MAX_EVENTS_PER_CHILD)
        assertEquals(1, activity.events.count { it.text == "same" })
        assertFalse(activity.events.any { it.text?.contains('\u0000') == true })
    }

    @Test
    fun `identity enrichment keeps one lane while conflicting child stays separate`() {
        controller.selectSession("parent", "scope")
        controller.beginTurn("parent", "scope", "turn")
        controller.onEvent(
            "parent", "scope", "turn",
            event(0, GatewaySubagentEvent.Phase.SPAWN_REQUESTED, childId = "session-1"),
        )
        controller.onEvent(
            "parent", "scope", "turn",
            event(0, GatewaySubagentEvent.Phase.START, subagentId = "agent-1"),
        )
        assertEquals(1, controller.activities.value.size)
        assertEquals("session-1", controller.activities.value.single().childSessionId)
        assertEquals("agent-1", controller.activities.value.single().subagentId)

        controller.onEvent(
            "parent", "scope", "turn",
            event(
                0,
                GatewaySubagentEvent.Phase.START,
                subagentId = "agent-2",
                childId = "session-2",
            ),
        )
        assertEquals(2, controller.activities.value.size)
        assertEquals(2, controller.activities.value.map { it.stableKey }.distinct().size)
    }

    private fun event(
        index: Int,
        phase: GatewaySubagentEvent.Phase,
        goal: String = "",
        preview: String? = null,
        tool: String? = null,
        status: String? = null,
        summary: String? = null,
        subagentId: String? = null,
        childId: String? = null,
    ) = GatewaySubagentEvent(
        phase = phase,
        taskIndex = index,
        taskCount = 2,
        goal = goal,
        preview = preview,
        toolName = tool,
        status = status,
        summary = summary,
        subagentId = subagentId,
        childSessionId = childId,
    )
}
