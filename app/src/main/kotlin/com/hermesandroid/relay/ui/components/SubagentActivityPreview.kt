package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.viewmodel.SubagentChildPreview
import com.hermesandroid.relay.viewmodel.SubagentActivity
import com.hermesandroid.relay.viewmodel.SubagentActivityEvent
import com.hermesandroid.relay.viewmodel.SubagentActivityEventKind
import com.hermesandroid.relay.viewmodel.SubagentActivityPhase

internal data class SubagentPreviewVisibility(
    val showLifecycle: Boolean = true,
    val showReasoning: Boolean = true,
    val showToolNames: Boolean = true,
    val showToolDetails: Boolean = true,
    val showChildHistory: Boolean = true,
)

internal fun LazyListScope.subagentActivityItems(
    activities: List<SubagentActivity>,
    expandedKeys: Set<String>,
    visibility: SubagentPreviewVisibility,
    childPreview: SubagentChildPreview?,
    onToggle: (String) -> Unit,
    onOpenChild: (String) -> Unit,
) {
    if (activities.isEmpty() || !visibility.showLifecycle) return
    item(key = "subagent-section") {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.agent_activity_section),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                stringResource(R.string.agent_activity_disclosure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    activities.forEach { activity ->
        val key = activity.stableKey
        item(key = "subagent-header-$key") {
            SubagentActivityHeader(
                activity = activity,
                expanded = key in expandedKeys,
                visibility = visibility,
                onClick = {
                    if (key !in expandedKeys && visibility.showChildHistory) onOpenChild(key)
                    onToggle(key)
                },
            )
        }
        if (key in expandedKeys) {
            if (activity.truncated) {
                item(key = "subagent-truncated-$key") {
                    SubagentMetaRow(stringResource(R.string.agent_activity_older_omitted))
                }
            }
            activity.events.forEach { event ->
                item(key = "subagent-event-$key-${event.sequence}") {
                    SubagentEventRow(event, visibility)
                }
            }
            if (activity.partialAfterGap) {
                item(key = "subagent-gap-$key") {
                    SubagentMetaRow(stringResource(R.string.agent_activity_partial))
                }
            }
            childPreview?.takeIf { visibility.showChildHistory && it.activityKey == key }?.let { preview ->
                when (preview.childWatchAvailable) {
                    null -> item(key = "subagent-child-loading-$key") {
                        SubagentMetaRow(stringResource(R.string.agent_activity_child_loading))
                    }
                    false -> item(key = "subagent-child-unavailable-$key") {
                        SubagentMetaRow(
                            preview.error?.takeIf(String::isNotBlank)
                                ?: stringResource(R.string.agent_activity_child_unavailable),
                        )
                    }
                    true -> {
                        item(key = "subagent-child-heading-$key") {
                            SubagentMetaRow(
                                if (preview.running) {
                                    stringResource(R.string.agent_activity_child_live)
                                } else {
                                    stringResource(R.string.agent_activity_child_history)
                                },
                            )
                        }
                        if (preview.historyTruncated) {
                            item(key = "subagent-child-truncated-$key") {
                                SubagentMetaRow(stringResource(R.string.agent_activity_child_truncated))
                            }
                        }
                        preview.messages.filterNot { it.role == MessageRole.SYSTEM }.forEach { message ->
                            item(key = "subagent-child-message-$key-${message.uiKey}") {
                                SubagentChildMessageRow(message, visibility)
                            }
                        }
                        preview.error?.takeIf(String::isNotBlank)?.let { error ->
                            item(key = "subagent-child-error-$key") { SubagentMetaRow(error) }
                        }
                    }
                }
                item(key = "subagent-child-tail-$key") {
                    Spacer(Modifier.height(1.dp))
                }
            }
        }
    }
}

internal fun subagentActivityItemCount(
    activities: List<SubagentActivity>,
    expandedKeys: Set<String>,
    visibility: SubagentPreviewVisibility,
    childPreview: SubagentChildPreview?,
): Int {
    if (activities.isEmpty() || !visibility.showLifecycle) return 0
    return 1 + activities.sumOf { activity ->
        val expanded = activity.stableKey in expandedKeys
        val preview = childPreview?.takeIf {
            visibility.showChildHistory && it.activityKey == activity.stableKey
        }
        val previewRows = when (preview?.childWatchAvailable) {
            null -> if (preview != null) 1 else 0
            false -> 1
            true -> 1 + preview.messages.count { it.role != MessageRole.SYSTEM } +
                (if (preview.historyTruncated) 1 else 0) +
                (if (preview.error.isNullOrBlank()) 0 else 1)
        } + if (preview != null) 1 else 0 // explicit bottom anchor for growing rows
        1 + if (!expanded) 0 else activity.events.size +
            (if (activity.truncated) 1 else 0) +
            (if (activity.partialAfterGap) 1 else 0) + previewRows
    }
}

internal fun subagentActivityFollowTarget(
    activities: List<SubagentActivity>,
    expandedKeys: Set<String>,
    visibility: SubagentPreviewVisibility,
    childPreview: SubagentChildPreview?,
): Int {
    if (activities.isEmpty() || !visibility.showLifecycle) return -1
    var index = 0 // section heading
    var selectedTarget = -1
    activities.forEach { activity ->
        index += 1 // lane header
        if (activity.stableKey in expandedKeys) {
            if (activity.truncated) index += 1
            index += activity.events.size
            if (activity.partialAfterGap) index += 1
            if (visibility.showChildHistory && childPreview?.activityKey == activity.stableKey) {
                index += when (childPreview.childWatchAvailable) {
                    null, false -> 1
                    true -> 1 + childPreview.messages.count { it.role != MessageRole.SYSTEM } +
                        (if (childPreview.historyTruncated) 1 else 0) +
                        (if (childPreview.error.isNullOrBlank()) 0 else 1)
                }
                index += 1 // explicit bottom anchor for growing child content
                selectedTarget = index
            }
        }
    }
    return if (selectedTarget >= 0) selectedTarget else index
}

@Composable
private fun SubagentActivityHeader(
    activity: SubagentActivity,
    expanded: Boolean,
    visibility: SubagentPreviewVisibility,
    onClick: () -> Unit,
) {
    val title = activity.goal.takeIf { visibility.showReasoning && it.isNotBlank() }
        ?: stringResource(R.string.agent_activity_fallback, activity.taskIndex + 1)
    val phaseLabel = phaseLabel(activity.phase)
    val description = stringResource(
        R.string.agent_activity_lane_a11y,
        title,
        phaseLabel,
        activity.taskIndex + 1,
        activity.taskCount,
    )
    val icon: ImageVector = when (activity.phase) {
        SubagentActivityPhase.COMPLETED -> Icons.Filled.CheckCircle
        SubagentActivityPhase.FAILED -> Icons.Filled.ErrorOutline
        SubagentActivityPhase.INTERRUPTED,
        SubagentActivityPhase.ENDED_WITH_PARENT,
        -> Icons.Filled.PauseCircleOutline
        SubagentActivityPhase.STARTED,
        SubagentActivityPhase.THINKING,
        SubagentActivityPhase.TOOL,
        SubagentActivityPhase.PROGRESS,
        -> Icons.Filled.HourglassTop
    }
    val tint = when (activity.phase) {
        SubagentActivityPhase.FAILED -> MaterialTheme.colorScheme.error
        SubagentActivityPhase.COMPLETED -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = description
                stateDescription = phaseLabel
                liveRegion = LiveRegionMode.Polite
            }
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        R.string.agent_activity_task_position,
                        activity.taskIndex + 1,
                        activity.taskCount,
                        phaseLabel,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            activity.durationSeconds?.takeIf { activity.isTerminal }?.let { seconds ->
                Text(
                    stringResource(R.string.agent_activity_duration, seconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.cd_subagent_collapse else R.string.cd_subagent_expand,
                ),
            )
        }
    }
}

@Composable
private fun SubagentEventRow(
    event: SubagentActivityEvent,
    visibility: SubagentPreviewVisibility,
) {
    val label = when (event.kind) {
        SubagentActivityEventKind.STARTED -> stringResource(R.string.agent_activity_event_started)
        SubagentActivityEventKind.UPDATE -> stringResource(R.string.agent_activity_event_update)
        SubagentActivityEventKind.TOOL -> stringResource(R.string.agent_activity_event_tool)
        SubagentActivityEventKind.COMPLETED -> phaseLabel(event.phase)
    }
    val showText = when (event.kind) {
        SubagentActivityEventKind.STARTED -> false
        SubagentActivityEventKind.UPDATE,
        SubagentActivityEventKind.COMPLETED,
        -> visibility.showReasoning
        SubagentActivityEventKind.TOOL -> visibility.showToolDetails
    }
    val toolName = event.toolName?.takeIf { visibility.showToolNames || visibility.showToolDetails }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 38.dp, end = 20.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.AccountTree,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
            toolName?.let {
                Text(
                    " · $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        event.text?.takeIf { showText && it.isNotBlank() }?.let { text ->
            Text(
                text,
                modifier = Modifier.padding(top = 3.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (event.kind == SubagentActivityEventKind.TOOL) {
                    FontFamily.Monospace
                } else {
                    FontFamily.Default
                },
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SubagentMetaRow(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 38.dp, end = 20.dp, top = 4.dp, bottom = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SubagentChildMessageRow(
    message: ChatMessage,
    visibility: SubagentPreviewVisibility,
) {
    if (message.role == MessageRole.SYSTEM) return
    val role = when (message.role) {
        MessageRole.USER -> stringResource(R.string.agent_activity_child_role_task)
        MessageRole.ASSISTANT -> stringResource(R.string.agent_activity_child_role_agent)
        MessageRole.SYSTEM -> stringResource(R.string.agent_activity_child_role_system)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 38.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Text(
            role,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        message.thinkingContent.takeIf { visibility.showReasoning && it.isNotBlank() }?.let { thought ->
            Text(
                thought,
                modifier = Modifier.padding(top = 3.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 10,
                overflow = TextOverflow.Ellipsis,
            )
        }
        message.content.takeIf(String::isNotBlank)?.let { content ->
            Text(
                content,
                modifier = Modifier.padding(top = 3.dp),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 20,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (visibility.showToolNames || visibility.showToolDetails) {
            message.toolCalls.forEach { tool ->
                Text(
                    buildString {
                        append(tool.name)
                        if (visibility.showToolDetails) {
                            tool.args?.takeIf(String::isNotBlank)?.let { append(" · ").append(it.take(500)) }
                        }
                    },
                    modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun phaseLabel(phase: SubagentActivityPhase): String = stringResource(
    when (phase) {
        SubagentActivityPhase.STARTED -> R.string.agent_activity_status_started
        SubagentActivityPhase.THINKING -> R.string.agent_activity_status_thinking
        SubagentActivityPhase.TOOL -> R.string.agent_activity_status_tool
        SubagentActivityPhase.PROGRESS -> R.string.agent_activity_status_progress
        SubagentActivityPhase.COMPLETED -> R.string.agent_activity_status_completed
        SubagentActivityPhase.FAILED -> R.string.agent_activity_status_failed
        SubagentActivityPhase.INTERRUPTED -> R.string.agent_activity_status_interrupted
        SubagentActivityPhase.ENDED_WITH_PARENT -> R.string.agent_activity_status_unavailable
    },
)
