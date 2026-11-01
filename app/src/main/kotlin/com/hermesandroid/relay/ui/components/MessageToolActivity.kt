package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.ToolCall

/**
 * The tool rows a single assistant message owns: routine calls collapse into
 * [ToolActivityRun], attention-bearing ones stay [ToolProgressCard], and
 * delegated work groups into [SubagentLane]s.
 *
 * Extracted from the chat transcript so the same rendering can be called once
 * per reasoning block instead of once per message. Callers that do not split
 * reasoning pass every call at once and get exactly the previous layout.
 */
@Composable
internal fun MessageToolActivity(
    calls: List<ToolCall>,
    uiKey: String,
    messageTimestamp: Long,
    messageStreaming: Boolean,
    isStreaming: Boolean,
    toolDisplay: String,
    retainedSubagentIds: Set<String>,
    onSteer: (String, String) -> Unit,
    onUserScrolledAway: () -> Unit,
) {
    if (calls.isEmpty()) return

    // Subagent children (taskIndex != null) group into lanes after the
    // top-level transcript activity; the null group retains source order
    // while routine calls collapse into runs.
    val laneGroups = calls.groupBy { it.taskIndex }
    val transcriptTools = groupTranscriptTools(laneGroups[null].orEmpty())

    transcriptTools.forEachIndexed { itemIndex, item ->
        when (item) {
            is ToolTranscriptItem.ActivityRun -> {
                if (item.isVisibleForToolDisplay(toolDisplay)) {
                    Spacer(modifier = Modifier.height(4.dp))
                    ToolActivityRun(
                        calls = item.calls,
                        live = item.calls.any { !it.isComplete } ||
                            (messageStreaming && itemIndex == transcriptTools.lastIndex),
                        detailed = toolDisplay == "detailed",
                        messageTimestamp = messageTimestamp,
                        petObstacleKey = "chat-tools:$uiKey:${item.calls.first().uiKey}",
                        onExpandedChange = { expanded ->
                            if (expanded && isStreaming) onUserScrolledAway()
                        },
                    )
                }
            }

            is ToolTranscriptItem.Standalone -> {
                // Generated media owns its lifecycle inside the message bubble.
                // Every other standalone call is attention-bearing and stays
                // visible even when ordinary tool scaffolding is Off.
                if (!item.call.showsImageGenerationPlaceholder()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    ToolProgressCard(
                        toolCall = item.call,
                        messageTimestamp = messageTimestamp,
                        onExpandedChange = { expanded ->
                            if (expanded && isStreaming) onUserScrolledAway()
                        },
                    )
                }
            }
        }
    }

    // Delegated work is a lifecycle surface, not optional diagnostic
    // scaffolding. Keep lanes visible in Off, Compact, and Detailed modes.
    laneGroups.keys.filterNotNull().sorted().forEach { taskIndex ->
        val laneCalls = laneGroups.getValue(taskIndex)

        if (laneCalls.all { it.subagentId != null && it.subagentId in retainedSubagentIds }) return@forEach

        Spacer(modifier = Modifier.height(4.dp))
        SubagentLane(
            taskIndex = taskIndex,
            calls = laneCalls,
            onSteer = onSteer,
        )
    }
}

/**
 * A live turn's reasoning rendered in the order it happened: each thinking
 * block followed by the tool rows that block produced.
 *
 * Only used while [ChatMessage.thinkingSegments] holds more than one block, so
 * history rows (rebuilt per model call from the server, which already
 * interleave) keep the plain single-block layout.
 */
@Composable
internal fun LiveThinkingToolColumn(
    message: ChatMessage,
    toolDisplay: String,
    isStreaming: Boolean,
    retainedSubagentIds: Set<String>,
    onSteer: (String, String) -> Unit,
    onUserScrolledAway: () -> Unit,
    modifier: Modifier = Modifier,
    maxBubbleWidth: Dp = 300.dp,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        message.thinkingSegments.forEachIndexed { index, text ->
            if (text.isNotBlank()) {
                ThinkingBlock(
                    thinkingContent = text,
                    isStreaming = message.isThinkingStreaming && index == message.thinkingSegments.lastIndex,
                    timestamp = message.timestamp,
                    petObstacleKey = "chat-thinking:${message.uiKey}:$index",
                    modifier = Modifier
                        .widthIn(max = maxBubbleWidth)
                        .padding(bottom = 4.dp),
                )
            }

            val calls = message.toolCalls.filter { it.thinkingSegment == index }

            MessageToolActivity(
                calls = calls,
                uiKey = message.uiKey,
                messageTimestamp = message.timestamp,
                messageStreaming = message.isStreaming,
                isStreaming = isStreaming,
                toolDisplay = toolDisplay,
                retainedSubagentIds = retainedSubagentIds,
                onSteer = onSteer,
                onUserScrolledAway = onUserScrolledAway,
            )
        }
    }
}
