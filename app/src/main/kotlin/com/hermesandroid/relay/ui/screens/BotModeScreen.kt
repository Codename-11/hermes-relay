package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import com.hermesandroid.relay.ui.components.ThemedMessageHost
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.BotGroupMessage
import com.hermesandroid.relay.data.BotGroupRoom
import com.hermesandroid.relay.data.BotGatewayRoute
import com.hermesandroid.relay.data.BotGatewayRouteKey
import com.hermesandroid.relay.data.BotModeState
import com.hermesandroid.relay.data.BotRosterEntry
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import java.io.File
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Bundle-compatible key; length-prefix the connection so delimiters cannot alias owners. */
internal val BotRosterEntry.lazyItemKey: String
    get() {
        val owner = route?.key ?: return "bot:unbound:${profile.name}"
        return "bot:route:${owner.connectionId.length}:${owner.connectionId}:${owner.profileName}"
    }

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
    openPicker: Boolean = false,
    onPickerHandled: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenBotChat: (route: BotGatewayRoute, sessionId: String) -> Unit,
    onOpenGroup: (roomKey: String) -> Unit,
) {
    val state by connectionViewModel.botModeState.collectAsState()
    val connections by connectionViewModel.connections.collectAsState()
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    var openingRoute by remember { mutableStateOf<BotGatewayRouteKey?>(null) }
    var showCreateBot by remember { mutableStateOf(false) }
    var creatingBot by remember { mutableStateOf(false) }
    var selectedGatewayId by rememberSaveable { mutableStateOf<String?>(null) }
    val chatOpenFailed = stringResource(R.string.bot_mode_chat_open_failed)
    val botCreateFailed = stringResource(R.string.bot_mode_create_failed)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(activeConnection?.id, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                connectionViewModel.refreshBotModeInBackground()
                delay(BOT_MODE_REFRESH_MS)
            }
        }
    }
    LaunchedEffect(connections, selectedGatewayId) {
        if (selectedGatewayId != null && connections.none { it.id == selectedGatewayId }) {
            selectedGatewayId = null
        }
    }

    val openBot: (BotRosterEntry) -> Unit = open@{ bot ->
        val route = bot.route ?: return@open
        if (openingRoute != null) return@open
        openingRoute = route.key
        scope.launch {
            val result = connectionViewModel.ensureCanonicalBotChat(route)
                .map { it.resolvedSessionId }
            result.fold(
                onSuccess = { onOpenBotChat(route, it) },
                onFailure = { snackbar.showSnackbar(it.message ?: chatOpenFailed) },
            )
            openingRoute = null
        }
    }

    BotModeContent(
        openPicker = openPicker,
        onPickerHandled = onPickerHandled,
        state = state,
        connections = connections,
        activeConnection = activeConnection,
        selectedGatewayId = selectedGatewayId,
        onOpenSettings = onOpenSettings,
        onOpenChat = onOpenChat,
        onRefresh = connectionViewModel::refreshBotMode,
        onSelectGateway = { selectedGatewayId = it },
        openingRoute = openingRoute,
        onOpenBot = openBot,
        onOpenGroup = { onOpenGroup(it.key) },
        onNewBot = { showCreateBot = true },
        snackbarHost = { ThemedMessageHost(snackbar) },
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
            gatewayLabel = connections.firstOrNull { it.id == (selectedGatewayId ?: activeConnection?.id) }?.label,
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
                        onSuccess = { createdName ->
                            showCreateBot = false
                            val created = connectionViewModel.botModeState.value.roster.bots.firstOrNull {
                                it.route?.connectionId == targetConnectionId && it.profile.name == createdName
                            }
                            if (created != null) openBot(created)
                            else snackbar.showSnackbar(resources.getString(R.string.bot_mode_created, title))
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
    openPicker: Boolean = false,
    onPickerHandled: () -> Unit = {},
    state: BotModeState,
    connections: List<Connection>,
    activeConnection: Connection?,
    selectedGatewayId: String? = null,
    onOpenSettings: () -> Unit,
    onOpenChat: () -> Unit,
    onRefresh: () -> Unit,
    onSelectGateway: (String?) -> Unit,
    openingRoute: BotGatewayRouteKey? = null,
    onOpenBot: (BotRosterEntry) -> Unit,
    onOpenGroup: (BotGroupRoom) -> Unit,
    onNewBot: () -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    nowMs: Long = System.currentTimeMillis(),
    botAvatar: @Composable (BotRosterEntry, Dp) -> Unit = { bot, size ->
        BotFallbackAvatar(bot.displayName, size)
    },
) {
    var gatewayMenuOpen by remember { mutableStateOf(false) }
    var showNewChat by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openPicker) {
        if (openPicker) {
            showNewChat = true
            onPickerHandled()
        }
    }
    val multiGateway = connections.size > 1
    val showRecentShortcuts = LocalConfiguration.current.screenHeightDp >= 480
    val visibleBots = remember(state.roster.bots, selectedGatewayId) {
        state.roster.bots.filterNot(BotRosterEntry::hidden)
            .filter { selectedGatewayId == null || it.route?.connectionId == selectedGatewayId }
    }
    val visibleGroups = remember(state.roster.groups, selectedGatewayId) {
        state.roster.groups.filter { selectedGatewayId == null || selectedGatewayId in it.sourceConnectionIds }
    }
    val pinnedBots = remember(visibleBots) {
        visibleBots.filter { !it.stale && it.latestActivityAtMs > 0L }
            .sortedByDescending(BotRosterEntry::latestActivityAtMs).take(PINNED_BOT_COUNT)
    }
    val rows = remember(visibleBots, visibleGroups) {
        (visibleBots.map(BotModeRow::Bot) + visibleGroups.map(BotModeRow::Group))
            .sortedByDescending(BotModeRow::activityAtMs)
    }

    Scaffold(
        containerColor = RelayRefresh.Background,
        snackbarHost = snackbarHost,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BotModeCircleButton(
                    icon = Icons.Filled.AccountCircle,
                    contentDescription = stringResource(R.string.cd_settings),
                    onClick = onOpenSettings,
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    // The title doubles as the gateway scope picker when there is more than one.
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(enabled = multiGateway) { gatewayMenuOpen = true }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            selectedGatewayId
                                ?.let { id -> connections.firstOrNull { it.id == id }?.label }
                                ?: stringResource(R.string.bot_mode_title),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (multiGateway) {
                            Icon(
                                Icons.Filled.ExpandMore,
                                contentDescription = stringResource(R.string.bot_mode_all_gateways),
                            )
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
                BotModeCircleButton(
                    icon = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.bot_mode_new_chat),
                    onClick = { showNewChat = true },
                    primary = true,
                )
            }
        },
    ) { padding ->
        val refreshLabel = stringResource(R.string.bot_mode_refresh)
        PullToRefreshBox(
            isRefreshing = state.loading && rows.isNotEmpty(),
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding).semantics {
                customActions = listOf(CustomAccessibilityAction(refreshLabel) { onRefresh(); true })
            },
        ) {
            when {
                state.loading && rows.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                }
                rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BotModeEmptyState(error = state.error, onRefresh = onRefresh)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp),
                ) {
                    if (showRecentShortcuts && pinnedBots.isNotEmpty()) {
                        item(key = "pinned") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                pinnedBots.forEach { bot ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .weight(1f)
                                            .semantics(mergeDescendants = true) {}
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable(enabled = openingRoute == null, role = Role.Button) { onOpenBot(bot) }
                                            .padding(horizontal = 4.dp, vertical = 8.dp),
                                    ) {
                                        botAvatar(bot, 64.dp)
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            bot.displayName,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.heightIn(min = 36.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    items(
                        items = rows,
                        contentType = { row -> if (row is BotModeRow.Bot) "bot" else "group" },
                        key = { row -> when (row) {
                            is BotModeRow.Bot -> row.value.lazyItemKey
                            is BotModeRow.Group -> "group:${row.value.key}"
                        } },
                    ) { row ->
                        when (row) {
                            is BotModeRow.Bot -> BotConversationRow(
                                bot = row.value,
                                // Same-named bots on different gateways need their source to tell them apart.
                                connectionLabel = row.value.route?.connectionLabel?.takeIf { multiGateway },
                                opening = openingRoute != null && openingRoute == row.value.route?.key,
                                enabled = openingRoute == null,
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
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 88.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }

    if (showNewChat) {
        BotNewChatSheet(
            bots = visibleBots,
            createEnabled = connections.any {
                it.id == (selectedGatewayId ?: activeConnection?.id) && it.resolvedDashboardUrl.isNotBlank()
            },
            onOpenChat = onOpenChat,
            onDismiss = { showNewChat = false },
            onCreate = {
                showNewChat = false
                onNewBot()
            },
            onOpen = { bot ->
                showNewChat = false
                onOpenBot(bot)
            },
            botAvatar = botAvatar,
        )
    }
}

/** The primary New action chooses a bot; Main chat is an explicit secondary route. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotNewChatSheet(
    bots: List<BotRosterEntry>,
    createEnabled: Boolean,
    onOpenChat: () -> Unit,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (BotRosterEntry) -> Unit,
    botAvatar: @Composable (BotRosterEntry, Dp) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(bots, query) {
        val needle = query.trim()
        bots.filter { bot ->
            needle.isBlank() || listOf(bot.displayName, bot.profile.name, bot.profile.description,
                bot.route?.connectionLabel.orEmpty()).any { it.contains(needle, ignoreCase = true) }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            stringResource(R.string.bot_mode_new_chat),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.bot_mode_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            item(key = "create") {
                BotSheetRow(onClick = onCreate, enabled = createEnabled) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.bot_mode_create), style = MaterialTheme.typography.titleMedium)
                        if (!createEnabled) Text(
                            stringResource(R.string.bot_mode_gateway_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (matches.isEmpty() && query.isNotBlank()) item(key = "no-results") {
                Text(
                    stringResource(R.string.chat_search_no_results),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            items(matches, key = { "to:" + it.lazyItemKey }, contentType = { "bot" }) { bot ->
                BotSheetRow(onClick = { onOpen(bot) }) {
                    botAvatar(bot, 48.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bot.displayName, style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(bot.route?.connectionLabel, "@${bot.handle}").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (bot.stale) Text(stringResource(R.string.bot_mode_offline),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        BotSheetRow(onClick = { onDismiss(); onOpenChat() }) {
            Icon(Icons.Filled.ChatBubble, null,
                modifier = Modifier.size(48.dp).padding(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.bot_mode_main_chat),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BotSheetRow(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .semantics(mergeDescendants = true) {}
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun BotModeCircleButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, primary: Boolean = false) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun BotConversationRow(
    bot: BotRosterEntry,
    connectionLabel: String?,
    opening: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    avatar: @Composable () -> Unit,
    nowMs: Long,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            avatar()
            if (opening) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                CircularProgressIndicator(Modifier.size(28.dp).padding(4.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    bot.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    bot.latestActivityAtMs.toBotModeTime(nowMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 112.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(connectionLabel?.takeIf(String::isNotBlank), "@${bot.handle}").joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (bot.stale) {
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bot_mode_offline), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                when {
                    opening -> stringResource(R.string.bot_mode_opening_chat)
                    bot.latestPreview.isNotBlank() -> bot.latestPreview
                    bot.profile.description.isNotBlank() -> bot.profile.description
                    else -> stringResource(R.string.bot_mode_no_messages)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
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
            .semantics(mergeDescendants = true) {}
            .clickable(role = Role.Button, onClick = onClick)
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 112.dp).padding(start = 8.dp),
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
    val pathFlow = remember(connectionViewModel, bot.route?.key, bot.profile.name) {
        bot.route?.let { route ->
            connectionViewModel.profileIconFlow(route.connectionId, route.profileName)
        } ?: connectionViewModel.profileIconFlow(bot.profile.name)
    }
    val path by pathFlow.collectAsState(initial = null)
    BotAvatar(path, bot.displayName, size)
}

@Composable
internal fun BotAvatar(path: String?, label: String, size: Dp) {
    if (path.isNullOrBlank()) {
        BotFallbackAvatar(label, size)
    } else {
        SubcomposeAsyncImage(
            model = File(path),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size),
            error = { BotFallbackAvatar(label, size) },
        )
    }
}

@Composable
internal fun BotFallbackAvatar(label: String, size: Dp) {
    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = botAvatarColor(label),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label.trim().firstOrNull()?.uppercase() ?: "H",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1B1B1F),
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
    gatewayLabel: String?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, title: String, description: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.bot_mode_new_bot)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                gatewayLabel?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
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

private const val PINNED_BOT_COUNT = 3
private const val BOT_MODE_REFRESH_MS = 30_000L

private val BOT_AVATAR_COLORS = listOf(
    Color(0xFF5BC0A9), Color(0xFFEF7A3A), Color(0xFF9B6BF2),
    Color(0xFFEC4F9C), Color(0xFFEF4A4A), Color(0xFF4A86F0),
)

/** Stable per-bot colour so a bot keeps its colour across lists and chats. */
internal fun botAvatarColor(label: String): Color =
    BOT_AVATAR_COLORS[Math.floorMod(label.trim().lowercase().hashCode(), BOT_AVATAR_COLORS.size)]
