package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.ToolCall
import com.hermesandroid.relay.ui.components.pet.petObstacleSurface

private val TOOL_ACTIVITY_PET_ROUTES = setOf("chat")
private val FILE_EDIT_TOOLS = setOf("edit_file", "write_file", "apply_patch", "patch")
private val ATTENTION_TOOLS = setOf(
    "clarify",
    "delegate_task",
    "image_generate",
    "approval",
    "request_user_input",
    "ask_user",
    "sudo",
    "secret",
)

internal sealed interface ToolTranscriptItem {
    data class ActivityRun(val calls: List<ToolCall>) : ToolTranscriptItem
    data class Standalone(val call: ToolCall) : ToolTranscriptItem
}

/** Off suppresses diagnostics, never an attention or deliverable surface. */
internal fun ToolTranscriptItem.isVisibleForToolDisplay(toolDisplay: String): Boolean =
    this is ToolTranscriptItem.Standalone || toolDisplay != "off"

/**
 * Preserve source order while folding only routine activity. Calls that are
 * actionable, security-relevant, failed, or deliver a file/media result split
 * the run and retain their own surface.
 */
internal fun groupTranscriptTools(calls: List<ToolCall>): List<ToolTranscriptItem> {
    val result = mutableListOf<ToolTranscriptItem>()
    val run = mutableListOf<ToolCall>()

    fun flushRun() {
        if (run.isNotEmpty()) {
            result += ToolTranscriptItem.ActivityRun(run.toList())
            run.clear()
        }
    }

    calls.forEach { call ->
        if (call.requiresStandaloneToolSurface()) {
            flushRun()
            result += ToolTranscriptItem.Standalone(call)
        } else {
            run += call
        }
    }
    flushRun()
    return result
}

internal fun ToolCall.requiresStandaloneToolSurface(): Boolean {
    val normalized = name.trim().lowercase()
    return success == false ||
        !error.isNullOrBlank() ||
        outputRisk != null ||
        normalized in FILE_EDIT_TOOLS ||
        normalized in ATTENTION_TOOLS
}

internal data class ToolActivityCounts(
    val reads: Int = 0,
    val searches: Int = 0,
    val commands: Int = 0,
    val browserActions: Int = 0,
    val deviceActions: Int = 0,
    val other: Int = 0,
)

internal fun countToolActivity(calls: List<ToolCall>): ToolActivityCounts {
    var counts = ToolActivityCounts()
    calls.forEach { call ->
        val name = call.name.trim().lowercase()
        counts = when {
            name.contains("search") || name.contains("grep") || name.contains("find") ->
                counts.copy(searches = counts.searches + 1)
            name.contains("terminal") || name.contains("shell") || name.contains("exec") ||
                name.contains("command") || name.contains("bash") || name.contains("powershell") ->
                counts.copy(commands = counts.commands + 1)
            name.contains("browser") || name.contains("chrome") || name.contains("playwright") ||
                name.startsWith("web_") -> counts.copy(browserActions = counts.browserActions + 1)
            name.contains("android") || name.contains("adb") || name.contains("device") ||
                name.contains("tap") || name.contains("swipe") ->
                counts.copy(deviceActions = counts.deviceActions + 1)
            name.contains("read") || name.contains("list") || name.contains("glob") ||
                name.contains("open") || name.contains("inspect") -> counts.copy(reads = counts.reads + 1)
            else -> counts.copy(other = counts.other + 1)
        }
    }
    return counts
}

@Composable
fun ToolActivityRun(
    calls: List<ToolCall>,
    live: Boolean,
    detailed: Boolean,
    modifier: Modifier = Modifier,
    messageTimestamp: Long? = null,
    petObstacleKey: String? = null,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    if (calls.isEmpty()) return
    var expanded by rememberSaveable(calls.first().uiKey) { mutableStateOf(false) }
    val summary = toolActivitySummary(countToolActivity(calls), live)
    val stateLabel = if (expanded) {
        stringResource(R.string.tool_progress_cd_collapse)
    } else {
        stringResource(R.string.tool_progress_cd_expand)
    }
    val expansionState = if (expanded) {
        stringResource(R.string.tool_progress_state_expanded)
    } else {
        stringResource(R.string.tool_progress_state_collapsed)
    }

    Column(
        modifier = modifier
            .then(
                if (petObstacleKey != null) {
                    Modifier.petObstacleSurface(petObstacleKey, TOOL_ACTIVITY_PET_ROUTES)
                } else {
                    Modifier
                },
            )
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) {
                    expanded = !expanded
                    onExpandedChange(expanded)
                }
                .semantics {
                    contentDescription = summary
                    stateDescription = expansionState
                }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.MoreHoriz,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (live) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stateLabel,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (live && !expanded) {
            val latest = calls.last()
            AnimatedContent(
                targetState = latest.uiKey,
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn()) togetherWith
                        (slideOutVertically { -it / 2 } + fadeOut())
                },
                label = "toolActivityTicker",
            ) { latestKey ->
                val visible = calls.lastOrNull { it.uiKey == latestKey } ?: latest
                Text(
                    text = toolActivityTickerLabel(visible),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 26.dp, end = 4.dp, bottom = 4.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(start = 22.dp, top = 2.dp)) {
                calls.forEach { call ->
                    key(call.uiKey) {
                        if (detailed) {
                            ToolProgressCard(
                                toolCall = call,
                                messageTimestamp = messageTimestamp,
                                onExpandedChange = onExpandedChange,
                            )
                        } else {
                            CompactToolCall(toolCall = call)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun toolActivitySummary(counts: ToolActivityCounts, live: Boolean): String {
    val resources = LocalResources.current
    val clauses = buildList {
        fun addClause(count: Int, liveRes: Int, settledRes: Int) {
            if (count > 0) add(resources.getQuantityString(if (live) liveRes else settledRes, count, count))
        }
        addClause(counts.reads, R.plurals.tool_run_reading, R.plurals.tool_run_read)
        addClause(counts.searches, R.plurals.tool_run_searching, R.plurals.tool_run_searched)
        addClause(counts.commands, R.plurals.tool_run_running_commands, R.plurals.tool_run_ran_commands)
        addClause(counts.browserActions, R.plurals.tool_run_browsing, R.plurals.tool_run_browsed)
        addClause(counts.deviceActions, R.plurals.tool_run_using_device, R.plurals.tool_run_used_device)
        addClause(counts.other, R.plurals.tool_run_using_tools, R.plurals.tool_run_used_tools)
    }
    return clauses.joinToString(" · ")
}

@Composable
private fun toolActivityTickerLabel(call: ToolCall): String {
    val name = localizeToolName(call.name).ifBlank { stringResource(R.string.tool_preparing) }
    return when {
        call.isGenerating && !call.isComplete -> stringResource(R.string.tool_run_preparing, name)
        !call.isComplete -> stringResource(R.string.tool_run_running, name)
        else -> stringResource(R.string.tool_run_finished, name)
    }
}
