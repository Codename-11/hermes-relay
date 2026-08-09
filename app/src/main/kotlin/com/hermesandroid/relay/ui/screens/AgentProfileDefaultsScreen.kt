package com.hermesandroid.relay.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.upstream.applyConfigEdits
import com.hermesandroid.relay.network.upstream.configValueAt
import com.hermesandroid.relay.network.upstream.parseConfigSchema
import com.hermesandroid.relay.ui.theme.LocalBrand
import com.hermesandroid.relay.ui.theme.gradientBorder
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private const val KEY_REASONING = "agent.reasoning_effort"
private const val KEY_SPEED = "agent.service_tier"
private const val KEY_PERSONALITY = "display.personality"
private const val KEY_APPROVAL = "approvals.mode"
private const val KEY_MEMORY = "memory.provider"

internal data class AgentProfileDefaultsTarget(
    val connectionId: String,
    val connectionLabel: String,
    val dashboardUrl: String,
    val profileName: String,
)

internal data class AgentProfileModelChoice(
    val provider: String,
    val model: String,
    val reasoningSupported: Boolean = true,
    val reasoningEfforts: List<String>? = null,
    val fastSupported: Boolean = false,
) {
    val label: String get() = if (provider.isBlank()) model else "$provider / $model"
    fun sameIdentity(other: AgentProfileModelChoice): Boolean =
        provider == other.provider && model == other.model
}

private data class AgentProfileDefaultsSnapshot(
    val config: JsonObject,
    val model: AgentProfileModelChoice,
    val modelChoices: List<AgentProfileModelChoice>,
    val personalityChoices: List<String>,
)

internal data class AgentProfileDefaultsPreviewState(
    val target: AgentProfileDefaultsTarget,
    val config: JsonObject,
    val model: AgentProfileModelChoice,
    val modelChoices: List<AgentProfileModelChoice>,
    val personalityChoices: List<String>,
    val profile: Profile,
    val gatewayReady: Boolean = true,
    val pendingConfig: Map<String, JsonElement> = emptyMap(),
)

private enum class DefaultsPicker {
    Profile,
    Model,
    Reasoning,
    Speed,
    Personality,
    Approval,
}

private data class ReviewChange(val label: String, val value: String)

/**
 * Profile-default editor backed only by standard Dashboard REST contracts.
 *
 * The target is frozen to a connection id + profile name. Chat/Gateway setters
 * are deliberately not used: without a live session they can persist against
 * the launch profile, which is exactly the cross-profile trap this screen exists
 * to remove.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentProfileDefaultsScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onOpenVoiceDefaults: (String) -> Unit,
) = AgentProfileDefaultsScreenContent(
    connectionViewModel = connectionViewModel,
    onBack = onBack,
    onOpenVoiceDefaults = onOpenVoiceDefaults,
)

@Composable
internal fun AgentProfileDefaultsPreviewScreen(
    connectionViewModel: ConnectionViewModel,
    state: AgentProfileDefaultsPreviewState,
) = AgentProfileDefaultsScreenContent(
    connectionViewModel = connectionViewModel,
    onBack = {},
    onOpenVoiceDefaults = {},
    previewState = state,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentProfileDefaultsScreenContent(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onOpenVoiceDefaults: (String) -> Unit,
    previewState: AgentProfileDefaultsPreviewState? = null,
) {
    val context = LocalContext.current
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val dashboardUrl by connectionViewModel.effectiveDashboardUrl.collectAsState()
    val effectiveProfileName by connectionViewModel.effectiveSessionProfileName.collectAsState()
    val profileSelectionSettled by connectionViewModel.profileSelectionSettled.collectAsState()
    val profiles by connectionViewModel.agentProfiles.collectAsState()
    val gatewayAvailability by connectionViewModel.gatewayAvailability.collectAsState()

    var target by remember(previewState) { mutableStateOf(previewState?.target) }
    var snapshot by remember(previewState) {
        mutableStateOf(
            previewState?.let {
                AgentProfileDefaultsSnapshot(
                    config = it.config,
                    model = it.model,
                    modelChoices = it.modelChoices,
                    personalityChoices = it.personalityChoices,
                )
            },
        )
    }
    var pendingConfig by remember(previewState) {
        mutableStateOf(previewState?.pendingConfig.orEmpty())
    }
    var pendingModel by remember { mutableStateOf<AgentProfileModelChoice?>(null) }
    var picker by remember { mutableStateOf<DefaultsPicker?>(null) }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reviewOpen by remember { mutableStateOf(false) }
    var discardOpen by remember { mutableStateOf(false) }
    var discardThenBack by remember { mutableStateOf(false) }
    var pendingProfileSwitch by remember { mutableStateOf<String?>(null) }
    var contextChanged by remember { mutableStateOf(false) }
    var retryEpoch by remember { mutableStateOf(0) }
    var expensiveModelWarning by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val brand = LocalBrand.current
    val isDarkTheme = brand.isDark
    val dirtyCount by remember(pendingConfig, pendingModel, snapshot) {
        derivedStateOf {
            pendingConfig.size + if (pendingModel != null && pendingModel != snapshot?.model) 1 else 0
        }
    }

    LaunchedEffect(
        profileSelectionSettled,
        activeConnection?.id,
        effectiveProfileName,
        dashboardUrl,
    ) {
        if (previewState != null) return@LaunchedEffect
        val connection = activeConnection ?: return@LaunchedEffect
        val profileName = effectiveProfileName?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val resolvedDashboard = dashboardUrl.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (!profileSelectionSettled) return@LaunchedEffect

        val current = target
        if (current == null) {
            target = AgentProfileDefaultsTarget(
                connectionId = connection.id,
                connectionLabel = connection.label.ifBlank { connection.primaryHost },
                dashboardUrl = resolvedDashboard,
                profileName = profileName,
            )
        } else if (current.connectionId != connection.id) {
            // Never let the authenticated client silently follow a connection
            // switch while an editable draft still names the old profile.
            contextChanged = true
        }
    }

    LaunchedEffect(target, retryEpoch) {
        if (previewState != null) return@LaunchedEffect
        val frozenTarget = target ?: return@LaunchedEffect
        loading = true
        error = null
        snapshot = null
        pendingConfig = emptyMap()
        pendingModel = null
        runCatching {
            val client = connectionViewModel.dashboardClientForConnection(
                frozenTarget.connectionId,
                frozenTarget.dashboardUrl,
            )
            try {
                coroutineScope {
                    val config = async { client.getConfig(frozenTarget.profileName).getOrThrow() }
                    val schema = async { client.getConfigSchema(frozenTarget.profileName).getOrThrow() }
                    val modelInfo = async { client.getModelInfo(frozenTarget.profileName).getOrThrow() }
                    val models = async { client.getModelOptions(profile = frozenTarget.profileName).getOrThrow() }
                    val configValue = config.await()
                    val schemaValue = schema.await()
                    val modelInfoValue = modelInfo.await()
                    val modelIdentity = AgentProfileModelChoice(
                        provider = modelInfoValue.stringValue("provider"),
                        model = modelInfoValue.stringValue("model"),
                    )
                    val modelOptionsValue = models.await()
                    val modelChoices = parseModelOptions(modelOptionsValue)
                        .filter { it.authenticated }
                        .flatMap { provider ->
                            provider.models.map { modelId ->
                                val capability = modelCapability(modelOptionsValue, provider.id, modelId)
                                AgentProfileModelChoice(
                                    provider = provider.id,
                                    model = modelId,
                                    reasoningSupported = capability.reasoning != false,
                                    reasoningEfforts = capability.reasoningEfforts,
                                    fastSupported = capability.fast == true,
                                )
                            }
                        }
                        .distinct()
                    val model = modelChoices.firstOrNull { it.sameIdentity(modelIdentity) }
                        ?: modelIdentity.copy(
                            reasoningSupported = ((modelInfoValue["capabilities"] as? JsonObject)
                                ?.get("supports_reasoning") as? JsonPrimitive)
                                ?.booleanOrNull != false,
                        )
                    val schemaPersonalities = parseConfigSchema(schemaValue)
                        .firstOrNull { it.key == KEY_PERSONALITY }
                        ?.options
                        .orEmpty()
                    val configuredPersonalities = ((configValue["agent"] as? JsonObject)
                        ?.get("personalities") as? JsonObject)
                        ?.keys
                        .orEmpty()
                    AgentProfileDefaultsSnapshot(
                        config = configValue,
                        model = model,
                        modelChoices = (listOf(model) + modelChoices)
                            .filter { it.model.isNotBlank() }
                            .distinct(),
                        personalityChoices = (listOf("") + schemaPersonalities + configuredPersonalities)
                            .distinct(),
                    )
                }
            } finally {
                client.shutdown()
            }
        }.onSuccess {
            if (target == frozenTarget) snapshot = it
        }.onFailure {
            if (target == frozenTarget) {
                error = it.message ?: context.getString(R.string.agent_defaults_request_failed)
            }
        }
        if (target == frozenTarget) loading = false
    }

    fun valueAt(path: String): JsonElement? =
        pendingConfig[path] ?: snapshot?.config?.let { configValueAt(it, path) }

    fun stageConfig(path: String, value: JsonElement) {
        val baselineValue = snapshot?.config?.let { configValueAt(it, path) }
        pendingConfig = if (value == baselineValue) {
            pendingConfig - path
        } else {
            pendingConfig + (path to value)
        }
    }

    fun requestBack() {
        if (dirtyCount > 0) {
            discardThenBack = true
            discardOpen = true
        } else {
            onBack()
        }
    }

    fun switchProfile(profileName: String) {
        val currentTarget = target ?: return
        if (profileName == currentTarget.profileName) return
        target = currentTarget.copy(profileName = profileName)
        picker = null
    }

    fun requestProfileSwitch(profileName: String) {
        if (dirtyCount > 0) {
            pendingProfileSwitch = profileName
            discardThenBack = false
            discardOpen = true
        } else {
            switchProfile(profileName)
        }
    }

    fun save(confirmExpensive: Boolean = false) {
        val frozenTarget = target ?: return
        val baseline = snapshot ?: return
        val modelDraft = pendingModel?.takeIf { it != baseline.model }
        val configDraft = pendingConfig.toMap()
        if (dirtyCount == 0 || saving || contextChanged) return
        scope.launch {
            saving = true
            error = null
            runCatching {
                val client = connectionViewModel.dashboardClientForConnection(
                    frozenTarget.connectionId,
                    frozenTarget.dashboardUrl,
                )
                try {
                    // Preflight every optimistic-concurrency check before the
                    // first mutation so a config conflict cannot strand a model
                    // change that the same draft can no longer retry.
                    if (modelDraft != null) {
                        val freshInfo = client.getModelInfo(frozenTarget.profileName).getOrThrow()
                        val freshModel = AgentProfileModelChoice(
                            provider = freshInfo.stringValue("provider"),
                            model = freshInfo.stringValue("model"),
                        )
                        check(freshModel.sameIdentity(baseline.model)) {
                            context.getString(R.string.agent_defaults_model_conflict)
                        }
                    }
                    val freshConfig = if (configDraft.isNotEmpty()) {
                        client.getConfig(frozenTarget.profileName).getOrThrow().also { fresh ->
                            val conflicts = configDraft.keys.filter { path ->
                                configValueAt(baseline.config, path) != configValueAt(fresh, path)
                            }
                            check(conflicts.isEmpty()) {
                                "${conflicts.joinToString()} changed on another client. Reload before saving."
                            }
                        }
                    } else {
                        null
                    }

                    if (modelDraft != null) {
                        val response = client.setMainModel(
                            provider = modelDraft.provider,
                            model = modelDraft.model,
                            confirmExpensive = confirmExpensive,
                            profile = frozenTarget.profileName,
                        ).getOrThrow()
                        if (response.booleanValue("confirm_required") == true && !confirmExpensive) {
                            val warning = response.stringValue("confirm_message")
                                .ifBlank { response.stringValue("warning") }
                                .ifBlank {
                                    context.getString(R.string.agent_defaults_model_confirmation_required)
                                }
                            throw ExpensiveModelConfirmation(warning)
                        }
                        check(response.booleanValue("ok") != false) {
                            response.stringValue("error").ifBlank {
                                context.getString(R.string.agent_defaults_model_change_rejected)
                            }
                        }
                        if (target == frozenTarget && pendingModel == modelDraft) {
                            snapshot = snapshot?.copy(model = modelDraft)
                            pendingModel = null
                        }
                    }

                    if (freshConfig != null) {
                        val merged = applyConfigEdits(freshConfig, configDraft)
                        client.updateConfig(merged, profile = frozenTarget.profileName).getOrThrow()
                        if (target == frozenTarget) {
                            snapshot = snapshot?.copy(config = merged)
                            pendingConfig = pendingConfig.filterKeys { path ->
                                pendingConfig[path] != configDraft[path]
                            }
                        }
                    }
                } finally {
                    client.shutdown()
                }
            }.onSuccess {
                if (target == frozenTarget) {
                    reviewOpen = false
                    retryEpoch += 1
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.agent_defaults_saved),
                    )
                }
            }.onFailure { failure ->
                if (target == frozenTarget) {
                    if (failure is ExpensiveModelConfirmation) {
                        expensiveModelWarning = failure.message
                        reviewOpen = false
                    } else {
                        error = failure.message ?: context.getString(R.string.agent_defaults_save_error)
                    }
                }
            }
            if (target == frozenTarget) saving = false
        }
    }

    BackHandler(onBack = ::requestBack)

    val currentTarget = target
    val loaded = snapshot
    val reviewChanges = buildList {
        pendingModel?.takeIf { it != loaded?.model }?.let {
            add(ReviewChange(stringResource(R.string.agent_defaults_default_model), it.label))
        }
        pendingConfig.forEach { (key, value) ->
            add(
                ReviewChange(
                    label = when (key) {
                        KEY_REASONING -> stringResource(R.string.agent_defaults_reasoning_effort)
                        KEY_SPEED -> stringResource(R.string.agent_defaults_speed)
                        KEY_PERSONALITY -> stringResource(R.string.agent_defaults_personality)
                        KEY_APPROVAL -> stringResource(R.string.agent_defaults_approval_mode)
                        else -> key
                    },
                    value = value.displayValue(),
                ),
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.settings_agent_profile_defaults),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = currentTarget?.connectionLabel.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (
                                previewState?.gatewayReady == true ||
                                gatewayAvailability == GatewayAvailability.Ready
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .size(8.dp),
                                    color = brand.green,
                                    shape = CircleShape,
                                    content = {},
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_settings_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            if (currentTarget != null && loaded != null) {
                DefaultsSaveBar(
                    target = currentTarget,
                    dirtyCount = dirtyCount,
                    saving = saving,
                    enabled = dirtyCount > 0 && !contextChanged,
                    onDiscard = {
                        discardThenBack = false
                        pendingProfileSwitch = null
                        discardOpen = true
                    },
                    onReview = { reviewOpen = true },
                )
            }
        },
    ) { innerPadding ->
        when {
            currentTarget == null || loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(R.string.agent_defaults_loading),
                            modifier = Modifier.padding(top = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            loaded == null -> {
                DefaultsErrorState(
                    modifier = Modifier.padding(innerPadding),
                    message = error ?: stringResource(R.string.agent_defaults_load_error),
                    onRetry = { retryEpoch += 1 },
                )
            }

            else -> {
                val displayedProfiles = previewState?.let { listOf(it.profile) } ?: profiles
                val profile = displayedProfiles.firstOrNull { it.name == currentTarget.profileName }
                val effectiveModel = pendingModel ?: loaded.model
                val reasoningSupported = effectiveModel.reasoningSupported
                val fastSupported = effectiveModel.fastSupported
                val reasoning = valueAt(KEY_REASONING).stringContent().ifBlank { "medium" }
                val speed = valueAt(KEY_SPEED).stringContent().let {
                    if (it.lowercase() in setOf("fast", "priority", "on")) {
                        stringResource(R.string.agent_defaults_option_fast)
                    } else {
                        stringResource(R.string.agent_defaults_option_normal)
                    }
                }
                val personality = valueAt(KEY_PERSONALITY).stringContent().ifBlank {
                    stringResource(R.string.agent_defaults_option_none)
                }
                val approval = valueAt(KEY_APPROVAL).stringContent().ifBlank { "smart" }
                    .replaceFirstChar { it.uppercase() }
                val voice = valueAt("tts.provider").stringContent().ifBlank {
                    stringResource(R.string.agent_defaults_option_server_default)
                }.replaceFirstChar { it.uppercase() }
                val memory = valueAt(KEY_MEMORY).stringContent().ifBlank { "Built-in" }
                    .replaceFirstChar { it.uppercase() }
                val skillSummary = profile?.skillCount?.takeIf { it > 0 }?.let {
                    stringResource(R.string.agent_defaults_skills_enabled, it)
                }
                    ?: stringResource(R.string.agent_defaults_manage)

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        EditingProfileCard(
                            target = currentTarget,
                            onSwitch = { picker = DefaultsPicker.Profile },
                            enabled = !saving,
                            isDarkTheme = isDarkTheme,
                        )
                    }
                    item { ActiveSessionNotice() }
                    if (contextChanged) {
                        item {
                            InlineWarning(stringResource(R.string.agent_defaults_connection_changed))
                        }
                    }
                    error?.let { message -> item { InlineWarning(message) } }
                    item {
                        DefaultsSectionCard(
                            title = stringResource(R.string.agent_defaults_model_behavior),
                            isDarkTheme = isDarkTheme,
                        ) {
                            DefaultsValueRow(
                                title = stringResource(R.string.agent_defaults_default_model),
                                value = effectiveModel.label.ifBlank {
                                    stringResource(R.string.agent_defaults_not_configured)
                                },
                                enabled = !saving,
                                onClick = { picker = DefaultsPicker.Model },
                            )
                            DefaultsDivider()
                            DefaultsValueRow(
                                title = stringResource(R.string.agent_defaults_reasoning_effort),
                                value = if (reasoningSupported) {
                                    reasoning.replaceFirstChar { it.uppercase() }
                                } else {
                                    stringResource(R.string.agent_defaults_not_supported)
                                },
                                enabled = !saving && reasoningSupported,
                                onClick = { picker = DefaultsPicker.Reasoning },
                            )
                            DefaultsDivider()
                            DefaultsValueRow(
                                title = stringResource(R.string.agent_defaults_speed),
                                value = if (fastSupported) speed
                                else stringResource(R.string.agent_defaults_not_supported),
                                enabled = !saving && fastSupported,
                                onClick = { picker = DefaultsPicker.Speed },
                            )
                            DefaultsDivider()
                            DefaultsValueRow(
                                title = stringResource(R.string.agent_defaults_personality),
                                value = personality,
                                enabled = !saving,
                                onClick = { picker = DefaultsPicker.Personality },
                            )
                        }
                    }
                    item {
                        DefaultsSectionCard(
                            title = stringResource(R.string.agent_defaults_policy),
                            isDarkTheme = isDarkTheme,
                        ) {
                            DefaultsValueRow(
                                title = stringResource(R.string.agent_defaults_approval_mode),
                                value = approval,
                                supporting = stringResource(R.string.agent_defaults_approval_mode_desc),
                                enabled = !saving,
                                onClick = { picker = DefaultsPicker.Approval },
                            )
                        }
                    }
                    item {
                        DefaultsSectionCard(
                            title = stringResource(R.string.agent_defaults_capabilities),
                            isDarkTheme = isDarkTheme,
                        ) {
                            DefaultsValueRow(
                                title = stringResource(R.string.agent_defaults_voice_defaults),
                                value = voice,
                                enabled = !saving && !contextChanged,
                                onClick = { onOpenVoiceDefaults(currentTarget.profileName) },
                            )
                            DefaultsDivider()
                            DefaultsValueRow(
                                title = stringResource(R.string.agent_defaults_skills_tools),
                                value = skillSummary,
                                enabled = !saving,
                                onClick = {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.agent_defaults_skills_unavailable),
                                        )
                                    }
                                },
                            )
                            DefaultsDivider()
                            DefaultsValueRow(
                                title = stringResource(R.string.agent_defaults_memory),
                                value = memory,
                                enabled = !saving,
                                onClick = {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.agent_defaults_memory_read_only),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    when (picker) {
        DefaultsPicker.Profile -> ChoiceDialog(
            title = stringResource(R.string.agent_defaults_switch),
            options = (previewState?.let { listOf(it.profile) } ?: profiles).map { it.name },
            selected = currentTarget?.profileName,
            label = { profileName ->
                (previewState?.let { listOf(it.profile) } ?: profiles)
                    .firstOrNull { it.name == profileName }
                    ?.description
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "$profileName · $it" }
                    ?: profileName
            },
            onSelect = ::requestProfileSwitch,
            onDismiss = { picker = null },
        )

        DefaultsPicker.Model -> loaded?.let { state ->
            ChoiceDialog(
                title = stringResource(R.string.agent_defaults_default_model),
                options = state.modelChoices,
                selected = pendingModel ?: state.model,
                label = { it.label },
                onSelect = { selectedModel ->
                    pendingModel = selectedModel
                    pendingConfig = pendingConfig.toMutableMap().apply {
                        val stagedReasoning = this[KEY_REASONING].stringContent()
                        if (
                            !selectedModel.reasoningSupported ||
                            (selectedModel.reasoningEfforts != null && stagedReasoning !in selectedModel.reasoningEfforts)
                        ) {
                            remove(KEY_REASONING)
                        }
                        if (!selectedModel.fastSupported) remove(KEY_SPEED)
                    }
                    picker = null
                },
                onDismiss = { picker = null },
            )
        }

        DefaultsPicker.Reasoning -> ChoiceDialog(
            title = stringResource(R.string.agent_defaults_reasoning_effort),
            options = (pendingModel ?: loaded?.model)?.reasoningEfforts
                ?: listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"),
            selected = valueAt(KEY_REASONING).stringContent().ifBlank { "medium" },
            label = { it.replaceFirstChar(Char::uppercase) },
            onSelect = { stageConfig(KEY_REASONING, JsonPrimitive(it)); picker = null },
            onDismiss = { picker = null },
        )

        DefaultsPicker.Speed -> ChoiceDialog(
            title = stringResource(R.string.agent_defaults_speed),
            options = listOf("normal", "fast"),
            selected = if (valueAt(KEY_SPEED).stringContent().lowercase() in setOf("fast", "priority", "on")) "fast" else "normal",
            label = { it.replaceFirstChar(Char::uppercase) },
            onSelect = { stageConfig(KEY_SPEED, JsonPrimitive(it)); picker = null },
            onDismiss = { picker = null },
        )

        DefaultsPicker.Personality -> loaded?.let { state ->
            ChoiceDialog(
                title = stringResource(R.string.agent_defaults_personality),
                options = state.personalityChoices,
                selected = valueAt(KEY_PERSONALITY).stringContent(),
                label = { it.ifBlank { stringResource(R.string.agent_defaults_option_none) } },
                onSelect = { stageConfig(KEY_PERSONALITY, JsonPrimitive(it)); picker = null },
                onDismiss = { picker = null },
            )
        }

        DefaultsPicker.Approval -> ChoiceDialog(
            title = stringResource(R.string.agent_defaults_approval_mode),
            options = listOf("manual", "smart", "off"),
            selected = valueAt(KEY_APPROVAL).stringContent().ifBlank { "smart" },
            label = { it.replaceFirstChar(Char::uppercase) },
            onSelect = { stageConfig(KEY_APPROVAL, JsonPrimitive(it)); picker = null },
            onDismiss = { picker = null },
        )

        null -> Unit
    }

    if (reviewOpen && currentTarget != null) {
        AlertDialog(
            onDismissRequest = { if (!saving) reviewOpen = false },
            title = { Text(stringResource(R.string.agent_defaults_review_changes)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(
                            R.string.agent_defaults_target,
                            currentTarget.profileName,
                            currentTarget.connectionLabel,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    reviewChanges.forEach { change ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(change.label, modifier = Modifier.weight(1f))
                            Text(
                                change.value,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { save() }, enabled = !saving) {
                    Text(
                        if (saving) stringResource(R.string.agent_defaults_saving)
                        else stringResource(R.string.agent_defaults_save_changes),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { reviewOpen = false }, enabled = !saving) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (discardOpen) {
        AlertDialog(
            onDismissRequest = { discardOpen = false },
            title = { Text(stringResource(R.string.agent_defaults_discard_dialog_title)) },
            text = { Text(stringResource(R.string.agent_defaults_discard_dialog_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingConfig = emptyMap()
                        pendingModel = null
                        discardOpen = false
                        val nextProfile = pendingProfileSwitch
                        pendingProfileSwitch = null
                        when {
                            discardThenBack -> onBack()
                            nextProfile != null -> switchProfile(nextProfile)
                        }
                    },
                ) { Text(stringResource(R.string.agent_defaults_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { discardOpen = false }) {
                    Text(stringResource(R.string.agent_defaults_keep_editing))
                }
            },
        )
    }

    expensiveModelWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = { expensiveModelWarning = null },
            title = { Text(stringResource(R.string.agent_defaults_default_model)) },
            text = { Text(warning) },
            confirmButton = {
                Button(
                    onClick = {
                        expensiveModelWarning = null
                        save(confirmExpensive = true)
                    },
                ) { Text(stringResource(R.string.agent_defaults_save_changes)) }
            },
            dismissButton = {
                TextButton(onClick = { expensiveModelWarning = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

private class ExpensiveModelConfirmation(message: String) : IllegalStateException(message)

@Composable
private fun EditingProfileCard(
    target: AgentProfileDefaultsTarget,
    onSwitch: () -> Unit,
    enabled: Boolean,
    isDarkTheme: Boolean,
) {
    val brand = LocalBrand.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBorder(
                shape = RoundedCornerShape(12.dp),
                isDarkTheme = isDarkTheme,
                colors = listOf(brand.lineStrong, brand.relay.copy(alpha = 0.32f)),
            ),
        colors = CardDefaults.cardColors(containerColor = brand.navy),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.agent_defaults_editing_profile).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = brand.navy3,
                    border = BorderStroke(1.dp, brand.purple.copy(alpha = 0.55f)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            tint = brand.relay,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(target.profileName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.agent_defaults_future_sessions_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ScopePill(stringResource(R.string.agent_defaults_profile).uppercase())
                TextButton(
                    onClick = onSwitch,
                    enabled = enabled,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                ) {
                    Text(stringResource(R.string.agent_defaults_switch))
                }
            }
        }
    }
}

@Composable
private fun ScopePill(text: String) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.17f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ActiveSessionNotice() {
    val brand = LocalBrand.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = brand.amber.copy(alpha = if (brand.isDark) 0.14f else 0.10f),
        border = BorderStroke(1.dp, brand.amber.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = brand.amber)
            Text(
                text = stringResource(R.string.agent_defaults_active_sessions_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = brand.amber,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun InlineWarning(message: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun DefaultsSectionCard(
    title: String,
    isDarkTheme: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val brand = LocalBrand.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBorder(
                shape = RoundedCornerShape(12.dp),
                isDarkTheme = isDarkTheme,
                colors = listOf(brand.lineStrong, brand.relay.copy(alpha = 0.32f)),
            ),
        colors = CardDefaults.cardColors(containerColor = brand.navy),
    ) {
        Column {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 4.dp),
            )
            content()
        }
    }
}

@Composable
private fun DefaultsValueRow(
    title: String,
    value: String,
    supporting: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.55f)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            supporting?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(0.72f, fill = false),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun DefaultsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 14.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
}

@Composable
private fun DefaultsSaveBar(
    target: AgentProfileDefaultsTarget,
    dirtyCount: Int,
    saving: Boolean,
    enabled: Boolean,
    onDiscard: () -> Unit,
    onReview: () -> Unit,
) {
    val brand = LocalBrand.current
    val reviewContentDescription = stringResource(
        R.string.agent_defaults_review_save_content_description,
    )
    Surface(
        color = brand.navy,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(
                        R.string.agent_defaults_target,
                        target.profileName,
                        target.connectionLabel,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (dirtyCount == 0) {
                        stringResource(R.string.agent_defaults_no_unsaved_changes)
                    } else {
                        stringResource(R.string.agent_defaults_unsaved_changes, dirtyCount)
                    },
                    color = if (dirtyCount > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDiscard, enabled = dirtyCount > 0 && !saving) {
                    Text(stringResource(R.string.agent_defaults_discard))
                }
                Button(
                    onClick = onReview,
                    enabled = enabled && !saving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = brand.purple,
                        contentColor = brand.paper,
                    ),
                    modifier = Modifier.semantics {
                        contentDescription = reviewContentDescription
                    },
                ) {
                    Text(
                        if (saving) stringResource(R.string.agent_defaults_saving)
                        else stringResource(R.string.agent_defaults_review_save),
                    )
                }
            }
        }
    }
}

@Composable
private fun DefaultsErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(message, color = MaterialTheme.colorScheme.error)
            Button(onClick = onRetry) { Text(stringResource(R.string.agent_defaults_retry)) }
        }
    }
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                items(options) { option ->
                    val isSelected = option == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = label(option),
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private fun JsonObject.stringValue(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.booleanValue(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonElement?.stringContent(): String =
    (this as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonElement.displayValue(): String =
    (this as? JsonPrimitive)?.contentOrNull?.ifBlank { "None" } ?: toString()

private data class ProfileModelCapability(
    val reasoning: Boolean? = null,
    val reasoningEfforts: List<String>? = null,
    val fast: Boolean? = null,
)

private fun modelCapability(
    root: JsonObject,
    provider: String,
    model: String,
): ProfileModelCapability {
    val providerRow = (root["providers"] as? JsonArray)
        .orEmpty()
        .mapNotNull { it as? JsonObject }
        .firstOrNull { row ->
            sequenceOf("slug", "id", "provider", "name")
                .map(row::stringValue)
                .any { it.equals(provider, ignoreCase = true) }
        }
        ?: return ProfileModelCapability()
    val row = (providerRow["capabilities"] as? JsonObject)
        ?.get(model) as? JsonObject
        ?: return ProfileModelCapability()
    val efforts = (row["reasoning_efforts"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        ?.distinct()
    return ProfileModelCapability(
        reasoning = (row["reasoning"] as? JsonPrimitive)?.booleanOrNull,
        reasoningEfforts = efforts,
        fast = (row["fast"] as? JsonPrimitive)?.booleanOrNull,
    )
}
