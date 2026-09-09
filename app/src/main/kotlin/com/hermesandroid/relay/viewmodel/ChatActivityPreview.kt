package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.ChatActivityPhase
import com.hermesandroid.relay.data.ChatActivityRecord
import com.hermesandroid.relay.network.upstream.GatewayProcess

internal data class RetainedChatActivityPreview(
    val record: ChatActivityRecord,
    val processes: List<GatewayProcess> = emptyList(),
)

/** Historical rows contain references and bounded summaries, never an invented live transcript. */
internal fun ChatActivityRecord.previewActivities(): List<SubagentActivity> = children.mapIndexed { index, child ->
    val phase = when (child.phase) {
        ChatActivityPhase.RUNNING -> SubagentActivityPhase.STARTED
        ChatActivityPhase.COMPLETE -> SubagentActivityPhase.COMPLETED
        ChatActivityPhase.FAILED -> SubagentActivityPhase.FAILED
        ChatActivityPhase.CANCELLED -> SubagentActivityPhase.INTERRUPTED
        ChatActivityPhase.UNKNOWN -> SubagentActivityPhase.ENDED_WITH_PARENT
    }
    SubagentActivity(
        laneId = 0,
        turnId = "record:$id:${child.id}",
        taskIndex = index,
        taskCount = maxOf(taskCount, children.size),
        goal = child.goal,
        subagentId = child.id,
        childSessionId = child.childSessionId,
        profile = AgentDisplay.parseProfileContextKey(scopeKey)?.requestProfileName,
        phase = phase,
        summary = child.summary,
        events = child.summary?.let { summary -> listOf(SubagentActivityEvent(
            sequence = 0,
            kind = SubagentActivityEventKind.COMPLETED,
            text = summary,
            phase = phase,
            observedAtMillis = updatedAt,
        )) }.orEmpty(),
        partialAfterGap = true,
        revision = updatedAt,
    )
}
