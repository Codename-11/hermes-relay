package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.BackgroundTaskPhase
import com.hermesandroid.relay.data.BackgroundTaskState
import com.hermesandroid.relay.data.ToolCall
import com.hermesandroid.relay.ui.theme.relayMetadataStyle

/**
 * The Chat-side identity for one promoted/durable Hermes run. It stays in the
 * owning assistant turn while [BackgroundTaskState.phase] advances, rather
 * than creating a running system notice and a second completion row.
 *
 * Tool activity is deliberately subordinate: the compact timeline expands
 * inside this card and reuses [CompactToolCall]/[SubagentLane], so background
 * work reads like the same task at every stage instead of a mini dashboard.
 */
@Composable
fun BackgroundTaskCard(
    task: BackgroundTaskState,
    toolCalls: List<ToolCall>,
    showTimeline: Boolean,
    modifier: Modifier = Modifier,
) {
    val terminal = task.phase in terminalBackgroundTaskPhases
    val timelineCalls = if (showTimeline) toolCalls else emptyList()
    val hasTimeline = timelineCalls.isNotEmpty()
    var expanded by rememberSaveable(task.id) { mutableStateOf(hasTimeline && !terminal) }

    LaunchedEffect(terminal, hasTimeline) {
        if (!hasTimeline || terminal) expanded = false
    }

    val phaseLabel = backgroundTaskPhaseLabel(task.phase)
    val meta = backgroundTaskMeta(task, timelineCalls)
    val icon: ImageVector
    val iconTint = when (task.phase) {
        BackgroundTaskPhase.COMPLETE -> {
            icon = Icons.Filled.Check
            MaterialTheme.colorScheme.primary
        }
        BackgroundTaskPhase.FAILED, BackgroundTaskPhase.CANCELLED -> {
            icon = Icons.Filled.Close
            MaterialTheme.colorScheme.error
        }
        else -> {
            icon = Icons.Filled.HourglassTop
            MaterialTheme.colorScheme.tertiary
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = buildString {
                    append("Background task, ")
                    append(task.title)
                    append(", ")
                    append(phaseLabel.lowercase())
                    task.statusLine?.takeIf { it.isNotBlank() }?.let {
                        append(", ")
                        append(it)
                    }
                    if (meta.isNotBlank()) {
                        append(", ")
                        append(meta)
                    }
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasTimeline) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    task.statusLine?.takeIf { it.isNotBlank() }?.let { status ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = phaseLabel,
                        style = relayMetadataStyle(),
                        color = iconTint,
                    )
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = relayMetadataStyle(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (hasTimeline) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse task timeline" else "Expand task timeline",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            if (!terminal) {
                // A fixed accent rail communicates active state without adding
                // another indeterminate animation to an already-live transcript.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)),
                )
            }

            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val lanes = timelineCalls.groupBy { it.taskIndex }
                    lanes[null].orEmpty().forEach { call ->
                        CompactToolCall(toolCall = call)
                    }
                    lanes.keys.filterNotNull().sorted().forEach { taskIndex ->
                        SubagentLane(
                            taskIndex = taskIndex,
                            calls = lanes.getValue(taskIndex),
                        )
                    }
                }
            }
        }
    }
}

internal fun backgroundTaskPhaseLabel(phase: BackgroundTaskPhase): String = when (phase) {
    BackgroundTaskPhase.RUNNING -> "Working"
    BackgroundTaskPhase.WAITING -> "Needs input"
    BackgroundTaskPhase.DELIVERING -> "Delivering"
    BackgroundTaskPhase.COMPLETE -> "Complete"
    BackgroundTaskPhase.FAILED -> "Failed"
    BackgroundTaskPhase.CANCELLED -> "Cancelled"
}

internal fun backgroundTaskMeta(task: BackgroundTaskState, toolCalls: List<ToolCall>): String {
    val completed = maxOf(task.completedToolCount, toolCalls.count { it.isComplete })
    return buildList {
        if (completed > 0) add("$completed step${if (completed == 1) "" else "s"}")
        if (task.queuedCount > 0) add("+${task.queuedCount} queued")
    }.joinToString(" · ")
}

private val terminalBackgroundTaskPhases = setOf(
    BackgroundTaskPhase.COMPLETE,
    BackgroundTaskPhase.FAILED,
    BackgroundTaskPhase.CANCELLED,
)
