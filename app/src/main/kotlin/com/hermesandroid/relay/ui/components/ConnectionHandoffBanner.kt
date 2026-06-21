package com.hermesandroid.relay.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import com.hermesandroid.relay.viewmodel.ConnectionHandoffStatus
import com.hermesandroid.relay.viewmodel.ConnectionHandoffTraceEntry
import com.hermesandroid.relay.viewmodel.ConnectionStatusSnapshot
import com.hermesandroid.relay.viewmodel.ConnectionStatusTone
import com.hermesandroid.relay.viewmodel.ConnectionStepState
import com.hermesandroid.relay.viewmodel.asConnectionStatusSnapshot
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ConnectionHandoffBanner(
    status: ConnectionHandoffStatus?,
    modifier: Modifier = Modifier,
    includeStatusBarPadding: Boolean = false,
) {
    ConnectionStatusBanner(
        status = status?.asConnectionStatusSnapshot(),
        modifier = modifier,
        includeStatusBarPadding = includeStatusBarPadding,
    )
}

@Composable
fun ConnectionStatusBanner(
    status: ConnectionStatusSnapshot?,
    modifier: Modifier = Modifier,
    includeStatusBarPadding: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val current = status ?: return
    val containerColor = when {
        current.tone == ConnectionStatusTone.Error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.86f)
        current.tone == ConnectionStatusTone.Warning -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.62f)
        current.success -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.58f)
        current.active -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.74f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.86f)
    }
    val contentColor = when {
        current.tone == ConnectionStatusTone.Error ||
            current.tone == ConnectionStatusTone.Warning -> MaterialTheme.colorScheme.onErrorContainer
        current.success -> MaterialTheme.colorScheme.onTertiaryContainer
        current.active -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val insetModifier = if (includeStatusBarPadding) {
        Modifier.windowInsetsPadding(WindowInsets.statusBars)
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
            .then(insetModifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(10.dp),
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .animateContentSize(animationSpec = tween(durationMillis = 180)),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 34.dp)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        current.active -> PulsingSyncIcon(contentColor)
                        current.success -> Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(16.dp),
                        )
                        current.tone == ConnectionStatusTone.Warning ||
                            current.tone == ConnectionStatusTone.Error -> Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = contentColor,
                                modifier = Modifier.size(16.dp),
                            )
                        else -> Icon(
                            imageVector = Icons.Filled.Sync,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(16.dp),
                        )
                    }
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
                                text = current.title,
                                style = MaterialTheme.typography.labelMedium,
                                color = contentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            current.route?.takeIf { it.isNotBlank() }?.let { route ->
                                Text(
                                    text = route,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentColor.copy(alpha = 0.76f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            current.actionLabel?.takeIf { it.isNotBlank() }?.let { label ->
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = contentColor.copy(alpha = 0.86f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        val outputLines = current.entries
                            .takeLast(2)
                            .mapNotNull { entry ->
                                val label = entry.label.trim().takeIf { it.isNotBlank() }
                                val detail = entry.detail?.trim()?.takeIf { it.isNotBlank() }
                                when {
                                    label != null && detail != null -> "$label: $detail"
                                    label != null -> label
                                    detail != null -> detail
                                    else -> null
                                }
                            }
                            .distinct()
                        outputLines.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (current.active) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 2.dp, max = 2.dp),
                        color = contentColor.copy(alpha = 0.76f),
                        trackColor = contentColor.copy(alpha = 0.16f),
                    )
                }
            }
        }
    }
}

private const val SWIPE_DISMISS_THRESHOLD_PX = 80f

/**
 * Floating, in-theme connection status **toast** for connection switches,
 * network handoffs, and disconnects.
 *
 * Unlike [ConnectionStatusBanner] (edge-to-edge, takes layout space above the
 * Scaffold and so resizes the content), this is meant to be rendered as a
 * top-aligned overlay inside a `Box` — it slides down OVER the UI without
 * shifting it. Pair it with `AnimatedVisibility(enter = slideInVertically{-it})`
 * at the call site.
 *
 * - Spinner while [ConnectionStatusSnapshot.active] (handoff / loading).
 * - [onClick] acts on it (reconnect / open the relevant screen).
 * - [onDismiss] is wired to a swipe-up; the host suppresses re-show until the
 *   status content changes.
 */
@Composable
fun ConnectionStatusToast(
    status: ConnectionStatusSnapshot?,
    modifier: Modifier = Modifier,
    includeStatusBarPadding: Boolean = true,
    onClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val current = status ?: return
    val surface = MaterialTheme.colorScheme.surface
    // This toast floats OVER arbitrary content, so a translucent container would
    // let the UI bleed through and hurt legibility. Composite any alpha over the
    // opaque surface: the result is always opaque while each tone keeps its
    // intended tint (e.g. Warning stays a lighter error than Error).
    val containerColor = when {
        current.tone == ConnectionStatusTone.Error -> MaterialTheme.colorScheme.errorContainer
        current.tone == ConnectionStatusTone.Warning -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.82f)
        current.success -> MaterialTheme.colorScheme.tertiaryContainer
        current.active -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }.compositeOver(surface)
    val contentColor = when {
        current.tone == ConnectionStatusTone.Error ||
            current.tone == ConnectionStatusTone.Warning -> MaterialTheme.colorScheme.onErrorContainer
        current.success -> MaterialTheme.colorScheme.onTertiaryContainer
        current.active -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Drag-to-dismiss that tracks the finger: the card slides + fades with the
    // upward drag, then flings the rest of the way (and fires onDismiss) past a
    // threshold or springs back if released short. Keyed on the status IDENTITY
    // (title+tone), not updatedAtMs — a fresh status reseats the toast, but the
    // frequent updatedAtMs bumps from live trace appends won't reset a swipe in
    // progress.
    val statusIdentity = "${current.title}|${current.tone}"
    val offsetY = remember(statusIdentity) { Animatable(0f) }
    val dragScope = rememberCoroutineScope()
    var heightPx by remember { mutableIntStateOf(0) }
    val dismissDistance = if (heightPx > 0) heightPx.toFloat() else 240f
    val dragProgress = (abs(offsetY.value) / dismissDistance).coerceIn(0f, 1f)
    val dragAlpha = 1f - 0.82f * dragProgress

    val swipeModifier = if (onDismiss != null) {
        Modifier.pointerInput(onDismiss, statusIdentity) {
            detectVerticalDragGestures(
                onVerticalDrag = { change, dy ->
                    // Only travels up; downward drags hold it seated at 0.
                    val next = (offsetY.value + dy).coerceAtMost(0f)
                    dragScope.launch { offsetY.snapTo(next) }
                    change.consume()
                },
                onDragEnd = {
                    if (offsetY.value < -SWIPE_DISMISS_THRESHOLD_PX) {
                        dragScope.launch {
                            offsetY.animateTo(-dismissDistance, tween(160))
                            onDismiss()
                        }
                    } else {
                        dragScope.launch { offsetY.animateTo(0f, spring()) }
                    }
                },
            )
        }
    } else {
        Modifier
    }

    // The error/warning poses get an explicit "Open <destination>" link at the
    // bottom so the path to the detailed Connections view is discoverable —
    // the whole-card tap still works, but nothing about it said "tap me".
    val showActionLink = onClick != null &&
        (current.tone == ConnectionStatusTone.Error || current.tone == ConnectionStatusTone.Warning)

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
        modifier = modifier
            .then(
                if (includeStatusBarPadding) {
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .alpha(dragAlpha)
            .onSizeChanged { heightPx = it.height }
            .then(swipeModifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(durationMillis = 180))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    current.active -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = contentColor,
                    )
                    current.success -> Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp),
                    )
                    current.tone == ConnectionStatusTone.Warning ||
                        current.tone == ConnectionStatusTone.Error -> Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(18.dp),
                        )
                    else -> Icon(
                        imageVector = Icons.Filled.Sync,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    current.route?.takeIf { it.isNotBlank() }?.let { route ->
                        Text(
                            text = route,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.76f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Live stepper — one row per trace entry, glyph driven by the
            // entry's resolved state. Indented to sit under the title (not the
            // status icon) so it reads as a sub-list.
            val steps = current.entries.filter {
                it.label.isNotBlank() || !it.detail.isNullOrBlank()
            }
            if (steps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(7.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 29.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    steps.forEachIndexed { index, entry ->
                        ConnectionStepRow(
                            entry = entry,
                            isLast = index == steps.lastIndex,
                            snapshot = current,
                            contentColor = contentColor,
                        )
                    }
                }
            }

            if (showActionLink) {
                Spacer(modifier = Modifier.height(9.dp))
                HorizontalDivider(color = contentColor.copy(alpha = 0.16f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 34.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = current.actionLabel
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "Open $it" }
                            ?: "View details",
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** Success green shared with [ConnectionStatusBadge] for a consistent "ok" cue. */
private val StepDoneGreen = Color(0xFF4CAF50)

private val STEP_SPINNER_FRAMES = listOf("|", "/", "-", "\\")

/**
 * One stepper line in [ConnectionStatusToast]. When [ConnectionHandoffTraceEntry.state]
 * is set (probe entries), it's used verbatim; otherwise the state is inferred
 * from position — the last entry follows the parent [snapshot]'s
 * active/success/error pose, earlier entries are Done.
 */
@Composable
private fun ConnectionStepRow(
    entry: ConnectionHandoffTraceEntry,
    isLast: Boolean,
    snapshot: ConnectionStatusSnapshot,
    contentColor: Color,
) {
    val state = entry.state ?: when {
        !isLast -> ConnectionStepState.Done
        snapshot.active -> ConnectionStepState.Active
        snapshot.success -> ConnectionStepState.Done
        snapshot.tone == ConnectionStatusTone.Error ||
            snapshot.tone == ConnectionStatusTone.Warning -> ConnectionStepState.Failed
        else -> ConnectionStepState.Done
    }
    val label = entry.label.trim()
    val detail = entry.detail?.trim()?.takeIf { it.isNotBlank() }
    val text = when {
        label.isNotBlank() && detail != null -> "$label · $detail"
        label.isNotBlank() -> label
        else -> detail.orEmpty()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepGlyph(state = state, contentColor = contentColor)
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = when (state) {
                ConnectionStepState.Pending -> contentColor.copy(alpha = 0.5f)
                ConnectionStepState.Active -> contentColor
                ConnectionStepState.Done -> contentColor.copy(alpha = 0.82f)
                ConnectionStepState.Failed -> contentColor
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Fixed-width monospace status glyph — `·` pending, an ASCII spinner while
 * Active, `✓` Done (green), `✕` Failed (error). Mirrors the cold-start sphere's
 * [com.hermesandroid.relay.ui.RelayApp]'s StartupCheckRow vocabulary so the
 * toast and splash read as one system.
 */
@Composable
private fun StepGlyph(state: ConnectionStepState, contentColor: Color) {
    var frame by remember { mutableIntStateOf(0) }
    if (state == ConnectionStepState.Active) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(120L)
                frame = (frame + 1) % STEP_SPINNER_FRAMES.size
            }
        }
    }
    val glyph = when (state) {
        ConnectionStepState.Pending -> "·"
        ConnectionStepState.Active -> STEP_SPINNER_FRAMES[frame]
        ConnectionStepState.Done -> "✓"
        ConnectionStepState.Failed -> "✕"
    }
    Text(
        text = glyph,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = when (state) {
            ConnectionStepState.Pending -> contentColor.copy(alpha = 0.5f)
            ConnectionStepState.Active -> contentColor
            ConnectionStepState.Done -> StepDoneGreen
            ConnectionStepState.Failed -> MaterialTheme.colorScheme.error
        },
        modifier = Modifier.width(12.dp),
    )
}

@Composable
private fun PulsingSyncIcon(color: androidx.compose.ui.graphics.Color) {
    // Throttled to ~30fps. Reverse ping-pong over 0.9s each way → a 1.8s linear
    // phase folded into a 0→1→0 triangle. See [rememberAmbientPhase].
    val phase = rememberAmbientPhase(periodMillis = 1800)
    val triangle = 1f - kotlin.math.abs(2f * phase - 1f)
    val alpha = 0.45f + 0.55f * triangle
    Icon(
        imageVector = Icons.Filled.Sync,
        contentDescription = null,
        tint = color,
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .alpha(alpha),
    )
}
