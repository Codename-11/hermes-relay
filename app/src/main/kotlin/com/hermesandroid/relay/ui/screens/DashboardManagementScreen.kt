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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Key
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.input.VisualTransformation
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
    DashboardManagementSection("Keys", "/api/env"),
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

    // Input-backed kinds — intercepted before runAction and routed to a
    // text-input or model-picker dialog instead of firing immediately.
    SetEnvKey,
    EditProfileDescription,
    SetProfileModel,
    EditProfileSoul,

    // Direct env actions.
    RevealEnvKey,
    ClearEnvKey,
}

/** Section-level (not per-item) affordances rendered at the top of a tab. */
private enum class DashboardSectionAction {
    ChangeMainModel,
    CreateProfile,
    BrowseSkillsHub,
    UpdateSkillsHub,
}

/** Editor session for a profile's SOUL.md — content is the FULL file from GET. */
private data class SoulEditorState(
    val profileName: String,
    val initialContent: String,
    val exists: Boolean,
)

/** Which config slot a model-picker selection writes to. */
private sealed interface ModelPickerTarget {
    data object Main : ModelPickerTarget
    data class Profile(val name: String) : ModelPickerTarget
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
    // Non-null route label ("Tailscale") when the resolver has moved the
    // dashboard off the connection's persisted URL — drives the target line
    // and the per-host sign-in explanation below.
    val dashboardRouteHint by connectionViewModel.dashboardRouteMovedHint.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var showingDetail by remember { mutableStateOf(false) }
    var reloadNonce by remember { mutableStateOf(0) }
    var forceReloadKey by remember { mutableStateOf<String?>(null) }
    val payloadStates = remember { mutableStateMapOf<String, DashboardPayloadState>() }
    val refreshingPayloads = remember { mutableStateMapOf<String, Boolean>() }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var actionInFlight by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PendingDashboardAction?>(null) }
    var detailResult by remember { mutableStateOf<DashboardDetailResult?>(null) }
    var oauthProvider by remember { mutableStateOf<DashboardAuthProvider?>(null) }
    var confirmClearDashboardSession by remember { mutableStateOf(false) }
    var inputAction by remember { mutableStateOf<PendingDashboardAction?>(null) }
    var modelPickerTarget by remember { mutableStateOf<ModelPickerTarget?>(null) }
    var showCreateProfile by remember { mutableStateOf(false) }
    var expensiveModelConfirm by remember { mutableStateOf<ExpensiveModelConfirm?>(null) }
    var showSkillsHub by remember { mutableStateOf(false) }
    var soulEditor by remember { mutableStateOf<SoulEditorState?>(null) }

    val section = managementSections[selectedTab]
    val connectionId = activeConnection?.id ?: "default"
    fun payloadKeyFor(targetSection: DashboardManagementSection): String =
        "$connectionId|$dashboardUrl|${targetSection.path}"

    val payloadKey = payloadKeyFor(section)
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

    suspend fun loadDashboardSection(
        targetSection: DashboardManagementSection,
        targetKey: String,
        foreground: Boolean,
        force: Boolean = false,
    ) {
        if (dashboardUrl.isBlank()) {
            if (foreground) {
                payloadStates[targetKey] = DashboardPayloadState.Error(
                    "No dashboard URL is configured for this connection.",
                )
            }
            return
        }
        val previousState = payloadStates[targetKey]
        if (!force && (previousState is DashboardPayloadState.Loaded ||
                previousState is DashboardPayloadState.Loading)
        ) {
            return
        }
        if (foreground) {
            if (previousState is DashboardPayloadState.Loaded) {
                refreshingPayloads[targetKey] = true
            } else {
                payloadStates[targetKey] = DashboardPayloadState.Loading
            }
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
                    val result = client.getJsonElement(targetSection.path)
                    result.fold(
                        onSuccess = { root ->
                            DashboardPayloadState.Loaded(
                                status = status,
                                session = session,
                                items = summarize(targetSection, root),
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
            val latestState = payloadStates[targetKey]
            if (
                foreground &&
                nextState is DashboardPayloadState.Error &&
                latestState is DashboardPayloadState.Loaded &&
                nextState.status?.authRequired != true
            ) {
                actionMessage = nextState.message
            } else {
                payloadStates[targetKey] = nextState
            }
        } catch (e: Exception) {
            if (foreground || previousState !is DashboardPayloadState.Loaded) {
                payloadStates[targetKey] = DashboardPayloadState.Error(
                    message = e.message ?: "Dashboard request failed",
                )
            }
        } finally {
            if (foreground) {
                refreshingPayloads[targetKey] = false
            }
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
                        forceReloadKey = actionPayloadKey
                        reloadNonce += 1
                        "${action.label} completed"
                    }
                },
                onFailure = { err -> err.message ?: "${action.label} failed" },
            )
            actionInFlight = false
        }
    }

    fun runInputAction(item: DashboardSummaryItem, action: DashboardItemAction, value: String) {
        if (dashboardUrl.isBlank() || actionInFlight) return
        val actionPayloadKey = payloadKey
        actionInFlight = true
        actionMessage = null
        scope.launch {
            val id = item.id.ifBlank { item.title }
            val result = try {
                withDashboardClient(clientFactory) { client ->
                    when (action.kind) {
                        DashboardActionKind.SetEnvKey -> client.setEnvVar(id, value)
                        DashboardActionKind.EditProfileDescription ->
                            client.setProfileDescription(id, value)
                        else -> Result.failure(IllegalStateException("Unsupported input action"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
            actionMessage = result.fold(
                onSuccess = {
                    refreshingPayloads[actionPayloadKey] = true
                    forceReloadKey = actionPayloadKey
                    reloadNonce += 1
                    "${action.label} completed"
                },
                onFailure = { err -> err.message ?: "${action.label} failed" },
            )
            actionInFlight = false
        }
    }

    fun applyModelSelection(
        target: ModelPickerTarget,
        provider: String,
        model: String,
        confirmExpensive: Boolean = false,
    ) {
        if (dashboardUrl.isBlank() || actionInFlight) return
        val actionPayloadKey = payloadKey
        actionInFlight = true
        actionMessage = null
        scope.launch {
            val result = try {
                withDashboardClient(clientFactory) { client ->
                    when (target) {
                        is ModelPickerTarget.Main ->
                            client.setMainModel(provider, model, confirmExpensive)
                        is ModelPickerTarget.Profile ->
                            client.setProfileModel(target.name, provider, model)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = { root ->
                    // Upstream's cost guard answers ok=false + confirm_required
                    // for pricey models; surface the warning and resend with
                    // the confirm flag only after the user accepts.
                    if (root.booleanField("confirm_required") == true) {
                        expensiveModelConfirm = ExpensiveModelConfirm(
                            target = target,
                            provider = provider,
                            model = model,
                            warning = root.stringField("warning")
                                ?: "This model can be expensive to run.",
                        )
                    } else {
                        modelPickerTarget = null
                        refreshingPayloads[actionPayloadKey] = true
                        forceReloadKey = actionPayloadKey
                        reloadNonce += 1
                        actionMessage = "Model set to $model"
                    }
                },
                onFailure = { err -> actionMessage = err.message ?: "Model change failed" },
            )
            actionInFlight = false
        }
    }

    fun openSoulEditor(item: DashboardSummaryItem) {
        if (dashboardUrl.isBlank() || actionInFlight) return
        actionInFlight = true
        actionMessage = null
        scope.launch {
            val profileName = item.id.ifBlank { item.title }
            val result = try {
                withDashboardClient(clientFactory) { client -> client.getProfileSoul(profileName) }
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = { root ->
                    soulEditor = SoulEditorState(
                        profileName = profileName,
                        initialContent = root.stringField("content").orEmpty(),
                        exists = root.booleanField("exists") != false,
                    )
                },
                onFailure = { err ->
                    actionMessage = err.message ?: "Could not load SOUL.md"
                },
            )
            actionInFlight = false
        }
    }

    fun saveSoul(profileName: String, content: String) {
        if (dashboardUrl.isBlank() || actionInFlight) return
        actionInFlight = true
        actionMessage = null
        scope.launch {
            val result = try {
                withDashboardClient(clientFactory) { client ->
                    client.putProfileSoul(profileName, content)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
            actionMessage = result.fold(
                onSuccess = {
                    soulEditor = null
                    "SOUL.md saved for $profileName"
                },
                onFailure = { err -> err.message ?: "SOUL.md save failed" },
            )
            actionInFlight = false
        }
    }

    fun runUpdateSkillsHub() {
        if (dashboardUrl.isBlank() || actionInFlight) return
        actionInFlight = true
        actionMessage = null
        scope.launch {
            val result = try {
                withDashboardClient(clientFactory) { client -> client.updateSkillsHub() }
            } catch (e: Exception) {
                Result.failure(e)
            }
            actionMessage = result.fold(
                // The server spawns `hermes skills update` and returns
                // immediately — completion lands in the skills list later.
                onSuccess = { "Skill update started on the server — refresh Skills in a minute" },
                onFailure = { err -> err.message ?: "Skill update failed to start" },
            )
            actionInFlight = false
        }
    }

    fun submitCreateProfile(name: String, description: String, cloneFromDefault: Boolean) {
        if (dashboardUrl.isBlank() || actionInFlight) return
        val actionPayloadKey = payloadKey
        actionInFlight = true
        actionMessage = null
        scope.launch {
            val result = try {
                withDashboardClient(clientFactory) { client ->
                    client.createProfile(
                        name = name,
                        cloneFromDefault = cloneFromDefault,
                        description = description.takeIf { it.isNotBlank() },
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
            actionMessage = result.fold(
                onSuccess = {
                    showCreateProfile = false
                    refreshingPayloads[actionPayloadKey] = true
                    forceReloadKey = actionPayloadKey
                    reloadNonce += 1
                    "Profile $name created"
                },
                onFailure = { err -> err.message ?: "Profile create failed" },
            )
            actionInFlight = false
        }
    }

    LaunchedEffect(dashboardUrl, selectedTab, reloadNonce, activeConnection?.id) {
        val forceCurrent = forceReloadKey == payloadKey
        loadDashboardSection(
            targetSection = section,
            targetKey = payloadKey,
            foreground = true,
            force = forceCurrent,
        )
        if (forceCurrent && forceReloadKey == payloadKey) {
            forceReloadKey = null
        }
    }

    LaunchedEffect(dashboardUrl, activeConnection?.id, payloadState) {
        val loadedState = payloadState as? DashboardPayloadState.Loaded ?: return@LaunchedEffect
        if (dashboardUrl.isBlank()) return@LaunchedEffect
        if (loadedState.status?.authRequired == true && loadedState.session?.authenticated != true) {
            return@LaunchedEffect
        }
        managementSections.forEach { prewarmSection ->
            val prewarmKey = payloadKeyFor(prewarmSection)
            if (prewarmKey == payloadKey) return@forEach
            val existingState = payloadStates[prewarmKey]
            if (existingState !is DashboardPayloadState.Loaded &&
                existingState !is DashboardPayloadState.Loading
            ) {
                loadDashboardSection(
                    targetSection = prewarmSection,
                    targetKey = prewarmKey,
                    foreground = false,
                )
            }
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
                    payloadStates.clear()
                    refreshingPayloads.clear()
                    forceReloadKey = null
                    reloadNonce += 1
                    // Standard voice rides this same cookie session — unlock the
                    // mic immediately rather than waiting for the next health tick.
                    connectionViewModel.refreshStandardVoice()
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

    inputAction?.let { pending ->
        val isEnvKey = pending.action.kind == DashboardActionKind.SetEnvKey
        var inputValue by remember(pending) {
            mutableStateOf(if (isEnvKey) "" else pending.item.subtitle.orEmpty())
        }
        AlertDialog(
            onDismissRequest = { inputAction = null },
            title = { Text("${pending.action.label} ${pending.item.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isEnvKey) {
                            "Stored in the server's ~/.hermes/.env. The field is write-only " +
                                "here — use Reveal to read the current value back."
                        } else {
                            "Short role description shown in the profile picker and used " +
                                "for routing. Leave empty to clear it."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = inputValue,
                        onValueChange = { inputValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = isEnvKey,
                        visualTransformation = if (isEnvKey) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        label = { Text(if (isEnvKey) "Value" else "Description") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = inputValue
                        inputAction = null
                        runInputAction(pending.item, pending.action, value)
                    },
                    enabled = !isEnvKey || inputValue.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { inputAction = null }) { Text("Cancel") }
            },
        )
    }

    if (showCreateProfile) {
        var newProfileName by remember { mutableStateOf("") }
        var newProfileDescription by remember { mutableStateOf("") }
        var cloneFromDefault by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showCreateProfile = false },
            title = { Text("New profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Name") },
                    )
                    OutlinedTextField(
                        value = newProfileDescription,
                        onValueChange = { newProfileDescription = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Description (optional)") },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = cloneFromDefault,
                            onCheckedChange = { cloneFromDefault = it },
                        )
                        Text(
                            text = "Start from the default profile's config and skills",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        submitCreateProfile(
                            name = newProfileName.trim(),
                            description = newProfileDescription.trim(),
                            cloneFromDefault = cloneFromDefault,
                        )
                    },
                    enabled = newProfileName.isNotBlank() && !actionInFlight,
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateProfile = false }) { Text("Cancel") }
            },
        )
    }

    expensiveModelConfirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = { expensiveModelConfirm = null },
            title = { Text("Expensive model") },
            text = { Text(confirm.warning, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(
                    onClick = {
                        expensiveModelConfirm = null
                        applyModelSelection(
                            target = confirm.target,
                            provider = confirm.provider,
                            model = confirm.model,
                            confirmExpensive = true,
                        )
                    },
                ) { Text("Use anyway") }
            },
            dismissButton = {
                TextButton(onClick = { expensiveModelConfirm = null }) { Text("Cancel") }
            },
        )
    }

    modelPickerTarget?.let { target ->
        ModelPickerDialog(
            target = target,
            clientFactory = clientFactory,
            actionInFlight = actionInFlight,
            onSelect = { provider, model -> applyModelSelection(target, provider, model) },
            onDismiss = { modelPickerTarget = null },
        )
    }

    soulEditor?.let { editor ->
        SoulEditorDialog(
            editor = editor,
            saving = actionInFlight,
            onSave = { content -> saveSoul(editor.profileName, content) },
            onDismiss = { soulEditor = null },
        )
    }

    if (showSkillsHub) {
        SkillsHubDialog(
            clientFactory = clientFactory,
            onPreview = { detail -> detailResult = detail },
            onMessage = { message -> actionMessage = message },
            onDismiss = { showSkillsHub = false },
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
                            payloadStates.clear()
                            refreshingPayloads.clear()
                            forceReloadKey = null
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
                payloadStates.clear()
                refreshingPayloads.clear()
                forceReloadKey = null
                actionMessage = "Signed in${session.provider?.let { " with $it" }.orEmpty()}"
                reloadNonce += 1
                connectionViewModel.refreshStandardVoice()
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
                        onClick = {
                            forceReloadKey = payloadKey
                            reloadNonce += 1
                        },
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
            if (dashboardUrl.isNotBlank()) {
                ManageDashboardTargetLine(
                    dashboardUrl = dashboardUrl,
                    routeHint = dashboardRouteHint,
                )
            }
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
                                    routeHint = dashboardRouteHint,
                                    actionInFlight = actionInFlight,
                                    actionMessage = actionMessage,
                                    onRetry = {
                                        forceReloadKey = payloadKey
                                        reloadNonce += 1
                                    },
                                    onSignIn = ::submitDashboardSignIn,
                                    onOAuthSignIn = { provider -> oauthProvider = provider },
                                )
                                is DashboardPayloadState.Loaded -> LoadedBody(
                                    section = section,
                                    state = state,
                                    actionInFlight = actionInFlight,
                                    actionMessage = actionMessage,
                                    onAction = { item, action ->
                                        when (action.kind) {
                                            DashboardActionKind.SetEnvKey,
                                            DashboardActionKind.EditProfileDescription ->
                                                inputAction = PendingDashboardAction(item, action)
                                            DashboardActionKind.SetProfileModel ->
                                                modelPickerTarget = ModelPickerTarget.Profile(
                                                    item.id.ifBlank { item.title },
                                                )
                                            DashboardActionKind.EditProfileSoul ->
                                                openSoulEditor(item)
                                            else -> if (action.destructive) {
                                                pendingAction = PendingDashboardAction(item, action)
                                            } else {
                                                runAction(item, action)
                                            }
                                        }
                                    },
                                    onSectionAction = { sectionAction ->
                                        when (sectionAction) {
                                            DashboardSectionAction.ChangeMainModel ->
                                                modelPickerTarget = ModelPickerTarget.Main
                                            DashboardSectionAction.CreateProfile ->
                                                showCreateProfile = true
                                            DashboardSectionAction.BrowseSkillsHub ->
                                                showSkillsHub = true
                                            DashboardSectionAction.UpdateSkillsHub ->
                                                runUpdateSkillsHub()
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
                        routeHint = dashboardRouteHint,
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
        subtitle = "Pick provider + default model",
    )
    "Keys" -> ManageTileSpec(
        icon = Icons.Filled.Key,
        title = "Keys",
        subtitle = "Provider keys + env secrets",
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
    routeHint: String?,
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
                routeHint = routeHint,
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
        item {
            RelayNavTile(
                icon = Icons.Filled.Tune,
                title = "Models",
                subtitle = "Pick provider + default model",
                onClick = { onSelectSection("Models") },
            )
        }
        item {
            RelayNavTile(
                icon = Icons.Filled.Key,
                title = "Keys",
                subtitle = "Provider keys + env secrets",
                onClick = { onSelectSection("Keys") },
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
    routeHint: String?,
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
        routeHint?.let { "$it route" },
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

/**
 * Always-visible one-liner naming the dashboard URL Manage is talking to
 * right now. The effective URL follows the resolved route (LAN ↔ Tailscale)
 * while the connection's saved URLs stay put — when something here fails,
 * the first debugging question is "which host was that against?", so the
 * answer lives on screen instead of in DiagnosticsLog. The route suffix
 * appears only when the resolver has moved Manage off the persisted URL.
 */
@Composable
private fun ManageDashboardTargetLine(dashboardUrl: String, routeHint: String?) {
    Text(
        text = "Dashboard: $dashboardUrl" +
            (routeHint?.let { " · $it route" } ?: ""),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
private fun ErrorBody(
    message: String,
    status: DashboardStatus?,
    dashboardUrl: String,
    routeHint: String?,
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
                routeHint = routeHint,
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
                    // Name the exact target: the dashboard (:9119) is a
                    // separate server from the API (:8642), so "chat works"
                    // proves nothing about this URL — and on a moved route
                    // the host may differ from what the user configured.
                    Text(
                        text = "Couldn't load from $dashboardUrl" +
                            (routeHint?.let { " ($it route)" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
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
    onSectionAction: (DashboardSectionAction) -> Unit = {},
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
        val sectionActions = when (section.label) {
            "Models" -> listOf(DashboardSectionAction.ChangeMainModel to "Change main model")
            "Profiles" -> listOf(DashboardSectionAction.CreateProfile to "New profile")
            "Skills" -> listOf(
                DashboardSectionAction.BrowseSkillsHub to "Browse hub",
                DashboardSectionAction.UpdateSkillsHub to "Update installed",
            )
            else -> emptyList()
        }
        if (sectionActions.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sectionActions.forEach { (sectionAction, label) ->
                        OutlinedButton(
                            onClick = { onSectionAction(sectionAction) },
                            enabled = !actionInFlight,
                        ) {
                            Text(label)
                        }
                    }
                }
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
                // Five-plus buttons (profiles carry six) wrap into a noisy
                // two-row block — keep the three most-used inline and fold
                // the rest behind "More".
                val inlineActions =
                    if (item.actions.size > 4) item.actions.take(3) else item.actions
                val overflowActions =
                    if (item.actions.size > 4) item.actions.drop(3) else emptyList()
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    inlineActions.forEach { action ->
                        OutlinedButton(
                            onClick = { onAction(action) },
                            enabled = !actionInFlight,
                        ) {
                            Text(action.label)
                        }
                    }
                    if (overflowActions.isNotEmpty()) {
                        Box {
                            var menuOpen by remember { mutableStateOf(false) }
                            OutlinedButton(
                                onClick = { menuOpen = true },
                                enabled = !actionInFlight,
                            ) {
                                Text("More")
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                overflowActions.forEach { action ->
                                    DropdownMenuItem(
                                        text = { Text(action.label) },
                                        onClick = {
                                            menuOpen = false
                                            onAction(action)
                                        },
                                    )
                                }
                            }
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
    routeHint: String? = null,
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
            if (routeHint != null) {
                // Same per-host cookie reality the voice gate explains:
                // a sign-in on the home host does not authenticate this
                // one. Without this strip, a user who signed in at home
                // reads the prompt above as a broken session.
                Text(
                    text = "You're on the $routeHint route. Dashboard " +
                        "sign-ins are per host, so your sign-in from the " +
                        "other route doesn't carry over — sign in once " +
                        "here and the app keeps both sessions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(10.dp),
                )
            }

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

private data class ExpensiveModelConfirm(
    val target: ModelPickerTarget,
    val provider: String,
    val model: String,
    val warning: String,
)

private data class ModelProviderOption(
    val id: String,
    val label: String,
    val authenticated: Boolean,
    val models: List<String>,
)

/**
 * Tolerant reader for `GET /api/model/options` (the REST twin of the TUI's
 * `model.options` RPC). Unauthenticated providers come back as skeleton rows
 * — keep them visible but unselectable so the user learns which key to add
 * in the Keys section instead of the provider silently missing.
 */
private fun parseModelOptions(root: JsonObject): List<ModelProviderOption> {
    val providers = root["providers"] as? JsonArray ?: return emptyList()
    return providers.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj.stringField("id")
            ?: obj.stringField("provider")
            ?: obj.stringField("name")
            ?: return@mapNotNull null
        val models = (obj["models"] as? JsonArray)?.mapNotNull { modelElement ->
            when (modelElement) {
                is JsonPrimitive -> modelElement.contentOrNull
                is JsonObject -> modelElement.stringField("id") ?: modelElement.stringField("name")
                else -> null
            }?.trim()?.takeIf { it.isNotBlank() }
        }.orEmpty()
        if (models.isEmpty()) return@mapNotNull null
        ModelProviderOption(
            id = id,
            label = obj.stringField("label")
                ?: obj.stringField("display_name")
                ?: obj.stringField("name")
                ?: id,
            authenticated = obj.booleanField("authenticated") != false,
            models = models,
        )
    }.sortedByDescending { it.authenticated }
}

@Composable
private fun ModelPickerDialog(
    target: ModelPickerTarget,
    clientFactory: () -> DashboardApiClient,
    actionInFlight: Boolean,
    onSelect: (provider: String, model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var providers by remember { mutableStateOf<List<ModelProviderOption>>(emptyList()) }

    LaunchedEffect(target) {
        loading = true
        error = null
        val result = try {
            withDashboardClient(clientFactory) { client -> client.getModelOptions() }
        } catch (e: Exception) {
            Result.failure(e)
        }
        result.fold(
            onSuccess = { root ->
                providers = parseModelOptions(root)
                if (providers.isEmpty()) {
                    error = "The dashboard returned no model options."
                }
            },
            onFailure = { err -> error = err.message ?: "Could not load model options" },
        )
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (target) {
                    is ModelPickerTarget.Main -> "Main model"
                    is ModelPickerTarget.Profile -> "Model for ${target.name}"
                },
            )
        },
        text = {
            when {
                loading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Loading provider catalog...")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                error != null -> Text(
                    text = error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Column {
                    Text(
                        text = "Applies to new sessions. Greyed providers need a key — " +
                            "add one under Manage → Keys.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        providers.forEach { provider ->
                            item(key = "provider-${provider.id}") {
                                Text(
                                    text = provider.label +
                                        if (provider.authenticated) "" else " · key missing",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (provider.authenticated) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                                )
                            }
                            items(
                                items = provider.models,
                                key = { model -> "model-${provider.id}-$model" },
                            ) { model ->
                                Text(
                                    text = model,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (provider.authenticated) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = provider.authenticated && !actionInFlight) {
                                            onSelect(provider.id, model)
                                        }
                                        .padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private data class SkillHubResult(
    val identifier: String,
    val name: String,
    val description: String?,
    val source: String?,
    val trustLevel: String?,
    val tags: List<String>,
    /** Installed-skill name from the server's lock file; null when not installed. */
    val installedName: String?,
)

private fun parseSkillHubSearch(
    root: JsonObject,
    resultsKey: String = "results",
): List<SkillHubResult> {
    val installed = root["installed"] as? JsonObject ?: JsonObject(emptyMap())
    val results = root[resultsKey] as? JsonArray ?: return emptyList()
    return results.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val identifier = obj.stringField("identifier") ?: return@mapNotNull null
        val lockEntry = installed[identifier] as? JsonObject
        SkillHubResult(
            identifier = identifier,
            name = obj.stringField("name") ?: identifier,
            description = obj.stringField("description"),
            source = obj.stringField("source"),
            trustLevel = obj.stringField("trust_level"),
            tags = (obj["tags"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .orEmpty(),
            installedName = lockEntry?.stringField("name")
                ?: if (installed.containsKey(identifier)) obj.stringField("name") else null,
        )
    }
}

/**
 * Browse-hub parity with hermes-desktop's Skills tab: multi-source search,
 * SKILL.md preview before install, async install/uninstall. Installs spawn
 * `hermes skills install` on the server and return immediately — rows flip
 * to a "started" state and the Skills list reflects reality after a refresh.
 */
@Composable
private fun SkillsHubDialog(
    clientFactory: () -> DashboardApiClient,
    onPreview: (DashboardDetailResult) -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<SkillHubResult>>(emptyList()) }
    var busyIdentifiers by remember { mutableStateOf(setOf<String>()) }
    var sourcesLine by remember { mutableStateOf<String?>(null) }
    var showingFeatured by remember { mutableStateOf(false) }

    // Pre-search content: configured sources + featured skills from the
    // centralized index, so the dialog isn't a blank search box on open.
    // Best-effort — failures stay silent (search still works without it).
    LaunchedEffect(Unit) {
        val sources = try {
            withDashboardClient(clientFactory) { client -> client.getSkillsHubSources() }
        } catch (e: Exception) {
            Result.failure(e)
        }
        sources.getOrNull()?.let { root ->
            sourcesLine = (root["sources"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.stringField("label") }
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
            if (!searched && results.isEmpty()) {
                val featured = parseSkillHubSearch(root, resultsKey = "featured")
                if (featured.isNotEmpty()) {
                    results = featured
                    showingFeatured = true
                }
            }
        }
    }

    fun runSearch() {
        val term = query.trim()
        if (term.isBlank() || searching) return
        searching = true
        error = null
        scope.launch {
            val result = try {
                withDashboardClient(clientFactory) { client -> client.searchSkillsHub(term) }
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = { root ->
                    results = parseSkillHubSearch(root)
                    searched = true
                    showingFeatured = false
                },
                onFailure = { err -> error = err.message ?: "Hub search failed" },
            )
            searching = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Skills hub", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Search the configured hub sources. Preview reads the " +
                        "SKILL.md before anything is installed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sourcesLine?.let { line ->
                    Text(
                        text = "Sources: $line",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Search skills") },
                    )
                    Button(onClick = { runSearch() }, enabled = !searching && query.isNotBlank()) {
                        Text("Search")
                    }
                }
                if (searching) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Searching hub sources (can take up to ~30s)...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (searched && !searching && results.isEmpty() && error == null) {
                    Text(
                        text = "No skills matched \"${query.trim()}\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showingFeatured && results.isNotEmpty() && !searching) {
                    Text(
                        text = "Featured skills",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(results, key = { it.identifier }) { result ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = result.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium,
                            )
                            result.description?.let { description ->
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = listOfNotNull(
                                    result.source,
                                    result.trustLevel,
                                    result.tags.take(3).joinToString(", ").takeIf { it.isNotBlank() },
                                    "installed".takeIf { result.installedName != null },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val busy = result.identifier in busyIdentifiers
                                OutlinedButton(
                                    onClick = {
                                        busyIdentifiers = busyIdentifiers + result.identifier
                                        scope.launch {
                                            val previewResult = try {
                                                withDashboardClient(clientFactory) { client ->
                                                    client.previewSkillsHub(result.identifier)
                                                }
                                            } catch (e: Exception) {
                                                Result.failure(e)
                                            }
                                            busyIdentifiers = busyIdentifiers - result.identifier
                                            previewResult.fold(
                                                onSuccess = { root ->
                                                    val body = root.stringField("skill_md")
                                                        ?: root.stringField("content")
                                                        ?: compactJsonLines(root)
                                                    onPreview(
                                                        DashboardDetailResult(
                                                            title = "${result.name} · SKILL.md",
                                                            body = body.take(6_000),
                                                        ),
                                                    )
                                                },
                                                onFailure = { err ->
                                                    onMessage(err.message ?: "Preview failed")
                                                },
                                            )
                                        }
                                    },
                                    enabled = !busy,
                                ) { Text("Preview") }
                                if (result.installedName != null) {
                                    OutlinedButton(
                                        onClick = {
                                            busyIdentifiers = busyIdentifiers + result.identifier
                                            scope.launch {
                                                val uninstall = try {
                                                    withDashboardClient(clientFactory) { client ->
                                                        client.uninstallSkillsHub(result.installedName)
                                                    }
                                                } catch (e: Exception) {
                                                    Result.failure(e)
                                                }
                                                busyIdentifiers = busyIdentifiers - result.identifier
                                                onMessage(
                                                    uninstall.fold(
                                                        onSuccess = {
                                                            "Uninstall of ${result.installedName} started — refresh Skills shortly"
                                                        },
                                                        onFailure = { err ->
                                                            err.message ?: "Uninstall failed to start"
                                                        },
                                                    ),
                                                )
                                            }
                                        },
                                        enabled = !busy,
                                    ) { Text("Uninstall") }
                                } else {
                                    Button(
                                        onClick = {
                                            busyIdentifiers = busyIdentifiers + result.identifier
                                            scope.launch {
                                                val install = try {
                                                    withDashboardClient(clientFactory) { client ->
                                                        client.installSkillsHub(result.identifier)
                                                    }
                                                } catch (e: Exception) {
                                                    Result.failure(e)
                                                }
                                                // Keep the row busy on success — install runs
                                                // server-side; re-enabling would invite doubles.
                                                if (install.isFailure) {
                                                    busyIdentifiers = busyIdentifiers - result.identifier
                                                }
                                                onMessage(
                                                    install.fold(
                                                        onSuccess = {
                                                            "Install of ${result.name} started — refresh Skills shortly"
                                                        },
                                                        onFailure = { err ->
                                                            err.message ?: "Install failed to start"
                                                        },
                                                    ),
                                                )
                                            }
                                        },
                                        enabled = !busy,
                                    ) { Text("Install") }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

/**
 * Full-file SOUL.md editor. The dashboard GET returns the complete file (no
 * truncation), so saving the edited buffer back is a lossless round-trip.
 */
@Composable
private fun SoulEditorDialog(
    editor: SoulEditorState,
    saving: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var content by remember(editor) { mutableStateOf(editor.initialContent) }
    Dialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 360.dp, max = 640.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "SOUL.md · ${editor.profileName}",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!editor.exists) {
                    Text(
                        text = "No SOUL.md exists for this profile yet — saving creates it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    enabled = !saving,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${content.length} chars",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                        Button(onClick = { onSave(content) }, enabled = !saving) {
                            Text(if (saving) "Saving..." else "Save")
                        }
                    }
                }
            }
        }
    }
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
        "Keys" -> summarizeEnvVars(root)
        "Config" -> summarizeKeyValueOrList(root, "Config")
        else -> emptyList()
    }
}

/**
 * `GET /api/env` returns a map of var name → metadata. Upstream's SPA hides
 * `channel_managed` vars because its Channels page owns them — we have no
 * Channels page, so they stay visible here, just tagged. Values are
 * pre-redacted server-side; Reveal round-trips for the real value.
 */
private fun summarizeEnvVars(root: JsonElement): List<DashboardSummaryItem> {
    val obj = root as? JsonObject ?: return emptyList()
    return obj.entries.mapNotNull { (name, value) ->
        val info = value as? JsonObject ?: return@mapNotNull null
        val isSet = info.booleanField("is_set") == true
        val meta = listOfNotNull(
            if (isSet) "set" else "not set",
            info.stringField("redacted_value"),
            info.stringField("category"),
            info.booleanField("channel_managed")?.takeIf { it }?.let { "channel" },
            info.booleanField("advanced")?.takeIf { it }?.let { "advanced" },
        ).joinToString(" · ")
        DashboardSummaryItem(
            id = name,
            title = name,
            subtitle = info.stringField("description"),
            meta = meta,
            actions = buildList {
                add(DashboardItemAction("Set", DashboardActionKind.SetEnvKey))
                if (isSet) {
                    add(DashboardItemAction("Reveal", DashboardActionKind.RevealEnvKey))
                    add(DashboardItemAction("Clear", DashboardActionKind.ClearEnvKey, destructive = true))
                }
            },
        )
    }.sortedWith(compareByDescending<DashboardSummaryItem> { it.meta?.startsWith("set") == true }.thenBy { it.title })
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
                add(DashboardItemAction("Edit SOUL", DashboardActionKind.EditProfileSoul))
                add(DashboardItemAction("Use", DashboardActionKind.ActivateProfile))
                add(DashboardItemAction("Describe", DashboardActionKind.EditProfileDescription))
                add(DashboardItemAction("Model", DashboardActionKind.SetProfileModel))
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
        DashboardActionKind.ClearEnvKey -> copy(
            meta = "not set",
            actions = listOf(DashboardItemAction("Set", DashboardActionKind.SetEnvKey)),
        )
        DashboardActionKind.ViewCronRuns,
        DashboardActionKind.TriggerCron,
        DashboardActionKind.TestMcp,
        DashboardActionKind.ViewProfileSoul,
        DashboardActionKind.ActivateProfile,
        DashboardActionKind.SetEnvKey,
        DashboardActionKind.RevealEnvKey,
        DashboardActionKind.EditProfileDescription,
        DashboardActionKind.SetProfileModel,
        DashboardActionKind.EditProfileSoul -> this
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
        DashboardActionKind.RevealEnvKey -> revealEnvVar(id)
        DashboardActionKind.ClearEnvKey -> deleteEnvVar(id)
        // Input-backed kinds are intercepted at the onAction layer and routed
        // to dialogs; reaching here means a wiring bug, not a server problem.
        DashboardActionKind.SetEnvKey,
        DashboardActionKind.EditProfileDescription,
        DashboardActionKind.SetProfileModel,
        DashboardActionKind.EditProfileSoul ->
            Result.failure(IllegalStateException("${action.label} requires input"))
    }
}

private val DashboardActionKind.isDetailAction: Boolean
    get() = this == DashboardActionKind.ViewCronRuns ||
        this == DashboardActionKind.ViewProfileSoul ||
        this == DashboardActionKind.RevealEnvKey

private fun detailBodyFor(kind: DashboardActionKind, root: JsonObject): String {
    return when (kind) {
        DashboardActionKind.RevealEnvKey -> {
            val key = root.stringField("key").orEmpty()
            val value = root.stringField("value").orEmpty()
            if (key.isBlank() && value.isBlank()) {
                compactJsonLines(root)
            } else {
                "$key=$value"
            }
        }
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
