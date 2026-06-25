package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.relayMetadataStyle
import com.hermesandroid.relay.ui.theme.relayPanel

@Composable
fun RelayStatusStrip(
    leadingBadge: @Composable () -> Unit,
    routeLabel: String,
    trailing: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** Optional security marker rendered just before the route label. */
    securityGlyph: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            )
            .padding(start = 14.dp, end = 14.dp, top = 3.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(999.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .relayPanel(
                shape = RoundedCornerShape(999.dp),
                background = RelayRefresh.Navy2.copy(alpha = 0.88f),
                borderColor = RelayRefresh.Line,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingBadge()
                if (securityGlyph != null) {
                    securityGlyph()
                }
                if (routeLabel.isNotBlank()) {
                    Text(
                        text = "· $routeLabel",
                        style = relayMetadataStyle(),
                        color = RelayRefresh.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
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
