package com.hermesandroid.relay.ui.components

import android.content.Context
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.ChatSession
import com.hermesandroid.relay.data.SessionActivityState
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.relayMetadataStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class SessionDrawerFilter {
    All,
    Threads,
    Pinned,
    Archive,
}

internal const val SESSION_DRAWER_LIST_TAG = "session-drawer-list"
internal const val UNPINNED_STAR_ALPHA = 0.45f

data class ProfileSessionRow(
    val profile: String,
    val session: ChatSession,
)

data class ProvisionalThreadRow(
    val chatId: String,
    val title: String,
    val messageCount: Int,
    val lastActivityAt: Long,
)

private const val PROVISIONAL_THREAD_PREFIX = "proactive:"

internal fun sessionWorkLabels(session: ChatSession): List<String> = buildList {
    val repo = (session.gitRepoRoot ?: session.workingDirectory)
        ?.trimEnd('/', '\\')
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() }
    repo?.let(::add)
    session.gitBranch?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
    session.pullRequestNumber?.takeIf { it > 0 }?.let { number ->
        val status = when {
            session.pullRequestDraft -> "Draft"
            !session.pullRequestState.isNullOrBlank() ->
                session.pullRequestState.lowercase().replaceFirstChar { it.uppercaseChar() }
            else -> null
        }
        add(listOfNotNull("PR #$number", status).joinToString(" · "))
    }
}

internal fun sessionPinIcon(pinned: Boolean) =
    if (pinned) Icons.Filled.Star else Icons.Outlined.StarBorder

internal fun resolveSessionDrawerFilter(
    filter: SessionDrawerFilter,
    showThreads: Boolean,
    archiveSupported: Boolean,
): SessionDrawerFilter = when {
    filter == SessionDrawerFilter.Threads && !showThreads -> SessionDrawerFilter.All
    filter == SessionDrawerFilter.Archive && !archiveSupported -> SessionDrawerFilter.All
    else -> filter
}

@Composable
fun SessionDrawerContent(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    scopeTitle: String = "",
    scopeSubtitle: String? = null,
    isLoading: Boolean = false,
    isOpen: Boolean = true,
    activityStates: Map<String, SessionActivityState> = emptyMap(),
    animationEnabled: Boolean = true,
    autoTitlesSupported: Boolean = true,
    archiveSupported: Boolean = true,
    onRefresh: (() -> Unit)? = null,
    onNewChat: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit,
    onSetSessionPinned: (String, Boolean) -> Unit = { _, _ -> },
    onSetSessionArchived: (String, Boolean) -> Unit = { _, _ -> },
    onCopySessionId: ((String) -> Unit)? = null,
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
    provisionalThreads: List<ProvisionalThreadRow> = emptyList(),
    onSelectProvisionalThread: ((String) -> Unit)? = null,
    /** Gateway sources currently hidden from the drawer (default: cron+webhook). */
    hiddenSources: Set<String> = emptySet(),
    /** Toggle a source's visibility (persisted). Null hides the source filter. */
    onToggleSourceHidden: ((String, Boolean) -> Unit)? = null,
    allProfileSessions: List<ProfileSessionRow> = emptyList(),
    allProfileSessionsLoading: Boolean = false,
    onRefreshAllProfiles: (() -> Unit)? = null,
    onSelectProfileSession: ((String, String) -> Unit)? = null,
) {
    var renameDialogSession by remember { mutableStateOf<ChatSession?>(null) }
    var newThreadDialog by remember { mutableStateOf(false) }
    var sourceFilterOpen by remember { mutableStateOf(false) }
    var deleteDialogSession by remember { mutableStateOf<ChatSession?>(null) }
    var query by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(SessionDrawerFilter.All) }
    var allProfilesOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val trimmedQuery = query.trim()
    // Threads affordance shows when the capability is active OR there's already at least one
    // agent Thread (source=phone) in the list. If the filter is on Threads but they've
    // vanished, fall back to All so the drawer never gets stuck on an empty hidden filter.
    val provisionalSessions = provisionalThreads.map { thread ->
        ChatSession(
            sessionId = "$PROVISIONAL_THREAD_PREFIX${thread.chatId}",
            title = thread.title,
            model = null,
            messageCount = thread.messageCount,
            updatedAt = thread.lastActivityAt,
            startedAt = thread.lastActivityAt,
            lastActivityAt = thread.lastActivityAt,
            source = "phone",
        )
    }
    val allSessions = sessions + provisionalSessions
    val showThreads = threadsCapabilityActive || allSessions.any { isThreadSource(it.source) }
    val activeFilter = resolveSessionDrawerFilter(filter, showThreads, archiveSupported)
    // External gateway sources present (discord/telegram/cron/…) for the source
    // filter dropdown. Own chats (tui/api_server) + phone Threads aren't listed.
    val presentSources = sessions
        .mapNotNull { it.source?.trim()?.lowercase()?.takeIf { s -> s.isNotBlank() } }
        .distinct()
        .filter { sourceBadge(it) != null }
        .sorted()
    val visibleSessions = allSessions
        .asSequence()
        .filter { session ->
            when (activeFilter) {
                SessionDrawerFilter.All -> !session.archived
                SessionDrawerFilter.Threads ->
                    isThreadSource(session.source) &&
                        !session.archived
                SessionDrawerFilter.Pinned ->
                    session.pinned && !session.archived
                SessionDrawerFilter.Archive -> session.archived
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
                session.model.orEmpty().contains(needle, ignoreCase = true) ||
                sessionWorkLabels(session).any { it.contains(needle, ignoreCase = true) }
        }
        .sortedWith(
            compareByDescending<ChatSession> { it.pinned }
                .thenByDescending { it.activityTimestamp }
                .thenByDescending { it.startTimestamp }
                .thenBy { it.title.orEmpty().lowercase(locale = Locale.ROOT) }
        )
        .toList()
    val topVisibleSessionId = visibleSessions.firstOrNull()?.sessionId

    LaunchedEffect(isOpen, activeFilter, trimmedQuery, topVisibleSessionId) {
        if (isOpen && topVisibleSessionId != null) {
            // A drawer-open refresh can reorder rows after the initial scroll.
            // Override LazyColumn's normal key anchoring so the new leading
            // session is visible instead of being retained above the viewport.
            listState.requestScrollToItem(0)
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
                    text = scopeTitle.ifBlank { stringResource(R.string.drawer_filter_sessions) },
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
                                contentDescription = stringResource(R.string.drawer_filter_by_source),
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
                                text = stringResource(R.string.drawer_show_sources),
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
                IconButton(
                    onClick = { searchExpanded = !searchExpanded },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = stringResource(R.string.drawer_search_sessions),
                        tint = if (searchExpanded || query.isNotBlank()) {
                            RelayRefresh.Relay
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
                // Manual re-pull: the server titles a session asynchronously after
                // the first turn (and never pushes a rename), so a refresh is the
                // way to pick up a title the auto-reconcile window missed.
                onRefresh?.let { refresh ->
                    IconButton(onClick = refresh, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.drawer_refresh_sessions),
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
            if (onRefreshAllProfiles != null && onSelectProfileSession != null) {
                TextButton(
                    onClick = {
                        allProfilesOpen = true
                        onRefreshAllProfiles()
                    },
                ) {
                    Text(stringResource(R.string.drawer_all_profiles))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // New Chat button
            Button(
                onClick = onNewChat,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.drawer_new_chat))
            }

            if (searchExpanded || query.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    placeholder = { Text(stringResource(R.string.drawer_search_placeholder)) },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SessionDrawerFilter.entries
                    .filter { item ->
                        (item != SessionDrawerFilter.Threads || showThreads) &&
                            (item != SessionDrawerFilter.Archive || archiveSupported)
                    }
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
                                        Text(text = when (item) {
                                            SessionDrawerFilter.All -> stringResource(R.string.drawer_filter_all)
                                            SessionDrawerFilter.Threads -> stringResource(R.string.drawer_filter_threads)
                                            SessionDrawerFilter.Pinned -> stringResource(R.string.drawer_filter_pinned)
                                            SessionDrawerFilter.Archive -> stringResource(R.string.drawer_filter_archive)
                                        }, style = relayMetadataStyle())
                                        BetaChip()
                                    }
                                } else {
                                    Text(text = when (item) {
                                        SessionDrawerFilter.All -> stringResource(R.string.drawer_filter_all)
                                        SessionDrawerFilter.Threads -> stringResource(R.string.drawer_filter_threads)
                                        SessionDrawerFilter.Pinned -> stringResource(R.string.drawer_filter_pinned)
                                        SessionDrawerFilter.Archive -> stringResource(R.string.drawer_filter_archive)
                                    }, style = relayMetadataStyle())
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
                    Text(stringResource(R.string.drawer_new_thread))
                }
            }
            if (!autoTitlesSupported) {
                // This connection runs chats over the api_server SSE path, which
                // doesn't auto-name sessions (only the gateway transport does).
                // A quiet hint so consistently-untitled chats read as expected
                // rather than broken — rename is one tap away via ⋮. (issue #133)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.drawer_chats_not_named),
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
                    text = stringResource(R.string.drawer_loading_sessions),
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
                    text = if (allSessions.isEmpty()) stringResource(R.string.drawer_no_sessions) else stringResource(R.string.drawer_no_matching_sessions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.drawer_start_conversation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.testTag(SESSION_DRAWER_LIST_TAG),
            ) {
                items(visibleSessions, key = { it.sessionId }) { session ->
                    SessionItem(
                        session = session,
                        isActive = session.sessionId == currentSessionId,
                        activityState = activityStates[session.sessionId],
                        animationEnabled = animationEnabled,
                        pinned = session.pinned,
                        archived = session.archived,
                        archiveSupported = archiveSupported,
                        onClick = {
                            if (session.sessionId.startsWith(PROVISIONAL_THREAD_PREFIX)) {
                                onSelectProvisionalThread?.invoke(
                                    session.sessionId.removePrefix(PROVISIONAL_THREAD_PREFIX),
                                )
                            } else {
                                onSelectSession(session.sessionId)
                            }
                        },
                        actionsEnabled = !session.sessionId.startsWith(PROVISIONAL_THREAD_PREFIX),
                        onTogglePinned = {
                            onSetSessionPinned(session.sessionId, !session.pinned)
                        },
                        onToggleArchived = {
                            onSetSessionArchived(session.sessionId, !session.archived)
                        },
                        onRename = { renameDialogSession = session },
                        onCopySessionId = { onCopySessionId?.invoke(session.sessionId) },
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
            title = { Text(stringResource(R.string.drawer_new_thread)) },
            text = {
                OutlinedTextField(
                    value = threadName,
                    onValueChange = { threadName = it },
                    label = { Text(stringResource(R.string.drawer_thread_name)) },
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
                    Text(stringResource(R.string.drawer_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { newThreadDialog = false }) {
                    Text(stringResource(R.string.drawer_cancel))
                }
            },
        )
    }

    // Rename dialog
    renameDialogSession?.let { session ->
        var newTitle by remember(session) { mutableStateOf(session.title ?: "") }
        AlertDialog(
            onDismissRequest = { renameDialogSession = null },
            title = { Text(stringResource(R.string.drawer_rename_session)) },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text(stringResource(R.string.drawer_title)) },
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
                    Text(stringResource(R.string.drawer_rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogSession = null }) {
                    Text(stringResource(R.string.drawer_cancel))
                }
            }
        )
    }

    // Delete confirmation dialog
    deleteDialogSession?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteDialogSession = null },
            title = { Text(stringResource(R.string.drawer_delete_session_title)) },
            text = {
                Text(stringResource(R.string.drawer_delete_session_prefix) + (session.title ?: stringResource(R.string.drawer_untitled)) + stringResource(R.string.drawer_delete_session_suffix))
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(session.sessionId)
                    deleteDialogSession = null
                }) {
                    Text(stringResource(R.string.drawer_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogSession = null }) {
                    Text(stringResource(R.string.drawer_cancel))
                }
            }
        )
    }

    if (allProfilesOpen) {
        var allQuery by remember { mutableStateOf("") }
        val needle = allQuery.trim()
        val rows = allProfileSessions.filter { row ->
            needle.isBlank() ||
                row.profile.contains(needle, ignoreCase = true) ||
                row.session.title.orEmpty().contains(needle, ignoreCase = true) ||
                row.session.sessionId.contains(needle, ignoreCase = true) ||
                sessionWorkLabels(row.session).any { it.contains(needle, ignoreCase = true) }
        }
        AlertDialog(
            onDismissRequest = { allProfilesOpen = false },
            title = { Text(stringResource(R.string.drawer_all_profiles)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = allQuery,
                        onValueChange = { allQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        placeholder = { Text(stringResource(R.string.drawer_search_placeholder)) },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    when {
                        allProfileSessionsLoading && allProfileSessions.isEmpty() ->
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        rows.isEmpty() -> Text(
                            stringResource(R.string.drawer_no_profile_sessions),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> LazyColumn(modifier = Modifier.height(420.dp)) {
                            items(rows, key = { "${it.profile}:${it.session.sessionId}" }) { row ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            allProfilesOpen = false
                                            onSelectProfileSession?.invoke(row.profile, row.session.sessionId)
                                        }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                ) {
                                    Text(
                                        row.session.title?.takeIf { it.isNotBlank() }
                                            ?: stringResource(R.string.drawer_untitled),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        row.profile,
                                        style = relayMetadataStyle(),
                                        color = RelayRefresh.Relay,
                                    )
                                    sessionWorkLabels(row.session).takeIf { it.isNotEmpty() }?.let { labels ->
                                        Text(
                                            labels.joinToString(" • "),
                                            style = relayMetadataStyle(),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { allProfilesOpen = false }) {
                    Text(stringResource(R.string.drawer_close))
                }
            },
        )
    }
}

@Composable
private fun SessionItem(
    session: ChatSession,
    isActive: Boolean,
    activityState: SessionActivityState?,
    animationEnabled: Boolean,
    pinned: Boolean,
    archived: Boolean,
    archiveSupported: Boolean,
    onClick: () -> Unit,
    actionsEnabled: Boolean = true,
    onTogglePinned: () -> Unit,
    onToggleArchived: () -> Unit,
    onRename: () -> Unit,
    onCopySessionId: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val locale = LocalLocale.current.platformLocale
    val context = LocalContext.current
    val untitledLabel = stringResource(R.string.drawer_untitled)
    val activityLabel = when (activityState) {
        SessionActivityState.Working -> stringResource(R.string.drawer_activity_working)
        SessionActivityState.NeedsInput -> stringResource(R.string.drawer_activity_needs_input)
        null -> null
    }
    val motion = rememberAccessibleMotionState()
    val backgroundColor = if (isActive) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .sessionActivityBorder(
                state = activityState,
                animated = animationEnabled && motion.osAnimations && !motion.touchExploration,
            )
            .semantics {
                activityLabel?.let { stateDescription = it }
            }
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
                text = session.title ?: untitledLabel,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isActive) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            val workLabels = sessionWorkLabels(session)
            if (workLabels.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    workLabels.forEach { label ->
                        Text(
                            text = label,
                            style = relayMetadataStyle(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (activityState != null && activityLabel != null) {
                    SessionActivityIndicator(activityState, activityLabel)
                }
                if (pinned) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = RelayRefresh.Amber,
                        modifier = Modifier.size(12.dp),
                    )
                }
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
                            text = stringResource(R.string.drawer_thread),
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
                sessionTimestampText(session, locale, context)?.let { timestamp ->
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
                        text = "${session.messageCount}" + stringResource(R.string.drawer_msgs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }

        if (actionsEnabled) Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.drawer_session_actions),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (pinned) {
                                stringResource(R.string.drawer_unpin_session)
                            } else {
                                stringResource(R.string.drawer_pin_session)
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            sessionPinIcon(pinned),
                            contentDescription = null,
                            tint = if (pinned) {
                                RelayRefresh.Amber
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = UNPINNED_STAR_ALPHA)
                            },
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onTogglePinned()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_copy_session_id)) },
                    leadingIcon = {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onCopySessionId()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_rename)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                if (archiveSupported) {
                    DropdownMenuItem(
                        text = { Text(if (archived) stringResource(R.string.drawer_restore) else stringResource(R.string.drawer_archive)) },
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
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.drawer_delete),
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

@Composable
private fun SessionActivityIndicator(state: SessionActivityState, label: String) {
    val color = when (state) {
        SessionActivityState.Working -> RelayRefresh.Relay
        SessionActivityState.NeedsInput -> RelayRefresh.Amber
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
        Text(
            text = label,
            style = relayMetadataStyle(),
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun Modifier.sessionActivityBorder(
    state: SessionActivityState?,
    animated: Boolean,
): Modifier {
    if (state == null) return this
    val color = when (state) {
        SessionActivityState.Working -> RelayRefresh.Relay
        SessionActivityState.NeedsInput -> RelayRefresh.Amber
    }
    val shouldRotate = animated && state == SessionActivityState.Working
    val phase = if (shouldRotate) {
        val transition = rememberInfiniteTransition(label = "session-activity")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2_230, easing = LinearEasing),
            ),
            label = "session-activity-glow",
        )
    } else {
        null
    }
    return drawWithCache {
        val coreWidth = 1.25.dp.toPx()
        val coreInset = coreWidth / 2f
        val haloWidth = 5.dp.toPx()
        val haloInset = haloWidth / 2f
        val radius = 12.dp.toPx()
        // Matching transparent endpoints make phase 0 and 1 identical, so the
        // full shader rotation loops without the dash-pattern reset of the old ring.
        val animatedShader = SweepGradient(
            size.width / 2f,
            size.height / 2f,
            intArrayOf(
                color.copy(alpha = 0f).toArgb(),
                color.copy(alpha = 0f).toArgb(),
                color.copy(alpha = 0.16f).toArgb(),
                color.copy(alpha = 0.72f).toArgb(),
                color.toArgb(),
                color.copy(alpha = 0.34f).toArgb(),
                color.copy(alpha = 0f).toArgb(),
            ),
            floatArrayOf(0f, 0.52f, 0.66f, 0.78f, 0.84f, 0.92f, 1f),
        )
        val shaderMatrix = Matrix()
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = haloWidth
            alpha = 88
            shader = animatedShader
        }
        val middlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.75.dp.toPx()
            alpha = 150
            shader = animatedShader
        }
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = coreWidth
            shader = animatedShader
        }
        val haloRect = RectF(
            haloInset,
            haloInset,
            size.width - haloInset,
            size.height - haloInset,
        )
        val middleInset = middlePaint.strokeWidth / 2f
        val middleRect = RectF(
            middleInset,
            middleInset,
            size.width - middleInset,
            size.height - middleInset,
        )
        val coreRect = RectF(
            coreInset,
            coreInset,
            size.width - coreInset,
            size.height - coreInset,
        )

        onDrawWithContent {
            drawContent()
            if (phase != null) {
                shaderMatrix.setRotate(
                    phase.value * 360f - 90f,
                    size.width / 2f,
                    size.height / 2f,
                )
                animatedShader.setLocalMatrix(shaderMatrix)
                drawContext.canvas.nativeCanvas.apply {
                    drawRoundRect(haloRect, radius, radius, haloPaint)
                    drawRoundRect(middleRect, radius, radius, middlePaint)
                    drawRoundRect(coreRect, radius, radius, corePaint)
                }
            } else {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(color.copy(alpha = 0.72f), color.copy(alpha = 0.28f))
                    ),
                    topLeft = androidx.compose.ui.geometry.Offset(coreInset, coreInset),
                    size = androidx.compose.ui.geometry.Size(
                        width = size.width - coreWidth,
                        height = size.height - coreWidth,
                    ),
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width = coreWidth),
                )
            }
        }
    }
}

private fun sessionTimestampText(session: ChatSession, locale: Locale, context: Context): String? {
    val timestamp = session.activityTimestamp
    if (timestamp <= 0L) return null
    val hasDistinctActivity =
        session.lastActivityAt > 0L &&
            session.startTimestamp > 0L &&
            session.lastActivityAt != session.startTimestamp
    val prefix = if (hasDistinctActivity) context.getString(R.string.drawer_timestamp_active) else context.getString(R.string.drawer_timestamp_started)
    return "$prefix ${formatTimestamp(timestamp, locale, context)}"
}

private fun formatTimestamp(millis: Long, locale: Locale, context: Context): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    return when {
        diff < 60_000 -> context.getString(R.string.drawer_just_now)
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> SimpleDateFormat("h:mm a", locale).format(Date(millis))
        else -> SimpleDateFormat("MMM d", locale).format(Date(millis))
    }
}
