package com.hermesandroid.relay.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.BotGroupMessage
import com.hermesandroid.relay.data.BotGroupRoom
import com.hermesandroid.relay.data.BotGatewayRoute
import com.hermesandroid.relay.data.BotModeState
import com.hermesandroid.relay.data.BotRosterEntry
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class BotModeFilter { All, Bots, Groups }

private sealed interface BotModeRow {
    val activityAtMs: Long

    data class Bot(val value: BotRosterEntry) : BotModeRow {
        override val activityAtMs: Long = value.latestActivityAtMs
    }

    data class Group(val value: BotGroupRoom) : BotModeRow {
        override val activityAtMs: Long = value.latestActivityAtMs
    }
}

@Composable
fun BotModeScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onOpenBotChat: (route: BotGatewayRoute, sessionId: String) -> Unit,
    onOpenGroup: (roomKey: String) -> Unit,
) {
    val state by connectionViewModel.botModeState.collectAsState()
    val connections by connectionViewModel.connections.collectAsState()
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var openingProfile by remember { mutableStateOf<String?>(null) }
    var showCreateBot by remember { mutableStateOf(false) }
    var creatingBot by remember { mutableStateOf(false) }
    var selectedGatewayId by rememberSaveable { mutableStateOf<String?>(null) }
    val chatOpenFailed = stringResource(R.string.bot_mode_chat_open_failed)
    val botCreateFailed = stringResource(R.string.bot_mode_create_failed)

    LaunchedEffect(activeConnection?.id) {
        while (true) {
            connectionViewModel.refreshBotMode()
            delay(BOT_MODE_REFRESH_MS)
        }
    }
    LaunchedEffect(connections, selectedGatewayId) {
        if (selectedGatewayId != null && connections.none { it.id == selectedGatewayId }) {
            selectedGatewayId = null
        }
    }

    BotModeContent(
        state = state,
        connections = connections,
        activeConnection = activeConnection,
        selectedGatewayId = selectedGatewayId,
        onBack = onBack,
        onRefresh = connectionViewModel::refreshBotMode,
        onSelectGateway = { selectedGatewayId = it },
        openingProfile = openingProfile,
        onOpenBot = { bot ->
            val route = bot.route ?: return@BotModeContent
            openingProfile = bot.profile.name
            scope.launch {
                val result = connectionViewModel.ensureCanonicalBotChat(route)
                    .map { it.resolvedSessionId }
                result.fold(
                    onSuccess = { onOpenBotChat(route, it) },
                    onFailure = { snackbar.showSnackbar(it.message ?: chatOpenFailed) },
                )
                openingProfile = null
            }
        },
        onOpenGroup = { onOpenGroup(it.key) },
        onNewBot = { showCreateBot = true },
        snackbarHost = { SnackbarHost(snackbar) },
        botAvatar = { bot, size ->
            BotProfileAvatar(
                connectionViewModel = connectionViewModel,
                bot = bot,
                size = size,
            )
        },
    )

    if (showCreateBot) {
        CreateBotDialog(
            saving = creatingBot,
            onDismiss = { showCreateBot = false },
            onCreate = { name, title, description ->
                scope.launch {
                    creatingBot = true
                    val targetConnectionId = selectedGatewayId ?: activeConnection?.id
                    val createResult = if (targetConnectionId == null) {
                        Result.failure(IllegalStateException(botCreateFailed))
                    } else {
                        connectionViewModel.createBot(
                            targetConnectionId,
                            name,
                            title,
                            description,
                        )
                    }
                    createResult.fold(
                        onSuccess = {
                            showCreateBot = false
                            snackbar.showSnackbar(
                                resources.getString(R.string.bot_mode_created, title),
                            )
                        },
                        onFailure = { snackbar.showSnackbar(it.message ?: botCreateFailed) },
                    )
                    creatingBot = false
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BotModeContent(
    state: BotModeState,
    connections: List<Connection>,
    activeConnection: Connection?,
    selectedGatewayId: String? = null,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelectGateway: (String?) -> Unit,
    openingProfile: String? = null,
    onOpenBot: (BotRosterEntry) -> Unit,
    onOpenGroup: (BotGroupRoom) -> Unit,
    onNewBot: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    nowMs: Long = System.currentTimeMillis(),
    botAvatar: @Composable (BotRosterEntry, Dp) -> Unit = { bot, size ->
        BotFallbackAvatar(bot.displayName, size)
    },
) {
    var filter by remember { mutableStateOf(BotModeFilter.All) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var gatewayMenuOpen by remember { mutableStateOf(false) }
    val visibleBots = state.roster.bots
        .filterNot(BotRosterEntry::hidden)
        .filter { selectedGatewayId == null || it.route?.connectionId == selectedGatewayId }
    val visibleGroups = state.roster.groups.filter { group ->
        selectedGatewayId == null || selectedGatewayId in group.sourceConnectionIds
    }
    val activeBots = visibleBots.filter { bot ->
        !bot.stale && bot.presenceActivityAtMs >= nowMs - ACTIVE_WINDOW_MS
    }.sortedByDescending(BotRosterEntry::presenceActivityAtMs).take(6)
    val needle = query.trim()
    val rows = buildList<BotModeRow> {
        if (filter != BotModeFilter.Groups) {
            addAll(visibleBots.filter { bot ->
                needle.isBlank() || listOf(
                    bot.displayName,
                    bot.profile.name,
                    bot.profile.description,
                    bot.latestPreview,
                ).any { it.contains(needle, ignoreCase = true) }
            }.map(BotModeRow::Bot))
        }
        if (filter != BotModeFilter.Bots) {
            addAll(visibleGroups.filter { room ->
                needle.isBlank() || room.name.contains(needle, ignoreCase = true) ||
                    room.latestMessage?.text.orEmpty().contains(needle, ignoreCase = true)
            }.map(BotModeRow::Group))
        }
    }.sortedByDescending(BotModeRow::activityAtMs)

    Scaffold(
        containerColor = RelayRefresh.Background,
        snackbarHost = snackbarHost,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.bot_mode_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.bot_mode_search),
                            tint = if (searchOpen || query.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = RelayRefresh.Background),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewBot,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.bot_mode_new_bot))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (searchOpen || query.isNotBlank()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.bot_mode_search_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Surface(
                    onClick = { gatewayMenuOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    activeConnection?.label?.trim()?.firstOrNull()?.uppercase() ?: "H",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            selectedGatewayId
                                ?.let { id -> connections.firstOrNull { it.id == id }?.label }
                                ?: stringResource(R.string.bot_mode_all_gateways),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Filled.ExpandMore, contentDescription = null)
                    }
                }
                DropdownMenu(
                    expanded = gatewayMenuOpen,
                    onDismissRequest = { gatewayMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bot_mode_all_gateways)) },
                        leadingIcon = if (selectedGatewayId == null) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else null,
                        onClick = {
                            gatewayMenuOpen = false
                            onSelectGateway(null)
                        },
                    )
                    connections.forEach { connection ->
                        val selected = connection.id == selectedGatewayId
                        val gatewayStatus = state.gateways.firstOrNull { it.connectionId == connection.id }
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(connection.label)
                                    if (gatewayStatus?.stale == true || gatewayStatus?.error != null) {
                                        Text(
                                            stringResource(R.string.bot_mode_offline),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else null,
                            onClick = {
                                gatewayMenuOpen = false
                                if (!selected) onSelectGateway(connection.id)
                            },
                        )
                    }
                }
            }

            BotModeFilterBar(filter = filter, onFilter = { filter = it })

            if (activeBots.isNotEmpty() && filter != BotModeFilter.Groups && needle.isBlank()) {
                Text(
                    stringResource(R.string.bot_mode_active_now),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    items(activeBots, key = { it.profile.name }) { bot ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(78.dp)
                                .clickable(enabled = openingProfile == null) { onOpenBot(bot) },
                        ) {
                            Box {
                                botAvatar(bot, 56.dp)
                                Surface(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(14.dp),
                                    shape = CircleShape,
                                    color = ACTIVE_GREEN,
                                    border = BorderStroke(2.dp, RelayRefresh.Background),
                                ) {}
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                bot.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                bot.route?.connectionLabel.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }

            when {
                state.loading && rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                }
                rows.isEmpty() -> BotModeEmptyState(error = state.error, onRefresh = onRefresh)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp, top = 4.dp),
                ) {
                    itemsIndexed(
                        items = rows,
                        key = { _, row -> when (row) {
                            is BotModeRow.Bot -> "bot:${row.value.profile.name}"
                            is BotModeRow.Group -> "group:${row.value.key}"
                        } },
                    ) { index, row ->
                        when (row) {
                            is BotModeRow.Bot -> BotConversationRow(
                                bot = row.value,
                                connectionLabel = row.value.route?.connectionLabel,
                                opening = openingProfile == row.value.profile.name,
                                onClick = { onOpenBot(row.value) },
                                avatar = { botAvatar(row.value, 56.dp) },
                                nowMs = nowMs,
                            )
                            is BotModeRow.Group -> BotGroupRow(
                                room = row.value,
                                onClick = { onOpenGroup(row.value) },
                                nowMs = nowMs,
                            )
                        }
                        if (index != rows.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 88.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BotModeFilterBar(filter: BotModeFilter, onFilter: (BotModeFilter) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            BotModeFilter.entries.forEach { item ->
                val selected = item == filter
                Surface(
                    onClick = { onFilter(item) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(9.dp),
                    color = if (selected) {
                        RelayRefresh.ElectricMuted.copy(alpha = 0.56f)
                    } else Color.Transparent,
                ) {
                    Text(
                        text = when (item) {
                            BotModeFilter.All -> stringResource(R.string.bot_mode_filter_all)
                            BotModeFilter.Bots -> stringResource(R.string.bot_mode_filter_bots)
                            BotModeFilter.Groups -> stringResource(R.string.bot_mode_filter_groups)
                        },
                        modifier = Modifier.padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = if (selected) {
                            RelayRefresh.Ink
                        } else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun BotConversationRow(
    bot: BotRosterEntry,
    connectionLabel: String?,
    opening: Boolean,
    onClick: () -> Unit,
    avatar: @Composable () -> Unit,
    nowMs: Long,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !opening, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar()
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    bot.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    bot.latestActivityAtMs.toBotModeTime(nowMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!connectionLabel.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$connectionLabel · @${bot.handle}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (bot.stale) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.bot_mode_offline),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Text(
                when {
                    opening -> stringResource(R.string.bot_mode_opening_chat)
                    bot.latestPreview.isNotBlank() -> bot.latestPreview
                    bot.profile.description.isNotBlank() -> bot.profile.description
                    else -> stringResource(R.string.bot_mode_no_messages)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BotGroupRow(room: BotGroupRoom, onClick: () -> Unit, nowMs: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupAvatar(56.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    room.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    room.latestActivityAtMs.toBotModeTime(nowMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(R.string.bot_mode_read_only),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (room.stale) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.bot_mode_offline),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            val latest = room.latestMessage
            Text(
                if (latest == null) {
                    stringResource(R.string.bot_mode_group_no_messages)
                } else {
                    "${latest.senderName}: ${latest.text}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BotModeEmptyState(
    error: String?,
    onRefresh: () -> Unit,
    actionLabel: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.Groups,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            error ?: stringResource(R.string.bot_mode_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRefresh) {
            Text(actionLabel ?: stringResource(R.string.chat_retry))
        }
    }
}

@Composable
private fun BotProfileAvatar(
    connectionViewModel: ConnectionViewModel,
    bot: BotRosterEntry,
    size: Dp,
) {
    val pathFlow = bot.route?.let { route ->
        connectionViewModel.profileIconFlow(route.connectionId, route.profileName)
    } ?: connectionViewModel.profileIconFlow(bot.profile.name)
    val path by pathFlow.collectAsState(initial = null)
    if (path.isNullOrBlank()) {
        BotFallbackAvatar(bot.displayName, size)
    } else {
        Surface(
            modifier = Modifier.size(size),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        ) {
            AsyncImage(
                model = File(path.orEmpty()),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun BotFallbackAvatar(label: String, size: Dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = RelayRefresh.Navy3,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label.trim().firstOrNull()?.uppercase() ?: "H",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = RelayRefresh.Relay,
            )
        }
    }
}

@Composable
private fun GroupAvatar(size: Dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Groups,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(size * 0.52f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotGroupDetailScreen(room: BotGroupRoom?, onBack: () -> Unit) {
    Scaffold(
        containerColor = RelayRefresh.Background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(room?.name ?: stringResource(R.string.bot_mode_group_title))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.bot_mode_read_only),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = RelayRefresh.Background),
            )
        },
    ) { padding ->
        if (room == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                BotModeEmptyState(
                    error = stringResource(R.string.bot_mode_group_missing),
                    onRefresh = onBack,
                    actionLabel = stringResource(R.string.onboarding_back),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.bot_mode_group_read_only_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(room.messages, key = { it.id ?: "${it.atMs}:${it.senderName}:${it.text.hashCode()}" }) { message ->
                    BotGroupMessageBubble(message)
                }
            }
        }
    }
}

@Composable
private fun BotGroupMessageBubble(message: BotGroupMessage) {
    val user = message.senderKind == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (user) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            modifier = Modifier.fillMaxWidth(0.86f),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    message.senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (user) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(3.dp))
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    message.atMs.toBotModeTime(System.currentTimeMillis()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun CreateBotDialog(
    saving: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, title: String, description: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.bot_mode_new_bot)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.lowercase().replace(' ', '-').take(64) },
                    label = { Text(stringResource(R.string.bot_mode_bot_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(128) },
                    label = { Text(stringResource(R.string.bot_mode_bot_title)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(512) },
                    label = { Text(stringResource(R.string.bot_mode_bot_description)) },
                    minLines = 2,
                    maxLines = 4,
                )
                Text(
                    stringResource(R.string.bot_mode_create_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, title.ifBlank { name }, description) },
                enabled = name.isNotBlank() && !saving,
            ) { Text(stringResource(R.string.bot_mode_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.dashboard_cancel))
            }
        },
    )
}

private fun Long.toBotModeTime(nowMs: Long): String {
    if (this <= 0L) return ""
    return DateUtils.getRelativeTimeSpanString(
        this,
        nowMs,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

private const val ACTIVE_WINDOW_MS = 90_000L
private const val BOT_MODE_REFRESH_MS = 30_000L
private val ACTIVE_GREEN = Color(0xFF4DD675)
