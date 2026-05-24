package com.hermesandroid.relay.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
