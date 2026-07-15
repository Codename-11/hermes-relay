package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.ToolCall

@Composable
fun ToolProgressCard(
    toolCall: ToolCall,
    modifier: Modifier = Modifier,
    /**
     * Fallback wall-clock for the right-aligned header time when the call
     * has no completion stamp (history-restored calls) — usually the parent
     * message timestamp. Null hides the time.
     */
    messageTimestamp: Long? = null,
) {
    // Preparing (tool.generating) starts collapsed — the args preview line
    // under the header is the whole story until tool.start lands.
    // rememberSaveable (keyed per tool call, namespaced by the message item's
    // key in the chat LazyColumn) so a manual expand survives scroll-off /
    // re-render instead of snapping shut every time the row recomposes.
    var expanded by rememberSaveable(
        key = "toolcard:" + (toolCall.id ?: "${toolCall.name}:${toolCall.startedAt}"),
    ) { mutableStateOf(!toolCall.isComplete && !toolCall.isGenerating) }
    val isPreparing = toolCall.isGenerating && !toolCall.isComplete
    val timeMillis = toolCall.completedAt ?: messageTimestamp
    val locale = LocalLocale.current.platformLocale
    val timeLabel = timeMillis?.takeIf { toolCall.isComplete }?.let {
        remember(it, locale) {
            java.text.SimpleDateFormat("h:mm a", locale)
                .format(java.util.Date(it))
        }
    }

    // Auto-collapse when tool completes
    LaunchedEffect(toolCall.isComplete) {
        if (toolCall.isComplete) expanded = false
    }

    val statusIcon: ImageVector
    val statusColor = when {
        toolCall.isComplete && toolCall.success == true -> {
            statusIcon = Icons.Filled.Check
            MaterialTheme.colorScheme.primary
        }
        toolCall.isComplete && toolCall.success == false -> {
            statusIcon = Icons.Filled.Close
            MaterialTheme.colorScheme.error
        }
        // Args still streaming — "preparing" must read as LESS active than
        // running: Muted instead of tertiary (Cyan stays reserved for
        // actually-executing tools).
        isPreparing -> {
            statusIcon = Icons.Filled.MoreHoriz
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        else -> {
            statusIcon = Icons.Filled.HourglassTop
            MaterialTheme.colorScheme.tertiary
        }
    }

    val toolIcon = toolIcon(toolCall.name)
    val statusText = when {
        toolCall.isComplete && toolCall.success == true -> stringResource(R.string.tool_progress_status_completed)
        toolCall.isComplete && toolCall.success == false -> stringResource(R.string.tool_progress_status_failed)
        isPreparing -> stringResource(R.string.tool_preparing_a11y)
        else -> stringResource(R.string.tool_progress_status_running)
    }

    // Slow alpha breathe on the tool icon while preparing — an indeterminate
    // bar promises imminent work; a breathe says "being written".
    val toolIconAlpha = if (isPreparing) {
        val breathe = rememberInfiniteTransition(label = "toolGenerating")
        breathe.animateFloat(
            initialValue = 0.35f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(tween(900), repeatMode = RepeatMode.Reverse),
            label = "toolGeneratingAlpha",
        ).value
    } else 1f

    val duration = if (toolCall.completedAt != null && toolCall.completedAt >= toolCall.startedAt) {
        val seconds = (toolCall.completedAt - toolCall.startedAt) / 1000.0
        String.format("%.1fs", seconds)
    } else null
    val durationDescription = duration?.let { stringResource(R.string.tool_duration_a11y, it) }.orEmpty()
    val riskDescription = toolCall.outputRisk?.let {
        stringResource(R.string.tool_output_risk_a11y, it)
    }.orEmpty()
    val toolDescription = stringResource(R.string.tool_a11y, toolCall.name, statusText, durationDescription) +
        riskDescription

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = toolDescription
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tool type icon
                Icon(
                    imageVector = toolIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = toolIconAlpha)
                )

                Spacer(modifier = Modifier.width(6.dp))

                // Tool name — tool.generating may arrive nameless
                Text(
                    text = if (isPreparing) toolCall.name.ifBlank { "Preparing tool…" } else toolCall.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isPreparing) MaterialTheme.colorScheme.onSurfaceVariant
                        else androidx.compose.ui.graphics.Color.Unspecified,
                    modifier = Modifier.weight(1f)
                )

                // Duration + completion time ("3.1s · 5:32 PM")
                val metaLabel = listOfNotNull(duration, timeLabel).joinToString(" · ")
                if (metaLabel.isNotEmpty()) {
                    Text(
                        text = metaLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }

                // Status icon — crossfade so MoreHoriz→HourglassTop on
                // tool.start reads as a state change, not a card swap.
                Crossfade(targetState = statusIcon, label = "toolStatusIcon") { icon ->
                    Icon(
                        imageVector = icon,
                        contentDescription = statusText,
                        tint = statusColor,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Expand/collapse
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            toolCall.outputRisk?.let { risk ->
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.tool_output_risk_badge, risk.uppercase()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // One-line faded mono args preview while preparing — partial
            // JSON grows per delta; no expand needed, the card stays folded.
            if (isPreparing && !toolCall.args.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = compactToolDetail(toolCall.args, 80),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Progress bar while running (not while args are still streaming)
            if (!toolCall.isComplete && !isPreparing) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            // Expandable details
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    toolCall.runId?.takeIf { it.isNotBlank() }?.let { runId ->
                        Text(
                            text = stringResource(R.string.tool_run_id, runId),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    toolCall.provenance?.takeIf { it.isNotBlank() }?.let { provenance ->
                        if (toolCall.runId != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text(
                            text = provenance,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (toolCall.outputRiskFindings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.tool_output_risk_findings),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = toolCall.outputRiskFindings.joinToString(separator = "\n") { "• $it" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (toolCall.outputRiskRedacted) {
                        Text(
                            text = stringResource(R.string.tool_output_risk_redacted),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    // Arguments
                    toolCall.args?.let { args ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.tool_arguments),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = args,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    // Error (tool.failed)
                    toolCall.error?.let { error ->
                        val preview = compactToolDetail(error)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.tool_error),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    // Result
                    toolCall.result?.let { result ->
                        val preview = compactToolDetail(result)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.tool_result),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

internal fun toolIcon(toolName: String): ImageVector = when {
    toolName.contains("screenshot") || toolName.contains("read_screen") || toolName.contains("vision") -> Icons.Filled.Description
    toolName.contains("tap") || toolName.contains("swipe") || toolName.contains("click") || toolName.contains("scroll") -> Icons.Filled.TouchApp
    toolName.contains("type") || toolName.contains("input") || toolName.contains("keyboard") -> Icons.Filled.Keyboard
    toolName.contains("launch") || toolName.contains("open") -> Icons.Filled.OpenInNew
    toolName.contains("bash") || toolName.contains("terminal") || toolName.contains("execute") || toolName.contains("shell") -> Icons.Filled.Code
    toolName.contains("file") || toolName.contains("read") || toolName.contains("write") -> Icons.Filled.Description
    toolName.contains("search") || toolName.contains("web") -> Icons.Filled.Search
    else -> Icons.Filled.Build
}

internal fun compactToolDetail(value: String, maxChars: Int = 700): String {
    val compact = value
        .replace(Regex("\\s+"), " ")
        .trim()
    if (compact.length <= maxChars) return compact
    return compact.take(maxChars.coerceAtLeast(80)).trimEnd() + "..."
}
