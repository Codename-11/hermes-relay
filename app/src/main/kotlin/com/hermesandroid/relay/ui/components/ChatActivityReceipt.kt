package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.ChatActivityKind
import com.hermesandroid.relay.data.ChatActivityPhase
import com.hermesandroid.relay.data.ChatActivityRecord

/** Stable transcript entry into the same read-only preview used by active work. */
@Composable
internal fun ChatActivityReceipt(
    record: ChatActivityRecord,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subagents = record.kind == ChatActivityKind.SUBAGENTS
    val title = stringResource(
        if (subagents) R.string.chat_activity_receipt_subagents else R.string.chat_activity_receipt_process,
    )
    val action = stringResource(
        if (subagents) R.string.chat_activity_receipt_view_activity else R.string.chat_activity_receipt_view_output,
    )
    val status = if (subagents && record.children.isNotEmpty()) {
        val groups = record.children.groupingBy { it.phase }.eachCount().toMutableMap()
        val missing = (record.taskCount - record.children.size).coerceAtLeast(0)
        if (missing > 0) groups[ChatActivityPhase.UNKNOWN] = (groups[ChatActivityPhase.UNKNOWN] ?: 0) + missing
        val labels = groups.map { (phase, count) ->
            stringResource(R.string.chat_activity_receipt_count_phase, count, activityPhaseLabel(phase))
        }
        labels.joinToString(" · ")
    } else if (subagents && record.taskCount > 0) {
        stringResource(R.string.chat_activity_receipt_count_phase, record.taskCount, activityPhaseLabel(record.phase))
    } else {
        activityPhaseLabel(record.phase)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) { stateDescription = status }
            .clickable(role = Role.Button, onClickLabel = action, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (subagents) Icons.Filled.AccountTree else Icons.Filled.Terminal,
                contentDescription = null,
                tint = if (record.phase == ChatActivityPhase.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$title · $status",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = action,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun activityPhaseLabel(phase: ChatActivityPhase): String = stringResource(
    when (phase) {
        ChatActivityPhase.RUNNING -> R.string.bg_processes_running
        ChatActivityPhase.COMPLETE -> R.string.agent_activity_status_completed
        ChatActivityPhase.FAILED -> R.string.agent_activity_status_failed
        ChatActivityPhase.CANCELLED -> R.string.task_status_cancelled
        ChatActivityPhase.UNKNOWN -> R.string.agent_activity_status_unavailable
    },
)
