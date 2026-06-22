package com.hermesandroid.relay.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticLogEntry
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog

@Composable
fun DiagnosticsLogPanel(
    modifier: Modifier = Modifier,
    title: String = "Recent activity",
    categories: Set<DiagnosticCategory>? = null,
    limit: Int = 8,
    showCategory: Boolean = false,
    showClear: Boolean = false,
    showSeverityFilter: Boolean = false,
) {
    val entries by DiagnosticsLog.entries.collectAsState()

    // Self-contained detail-view state — tapping a row opens DiagnosticDetailDialog.
    // No nav route; nothing to wire in RelayApp.
    var selected by remember { mutableStateOf<DiagnosticLogEntry?>(null) }
    // Optional severity filter, local to the panel (null = all severities).
    var severityFilter by remember { mutableStateOf<DiagnosticSeverity?>(null) }

    val visible = entries
        .asReversed()
        .filter { categories == null || it.category in categories }
        .filter { severityFilter == null || it.severity == severityFilter }
        .take(limit.coerceAtLeast(0))

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (showClear && entries.isNotEmpty()) {
                TextButton(onClick = { DiagnosticsLog.clear() }) {
                    Text("Clear")
                }
            }
        }

        if (showSeverityFilter) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = severityFilter == null,
                    onClick = { severityFilter = null },
                    label = { Text("All") },
                )
                DiagnosticSeverity.entries.forEach { sev ->
                    FilterChip(
                        selected = severityFilter == sev,
                        onClick = { severityFilter = if (severityFilter == sev) null else sev },
                        label = { Text(sev.name) },
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            Text(
                text = "No recent activity",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    visible.forEachIndexed { index, entry ->
                        DiagnosticLogRow(
                            entry = entry,
                            showCategory = showCategory,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = entry }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                        if (index != visible.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        DiagnosticDetailDialog(entry = entry, onDismiss = { selected = null })
    }
}

@Composable
private fun DiagnosticLogRow(
    entry: DiagnosticLogEntry,
    showCategory: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(severityColor(entry.severity)),
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (showCategory) {
                        "${entry.category.label} - ${entry.title}"
                    } else {
                        entry.title
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (entry.severity != DiagnosticSeverity.Info) {
                    DiagnosticSeverityChip(entry.severity)
                }
                Text(
                    text = DateFormat.format("HH:mm:ss", entry.timestampMs).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }

            val detail = entry.detailLine()
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(modifier = Modifier.height(1.dp))
            }
        }
    }
}

@Composable
private fun severityColor(severity: DiagnosticSeverity): Color = when (severity) {
    DiagnosticSeverity.Info -> MaterialTheme.colorScheme.primary
    DiagnosticSeverity.Warning -> MaterialTheme.colorScheme.tertiary
    DiagnosticSeverity.Error -> MaterialTheme.colorScheme.error
}

private fun DiagnosticLogEntry.detailLine(): String? {
    val pieces = listOfNotNull(
        detail,
        endpointRole?.let { "route=$it" },
        url,
        elapsedMs?.let { "${it}ms" },
    )
    return pieces.joinToString(" - ").takeIf { it.isNotBlank() }
}
