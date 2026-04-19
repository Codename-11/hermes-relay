package com.hermesandroid.relay.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.ToolCallEvent
import com.hermesandroid.relay.viewmodel.VoiceStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vertical time-ordered activity feed below StatsForNerds. Aggregates
 * events from multiple sources (tool calls, voice turns) into a single
 * timeline and color-codes them by type. Tap a dot to expand the details
 * row; the bucket resolution keeps the surface phone-friendly on busy
 * sessions.
 *
 * Feed caps are enforced in [buildTimelineEvents] — the 200 upper bound
 * mirrors a rough "last ~30 minutes of heavy use" window.
 *
 * Event bucketing: events within the same [BUCKET_WINDOW_MS] (5 s)
 * collapse into a single row with a count badge. Tapping the row reveals
 * the full set of events in the bucket.
 */
@Composable
fun TimelineView(
    voiceStats: VoiceStats? = null,
    toolCalls: List<ToolCallEvent> = emptyList(),
    nowMs: Long = System.currentTimeMillis(),
) {
    val events = remember(voiceStats, toolCalls) {
        buildTimelineEvents(
            toolCalls = toolCalls,
            voiceStats = voiceStats,
        )
    }
    // Bucket by BUCKET_WINDOW_MS so high-frequency events collapse into
    // a single row. Each bucket keeps the newest event for the primary
    // dot/label, plus the count of siblings for the badge.
    val buckets = remember(events) { bucketize(events) }
    var expandedBucketKey by rememberSaveable { mutableStateOf<Long?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Timeline",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${events.size} events",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (buckets.isEmpty()) {
                Text(
                    text = "No activity yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            // Color legend — keeps the palette self-documenting so the
            // dots don't need labels on every row.
            TimelineLegend()

            Spacer(modifier = Modifier.height(8.dp))

            // Scrollable list of buckets; capped height keeps the card
            // from dominating the AnalyticsScreen. Max ~30 rows before
            // scrolling kicks in — matching the "max ~30 events visible
            // at once" UX goal.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                buckets.forEach { bucket ->
                    TimelineRow(
                        bucket = bucket,
                        nowMs = nowMs,
                        expanded = expandedBucketKey == bucket.bucketMs,
                        onToggle = {
                            expandedBucketKey = if (expandedBucketKey == bucket.bucketMs) {
                                null
                            } else {
                                bucket.bucketMs
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LegendEntry("Chat", TimelineEventKind.ChatMessage.color())
        LegendEntry("Tool", TimelineEventKind.ToolCall.color())
        LegendEntry("Voice", TimelineEventKind.VoiceTurn.color())
        LegendEntry("Profile", TimelineEventKind.ProfileSwitch.color())
        LegendEntry("Conn", TimelineEventKind.ConnectionEvent.color())
    }
}

@Composable
private fun LegendEntry(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TimelineRow(
    bucket: TimelineBucket,
    nowMs: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val primary = bucket.primary
    val dotColor = primary.kind.color()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(8.dp),
        color = if (expanded) {
            MaterialTheme.colorScheme.surface
        } else {
            Color.Transparent
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Text(
                    text = timeOfDay(primary.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(56.dp),
                )
                Text(
                    text = primary.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (bucket.siblings.isNotEmpty()) {
                    // +N indicator for collapsed sibling events.
                    Text(
                        text = "+${bucket.siblings.size}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(start = 20.dp, top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = primary.details,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "${relativeTimeShort(primary.timestampMs, nowMs)} · id: ${primary.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    bucket.siblings.forEach { sibling ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(sibling.kind.color()),
                            )
                            Text(
                                text = sibling.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Event model + bucketing
// -----------------------------------------------------------------------------

/** Kinds of events carried in the timeline. */
enum class TimelineEventKind {
    ChatMessage,
    ToolCall,
    VoiceTurn,
    ProfileSwitch,
    ConnectionEvent,
}

private const val TIMELINE_CAP = 200
private const val BUCKET_WINDOW_MS = 5_000L

/**
 * A single row's worth of telemetry. Immutable; rebuilt on each change to
 * the upstream flows.
 */
data class TimelineEvent(
    val id: String,
    val timestampMs: Long,
    val kind: TimelineEventKind,
    /** Short one-liner shown on the row. */
    val title: String,
    /** Detail string shown when the row is expanded. */
    val details: String,
)

/** A 5 s window of timeline events. `primary` is the newest. */
private data class TimelineBucket(
    val bucketMs: Long,
    val primary: TimelineEvent,
    val siblings: List<TimelineEvent>,
)

/**
 * Collapse timeline events into time-buckets of [BUCKET_WINDOW_MS].
 *
 * Each bucket keeps the newest event as `primary` and stashes older
 * same-window siblings for the expanded view. Events inbound here are
 * already time-ordered (newest-first in [buildTimelineEvents]) so we
 * only need to walk once.
 */
private fun bucketize(events: List<TimelineEvent>): List<TimelineBucket> {
    if (events.isEmpty()) return emptyList()
    val out = mutableListOf<TimelineBucket>()
    var currentKey = events[0].timestampMs / BUCKET_WINDOW_MS * BUCKET_WINDOW_MS
    var primary = events[0]
    val siblings = mutableListOf<TimelineEvent>()
    for (i in 1 until events.size) {
        val e = events[i]
        val key = e.timestampMs / BUCKET_WINDOW_MS * BUCKET_WINDOW_MS
        if (key == currentKey) {
            siblings.add(e)
        } else {
            out.add(TimelineBucket(currentKey, primary, siblings.toList()))
            currentKey = key
            primary = e
            siblings.clear()
        }
    }
    out.add(TimelineBucket(currentKey, primary, siblings.toList()))
    return out
}

/**
 * Build the timeline event list from the currently-available data
 * sources. Kept pure + testable; the composable just calls this with
 * remember-cached inputs.
 *
 * v1 sources: [toolCalls] (mapped one-to-one) plus a synthetic
 * "voice-turn" event derived from [voiceStats] when there's a recent
 * transcript. Chat-message / profile-switch / connection-event sources
 * are wired in later passes when their own history stores exist; today
 * they surface in the timeline indirectly via the tool-call kind.
 */
internal fun buildTimelineEvents(
    toolCalls: List<ToolCallEvent>,
    voiceStats: VoiceStats?,
): List<TimelineEvent> {
    val events = mutableListOf<TimelineEvent>()

    toolCalls.forEach { call ->
        val ts = call.completedAtMs ?: call.startedAtMs
        val statusSuffix = when {
            !call.isComplete -> "running"
            call.success == true -> "ok"
            else -> "failed"
        }
        events.add(
            TimelineEvent(
                id = call.id,
                timestampMs = ts,
                kind = TimelineEventKind.ToolCall,
                title = "${call.name} · $statusSuffix",
                details = call.resultSummary ?: call.errorSummary ?: "—",
            ),
        )
    }

    // Voice-turn snapshot: a single placeholder event at "now" when the
    // session has had at least one transcript and we haven't seen it in
    // the toolCalls stream. Gives the timeline a visible anchor during
    // voice-only sessions.
    if (voiceStats != null && voiceStats.sttCallCount > 0 &&
        voiceStats.lastTranscript.isNotBlank()
    ) {
        events.add(
            TimelineEvent(
                id = "voice-${voiceStats.sttCallCount}",
                timestampMs = System.currentTimeMillis(),
                kind = TimelineEventKind.VoiceTurn,
                title = "voice · ${voiceStats.lastTranscript.take(40)}",
                details = "stt ${voiceStats.lastSttLatencyMs} ms · tts calls ${voiceStats.ttsCallCount}",
            ),
        )
    }

    return events
        .sortedByDescending { it.timestampMs }
        .take(TIMELINE_CAP)
}

// -----------------------------------------------------------------------------
// Color + formatting helpers
// -----------------------------------------------------------------------------

@Composable
private fun TimelineEventKind.color(): Color = when (this) {
    TimelineEventKind.ChatMessage -> Color(0xFF4FC3F7)     // blue
    TimelineEventKind.ToolCall -> Color(0xFFFFB74D)        // orange
    TimelineEventKind.VoiceTurn -> Color(0xFFBA68C8)       // purple
    TimelineEventKind.ProfileSwitch -> Color(0xFF81C784)   // green
    TimelineEventKind.ConnectionEvent -> MaterialTheme.colorScheme.onSurfaceVariant
}

private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun timeOfDay(epochMs: Long): String = TIME_FMT.format(Date(epochMs))

private fun relativeTimeShort(eventMs: Long, nowMs: Long): String {
    val delta = ((nowMs - eventMs) / 1000).coerceAtLeast(0L)
    return when {
        delta < 2 -> "just now"
        delta < 60 -> "${delta}s ago"
        delta < 3600 -> "${delta / 60}m ago"
        else -> "${delta / 3600}h ago"
    }
}
