@file:Suppress("LocalContextGetResourceValueCall")

package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.BargeInPreferences
import com.hermesandroid.relay.data.BargeInSensitivity
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.VoiceAudioRoute
import com.hermesandroid.relay.data.VoiceEngineMode
import com.hermesandroid.relay.data.VoiceModePreset
import com.hermesandroid.relay.data.VoiceModePresetState
import com.hermesandroid.relay.data.VoicePreferencesRepository
import com.hermesandroid.relay.data.VoicePresetPromotionSettings
import com.hermesandroid.relay.data.VoiceSettings
import com.hermesandroid.relay.data.detectVoiceModePreset
import com.hermesandroid.relay.network.relay.RealtimeProviderInfo
import com.hermesandroid.relay.network.relay.RealtimeVoiceConfig
import com.hermesandroid.relay.network.relay.RealtimeVoicePromotion
import com.hermesandroid.relay.network.relay.RelayVoiceClient
import com.hermesandroid.relay.network.relay.VoiceConfig
import com.hermesandroid.relay.network.relay.VoiceOutputConfig
import com.hermesandroid.relay.network.relay.VoiceProviderValidationResponse
import com.hermesandroid.relay.network.upstream.ConfigFieldType
import com.hermesandroid.relay.network.upstream.ConfigSchemaField
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.DashboardCookieStore
import com.hermesandroid.relay.network.upstream.ElevenLabsVoices
import com.hermesandroid.relay.network.upstream.InMemoryDashboardCookieStore
import com.hermesandroid.relay.network.upstream.applyConfigEdits
import com.hermesandroid.relay.network.upstream.configValueAt
import com.hermesandroid.relay.network.upstream.parseConfigSchema
import com.hermesandroid.relay.network.upstream.voiceConfigFields
import com.hermesandroid.relay.ui.LocalSnackbarHost
import com.hermesandroid.relay.ui.showHumanError
import com.hermesandroid.relay.util.classifyError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import com.hermesandroid.relay.viewmodel.InteractionMode
import com.hermesandroid.relay.viewmodel.StandardVoiceAvailability
import com.hermesandroid.relay.viewmodel.VoiceConfigUiState
import com.hermesandroid.relay.viewmodel.VoiceSettingsViewModel
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import kotlinx.coroutines.launch

/**
 * Dedicated voice-mode settings screen. Reachable from Settings → Voice.
 *
 * Information architecture (WP-V3/WP-V4 overhaul):
 *   1. Profile summary          — active profile + resolved voice line
 *   2. Voice scope banner        — the SINGLE home for "Profile / Scope"; on a
 *                                  Standard (no-Relay) connection it honestly
 *                                  reads "Global voice" (upstream profiles have
 *                                  no voice field — voice is host-wide).
 *   3. Voice for this profile    — engine + STT/TTS route; these are persisted
 *                                  per-profile (WP-V2 scope-aware prefs).
 *   4. Text-to-Speech            — merged streaming output + basic-synthesize
 *                                  fallback + enhanced overrides (Advanced).
 *   5. Realtime Agent            — provider-native realtime config (Relay only).
 *   6. Global voice controls     — interaction mode + silence threshold.
 *   7. Barge-in                  — interrupt TTS by speaking.
 *   8. Speech-to-Text            — provider/model labels.
 *   9. Server voice config        — edit host tts/stt config (Standard path)
 *                                  plus the ElevenLabs voice picker.
 *  10. Test current engine       — voice-output / realtime sample playback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsScreen(
    voiceViewModel: VoiceViewModel,
    voiceClient: RelayVoiceClient?,
    selectedProfile: Profile? = null,
    standardVoiceAvailability: StandardVoiceAvailability = StandardVoiceAvailability.Unknown,
    /**
     * Non-null endpoint display label (e.g. "Tailscale") when the sign-in
     * gate is up because the resolver moved the dashboard to a route the
     * user hasn't signed in on yet — dashboard cookies are per-host.
     */
    standardVoiceSignInRouteHint: String? = null,
    relayVoiceReady: Boolean = false,
    /**
     * Active connection id used to namespace per-profile voice prefs so two
     * connections that expose a same-named profile don't share voice picks.
     * Passed from RelayApp; null degrades to profile-only namespacing.
     */
    connectionId: String? = null,
    /**
     * Dashboard base URL + per-connection cookie store provider for the
     * standard-path server voice-config editor (`/api/config`, cookie auth).
     * Null on connections with no dashboard — the editor card is then hidden.
     */
    dashboardUrl: String? = null,
    dashboardCookieStoreProvider: (() -> DashboardCookieStore?)? = null,
    onOpenManage: (() -> Unit)? = null,
    onBack: () -> Unit,
    settingsViewModel: VoiceSettingsViewModel = viewModel(),
) {
    val context = LocalContext.current

    val prefsRepo = remember { VoicePreferencesRepository(context) }
    val voiceSettings by prefsRepo.settings.collectAsState(initial = VoiceSettings())
    val currentEngine = VoiceEngineMode.fromStorage(voiceSettings.engineMode)
    val currentAudioRoute = VoiceAudioRoute.fromStorage(voiceSettings.audioRoute)

    val bargeInPrefs by settingsViewModel.bargeInPrefs.collectAsState()
    val aecAvailable = settingsViewModel.aecAvailable

    // Authoritative relay voice config now lives in the VM (WP-V3). The screen
    // just observes it; the editor cards push saves back through the VM.
    val configState by settingsViewModel.configState.collectAsState()

    // Standard-path server voice-config editor client (dashboard cookie auth).
    // Built once per (dashboardUrl, connection); shut down on dispose. Null when
    // the connection has no dashboard, which hides the card entirely.
    val dashboardConfigClient = remember(dashboardUrl, connectionId) {
        val url = dashboardUrl?.trim()?.takeIf { it.isNotBlank() } ?: return@remember null
        DashboardApiClient(
            baseUrl = url,
            okHttpClient = DashboardApiClient.defaultClient(
                cookieStore = dashboardCookieStoreProvider?.invoke() ?: InMemoryDashboardCookieStore(),
            ),
        )
    }
    DisposableEffect(dashboardConfigClient) {
        onDispose { dashboardConfigClient?.shutdown() }
    }

    // Global snackbar host — voice/config errors routed through the classifier
    // are shown here as well as the inline "unavailable" labels.
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    var presetApplying by remember { mutableStateOf(false) }

    val presetState = VoiceModePresetState(
        voiceSettings = voiceSettings,
        bargeInPreferences = bargeInPrefs,
        promotion = configState.realtimeConfig?.promotion?.toPresetSettings(),
    )
    val activePreset = detectVoiceModePreset(presetState)
    val presetsReady = voiceClient != null && presetState.promotion != null

    fun applyPreset(preset: VoiceModePreset) {
        if (presetApplying) return
        val client = voiceClient
        val priorPromotion = presetState.promotion
        if (client == null || priorPromotion == null) {
            scope.launch {
                snackbarHost.showSnackbar(
                    "Realtime Agent background settings must be available before applying a preset.",
                )
            }
            return
        }
        val target = preset.applyTo(presetState)
        val update = preset.promotionUpdate
        scope.launch {
            presetApplying = true
            try {
                // Server first: if the relay rejects a preset, local controls
                // stay untouched and the UI cannot falsely report it active.
                val result = client.updateRealtimeAgentPromotion(
                    promotionEnabled = update.enabled,
                    promoteAfterMs = update.promoteAfterMs,
                    spokenHandoff = update.spokenHandoff,
                    resultDelivery = update.resultDelivery,
                    backgroundDefaultMode = update.backgroundDefaultMode,
                    progressSpokenAfterMs = update.progressSpokenAfterMs,
                    progressRepeatMs = update.progressRepeatMs,
                    maxBackgroundRuns = update.maxBackgroundRuns,
                )
                if (result.isFailure) {
                    snackbarHost.showHumanError(
                        classifyError(result.exceptionOrNull(), context = "voice_config"),
                    )
                    return@launch
                }
                settingsViewModel.setRealtimeConfig(result.getOrNull())

                try {
                    // One DataStore transaction covers Voice + barge-in.
                    prefsRepo.applyModePreset(preset)
                } catch (error: Exception) {
                    // The network and DataStore cannot share one transaction.
                    // Restore the captured relay values so a local write
                    // failure does not leave a half-applied preset.
                    val rollback = client.updateRealtimeAgentPromotion(
                        promotionEnabled = priorPromotion.enabled,
                        promoteAfterMs = priorPromotion.promoteAfterMs,
                        spokenHandoff = priorPromotion.spokenHandoff,
                        resultDelivery = priorPromotion.resultDelivery,
                        backgroundDefaultMode = priorPromotion.backgroundDefaultMode,
                        progressSpokenAfterMs = priorPromotion.progressSpokenAfterMs,
                        progressRepeatMs = priorPromotion.progressRepeatMs,
                        maxBackgroundRuns = priorPromotion.maxBackgroundRuns,
                    )
                    if (rollback.isSuccess) {
                        settingsViewModel.setRealtimeConfig(rollback.getOrNull())
                        snackbarHost.showHumanError(
                            classifyError(error, context = "voice_config"),
                        )
                    } else {
                        snackbarHost.showSnackbar(
                            "Preset partly applied: Relay settings changed, but phone " +
                                "settings could not be saved. Reapply a preset to recover.",
                        )
                    }
                    return@launch
                }

                voiceViewModel.setInteractionMode(
                    when (target.voiceSettings.interactionMode) {
                        "hold" -> InteractionMode.HoldToTalk
                        "continuous" -> InteractionMode.Continuous
                        else -> InteractionMode.TapToTalk
                    },
                )
                snackbarHost.showSnackbar("${preset.displayName} preset applied")
            } finally {
                presetApplying = false
            }
        }
    }

    // WP-V2/V3: point the screen's prefs repo at the active (connection,
    // profile) scope so the per-profile engine/route/enhanced toggles read and
    // write the SAME namespaced keys VoiceViewModel seeds from. The connection
    // id (when supplied by RelayApp) disambiguates two connections that expose
    // a same-named profile; the normalized profile name matches VoiceViewModel.
    LaunchedEffect(connectionId, selectedProfile?.name) {
        prefsRepo.setActiveScope(
            connectionId = connectionId,
            profileName = AgentDisplay.profileRequestName(selectedProfile?.name),
        )
    }

    // Fetch (or clear, on a Standard connection) the relay voice config.
    LaunchedEffect(voiceClient, selectedProfile?.name, relayVoiceReady) {
        settingsViewModel.loadVoiceConfig(voiceClient, relayVoiceReady)
    }

    // Surface config-fetch failures as one-shot snackbars.
    LaunchedEffect(settingsViewModel) {
        settingsViewModel.configErrorEvents.collect { err ->
            snackbarHost.showHumanError(err)
        }
    }

    // Surface VoiceViewModel errors (testVoice synthesize failures etc.) as
    // snackbars while the user is on this screen.
    LaunchedEffect(voiceViewModel) {
        voiceViewModel.errorEvents.collect { err ->
            snackbarHost.showHumanError(err)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voice_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.voice_settings_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VoiceProfileSummaryCard(
                selectedProfile = selectedProfile,
                currentEngine = currentEngine,
                output = configState.voiceOutputConfig,
                realtime = configState.realtimeConfig,
                realtimeModel = voiceSettings.realtimeModel,
                realtimeVoice = voiceSettings.realtimeVoice,
            )

            // --- Voice scope (single home; WP-V3 dedupe + WP-V4 honest label) ---
            VoiceScopeBanner(
                relayVoiceReady = relayVoiceReady,
                currentEngine = currentEngine,
                configState = configState,
                selectedProfile = selectedProfile,
            )

            VoiceModePresetCard(
                activePreset = activePreset,
                enabled = presetsReady,
                applying = presetApplying,
                onSelect = ::applyPreset,
            )

            // --- Voice for this profile: engine + route ---
            VoiceForThisProfileCard(
                currentEngine = currentEngine,
                currentAudioRoute = currentAudioRoute,
                relayVoiceReady = relayVoiceReady,
                prefsRepo = prefsRepo,
                standardVoiceAvailability = standardVoiceAvailability,
                standardVoiceSignInRouteHint = standardVoiceSignInRouteHint,
                onOpenManage = onOpenManage,
            )

            // --- Text-to-Speech (streaming output + basic synthesize + enhanced) ---
            if (relayVoiceReady) {
                TextToSpeechCard(
                    showStreaming = currentEngine == VoiceEngineMode.HermesVoiceOutput,
                    voiceClient = voiceClient,
                    settingsViewModel = settingsViewModel,
                    configState = configState,
                    voiceSettings = voiceSettings,
                    prefsRepo = prefsRepo,
                    voiceViewModel = voiceViewModel,
                )
            }

            // --- Realtime Agent (Relay only) ---
            if (currentEngine == VoiceEngineMode.RealtimeAgent && relayVoiceReady) {
                RealtimeAgentCard(
                    voiceClient = voiceClient,
                    settingsViewModel = settingsViewModel,
                    configState = configState,
                    voiceSettings = voiceSettings,
                    prefsRepo = prefsRepo,
                )
            }

            // --- Global Voice Controls ---
            GlobalVoiceControlsCard(
                voiceSettings = voiceSettings,
                prefsRepo = prefsRepo,
                voiceViewModel = voiceViewModel,
            )

            // --- Barge-in ---
            BargeInCard(
                bargeInPrefs = bargeInPrefs,
                aecAvailable = aecAvailable,
                settingsViewModel = settingsViewModel,
            )

            // --- Speech-to-Text ---
            SpeechToTextCard(
                relayVoiceReady = relayVoiceReady,
                configState = configState,
            )

            // --- Server voice config (standard path: edit tts.*/stt.* on the host) ---
            if (dashboardConfigClient != null) {
                StandardVoiceServerConfigCard(
                    client = dashboardConfigClient,
                    onOpenManage = onOpenManage,
                    onMessage = { message ->
                        scope.launch { snackbarHost.showSnackbar(message) }
                    },
                )
            }

            // --- Test Current Engine ---
            TestCurrentEngineCard(
                currentEngine = currentEngine,
                relayVoiceReady = relayVoiceReady,
                configState = configState,
                selectedProfile = selectedProfile,
                voiceViewModel = voiceViewModel,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Voice scope banner — the single home for Profile / Scope (WP-V3 dedupe).
// On Standard it honestly reads "Global voice" (WP-V4).
// ---------------------------------------------------------------------------

@Composable
private fun VoiceScopeBanner(
    relayVoiceReady: Boolean,
    currentEngine: VoiceEngineMode,
    configState: VoiceConfigUiState,
    selectedProfile: Profile?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.30f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!relayVoiceReady) {
                // Standard (no-Relay): upstream /api/profiles/* has no voice
                // field and /api/audio/* is host-global, so voice can't carry
                // per-profile here. Label it honestly.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.voice_settings_scope_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    ExperimentalBadge(stringResource(R.string.voice_settings_global_voice))
                }
                Text(
                    text = stringResource(R.string.voice_settings_standard_scope_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Relay: voice is scoped per profile. Show the resolved scope
                // once, here, instead of repeating it on every provider card.
                val config: Any? = when (currentEngine) {
                    VoiceEngineMode.HermesVoiceOutput ->
                        configState.voiceOutputConfig ?: configState.voiceConfig
                    VoiceEngineMode.RealtimeAgent ->
                        configState.realtimeConfig ?: configState.voiceConfig
                }
                val profileRaw = scopeProfile(config)
                val scopeRaw = scopeScope(config)
                val fallback = scopeFallback(config)
                Text(
                    text = stringResource(R.string.voice_settings_scope_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (config == null) {
                    Text(
                        text = stringResource(R.string.voice_settings_scope_resolving),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ProviderRow(
                        label = stringResource(R.string.voice_settings_label_profile),
                        value = voiceProfileLabel(profileRaw, selectedProfile),
                    )
                    ProviderRow(
                        label = stringResource(R.string.voice_settings_label_scope),
                        value = voiceScopeLabel(scopeRaw, fallback),
                    )
                    Text(
                        text = stringResource(R.string.voice_settings_relay_scope_footer),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// Small adapters so the banner can read profile/scope off any of the three
// config shapes without a shared interface.
private fun scopeProfile(config: Any?): String? = when (config) {
    is VoiceConfig -> config.profile
    is VoiceOutputConfig -> config.profile
    is RealtimeVoiceConfig -> config.profile
    else -> null
}

private fun scopeScope(config: Any?): String? = when (config) {
    is VoiceConfig -> config.configScope
    is VoiceOutputConfig -> config.configScope
    is RealtimeVoiceConfig -> config.configScope
    else -> null
}

private fun scopeFallback(config: Any?): Boolean = when (config) {
    is VoiceConfig -> config.fallbackToGlobal
    is VoiceOutputConfig -> config.fallbackToGlobal
    is RealtimeVoiceConfig -> config.fallbackToGlobal
    else -> false
}

private fun RealtimeVoicePromotion.toPresetSettings(): VoicePresetPromotionSettings =
    VoicePresetPromotionSettings(
        enabled = enabled,
        promoteAfterMs = promoteAfterMs,
        backgroundDefaultMode = backgroundDefaultMode,
        spokenHandoff = spokenHandoff,
        progressSpokenAfterMs = progressSpokenAfterMs,
        progressRepeatMs = progressRepeatMs,
        resultDelivery = resultDelivery,
        maxBackgroundRuns = maxBackgroundRuns,
    )

// ---------------------------------------------------------------------------
// Mode presets — compact bundles over controls already present on this screen.
// ---------------------------------------------------------------------------

@Composable
private fun VoiceModePresetCard(
    activePreset: VoiceModePreset?,
    enabled: Boolean,
    applying: Boolean,
    onSelect: (VoiceModePreset) -> Unit,
) {
    SectionCard(title = "Mode preset") {
        Text(
            text = "Tune interaction, interruption, trace, and long-task delivery together.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        // Two rows keep each target about 148dp wide on a 360dp screen after
        // screen/card padding; all four labels remain readable without tiny
        // type or ambiguous abbreviations.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            VoiceModePreset.entries.chunked(2).forEach { rowPresets ->
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    rowPresets.forEachIndexed { index, preset ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = rowPresets.size,
                            ),
                            onClick = { onSelect(preset) },
                            selected = activePreset == preset,
                            enabled = enabled && !applying,
                        ) {
                            Text(
                                text = preset.shortLabel,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = activePreset?.displayName ?: "Custom",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = activePreset?.description
                ?: "Your manual values do not exactly match a preset.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!enabled) {
            Text(
                text = "Connect Relay voice so background delivery can be " +
                    "applied with the local controls.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (applying) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Engine, route, provider, model, voice, and credentials stay unchanged.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Voice for this profile — engine + STT/TTS route (per-profile prefs).
// ---------------------------------------------------------------------------

@Composable
private fun VoiceForThisProfileCard(
    currentEngine: VoiceEngineMode,
    currentAudioRoute: VoiceAudioRoute,
    relayVoiceReady: Boolean,
    prefsRepo: VoicePreferencesRepository,
    standardVoiceAvailability: StandardVoiceAvailability,
    standardVoiceSignInRouteHint: String?,
    onOpenManage: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()

    // Auto-repair when Relay disappears out from under a Relay-only selection:
    // a persisted RealtimeAgent engine (Relay-only) or Relay route can't run
    // without a paired Relay, so fall back to the always-available defaults.
    LaunchedEffect(relayVoiceReady, currentEngine, currentAudioRoute) {
        if (!relayVoiceReady) {
            if (currentEngine == VoiceEngineMode.RealtimeAgent) {
                prefsRepo.setEngineMode(VoiceEngineMode.HermesVoiceOutput)
            }
            val coerced = coerceAudioRoute(
                engine = VoiceEngineMode.HermesVoiceOutput,
                route = currentAudioRoute,
                relayVoiceReady = false,
            )
            if (coerced != currentAudioRoute) {
                prefsRepo.setAudioRoute(coerced)
            }
        }
    }

    SectionCard(title = stringResource(R.string.voice_settings_for_profile_title)) {
        Text(
            text = stringResource(R.string.voice_settings_engine_label),
            style = MaterialTheme.typography.labelLarge,
        )
        listOf(
            VoiceEngineMode.HermesVoiceOutput to Triple(
                stringResource(R.string.voice_settings_engine_hermes),
                stringResource(R.string.voice_settings_engine_hermes_desc),
                false,
            ),
            VoiceEngineMode.RealtimeAgent to Triple(
                stringResource(R.string.voice_settings_engine_realtime),
                stringResource(R.string.voice_settings_engine_realtime_desc),
                true,
            ),
        ).forEach { (engine, copy) ->
            val (label, detail, experimental) = copy
            // RealtimeAgent requires a paired Relay; HermesVoiceOutput is always
            // selectable. The existing warning row below explains the disabled
            // RealtimeAgent radio.
            val engineEnabled = engine == VoiceEngineMode.HermesVoiceOutput || relayVoiceReady
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = currentEngine == engine,
                        enabled = engineEnabled,
                        onClick = {
                            scope.launch {
                                prefsRepo.setEngineMode(engine)
                                // Switching to HermesVoiceOutput may leave a now
                                // invalid persisted route (e.g. Relay while
                                // unpaired) — coerce it to a reachable one.
                                if (engine == VoiceEngineMode.HermesVoiceOutput) {
                                    val coerced = coerceAudioRoute(
                                        engine = engine,
                                        route = currentAudioRoute,
                                        relayVoiceReady = relayVoiceReady,
                                    )
                                    if (coerced != currentAudioRoute) {
                                        prefsRepo.setAudioRoute(coerced)
                                    }
                                }
                            }
                        },
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = currentEngine == engine,
                    onClick = null,
                    enabled = engineEnabled,
                )
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (engineEnabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            },
                        )
                        if (experimental) ExperimentalBadge(stringResource(R.string.voice_settings_experimental))
                    }
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (currentEngine == VoiceEngineMode.RealtimeAgent && !relayVoiceReady) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.voice_settings_realtime_no_relay),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (currentEngine == VoiceEngineMode.HermesVoiceOutput) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = stringResource(R.string.voice_settings_route_label),
                style = MaterialTheme.typography.labelLarge,
            )
            val standardStatus = when (standardVoiceAvailability) {
                StandardVoiceAvailability.Ready -> stringResource(R.string.voice_settings_status_ready)
                StandardVoiceAvailability.SignInRequired -> stringResource(R.string.voice_settings_status_sign_in_required)
                StandardVoiceAvailability.Unreachable -> stringResource(R.string.voice_settings_status_dashboard_unreachable)
                StandardVoiceAvailability.Unsupported -> stringResource(R.string.voice_settings_status_unsupported_build)
                StandardVoiceAvailability.Unknown -> stringResource(R.string.voice_settings_status_checking)
            }
            val standardOk = standardVoiceAvailability == StandardVoiceAvailability.Ready
            val relayStatus = if (relayVoiceReady) stringResource(R.string.voice_settings_status_ready) else stringResource(R.string.voice_settings_relay_not_configured)
            val autoStatus = when {
                relayVoiceReady -> stringResource(R.string.voice_settings_status_auto_relay)
                standardOk -> stringResource(R.string.voice_settings_status_auto_hermes)
                else -> stringResource(R.string.voice_settings_status_no_route)
            }
            listOf(
                RouteOption(
                    route = VoiceAudioRoute.Auto,
                    label = stringResource(R.string.voice_settings_route_auto),
                    detail = stringResource(R.string.voice_settings_route_auto_desc),
                    status = autoStatus,
                    statusOk = relayVoiceReady || standardOk,
                ),
                RouteOption(
                    route = VoiceAudioRoute.Standard,
                    label = stringResource(R.string.voice_settings_route_hermes),
                    detail = stringResource(R.string.voice_settings_route_hermes_desc),
                    status = standardStatus,
                    statusOk = standardOk,
                ),
                RouteOption(
                    route = VoiceAudioRoute.Relay,
                    label = stringResource(R.string.voice_settings_route_relay),
                    detail = stringResource(R.string.voice_settings_route_relay_desc),
                    status = relayStatus,
                    statusOk = relayVoiceReady,
                    badge = stringResource(R.string.voice_settings_optional),
                ),
            ).forEach { option ->
                // Auto always stays selectable (it self-resolves to whatever's
                // reachable). Standard/Relay are only selectable when their live
                // availability probe says so — otherwise the radio is dimmed.
                val routeEnabled = option.route == VoiceAudioRoute.Auto || option.statusOk
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = currentAudioRoute == option.route,
                            enabled = routeEnabled,
                            onClick = {
                                scope.launch { prefsRepo.setAudioRoute(option.route) }
                            },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = currentAudioRoute == option.route,
                        onClick = null,
                        enabled = routeEnabled,
                    )
                    Spacer(Modifier.size(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (routeEnabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                            )
                            option.badge?.let { ExperimentalBadge(it) }
                        }
                        Text(
                            text = option.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = option.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (option.statusOk) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            when (standardVoiceAvailability) {
                StandardVoiceAvailability.SignInRequired -> {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = if (standardVoiceSignInRouteHint != null) {
                            stringResource(R.string.voice_settings_signin_route_hint, standardVoiceSignInRouteHint)
                        } else {
                            stringResource(R.string.voice_settings_signin_default_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onOpenManage != null) {
                        TextButton(onClick = onOpenManage) {
                            Text(stringResource(R.string.voice_settings_sign_in_via_manage))
                        }
                    }
                }
                StandardVoiceAvailability.Unsupported -> {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = stringResource(R.string.voice_settings_unsupported_build_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> Unit
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Text-to-Speech — merged streaming output + basic synthesize fallback +
// enhanced overrides (WP-V3 card merge).
// ---------------------------------------------------------------------------

@Composable
private fun TextToSpeechCard(
    showStreaming: Boolean,
    voiceClient: RelayVoiceClient?,
    settingsViewModel: VoiceSettingsViewModel,
    configState: VoiceConfigUiState,
    voiceSettings: VoiceSettings,
    prefsRepo: VoicePreferencesRepository,
    voiceViewModel: VoiceViewModel,
) {
    val scope = rememberCoroutineScope()
    SectionCard(title = stringResource(R.string.voice_settings_tts_title)) {
        if (showStreaming) {
            Text(
                text = stringResource(R.string.voice_settings_streaming_output_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.voice_settings_streaming_output_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            StreamingVoiceOutputEditor(
                voiceClient = voiceClient,
                settingsViewModel = settingsViewModel,
                configState = configState,
                voiceViewModel = voiceViewModel,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        }

        Text(
            text = stringResource(R.string.voice_settings_basic_synthesize_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.voice_settings_basic_synthesize_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        val providerUnavailable = stringResource(R.string.voice_settings_provider_unavailable)
        val loadingLabel = stringResource(R.string.voice_settings_loading)
        val yesLabel = stringResource(R.string.voice_settings_yes)
        val noLabel = stringResource(R.string.voice_settings_no)
        ProviderRow(
            label = stringResource(R.string.voice_settings_label_provider),
            value = configState.voiceConfig?.tts?.provider
                ?: (configState.voiceConfigError?.let { providerUnavailable } ?: loadingLabel),
        )
        configState.voiceConfig?.tts?.let { tts ->
            ProviderRow(label = stringResource(R.string.voice_settings_label_enabled), value = if (tts.isEnabled) yesLabel else noLabel)
        }
        configState.voiceConfig?.tts?.model?.let { model ->
            ProviderRow(label = stringResource(R.string.voice_settings_label_model), value = model)
        }
        configState.voiceConfig?.tts?.displayVoice?.let { voice ->
            ProviderRow(label = stringResource(R.string.voice_settings_label_voice), value = voice)
        }
        configState.voiceConfigError?.let { error ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Enhanced-voice overrides ride the per-request /voice/synthesize body
        // (the basic path), so they live under this fallback subgroup, gated
        // behind an Advanced expander. Shown only when the relay advertises a
        // provider with a per-request enhanced surface (Gemini / xAI).
        configState.voiceConfig?.tts?.enhanced?.takeIf { it.supported }?.let { enhanced ->
            val providerLabel = when (enhanced.provider) {
                "gemini" -> stringResource(R.string.voice_settings_provider_gemini)
                "xai" -> stringResource(R.string.voice_settings_provider_xai)
                else -> enhanced.provider?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.voice_settings_label_provider)
            }
            var enhancedOpen by remember { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { enhancedOpen = !enhancedOpen }) {
                Text(
                    if (enhancedOpen) {
                        stringResource(R.string.voice_settings_hide_enhanced, providerLabel)
                    } else {
                        stringResource(R.string.voice_settings_advanced_enhanced, providerLabel)
                    }
                )
            }
            if (enhancedOpen) {
                Text(
                    text = stringResource(R.string.voice_settings_enhanced_body, providerLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                // Curated dropdown when the relay enumerates voices (Gemini);
                // free-text entry otherwise (xAI's catalog isn't enumerated).
                if (enhanced.voices.isNotEmpty()) {
                    val voiceChoices = buildList {
                        add(VoiceChoice(value = "", label = stringResource(R.string.voice_settings_server_default)))
                        enhanced.voices.forEach { add(VoiceChoice(value = it, label = it)) }
                    }
                    VoiceChoiceDropdown(
                        label = stringResource(R.string.voice_settings_label_voice),
                        value = voiceSettings.enhancedVoice,
                        choices = voiceChoices,
                        onValueChange = { scope.launch { prefsRepo.setEnhancedVoice(it) } },
                    )
                } else {
                    OutlinedTextField(
                        value = voiceSettings.enhancedVoice,
                        onValueChange = { scope.launch { prefsRepo.setEnhancedVoice(it) } },
                        label = { Text(stringResource(R.string.voice_settings_voice_blank_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))

                if (enhanced.models.isNotEmpty()) {
                    val modelChoices = buildList {
                        add(VoiceChoice(value = "", label = stringResource(R.string.voice_settings_server_default)))
                        enhanced.models.forEach { model ->
                            add(
                                VoiceChoice(
                                    value = model,
                                    label = model,
                                    detail = if (model in enhanced.audioTagModels) {
                                        stringResource(R.string.voice_settings_supports_tone_tags)
                                    } else {
                                        null
                                    },
                                ),
                            )
                        }
                    }
                    VoiceChoiceDropdown(
                        label = stringResource(R.string.voice_settings_label_model),
                        value = voiceSettings.enhancedModel,
                        choices = modelChoices,
                        onValueChange = { scope.launch { prefsRepo.setEnhancedModel(it) } },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // Tone/speech tags. xAI advertises no gating model list, so
                // they're always available there; Gemini requires a 3.1 TTS
                // model (matches upstream _gemini_model_supports_audio_tags).
                val effectiveModel =
                    voiceSettings.enhancedModel.ifBlank { configState.voiceConfig?.tts?.model.orEmpty() }
                val audioTagsSupported = enhanced.audioTagModels.isEmpty() ||
                    enhanced.audioTagModels.any { it.equals(effectiveModel, ignoreCase = true) } ||
                    (
                        effectiveModel.contains("gemini-3.1", ignoreCase = true) &&
                            effectiveModel.contains("tts", ignoreCase = true)
                    )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = enhanced.audioTagsLabel,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = if (audioTagsSupported) {
                                stringResource(R.string.voice_settings_audio_tags_supported)
                            } else {
                                stringResource(R.string.voice_settings_audio_tags_unsupported)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = voiceSettings.enhancedAudioTags && audioTagsSupported,
                        enabled = audioTagsSupported,
                        onCheckedChange = { scope.launch { prefsRepo.setEnhancedAudioTags(it) } },
                    )
                }

                if (enhanced.supportsPersona) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = voiceSettings.enhancedPersona,
                        onValueChange = { scope.launch { prefsRepo.setEnhancedPersona(it) } },
                        label = { Text(stringResource(R.string.voice_settings_voice_direction_label)) },
                        placeholder = { Text(stringResource(R.string.voice_settings_voice_direction_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }

                if (enhanced.supportsLanguage) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = voiceSettings.enhancedLanguage,
                        onValueChange = { scope.launch { prefsRepo.setEnhancedLanguage(it) } },
                        label = { Text(stringResource(R.string.voice_settings_language_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingVoiceOutputEditor(
    voiceClient: RelayVoiceClient?,
    settingsViewModel: VoiceSettingsViewModel,
    configState: VoiceConfigUiState,
    voiceViewModel: VoiceViewModel,
) {
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current

    var voiceOutputEnabled by remember { mutableStateOf(true) }
    var voiceOutputProvider by remember { mutableStateOf("") }
    var voiceOutputModel by remember { mutableStateOf("") }
    var voiceOutputVoice by remember { mutableStateOf("") }
    var voiceOutputSampleRate by remember { mutableStateOf("24000") }
    var voiceOutputLanguage by remember { mutableStateOf("en") }
    var voiceOutputLatency by remember { mutableStateOf(1f) }
    var voiceOutputFallback by remember { mutableStateOf(true) }
    var voiceOutputSpeechTags by remember { mutableStateOf(false) }
    var voiceOutputSaving by remember { mutableStateOf(false) }
    var voiceOutputManualOpen by remember { mutableStateOf(false) }

    val config = configState.voiceOutputConfig
    LaunchedEffect(
        config?.enabled,
        config?.default_provider,
        config?.default_model,
        config?.default_voice,
        config?.sample_rate,
        config?.language,
        config?.optimize_streaming_latency,
        config?.fallback_enabled,
        config?.auto_speech_tags,
    ) {
        val c = config ?: return@LaunchedEffect
        voiceOutputEnabled = c.enabled
        voiceOutputProvider = c.default_provider.orEmpty()
        voiceOutputModel = c.default_model.orEmpty()
        voiceOutputVoice = c.default_voice.orEmpty()
        voiceOutputSampleRate = c.sample_rate.toString()
        voiceOutputLanguage = c.language
        voiceOutputLatency = c.optimize_streaming_latency.toFloat()
        voiceOutputFallback = c.fallback_enabled
        voiceOutputSpeechTags = c.auto_speech_tags
    }

    // Refresh advertised options for a provider, re-seeding draft defaults from
    // the fresher metadata once it arrives. The VM owns the fetch + options map;
    // the applyDefaults callback mutates this editor's draft.
    fun refreshVoiceOutputProviderOptions(providerId: String, applyDefaults: Boolean) {
        settingsViewModel.refreshVoiceOutputProviderOptions(
            client = voiceClient,
            providerId = providerId,
            applyDefaults = applyDefaults,
        ) { provider ->
            if (voiceOutputProvider == providerId) {
                val selection = selectionWithProviderDefaults(
                    provider = provider,
                    model = voiceOutputModel,
                    voice = voiceOutputVoice,
                    sampleRate = voiceOutputSampleRate,
                    language = voiceOutputLanguage,
                )
                voiceOutputModel = selection.model
                voiceOutputVoice = selection.voice
                voiceOutputSampleRate = selection.sampleRate
                voiceOutputLanguage = selection.language
            }
        }
    }

    val activeLabel = stringResource(R.string.voice_settings_status_active)
    val unavailableLabel = stringResource(R.string.voice_settings_status_unavailable)
    val disabledLabel = stringResource(R.string.voice_settings_status_disabled)
    val loadingLabel = stringResource(R.string.voice_settings_loading)
    val streamingLabel = stringResource(R.string.voice_settings_render_streaming)
    val basicLabel = stringResource(R.string.voice_settings_render_basic)
    val fallbackOnLabel = stringResource(R.string.voice_settings_fallback_legacy)
    val offLabel = stringResource(R.string.voice_settings_off)
    ProviderRow(
        label = stringResource(R.string.voice_settings_label_status),
        value = when {
            config?.enabled == true -> activeLabel
            configState.voiceOutputConfigError != null -> unavailableLabel
            config != null -> disabledLabel
            else -> loadingLabel
        },
    )
    // Which path actually renders speech, for troubleshooting: streaming
    // /voice/output when enabled, else the basic /voice/synthesize fallback.
    ProviderRow(
        label = stringResource(R.string.voice_settings_render_path),
        value = when {
            config?.enabled == true -> streamingLabel
            config != null -> basicLabel
            else -> loadingLabel
        },
    )
    ProviderRow(
        label = stringResource(R.string.voice_settings_label_provider),
        value = config?.default_provider
            ?: (configState.voiceOutputConfigError?.let { unavailableLabel } ?: loadingLabel),
    )
    config?.default_model?.let { model ->
        ProviderRow(label = stringResource(R.string.voice_settings_label_model), value = model)
    }
    config?.default_voice?.let { voice ->
        ProviderRow(label = stringResource(R.string.voice_settings_label_voice), value = voice)
    }
    config?.let { c ->
        ProviderRow(label = stringResource(R.string.voice_settings_label_sample_rate), value = stringResource(R.string.voice_settings_hz_value, c.sample_rate))
        ProviderRow(label = stringResource(R.string.voice_settings_label_language), value = c.language)
        ProviderRow(label = stringResource(R.string.voice_settings_label_fallback), value = if (c.fallback_enabled) fallbackOnLabel else offLabel)
        ProviderRow(label = stringResource(R.string.voice_settings_label_advertised), value = voiceOutputProviderList(c))
        ProviderRow(label = stringResource(R.string.voice_settings_label_auth), value = voiceOutputAuthLabel(c))
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(R.string.voice_settings_label_enabled), style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = voiceOutputEnabled,
            onCheckedChange = { voiceOutputEnabled = it },
        )
    }

    val providers = mergedProviders(
        config?.providers.orEmpty(),
        configState.voiceOutputProviderOptions,
    )
    val selectedOutputProvider = providerFor(providers, voiceOutputProvider)
    VoiceChoiceDropdown(
        label = stringResource(R.string.voice_settings_label_provider),
        value = voiceOutputProvider,
        choices = providerChoices(providers, voiceOutputProvider),
        onValueChange = { providerId ->
            voiceOutputProvider = providerId
            providerFor(providers, providerId)?.let { provider ->
                val selection = selectionWithProviderDefaults(
                    provider = provider,
                    model = voiceOutputModel,
                    voice = voiceOutputVoice,
                    sampleRate = voiceOutputSampleRate,
                    language = voiceOutputLanguage,
                )
                voiceOutputModel = selection.model
                voiceOutputVoice = selection.voice
                voiceOutputSampleRate = selection.sampleRate
                voiceOutputLanguage = selection.language
            }
            refreshVoiceOutputProviderOptions(providerId, applyDefaults = true)
        },
        enabled = voiceClient != null,
    )
    providerOptionsStatusText(
        loading = configState.voiceOutputOptionsLoading == voiceOutputProvider,
        status = configState.voiceOutputOptionsStatus,
        refreshingMessage = stringResource(R.string.voice_settings_refreshing_options),
    )?.let { status ->
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    VoiceChoiceDropdown(
        label = stringResource(R.string.voice_settings_label_model),
        value = voiceOutputModel,
        choices = valueChoices(
            selectedOutputProvider?.models.orEmpty(),
            voiceOutputModel,
            selectedOutputProvider?.model_labels.orEmpty(),
        ),
        onValueChange = { model ->
            voiceOutputModel = model
            selectedOutputProvider?.let { provider ->
                voiceOutputVoice = voiceForModel(provider, model, voiceOutputVoice)
            }
        },
        enabled = voiceClient != null,
    )
    VoiceChoiceDropdown(
        label = stringResource(R.string.voice_settings_label_voice),
        value = voiceOutputVoice,
        choices = voiceChoices(
            selectedOutputProvider,
            voiceOutputVoice,
            voiceOutputModel,
        ),
        onValueChange = { voiceOutputVoice = it },
        enabled = voiceClient != null,
    )
    compatibilityNotice(
        selectedOutputProvider,
        voiceOutputModel,
        voiceOutputVoice,
        notAdvertisedMessage = stringResource(R.string.voice_settings_voice_not_advertised),
    )?.let { notice ->
        Text(
            text = notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    VoiceChoiceDropdown(
        label = stringResource(R.string.voice_settings_label_language),
        value = voiceOutputLanguage,
        choices = commonLanguages(voiceOutputLanguage, selectedOutputProvider),
        onValueChange = { voiceOutputLanguage = it },
        enabled = voiceClient != null,
    )
    VoiceChoiceDropdown(
        label = stringResource(R.string.voice_settings_label_sample_rate),
        value = voiceOutputSampleRate,
        choices = intChoices(selectedOutputProvider?.sample_rates.orEmpty(), voiceOutputSampleRate),
        onValueChange = { voiceOutputSampleRate = it },
        enabled = voiceClient != null,
    )

    AdvancedManualToggle(
        expanded = voiceOutputManualOpen,
        onExpandedChange = { voiceOutputManualOpen = it },
    )
    if (voiceOutputManualOpen) {
        OutlinedTextField(
            value = voiceOutputProvider,
            onValueChange = { voiceOutputProvider = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.voice_settings_provider_id)) },
        )
        OutlinedTextField(
            value = voiceOutputModel,
            onValueChange = { voiceOutputModel = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.voice_settings_model_id)) },
        )
        OutlinedTextField(
            value = voiceOutputVoice,
            onValueChange = { voiceOutputVoice = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.voice_settings_voice_id)) },
        )
        OutlinedTextField(
            value = voiceOutputSampleRate,
            onValueChange = { voiceOutputSampleRate = it.filter(Char::isDigit) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.voice_settings_label_sample_rate)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = voiceOutputLanguage,
            onValueChange = { voiceOutputLanguage = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.voice_settings_label_language)) },
        )
    }

    Text(
        text = stringResource(R.string.voice_settings_streaming_latency, voiceOutputLatency.toInt()),
        style = MaterialTheme.typography.labelLarge,
    )
    Slider(
        value = voiceOutputLatency,
        onValueChange = { voiceOutputLatency = it },
        valueRange = 0f..1f,
        steps = 0,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.voice_settings_label_fallback), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.voice_settings_fallback_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = voiceOutputFallback,
            onCheckedChange = { voiceOutputFallback = it },
        )
    }

    // xAI expressive speech tags on the streaming renderer (xai_tts only).
    if (voiceOutputProvider == "xai_tts") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.voice_settings_expressive_tags), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.voice_settings_expressive_tags_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = voiceOutputSpeechTags,
                onCheckedChange = { voiceOutputSpeechTags = it },
            )
        }
    }

    val sampleRateMustBeNumber = stringResource(R.string.voice_settings_sample_rate_must_be_number)
    val providerNotValidMsg = stringResource(R.string.voice_settings_provider_not_valid)
    val savedWithWarningFmt = stringResource(R.string.voice_settings_saved_with_warning_format)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = {
                val client = voiceClient ?: return@FilledTonalButton
                val sampleRate = voiceOutputSampleRate.toIntOrNull()
                if (sampleRate == null) {
                    settingsViewModel.setVoiceOutputError(sampleRateMustBeNumber)
                    return@FilledTonalButton
                }
                scope.launch {
                    voiceOutputSaving = true
                    val validationResult = client.validateVoiceOutputProvider(
                        providerId = voiceOutputProvider,
                        model = voiceOutputModel,
                        voice = voiceOutputVoice,
                        sampleRate = sampleRate,
                        language = voiceOutputLanguage,
                    )
                    validationIssue(validationResult.getOrNull(), providerNotValidMsg)?.let { issue ->
                        voiceOutputSaving = false
                        settingsViewModel.setVoiceOutputError(issue)
                        return@launch
                    }
                    if (validationResult.isFailure) {
                        voiceOutputSaving = false
                        val human = classifyError(
                            validationResult.exceptionOrNull(),
                            context = "voice_config",
                        )
                        settingsViewModel.setVoiceOutputError(human.body)
                        snackbarHost.showHumanError(human)
                        return@launch
                    }
                    validationWarning(validationResult.getOrNull(), savedWithWarningFmt)?.let { warning ->
                        settingsViewModel.setVoiceOutputOptionsStatus(warning)
                    }
                    val result = client.updateVoiceOutputConfig(
                        enabled = voiceOutputEnabled,
                        provider = voiceOutputProvider,
                        model = voiceOutputModel,
                        voice = voiceOutputVoice,
                        sampleRate = sampleRate,
                        language = voiceOutputLanguage,
                        codec = "pcm",
                        optimizeStreamingLatency = voiceOutputLatency.toInt(),
                        autoSpeechTags = voiceOutputSpeechTags,
                        fallbackEnabled = voiceOutputFallback,
                    )
                    voiceOutputSaving = false
                    if (result.isSuccess) {
                        settingsViewModel.setVoiceOutputConfig(result.getOrNull())
                    } else {
                        val human = classifyError(
                            result.exceptionOrNull(),
                            context = "voice_config",
                        )
                        settingsViewModel.setVoiceOutputError(human.body)
                        snackbarHost.showHumanError(human)
                    }
                }
            },
            enabled = !voiceOutputSaving && voiceClient != null,
            modifier = Modifier.weight(1f),
        ) {
            Text(if (voiceOutputSaving) stringResource(R.string.voice_settings_saving) else stringResource(R.string.voice_settings_save))
        }
        FilledTonalButton(
            onClick = {
                val client = voiceClient ?: return@FilledTonalButton
                val sampleRate = voiceOutputSampleRate.toIntOrNull()
                if (sampleRate == null) {
                    settingsViewModel.setVoiceOutputError(sampleRateMustBeNumber)
                    return@FilledTonalButton
                }
                scope.launch {
                    voiceOutputSaving = true
                    val validationResult = client.validateVoiceOutputProvider(
                        providerId = voiceOutputProvider,
                        model = voiceOutputModel,
                        voice = voiceOutputVoice,
                        sampleRate = sampleRate,
                        language = voiceOutputLanguage,
                    )
                    validationIssue(validationResult.getOrNull(), providerNotValidMsg)?.let { issue ->
                        voiceOutputSaving = false
                        settingsViewModel.setVoiceOutputError(issue)
                        return@launch
                    }
                    if (validationResult.isFailure) {
                        voiceOutputSaving = false
                        val human = classifyError(
                            validationResult.exceptionOrNull(),
                            context = "voice_config",
                        )
                        settingsViewModel.setVoiceOutputError(human.body)
                        snackbarHost.showHumanError(human)
                        return@launch
                    }
                    validationWarning(validationResult.getOrNull(), savedWithWarningFmt)?.let { warning ->
                        settingsViewModel.setVoiceOutputOptionsStatus(warning)
                    }
                    val result = client.updateVoiceOutputConfig(
                        enabled = voiceOutputEnabled,
                        provider = voiceOutputProvider,
                        model = voiceOutputModel,
                        voice = voiceOutputVoice,
                        sampleRate = sampleRate,
                        language = voiceOutputLanguage,
                        codec = "pcm",
                        optimizeStreamingLatency = voiceOutputLatency.toInt(),
                        autoSpeechTags = voiceOutputSpeechTags,
                        fallbackEnabled = voiceOutputFallback,
                    )
                    voiceOutputSaving = false
                    if (result.isSuccess) {
                        settingsViewModel.setVoiceOutputConfig(result.getOrNull())
                        voiceViewModel.testVoice()
                    } else {
                        val human = classifyError(
                            result.exceptionOrNull(),
                            context = "voice_config",
                        )
                        settingsViewModel.setVoiceOutputError(human.body)
                        snackbarHost.showHumanError(human)
                    }
                }
            },
            enabled = !voiceOutputSaving && voiceClient != null,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.voice_settings_save_and_test))
        }
    }
    configState.voiceOutputConfigError?.let { error ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

// ---------------------------------------------------------------------------
// Realtime Agent (Relay-only engine).
// ---------------------------------------------------------------------------

@Composable
private fun RealtimeAgentCard(
    voiceClient: RelayVoiceClient?,
    settingsViewModel: VoiceSettingsViewModel,
    configState: VoiceConfigUiState,
    voiceSettings: VoiceSettings,
    prefsRepo: VoicePreferencesRepository,
) {
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current

    var realtimeEnabled by remember { mutableStateOf(true) }
    var realtimeProvider by remember { mutableStateOf("") }
    var realtimeModel by remember { mutableStateOf("") }
    var realtimeVoice by remember { mutableStateOf("") }
    var realtimeSampleRate by remember { mutableStateOf("24000") }
    var realtimeSaving by remember { mutableStateOf(false) }
    var realtimeManualOpen by remember { mutableStateOf(false) }
    var showDeliveryInfo by remember { mutableStateOf(false) }

    val config = configState.realtimeConfig
    LaunchedEffect(
        config?.enabled,
        config?.default_provider,
        config?.sample_rate,
    ) {
        val c = config ?: return@LaunchedEffect
        realtimeEnabled = c.enabled
        realtimeProvider = c.default_provider.orEmpty()
        realtimeSampleRate = c.sample_rate.toString()
    }
    LaunchedEffect(
        config?.default_model,
        config?.default_voice,
        voiceSettings.realtimeModel,
        voiceSettings.realtimeVoice,
    ) {
        val c = config ?: return@LaunchedEffect
        realtimeModel = voiceSettings.realtimeModel.ifBlank { c.default_model.orEmpty() }
        realtimeVoice = voiceSettings.realtimeVoice.ifBlank { c.default_voice.orEmpty() }
    }

    fun refreshRealtimeProviderOptions(providerId: String, applyDefaults: Boolean) {
        settingsViewModel.refreshRealtimeProviderOptions(
            client = voiceClient,
            providerId = providerId,
            applyDefaults = applyDefaults,
        ) { provider ->
            if (realtimeProvider == providerId) {
                val selection = selectionWithProviderDefaults(
                    provider = provider,
                    model = realtimeModel,
                    voice = realtimeVoice,
                    sampleRate = realtimeSampleRate,
                    language = null,
                )
                realtimeModel = selection.model
                realtimeVoice = selection.voice
                realtimeSampleRate = selection.sampleRate
            }
        }
    }

    SectionCard(title = stringResource(R.string.voice_settings_realtime_agent_title), badge = stringResource(R.string.voice_settings_experimental)) {
        Text(
            text = stringResource(R.string.voice_settings_realtime_agent_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.voice_settings_detailed_trace), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.voice_settings_detailed_trace_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = voiceSettings.realtimeTraceDetails,
                onCheckedChange = { enabled ->
                    scope.launch { prefsRepo.setRealtimeTraceDetails(enabled) }
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.voice_settings_persistent_session), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.voice_settings_persistent_session_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = voiceSettings.realtimePersistentSession,
                onCheckedChange = { enabled ->
                    scope.launch { prefsRepo.setRealtimePersistentSession(enabled) }
                },
            )
        }
        Spacer(Modifier.height(4.dp))
        val availableLabel = stringResource(R.string.voice_settings_status_available)
        val unavailableLabel = stringResource(R.string.voice_settings_status_unavailable)
        val disabledLabel = stringResource(R.string.voice_settings_status_disabled)
        val loadingLabel = stringResource(R.string.voice_settings_loading)
        ProviderRow(
            label = stringResource(R.string.voice_settings_label_status),
            value = when {
                config?.enabled == true -> availableLabel
                configState.realtimeConfigError != null -> unavailableLabel
                config != null -> disabledLabel
                else -> loadingLabel
            },
        )
        ProviderRow(
            label = stringResource(R.string.voice_settings_label_provider),
            value = config?.default_provider
                ?: (configState.realtimeConfigError?.let { unavailableLabel } ?: loadingLabel),
        )
        realtimeModel.takeIf { it.isNotBlank() }?.let { model ->
            ProviderRow(label = "Model", value = model)
        }
        realtimeVoice.takeIf { it.isNotBlank() }?.let { voice ->
            ProviderRow(label = "Voice", value = voice)
        }
        config?.let { c ->
            ProviderRow(label = stringResource(R.string.voice_settings_label_advertised), value = realtimeProviderList(c))
            ProviderRow(label = stringResource(R.string.voice_settings_label_auth), value = realtimeAuthLabel(c))
        }

        config?.promotion?.let { promo ->
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.voice_settings_background_tasks), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.voice_settings_background_tasks_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.voice_settings_promote_long_tasks), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = stringResource(R.string.voice_settings_promote_long_tasks_desc, promo.promoteAfterMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = promo.enabled,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            val client = voiceClient ?: return@launch
                            val result = client.updateRealtimeAgentPromotion(
                                promotionEnabled = enabled,
                            )
                            if (result.isSuccess) settingsViewModel.setRealtimeConfig(result.getOrNull())
                        }
                    },
                )
            }
            if (promo.enabled) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.voice_settings_spoken_handoff), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = stringResource(R.string.voice_settings_spoken_handoff_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = promo.spokenHandoff,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                val client = voiceClient ?: return@launch
                                val result = client.updateRealtimeAgentPromotion(
                                    spokenHandoff = enabled,
                                )
                                if (result.isSuccess) settingsViewModel.setRealtimeConfig(result.getOrNull())
                            }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "When the answer is ready",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    IconButton(
                        onClick = { showDeliveryInfo = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "Delivery mode details",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                val deliveryOptions = listOf(
                    "speak_verbatim",
                    "speak_when_idle",
                    "notify_then_speak",
                    "visual_only",
                )
                val deliveryLabels = listOf("Exact", "Summary", "Notify", "Show")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    deliveryOptions.forEachIndexed { index, option ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = deliveryOptions.size,
                            ),
                            onClick = {
                                scope.launch {
                                    val client = voiceClient ?: return@launch
                                    val result = client.updateRealtimeAgentPromotion(
                                        resultDelivery = option,
                                    )
                                    if (result.isSuccess) settingsViewModel.setRealtimeConfig(result.getOrNull())
                                }
                            },
                            selected = option == promo.resultDelivery,
                        ) {
                            Text(
                                deliveryLabels[index],
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }

        if (showDeliveryInfo) {
            DeliveryModeInfoDialog(onDismiss = { showDeliveryInfo = false })
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.voice_settings_label_enabled), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.voice_settings_realtime_defaults_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = realtimeEnabled,
                onCheckedChange = { realtimeEnabled = it },
            )
        }

        val realtimeProviders = mergedProviders(
            config?.providers.orEmpty(),
            configState.realtimeProviderOptions,
        )
            .filter { it.supports_realtime_agent_native }
        val selectedRealtimeProvider = providerFor(
            realtimeProviders,
            realtimeProvider,
        )
        VoiceChoiceDropdown(
            label = stringResource(R.string.voice_settings_label_provider),
            value = realtimeProvider,
            choices = providerChoices(realtimeProviders, realtimeProvider),
            onValueChange = { providerId ->
                realtimeProvider = providerId
                providerFor(realtimeProviders, providerId)?.let { provider ->
                    val selection = selectionWithProviderDefaults(
                        provider = provider,
                        model = realtimeModel,
                        voice = realtimeVoice,
                        sampleRate = realtimeSampleRate,
                        language = null,
                    )
                    realtimeModel = selection.model
                    realtimeVoice = selection.voice
                    realtimeSampleRate = selection.sampleRate
                }
                refreshRealtimeProviderOptions(providerId, applyDefaults = true)
            },
            enabled = voiceClient != null,
        )
        providerOptionsStatusText(
            loading = configState.realtimeOptionsLoading == realtimeProvider,
            status = configState.realtimeOptionsStatus,
            refreshingMessage = stringResource(R.string.voice_settings_refreshing_options),
        )?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        VoiceChoiceDropdown(
            label = stringResource(R.string.voice_settings_label_model),
            value = realtimeModel,
            choices = valueChoices(
                selectedRealtimeProvider?.models.orEmpty(),
                realtimeModel,
                selectedRealtimeProvider?.model_labels.orEmpty(),
            ),
            onValueChange = { model ->
                realtimeModel = model
                selectedRealtimeProvider?.let { provider ->
                    realtimeVoice = voiceForModel(provider, model, realtimeVoice)
                }
                scope.launch {
                    prefsRepo.setRealtimeSelection(realtimeModel, realtimeVoice)
                }
            },
            enabled = voiceClient != null,
        )
        VoiceChoiceDropdown(
            label = stringResource(R.string.voice_settings_label_voice),
            value = realtimeVoice,
            choices = voiceChoices(
                selectedRealtimeProvider,
                realtimeVoice,
                realtimeModel,
            ),
            onValueChange = { voice ->
                realtimeVoice = voice
                scope.launch { prefsRepo.setRealtimeVoice(voice) }
            },
            enabled = voiceClient != null,
        )
        compatibilityNotice(
            selectedRealtimeProvider,
            realtimeModel,
            realtimeVoice,
            notAdvertisedMessage = stringResource(R.string.voice_settings_voice_not_advertised),
        )?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        VoiceChoiceDropdown(
            label = stringResource(R.string.voice_settings_label_sample_rate),
            value = realtimeSampleRate,
            choices = intChoices(
                selectedRealtimeProvider?.sample_rates.orEmpty(),
                realtimeSampleRate,
            ),
            onValueChange = { realtimeSampleRate = it },
            enabled = voiceClient != null,
        )

        AdvancedManualToggle(
            expanded = realtimeManualOpen,
            onExpandedChange = { realtimeManualOpen = it },
        )
        if (realtimeManualOpen) {
            OutlinedTextField(
                value = realtimeProvider,
                onValueChange = { realtimeProvider = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.voice_settings_provider_id)) },
            )
            OutlinedTextField(
                value = realtimeModel,
                onValueChange = { model ->
                    realtimeModel = model
                    scope.launch { prefsRepo.setRealtimeModel(model) }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.voice_settings_model_id)) },
            )
            OutlinedTextField(
                value = realtimeVoice,
                onValueChange = { voice ->
                    realtimeVoice = voice
                    scope.launch { prefsRepo.setRealtimeVoice(voice) }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.voice_settings_voice_id)) },
            )
            OutlinedTextField(
                value = realtimeSampleRate,
                onValueChange = { realtimeSampleRate = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.voice_settings_label_sample_rate)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        val realtimeSampleRateMustBeNumber = stringResource(R.string.voice_settings_sample_rate_must_be_number)
        val providerNotValidMsg = stringResource(R.string.voice_settings_provider_not_valid)
        val savedWithWarningFmt = stringResource(R.string.voice_settings_saved_with_warning_format)
        FilledTonalButton(
            onClick = {
                val client = voiceClient ?: return@FilledTonalButton
                val sampleRate = realtimeSampleRate.toIntOrNull()
                if (sampleRate == null) {
                    settingsViewModel.setRealtimeError(realtimeSampleRateMustBeNumber)
                    return@FilledTonalButton
                }
                scope.launch {
                    realtimeSaving = true
                    val validationResult = client.validateRealtimeAgentProvider(
                        providerId = realtimeProvider,
                        model = realtimeModel,
                        voice = realtimeVoice,
                        sampleRate = sampleRate,
                    )
                    validationIssue(validationResult.getOrNull(), providerNotValidMsg)?.let { issue ->
                        realtimeSaving = false
                        settingsViewModel.setRealtimeError(issue)
                        return@launch
                    }
                    if (validationResult.isFailure) {
                        realtimeSaving = false
                        val human = classifyError(
                            validationResult.exceptionOrNull(),
                            context = "voice_config",
                        )
                        settingsViewModel.setRealtimeError(human.body)
                        snackbarHost.showHumanError(human)
                        return@launch
                    }
                    validationWarning(validationResult.getOrNull(), savedWithWarningFmt)?.let { warning ->
                        settingsViewModel.setRealtimeOptionsStatus(warning)
                    }
                    val result = client.updateRealtimeAgentConfig(
                        enabled = realtimeEnabled,
                        provider = realtimeProvider,
                        model = realtimeModel,
                        voice = realtimeVoice,
                        sampleRate = sampleRate,
                    )
                    realtimeSaving = false
                    if (result.isSuccess) {
                        prefsRepo.setRealtimeSelection(realtimeModel, realtimeVoice)
                        settingsViewModel.setRealtimeConfig(result.getOrNull())
                    } else {
                        val human = classifyError(
                            result.exceptionOrNull(),
                            context = "voice_config",
                        )
                        settingsViewModel.setRealtimeError(human.body)
                        snackbarHost.showHumanError(human)
                    }
                }
            },
            enabled = !realtimeSaving && voiceClient != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (realtimeSaving) stringResource(R.string.voice_settings_saving) else stringResource(R.string.voice_settings_save_realtime_agent))
        }
        configState.realtimeConfigError?.let { error ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DeliveryModeInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Answer delivery") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DeliveryModeInfoRow(
                    label = "Exact",
                    body = "Recommended. The realtime voice reads the Hermes answer word for word, falling back to standard TTS only if it goes off-script.",
                )
                DeliveryModeInfoRow(
                    label = "Summary",
                    body = "The realtime voice rephrases the result in its own words. More conversational, less faithful to the exact answer.",
                )
                DeliveryModeInfoRow(
                    label = "Notify",
                    body = "Shows that the answer is ready first, then speaks when you re-engage.",
                )
                DeliveryModeInfoRow(
                    label = "Show",
                    body = "Keeps the completed answer visual only.",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        },
    )
}

@Composable
private fun DeliveryModeInfoRow(label: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Global voice controls — interaction mode + silence threshold.
// ---------------------------------------------------------------------------

@Composable
private fun GlobalVoiceControlsCard(
    voiceSettings: VoiceSettings,
    prefsRepo: VoicePreferencesRepository,
    voiceViewModel: VoiceViewModel,
) {
    val scope = rememberCoroutineScope()
    SectionCard(title = stringResource(R.string.voice_settings_global_controls_title)) {
        Text(
            text = stringResource(R.string.voice_settings_global_controls_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(R.string.voice_settings_interaction_mode),
            style = MaterialTheme.typography.labelLarge,
        )
        val currentMode = voiceSettings.interactionMode
        listOf(
            "tap" to stringResource(R.string.voice_settings_interaction_tap),
            "hold" to stringResource(R.string.voice_settings_interaction_hold),
            "continuous" to stringResource(R.string.voice_settings_interaction_continuous),
        ).forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = currentMode == value,
                        onClick = {
                            scope.launch { prefsRepo.setInteractionMode(value) }
                            voiceViewModel.setInteractionMode(
                                when (value) {
                                    "hold" -> InteractionMode.HoldToTalk
                                    "continuous" -> InteractionMode.Continuous
                                    else -> InteractionMode.TapToTalk
                                }
                            )
                        },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = currentMode == value,
                    onClick = null,
                )
                Spacer(Modifier.size(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = stringResource(R.string.voice_settings_silence_threshold, "%.2f".format(voiceSettings.silenceThresholdMs / 1000f)),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = stringResource(R.string.voice_settings_silence_threshold_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 750 ms..5 s in 250 ms steps (18 stops) so the desktop-matching 1.25s
        // default lands on a stop. Idle/no-speech (12 s) and the 60 s hard turn
        // cap are fixed in VoiceViewModel, not exposed here.
        Slider(
            value = voiceSettings.silenceThresholdMs.toFloat(),
            onValueChange = { newValue ->
                scope.launch { prefsRepo.setSilenceThresholdMs(newValue.toLong()) }
            },
            valueRange = 750f..5000f,
            steps = 16,
        )
    }
}

// ---------------------------------------------------------------------------
// Barge-in.
// ---------------------------------------------------------------------------

@Composable
private fun BargeInCard(
    bargeInPrefs: BargeInPreferences,
    aecAvailable: Boolean,
    settingsViewModel: VoiceSettingsViewModel,
) {
    SectionCard(title = stringResource(R.string.voice_settings_barge_in_title), badge = stringResource(R.string.voice_settings_experimental)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.voice_settings_barge_in_master), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.voice_settings_barge_in_master_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = bargeInPrefs.enabled,
                onCheckedChange = { settingsViewModel.setBargeInEnabled(it) },
            )
        }

        if (!aecAvailable) {
            // Badge sits directly below the master toggle so the explanation
            // stays adjacent to the control. We do NOT disable the toggle —
            // barge-in still works on AEC-less devices, just more
            // false-trigger-prone.
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.voice_settings_barge_in_aec_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (bargeInPrefs.enabled) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.voice_settings_sensitivity),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = stringResource(R.string.voice_settings_sensitivity_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val sensitivityOptions = listOf(
                BargeInSensitivity.Off,
                BargeInSensitivity.Low,
                BargeInSensitivity.Default,
                BargeInSensitivity.High,
            )
            val sensitivityLabels = listOf(
                stringResource(R.string.voice_settings_sensitivity_off),
                stringResource(R.string.voice_settings_sensitivity_low),
                stringResource(R.string.voice_settings_sensitivity_default),
                stringResource(R.string.voice_settings_sensitivity_high),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                sensitivityOptions.forEachIndexed { index, option ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = sensitivityOptions.size,
                        ),
                        onClick = { settingsViewModel.setBargeInSensitivity(option) },
                        selected = option == bargeInPrefs.sensitivity,
                    ) {
                        Text(
                            sensitivityLabels[index],
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.voice_settings_resume_after_interruption),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.voice_settings_resume_after_interruption_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = bargeInPrefs.resumeAfterInterruption,
                    onCheckedChange = {
                        settingsViewModel.setResumeAfterInterruption(it)
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Speech-to-Text — provider/model labels (dead language radios moved to the
// Coming-soon expander; WP-V3).
// ---------------------------------------------------------------------------

@Composable
private fun SpeechToTextCard(
    relayVoiceReady: Boolean,
    configState: VoiceConfigUiState,
) {
    SectionCard(title = stringResource(R.string.voice_settings_stt_title)) {
        if (!relayVoiceReady) {
            Text(
                text = stringResource(R.string.voice_settings_stt_standard_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val unavailableLabel = stringResource(R.string.voice_settings_provider_unavailable)
            val loadingLabel = stringResource(R.string.voice_settings_loading)
            val yesLabel = stringResource(R.string.voice_settings_yes)
            val noLabel = stringResource(R.string.voice_settings_no)
            ProviderRow(
                label = stringResource(R.string.voice_settings_label_provider),
                value = configState.voiceConfig?.stt?.provider
                    ?: (configState.voiceConfigError?.let { unavailableLabel } ?: loadingLabel),
            )
            configState.voiceConfig?.stt?.let { stt ->
                ProviderRow(label = stringResource(R.string.voice_settings_label_enabled), value = if (stt.isEnabled) yesLabel else noLabel)
            }
            configState.voiceConfig?.stt?.model?.let { model ->
                ProviderRow(label = stringResource(R.string.voice_settings_label_model), value = model)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Test current engine.
// ---------------------------------------------------------------------------

@Composable
private fun TestCurrentEngineCard(
    currentEngine: VoiceEngineMode,
    relayVoiceReady: Boolean,
    configState: VoiceConfigUiState,
    selectedProfile: Profile?,
    voiceViewModel: VoiceViewModel,
) {
    val context = LocalContext.current
    var currentEngineTestRunning by remember { mutableStateOf(false) }
    var currentEngineTestResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentEngine) {
        currentEngineTestRunning = false
        currentEngineTestResult = null
    }

    val playingSampleLabel = stringResource(R.string.voice_settings_playing_output_sample)
    val outputSuccessLabel = stringResource(R.string.voice_settings_output_test_success)
    val playbackErrorLabel = stringResource(R.string.voice_settings_playback_error)
    val startingSampleLabel = stringResource(R.string.voice_settings_starting_realtime_sample)
    val realtimeSuccessLabel = stringResource(R.string.voice_settings_realtime_test_success)
    val providerErrorLabel = stringResource(R.string.voice_settings_provider_error)
    val loadingLabel = stringResource(R.string.voice_settings_loading)
    val notSetLabel = stringResource(R.string.voice_settings_not_set)
    val hermesEngineLabel = stringResource(R.string.voice_settings_engine_hermes)
    val realtimeEngineLabel = stringResource(R.string.voice_settings_engine_realtime)
    val hermesRouteLabel = stringResource(R.string.voice_settings_route_hermes)
    val serverConfiguredTtsLabel = stringResource(R.string.voice_settings_server_configured_tts)
    val outputTestFailedFmt = context.getString(R.string.voice_settings_output_test_failed_format)
    val realtimeTestFailedFmt = context.getString(R.string.voice_settings_realtime_test_failed_format)

    SectionCard(title = stringResource(R.string.voice_settings_test_engine_title)) {
        ProviderRow(
            label = stringResource(R.string.voice_settings_label_engine),
            value = when (currentEngine) {
                VoiceEngineMode.HermesVoiceOutput -> hermesEngineLabel
                VoiceEngineMode.RealtimeAgent -> realtimeEngineLabel
            },
        )
        if (currentEngine == VoiceEngineMode.HermesVoiceOutput) {
            if (relayVoiceReady) {
                ProviderRow(
                    label = stringResource(R.string.voice_settings_label_profile),
                    value = configState.voiceOutputConfig?.let { config ->
                        voiceProfileLabel(config.profile, selectedProfile)
                    } ?: voiceProfileLabel(null, selectedProfile),
                )
                ProviderRow(
                    label = stringResource(R.string.voice_settings_label_voice),
                    value = listOfNotNull(
                        configState.voiceOutputConfig?.default_provider,
                        configState.voiceOutputConfig?.default_model,
                        configState.voiceOutputConfig?.default_voice,
                    ).joinToString(" / ").ifBlank { loadingLabel },
                )
            } else {
                ProviderRow(label = stringResource(R.string.voice_settings_label_route), value = hermesRouteLabel)
                ProviderRow(label = stringResource(R.string.voice_settings_label_voice), value = serverConfiguredTtsLabel)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = stringResource(R.string.voice_settings_play_sample_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = {
                    currentEngineTestRunning = true
                    currentEngineTestResult = playingSampleLabel
                    voiceViewModel.testVoice { result ->
                        currentEngineTestRunning = false
                        currentEngineTestResult = if (result.isSuccess) {
                            outputSuccessLabel
                        } else {
                            val msg = result.exceptionOrNull()?.message ?: playbackErrorLabel
                            outputTestFailedFmt.format(msg)
                        }
                    }
                },
                enabled = !currentEngineTestRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.voice_settings_play_output_test))
            }
        } else {
            // Realtime: show the saved/active realtime config (the editor card
            // holds the unsaved draft; here we report what's actually live).
            val realtime = configState.realtimeConfig
            ProviderRow(label = stringResource(R.string.voice_settings_label_provider), value = realtime?.default_provider?.takeIf { it.isNotBlank() } ?: notSetLabel)
            ProviderRow(label = stringResource(R.string.voice_settings_label_model), value = realtime?.default_model?.takeIf { it.isNotBlank() } ?: notSetLabel)
            ProviderRow(label = stringResource(R.string.voice_settings_label_voice), value = realtime?.default_voice?.takeIf { it.isNotBlank() } ?: notSetLabel)
            ProviderRow(label = stringResource(R.string.voice_settings_label_sample_rate), value = stringResource(R.string.voice_settings_hz_value, realtime?.sample_rate ?: 0))
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = stringResource(R.string.voice_settings_realtime_test_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = {
                    currentEngineTestRunning = true
                    currentEngineTestResult = startingSampleLabel
                    voiceViewModel.testRealtimeAgent { result ->
                        currentEngineTestRunning = false
                        currentEngineTestResult = if (result.isSuccess) {
                            realtimeSuccessLabel
                        } else {
                            val msg = result.exceptionOrNull()?.message ?: providerErrorLabel
                            realtimeTestFailedFmt.format(msg)
                        }
                    }
                },
                enabled = !currentEngineTestRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.voice_settings_play_realtime_test))
            }
        }
        currentEngineTestResult?.let { result ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = result,
                style = MaterialTheme.typography.bodySmall,
                color = if (
                    result == playingSampleLabel ||
                    result == startingSampleLabel ||
                    result == outputSuccessLabel ||
                    result == realtimeSuccessLabel
                ) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

// ===========================================================================
// Standard-path server voice config editor.
//
// Edits the host's tts.*/stt.* config via the dashboard /api/config surface —
// the same config.yaml the dashboard's own Audio settings write, and the same
// values the standard (no-Relay) /api/audio/* voice path reads. Standard voice
// is host-global (see VoiceScopeBanner), so writes target the launch profile's
// config (profile = null). Schema (/api/config/schema) drives field rendering;
// values (/api/config) seed current state; PUT writes the whole tree back with
// the edited leaves merged in. Includes the ElevenLabs voice picker — the one
// genuine desktop voice feature the app previously lacked.
// ===========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StandardVoiceServerConfigCard(
    client: DashboardApiClient,
    onOpenManage: (() -> Unit)?,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var values by remember { mutableStateOf<JsonObject?>(null) }
    var fields by remember { mutableStateOf<List<ConfigSchemaField>>(emptyList()) }
    var elevenVoices by remember { mutableStateOf<ElevenLabsVoices?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var signInRequired by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var edits by remember { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }
    var reloadNonce by remember { mutableStateOf(0) }

    val signInToEditMsg = stringResource(R.string.voice_settings_signin_to_edit)
    val couldNotLoadMsg = stringResource(R.string.voice_settings_could_not_load_config)
    val voiceConfigSavedMsg = stringResource(R.string.voice_settings_voice_config_saved)
    val saveFailedMsg = stringResource(R.string.voice_settings_save_failed)
    val signInRequiredMsg = stringResource(R.string.voice_settings_signin_required)
    val couldNotLoadConfigMsg = stringResource(R.string.voice_settings_could_not_load_config)

    LaunchedEffect(client, reloadNonce) {
        loading = true
        error = null
        signInRequired = false
        val cfg = client.getConfig()
        val sch = client.getConfigSchema()
        if (cfg.isSuccess && sch.isSuccess) {
            values = cfg.getOrNull()
            fields = voiceConfigFields(parseConfigSchema(sch.getOrNull() ?: JsonObject(emptyMap())))
            edits = emptyMap()
            // Best-effort; only consulted when the TTS provider is elevenlabs.
            elevenVoices = client.getElevenLabsVoices().getOrNull()
        } else {
            val ex = cfg.exceptionOrNull() ?: sch.exceptionOrNull()
            val msg = ex?.message.orEmpty()
            signInRequired = msg.contains("401") || msg.contains("403") ||
                msg.contains("sign-in", ignoreCase = true)
            error = if (signInRequired) {
                signInToEditMsg
            } else {
                ex?.message ?: couldNotLoadMsg
            }
        }
        loading = false
    }

    SectionCard(title = stringResource(R.string.voice_settings_server_config_title), badge = stringResource(R.string.voice_settings_standard_badge)) {
        Text(
            text = stringResource(R.string.voice_settings_server_config_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val tree = values
        if (tree != null) {
            // current = pending edit, else the loaded value at the dot-path.
            fun current(key: String): JsonElement? = edits[key] ?: configValueAt(tree, key)
            fun currentString(key: String): String =
                (current(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
            fun setEdit(key: String, value: JsonElement) { edits = edits + (key to value) }

            val ttsProvider = currentString("tts.provider")
            val sttProvider = currentString("stt.provider")

            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.voice_settings_tts_title), style = MaterialTheme.typography.labelLarge)

            fields.firstOrNull { it.key == "tts.provider" }?.let { field ->
                ConfigFieldRow(field, current(field.key), null) { setEdit(field.key, it) }
            }
            if (ttsProvider.isNotBlank()) {
                fields.filter { it.key.startsWith("tts.$ttsProvider.") }.forEach { field ->
                    val isElevenVoice = field.key == "tts.elevenlabs.voice_id" &&
                        elevenVoices?.available == true
                    ConfigFieldRow(
                        field = field,
                        current = current(field.key),
                        overrideChoices = if (isElevenVoice) {
                            elevenVoices?.voices?.map { VoiceChoice(value = it.voiceId, label = it.label) }
                        } else {
                            null
                        },
                        onEdit = { setEdit(field.key, it) },
                    )
                }
                if (ttsProvider == "elevenlabs" && elevenVoices?.available == false) {
                    Text(
                        text = stringResource(R.string.voice_settings_no_elevenlabs_key),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.voice_settings_stt_title), style = MaterialTheme.typography.labelLarge)

            fields.firstOrNull { it.key == "stt.enabled" }?.let { field ->
                ConfigFieldRow(field, current(field.key), null) { setEdit(field.key, it) }
            }
            fields.firstOrNull { it.key == "stt.provider" }?.let { field ->
                ConfigFieldRow(field, current(field.key), null) { setEdit(field.key, it) }
            }
            if (sttProvider.isNotBlank()) {
                fields.filter { it.key.startsWith("stt.$sttProvider.") }.forEach { field ->
                    ConfigFieldRow(field, current(field.key), null) { setEdit(field.key, it) }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = {
                        val pending = edits
                        saving = true
                        scope.launch {
                            // GET-merged tree -> PUT whole document (upstream
                            // save_config overwrites; a partial PUT would drop keys).
                            val merged = applyConfigEdits(tree, pending)
                            val result = client.updateConfig(merged, profile = null)
                            saving = false
                            result.fold(
                                onSuccess = {
                                    values = merged
                                    edits = emptyMap()
                                    onMessage(voiceConfigSavedMsg)
                                },
                                onFailure = { onMessage(it.message ?: saveFailedMsg) },
                            )
                        }
                    },
                    enabled = edits.isNotEmpty() && !saving,
                ) { Text(if (saving) stringResource(R.string.voice_settings_saving_ellipsis) else stringResource(R.string.voice_settings_save)) }

                if (edits.isNotEmpty() && !saving) {
                    TextButton(onClick = { edits = emptyMap() }) { Text(stringResource(R.string.voice_settings_discard)) }
                }
            }
        } else if (loading) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (signInRequired) {
            Text(
                text = error ?: signInRequiredMsg,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            onOpenManage?.let { open ->
                TextButton(onClick = open) { Text(stringResource(R.string.voice_settings_open_manage_signin)) }
            }
        } else {
            Text(
                text = error ?: couldNotLoadConfigMsg,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { reloadNonce++ }) { Text(stringResource(R.string.voice_settings_retry)) }
        }
    }
}

/**
 * One editable row for a [ConfigSchemaField]. [overrideChoices] forces a
 * dropdown regardless of the field's declared type — used for the ElevenLabs
 * voice picker, where a plain `string` schema field is upgraded to a list when
 * voices are available. [onEdit] receives the new value as a [JsonElement] for
 * direct merge into the config tree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigFieldRow(
    field: ConfigSchemaField,
    current: JsonElement?,
    overrideChoices: List<VoiceChoice>?,
    onEdit: (JsonElement) -> Unit,
) {
    val label = field.description?.takeIf { it.isNotBlank() } ?: field.key
    val str = (current as? JsonPrimitive)?.contentOrNull.orEmpty()
    when {
        overrideChoices != null -> VoiceChoiceDropdown(
            label = label,
            value = str,
            choices = overrideChoices,
            onValueChange = { onEdit(JsonPrimitive(it)) },
            modifier = Modifier.fillMaxWidth(),
        )

        field.type == ConfigFieldType.Select -> VoiceChoiceDropdown(
            label = label,
            value = str,
            choices = field.options.map { VoiceChoice(value = it) },
            onValueChange = { onEdit(JsonPrimitive(it)) },
            modifier = Modifier.fillMaxWidth(),
        )

        field.type == ConfigFieldType.Boolean -> {
            val checked = (current as? JsonPrimitive)?.booleanOrNull ?: false
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = checked, onCheckedChange = { onEdit(JsonPrimitive(it)) })
            }
        }

        field.type == ConfigFieldType.Number -> OutlinedTextField(
            value = str,
            onValueChange = { input ->
                val parsed = input.toLongOrNull()?.let { JsonPrimitive(it) }
                    ?: input.toDoubleOrNull()?.let { JsonPrimitive(it) }
                    ?: JsonPrimitive(input)
                onEdit(parsed)
            },
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        else -> OutlinedTextField(
            value = str,
            onValueChange = { onEdit(JsonPrimitive(it)) },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ===========================================================================
// Pure helpers (unchanged from the pre-refactor screen; providerOptionStatus
// moved to VoiceSettingsViewModel with the fetch).
// ===========================================================================

@Composable
private fun realtimeProviderList(config: RealtimeVoiceConfig): String {
    val ids = config.providers.map { provider -> provider.id }.filter { it.isNotBlank() }
    return ids.joinToString(", ").ifBlank { stringResource(R.string.voice_settings_none) }
}

@Composable
private fun voiceOutputProviderList(config: VoiceOutputConfig): String {
    val ids = config.providers.map { provider -> provider.id }.filter { it.isNotBlank() }
    return ids.joinToString(", ").ifBlank { stringResource(R.string.voice_settings_none) }
}

@Composable
private fun realtimeAuthLabel(config: RealtimeVoiceConfig): String {
    val notReported = stringResource(R.string.voice_settings_auth_not_reported)
    val auth = config.auth ?: return notReported
    return when {
        auth.xai_oauth -> stringResource(R.string.voice_settings_auth_hermes_xai_oauth)
        auth.xai_env -> stringResource(R.string.voice_settings_auth_xai_env)
        auth.openai_env -> stringResource(R.string.voice_settings_auth_openai_env)
        else -> stringResource(R.string.voice_settings_auth_server_managed)
    }
}

@Composable
private fun voiceOutputAuthLabel(config: VoiceOutputConfig): String {
    val notReported = stringResource(R.string.voice_settings_auth_not_reported)
    val auth = config.auth ?: return notReported
    return when {
        auth.xai_oauth -> stringResource(R.string.voice_settings_auth_xai_oauth)
        auth.xai_env -> stringResource(R.string.voice_settings_auth_xai_env)
        auth.openai_env -> stringResource(R.string.voice_settings_auth_openai_env)
        else -> stringResource(R.string.voice_settings_auth_server_managed)
    }
}

@Composable
private fun voiceProfileLabel(profile: String?, selectedProfile: Profile?): String {
    val selectedName = selectedProfile?.name?.takeIf { it.isNotBlank() }
    val selectedLabel = selectedProfile?.description?.takeIf { it.isNotBlank() }
        ?: selectedName
    return when {
        !profile.isNullOrBlank() && selectedLabel != null && profile == selectedName ->
            stringResource(R.string.voice_settings_profile_label_with_profile, selectedLabel, profile)
        !profile.isNullOrBlank() -> profile
        selectedLabel != null -> stringResource(R.string.voice_settings_profile_label_pending, selectedLabel)
        else -> stringResource(R.string.voice_settings_server_default)
    }
}

@Composable
private fun voiceScopeLabel(scope: String?, fallbackToGlobal: Boolean): String {
    val base = when (scope) {
        "profile" -> stringResource(R.string.voice_settings_scope_profile_config)
        "relay" -> stringResource(R.string.voice_settings_scope_relay_config)
        "global" -> stringResource(R.string.voice_settings_scope_global_config)
        else -> scope?.takeIf { it.isNotBlank() } ?: stringResource(R.string.voice_settings_server_default)
    }
    return if (fallbackToGlobal) stringResource(R.string.voice_settings_scope_with_fallback, base) else base
}

private data class RouteOption(
    val route: VoiceAudioRoute,
    val label: String,
    val detail: String,
    val status: String,
    val statusOk: Boolean,
    val badge: String? = null,
)

/**
 * Pure coercion of a persisted [route] to a reachable one for the given
 * [engine] / [relayVoiceReady] combination. Keeps the engine/route radios from
 * leaving a stale invalid selection persisted (e.g. a Relay route after Relay
 * was unpaired). Unit-testable; the composable just applies the result.
 *
 * - Engine == RealtimeAgent requires a paired Relay; this helper only governs
 *   the audio route, so when relay isn't ready the route is forced to [Auto]
 *   (the caller separately forces the engine back to HermesVoiceOutput).
 * - [VoiceAudioRoute.Relay] is only valid when [relayVoiceReady].
 * - [VoiceAudioRoute.Auto] is always valid (it self-resolves at runtime).
 * - [VoiceAudioRoute.Standard] is left as-is — its reachability is a live
 *   dashboard probe the UI dims via `statusOk`, not something we can know here.
 */
internal fun coerceAudioRoute(
    engine: VoiceEngineMode,
    route: VoiceAudioRoute,
    relayVoiceReady: Boolean,
): VoiceAudioRoute = when {
    route == VoiceAudioRoute.Relay && !relayVoiceReady -> VoiceAudioRoute.Auto
    else -> route
}

private data class VoiceChoice(
    val value: String,
    val label: String = value,
    val detail: String? = null,
    val group: String? = null,
    val custom: Boolean = false,
    val recommended: Boolean = false,
    val enabled: Boolean = true,
)

private data class VoiceSelection(
    val model: String,
    val voice: String,
    val sampleRate: String,
    val language: String,
)

@Composable
private fun providerChoices(
    providers: List<RealtimeProviderInfo>,
    current: String,
): List<VoiceChoice> {
    return providers
        .map { provider ->
            VoiceChoice(
                value = provider.id,
                label = provider.name?.takeIf { it.isNotBlank() } ?: provider.id,
                detail = provider.id,
            )
        }
        .withCurrent(current)
}

@Composable
private fun valueChoices(
    values: List<String>,
    current: String,
    labels: Map<String, String> = emptyMap(),
): List<VoiceChoice> =
    values.distinct().map { value ->
        val label = labels[value]?.takeIf { it.isNotBlank() } ?: value
        VoiceChoice(
            value = value,
            label = label,
            detail = value.takeIf { label != value },
        )
    }.withCurrent(current)

@Composable
private fun voiceChoices(
    provider: RealtimeProviderInfo?,
    current: String,
    model: String,
): List<VoiceChoice> {
    val voicesFallback = stringResource(R.string.voice_settings_voices_fallback)
    val recommendedLabel = stringResource(R.string.voice_settings_recommended)
    val customLabel = stringResource(R.string.voice_settings_custom)
    if (provider == null) return emptyList<VoiceChoice>().withCurrent(current)
    val orderedValues = mutableListOf<String>()
    val groupLabels = mutableMapOf<String, String>()
    val groupCustom = mutableMapOf<String, Boolean>()
    provider.voice_groups.forEach { group ->
        val label = group.label?.takeIf { it.isNotBlank() }
            ?: group.id?.takeIf { it.isNotBlank() }
            ?: voicesFallback
        group.values.forEach { value ->
            if (value.isNotBlank()) {
                orderedValues.add(value)
                groupLabels[value] = label
                groupCustom[value] = group.custom
            }
        }
    }
    provider.voices.forEach { value ->
        if (value.isNotBlank()) orderedValues.add(value)
    }

    val compatible = voiceCompatibilityFor(provider, model)
    val compatibleSet = compatible?.toSet()
    val recommended = provider.recommended_voices.toSet()
    return orderedValues
        .distinct()
        .filter { value -> compatibleSet == null || value in compatibleSet || value == current }
        .map { value ->
            val metadata = provider.voice_metadata[value]
            val label = metadata?.label?.takeIf { it.isNotBlank() }
                ?: provider.voice_labels[value]?.takeIf { it.isNotBlank() }
                ?: value
            val isCustom = metadata?.custom == true || groupCustom[value] == true
            val isRecommended = metadata?.recommended == true || value in recommended
            val details = buildList {
                if (label != value) add(value)
                if (isRecommended) add(recommendedLabel)
                if (isCustom) add(customLabel)
                metadata?.source?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            VoiceChoice(
                value = value,
                label = label,
                detail = details.joinToString(" · ").takeIf { it.isNotBlank() },
                group = groupLabels[value],
                custom = isCustom,
                recommended = isRecommended,
            )
        }
        .withCurrent(current)
}

@Composable
private fun intChoices(values: List<Int>, current: String): List<VoiceChoice> =
    values.distinct().map { VoiceChoice(it.toString(), stringResource(R.string.voice_settings_hz_value, it)) }.withCurrent(current)

@Composable
private fun List<VoiceChoice>.withCurrent(current: String): List<VoiceChoice> {
    val trimmed = current.trim()
    if (trimmed.isBlank() || any { it.value == trimmed }) return this
    return listOf(
        VoiceChoice(
            value = trimmed,
            label = stringResource(R.string.voice_settings_current_value, trimmed),
            detail = stringResource(R.string.voice_settings_manual_entry),
            group = stringResource(R.string.voice_settings_manual_group),
        )
    ) + this
}

private fun providerFor(
    providers: List<RealtimeProviderInfo>,
    providerId: String,
): RealtimeProviderInfo? = providers.firstOrNull { it.id == providerId }

private fun mergedProviders(
    base: List<RealtimeProviderInfo>,
    overrides: Map<String, RealtimeProviderInfo>,
): List<RealtimeProviderInfo> {
    if (overrides.isEmpty()) return base
    val seen = mutableSetOf<String>()
    val merged = base.map { provider ->
        seen.add(provider.id)
        overrides[provider.id] ?: provider
    }.toMutableList()
    overrides.values
        .filter { provider -> provider.id !in seen }
        .forEach { provider -> merged.add(provider) }
    return merged
}

private fun selectionWithProviderDefaults(
    provider: RealtimeProviderInfo,
    model: String,
    voice: String,
    sampleRate: String,
    language: String?,
): VoiceSelection {
    val nextModel = if (provider.models.isNotEmpty() && model !in provider.models) {
        provider.models.first()
    } else {
        model
    }
    val nextVoice = if (provider.voices.isNotEmpty() && voice !in provider.voices) {
        provider.voices.first()
    } else {
        voice
    }
    val compatibleVoice = voiceForModel(provider, nextModel, nextVoice)
    val currentSampleRate = sampleRate.toIntOrNull()
    val nextSampleRate = if (
        provider.sample_rates.isNotEmpty() &&
        currentSampleRate?.let { it in provider.sample_rates } != true
    ) {
        provider.sample_rates.first().toString()
    } else {
        sampleRate
    }
    val nextLanguage = if (
        language != null &&
        provider.languages.isNotEmpty() &&
        language !in provider.languages
    ) {
        provider.languages.first()
    } else {
        language.orEmpty()
    }
    return VoiceSelection(
        model = nextModel,
        voice = compatibleVoice,
        sampleRate = nextSampleRate,
        language = nextLanguage,
    )
}

private fun voiceForModel(
    provider: RealtimeProviderInfo,
    model: String,
    current: String,
): String {
    val compatible = voiceCompatibilityFor(provider, model)
    if (compatible.isNullOrEmpty()) {
        return if (provider.voices.isNotEmpty() && current !in provider.voices) {
            provider.voices.first()
        } else {
            current
        }
    }
    if (current in compatible) return current
    return compatible.firstOrNull() ?: current
}

private fun voiceCompatibilityFor(
    provider: RealtimeProviderInfo,
    model: String,
): List<String>? {
    val trimmed = model.trim()
    if (trimmed.isBlank()) return null
    return provider.model_voice_compatibility[trimmed]
}

private fun compatibilityNotice(
    provider: RealtimeProviderInfo?,
    model: String,
    voice: String,
    notAdvertisedMessage: String,
): String? {
    provider ?: return null
    val compatible = voiceCompatibilityFor(provider, model) ?: return null
    if (voice.isBlank() || voice in compatible) return null
    return notAdvertisedMessage
}

private fun validationIssue(
    validation: VoiceProviderValidationResponse?,
    fallbackMessage: String,
): String? {
    if (validation == null || validation.valid) return null
    val message = validation.checks.firstOrNull { it.status == "error" }
        ?.message
        ?.takeIf { it.isNotBlank() }
    return message ?: fallbackMessage
}

private fun validationWarning(
    validation: VoiceProviderValidationResponse?,
    savedWithWarningFormat: String,
): String? {
    validation ?: return null
    val message = validation.checks.firstOrNull { it.status == "warning" }
        ?.message
        ?.takeIf { it.isNotBlank() }
    return message?.let { savedWithWarningFormat.format(it) }
}

private fun providerOptionsStatusText(
    loading: Boolean,
    status: String?,
    refreshingMessage: String,
): String? = when {
    loading -> refreshingMessage
    !status.isNullOrBlank() -> status
    else -> null
}

@Composable
private fun commonLanguages(current: String, provider: RealtimeProviderInfo?): List<VoiceChoice> {
    val base = provider?.languages.orEmpty().ifEmpty {
        listOf("en", "es", "fr", "de", "ja", "zh")
    }
    return valueChoices(base, current, provider?.language_labels.orEmpty())
}

@Composable
private fun voiceOutputSummary(
    profile: Profile?,
    currentEngine: VoiceEngineMode,
    output: VoiceOutputConfig?,
    realtime: RealtimeVoiceConfig?,
    realtimeModel: String = "",
    realtimeVoice: String = "",
): Pair<String, String> {
    val profileLabel = profile?.description?.takeIf { it.isNotBlank() }
        ?: profile?.name?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.voice_settings_server_default)
    val outputLabel = output?.let { config ->
        val provider = config.default_provider?.takeIf { it.isNotBlank() } ?: stringResource(R.string.voice_settings_summary_provider)
        val voice = config.default_voice?.takeIf { it.isNotBlank() } ?: stringResource(R.string.voice_settings_summary_voice)
        val model = config.default_model?.takeIf { it.isNotBlank() }
        if (model == null) "$provider / $voice" else "$provider / $model / $voice"
    } ?: stringResource(R.string.voice_settings_loading_output)
    val realtimeLabel = realtime?.let { config ->
        val provider = config.default_provider?.takeIf { it.isNotBlank() } ?: "realtime ..."
        val model = realtimeModel.takeIf { it.isNotBlank() }
            ?: config.default_model?.takeIf { it.isNotBlank() }
        val voice = realtimeVoice.takeIf { it.isNotBlank() }
            ?: config.default_voice?.takeIf { it.isNotBlank() }
            ?: "voice ..."
        if (model == null) "$provider / $voice" else "$provider / $model / $voice"
    } ?: "realtime loading..."
    return profileLabel to when (currentEngine) {
        VoiceEngineMode.HermesVoiceOutput -> stringResource(R.string.voice_settings_summary_hermes_engine, outputLabel)
        VoiceEngineMode.RealtimeAgent -> stringResource(R.string.voice_settings_summary_realtime_engine, realtimeLabel)
    }
}

@Composable
private fun VoiceProfileSummaryCard(
    selectedProfile: Profile?,
    currentEngine: VoiceEngineMode,
    output: VoiceOutputConfig?,
    realtime: RealtimeVoiceConfig?,
    realtimeModel: String,
    realtimeVoice: String,
) {
    val (title, subtitle) = voiceOutputSummary(
        profile = selectedProfile,
        currentEngine = currentEngine,
        output = output,
        realtime = realtime,
        realtimeModel = realtimeModel,
        realtimeVoice = realtimeVoice,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceChoiceDropdown(
    label: String,
    value: String,
    choices: List<VoiceChoice>,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember(label) { mutableStateOf("") }
    val selected = choices.firstOrNull { it.value == value }
    val displayValue = selected?.label ?: value
    val searchable = choices.size > 12
    val filteredChoices = remember(choices, query) {
        val term = query.trim()
        if (term.isBlank()) {
            choices
        } else {
            choices.filter { choice ->
                choice.value.contains(term, ignoreCase = true) ||
                    choice.label.contains(term, ignoreCase = true) ||
                    choice.detail.orEmpty().contains(term, ignoreCase = true) ||
                    choice.group.orEmpty().contains(term, ignoreCase = true)
            }
        }
    }
    LaunchedEffect(expanded) {
        if (!expanded) query = ""
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled && choices.isNotEmpty()) expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp),
        ) {
            if (searchable) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    singleLine = true,
                    label = { Text(stringResource(R.string.voice_settings_search)) },
                )
                HorizontalDivider()
            }
            var lastGroup: String? = null
            filteredChoices.forEach { choice ->
                val group = choice.group?.takeIf { it.isNotBlank() }
                if (group != null && group != lastGroup) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = group,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                    lastGroup = group
                }
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                choice.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            choice.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    onClick = {
                        onValueChange(choice.value)
                        expanded = false
                    },
                    enabled = choice.enabled,
                )
            }
            if (filteredChoices.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.voice_settings_no_matches),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
            }
        }
    }
}

@Composable
private fun AdvancedManualToggle(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    TextButton(onClick = { onExpandedChange(!expanded) }) {
        Text(if (expanded) stringResource(R.string.voice_settings_hide_advanced_manual) else stringResource(R.string.voice_settings_advanced_manual))
    }
}

@Composable
private fun SectionCard(
    title: String,
    badge: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                badge?.let { ExperimentalBadge(it) }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ExperimentalBadge(text: String) {
    Row(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun ProviderRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.62f),
        )
    }
}
