package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.ToolCall

/** Stable, turn-level phases for compact thinking and tool activity. */
enum class ToolActivityPhase {
    THINKING,
    USING_TOOLS,
    COMPLETED,
    FAILED,
}

/**
 * UI input for one assistant turn's compact activity stack.
 *
 * [elapsedMillis] is presentation-only. It is deliberately excluded from the
 * accessibility state so a ticking timer does not create repeated TalkBack
 * announcements. Likewise, streamed [thinkingContent] is never a live region.
 */
data class ToolActivityState(
    val phase: ToolActivityPhase,
    val toolCalls: List<ToolCall> = emptyList(),
    val thinkingContent: String? = null,
    val elapsedMillis: Long? = null,
) {
    val activeCount: Int get() = toolCalls.count { !it.isComplete }
    val completedCount: Int get() = toolCalls.count { it.isComplete }
    val failedCount: Int get() = toolCalls.count { it.isComplete && it.success == false }
}

/**
 * Collapses a turn's thinking and tool calls into one stable transcript row.
 *
 * Integration: place this once per assistant turn and pass the message's
 * stable `uiKey` as [activityKey]. The stack collapses automatically when a
 * live phase becomes terminal, but completed history can still be expanded.
 * [onExpandedChange] lets the transcript temporarily yield bottom-follow while
 * the user inspects activity details.
 */
@Composable
fun ToolActivityStack(
    state: ToolActivityState,
    modifier: Modifier = Modifier,
    activityKey: String? = null,
    messageTimestamp: Long? = null,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    var expanded by rememberSaveable(activityKey) { mutableStateOf(false) }
    val terminal = state.phase == ToolActivityPhase.COMPLETED ||
        state.phase == ToolActivityPhase.FAILED

    LaunchedEffect(state.phase) {
        if (terminal && expanded) {
            expanded = false
            onExpandedChange(false)
        }
    }

    val presentation = toolActivityPresentation(state)
    val elapsed = state.elapsedMillis?.let(::formatToolActivityElapsed)
    val visibleSummary = listOfNotNull(presentation.visibleSummary, elapsed)
        .joinToString(separator = " · ")
    val expandLabel = stringResource(
        if (expanded) R.string.tool_activity_collapse else R.string.tool_activity_expand,
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(
                        onClickLabel = expandLabel,
                        role = Role.Button,
                    ) {
                        expanded = !expanded
                        onExpandedChange(expanded)
                    }
                    .semantics {
                        // Only phase/count transitions are announced. Token and
                        // elapsed-time changes intentionally do not alter this node.
                        contentDescription = expandLabel
                        liveRegion = LiveRegionMode.Polite
                        stateDescription = presentation.accessibilityState
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = presentation.icon,
                    contentDescription = null,
                    tint = presentation.tint,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = presentation.phaseLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = visibleSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            if (!terminal && (state.phase == ToolActivityPhase.THINKING || state.activeCount > 0)) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.thinkingContent
                        ?.takeIf { it.isNotBlank() }
                        ?.let { thinking ->
                            Text(
                                text = stringResource(R.string.thinking_title),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                text = thinking,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Default,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                    state.toolCalls.forEach { toolCall ->
                        ToolProgressCard(
                            toolCall = toolCall,
                            messageTimestamp = messageTimestamp,
                        )
                    }
                }
            }
        }
    }
}

private data class ToolActivityPresentation(
    val phaseLabel: String,
    val visibleSummary: String,
    val accessibilityState: String,
    val icon: ImageVector,
    val tint: Color,
)

@Composable
private fun toolActivityPresentation(state: ToolActivityState): ToolActivityPresentation {
    val active = pluralStringResource(
        R.plurals.tool_activity_active_count,
        state.activeCount,
        state.activeCount,
    )
    val completed = pluralStringResource(
        R.plurals.tool_activity_completed_count,
        state.completedCount,
        state.completedCount,
    )
    val used = pluralStringResource(
        R.plurals.tool_activity_used_count,
        state.toolCalls.size,
        state.toolCalls.size,
    )
    val counts = listOf(active, completed).joinToString(" · ")
    val accessibleCounts = listOf(active, completed).joinToString(", ")

    return when (state.phase) {
        ToolActivityPhase.THINKING -> ToolActivityPresentation(
            phaseLabel = stringResource(R.string.tool_activity_thinking),
            visibleSummary = if (state.toolCalls.isEmpty()) {
                stringResource(R.string.tool_activity_working)
            } else {
                counts
            },
            accessibilityState = if (state.toolCalls.isEmpty()) {
                stringResource(R.string.tool_activity_thinking)
            } else {
                stringResource(R.string.tool_activity_thinking_state, accessibleCounts)
            },
            icon = Icons.Filled.Psychology,
            tint = MaterialTheme.colorScheme.tertiary,
        )
        ToolActivityPhase.USING_TOOLS -> ToolActivityPresentation(
            phaseLabel = stringResource(R.string.tool_activity_running_tools),
            visibleSummary = counts,
            accessibilityState = stringResource(R.string.tool_activity_running_state, accessibleCounts),
            icon = Icons.Filled.Build,
            tint = MaterialTheme.colorScheme.tertiary,
        )
        ToolActivityPhase.COMPLETED -> ToolActivityPresentation(
            phaseLabel = stringResource(R.string.tool_activity_completed),
            visibleSummary = if (state.toolCalls.isEmpty()) {
                stringResource(R.string.thinking_title)
            } else {
                used
            },
            accessibilityState = stringResource(
                R.string.tool_activity_completed_state,
                if (state.toolCalls.isEmpty()) stringResource(R.string.thinking_title) else used,
            ),
            icon = Icons.Filled.Check,
            tint = MaterialTheme.colorScheme.primary,
        )
        ToolActivityPhase.FAILED -> ToolActivityPresentation(
            phaseLabel = stringResource(R.string.tool_activity_failed),
            visibleSummary = if (state.toolCalls.isEmpty()) {
                stringResource(R.string.tool_activity_needs_attention)
            } else {
                counts
            },
            accessibilityState = if (state.toolCalls.isEmpty()) {
                stringResource(R.string.tool_activity_failed)
            } else {
                stringResource(R.string.tool_activity_failed_state, accessibleCounts)
            },
            icon = Icons.Filled.ErrorOutline,
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

internal fun formatToolActivityElapsed(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "%dh %02dm".format(hours, minutes)
        minutes > 0L -> "%dm %02ds".format(minutes, seconds)
        else -> "%ds".format(seconds)
    }
}
