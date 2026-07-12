package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.ToolCall
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.relayMetadataStyle

/**
 * One subagent lane — the grouped render for every [ToolCall] sharing a
 * non-null [ToolCall.taskIndex], shown after top-level tool cards under the
 * streaming bubble. Grouping is a pure derivation in the caller
 * (`message.toolCalls.groupBy { it.taskIndex }`); the null group renders
 * exactly as before (flat ToolProgressCard / CompactToolCall).
 *
 * Anatomy mirrors the ToolProgressCard header (icon, 6dp gap, labelMedium
 * title, weight spacer, mono meta, expand chevron) with a 2dp guide rail
 * down the leading edge — tertiary while any child runs, hairline once done.
 * Children are ALWAYS [CompactToolCall] regardless of the toolDisplay pref:
 * full cards nested in a lane are visual noise on a phone width.
 *
 * Collapse behavior copies ToolProgressCard's auto-collapse: expanded while
 * any child is running, folds to the one-line summary header when the last
 * child completes ([LaunchedEffect] keyed on allComplete). A failed child
 * stamps the folded header with Close in the error tint instead of Check.
 *
 * Max depth is 1 — nested subagent-of-subagent calls arrive flattened into
 * their parent lane by the mapper (taskLabel prefixed "parent / child"), so
 * this component never indents twice.
 */
@Composable
fun SubagentLane(
    taskIndex: Int,
    calls: List<ToolCall>,
    modifier: Modifier = Modifier,
) {
    val anyRunning = calls.any { !it.isComplete }
    val allComplete = calls.isNotEmpty() && calls.all { it.isComplete }
    val anyFailed = calls.any { it.isComplete && it.success == false }
    val runningCount = calls.count { !it.isComplete }

    val laneLabel = calls.firstNotNullOfOrNull { call ->
        call.taskLabel?.takeIf { it.isNotBlank() }
    } ?: stringResource(R.string.subagent_lane_label_fallback, taskIndex + 1)

    // Lane duration: first child start → last child completion.
    val duration = run {
        val startedAt = calls.minOfOrNull { it.startedAt }
        val completedAt = calls.mapNotNull { it.completedAt }.maxOrNull()
        if (allComplete && startedAt != null && completedAt != null && completedAt >= startedAt) {
            String.format("%.1fs", (completedAt - startedAt) / 1000.0)
        } else null
    }

    val toolCountLabel = stringResource(R.string.subagent_lane_tool_count, calls.size)
    val statusMeta = when {
        anyRunning -> stringResource(R.string.subagent_lane_running_count, runningCount)
        duration != null -> "$toolCountLabel · $duration"
        else -> toolCountLabel
    }
    val laneA11y = stringResource(R.string.subagent_lane_a11y, laneLabel, statusMeta) +
        if (anyFailed) ", " + stringResource(R.string.cd_subagent_failed) else ""
    val completionLabel = stringResource(R.string.cd_subagent_completed)
    val collapseLabel = stringResource(R.string.cd_subagent_collapse)
    val expandLabel = stringResource(R.string.cd_subagent_expand)
    val failureLabel = stringResource(R.string.cd_subagent_failed)

    var expanded by remember { mutableStateOf(!allComplete) }

    // Auto-collapse when the last child completes — same pattern as
    // ToolProgressCard's LaunchedEffect(toolCall.isComplete).
    LaunchedEffect(allComplete) {
        if (allComplete) expanded = false
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp)
            .height(IntrinsicSize.Min)
            .semantics {
                contentDescription = laneA11y
            },
    ) {
        // Guide rail
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(
                    if (anyRunning) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                    else RelayRefresh.Line
                ),
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Lane header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.AccountTree,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = laneLabel,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                Text(
                    text = statusMeta,
                    style = relayMetadataStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (allComplete) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = if (anyFailed) Icons.Filled.Close else Icons.Filled.Check,
                        contentDescription = if (anyFailed) failureLabel else completionLabel,
                        modifier = Modifier.size(14.dp),
                        tint = if (anyFailed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) collapseLabel else expandLabel,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    calls.forEach { call ->
                        CompactToolCall(toolCall = call)
                    }
                }
            }
        }
    }
}
