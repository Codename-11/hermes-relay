package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.network.upstream.GatewayProcess
import com.hermesandroid.relay.viewmodel.SubagentActivity
import com.hermesandroid.relay.viewmodel.SubagentActivityPhase
import com.hermesandroid.relay.viewmodel.SubagentChildPreview
import kotlinx.coroutines.launch

/**
 * Composer-adjacent summary of upstream Hermes processes for the active chat.
 * The process registry is session-scoped, so this deliberately does not live
 * in the global session/navigation drawer.
 */
@Composable
internal fun GatewayBackgroundProcessStrip(
    processes: List<GatewayProcess>,
    subagentActivities: List<SubagentActivity>,
    subagentPreviewVisibility: SubagentPreviewVisibility,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Initial/switch refreshes are silent. The strip appears only after the
    // session actually owns a process, avoiding a transient "Checking" row on
    // every ordinary chat open.
    val visibleActivities = subagentActivities.takeIf { subagentPreviewVisibility.showLifecycle }.orEmpty()
    if (processes.isEmpty() && visibleActivities.isEmpty()) return

    val running = processes.count { it.isRunning }
    val runningAgents = visibleActivities.count { !it.isTerminal }
    val failed = processes.count { !it.isRunning && (it.exitCode ?: 0) != 0 }
    val failedAgents = visibleActivities.count { it.phase == SubagentActivityPhase.FAILED }
    val interruptedAgents = visibleActivities.count {
        it.phase == SubagentActivityPhase.INTERRUPTED ||
            it.phase == SubagentActivityPhase.ENDED_WITH_PARENT
    }
    val failureCount = failed + failedAgents
    val status = when {
        runningAgents > 0 -> stringResource(R.string.subagent_lane_running_count, runningAgents)
        running > 0 -> "$running ${stringResource(R.string.bg_processes_running)}"
        failureCount > 0 -> "$failureCount ${stringResource(R.string.task_status_failed)}"
        interruptedAgents > 0 -> stringResource(R.string.agent_activity_status_interrupted)
        else -> stringResource(R.string.task_status_complete)
    }
    val openDescription = stringResource(R.string.current_chat_activity_open)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription =
                    "$openDescription, $status"
                stateDescription = status
            }
            .clickable(
                onClickLabel = openDescription,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running > 0 || runningAgents > 0 || loading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = when {
                        failureCount > 0 -> Icons.Filled.ErrorOutline
                        interruptedAgents > 0 -> Icons.Filled.PauseCircleOutline
                        else -> Icons.Filled.CheckCircle
                    },
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = when {
                        failureCount > 0 -> MaterialTheme.colorScheme.error
                        interruptedAgents > 0 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }
            Spacer(Modifier.width(9.dp))
            Text(
                text = stringResource(R.string.current_chat_activity_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = if (failureCount > 0 && running == 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Icon(
                imageVector = Icons.Filled.Visibility,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Mobile analogue of Hermes Desktop's composer process stack + terminal viewer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GatewayBackgroundProcessSheet(
    processes: List<GatewayProcess>,
    subagentActivities: List<SubagentActivity>,
    subagentChildPreview: SubagentChildPreview?,
    subagentPreviewVisibility: SubagentPreviewVisibility,
    loading: Boolean,
    stoppingProcessIds: Set<String>,
    onRefresh: () -> Unit,
    onStop: (String) -> Unit,
    onDismissProcess: (String) -> Unit,
    onOpenSubagentChild: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    var expandedAgentKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    val running = processes.filter { it.isRunning }
    val recent = processes.filterNot { it.isRunning }
    val visibleActivities = subagentActivities.takeIf { subagentPreviewVisibility.showLifecycle }.orEmpty()
    val followTarget = subagentActivityFollowTarget(
        visibleActivities,
        expandedAgentKeys,
        subagentPreviewVisibility,
        subagentChildPreview,
    )
    val activityRevision = visibleActivities.sumOf { it.revision } +
        subagentChildPreview?.messages.orEmpty().sumOf { message ->
            message.content.length + message.thinkingContent.length +
                message.toolCalls.sumOf { (it.args?.length ?: 0) + it.name.length }
        }
    val nearActivityTail by remember(followTarget, listState) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            followTarget >= 0 && last in maxOf(0, followTarget - 2)..followTarget
        }
    }
    var followAgentTail by remember { mutableStateOf(true) }

    LaunchedEffect(listState, followTarget) {
        snapshotFlow { listState.isScrollInProgress to nearActivityTail }.collect { (scrolling, nearTail) ->
            if (scrolling) followAgentTail = nearTail
            else if (nearTail) followAgentTail = true
        }
    }
    LaunchedEffect(activityRevision, followTarget) {
        if (followAgentTail && followTarget >= 0) {
            listState.scrollToItem(followTarget)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.current_chat_activity_title),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        stringResource(R.string.current_chat_activity_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (processes.isNotEmpty()) IconButton(onClick = onRefresh, enabled = !loading) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.background_processes_refresh_a11y))
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.current_chat_activity_close),
                    )
                }
            }
            if (!followAgentTail && followTarget >= 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = {
                        followAgentTail = true
                        scope.launch { listState.animateScrollToItem(followTarget) }
                    }) {
                        Text(stringResource(R.string.current_chat_activity_latest))
                    }
                }
            }

            if (processes.isEmpty() && visibleActivities.isEmpty() && !loading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.current_chat_activity_empty),
                        modifier = Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp),
                ) {
                    subagentActivityItems(
                        activities = visibleActivities,
                        expandedKeys = expandedAgentKeys,
                        visibility = subagentPreviewVisibility,
                        childPreview = subagentChildPreview,
                        onToggle = { key ->
                            expandedAgentKeys = if (key in expandedAgentKeys) {
                                expandedAgentKeys - key
                            } else {
                                expandedAgentKeys + key
                            }
                        },
                        onOpenChild = onOpenSubagentChild,
                    )
                    if (visibleActivities.isNotEmpty() && processes.isNotEmpty()) {
                        item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
                        item {
                            ProcessSectionLabel(
                                stringResource(R.string.background_processes_title),
                                processes.size,
                            )
                        }
                    }
                    if (running.isNotEmpty()) {
                        item { ProcessSectionLabel(stringResource(R.string.bg_processes_running), running.size) }
                        items(running, key = { it.id }) { process ->
                            GatewayProcessRow(
                                process = process,
                                stopping = process.id in stoppingProcessIds,
                                onStop = { onStop(process.id) },
                                onDismiss = null,
                            )
                        }
                    }
                    if (running.isNotEmpty() && recent.isNotEmpty()) {
                        item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
                    }
                    if (recent.isNotEmpty()) {
                        item { ProcessSectionLabel(stringResource(R.string.bg_processes_recent), recent.size) }
                        items(recent, key = { it.id }) { process ->
                            GatewayProcessRow(
                                process = process,
                                stopping = false,
                                onStop = null,
                                onDismiss = { onDismissProcess(process.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessSectionLabel(label: String, count: Int) {
    Text(
        text = "$label · $count",
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GatewayProcessRow(
    process: GatewayProcess,
    stopping: Boolean,
    onStop: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
) {
    var expanded by remember(process.id) { mutableStateOf(false) }
    val failed = !process.isRunning && (process.exitCode ?: 0) != 0
    val output = sanitizeTerminalText(
        process.outputTail.orEmpty().ifBlank { process.outputPreview.orEmpty() },
    ).trimEnd()
    val command = sanitizeTerminalText(process.command)
        .lineSequence()
        .firstOrNull()
        ?.trim()
        .orEmpty()
        .ifBlank {
            stringResource(R.string.bg_processes_title)
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(
                enabled = output.isNotBlank(),
                onClickLabel = if (expanded) stringResource(R.string.bg_collapse_output) else stringResource(R.string.bg_expand_output),
            ) { expanded = !expanded }
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProcessStateIcon(process = process, failed = failed, stopping = stopping)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            ) {
                Text(
                    text = command,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = processMetadata(process, failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onStop != null) {
                TextButton(onClick = onStop, enabled = !stopping) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (stopping) stringResource(R.string.background_processes_stopping) else stringResource(R.string.background_processes_stop))
                }
            } else if (onDismiss != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_dismiss)) }
            }
            if (output.isNotBlank()) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.tool_progress_cd_collapse) else stringResource(R.string.tool_progress_cd_expand),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(visible = expanded && output.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = output,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProcessStateIcon(process: GatewayProcess, failed: Boolean, stopping: Boolean) {
    val tint: Color = when {
        failed -> MaterialTheme.colorScheme.error
        process.isRunning || stopping -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    Surface(
        modifier = Modifier.size(30.dp),
        shape = CircleShape,
        color = tint.copy(alpha = 0.12f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                process.isRunning || stopping -> CircularProgressIndicator(
                    modifier = Modifier.size(17.dp),
                    strokeWidth = 2.dp,
                    color = tint,
                )
                failed -> Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = stringResource(R.string.tool_failed_a11y),
                    modifier = Modifier.size(18.dp),
                    tint = tint,
                )
                else -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.tool_completed_a11y),
                    modifier = Modifier.size(18.dp),
                    tint = tint,
                )
            }
        }
    }
}

@Composable
private fun processMetadata(process: GatewayProcess, failed: Boolean): String {
    val state = when {
        process.isRunning -> stringResource(R.string.bg_processes_running)
        failed -> stringResource(R.string.task_status_failed) +
            process.exitCode?.let { " · exit $it" }.orEmpty()
        else -> stringResource(R.string.task_status_complete) +
            process.exitCode?.let { " · exit $it" }.orEmpty()
    }
    return "$state · ${formatElapsed(process.uptimeSeconds)}" +
        if (process.detached) " · recovered" else ""
}

private val ansiTerminalEscape = Regex(
    "\u001B(?:\\].*?(?:\u0007|\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[ -/]*[@-~])",
    RegexOption.DOT_MATCHES_ALL,
)
private val unterminatedOsc = Regex("\u001B\\][^\\n]*")

/** Plain-text mobile output viewer: remove terminal control/ANSI while keeping layout text. */
internal fun sanitizeTerminalText(raw: String): String {
    val withoutAnsi = unterminatedOsc.replace(
        ansiTerminalEscape.replace(raw, ""),
        "",
    )
    return withoutAnsi
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .filter { char ->
            char == '\n' || char == '\t' || (char.code >= 0x20 && char.code != 0x7F)
        }
}

internal fun formatElapsed(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainder = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${remainder}s"
        else -> "${remainder}s"
    }
}
