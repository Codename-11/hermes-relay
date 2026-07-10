package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.BackgroundTaskPhase
import com.hermesandroid.relay.data.BackgroundTaskState
import com.hermesandroid.relay.data.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundTaskCardTest {

    @Test
    fun phaseLabelsUseSharedTaskVocabulary() {
        assertEquals("Working", backgroundTaskPhaseLabel(BackgroundTaskPhase.RUNNING))
        assertEquals("Needs input", backgroundTaskPhaseLabel(BackgroundTaskPhase.WAITING))
        assertEquals("Delivering", backgroundTaskPhaseLabel(BackgroundTaskPhase.DELIVERING))
        assertEquals("Complete", backgroundTaskPhaseLabel(BackgroundTaskPhase.COMPLETE))
        assertEquals("Failed", backgroundTaskPhaseLabel(BackgroundTaskPhase.FAILED))
        assertEquals("Cancelled", backgroundTaskPhaseLabel(BackgroundTaskPhase.CANCELLED))
    }

    @Test
    fun metaUsesLargestCompletedCountAndQueuedDepth() {
        val task = BackgroundTaskState(
            id = "run-1",
            title = "Check release",
            completedToolCount = 1,
            queuedCount = 2,
        )
        val calls = listOf(
            ToolCall(name = "one", args = null, result = null, success = true, isComplete = true),
            ToolCall(name = "two", args = null, result = null, success = true, isComplete = true),
        )

        assertEquals("2 steps · +2 queued", backgroundTaskMeta(task, calls))
    }
}
