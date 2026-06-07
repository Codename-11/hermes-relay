package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.network.EncryptedDashboardCookieStore
import com.hermesandroid.relay.network.DashboardApiClient
import com.hermesandroid.relay.network.DashboardStatus
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

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
        val items: List<DashboardSummaryItem>,
        val rawSummary: String,
    ) : DashboardPayloadState
    data class Error(val message: String) : DashboardPayloadState
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
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val dashboardUrl = activeConnection?.resolvedDashboardUrl.orEmpty()
    var selectedTab by remember { mutableStateOf(0) }
    var reloadNonce by remember { mutableStateOf(0) }
    var payloadState by remember { mutableStateOf<DashboardPayloadState>(DashboardPayloadState.Idle) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var actionInFlight by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PendingDashboardAction?>(null) }
    var detailResult by remember { mutableStateOf<DashboardDetailResult?>(null) }

    val section = managementSections[selectedTab]
    val clientFactory = remember(context, activeConnection?.id, dashboardUrl) {
        {
            DashboardApiClient(
                baseUrl = dashboardUrl,
                okHttpClient = DashboardApiClient.defaultClient(
                    cookieStore = EncryptedDashboardCookieStore(
                        context = context,
                        connectionId = activeConnection?.id ?: "default",
                    ),
                ),
            )
        }
    }

    fun runAction(item: DashboardSummaryItem, action: DashboardItemAction) {
        if (dashboardUrl.isBlank() || actionInFlight) return
        actionInFlight = true
        actionMessage = null
        scope.launch {
            val client = clientFactory()
            val result = try {
                client.runDashboardAction(item, action)
            } finally {
                client.shutdown()
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
            payloadState = DashboardPayloadState.Error("No dashboard URL is configured for this connection.")
            return@LaunchedEffect
        }
        payloadState = DashboardPayloadState.Loading
        val client = clientFactory()
        try {
            val status = client.getStatus().getOrNull()
            val session = if (status?.authRequired == true) {
                client.currentSession().getOrNull()
            } else {
                null
            }
            payloadState = if (status?.authRequired == true && session?.authenticated != true) {
                DashboardPayloadState.Error("Dashboard sign-in required")
            } else {
                val result = client.getJsonObject(section.path)
                result.fold(
                    onSuccess = { root ->
                        DashboardPayloadState.Loaded(
                            status = status,
                            items = summarize(section, root),
                            rawSummary = summarizeRoot(root),
                        )
                    },
                    onFailure = { err ->
                        DashboardPayloadState.Error(err.message ?: "Dashboard request failed")
                    },
                )
            }
        } finally {
            client.shutdown()
        }
    }

    fun submitDashboardSignIn(username: String, password: String) {
        if (dashboardUrl.isBlank() || actionInFlight) return
        actionInFlight = true
        actionMessage = null
        scope.launch {
            val client = clientFactory()
            val result = try {
                client.loginPassword(username = username, password = password)
            } finally {
                client.shutdown()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage") },
                actions = {
                    IconButton(onClick = { reloadNonce += 1 }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PrimaryScrollableTabRow(selectedTabIndex = selectedTab) {
                managementSections.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tab.label) },
                    )
                }
            }
            DashboardConnectionHeader(
                dashboardUrl = dashboardUrl,
                status = (payloadState as? DashboardPayloadState.Loaded)?.status,
                onNavigateToConnections = onNavigateToConnections,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
            when (val state = payloadState) {
                DashboardPayloadState.Idle,
                DashboardPayloadState.Loading -> LoadingBody()
                is DashboardPayloadState.Error -> ErrorBody(
                    message = state.message,
                    dashboardUrl = dashboardUrl,
                    actionInFlight = actionInFlight,
                    actionMessage = actionMessage,
                    onRetry = { reloadNonce += 1 },
                    onSignIn = ::submitDashboardSignIn,
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
}

@Composable
private fun DashboardConnectionHeader(
    dashboardUrl: String,
    status: DashboardStatus?,
    onNavigateToConnections: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dashboardUrl.ifBlank { "No dashboard URL" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val authLabel = when {
                    status == null -> "Checking dashboard"
                    status.authRequired -> "Dashboard sign-in required"
                    else -> "Dashboard available"
                }
                Text(
                    text = authLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onNavigateToConnections) {
                Text("Connection")
            }
        }
    }
}

@Composable
private fun LoadingBody() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Loading dashboard data",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorBody(
    message: String,
    dashboardUrl: String,
    actionInFlight: Boolean,
    actionMessage: String?,
    onRetry: () -> Unit,
    onSignIn: (String, String) -> Unit,
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
                actionInFlight = actionInFlight,
                actionMessage = actionMessage,
                onSignIn = onSignIn,
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
    actionInFlight: Boolean,
    actionMessage: String?,
    onSignIn: (String, String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

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
                onClick = { onSignIn(username, password) },
                enabled = !actionInFlight && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (actionInFlight) "Signing in..." else "Sign in")
            }
            actionMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

private fun summarize(
    section: DashboardManagementSection,
    root: JsonObject,
): List<DashboardSummaryItem> {
    return when (section.label) {
        "Skills" -> root.arrayField("skills", "items")
            ?.mapIndexed { index, item -> summarizeObjectItem(item, "Skill ${index + 1}") }
            ?: emptyList()
        "Cron" -> root.arrayField("jobs", "items")
            ?.mapIndexed { index, item -> summarizeObjectItem(item, "Job ${index + 1}") }
            ?: emptyList()
        "MCP" -> root.arrayField("servers", "items")
            ?.mapIndexed { index, item -> summarizeObjectItem(item, "Server ${index + 1}") }
            ?: emptyList()
        "Catalog" -> root.arrayField("entries", "catalog", "items")
            ?.mapIndexed { index, item -> summarizeObjectItem(item, "Catalog ${index + 1}") }
            ?: emptyList()
        "Profiles" -> {
            root.arrayField("profiles", "items")
                ?.mapIndexed { index, item -> summarizeObjectItem(item, "Profile ${index + 1}") }
                ?: (root["profiles"] as? JsonObject)
                    ?.entries
                    ?.map { (name, value) -> summarizeObjectItem(value, name) }
                ?: root.entries.map { (name, value) -> summarizeObjectItem(value, name) }
        }
        "Models" -> root.entries.map { (name, value) ->
            DashboardSummaryItem(
                id = name,
                title = name,
                subtitle = value.shortDisplay(),
                meta = value.typeLabel(),
            )
        }
        "Config" -> root.entries.map { (name, value) ->
            DashboardSummaryItem(
                id = name,
                title = name,
                subtitle = value.shortDisplay(),
                meta = value.typeLabel(),
            )
        }
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

private fun summarizeRoot(root: JsonObject): String {
    val keys = root.keys.take(8).joinToString(", ")
    return if (keys.isBlank()) "{ }" else "Fields: $keys"
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
