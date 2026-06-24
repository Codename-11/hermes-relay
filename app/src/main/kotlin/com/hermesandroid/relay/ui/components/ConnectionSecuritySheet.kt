package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.ConnectionSecurity
import com.hermesandroid.relay.data.ConnectionSecurityLevel
import com.hermesandroid.relay.data.SurfaceSecurity

private const val LEARN_MORE_URL =
    "https://codename-11.github.io/hermes-relay/architecture/connection-security.html"

/**
 * Per-surface "Connection security" detail sheet — the tap target for the
 * connection-security badge. Shows the rollup, the per-transport breakdown,
 * and a one-line explainer of the mechanism so the at-a-glance badge never
 * has to lie about a mixed connection.
 */
/**
 * Self-contained badge that opens the [ConnectionSecuritySheet] on tap. Drop
 * it on any surface (connection header, posture strip) without threading sheet
 * state through the caller.
 */
@Composable
fun ConnectionSecurityBadgeWithSheet(
    security: ConnectionSecurity,
    modifier: Modifier = Modifier,
    size: TransportSecuritySize = TransportSecuritySize.Chip,
) {
    var show by remember { mutableStateOf(false) }
    ConnectionSecurityBadge(
        security = security,
        modifier = modifier,
        size = size,
        onClick = { show = true },
    )
    if (show) {
        ConnectionSecuritySheet(security = security, onDismiss = { show = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSecuritySheet(
    security: ConnectionSecurity,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uriHandler = LocalUriHandler.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Connection security",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            ConnectionSecurityBadge(
                security = security,
                size = TransportSecuritySize.Large,
            )

            HorizontalDivider()

            if (security.surfaces.isEmpty()) {
                Text(
                    text = "No active route yet. Connect to a server to see how each " +
                        "part of the connection is protected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                security.surfaces.forEach { SurfaceSecurityRow(it) }
            }

            HorizontalDivider()

            Text(
                text = explainer(security.level),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(onClick = { uriHandler.openUri(LEARN_MORE_URL) }) {
                Text("Learn about connection security →")
            }
        }
    }
}

@Composable
private fun SurfaceSecurityRow(surface: SurfaceSecurity) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SurfaceSecurityGlyph(kind = surface.kind, modifier = Modifier.size(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = surface.label,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = surface.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = surface.mechanism,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun explainer(level: ConnectionSecurityLevel): String = when (level) {
    ConnectionSecurityLevel.Tls ->
        "Encrypted with TLS. The server's certificate is pinned on first connect."
    ConnectionSecurityLevel.Overlay ->
        "Encrypted by your overlay network (e.g. Tailscale/WireGuard), not TLS. " +
            "Cert pinning applies only to TLS routes."
    ConnectionSecurityLevel.Mixed ->
        "Some parts of this connection are encrypted and some are plain. The app " +
            "prefers a secure route when one is reachable."
    ConnectionSecurityLevel.Plain ->
        "Not encrypted. Only safe on a network you fully trust — anyone in between " +
            "could read this traffic."
    ConnectionSecurityLevel.Unknown -> ""
}
