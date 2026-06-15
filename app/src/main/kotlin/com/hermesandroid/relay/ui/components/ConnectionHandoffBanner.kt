package com.hermesandroid.relay.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import com.hermesandroid.relay.viewmodel.ConnectionHandoffStatus
import com.hermesandroid.relay.viewmodel.ConnectionStatusSnapshot
import com.hermesandroid.relay.viewmodel.ConnectionStatusTone
import com.hermesandroid.relay.viewmodel.asConnectionStatusSnapshot

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
    val containerColor = when {
        current.tone == ConnectionStatusTone.Error -> MaterialTheme.colorScheme.errorContainer
        current.tone == ConnectionStatusTone.Warning -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.82f)
        current.success -> MaterialTheme.colorScheme.tertiaryContainer
        current.active -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        current.tone == ConnectionStatusTone.Error ||
            current.tone == ConnectionStatusTone.Warning -> MaterialTheme.colorScheme.onErrorContainer
        current.success -> MaterialTheme.colorScheme.onTertiaryContainer
        current.active -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Reset the swipe accumulator whenever a new status arrives.
    val dragAccum = remember(current.updatedAtMs) { mutableFloatStateOf(0f) }
    val swipeModifier = if (onDismiss != null) {
        Modifier.pointerInput(onDismiss) {
            detectVerticalDragGestures(
                onDragEnd = {
                    if (dragAccum.floatValue < -SWIPE_DISMISS_THRESHOLD_PX) onDismiss()
                    dragAccum.floatValue = 0f
                },
                onVerticalDrag = { change, dy ->
                    if (dy < 0f) {
                        dragAccum.floatValue += dy
                        change.consume()
                    }
                },
            )
        }
    } else {
        Modifier
    }

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
            .then(swipeModifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
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
                        style = MaterialTheme.typography.labelLarge,
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
        }
    }
}

@Composable
private fun PulsingSyncIcon(color: androidx.compose.ui.graphics.Color) {
    val infinite = rememberInfiniteTransition(label = "connection-handoff-pulse")
    val alpha by infinite.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "connection-handoff-alpha",
    )
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
