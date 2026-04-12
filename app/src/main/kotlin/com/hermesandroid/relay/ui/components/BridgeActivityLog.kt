package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.InsertPhoto
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.BridgeActivityEntry
import com.hermesandroid.relay.data.BridgeActivityStatus
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Scrollable activity log card — rolling view of the most recent bridge
 * commands with timestamp, method, status icon, and tap-to-expand detail.
 *
 * Phase 3 Wave 1 — bridge-ui (`bridge-screen-ui`). The log is populated via
 * [com.hermesandroid.relay.data.BridgePreferencesRepository.appendEntry];
 * accessibility's command dispatcher is the producer once its runtime is wired. Until
 * then this card shows the "no activity yet" empty state.
 *
 * Height-bounded with [heightIn] to keep the log from pushing the safety
 * card off the fold on phone-portrait screens — the activity log is
 * internally scrollable within its own [LazyColumn].
 */
@Composable
fun BridgeActivityLog(
    entries: List<BridgeActivityEntry>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Timeline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = "Activity Log",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Clear") }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

            if (entries.isEmpty()) {
                Text(
                    text = "No bridge commands yet. Every tap, type, and " +
                        "screenshot the agent performs will show up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Hard cap the visible height so the log doesn't eat the whole
                // screen — internally scrollable for history.
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        ActivityRow(entry = entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityRow(entry: BridgeActivityEntry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            StatusDot(status = entry.status)
            Text(
                text = formatTime(entry.timestampMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = entry.method,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (entry.thumbnailToken != null) {
                Icon(
                    imageVector = Icons.Filled.InsertPhoto,
                    contentDescription = "Has screenshot",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 28.dp, top = 6.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Full timestamp: ${formatFullTime(entry.timestampMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Status: ${entry.status.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!entry.resultText.isNullOrBlank()) {
                    Text(
                        text = entry.resultText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (entry.thumbnailToken != null) {
                    // TODO(accessibility-handoff): wire this to InboundAttachmentCard
                    // once accessibility's ScreenCapture.kt is uploading real screenshots
                    // to MediaRegistry. Until then we show a placeholder token
                    // label so the expand affordance still communicates the
                    // shape of the future feature.
                    Text(
                        text = "Screenshot token: ${entry.thumbnailToken}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: BridgeActivityStatus) {
    val (icon, tint) = when (status) {
        BridgeActivityStatus.Pending -> Icons.Filled.HourglassEmpty to
            MaterialTheme.colorScheme.onSurfaceVariant
        BridgeActivityStatus.Success -> Icons.Filled.CheckCircle to Color(0xFF4CAF50)
        BridgeActivityStatus.Failed -> Icons.Filled.Error to
            MaterialTheme.colorScheme.error
        BridgeActivityStatus.Blocked -> Icons.Filled.Block to Color(0xFFFFA726)
    }
    Icon(
        imageVector = icon,
        contentDescription = status.name,
        tint = tint,
        modifier = Modifier.size(14.dp)
    )
}

// ── Formatting helpers ──────────────────────────────────────────────────

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val FULL_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun formatTime(epochMs: Long): String = try {
    LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
        .format(TIME_FMT)
} catch (_: Exception) {
    "--:--:--"
}

private fun formatFullTime(epochMs: Long): String = try {
    LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
        .format(FULL_FMT)
} catch (_: Exception) {
    "—"
}

// ── Previews ────────────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun BridgeActivityLogPreviewFilled() {
    val now = System.currentTimeMillis()
    val sample = listOf(
        BridgeActivityEntry(
            id = "1",
            timestampMs = now,
            method = "tap",
            summary = "(540, 1200)",
            status = BridgeActivityStatus.Success,
            resultText = "Dispatched gesture at (540, 1200)",
        ),
        BridgeActivityEntry(
            id = "2",
            timestampMs = now - 2_000,
            method = "read_screen",
            summary = "root → 42 nodes",
            status = BridgeActivityStatus.Success,
            thumbnailToken = "hermes-relay://ab12cd34",
        ),
        BridgeActivityEntry(
            id = "3",
            timestampMs = now - 5_500,
            method = "open_app",
            summary = "Chrome",
            status = BridgeActivityStatus.Blocked,
            resultText = "Blocked by safety rails: chrome is in user blocklist",
        ),
        BridgeActivityEntry(
            id = "4",
            timestampMs = now - 9_000,
            method = "type",
            summary = "\"hello\"",
            status = BridgeActivityStatus.Failed,
            resultText = "No focused input field",
        ),
        BridgeActivityEntry(
            id = "5",
            timestampMs = now - 12_000,
            method = "ping",
            summary = "→ 12ms",
            status = BridgeActivityStatus.Success,
        ),
    )
    MaterialTheme {
        BridgeActivityLog(
            entries = sample,
            onClear = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun BridgeActivityLogPreviewEmpty() {
    MaterialTheme {
        BridgeActivityLog(
            entries = emptyList(),
            onClear = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
