package com.hermesandroid.relay.ui.screens

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hermesandroid.relay.network.EncryptedDashboardCookieStore
import com.hermesandroid.relay.network.DashboardApiClient
import com.hermesandroid.relay.network.DashboardAuthProvider
import com.hermesandroid.relay.network.DashboardAuthSession
import com.hermesandroid.relay.network.DashboardStatus
import com.hermesandroid.relay.network.importDashboardCookieHeader
import com.hermesandroid.relay.ui.components.RelayChromeIconButton
import com.hermesandroid.relay.ui.components.RelayMetricCard
import com.hermesandroid.relay.ui.components.RelayModeStrip
import com.hermesandroid.relay.ui.components.RelayNavTile
import com.hermesandroid.relay.ui.components.RelayPrimaryMode
import com.hermesandroid.relay.ui.components.RelayReturnStrip
import com.hermesandroid.relay.ui.components.RelaySectionCaption
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.relayGridTexture
import com.hermesandroid.relay.ui.theme.relayMetadataStyle
import com.hermesandroid.relay.ui.theme.relayPanel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.text.DateFormat
import java.util.Date

private data class DashboardManagementSection(
    val label: String,
    val path: String,
)

private val managementSections = listOf(
    DashboardManagementSection("Skills", "/api/skills"),
    DashboardManagementSection("Cron", "/api/cron/jobs"),
    DashboardManagementSection("MCP", "/api/mcp/servers"),
    DashboardManagementSection("Catalog", "/api/mcp/catalog"),
    DashboardManagementSection("Profiles", "/api/profiles"),
    DashboardManagementSection("Models", "/api/model/info"),
    DashboardManagementSection("Config", "/api/config/schema"),
)

private sealed interface DashboardPayloadState {
    data object Idle : DashboardPayloadState
    data object Loading : DashboardPayloadState
    data class Loaded(
        val status: DashboardStatus?,
        val session: DashboardAuthSession?,
        val items: List<DashboardSummaryItem>,
        val rawSummary: String,
    ) : DashboardPayloadState
    data class Error(
        val message: String,
        val status: DashboardStatus? = null,
    ) : DashboardPayloadState
}

private data class DashboardSummaryItem(
    val id: String = "",
    val title: String,
    val subtitle: String? = null,
    val meta: String? = null,
    val profile: String? = null,
    val actions: List<DashboardItemAction> = emptyList(),
)

private data class DashboardItemAction(
    val label: String,
    val kind: DashboardActionKind,
    val destructive: Boolean = false,
)

private enum class DashboardActionKind {
    EnableSkill,
    DisableSkill,
    ViewCronRuns,
    PauseCron,
    ResumeCron,
    TriggerCron,
    DeleteCron,
    EnableMcp,
    DisableMcp,
    TestMcp,
    RemoveMcp,
    InstallMcpCatalog,
    ViewProfileSoul,
    ActivateProfile,
    DeleteProfile,
}

private data class DashboardDetailResult(
    val title: String,
    val body: String,
)

private data class PendingDashboardAction(
    val item: DashboardSummaryItem,
    val action: DashboardItemAction,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardManagementScreen(
    connectionViewModel: ConnectionViewModel,
    onNavigateToConnections: () -> Unit,
    onNavigateToChat: () -> Unit = {},
    onNavigateToBridge: () -> Unit = {},
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val dashboardUrl by connectionViewModel.effectiveDashboardUrl.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var showingDetail by remember { mutableStateOf(false) }
    var reloadNonce by remember { mutableStateOf(0) }
    val payloadStates = remember { mutableStateMapOf<String, DashboardPayloadState>() }
    val refreshingPayloads = remember { mutableStateMapOf<String, Boolean>() }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var actionInFlight by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PendingDashboardAction?>(null) }
    var detailResult by remember { mutableStateOf<DashboardDetailResult?>(null) }
    var oauthProvider by remember { mutableStateOf<DashboardAuthProvider?>(null) }
    var confirmClearDashboardSession by remember { mutableStateOf(false) }

    val section = managementSections[selectedTab]
    val connectionId = activeConnection?.id ?: "default"
    val payloadKey = remember(connectionId, dashboardUrl, section.path) {
        "$connectionId|$dashboardUrl|${section.path}"
    }
    val payloadState = payloadStates[payloadKey] ?: DashboardPayloadState.Idle
    val isRefreshing = refreshingPayloads[payloadKey] == true
    val dashboardStatus = when (val state = payloadState) {
        is DashboardPayloadState.Loaded -> state.status
        is DashboardPayloadState.Error -> state.status
        else -> null
    }
    val dashboardSession = when (val state = payloadState) {
        is DashboardPayloadState.Loaded -> state.session
        else -> null
    }
    val dashboardAuthenticated = when (val state = payloadState) {
        is DashboardPayloadState.Loaded -> state.session?.authenticated
        is DashboardPayloadState.Error -> false
        else -> null
    }
    val cookieStoreFactory = remember(context, connectionId) {
        {
            EncryptedDashboardCookieStore(
                context = context,
                connectionId = connectionId,
            )
        }
    }
    val clientFactory = remember(dashboardUrl, cookieStoreFactory) {
        {
            DashboardApiClient(
                baseUrl = dashboardUrl,
                okHttpClient = DashboardApiClient.defaultClient(
                    cookieStore = cookieStoreFactory(),
                ),
            )
        }
    }

    fun runAction(item: DashboardSummaryItem, action: DashboardItemAction) {
        if (dashboardUrl.isBlank() || actionInFlight) return
        val actionPayloadKey = payloadKey
        actionInFlight = true
        actionMessage = null
        scope.launch {
            val result = try {
                withDashboardClient(clientFactory) { client ->
                    client.runDashboardAction(item, action)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
            actionMessage = result.fold(
                onSuccess = { root ->
                    if (action.kind.isDetailAction) {
                        detailResult = DashboardDetailResult(
                            title = "${item.title} · ${action.label}",
                            body = detailBodyFor(action.kind, root),
                        )
                        null
                    } else {
                        val loaded = payloadStates[actionPayloadKey] as? DashboardPayloadState.Loaded
                        loaded?.optimisticAfter(item, action)?.let { next ->
                            payloadStates[actionPayloadKey] = next
                        }
                        refreshingPayloads[actionPayloadKey] = true
                        reloadNonce += 1
                        "${action.label} completed"
                    }
                },
                onFailure = { err -> err.message ?: "${action.label} failed" },
            )
            actionInFlight = false
        }
    }

    LaunchedEffect(dashboardUrl, selectedTab, reloadNonce, activeConnection?.id) {
        if (dashboardUrl.isBlank()) {
            payloadStates[payloadKey] = DashboardPayloadState.Error("No dashboard URL is configured for this connection.")
            return@LaunchedEffect
        }
        val previousState = payloadStates[payloadKey]
        if (previousState is DashboardPayloadState.Loaded) {
            refreshingPayloads[payloadKey] = true
        } else {
            payloadStates[payloadKey] = DashboardPayloadState.Loading
        }
        try {
            val nextState = withDashboardClient(clientFactory) { client ->
                val probedStatus = client.getStatus().getOrNull()
                val providerDetails = if (probedStatus?.authRequired == true) {
                    client.getAuthProviders().getOrNull().orEmpty()
                } else {
                    emptyList()
                }
                val status = if (probedStatus != null && providerDetails.isNotEmpty()) {
                    probedStatus.copy(
                        authProviders = providerDetails.map { it.name },
                        authProviderDetails = providerDetails,
                    )
                } else {
                    probedStatus
                }
                val session = if (status?.authRequired == true) {
                    client.currentSession().getOrNull()
                } else {
                    null
                }
                val gatewayTicketAvailable = if (session?.authenticated == true) {
                    client.requestWsTicket().isSuccess
                } else {
                    null
                }
                connectionViewModel.recordDashboardStatus(
                    status = status,
                    session = session,
                    reachable = status != null,
                    gatewayTicketAvailable = gatewayTicketAvailable,
                )
                if (status?.authRequired == true && session?.authenticated != true) {
                    DashboardPayloadState.Error("Dashboard sign-in required", status = status)
                } else {
                    val result = client.getJsonElement(section.path)
                    result.fold(
                        onSuccess = { root ->
                            DashboardPayloadState.Loaded(
                                status = status,
                                session = session,
                                items = summarize(section, root),
                                rawSummary = summarizeRoot(root),
                            )
                        },
                        onFailure = { err ->
                            DashboardPayloadState.Error(
                                message = err.message ?: "Dashboard request failed",
                                status = status,
                            )
                        },
                    )
                }
            }
            val latestState = payloadStates[payloadKey]
            if (
                nextState is DashboardPayloadState.Error &&
                latestState is DashboardPayloadState.Loaded &&
                nextState.status?.authRequired != true
            ) {
                actionMessage = nextState.message
            } else {
                payloadStates[payloadKey] = nextState
            }
        } catch (e: Exception) {
            payloadStates[payloadKey] = DashboardPayloadState.Error(
                message = e.message ?: "Dashboard request failed",
            )
        } finally {
            refreshingPayloads[payloadKey] = false
        }
    }

    fun submitDashboardSignIn(provider: String, username: String, password: String) {
        if (dashboardUrl.isBlank() || actionInFlight) return
        actionInFlight = true
        actionMessage = null
        scope.launch {
            val result = try {
                withDashboardClient(clientFactory) { client ->
                    client.loginPassword(
                        provider = provider,
                        username = username,
                        password = password,
                    ).mapCatching {
                        val status = client.getStatus().getOrNull()
                        val session = client.currentSession().getOrNull()
                        val gatewayTicketAvailable = if (session?.authenticated == true) {
                            client.requestWsTicket().isSuccess
                        } else {
                            null
                        }
                        connectionViewModel.recordDashboardStatus(
                            status = status,
                            session = session,
                            reachable = status != null,
                            gatewayTicketAvailable = gatewayTicketAvailable,
                        )
                        if (session?.authenticated != true) {
                            throw IllegalStateException(
                                "Sign-in completed, but the dashboard did not return an authenticated session.",
                            )
                        }
                        it
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
            actionMessage = result.fold(
                onSuccess = {
                    reloadNonce += 1
                    "Dashboard signed in"
                },
                onFailure = { err -> err.message ?: "Dashboard sign-in failed" },
            )
            actionInFlight = false
        }
    }

    pendingAction?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("${pending.action.label} ${pending.item.title}?") },
            text = {
                Text(
                    text = "This changes server-side dashboard state for ${pending.item.title}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        runAction(pending.item, pending.action)
                    },
                ) { Text(pending.action.label) }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    detailResult?.let { detail ->
        DashboardDetailDialog(
            detail = detail,
            onDismiss = { detailResult = null },
        )
    }

    if (confirmClearDashboardSession) {
        AlertDialog(
            onDismissRequest = { confirmClearDashboardSession = false },
            title = { Text("Clear dashboard session?") },
            text = {
                Text(
                    text = "This signs this connection out of the Hermes dashboard on this device. Your API key, saved connection, and Relay pairing stay unchanged.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearDashboardSession = false
                        connectionViewModel.clearDashboardSession {
                            actionMessage = "Dashboard session cleared"
                            reloadNonce += 1
                        }
                    },
                ) {
                    Text("Clear session")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearDashboardSession = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    oauthProvider?.let { provider ->
        DashboardOAuthSignInDialog(
            dashboardUrl = dashboardUrl,
            provider = provider,
            cookieStoreFactory = cookieStoreFactory,
            onDismiss = { oauthProvider = null },
            onAuthenticated = { session ->
                oauthProvider = null
                actionMessage = "Signed in${session.provider?.let { " with $it" }.orEmpty()}"
                reloadNonce += 1
            },
            onError = { message ->
                actionMessage = message
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage") },
                actions = {
                    RelayChromeIconButton(
                        icon = Icons.Filled.Code,
                        contentDescription = "Terminal",
                        onClick = onNavigateToTerminal,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    RelayChromeIconButton(
                        icon = Icons.Filled.Tune,
                        contentDescription = "Settings",
                        onClick = onNavigateToSettings,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    IconButton(
                        onClick = { reloadNonce += 1 },
                        enabled = !isRefreshing,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = RelayRefresh.Background.copy(alpha = 0.96f),
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(RelayRefresh.Background)
                .relayGridTexture(alpha = 0.12f)
        ) {
            RelayModeStrip(
                selected = RelayPrimaryMode.Manage,
                onModeSelected = { mode ->
                    when (mode) {
                        RelayPrimaryMode.Chat -> onNavigateToChat()
                        RelayPrimaryMode.Manage -> Unit
                        RelayPrimaryMode.Bridge -> onNavigateToBridge()
                    }
                },
            )
            val loadedCount = (payloadState as? DashboardPayloadState.Loaded)?.items?.size ?: 0
            AnimatedContent(
                targetState = showingDetail,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    if (targetState) {
                        (
                            slideInVertically(animationSpec = tween(220)) { it / 3 } +
                                fadeIn(animationSpec = tween(160))
                        ) togetherWith (
                            slideOutVertically(animationSpec = tween(220)) { -it / 2 } +
                                fadeOut(animationSpec = tween(120))
                        )
                    } else {
                        (
                            slideInVertically(animationSpec = tween(220)) { -it / 3 } +
                                fadeIn(animationSpec = tween(160))
                        ) togetherWith (
                            slideOutVertically(animationSpec = tween(180)) { it / 2 } +
                                fadeOut(animationSpec = tween(120))
                        )
                    }
                },
                label = "manage-content-mode",
            ) { detailMode ->
                if (detailMode) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        ManageSelectedSectionHeader(
                            section = section,
                            onBackToOverview = { showingDetail = false },
                        )
                        PrimaryScrollableTabRow(selectedTabIndex = selectedTab) {
                            managementSections.forEachIndexed { index, tab ->
                                Tab(
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index },
                                    text = { Text(tab.label) },
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                        if (isRefreshing && payloadState !is DashboardPayloadState.Loading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            when (val state = payloadState) {
                                DashboardPayloadState.Idle,
                                DashboardPayloadState.Loading -> LoadingBody(section.label)
                                is DashboardPayloadState.Error -> ErrorBody(
                                    message = state.message,
                                    status = state.status,
                                    dashboardUrl = dashboardUrl,
                                    actionInFlight = actionInFlight,
                                    actionMessage = actionMessage,
                                    onRetry = { reloadNonce += 1 },
                                    onSignIn = ::submitDashboardSignIn,
                                    onOAuthSignIn = { provider -> oauthProvider = provider },
                                )
                                is DashboardPayloadState.Loaded -> LoadedBody(
                                    section = section,
                                    state = state,
                                    actionInFlight = actionInFlight,
                                    actionMessage = actionMessage,
                                    onAction = { item, action ->
                                        if (action.destructive) {
                                            pendingAction = PendingDashboardAction(item, action)
                                        } else {
                                            runAction(item, action)
                                        }
                                    },
                                )
                            }
                        }
                    }
                } else {
                    ManageOverviewBody(
                        loadedCount = loadedCount,
                        section = section,
                        payloadState = payloadState,
                        dashboardUrl = dashboardUrl,
                        status = dashboardStatus,
                        session = dashboardSession,
                        authenticated = dashboardAuthenticated,
                        lastCheckedAtMillis = activeConnection?.dashboardLastStatus?.checkedAtMillis,
                        onClearSession = { confirmClearDashboardSession = true },
                        onNavigateToConnections = onNavigateToConnections,
                        onSelectSection = { label ->
                            managementSections.indexOfFirst { it.label == label }
                                .takeIf { it >= 0 }
                                ?.let {
                                    selectedTab = it
                                    showingDetail = true
                                }
                        },
                    )
                }
            }
        }
    }
}

private data class ManageTileSpec(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
)

private fun manageTileSpec(section: DashboardManagementSection): ManageTileSpec = when (section.label) {
    "Profiles" -> ManageTileSpec(
        icon = Icons.Filled.Person,
        title = "Profiles",
        subtitle = "SOUL, memory, skills, sessions",
    )
    "Skills" -> ManageTileSpec(
        icon = Icons.Filled.AutoAwesome,
        title = "Skills + Tools",
        subtitle = "Browse, enable, configure",
    )
    "Cron" -> ManageTileSpec(
        icon = Icons.Filled.Schedule,
        title = "Automations",
        subtitle = "Cron, background runs, delivery",
    )
    "MCP" -> ManageTileSpec(
        icon = Icons.Filled.Code,
        title = "MCP Servers",
        subtitle = "Servers, status, tools",
    )
    "Catalog" -> ManageTileSpec(
        icon = Icons.Filled.AutoAwesome,
        title = "Catalog",
        subtitle = "Discover upstream servers",
    )
    "Models" -> ManageTileSpec(
        icon = Icons.Filled.Tune,
        title = "Models",
        subtitle = "Provider and model state",
    )
    "Config" -> ManageTileSpec(
        icon = Icons.Filled.Tune,
        title = "Config",
        subtitle = "Schema and runtime options",
    )
    else -> ManageTileSpec(
        icon = Icons.Filled.Link,
        title = section.label,
        subtitle = section.path,
    )
}

@Composable
private fun ManageOverviewBody(
    loadedCount: Int,
    section: DashboardManagementSection,
    payloadState: DashboardPayloadState,
    dashboardUrl: String,
    status: DashboardStatus?,
    session: DashboardAuthSession?,
    authenticated: Boolean?,
    lastCheckedAtMillis: Long?,
    onClearSession: () -> Unit,
    onNavigateToConnections: () -> Unit,
    onSelectSection: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            RelaySectionCaption(
                title = "Relay Hub",
                meta = "overview",
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RelayMetricCard(
                    value = if (loadedCount > 0) loadedCount.toString() else "-",
                    label = section.label.lowercase(),
                    modifier = Modifier.weight(1f),
                )
                RelayMetricCard(
                    value = when (payloadState) {
                        is DashboardPayloadState.Loaded -> "ok"
                        is DashboardPayloadState.Loading -> "..."
                        is DashboardPayloadState.Error -> "!"
                        DashboardPayloadState.Idle -> "-"
                    },
                    label = "dashboard",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            DashboardConnectionHeader(
                dashboardUrl = dashboardUrl,
                status = status,
                session = session,
                authenticated = authenticated,
                lastCheckedAtMillis = lastCheckedAtMillis,
                onClearSession = onClearSession,
                onNavigateToConnections = onNavigateToConnections,
            )
        }
        item {
            RelayNavTile(
                icon = Icons.Filled.Link,
                title = "Connections",
                subtitle = "Pair, switch, verify routes",
                onClick = onNavigateToConnections,
            )
        }
        item {
            RelayNavTile(
                icon = Icons.Filled.Person,
                title = "Profiles",
                subtitle = "SOUL, memory, skills, sessions",
                onClick = { onSelectSection("Profiles") },
            )
        }
        item {
            RelayNavTile(
                icon = Icons.Filled.AutoAwesome,
                title = "Skills + Tools",
                subtitle = "Browse, enable, configure",
                onClick = { onSelectSection("Skills") },
            )
        }
        item {
            RelayNavTile(
                icon = Icons.Filled.Schedule,
                title = "Automations",
                subtitle = "Cron, background runs, delivery",
                onClick = { onSelectSection("Cron") },
            )
        }
        item {
            RelayNavTile(
                icon = Icons.Filled.Code,
                title = "MCP Servers",
                subtitle = "Servers, status, tools",
                onClick = { onSelectSection("MCP") },
            )
        }
        item {
            RelayNavTile(
                icon = Icons.Filled.AutoAwesome,
                title = "Catalog",
                subtitle = "Discover upstream servers",
                onClick = { onSelectSection("Catalog") },
            )
        }
    }
}

@Composable
private fun ManageSelectedSectionHeader(
    section: DashboardManagementSection,
    onBackToOverview: () -> Unit,
) {
    val spec = manageTileSpec(section)
    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RelayReturnStrip(
            icon = spec.icon,
            title = spec.title,
            subtitle = spec.subtitle,
            onClick = onBackToOverview,
            label = "Overview",
        )
    }
}

@Composable
private fun DashboardConnectionHeader(
    dashboardUrl: String,
    status: DashboardStatus?,
    session: DashboardAuthSession?,
    authenticated: Boolean?,
    lastCheckedAtMillis: Long?,
    onClearSession: () -> Unit,
    onNavigateToConnections: () -> Unit,
) {
    val authLabel = when {
        status == null -> "Checking dashboard"
        status.authRequired && authenticated == true -> "Dashboard signed in"
        status.authRequired -> "Dashboard sign-in required"
        else -> "Dashboard available"
    }
    val identity = if (authenticated == true) {
        session?.username ?: session?.provider?.let { "Provider: $it" }
    } else {
        null
    }
    val secondary = listOfNotNull(
        identity,
        dashboardUrl.ifBlank { "No dashboard URL" },
        lastCheckedAtMillis?.let { "Checked ${formatDashboardCheckedAt(it)}" },
    ).joinToString(" · ")
    val bannerText = listOf(authLabel, secondary)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
    val statusColor = when {
        status == null -> RelayRefresh.Amber
        status.authRequired && authenticated != true -> RelayRefresh.Danger
        else -> RelayRefresh.Green
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .relayPanel(background = RelayRefresh.Background.copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(statusColor, CircleShape),
        )
        Text(
            text = bannerText,
            style = relayMetadataStyle(),
            color = if (authenticated == false && status?.authRequired == true) {
                RelayRefresh.Danger
            } else {
                RelayRefresh.Muted
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (authenticated == true) {
            TextButton(
                onClick = onClearSession,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = RelayRefresh.Relay,
                ),
            ) {
                Text("Sign out")
            }
        }
        TextButton(
            onClick = onNavigateToConnections,
            modifier = Modifier.height(30.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            colors = ButtonDefaults.textButtonColors(
                contentColor = RelayRefresh.Relay,
            ),
        ) {
            Text("Connection")
        }
    }
}

private fun formatDashboardCheckedAt(checkedAtMillis: Long): String {
    val deltaMs = System.currentTimeMillis() - checkedAtMillis
    return when {
        deltaMs in 0 until 60_000L -> "just now"
        deltaMs in 60_000L until 3_600_000L -> "${deltaMs / 60_000L}m ago"
        deltaMs in 3_600_000L until 86_400_000L -> "${deltaMs / 3_600_000L}h ago"
        else -> DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
        ).format(Date(checkedAtMillis))
    }
}

private suspend fun <T> withDashboardClient(
    clientFactory: () -> DashboardApiClient,
    block: suspend (DashboardApiClient) -> T,
): T {
    val client = withContext(Dispatchers.IO) { clientFactory() }
    return try {
        block(client)
    } finally {
        withContext(Dispatchers.IO) { client.shutdown() }
    }
}

@Composable
private fun LoadingBody(sectionLabel: String) {
    val sectionName = sectionLabel.lowercase()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Loading $sectionName",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Manage is ready. Dashboard data is still coming in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        repeat(3) { index ->
            LoadingSummaryPlaceholder(index)
        }
    }
}

@Composable
private fun LoadingSummaryPlaceholder(index: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when (index) {
                    0 -> "Preparing summary"
                    1 -> "Checking dashboard session"
                    else -> "Reading server state"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            )
        }
    }
}

@Composable
private fun ErrorBody(
    message: String,
    status: DashboardStatus?,
    dashboardUrl: String,
    actionInFlight: Boolean,
    actionMessage: String?,
    onRetry: () -> Unit,
    onSignIn: (String, String, String) -> Unit,
    onOAuthSignIn: (DashboardAuthProvider) -> Unit,
) {
    val signInRequired = message.contains("401") ||
        message.contains("403") ||
        message.contains("sign-in", ignoreCase = true)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (signInRequired) {
            DashboardSignInCard(
                dashboardUrl = dashboardUrl,
                providers = status?.authProviderDetails.orEmpty(),
                actionInFlight = actionInFlight,
                actionMessage = actionMessage,
                onSignIn = onSignIn,
                onOAuthSignIn = onOAuthSignIn,
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Dashboard unavailable",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Button(onClick = onRetry) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadedBody(
    section: DashboardManagementSection,
    state: DashboardPayloadState.Loaded,
    actionInFlight: Boolean,
    actionMessage: String?,
    onAction: (DashboardSummaryItem, DashboardItemAction) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        actionMessage?.let { message ->
            item {
                ActionMessageCard(message)
            }
        }
        if (state.items.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "No ${section.label.lowercase()} returned",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = state.rawSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        } else {
            items(state.items) { item ->
                DashboardSummaryCard(
                    item = item,
                    actionInFlight = actionInFlight,
                    onAction = { action -> onAction(item, action) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardSummaryCard(
    item: DashboardSummaryItem,
    actionInFlight: Boolean,
    onAction: (DashboardItemAction) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.meta?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (item.actions.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item.actions.forEach { action ->
                        OutlinedButton(
                            onClick = { onAction(action) },
                            enabled = !actionInFlight,
                        ) {
                            Text(action.label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardSignInCard(
    dashboardUrl: String,
    providers: List<DashboardAuthProvider>,
    actionInFlight: Boolean,
    actionMessage: String?,
    onSignIn: (String, String, String) -> Unit,
    onOAuthSignIn: (DashboardAuthProvider) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val passwordProvider = providers.firstOrNull { it.supportsPassword }
    val redirectProviders = providers.filter { it.isRedirectProvider }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Dashboard sign-in required",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Manage uses the Hermes dashboard session at $dashboardUrl.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            redirectProviders.forEach { provider ->
                Button(
                    onClick = { onOAuthSignIn(provider) },
                    enabled = !actionInFlight,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sign in with ${provider.displayName ?: provider.name}")
                }
            }

            if (passwordProvider != null || providers.isEmpty()) {
                if (redirectProviders.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))
                }
                Text(
                    text = passwordProvider?.displayName ?: "Username & Password",
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Button(
                    onClick = { onSignIn(passwordProvider?.name ?: "basic", username, password) },
                    enabled = !actionInFlight && username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (actionInFlight) "Signing in..." else "Sign in")
                }
            } else if (redirectProviders.isEmpty()) {
                Text(
                    text = "This dashboard did not advertise a supported Android sign-in provider.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            actionMessage?.let { message ->
                val isError = message.contains("failed", ignoreCase = true) ||
                    message.contains("not accepted", ignoreCase = true) ||
                    message.contains("not return", ignoreCase = true)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun DashboardOAuthSignInDialog(
    dashboardUrl: String,
    provider: DashboardAuthProvider,
    cookieStoreFactory: () -> EncryptedDashboardCookieStore,
    onDismiss: () -> Unit,
    onAuthenticated: (DashboardAuthSession) -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf("Complete sign-in in the secure dashboard page.") }
    var checking by remember { mutableStateOf(false) }
    val loginUrl = remember(dashboardUrl, provider.name) {
        DashboardApiClient.authLoginUrl(
            baseUrl = dashboardUrl,
            provider = provider.name,
            next = DashboardApiClient.authLandingPath(dashboardUrl),
        )
    }

    fun maybeImportAndVerify(url: String?) {
        val loadedUrl = url?.takeIf { it.isNotBlank() } ?: return
        if (!isDashboardReturnUrl(dashboardUrl, loadedUrl) || isDashboardAuthFlowUrl(dashboardUrl, loadedUrl)) {
            return
        }
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        val imported = importDashboardCookieHeader(
            store = cookieStoreFactory(),
            url = loadedUrl,
            cookieHeader = cookieManager.getCookie(loadedUrl),
        )
        if (checking || imported == 0) return

        checking = true
        statusText = "Verifying dashboard session..."
        scope.launch {
            try {
                val session = withDashboardClient(
                    clientFactory = {
                        DashboardApiClient(
                            baseUrl = dashboardUrl,
                            okHttpClient = DashboardApiClient.defaultClient(
                                cookieStore = cookieStoreFactory(),
                            ),
                        )
                    },
                ) { client ->
                    client.currentSession().getOrNull()
                }
                if (session?.authenticated == true) {
                    onAuthenticated(session)
                } else {
                    checking = false
                    statusText = "Sign-in was not accepted yet. Finish the dashboard flow to continue."
                }
            } catch (e: Exception) {
                checking = false
                val message = e.message ?: "Dashboard sign-in verification failed"
                statusText = message
                onError(message)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sign in with ${provider.displayName ?: provider.name}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = dashboardUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close sign-in",
                        )
                    }
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { viewContext ->
                        CookieManager.getInstance().setAcceptCookie(true)
                        WebView(viewContext).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean = false

                                override fun onPageFinished(view: WebView, url: String?) {
                                    super.onPageFinished(view, url)
                                    maybeImportAndVerify(url)
                                }
                            }
                            loadUrl(loginUrl)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionMessageCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun DashboardDetailDialog(
    detail: DashboardDetailResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(detail.title) },
        text = {
            Text(
                text = detail.body,
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

private fun isDashboardReturnUrl(dashboardUrl: String, loadedUrl: String): Boolean {
    val root = dashboardUrl.trim().trimEnd('/')
    return root.isNotBlank() && loadedUrl.trim().startsWith(root, ignoreCase = true)
}

private fun isDashboardAuthFlowUrl(dashboardUrl: String, loadedUrl: String): Boolean {
    val root = dashboardUrl.trim().trimEnd('/')
    val relative = loadedUrl.trim().removePrefix(root)
    return relative.startsWith("/login", ignoreCase = true) ||
        relative.startsWith("/auth/login", ignoreCase = true) ||
        relative.startsWith("/auth/callback", ignoreCase = true)
}

private fun summarize(
    section: DashboardManagementSection,
    root: JsonElement,
): List<DashboardSummaryItem> {
    return when (section.label) {
        "Skills" -> root.arrayItems("skills", "items")
            ?.mapIndexed { index, item -> summarizeObjectItem(item, "Skill ${index + 1}") }
            ?: emptyList()
        "Cron" -> root.arrayItems("jobs", "items")
            ?.mapIndexed { index, item -> summarizeObjectItem(item, "Job ${index + 1}") }
            ?: emptyList()
        "MCP" -> root.arrayItems("servers", "items")
            ?.mapIndexed { index, item -> summarizeObjectItem(item, "Server ${index + 1}") }
            ?: emptyList()
        "Catalog" -> root.arrayItems("entries", "catalog", "items")
            ?.mapIndexed { index, item -> summarizeObjectItem(item, "Catalog ${index + 1}") }
            ?: emptyList()
        "Profiles" -> {
            root.arrayItems("profiles", "items")
                ?.mapIndexed { index, item -> summarizeObjectItem(item, "Profile ${index + 1}") }
                ?: ((root as? JsonObject)?.get("profiles") as? JsonObject)
                    ?.entries
                    ?.map { (name, value) -> summarizeObjectItem(value, name) }
                ?: (root as? JsonObject)
                    ?.entries
                    ?.map { (name, value) -> summarizeObjectItem(value, name) }
                ?: listOf(summarizeObjectItem(root, "Profile"))
        }
        "Models" -> summarizeKeyValueOrList(root, "Model")
        "Config" -> summarizeKeyValueOrList(root, "Config")
        else -> emptyList()
    }
}

private fun summarizeObjectItem(
    element: JsonElement,
    fallbackTitle: String,
): DashboardSummaryItem {
    val obj = element as? JsonObject
    if (obj == null) {
        return DashboardSummaryItem(
            id = fallbackTitle,
            title = element.shortDisplay().ifBlank { fallbackTitle },
            meta = element.typeLabel(),
        )
    }

    val title = obj.stringField("name")
        ?: obj.stringField("id")
        ?: obj.stringField("title")
        ?: fallbackTitle
    val id = obj.stringField("id")
        ?: obj.stringField("name")
        ?: title
    val subtitle = obj.stringField("description")
        ?: obj.stringField("summary")
        ?: obj.stringField("command")
        ?: obj.stringField("path")
        ?: obj.stringField("model")
    val enabled = obj.booleanField("enabled")
    val status = obj.stringField("status") ?: obj.stringField("state")
    val meta = listOfNotNull(
        enabled?.let { if (it) "enabled" else "disabled" },
        obj.booleanField("installed")?.let { if (it) "installed" else "not installed" },
        obj.booleanField("needs_install")?.let { if (it) "bootstrap install" else null },
        status,
        obj.stringField("provider"),
        obj.stringField("transport"),
        obj.stringField("auth_type"),
        obj.stringField("category"),
        obj.intField("skill_count")?.let { "$it skills" },
        requiredEnvCount(obj).takeIf { it > 0 }?.let { "$it credential${if (it == 1) "" else "s"} required" },
    ).joinToString(" · ").takeIf { it.isNotBlank() }

    return DashboardSummaryItem(
        id = id,
        title = title,
        subtitle = subtitle,
        meta = meta,
        profile = obj.stringField("profile"),
        actions = dashboardActionsFor(obj),
    )
}

private fun dashboardActionsFor(obj: JsonObject): List<DashboardItemAction> {
    val hasSchedule = obj["schedule"] != null || obj["cron"] != null || obj.stringField("next_run") != null
    val hasTransport = obj.stringField("transport") != null ||
        obj.stringField("command") != null ||
        obj.stringField("url") != null
    val isCatalogEntry = obj["required_env"] != null ||
        obj.booleanField("installed") != null && obj.stringField("source") != null
    val isProfile = obj.booleanField("is_default") != null ||
        obj.intField("skill_count") != null ||
        obj.booleanField("has_env") != null ||
        obj.booleanField("gateway_running") != null ||
        obj.booleanField("description_auto") != null
    val hasSkillUsage = obj["usage"] != null || obj.stringField("category") != null
    val enabled = obj.booleanField("enabled")
    val paused = obj.booleanField("paused")

    return when {
        isCatalogEntry -> buildList {
            if (obj.booleanField("installed") != true && requiredEnvCount(obj) == 0) {
                add(DashboardItemAction("Install", DashboardActionKind.InstallMcpCatalog))
            }
        }
        hasSchedule -> buildList {
            add(DashboardItemAction("Runs", DashboardActionKind.ViewCronRuns))
            if (paused == true) {
                add(DashboardItemAction("Resume", DashboardActionKind.ResumeCron))
            } else {
                add(DashboardItemAction("Pause", DashboardActionKind.PauseCron))
            }
            add(DashboardItemAction("Run now", DashboardActionKind.TriggerCron))
            add(DashboardItemAction("Delete", DashboardActionKind.DeleteCron, destructive = true))
        }
        hasTransport -> buildList {
            if (enabled == false) {
                add(DashboardItemAction("Enable", DashboardActionKind.EnableMcp))
            } else {
                add(DashboardItemAction("Disable", DashboardActionKind.DisableMcp))
            }
            add(DashboardItemAction("Test", DashboardActionKind.TestMcp))
            add(DashboardItemAction("Remove", DashboardActionKind.RemoveMcp, destructive = true))
        }
        isProfile -> buildList {
            add(DashboardItemAction("SOUL", DashboardActionKind.ViewProfileSoul))
            val name = obj.stringField("name").orEmpty()
            if (name.isNotBlank()) {
                add(DashboardItemAction("Use", DashboardActionKind.ActivateProfile))
                if (!name.equals("default", ignoreCase = true)) {
                    add(DashboardItemAction("Delete", DashboardActionKind.DeleteProfile, destructive = true))
                }
            }
        }
        hasSkillUsage || enabled != null -> listOf(
            if (enabled == false) {
                DashboardItemAction("Enable", DashboardActionKind.EnableSkill)
            } else {
                DashboardItemAction("Disable", DashboardActionKind.DisableSkill)
            },
        )
        else -> {
            val name = obj.stringField("name").orEmpty()
            if (name.isNotBlank()) {
                buildList {
                    add(DashboardItemAction("Use", DashboardActionKind.ActivateProfile))
                    if (!name.equals("default", ignoreCase = true)) {
                        add(DashboardItemAction("Delete", DashboardActionKind.DeleteProfile, destructive = true))
                    }
                }
            } else {
                emptyList()
            }
        }
    }
}

private fun summarizeRoot(root: JsonElement): String {
    return when (root) {
        is JsonObject -> {
            val keys = root.keys.take(8).joinToString(", ")
            if (keys.isBlank()) "{ }" else "Fields: $keys"
        }
        is JsonArray -> "${root.size} item${if (root.size == 1) "" else "s"}"
        is JsonPrimitive -> root.contentOrNull ?: root.toString()
    }
}

private fun DashboardPayloadState.Loaded.optimisticAfter(
    item: DashboardSummaryItem,
    action: DashboardItemAction,
): DashboardPayloadState.Loaded {
    val nextItems = items.mapNotNull { existing ->
        if (existing.id != item.id || existing.title != item.title) {
            existing
        } else {
            existing.optimisticAfter(action)
        }
    }
    return copy(items = nextItems)
}

private fun DashboardSummaryItem.optimisticAfter(action: DashboardItemAction): DashboardSummaryItem? {
    return when (action.kind) {
        DashboardActionKind.EnableSkill -> withEnabledMeta(true).withActionSwap(
            from = DashboardActionKind.EnableSkill,
            to = DashboardItemAction("Disable", DashboardActionKind.DisableSkill),
        )
        DashboardActionKind.DisableSkill -> withEnabledMeta(false).withActionSwap(
            from = DashboardActionKind.DisableSkill,
            to = DashboardItemAction("Enable", DashboardActionKind.EnableSkill),
        )
        DashboardActionKind.EnableMcp -> withEnabledMeta(true).withActionSwap(
            from = DashboardActionKind.EnableMcp,
            to = DashboardItemAction("Disable", DashboardActionKind.DisableMcp),
        )
        DashboardActionKind.DisableMcp -> withEnabledMeta(false).withActionSwap(
            from = DashboardActionKind.DisableMcp,
            to = DashboardItemAction("Enable", DashboardActionKind.EnableMcp),
        )
        DashboardActionKind.PauseCron -> withActionSwap(
            from = DashboardActionKind.PauseCron,
            to = DashboardItemAction("Resume", DashboardActionKind.ResumeCron),
        )
        DashboardActionKind.ResumeCron -> withActionSwap(
            from = DashboardActionKind.ResumeCron,
            to = DashboardItemAction("Pause", DashboardActionKind.PauseCron),
        )
        DashboardActionKind.DeleteCron,
        DashboardActionKind.RemoveMcp,
        DashboardActionKind.DeleteProfile -> null
        DashboardActionKind.InstallMcpCatalog -> copy(
            meta = appendMeta(meta, "installed"),
            actions = emptyList(),
        )
        DashboardActionKind.ViewCronRuns,
        DashboardActionKind.TriggerCron,
        DashboardActionKind.TestMcp,
        DashboardActionKind.ViewProfileSoul,
        DashboardActionKind.ActivateProfile -> this
    }
}

private fun DashboardSummaryItem.withActionSwap(
    from: DashboardActionKind,
    to: DashboardItemAction,
): DashboardSummaryItem = copy(
    actions = actions.map { action ->
        if (action.kind == from) to else action
    },
)

private fun DashboardSummaryItem.withEnabledMeta(enabled: Boolean): DashboardSummaryItem {
    val enabledText = if (enabled) "enabled" else "disabled"
    val parts = meta
        ?.split(" · ")
        ?.filterNot { it == "enabled" || it == "disabled" }
        .orEmpty()
    return copy(meta = listOf(enabledText).plus(parts).joinToString(" · "))
}

private fun appendMeta(meta: String?, value: String): String {
    val parts = meta?.split(" · ").orEmpty()
    return if (parts.any { it.equals(value, ignoreCase = true) }) {
        meta.orEmpty()
    } else {
        parts.plus(value).filter { it.isNotBlank() }.joinToString(" · ")
    }
}

private fun summarizeKeyValueOrList(
    root: JsonElement,
    fallbackTitle: String,
): List<DashboardSummaryItem> {
    return when (root) {
        is JsonObject -> root.entries.map { (name, value) ->
            DashboardSummaryItem(
                id = name,
                title = name,
                subtitle = value.shortDisplay(),
                meta = value.typeLabel(),
            )
        }
        is JsonArray -> root.mapIndexed { index, item ->
            summarizeObjectItem(item, "$fallbackTitle ${index + 1}")
        }
        else -> listOf(summarizeObjectItem(root, fallbackTitle))
    }
}

private fun JsonElement.arrayItems(vararg names: String): JsonArray? {
    return when (this) {
        is JsonArray -> this
        is JsonObject -> arrayField(*names)
        else -> null
    }
}

private fun JsonObject.arrayField(vararg names: String): JsonArray? {
    for (name in names) {
        val value = this[name] as? JsonArray
        if (value != null) return value
    }
    return null
}

private fun JsonObject.stringField(name: String): String? =
    ((this[name] as? JsonPrimitive)?.contentOrNull)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

private fun JsonObject.booleanField(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.intField(name: String): Int? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

private fun requiredEnvCount(obj: JsonObject): Int =
    (obj["required_env"] as? JsonArray)
        ?.count { env ->
            (env as? JsonObject)?.booleanField("required") != false
        }
        ?: 0

private fun JsonElement.shortDisplay(): String {
    return when (this) {
        is JsonPrimitive -> contentOrNull ?: toString()
        is JsonArray -> "${size} item${if (size == 1) "" else "s"}"
        is JsonObject -> "${size} field${if (size == 1) "" else "s"}"
    }.take(180)
}

private fun JsonElement.typeLabel(): String =
    when (this) {
        is JsonPrimitive -> "value"
        is JsonArray -> "list"
        is JsonObject -> "object"
    }

private suspend fun DashboardApiClient.runDashboardAction(
    item: DashboardSummaryItem,
    action: DashboardItemAction,
): Result<JsonObject> {
    val id = item.id.ifBlank { item.title }
    return when (action.kind) {
        DashboardActionKind.EnableSkill -> toggleSkill(id, enabled = true)
        DashboardActionKind.DisableSkill -> toggleSkill(id, enabled = false)
        DashboardActionKind.ViewCronRuns -> getCronJobRuns(id, profile = item.profile)
        DashboardActionKind.PauseCron -> pauseCronJob(id, profile = item.profile)
        DashboardActionKind.ResumeCron -> resumeCronJob(id, profile = item.profile)
        DashboardActionKind.TriggerCron -> triggerCronJob(id, profile = item.profile)
        DashboardActionKind.DeleteCron -> deleteCronJob(id, profile = item.profile)
        DashboardActionKind.EnableMcp -> setMcpServerEnabled(id, enabled = true)
        DashboardActionKind.DisableMcp -> setMcpServerEnabled(id, enabled = false)
        DashboardActionKind.TestMcp -> testMcpServer(id)
        DashboardActionKind.RemoveMcp -> removeMcpServer(id)
        DashboardActionKind.InstallMcpCatalog -> installMcpCatalogEntry(id)
        DashboardActionKind.ViewProfileSoul -> getProfileSoul(id)
        DashboardActionKind.ActivateProfile -> setActiveProfile(id)
        DashboardActionKind.DeleteProfile -> deleteProfile(id)
    }
}

private val DashboardActionKind.isDetailAction: Boolean
    get() = this == DashboardActionKind.ViewCronRuns ||
        this == DashboardActionKind.ViewProfileSoul

private fun detailBodyFor(kind: DashboardActionKind, root: JsonObject): String {
    return when (kind) {
        DashboardActionKind.ViewProfileSoul -> {
            val content = root.stringField("content").orEmpty()
            when {
                content.isNotBlank() -> content.take(6_000)
                root.booleanField("exists") == false -> "No SOUL.md exists for this profile yet."
                else -> compactJsonLines(root)
            }
        }
        DashboardActionKind.ViewCronRuns -> {
            val runs = root.arrayField("runs", "items", "sessions")
            if (runs == null || runs.isEmpty()) {
                "No recent runs returned."
            } else {
                runs.take(12).mapIndexed { index, item ->
                    "${index + 1}. ${item.rowDisplay()}"
                }.joinToString("\n")
            }
        }
        else -> compactJsonLines(root)
    }
}

private fun JsonElement.rowDisplay(): String {
    return when (this) {
        is JsonObject -> entries.take(8).joinToString(" · ") { (key, value) ->
            "$key=${value.shortDisplay()}"
        }.ifBlank { "{ }" }
        else -> shortDisplay()
    }
}

private fun compactJsonLines(root: JsonObject): String =
    root.entries.joinToString("\n") { (key, value) ->
        "$key: ${value.shortDisplay()}"
    }.ifBlank { "{ }" }
