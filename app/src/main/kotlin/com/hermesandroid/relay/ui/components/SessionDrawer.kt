package com.hermesandroid.relay.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLocale
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
    Threads("Threads"),
    Pinned("Pinned"),
    Archive("Archive"),
}

@Composable
fun SessionDrawerContent(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    scopeTitle: String = "Sessions",
    scopeSubtitle: String? = null,
    isLoading: Boolean = false,
    isOpen: Boolean = true,
    autoTitlesSupported: Boolean = true,
    onRefresh: (() -> Unit)? = null,
    onNewChat: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    /**
     * When true, the Threads affordance (header spool + filter chip) shows even with no
     * Thread sessions present yet — i.e. the relay Threads capability is paired + opted in
     * (slice 5 wires this from ConnectionViewModel). Until then the affordance is purely
     * data-driven: it appears whenever at least one `source=phone` session is in the list.
     */
    threadsCapabilityActive: Boolean = false,
    /**
     * Create a new agent Thread with the given name (Discord-style "+ New
     * Thread"). Null hides the affordance; when set it shows in the Threads
     * filter view. The first message the user types opens the conversation.
     */
    onNewThread: ((String) -> Unit)? = null,
    /** Gateway sources currently hidden from the drawer (default: cron+webhook). */
    hiddenSources: Set<String> = emptySet(),
    /** Toggle a source's visibility (persisted). Null hides the source filter. */
    onToggleSourceHidden: ((String, Boolean) -> Unit)? = null,
) {
    var renameDialogSession by remember { mutableStateOf<ChatSession?>(null) }
    var newThreadDialog by remember { mutableStateOf(false) }
    var sourceFilterOpen by remember { mutableStateOf(false) }
    var deleteDialogSession by remember { mutableStateOf<ChatSession?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SessionDrawerFilter.All) }
    var pinnedSessionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var archivedSessionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val listState = rememberLazyListState()
    var scrollToTopPending by remember { mutableStateOf(false) }
    val trimmedQuery = query.trim()
    // Threads affordance shows when the capability is active OR there's already at least one
    // agent Thread (source=phone) in the list. If the filter is on Threads but they've
    // vanished, fall back to All so the drawer never gets stuck on an empty hidden filter.
    val showThreads = threadsCapabilityActive || sessions.any { isThreadSource(it.source) }
    val activeFilter = if (filter == SessionDrawerFilter.Threads && !showThreads) {
        SessionDrawerFilter.All
    } else {
        filter
    }
    // External gateway sources present (discord/telegram/cron/…) for the source
    // filter dropdown. Own chats (tui/api_server) + phone Threads aren't listed.
    val presentSources = sessions
        .mapNotNull { it.source?.trim()?.lowercase()?.takeIf { s -> s.isNotBlank() } }
        .distinct()
        .filter { sourceBadge(it) != null }
        .sorted()
    val visibleSessions = sessions
        .asSequence()
        .filter { session ->
            when (activeFilter) {
                SessionDrawerFilter.All -> session.sessionId !in archivedSessionIds
                SessionDrawerFilter.Threads ->
                    isThreadSource(session.source) &&
                        session.sessionId !in archivedSessionIds
                SessionDrawerFilter.Pinned ->
                    session.sessionId in pinnedSessionIds &&
                        session.sessionId !in archivedSessionIds
                SessionDrawerFilter.Archive -> session.sessionId in archivedSessionIds
            }
        }
        .filter { session ->
            // Source visibility (default hides cron+webhook) — only on the "All"
            // view; Threads/Pinned/Archive show their full set.
            if (activeFilter != SessionDrawerFilter.All) return@filter true
            val src = session.source?.trim()?.lowercase()
            src == null || src !in hiddenSources
        }
        .filter { session ->
            val needle = trimmedQuery
            needle.isBlank() ||
                session.sessionId.contains(needle, ignoreCase = true) ||
                session.title.orEmpty().contains(needle, ignoreCase = true) ||
                session.model.orEmpty().contains(needle, ignoreCase = true)
        }
        .sortedWith(
            compareByDescending<ChatSession> { it.sessionId in pinnedSessionIds }
                .thenByDescending { it.activityTimestamp }
                .thenByDescending { it.startTimestamp }
                .thenBy { it.title.orEmpty().lowercase(locale = Locale.ROOT) }
        )
        .toList()
    val topVisibleSessionId = visibleSessions.firstOrNull()?.sessionId

    LaunchedEffect(isOpen) {
        scrollToTopPending = isOpen
        if (isOpen && visibleSessions.isNotEmpty()) {
            listState.scrollToItem(0)
            scrollToTopPending = false
        }
    }

    LaunchedEffect(filter, trimmedQuery) {
        if (isOpen && visibleSessions.isNotEmpty()) {
            listState.scrollToItem(0)
            scrollToTopPending = false
        } else if (isOpen) {
            scrollToTopPending = true
        }
    }

    LaunchedEffect(isOpen, topVisibleSessionId, visibleSessions.size) {
        if (isOpen && scrollToTopPending && visibleSessions.isNotEmpty()) {
            listState.scrollToItem(0)
            scrollToTopPending = false
        }
    }

    ModalDrawerSheet(
        modifier = Modifier.width(320.dp),
        drawerContainerColor = RelayRefresh.Background,
        drawerContentColor = RelayRefresh.Ink,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = scopeTitle,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                // Source filter — show/hide gateway sources (default hides the
                // noisy cron+webhook). Only when external sources are present.
                if (onToggleSourceHidden != null && presentSources.isNotEmpty()) {
                    Box {
                        IconButton(
                            onClick = { sourceFilterOpen = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Filled.FilterList,
                                contentDescription = "Filter by source",
                                tint = if (presentSources.any { it in hiddenSources }) {
                                    RelayRefresh.Relay
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = sourceFilterOpen,
                            onDismissRequest = { sourceFilterOpen = false },
                        ) {
                            Text(
                                text = "Show sources",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                            presentSources.forEach { src ->
                                val badge = sourceBadge(src)
                                val shown = src !in hiddenSources
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = badge?.label ?: src,
                                            color = if (shown) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    },
                                    leadingIcon = {
                                        if (shown) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = badge?.color ?: RelayRefresh.Relay,
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.size(24.dp))
                                        }
                                    },
                                    onClick = { onToggleSourceHidden(src, shown) },
                                )
                            }
                        }
                    }
                }
                // Threads affordance — a clean thread-spool that toggles the Threads
                // filter. Shown only when the Threads capability is active (or a Thread is
                // already present), so an ordinary no-relay drawer is visually unchanged.
                if (showThreads) {
                    IconButton(
                        onClick = {
                            filter = if (filter == SessionDrawerFilter.Threads) {
                                SessionDrawerFilter.All
                            } else {
                                SessionDrawerFilter.Threads
                            }
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        ThreadSpoolGlyph(
                            modifier = Modifier.size(20.dp),
                            tint = if (activeFilter == SessionDrawerFilter.Threads) {
                                RelayRefresh.Relay
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                // Manual re-pull: the server titles a session asynchronously after
                // the first turn (and never pushes a rename), so a refresh is the
                // way to pick up a title the auto-reconcile window missed.
                onRefresh?.let { refresh ->
                    IconButton(onClick = refresh, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Refresh sessions",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
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
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SessionDrawerFilter.entries
                    .filter { it != SessionDrawerFilter.Threads || showThreads }
                    .forEach { item ->
                        FilterChip(
                            selected = activeFilter == item,
                            onClick = { filter = item },
                            label = {
                                if (item == SessionDrawerFilter.Threads) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(text = item.label, style = relayMetadataStyle())
                                        BetaChip()
                                    }
                                } else {
                                    Text(text = item.label, style = relayMetadataStyle())
                                }
                            },
                        )
                    }
            }
            // "+ New Thread" — Discord-style user-created thread, shown when the
            // Threads filter is active. The first message opens the conversation.
            if (activeFilter == SessionDrawerFilter.Threads && onNewThread != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { newThreadDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ThreadSpoolGlyph(
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("New Thread")
                }
            }
            if (!autoTitlesSupported) {
                // This connection runs chats over the api_server SSE path, which
                // doesn't auto-name sessions (only the gateway transport does).
                // A quiet hint so consistently-untitled chats read as expected
                // rather than broken — rename is one tap away via ⋮. (issue #133)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Chats aren't auto-named on this connection — use ⋮ → Rename.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Crossfade the loading→content transition so the list fades in rather
        // than the spinner snapping straight to rows.
        Crossfade(
            targetState = isLoading && sessions.isEmpty(),
            animationSpec = tween(220),
            label = "drawerSessions",
        ) { loading ->
        if (loading) {
            // First load (or a profile switch) — show a quiet spinner instead of
            // flashing "No sessions yet" before the list arrives.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "Loading sessions…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (visibleSessions.isEmpty()) {
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
            LazyColumn(state = listState) {
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
    }

    // New Thread dialog (Discord-style): name a fresh agent Thread.
    if (newThreadDialog) {
        var threadName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newThreadDialog = false },
            title = { Text("New Thread") },
            text = {
                OutlinedTextField(
                    value = threadName,
                    onValueChange = { threadName = it },
                    label = { Text("Thread name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = threadName.trim()
                    if (name.isNotBlank()) {
                        onNewThread?.invoke(name)
                        newThreadDialog = false
                    }
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { newThreadDialog = false }) {
                    Text("Cancel")
                }
            },
        )
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
    var menuOpen by remember { mutableStateOf(false) }
    val locale = LocalLocale.current.platformLocale
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
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        ) {
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Agent Thread tag — the clean spool + "Thread", so a source=phone
                // conversation reads as its own lane in the unified session list (ADR 12).
                if (isThreadSource(session.source)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(RelayRefresh.Relay.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        ThreadSpoolGlyph(
                            modifier = Modifier.size(11.dp),
                            tint = RelayRefresh.Relay,
                        )
                        Text(
                            text = "Thread",
                            style = relayMetadataStyle(),
                            color = RelayRefresh.Relay,
                            maxLines = 1,
                        )
                    }
                }
                // Source badge — external gateway origin (Discord / Telegram /
                // Cron / Webhook / …); null for own chats + the phone Thread.
                sourceBadge(session.source)?.let { badge ->
                    SourceChip(badge)
                }
                sessionTimestampText(session, locale)?.let { timestamp ->
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (session.messageCount > 0) {
                    Text(
                        text = "${session.messageCount} msgs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }

        IconButton(
            onClick = onTogglePinned,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = if (pinned) "Unpin session" else "Pin session",
                tint = if (pinned) RelayRefresh.Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }

        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Session actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (archived) "Restore" else "Archive") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Archive,
                            contentDescription = null,
                            tint = if (archived) {
                                RelayRefresh.Relay
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onToggleArchived()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "Delete",
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

private fun sessionTimestampText(session: ChatSession, locale: Locale): String? {
    val timestamp = session.activityTimestamp
    if (timestamp <= 0L) return null
    val hasDistinctActivity =
        session.lastActivityAt > 0L &&
            session.startTimestamp > 0L &&
            session.lastActivityAt != session.startTimestamp
    val prefix = if (hasDistinctActivity) "Active" else "Started"
    return "$prefix ${formatTimestamp(timestamp, locale)}"
}

private fun formatTimestamp(millis: Long, locale: Locale): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> SimpleDateFormat("h:mm a", locale).format(Date(millis))
        else -> SimpleDateFormat("MMM d", locale).format(Date(millis))
    }
}
