package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.ChatSession
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.relayMetadataStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SessionDrawerFilter(val label: String) {
    All("All"),
    Pinned("Pinned"),
    Archive("Archive"),
}

@Composable
fun SessionDrawerContent(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    scopeTitle: String = "Sessions",
    scopeSubtitle: String? = null,
    onNewChat: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit
) {
    var renameDialogSession by remember { mutableStateOf<ChatSession?>(null) }
    var deleteDialogSession by remember { mutableStateOf<ChatSession?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SessionDrawerFilter.All) }
    var pinnedSessionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var archivedSessionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val visibleSessions = sessions
        .asSequence()
        .filter { session ->
            when (filter) {
                SessionDrawerFilter.All -> session.sessionId !in archivedSessionIds
                SessionDrawerFilter.Pinned ->
                    session.sessionId in pinnedSessionIds &&
                        session.sessionId !in archivedSessionIds
                SessionDrawerFilter.Archive -> session.sessionId in archivedSessionIds
            }
        }
        .filter { session ->
            val needle = query.trim()
            needle.isBlank() ||
                session.sessionId.contains(needle, ignoreCase = true) ||
                session.title.orEmpty().contains(needle, ignoreCase = true) ||
                session.model.orEmpty().contains(needle, ignoreCase = true)
        }
        .toList()

    ModalDrawerSheet(
        modifier = Modifier.width(320.dp),
        drawerContainerColor = RelayRefresh.Background,
        drawerContentColor = RelayRefresh.Ink,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Text(
                text = scopeTitle,
                style = MaterialTheme.typography.titleLarge
            )
            scopeSubtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // New Chat button
            Button(
                onClick = onNewChat,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("New Chat")
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                placeholder = { Text("Search sessions or id...") },
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SessionDrawerFilter.entries.forEach { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = {
                            Text(
                                text = item.label,
                                style = relayMetadataStyle(),
                            )
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (visibleSessions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (sessions.isEmpty()) "No sessions yet" else "No matching sessions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Start a conversation to see it here",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn {
                items(visibleSessions, key = { it.sessionId }) { session ->
                    SessionItem(
                        session = session,
                        isActive = session.sessionId == currentSessionId,
                        pinned = session.sessionId in pinnedSessionIds,
                        archived = session.sessionId in archivedSessionIds,
                        onClick = { onSelectSession(session.sessionId) },
                        onTogglePinned = {
                            pinnedSessionIds = if (session.sessionId in pinnedSessionIds) {
                                pinnedSessionIds - session.sessionId
                            } else {
                                pinnedSessionIds + session.sessionId
                            }
                        },
                        onToggleArchived = {
                            archivedSessionIds = if (session.sessionId in archivedSessionIds) {
                                archivedSessionIds - session.sessionId
                            } else {
                                archivedSessionIds + session.sessionId
                            }
                        },
                        onRename = { renameDialogSession = session },
                        onDelete = { deleteDialogSession = session }
                    )
                }
            }
        }
    }

    // Rename dialog
    renameDialogSession?.let { session ->
        var newTitle by remember(session) { mutableStateOf(session.title ?: "") }
        AlertDialog(
            onDismissRequest = { renameDialogSession = null },
            title = { Text("Rename Session") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newTitle.isNotBlank()) {
                        onRenameSession(session.sessionId, newTitle)
                    }
                    renameDialogSession = null
                }) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogSession = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete confirmation dialog
    deleteDialogSession?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteDialogSession = null },
            title = { Text("Delete Session?") },
            text = {
                Text("This will permanently delete \"${session.title ?: "Untitled"}\" and its message history.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(session.sessionId)
                    deleteDialogSession = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogSession = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SessionItem(
    session: ChatSession,
    isActive: Boolean,
    pinned: Boolean,
    archived: Boolean,
    onClick: () -> Unit,
    onTogglePinned: () -> Unit,
    onToggleArchived: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val backgroundColor = if (isActive) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title ?: "Untitled",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isActive) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (session.updatedAt > 0) {
                    Text(
                        text = formatTimestamp(session.updatedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (session.messageCount > 0) {
                    Text(
                        text = "${session.messageCount} msgs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        IconButton(onClick = onTogglePinned, modifier = Modifier.padding(0.dp)) {
            Icon(
                Icons.Filled.Star,
                contentDescription = if (pinned) "Unpin session" else "Pin session",
                tint = if (pinned) RelayRefresh.Amber else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onToggleArchived, modifier = Modifier.padding(0.dp)) {
            Icon(
                Icons.Filled.Archive,
                contentDescription = if (archived) "Restore session" else "Archive session",
                tint = if (archived) RelayRefresh.Relay else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRename, modifier = Modifier.padding(0.dp)) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = "Rename",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.padding(0.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            )
        }
    }
}

private fun formatTimestamp(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(millis))
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
    }
}
