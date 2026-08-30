package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.viewmodel.SubagentActivity
import com.hermesandroid.relay.viewmodel.SubagentActivityEvent
import com.hermesandroid.relay.viewmodel.SubagentActivityEventKind
import com.hermesandroid.relay.viewmodel.SubagentActivityPhase
import com.hermesandroid.relay.viewmodel.SubagentChildPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentActivityPreviewTest {
    @Test
    fun `supervised visibility excludes child history rows`() {
        val activity = activity(laneId = 0, taskIndex = 0)
        val preview = preview(activity, messageCount = 2)
        val expanded = setOf(activity.stableKey)

        val full = subagentActivityItemCount(
            listOf(activity),
            expanded,
            SubagentPreviewVisibility(showChildHistory = true),
            preview,
        )
        val supervised = subagentActivityItemCount(
            listOf(activity),
            expanded,
            SubagentPreviewVisibility(showChildHistory = false),
            preview,
        )

        assertTrue(full > supervised)
        assertEquals(4, full - supervised) // heading, two child messages, and tail anchor
    }

    @Test
    fun `follow target stays with selected child instead of final concurrent lane`() {
        val first = activity(laneId = 0, taskIndex = 0)
        val second = activity(laneId = 1, taskIndex = 1)
        val preview = preview(first, messageCount = 1)
        val target = subagentActivityFollowTarget(
            activities = listOf(first, second),
            expandedKeys = setOf(first.stableKey, second.stableKey),
            visibility = SubagentPreviewVisibility(),
            childPreview = preview,
        )
        val total = subagentActivityItemCount(
            listOf(first, second),
            setOf(first.stableKey, second.stableKey),
            SubagentPreviewVisibility(),
            preview,
        )

        assertTrue(target < total - 1)
    }

    private fun activity(laneId: Long, taskIndex: Int) = SubagentActivity(
        laneId = laneId,
        turnId = "turn",
        taskIndex = taskIndex,
        taskCount = 2,
        goal = "Task $taskIndex",
        phase = SubagentActivityPhase.PROGRESS,
        events = listOf(
            SubagentActivityEvent(
                sequence = 0,
                kind = SubagentActivityEventKind.UPDATE,
                text = "Working",
                phase = SubagentActivityPhase.PROGRESS,
                observedAtMillis = 1,
            ),
        ),
    )

    private fun preview(activity: SubagentActivity, messageCount: Int) = SubagentChildPreview(
        activityKey = activity.stableKey,
        parentSessionId = "parent",
        parentScopeKey = "scope",
        childWatchAvailable = true,
        messages = List(messageCount) { index ->
            ChatMessage("message-$index", MessageRole.ASSISTANT, "Text", index.toLong())
        },
    )
}
