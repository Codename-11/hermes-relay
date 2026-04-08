package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermesandroid.relay.data.AppAnalytics
import com.hermesandroid.relay.data.AppStats

// Brand gradient colors
private val GradientStart = Color(0xFF9B6BF0)
private val GradientEnd = Color(0xFF6B35E8)

@Composable
fun StatsForNerds() {
    val appStats by AppAnalytics.stats.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row — tap to expand/collapse
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Analytics",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row {
                    if (expanded) {
                        TextButton(onClick = { showResetDialog = true }) {
                            Text("Reset", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Collapse" else "Expand")
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Summary line always visible
            val totalTokens = appStats.totalTokensIn + appStats.totalTokensOut
            val tokensPerMsg = if (appStats.totalMessagesSent > 0)
                totalTokens / appStats.totalMessagesSent else 0L
            Text(
                text = "${appStats.totalMessagesSent} messages | " +
                    "${formatTokenCount(totalTokens)} tokens" +
                    (if (tokensPerMsg > 0) " (~${formatTokenCount(tokensPerMsg)}/msg)" else "") +
                    " | ${appStats.sessionCount} sessions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    // -- Response Time Chart (TTFT) --
                    if (appStats.recentResponseTimesMs.isNotEmpty()) {
                        ChartSection(
                            title = "Time to First Token",
                            data = appStats.recentResponseTimesMs,
                            unit = "ms"
                        )
                    }

                    // -- Completion Time Chart --
                    if (appStats.recentCompletionTimesMs.isNotEmpty()) {
                        ChartSection(
                            title = "Completion Time",
                            data = appStats.recentCompletionTimesMs,
                            unit = "ms"
                        )
                    }

                    HorizontalDivider()

                    // -- Token Usage --
                    TokenUsageSection(appStats)

                    HorizontalDivider()

                    // -- Connection Health --
                    ConnectionHealthSection(appStats)

                    HorizontalDivider()

                    // -- Stream Stats --
                    StreamStatsSection(appStats)
                }
            }
        }
    }

    // Reset confirmation dialog
    if (showResetDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Analytics?") },
            text = { Text("This will clear all recorded stats including token counts, response times, and stream history. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    AppAnalytics.resetAll()
                    showResetDialog = false
                }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// --- Chart ---

@Composable
private fun ChartSection(
    title: String,
    data: List<Long>,
    unit: String
) {
    val minVal = data.minOrNull() ?: 0L
    val maxVal = data.maxOrNull() ?: 0L
    val avgVal = if (data.isNotEmpty()) data.sum() / data.size else 0L
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Min / Avg / Max labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatLabel("Min", formatMsWithSeconds(minVal))
            StatLabel("Avg", formatMsWithSeconds(avgVal))
            StatLabel("Max", formatMsWithSeconds(maxVal))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Bar chart drawn with Canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
        ) {
            val barCount = data.size
            if (barCount == 0) return@Canvas

            val chartMax = maxVal.coerceAtLeast(1L)
            val barSpacing = 2.dp.toPx()
            val totalSpacing = barSpacing * (barCount - 1).coerceAtLeast(0)
            val barWidth = ((size.width - totalSpacing) / barCount).coerceAtLeast(2f)
            val bottomPadding = 16.dp.toPx()
            val chartHeight = size.height - bottomPadding

            val gradient = Brush.verticalGradient(
                colors = listOf(GradientStart, GradientEnd),
                startY = 0f,
                endY = chartHeight
            )

            // Draw bars
            data.forEachIndexed { index, value ->
                val barHeight = (value.toFloat() / chartMax) * chartHeight
                val x = index * (barWidth + barSpacing)
                val y = chartHeight - barHeight

                drawRect(
                    brush = gradient,
                    topLeft = Offset(x, y),
                    size = Size(barWidth, barHeight)
                )
            }

            // Draw average line
            val avgY = chartHeight - (avgVal.toFloat() / chartMax) * chartHeight
            drawLine(
                color = GradientStart.copy(alpha = 0.5f),
                start = Offset(0f, avgY),
                end = Offset(size.width, avgY),
                strokeWidth = 1.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    floatArrayOf(8f, 6f), 0f
                )
            )

            // X-axis label area
            val textPaint = android.graphics.Paint().apply {
                color = labelColor.hashCode()
                textSize = 9.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }

            // Draw a few index labels (first, middle, last)
            val indicesToLabel = when {
                barCount <= 5 -> data.indices.toList()
                else -> listOf(0, barCount / 2, barCount - 1)
            }
            indicesToLabel.forEach { idx ->
                val x = idx * (barWidth + barSpacing) + barWidth / 2
                drawContext.canvas.nativeCanvas.drawText(
                    "${idx + 1}",
                    x,
                    size.height,
                    textPaint
                )
            }
        }
    }
}

@Composable
private fun StatLabel(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- Token Usage ---

@Composable
private fun TokenUsageSection(stats: AppStats) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Token Usage",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )

        // Current session
        Text(
            text = "Current Session",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TokenStat("In", formatTokenCount(stats.currentSessionTokensIn))
            TokenStat("Out", formatTokenCount(stats.currentSessionTokensOut))
            TokenStat("Total", formatTokenCount(stats.currentSessionTokensIn + stats.currentSessionTokensOut))
        }

        // Lifetime
        Text(
            text = "Lifetime",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TokenStat("In", formatTokenCount(stats.totalTokensIn))
            TokenStat("Out", formatTokenCount(stats.totalTokensOut))
            TokenStat("Total", formatTokenCount(stats.totalTokensIn + stats.totalTokensOut))
        }
    }
}

@Composable
private fun TokenStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- Connection Health ---

@Composable
private fun ConnectionHealthSection(stats: AppStats) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Connection Health",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )

        if (stats.healthChecksTotal == 0) {
            Text(
                text = "No health checks recorded yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Success rate bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Success rate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${(stats.healthCheckSuccessRate * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        stats.healthCheckSuccessRate >= 0.9f -> MaterialTheme.colorScheme.primary
                        stats.healthCheckSuccessRate >= 0.5f -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
            }
            LinearProgressIndicator(
                progress = { stats.healthCheckSuccessRate },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )

            // Avg latency
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Avg latency",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatMsWithSeconds(stats.avgHealthLatencyMs),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Total checks
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Total checks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${stats.healthChecksTotal}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// --- Stream Stats ---

@Composable
private fun StreamStatsSection(stats: AppStats) {
    val totalStreams = stats.streamsCompleted + stats.streamsErrored + stats.streamsCancelled

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Stream Stats",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )

        if (totalStreams == 0) {
            Text(
                text = "No streams recorded yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // Success rate bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Success rate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${(stats.streamSuccessRate * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        stats.streamSuccessRate >= 0.9f -> MaterialTheme.colorScheme.primary
                        stats.streamSuccessRate >= 0.5f -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
            }
            LinearProgressIndicator(
                progress = { stats.streamSuccessRate },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Breakdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StreamCounter("Completed", stats.streamsCompleted, MaterialTheme.colorScheme.primary)
                StreamCounter("Errored", stats.streamsErrored, MaterialTheme.colorScheme.error)
                StreamCounter("Cancelled", stats.streamsCancelled, MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Avg times
            if (stats.avgResponseTimeMs > 0) {
                val peakTtft = stats.recentResponseTimesMs.maxOrNull() ?: 0L
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Avg TTFT",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatMsWithSeconds(stats.avgResponseTimeMs),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (peakTtft > stats.avgResponseTimeMs) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Peak TTFT",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatMsWithSeconds(peakTtft),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
            if (stats.avgCompletionTimeMs > 0) {
                val worstCompletion = stats.recentCompletionTimesMs.maxOrNull() ?: 0L
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Avg completion",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatMsWithSeconds(stats.avgCompletionTimeMs),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (worstCompletion > stats.avgCompletionTimeMs) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Slowest",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatMsWithSeconds(worstCompletion),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamCounter(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// --- Formatting helpers ---

private fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}.${(tokens % 1_000_000) / 100_000}M"
    tokens >= 1_000 -> "${tokens / 1_000}.${(tokens % 1_000) / 100}k"
    else -> "$tokens"
}

private fun formatDuration(ms: Long): String = when {
    ms >= 60_000 -> "${ms / 60_000}m ${(ms % 60_000) / 1_000}s"
    ms >= 1_000 -> "${ms / 1_000}.${(ms % 1_000) / 100}s"
    else -> "${ms}ms"
}

/** Format ms with seconds subtext: "1234ms (1.2s)" */
private fun formatMsWithSeconds(ms: Long): String = when {
    ms >= 1_000 -> "${ms}ms (${"%.1f".format(ms / 1000.0)}s)"
    else -> "${ms}ms"
}
