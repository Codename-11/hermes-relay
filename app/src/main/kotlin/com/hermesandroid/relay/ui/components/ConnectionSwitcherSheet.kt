package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.Connection

/**
 * Bottom sheet chooser for switching between Hermes connections. Driven by
 * the top-bar [ConnectionChip] tap and the Settings → Connections row.
 * Each row is a radio selection — tapping commits immediately and dismisses
 * the sheet so the swap kicks off before the user's finger is off the screen.
 *
 * The "Manage connections…" footer button navigates to
 * [ConnectionsSettingsScreen] for rename / re-pair / revoke / remove —
 * anything beyond plain switching.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSwitcherSheet(
    connections: List<Connection>,
    activeConnectionId: String?,
    onSelectConnection: (String) -> Unit,
    onManageConnections: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Switch connection",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (connections.isEmpty()) {
                // Defensive: the legacy migration should always seed connection 0,
                // but fall back to a Manage-only state if the list is empty.
                Text(
                    text = "No connections yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                TextButton(
                    onClick = onManageConnections,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Manage connections…")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(connections, key = { it.id }) { connection ->
                        ConnectionRow(
                            connection = connection,
                            isActive = connection.id == activeConnectionId,
                            onClick = {
                                onSelectConnection(connection.id)
                                onDismiss()
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                )
                TextButton(
                    onClick = onManageConnections,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Manage connections…")
                }
            }
        }
    }
}

@Composable
private fun ConnectionRow(
    connection: Connection,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val hostname = Connection.extractDefaultLabel(connection.apiServerUrl)
    val statusLine = if (connection.pairedAt == null) {
        "$hostname • Not paired"
    } else {
        "$hostname • Paired"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = isActive,
            onClick = onClick,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = connection.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
