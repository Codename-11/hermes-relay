package com.hermesandroid.relay.ui.screens

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hermesandroid.relay.R
import com.hermesandroid.relay.network.upstream.EncryptedDashboardCookieStore
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.DashboardCookieStore
import com.hermesandroid.relay.network.upstream.DashboardAuthProvider
import com.hermesandroid.relay.network.upstream.DashboardAuthSession
import com.hermesandroid.relay.network.upstream.DashboardStatus
import com.hermesandroid.relay.network.upstream.importDashboardCookieHeader
import com.hermesandroid.relay.ui.components.RelayChromeIconButton
import com.hermesandroid.relay.ui.components.RelayMetricCard
import com.hermesandroid.relay.ui.components.RelayNavTile
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

/**
 * Section of the Hermes dashboard Manage tab. The enum identity is used for
 * `when` branching and equality; [displayLabel] / [lowercaseLabel] resolve to
 * localized strings at call sites. [path] is the upstream API path.
 */
private enum class DashboardManagementSection(val path: String) {
    Skills("/api/skills"),
    Cron("/api/cron/jobs"),
    Mcp("/api/mcp/servers"),
    Catalog("/api/mcp/catalog"),
    Profiles("/api/profiles"),
    Models("/api/model/info"),
    Keys("/api/env"),
    Config("/api/config/schema");

    @Composable
    fun displayLabel(): String = when (this) {
        Skills -> stringResource(R.string.dashboard_tab_skills)
        Cron -> stringResource(R.string.dashboard_tab_cron)
        Mcp -> stringResource(R.string.dashboard_tab_mcp)
        Catalog -> stringResource(R.string.dashboard_tab_catalog)
        Profiles -> stringResource(R.string.dashboard_tab_profiles)
        Models -> stringResource(R.string.dashboard_tab_models)
        Keys -> stringResource(R.string.dashboard_tab_keys)
        Config -> stringResource(R.string.dashboard_tab_config)
    }

    /** Lowercased display label for compact UI surfaces (KPI strip). */
    @Composable
    fun lowercaseLabel(): String = when (this) {
        Skills -> stringResource(R.string.dashboard_tab_skills_lower)
        Cron -> stringResource(R.string.dashboard_tab_cron_lower)
        Mcp -> stringResource(R.string.dashboard_tab_mcp_lower)
        Catalog -> stringResource(R.string.dashboard_tab_catalog_lower)
        Profiles -> stringResource(R.string.dashboard_tab_profiles_lower)
        Models -> stringResource(R.string.dashboard_tab_models_lower)
        Keys -> stringResource(R.string.dashboard_tab_keys_lower)
        Config -> stringResource(R.string.dashboard_tab_config_lower)
    }
}

private val managementSections: List<DashboardManagementSection> = DashboardManagementSection.entries

private sealed interface DashboardPayloadState {
    data object Idle : DashboardPayloadState
    data object Loading : DashboardPayloadState
    data class Loaded(
        val status: DashboardStatus?,
        val session: DashboardAuthSession?,
        val items: List<DashboardSummaryItem>,
        val rawSummary: String,
        /** Wall-clock fetch time — drives the stale-while-revalidate window. */
        val fetchedAtMillis: Long = 0L,
    ) : DashboardPayloadState
    data class Error(
        val message: String,
        val status: DashboardStatus? = null,
    ) : DashboardPayloadState
}

/**
 * Process-lifetime cache of dashboard section payloads, keyed
 * `"connectionId|dashboardUrl|sectionPath"` (see [dashboardPayloadKey]).
 *
 * This used to live in `remember {}` inside the screen, which meant every
 * navigation away from Manage threw the data away and every entry replayed
 * the full skeleton. Hoisting it to a file-level singleton makes re-entry
 * instant (stale-while-revalidate: cached content renders immediately,
 * entries older than [FRESH_WINDOW_MS] refresh in the background) and gives
 * the app-start pre-warm somewhere to put its results. Keys are partitioned
 * by connection AND dashboard URL, so connection switches and LAN↔Tailscale
 * route handoffs never serve each other's data. Sign-in/sign-out paths call
 * `states.clear()` exactly as they did against the remembered map.
 */
private object DashboardPayloadCache {
    val states = mutableStateMapOf<String, DashboardPayloadState>()
    val refreshing = mutableStateMapOf<String, Boolean>()

    /** Loaded entries younger than this are served without a re-fetch. */
    const val FRESH_WINDOW_MS = 30_000L

    /**
     * One disk hydration per process — set (main thread only) by
     * [hydrateDashboardManageCache] before it reads the file.
     */
    var hydrationAttempted = false
}

private fun DashboardPayloadState.Loaded.toPersisted() = PersistedDashboardPayload(
    status = status,
    session = session,
    items = items,
    rawSummary = rawSummary,
    fetchedAtMillis = fetchedAtMillis,
)

private fun PersistedDashboardPayload.toLoaded() = DashboardPayloadState.Loaded(
    status = status,
    session = session,
    items = items,
    rawSummary = rawSummary,
    fetchedAtMillis = fetchedAtMillis,
)

/**
 * Fill the in-memory payload cache from disk at app start — keys that are
 * already populated (a fetch beat us to it) are left alone. Hydrated
 * entries carry their original [DashboardPayloadState.Loaded.fetchedAtMillis],
 * so they render instantly AND count as stale: the screen's
 * stale-while-revalidate path and [prewarmDashboardManage] refresh them
 * quietly. Call from the main dispatcher.
 */
internal suspend fun hydrateDashboardManageCache(cacheDir: java.io.File) {
    if (DashboardPayloadCache.hydrationAttempted) return
    DashboardPayloadCache.hydrationAttempted = true
    val entries = DashboardManageDiskCache.read(cacheDir)
    entries.forEach { (key, persisted) ->
        if (key !in DashboardPayloadCache.states && persisted.fetchedAtMillis > 0L) {
            DashboardPayloadCache.states[key] = persisted.toLoaded()
        }
    }
}

/**
 * Snapshot every Loaded entry to disk (whole-file rewrite — the payload is
 * a few KB across all sections/routes). Call after any fetch that lands a
 * new Loaded entry; concurrent callers serialize on the store's write lock
 * and the last snapshot wins.
 */
internal suspend fun persistDashboardManageCache(cacheDir: java.io.File) {
    val entries = buildMap {
        DashboardPayloadCache.states.forEach { (key, state) ->
            if (state is DashboardPayloadState.Loaded && state.fetchedAtMillis > 0L) {
                put(key, state.toPersisted())
            }
        }
    }
    DashboardManageDiskCache.write(cacheDir, entries)
}

/** Sign-in/out invalidation — wipes the disk mirror alongside the map. */
internal suspend fun clearDashboardManageDiskCache(cacheDir: java.io.File) {
    DashboardManageDiskCache.clear(cacheDir)
}

private fun dashboardPayloadKey(
    connectionId: String,
    dashboardUrl: String,
    sectionPath: String,
): String = "$connectionId|$dashboardUrl|$sectionPath"

// DashboardSummaryItem / DashboardItemAction / DashboardActionKind moved to
// DashboardManageDiskCache.kt (internal + @Serializable) so the payload
// cache can persist across process death. Same package — usages unchanged.

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
    onBack: () -> Unit = {},
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
    // Process-lifetime cache (NOT remember{}) — see [DashboardPayloadCache].
    val payloadStates = DashboardPayloadCache.states
    val refreshingPayloads = DashboardPayloadCache.refreshing
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
        dashboardPayloadKey(connectionId, dashboardUrl, targetSection.path)

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
            // Prefer the VM's per-connection cached store — one Keystore
            // keyset build per connection per process instead of one per
            // client construction (each build holds a global Tink lock for
            // seconds on StrongBox devices).
            connectionViewModel.activeDashboardCookieStore()
                ?: EncryptedDashboardCookieStore(
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
        // Shared auth context for background sweeps — skips the 4-call
        // preamble per section AND the redundant re-record of the same
        // status snapshot into the connection.
        preamble: DashboardPreamble? = null,
    ) {
        if (dashboardUrl.isBlank()) {
            if (foreground) {
                payloadStates[targetKey] = DashboardPayloadState.Error(
                    context.getString(R.string.dashboard_no_url_configured),
                )
            }
            return
        }
        val previousState = payloadStates[targetKey]
        if (!force) {
            if (previousState is DashboardPayloadState.Loading) return
            if (previousState is DashboardPayloadState.Loaded) {
                val isFresh = System.currentTimeMillis() - previousState.fetchedAtMillis <
                    DashboardPayloadCache.FRESH_WINDOW_MS
                if (isFresh) return
                // Stale-while-revalidate: the cached content stays on screen
                // (refreshing bar only) while we re-fetch below.
            }
        }
        if (foreground) {
            if (previousState is DashboardPayloadState.Loaded) {
                refreshingPayloads[targetKey] = true
            } else {
                payloadStates[targetKey] = DashboardPayloadState.Loading
            }
        }
        try {
            val nextState = fetchDashboardSectionState(
                clientFactory = clientFactory,
                targetSection = targetSection,
                preamble = preamble,
            ) { status, session, gatewayTicketAvailable ->
                if (preamble == null) {
                    connectionViewModel.recordDashboardStatus(
                        status = status,
                        session = session,
                        reachable = status != null,
                        gatewayTicketAvailable = gatewayTicketAvailable,
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
            if (nextState is DashboardPayloadState.Loaded) {
                persistDashboardManageCache(context.cacheDir)
            }
        } catch (e: Exception) {
            if (foreground || previousState !is DashboardPayloadState.Loaded) {
                payloadStates[targetKey] = DashboardPayloadState.Error(
                    message = e.message ?: context.getString(R.string.dashboard_request_failed),
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
                    val actionLabel = dashboardActionLabel(context, action.kind)
                    if (action.kind.isDetailAction) {
                        detailResult = DashboardDetailResult(
                            title = context.getString(R.string.dashboard_detail_title, item.title, actionLabel),
                            body = detailBodyFor(context, action.kind, root),
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
                        context.getString(R.string.dashboard_action_completed, actionLabel)
                    }
                },
                onFailure = { err ->
                    err.message ?: context.getString(
                        R.string.dashboard_action_failed,
                        dashboardActionLabel(context, action.kind),
                    )
                },
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
                    context.getString(
                        R.string.dashboard_action_completed,
                        dashboardActionLabel(context, action.kind),
                    )
                },
                onFailure = { err ->
                    err.message ?: context.getString(
                        R.string.dashboard_action_failed,
                        dashboardActionLabel(context, action.kind),
                    )
                },
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
                                ?: context.getString(R.string.dashboard_expensive_model_default_warning),
                        )
                    } else {
                        modelPickerTarget = null
                        refreshingPayloads[actionPayloadKey] = true
                        forceReloadKey = actionPayloadKey
                        reloadNonce += 1
                        actionMessage = context.getString(R.string.dashboard_model_set, model)
                    }
                },
                onFailure = { err -> actionMessage = err.message ?: context.getString(R.string.dashboard_model_change_failed) },
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
                    actionMessage = err.message ?: context.getString(R.string.dashboard_soul_load_failed)
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
                    context.getString(R.string.dashboard_soul_saved, profileName)
                },
                onFailure = { err -> err.message ?: context.getString(R.string.dashboard_soul_save_failed) },
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
                onSuccess = { context.getString(R.string.dashboard_skills_update_started) },
                onFailure = { err -> err.message ?: context.getString(R.string.dashboard_skills_update_failed) },
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
                    context.getString(R.string.dashboard_profile_created, name)
                },
                onFailure = { err -> err.message ?: context.getString(R.string.dashboard_profile_create_failed) },
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
        // The visible section just loaded with a verified auth context —
        // reuse it for the sibling sweep (ticket availability unknown here,
        // but nothing in a section fetch consumes it) and fan the remaining
        // sections out concurrently instead of one preamble-laden fetch at
        // a time.
        val sharedPreamble = DashboardPreamble(
            status = loadedState.status,
            session = loadedState.session,
            gatewayTicketAvailable = null,
        )
        managementSections.forEach { prewarmSection ->
            val prewarmKey = payloadKeyFor(prewarmSection)
            if (prewarmKey == payloadKey) return@forEach
            val existingState = payloadStates[prewarmKey]
            if (existingState !is DashboardPayloadState.Loaded &&
                existingState !is DashboardPayloadState.Loading
            ) {
                launch {
                    loadDashboardSection(
                        targetSection = prewarmSection,
                        targetKey = prewarmKey,
                        foreground = false,
                        preamble = sharedPreamble,
                    )
                }
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
                                context.getString(R.string.dashboard_signin_no_session),
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
                    clearDashboardManageDiskCache(context.cacheDir)
                    forceReloadKey = null
                    reloadNonce += 1
                    // Standard voice rides this same cookie session — unlock the
                    // mic immediately rather than waiting for the next health tick.
                    connectionViewModel.refreshStandardVoice()
                    context.getString(R.string.dashboard_signed_in)
                },
                onFailure = { err -> err.message ?: context.getString(R.string.dashboard_signin_failed) },
            )
            actionInFlight = false
        }
    }

    pendingAction?.let { pending ->
        val isActivateProfile = pending.action.kind == DashboardActionKind.ActivateProfile
        val actionLabel = dashboardActionLabel(pending.action)
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = {
                Text(
                    if (isActivateProfile) stringResource(R.string.dashboard_make_default_title, pending.item.title)
                    else stringResource(R.string.dashboard_action_confirm_title, actionLabel, pending.item.title),
                )
            },
            text = {
                Text(
                    text = if (isActivateProfile) {
                        stringResource(R.string.dashboard_activate_profile_body, pending.item.title)
                    } else {
                        stringResource(R.string.dashboard_generic_action_body, pending.item.title)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAction = null
                        runAction(pending.item, pending.action)
                    },
                ) { Text(if (isActivateProfile) stringResource(R.string.dashboard_set_default) else actionLabel) }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text(stringResource(R.string.dashboard_cancel))
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
            title = { Text(stringResource(R.string.dashboard_input_action_title, dashboardActionLabel(pending.action), pending.item.title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isEnvKey) {
                            stringResource(R.string.dashboard_env_key_help)
                        } else {
                            stringResource(R.string.dashboard_profile_desc_help)
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
                        label = { Text(if (isEnvKey) stringResource(R.string.dashboard_value_label) else stringResource(R.string.dashboard_description_label)) },
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
                ) { Text(stringResource(R.string.dashboard_save)) }
            },
            dismissButton = {
                TextButton(onClick = { inputAction = null }) { Text(stringResource(R.string.dashboard_cancel)) }
            },
        )
    }

    if (showCreateProfile) {
        var newProfileName by remember { mutableStateOf("") }
        var newProfileDescription by remember { mutableStateOf("") }
        var cloneFromDefault by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showCreateProfile = false },
            title = { Text(stringResource(R.string.dashboard_new_profile_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.dashboard_name_label)) },
                    )
                    OutlinedTextField(
                        value = newProfileDescription,
                        onValueChange = { newProfileDescription = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.dashboard_description_optional)) },
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
                            text = stringResource(R.string.dashboard_clone_from_default),
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
                ) { Text(stringResource(R.string.dashboard_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showCreateProfile = false }) { Text(stringResource(R.string.dashboard_cancel)) }
            },
        )
    }

    expensiveModelConfirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = { expensiveModelConfirm = null },
            title = { Text(stringResource(R.string.dashboard_expensive_model_title)) },
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
                ) { Text(stringResource(R.string.dashboard_use_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { expensiveModelConfirm = null }) { Text(stringResource(R.string.dashboard_cancel)) }
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
        val clearedMsg = stringResource(R.string.dashboard_session_cleared)
        AlertDialog(
            onDismissRequest = { confirmClearDashboardSession = false },
            title = { Text(stringResource(R.string.dashboard_clear_session_title)) },
            text = {
                Text(
                    text = stringResource(R.string.dashboard_clear_session_body),
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
                            scope.launch {
                                clearDashboardManageDiskCache(context.cacheDir)
                            }
                            forceReloadKey = null
                            actionMessage = clearedMsg
                            reloadNonce += 1
                        }
                    },
                ) {
                    Text(stringResource(R.string.dashboard_clear_session_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearDashboardSession = false }) {
                    Text(stringResource(R.string.dashboard_cancel))
                }
            },
        )
    }

    oauthProvider?.let { provider ->
        val signedInMsg = stringResource(R.string.dashboard_signed_in)
        val signedInWithMsg = stringResource(R.string.dashboard_signed_in_with)
        DashboardOAuthSignInDialog(
            dashboardUrl = dashboardUrl,
            provider = provider,
            cookieStoreFactory = cookieStoreFactory,
            onDismiss = { oauthProvider = null },
            onAuthenticated = { session ->
                oauthProvider = null
                payloadStates.clear()
                refreshingPayloads.clear()
                scope.launch {
                    clearDashboardManageDiskCache(context.cacheDir)
                }
                forceReloadKey = null
                actionMessage = session.provider?.let { signedInWithMsg.format(it) } ?: signedInMsg
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
                title = { Text(stringResource(R.string.dashboard_title)) },
                navigationIcon = {
                    RelayChromeIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.dashboard_back),
                        onClick = onBack,
                    )
                },
                actions = {
                    RelayChromeIconButton(
                        icon = Icons.Filled.Code,
                        contentDescription = stringResource(R.string.dashboard_terminal),
                        onClick = onNavigateToTerminal,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    RelayChromeIconButton(
                        icon = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.dashboard_settings),
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
                            contentDescription = stringResource(R.string.dashboard_refresh),
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
                                    text = { Text(tab.displayLabel()) },
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                        if (isRefreshing && payloadState !is DashboardPayloadState.Loading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            // Crossfade Loading→Loaded (and →Error) so the body
                            // fades in rather than snapping. Keyed on the payload
                            // state, so a stale-while-revalidate refresh also
                            // cross-dissolves instead of flashing.
                            Crossfade(
                                targetState = payloadState,
                                animationSpec = tween(200),
                                label = "managePayload",
                            ) { state ->
                            when (state) {
                                DashboardPayloadState.Idle,
                                DashboardPayloadState.Loading -> LoadingBody(section.displayLabel())
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
                                            // Always confirm: this flips the
                                            // server's persistent active agent for
                                            // every client, unlike the ephemeral
                                            // per-conversation switch in chat.
                                            DashboardActionKind.ActivateProfile ->
                                                pendingAction = PendingDashboardAction(item, action)
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
                        actionInFlight = actionInFlight,
                        actionMessage = actionMessage,
                        onClearSession = { confirmClearDashboardSession = true },
                        onSignIn = ::submitDashboardSignIn,
                        onOAuthSignIn = { provider -> oauthProvider = provider },
                        onNavigateToConnections = onNavigateToConnections,
                        onSelectSection = { sectionPick ->
                            managementSections.indexOf(sectionPick)
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

@Composable
private fun manageTileSpec(section: DashboardManagementSection): ManageTileSpec = when (section) {
    DashboardManagementSection.Profiles -> ManageTileSpec(
        icon = Icons.Filled.Person,
        title = stringResource(R.string.dashboard_tile_profiles_title),
        subtitle = stringResource(R.string.dashboard_tile_profiles_sub),
    )
    DashboardManagementSection.Skills -> ManageTileSpec(
        icon = Icons.Filled.AutoAwesome,
        title = stringResource(R.string.dashboard_tile_skills_title),
        subtitle = stringResource(R.string.dashboard_tile_skills_sub),
    )
    DashboardManagementSection.Cron -> ManageTileSpec(
        icon = Icons.Filled.Schedule,
        title = stringResource(R.string.dashboard_tile_cron_title),
        subtitle = stringResource(R.string.dashboard_tile_cron_sub),
    )
    DashboardManagementSection.Mcp -> ManageTileSpec(
        icon = Icons.Filled.Code,
        title = stringResource(R.string.dashboard_tile_mcp_title),
        subtitle = stringResource(R.string.dashboard_tile_mcp_sub),
    )
    DashboardManagementSection.Catalog -> ManageTileSpec(
        icon = Icons.Filled.AutoAwesome,
        title = stringResource(R.string.dashboard_tile_catalog_title),
        subtitle = stringResource(R.string.dashboard_tile_catalog_sub),
    )
    DashboardManagementSection.Models -> ManageTileSpec(
        icon = Icons.Filled.Tune,
        title = stringResource(R.string.dashboard_tile_models_title),
        subtitle = stringResource(R.string.dashboard_tile_models_sub),
    )
    DashboardManagementSection.Keys -> ManageTileSpec(
        icon = Icons.Filled.Key,
        title = stringResource(R.string.dashboard_tile_keys_title),
        subtitle = stringResource(R.string.dashboard_tile_keys_sub),
    )
    DashboardManagementSection.Config -> ManageTileSpec(
        icon = Icons.Filled.Tune,
        title = stringResource(R.string.dashboard_tile_config_title),
        subtitle = stringResource(R.string.dashboard_tile_config_sub),
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
    actionInFlight: Boolean,
    actionMessage: String?,
    onClearSession: () -> Unit,
    onSignIn: (String, String, String) -> Unit,
    onOAuthSignIn: (DashboardAuthProvider) -> Unit,
    onNavigateToConnections: () -> Unit,
    onSelectSection: (DashboardManagementSection) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            RelaySectionCaption(
                title = stringResource(R.string.dashboard_hub_title),
                meta = stringResource(R.string.dashboard_hub_meta),
            )
        }
        item {
            // KPI strip — words, not glyphs. "ok / ... / ! / -" required the
            // user to already know what each symbol meant; state words plus
            // a tone color answer "is Manage healthy?" at a glance, and the
            // server version confirms WHICH server answered (useful when
            // routes roam between LAN and Tailscale hosts).
            val signInNeeded = status?.authRequired == true && authenticated != true
            val dashboardWord = when {
                payloadState is DashboardPayloadState.Loading ||
                    payloadState is DashboardPayloadState.Idle -> DashboardHealthWord.Loading
                signInNeeded -> DashboardHealthWord.SignIn
                payloadState is DashboardPayloadState.Error && status == null -> DashboardHealthWord.Offline
                payloadState is DashboardPayloadState.Error -> DashboardHealthWord.Error
                else -> DashboardHealthWord.Ready
            }
            val dashboardTone = when (dashboardWord) {
                DashboardHealthWord.Ready -> RelayRefresh.Green
                DashboardHealthWord.SignIn -> RelayRefresh.Amber
                DashboardHealthWord.Offline, DashboardHealthWord.Error -> RelayRefresh.Danger
                DashboardHealthWord.Loading -> RelayRefresh.Muted
            }
            val dashboardWordText = when (dashboardWord) {
                DashboardHealthWord.Loading -> stringResource(R.string.dashboard_health_loading)
                DashboardHealthWord.SignIn -> stringResource(R.string.dashboard_health_signin)
                DashboardHealthWord.Offline -> stringResource(R.string.dashboard_health_offline)
                DashboardHealthWord.Error -> stringResource(R.string.dashboard_health_error)
                DashboardHealthWord.Ready -> stringResource(R.string.dashboard_health_ready)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RelayMetricCard(
                    value = if (loadedCount > 0) loadedCount.toString() else "—",
                    label = section.lowercaseLabel(),
                    modifier = Modifier.weight(1f),
                )
                RelayMetricCard(
                    value = dashboardWordText,
                    label = stringResource(R.string.dashboard_metric_dashboard),
                    modifier = Modifier.weight(1f),
                    valueColor = dashboardTone,
                )
                RelayMetricCard(
                    value = status?.version ?: "—",
                    label = stringResource(R.string.dashboard_metric_server),
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
            )
        }
        val signInStatus = status
        if (signInStatus?.authRequired == true && authenticated != true) {
            item {
                DashboardSignInCard(
                    dashboardUrl = dashboardUrl,
                    routeHint = routeHint,
                    providers = signInStatus.authProviderDetails,
                    actionInFlight = actionInFlight,
                    actionMessage = actionMessage,
                    onSignIn = onSignIn,
                    onOAuthSignIn = onOAuthSignIn,
                )
            }
        }
        item {
            RelayNavTile(
                icon = Icons.Filled.Link,
                title = stringResource(R.string.dashboard_nav_connections_title),
                subtitle = stringResource(R.string.dashboard_nav_connections_sub),
                onClick = onNavigateToConnections,
            )
        }
        item {
            RelayNavTile(
                icon = Icons.Filled.Person,
                title = stringResource(R.string.dashboard_tile_profiles_title),
                subtitle = stringResource(R.string.dashboard_tile_profiles_sub),
                onClick = { onSelectSection(DashboardManagementSection.Profiles) },
            )
        }
        item {
            RelayNavTile(
                icon = Icons.Filled.AutoAwesome,
                title = stringResource(R.string.dashboard_tile_skills_title),
                subtitle = stringResource(R.string.dashboard_tile_skills_sub),
                onClick = { onSelectSection(DashboardManagementSection.Skills) },
            )
        }
        item {
            RelayNavTile(
                icon = Icons.Filled.Schedule,
                title = stringResource(R.string.dashboard_tile_cron_title),
                subtitle = stringResource(R.string.dashboard_tile_cron_sub),
                onClick = { onSelectSection(DashboardManagementSection.Cron) },
            )
        }
        item {
            RelayNavTile(
                icon = Icons.Filled.Code,
                title = stringResource(R.string.dashboard_tile_mcp_title),
                subtitle = stringResource(R.string.dashboard_tile_mcp_sub),
                onClick = { onSelectSection(DashboardManagementSection.Mcp) },
            )
        }
        item {
            RelayNavTile(
                icon = Icons.Filled.AutoAwesome,
                title = stringResource(R.string.dashboard_tile_catalog_title),
                subtitle = stringResource(R.string.dashboard_tile_catalog_sub),
                onClick = { onSelectSection(DashboardManagementSection.Catalog) },
            )
        }
        item {
            RelayNavTile(
                icon = Icons.Filled.Tune,
                title = stringResource(R.string.dashboard_tile_models_title),
                subtitle = stringResource(R.string.dashboard_tile_models_sub),
                onClick = { onSelectSection(DashboardManagementSection.Models) },
            )
        }
        item {
            RelayNavTile(
                icon = Icons.Filled.Key,
                title = stringResource(R.string.dashboard_tile_keys_title),
                subtitle = stringResource(R.string.dashboard_tile_keys_sub),
                onClick = { onSelectSection(DashboardManagementSection.Keys) },
            )
        }
    }
}

/** Health-word enum backing the KPI strip; drives both the label and the tone. */
private enum class DashboardHealthWord { Loading, SignIn, Offline, Error, Ready }

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
            label = stringResource(R.string.dashboard_overview_label),
        )
    }
}

/**
 * Two-line dashboard status panel. The old single-line banner crammed
 * auth state, identity, URL, route, and checked-time into one ellipsized
 * Text fighting two trailing buttons for width — the interesting tail
 * (URL + route) was the first thing to get cut. Line 1 carries state +
 * identity with the lone Sign out action; line 2 carries the target
 * (URL · route · checked). The "Connection" button is gone: the
 * Connections nav tile rendered directly below this panel already does
 * exactly that.
 */
@Composable
private fun DashboardConnectionHeader(
    dashboardUrl: String,
    routeHint: String?,
    status: DashboardStatus?,
    session: DashboardAuthSession?,
    authenticated: Boolean?,
    lastCheckedAtMillis: Long?,
    onClearSession: () -> Unit,
) {
    val context = LocalContext.current
    val authLabel = when {
        status == null -> stringResource(R.string.dashboard_status_checking)
        status.authRequired && authenticated == true -> stringResource(R.string.dashboard_status_signed_in)
        status.authRequired -> stringResource(R.string.dashboard_status_signin_required)
        else -> stringResource(R.string.dashboard_status_available)
    }
    val identity = if (authenticated == true) {
        session?.username ?: session?.provider?.let {
            stringResource(R.string.dashboard_provider_prefix, it)
        }
    } else {
        null
    }
    val primaryLine = listOfNotNull(authLabel, identity).joinToString(" · ")
    val noUrlLabel = stringResource(R.string.dashboard_no_url)
    val routeLabel = stringResource(R.string.dashboard_route_suffix)
    val checkedLabel = stringResource(R.string.dashboard_checked_at)
    val secondaryLine = listOfNotNull(
        dashboardUrl.ifBlank { noUrlLabel },
        routeHint?.let { routeLabel.format(it) },
        lastCheckedAtMillis?.let { checkedLabel.format(formatDashboardCheckedAt(context, it)) },
    ).joinToString(" · ")
    val signInNeeded = status?.authRequired == true && authenticated != true
    val statusColor = when {
        status == null -> RelayRefresh.Amber
        signInNeeded -> RelayRefresh.Danger
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
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = primaryLine,
                style = relayMetadataStyle(),
                color = if (signInNeeded) RelayRefresh.Danger else RelayRefresh.Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = secondaryLine,
                style = relayMetadataStyle(),
                color = RelayRefresh.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (authenticated == true) {
            TextButton(
                onClick = onClearSession,
                modifier = Modifier.height(30.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = RelayRefresh.Relay,
                ),
            ) {
                Text(stringResource(R.string.dashboard_sign_out))
            }
        }
    }
}

private fun formatDashboardCheckedAt(context: android.content.Context, checkedAtMillis: Long): String {
    val deltaMs = System.currentTimeMillis() - checkedAtMillis
    return when {
        deltaMs in 0 until 60_000L -> context.getString(R.string.dashboard_just_now)
        deltaMs in 60_000L until 3_600_000L ->
            context.getString(R.string.dashboard_minutes_ago, (deltaMs / 60_000L).toInt())
        deltaMs in 3_600_000L until 86_400_000L ->
            context.getString(R.string.dashboard_hours_ago, (deltaMs / 3_600_000L).toInt())
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

/**
 * The auth context every section fetch needs: dashboard status (with
 * provider details merged in when auth is on), the cookie session, and
 * whether a gateway ws-ticket could be minted. Identical for all 8 sections
 * of a sweep — fetch it ONCE via [fetchDashboardPreamble] and pass it down.
 * Re-running it per section used to turn a full Manage load into ~40
 * sequential round trips (8 sections × 4 preamble calls + payload), which
 * read as 5–10 seconds of "still loading" over Tailscale.
 */
private class DashboardPreamble(
    val status: DashboardStatus?,
    val session: DashboardAuthSession?,
    val gatewayTicketAvailable: Boolean?,
) {
    val signInRequired: Boolean
        get() = status?.authRequired == true && session?.authenticated != true
}

private suspend fun fetchDashboardPreamble(client: DashboardApiClient): DashboardPreamble {
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
    return DashboardPreamble(status, session, gatewayTicketAvailable)
}

/**
 * One full section fetch, summarized into a [DashboardPayloadState]. Shared
 * by the screen's loader and [prewarmDashboardManage]; [recordStatus]
 * receives (status, session, gatewayTicketAvailable) so the screen can
 * mirror the snapshot into the connection record while the pre-warm passes
 * a no-op. When [preamble] is null the auth context is fetched fresh —
 * background sweeps should fetch it once and share it.
 */
private suspend fun fetchDashboardSectionState(
    clientFactory: () -> DashboardApiClient,
    targetSection: DashboardManagementSection,
    preamble: DashboardPreamble? = null,
    recordStatus: (DashboardStatus?, DashboardAuthSession?, Boolean?) -> Unit = { _, _, _ -> },
): DashboardPayloadState = withDashboardClient(clientFactory) { client ->
    fetchDashboardSectionStateWith(
        client = client,
        targetSection = targetSection,
        preamble = preamble,
        recordStatus = recordStatus,
    )
}

/** Core of [fetchDashboardSectionState] against an already-built client. */
private suspend fun fetchDashboardSectionStateWith(
    client: DashboardApiClient,
    targetSection: DashboardManagementSection,
    preamble: DashboardPreamble? = null,
    recordStatus: (DashboardStatus?, DashboardAuthSession?, Boolean?) -> Unit = { _, _, _ -> },
): DashboardPayloadState {
    val resolved = preamble ?: fetchDashboardPreamble(client)
    recordStatus(resolved.status, resolved.session, resolved.gatewayTicketAvailable)
    return if (resolved.signInRequired) {
        DashboardPayloadState.Error("Dashboard sign-in required", status = resolved.status)
    } else {
        val result = client.getJsonElement(targetSection.path)
        result.fold(
            onSuccess = { root ->
                DashboardPayloadState.Loaded(
                    status = resolved.status,
                    session = resolved.session,
                    items = summarize(targetSection, root),
                    rawSummary = summarizeRoot(root),
                    fetchedAtMillis = System.currentTimeMillis(),
                )
            },
            onFailure = { err ->
                DashboardPayloadState.Error(
                    message = err.message ?: "Dashboard request failed",
                    status = resolved.status,
                )
            },
        )
    }
}

/**
 * App-start (and route-handoff) pre-warm for the Manage tab. Fills cold
 * cache keys and quietly re-fetches stale ones (disk-hydrated entries from
 * a previous process land here) — it never replaces a Loaded entry with
 * anything but a NEWER Loaded, never marks anything Loading (so it can't
 * fight the open screen), and never surfaces errors. An unreachable or
 * unauthenticated preamble aborts the whole sweep: if the dashboard isn't
 * answering or wants a sign-in, eight more requests won't change that, and
 * the screen's own loader owns error presentation.
 *
 * The auth preamble is fetched ONCE and the per-section payload GETs run
 * concurrently over one client — the first iteration re-ran the preamble
 * per section, sequentially: ~40 round trips ≈ 5–10s over Tailscale.
 *
 * Called from RelayApp when the active connection's persisted snapshot says
 * the dashboard was reachable and signed-in (or auth-free), so a cold app
 * start lands on an already-populated Manage tab.
 */
internal suspend fun prewarmDashboardManage(
    cookieStore: DashboardCookieStore,
    connectionId: String,
    dashboardUrl: String,
    /** When non-null, the sweep's results are mirrored to the disk cache. */
    cacheDir: java.io.File? = null,
) {
    if (dashboardUrl.isBlank()) return
    fun needsWarm(targetSection: DashboardManagementSection): Boolean {
        val key = dashboardPayloadKey(connectionId, dashboardUrl, targetSection.path)
        return when (val existing = DashboardPayloadCache.states[key]) {
            is DashboardPayloadState.Loading -> false
            is DashboardPayloadState.Loaded ->
                System.currentTimeMillis() - existing.fetchedAtMillis >=
                    DashboardPayloadCache.FRESH_WINDOW_MS
            else -> true
        }
    }
    if (managementSections.none(::needsWarm)) return
    // ONE client (and the caller's ONE shared cookie store) for the whole
    // sweep. The first iteration of this function built a fresh client +
    // encrypted cookie store per section: 8 Keystore keyset builds, each
    // holding Tink's process-global lock for seconds on StrongBox devices,
    // which starved main-thread keystore users and froze the UI at startup.
    val client = withContext(Dispatchers.IO) {
        DashboardApiClient(
            baseUrl = dashboardUrl,
            okHttpClient = DashboardApiClient.defaultClient(cookieStore = cookieStore),
        )
    }
    try {
        val preamble = try {
            fetchDashboardPreamble(client)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        if (preamble.status == null || preamble.signInRequired) return
        kotlinx.coroutines.coroutineScope {
            managementSections.filter(::needsWarm).forEach { targetSection ->
                launch {
                    val key = dashboardPayloadKey(
                        connectionId,
                        dashboardUrl,
                        targetSection.path,
                    )
                    if (!needsWarm(targetSection)) return@launch
                    val state = try {
                        fetchDashboardSectionStateWith(
                            client = client,
                            targetSection = targetSection,
                            preamble = preamble,
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (state is DashboardPayloadState.Loaded) {
                        DashboardPayloadCache.states[key] = state
                    }
                }
            }
        }
        if (cacheDir != null) {
            persistDashboardManageCache(cacheDir)
        }
    } finally {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            client.shutdown()
        }
    }
}

/**
 * Cold-load skeleton: ONE progress indicator + quiet ghost cards. The old
 * version stacked four progress bars with fake narrative labels ("Checking
 * dashboard session"…), which read as three different things being broken.
 * With the process-lifetime payload cache this body only appears on the
 * true first load per connection/route — every later entry shows cached
 * content with a thin refresh bar instead.
 */
@Composable
private fun LoadingBody(sectionLabel: String) {
    val pulse = rememberInfiniteTransition(label = "manage-skeleton-pulse")
    val ghostAlpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "manage-skeleton-alpha",
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.dashboard_loading_section, sectionLabel.lowercase()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        repeat(3) {
            GhostSummaryCard(blockAlpha = ghostAlpha)
        }
    }
}

/** One content-shaped ghost card — no text, no per-card spinner. */
@Composable
private fun GhostSummaryCard(blockAlpha: Float) {
    val blockColor = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = 0.42f)
                    .height(14.dp)
                    .background(
                        blockColor.copy(alpha = 0.18f * blockAlpha),
                        RoundedCornerShape(4.dp),
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = 0.72f)
                    .height(10.dp)
                    .background(
                        blockColor.copy(alpha = 0.12f * blockAlpha),
                        RoundedCornerShape(4.dp),
                    ),
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
    val routeSuffix = routeHint?.let {
        stringResource(R.string.dashboard_target_line_route_suffix, it)
    } ?: ""
    Text(
        text = stringResource(R.string.dashboard_target_line, dashboardUrl) + routeSuffix,
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
                        text = stringResource(R.string.dashboard_error_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    // Name the exact target: the dashboard (:9119) is a
                    // separate server from the API (:8642), so "chat works"
                    // proves nothing about this URL — and on a moved route
                    // the host may differ from what the user configured.
                    val routeSuffix = routeHint?.let {
                        stringResource(R.string.dashboard_error_route_suffix, it)
                    } ?: ""
                    Text(
                        text = stringResource(R.string.dashboard_error_target, dashboardUrl) + routeSuffix,
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
                        Text(stringResource(R.string.dashboard_retry))
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
        val sectionActions: List<Pair<DashboardSectionAction, String>> = when (section) {
            DashboardManagementSection.Models -> listOf(
                DashboardSectionAction.ChangeMainModel to stringResource(R.string.dashboard_section_action_change_main_model),
            )
            DashboardManagementSection.Profiles -> listOf(
                DashboardSectionAction.CreateProfile to stringResource(R.string.dashboard_section_action_new_profile),
            )
            DashboardManagementSection.Skills -> listOf(
                DashboardSectionAction.BrowseSkillsHub to stringResource(R.string.dashboard_section_action_browse_hub),
                DashboardSectionAction.UpdateSkillsHub to stringResource(R.string.dashboard_section_action_update_installed),
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
                            text = stringResource(R.string.dashboard_empty_section, section.lowercaseLabel()),
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

/**
 * Resolve the user-facing label for a [DashboardItemAction] from its [kind].
 * The persisted `action.label` field is a stable English identifier and is
 * NOT displayed directly — every display site routes through this helper so
 * the rendered text is always localized.
 */
@Composable
private fun dashboardActionLabel(action: DashboardItemAction): String =
    dashboardActionLabel(action.kind)

@Composable
private fun dashboardActionLabel(kind: DashboardActionKind): String = when (kind) {
    DashboardActionKind.SetEnvKey -> stringResource(R.string.dashboard_action_set)
    DashboardActionKind.RevealEnvKey -> stringResource(R.string.dashboard_action_reveal)
    DashboardActionKind.ClearEnvKey -> stringResource(R.string.dashboard_action_clear)
    DashboardActionKind.InstallMcpCatalog -> stringResource(R.string.dashboard_action_install)
    DashboardActionKind.ViewCronRuns -> stringResource(R.string.dashboard_action_runs)
    DashboardActionKind.ResumeCron -> stringResource(R.string.dashboard_action_resume)
    DashboardActionKind.PauseCron -> stringResource(R.string.dashboard_action_pause)
    DashboardActionKind.TriggerCron -> stringResource(R.string.dashboard_action_run_now)
    DashboardActionKind.DeleteCron -> stringResource(R.string.dashboard_action_delete)
    DashboardActionKind.EnableMcp -> stringResource(R.string.dashboard_action_enable)
    DashboardActionKind.DisableMcp -> stringResource(R.string.dashboard_action_disable)
    DashboardActionKind.TestMcp -> stringResource(R.string.dashboard_action_test)
    DashboardActionKind.RemoveMcp -> stringResource(R.string.dashboard_action_remove)
    DashboardActionKind.ViewProfileSoul -> stringResource(R.string.dashboard_action_soul)
    DashboardActionKind.EditProfileSoul -> stringResource(R.string.dashboard_action_edit_soul)
    DashboardActionKind.ActivateProfile -> stringResource(R.string.dashboard_action_use)
    DashboardActionKind.EditProfileDescription -> stringResource(R.string.dashboard_action_describe)
    DashboardActionKind.SetProfileModel -> stringResource(R.string.dashboard_action_model)
    DashboardActionKind.EnableSkill -> stringResource(R.string.dashboard_action_enable)
    DashboardActionKind.DisableSkill -> stringResource(R.string.dashboard_action_disable)
    DashboardActionKind.DeleteProfile -> stringResource(R.string.dashboard_action_delete)
}

/**
 * Context-only variant of [dashboardActionLabel] for use inside non-composable
 * scopes (coroutine bodies, suspend funs) — resolves via [context.getString].
 */
private fun dashboardActionLabel(context: android.content.Context, kind: DashboardActionKind): String =
    when (kind) {
        DashboardActionKind.SetEnvKey -> context.getString(R.string.dashboard_action_set)
        DashboardActionKind.RevealEnvKey -> context.getString(R.string.dashboard_action_reveal)
        DashboardActionKind.ClearEnvKey -> context.getString(R.string.dashboard_action_clear)
        DashboardActionKind.InstallMcpCatalog -> context.getString(R.string.dashboard_action_install)
        DashboardActionKind.ViewCronRuns -> context.getString(R.string.dashboard_action_runs)
        DashboardActionKind.ResumeCron -> context.getString(R.string.dashboard_action_resume)
        DashboardActionKind.PauseCron -> context.getString(R.string.dashboard_action_pause)
        DashboardActionKind.TriggerCron -> context.getString(R.string.dashboard_action_run_now)
        DashboardActionKind.DeleteCron -> context.getString(R.string.dashboard_action_delete)
        DashboardActionKind.EnableMcp -> context.getString(R.string.dashboard_action_enable)
        DashboardActionKind.DisableMcp -> context.getString(R.string.dashboard_action_disable)
        DashboardActionKind.TestMcp -> context.getString(R.string.dashboard_action_test)
        DashboardActionKind.RemoveMcp -> context.getString(R.string.dashboard_action_remove)
        DashboardActionKind.ViewProfileSoul -> context.getString(R.string.dashboard_action_soul)
        DashboardActionKind.EditProfileSoul -> context.getString(R.string.dashboard_action_edit_soul)
        DashboardActionKind.ActivateProfile -> context.getString(R.string.dashboard_action_use)
        DashboardActionKind.EditProfileDescription -> context.getString(R.string.dashboard_action_describe)
        DashboardActionKind.SetProfileModel -> context.getString(R.string.dashboard_action_model)
        DashboardActionKind.EnableSkill -> context.getString(R.string.dashboard_action_enable)
        DashboardActionKind.DisableSkill -> context.getString(R.string.dashboard_action_disable)
        DashboardActionKind.DeleteProfile -> context.getString(R.string.dashboard_action_delete)
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
                            Text(dashboardActionLabel(action))
                        }
                    }
                    if (overflowActions.isNotEmpty()) {
                        Box {
                            var menuOpen by remember { mutableStateOf(false) }
                            OutlinedButton(
                                onClick = { menuOpen = true },
                                enabled = !actionInFlight,
                            ) {
                                Text(stringResource(R.string.dashboard_more))
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                overflowActions.forEach { action ->
                                    DropdownMenuItem(
                                        text = { Text(dashboardActionLabel(action)) },
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
                text = stringResource(R.string.dashboard_signin_required_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.dashboard_signin_required_body, dashboardUrl),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (routeHint != null) {
                // Same per-host cookie reality the voice gate explains:
                // a sign-in on the home host does not authenticate this
                // one. Without this strip, a user who signed in at home
                // reads the prompt above as a broken session.
                Text(
                    text = stringResource(R.string.dashboard_signin_route_hint, routeHint),
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
                    Text(stringResource(R.string.dashboard_signin_with_provider, provider.displayName ?: provider.name))
                }
            }

            if (passwordProvider != null || providers.isEmpty()) {
                if (redirectProviders.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))
                }
                Text(
                    text = passwordProvider?.displayName ?: stringResource(R.string.dashboard_username_password),
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.dashboard_username)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.dashboard_password)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Button(
                    onClick = { onSignIn(passwordProvider?.name ?: "basic", username, password) },
                    enabled = !actionInFlight && username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (actionInFlight) stringResource(R.string.dashboard_signing_in) else stringResource(R.string.dashboard_sign_in))
                }
            } else if (redirectProviders.isEmpty()) {
                Text(
                    text = stringResource(R.string.dashboard_no_supported_provider),
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
    cookieStoreFactory: () -> DashboardCookieStore,
    onDismiss: () -> Unit,
    onAuthenticated: (DashboardAuthSession) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusText by remember {
        mutableStateOf(context.getString(R.string.dashboard_oauth_initial_status))
    }
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
        statusText = context.getString(R.string.dashboard_oauth_verifying)
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
                    statusText = context.getString(R.string.dashboard_oauth_not_accepted)
                }
            } catch (e: Exception) {
                checking = false
                val message = e.message ?: context.getString(R.string.dashboard_oauth_verify_failed)
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
                            text = stringResource(R.string.dashboard_signin_with_provider, provider.displayName ?: provider.name),
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
                            contentDescription = stringResource(R.string.dashboard_close_signin),
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
                Text(stringResource(R.string.dashboard_done))
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
    val context = LocalContext.current
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
                    error = context.getString(R.string.dashboard_no_model_options)
                }
            },
            onFailure = { err -> error = err.message ?: context.getString(R.string.dashboard_model_options_load_failed) },
        )
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (target) {
                    is ModelPickerTarget.Main -> stringResource(R.string.dashboard_main_model_title)
                    is ModelPickerTarget.Profile -> stringResource(R.string.dashboard_profile_model_title, target.name)
                },
            )
        },
        text = {
            when {
                loading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.dashboard_loading_provider_catalog))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                error != null -> Text(
                    text = error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Column {
                    Text(
                        text = stringResource(R.string.dashboard_model_picker_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        providers.forEach { provider ->
                            item(key = "provider-${provider.id}") {
                                val keyMissingSuffix = if (provider.authenticated) "" else stringResource(R.string.dashboard_key_missing_suffix)
                                Text(
                                    text = provider.label + keyMissingSuffix,
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
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dashboard_cancel)) }
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
    val context = LocalContext.current
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
                onFailure = { err -> error = err.message ?: context.getString(R.string.dashboard_hub_search_failed) },
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
                Text(stringResource(R.string.dashboard_skills_hub_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.dashboard_skills_hub_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sourcesLine?.let { line ->
                    Text(
                        text = stringResource(R.string.dashboard_skills_hub_sources, line),
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
                        label = { Text(stringResource(R.string.dashboard_search_skills)) },
                    )
                    Button(onClick = { runSearch() }, enabled = !searching && query.isNotBlank()) {
                        Text(stringResource(R.string.dashboard_search))
                    }
                }
                if (searching) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.dashboard_hub_searching),
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
                        text = stringResource(R.string.dashboard_hub_no_matches, query.trim()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showingFeatured && results.isNotEmpty() && !searching) {
                    Text(
                        text = stringResource(R.string.dashboard_hub_featured),
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
                                    stringResource(R.string.dashboard_hub_installed).takeIf { result.installedName != null },
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
                                                            title = context.getString(R.string.dashboard_hub_skill_md_title, result.name),
                                                            body = body.take(6_000),
                                                        ),
                                                    )
                                                },
                                                onFailure = { err ->
                                                    onMessage(err.message ?: context.getString(R.string.dashboard_hub_preview_failed))
                                                },
                                            )
                                        }
                                    },
                                    enabled = !busy,
                                ) { Text(stringResource(R.string.dashboard_hub_preview)) }
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
                                                            context.getString(R.string.dashboard_hub_uninstall_started, result.installedName)
                                                        },
                                                        onFailure = { err ->
                                                            err.message ?: context.getString(R.string.dashboard_hub_uninstall_failed)
                                                        },
                                                    ),
                                                )
                                            }
                                        },
                                        enabled = !busy,
                                    ) { Text(stringResource(R.string.dashboard_hub_uninstall)) }
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
                                                            context.getString(R.string.dashboard_hub_install_started, result.name)
                                                        },
                                                        onFailure = { err ->
                                                            err.message ?: context.getString(R.string.dashboard_hub_install_failed)
                                                        },
                                                    ),
                                                )
                                            }
                                        },
                                        enabled = !busy,
                                    ) { Text(stringResource(R.string.dashboard_hub_install)) }
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
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dashboard_close)) }
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
                    text = stringResource(R.string.dashboard_soul_editor_title, editor.profileName),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!editor.exists) {
                    Text(
                        text = stringResource(R.string.dashboard_soul_editor_missing),
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
                        text = stringResource(R.string.dashboard_soul_editor_chars, content.length),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.dashboard_cancel)) }
                        Button(onClick = { onSave(content) }, enabled = !saving) {
                            Text(if (saving) stringResource(R.string.dashboard_saving) else stringResource(R.string.dashboard_save))
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
    return when (section) {
        DashboardManagementSection.Skills -> root.arrayItems("skills", "items")
            ?.mapIndexed { index, item -> summarizeObjectItem(item, "Skill ${index + 1}") }
            ?: emptyList()
        DashboardManagementSection.Cron -> root.arrayItems("jobs", "items")
            ?.mapIndexed { index, item -> summarizeObjectItem(item, "Job ${index + 1}") }
            ?: emptyList()
        DashboardManagementSection.Mcp -> root.arrayItems("servers", "items")
            ?.mapIndexed { index, item -> summarizeObjectItem(item, "Server ${index + 1}") }
            ?: emptyList()
        DashboardManagementSection.Catalog -> root.arrayItems("entries", "catalog", "items")
            ?.mapIndexed { index, item -> summarizeObjectItem(item, "Catalog ${index + 1}") }
            ?: emptyList()
        DashboardManagementSection.Profiles -> {
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
        DashboardManagementSection.Models -> summarizeKeyValueOrList(root, "Model")
        DashboardManagementSection.Keys -> summarizeEnvVars(root)
        DashboardManagementSection.Config -> summarizeKeyValueOrList(root, "Config")
    }
}
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

private fun detailBodyFor(
    context: android.content.Context,
    kind: DashboardActionKind,
    root: JsonObject,
): String {
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
                root.booleanField("exists") == false -> context.getString(R.string.dashboard_soul_not_exists)
                else -> compactJsonLines(root)
            }
        }
        DashboardActionKind.ViewCronRuns -> {
            val runs = root.arrayField("runs", "items", "sessions")
            if (runs == null || runs.isEmpty()) {
                context.getString(R.string.dashboard_no_recent_runs)
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
