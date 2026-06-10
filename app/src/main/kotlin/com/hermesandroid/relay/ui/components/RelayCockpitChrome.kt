package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.theme.RelayDottedOverlay
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.relayMetadataStyle
import com.hermesandroid.relay.ui.theme.relayPanel
import com.hermesandroid.relay.ui.theme.relaySelectedPanel

enum class RelayPrimaryMode(val label: String) {
    Chat("Chat"),
    Manage("Manage"),
    Bridge("Bridge"),
}

@Composable
fun RelayModeStrip(
    selected: RelayPrimaryMode,
    onModeSelected: (RelayPrimaryMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RelayPrimaryMode.entries.forEach { mode ->
            val active = mode == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(RelayRefresh.CardRadius))
                    .then(
                        if (active) {
                            Modifier.relaySelectedPanel()
                        } else {
                            Modifier.relayPanel(
                                background = RelayRefresh.Background.copy(alpha = 0.45f),
                            )
                        },
                    )
                    .clickable { onModeSelected(mode) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                    color = if (active) RelayRefresh.Paper else RelayRefresh.Muted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun RelayStatusStrip(
    leading: String,
    trailing: String,
    modifier: Modifier = Modifier,
    leadingColor: Color = RelayRefresh.Green,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .relayPanel(
                shape = RoundedCornerShape(0.dp),
                background = RelayRefresh.Background.copy(alpha = 0.94f),
                borderColor = RelayRefresh.Line,
            )
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            )
            .padding(bottom = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = leading,
                style = relayMetadataStyle(),
                color = leadingColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = trailing,
                style = relayMetadataStyle(),
                color = RelayRefresh.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun RelayReturnStrip(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Back",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RelayRefresh.CardRadius))
            .relaySelectedPanel()
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(7.dp),
            color = RelayRefresh.Background.copy(alpha = 0.58f),
            border = BorderStroke(1.dp, RelayRefresh.LineStrong),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = RelayRefresh.Paper,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Surface(
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(7.dp),
            color = RelayRefresh.Navy3.copy(alpha = 0.72f),
            border = BorderStroke(1.dp, RelayRefresh.Line),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = RelayRefresh.Relay,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = RelayRefresh.Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = RelayRefresh.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = label,
            style = relayMetadataStyle(),
            color = RelayRefresh.Relay,
            maxLines = 1,
        )
    }
}

@Composable
fun RelayHeroPanel(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    accent: Color = RelayRefresh.Relay,
    action: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RelayRefresh.CardRadius))
            .relaySelectedPanel(),
    ) {
        RelayDottedOverlay(alpha = 0.18f)
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = RelayRefresh.Paper,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = RelayRefresh.Ink.copy(alpha = 0.86f),
            )
            action?.invoke()
        }
    }
}

@Composable
fun RelayMetricCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .relayPanel()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = RelayRefresh.Paper,
            maxLines = 1,
        )
        Text(
            text = label,
            style = relayMetadataStyle(),
            color = RelayRefresh.Muted,
            maxLines = 1,
        )
    }
}

@Composable
fun RelayNavTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    val base = if (selected) {
        Modifier.relaySelectedPanel()
    } else {
        Modifier.relayPanel(background = RelayRefresh.Navy2.copy(alpha = 0.72f))
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RelayRefresh.CardRadius))
            .then(base)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(7.dp),
            color = RelayRefresh.Navy3.copy(alpha = if (enabled) 0.86f else 0.38f),
            border = BorderStroke(1.dp, RelayRefresh.Line),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) RelayRefresh.Relay else RelayRefresh.Dim,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = if (enabled) RelayRefresh.Paper else RelayRefresh.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) RelayRefresh.Muted else RelayRefresh.Dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = if (enabled) RelayRefresh.Muted else RelayRefresh.Dim,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun RelaySectionCaption(
    title: String,
    meta: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = RelayRefresh.Paper,
        )
        meta?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = relayMetadataStyle(),
                color = RelayRefresh.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun RelayStatusPill(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .relayPanel(
                background = if (active) RelayRefresh.Green.copy(alpha = 0.12f) else RelayRefresh.Navy3.copy(alpha = 0.7f),
                borderColor = if (active) RelayRefresh.Green.copy(alpha = 0.36f) else RelayRefresh.Line,
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = if (active) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (active) RelayRefresh.Green else RelayRefresh.Muted,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = text,
            style = relayMetadataStyle(),
            color = if (active) RelayRefresh.Green else RelayRefresh.Muted,
            maxLines = 1,
        )
    }
}

@Composable
fun RelayChromeIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(38.dp),
        shape = RoundedCornerShape(RelayRefresh.CardRadius),
        color = RelayRefresh.Background.copy(alpha = 0.52f),
        border = BorderStroke(1.dp, RelayRefresh.LineStrong),
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = RelayRefresh.Paper,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}
