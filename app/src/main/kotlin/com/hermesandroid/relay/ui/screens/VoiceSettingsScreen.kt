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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.BargeInPreferences
import com.hermesandroid.relay.data.BargeInSensitivity
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.VoiceAudioRoute
import com.hermesandroid.relay.data.VoiceEngineMode
import com.hermesandroid.relay.data.VoicePreferencesRepository
import com.hermesandroid.relay.data.VoiceSettings
import com.hermesandroid.relay.network.relay.RealtimeProviderInfo
import com.hermesandroid.relay.network.relay.RealtimeVoiceConfig
import com.hermesandroid.relay.network.relay.RelayVoiceClient
import com.hermesandroid.relay.network.relay.VoiceConfig
import com.hermesandroid.relay.network.relay.VoiceOutputConfig
import com.hermesandroid.relay.network.relay.VoiceProviderValidationResponse
import com.hermesandroid.relay.ui.LocalSnackbarHost
import com.hermesandroid.relay.ui.showHumanError
import com.hermesandroid.relay.util.classifyError
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
 *   9. Test current engine       — voice-output / realtime sample playback.
 *  10. Coming soon               — not-yet-wired controls (Auto-TTS, STT lang).
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

    // Global snackbar host — voice/config errors routed through the classifier
    // are shown here as well as the inline "unavailable" labels.
    val snackbarHost = LocalSnackbarHost.current

    // WP-V2/V3: point the screen's prefs repo at the active (connection,
    // profile) scope so the per-profile engine/route/enhanced toggles read and
    // write the SAME namespaced keys VoiceViewModel seeds from. RelayApp never
    // wires a connection id into the voice-prefs scope today (the VM mirrors
    // ProfileSelectionStore profile-only keying), so we pass a null connection
    // id and the normalized profile name — matching VoiceViewModel exactly.
    LaunchedEffect(selectedProfile?.name) {
        prefsRepo.setActiveScope(
            connectionId = null,
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
                title = { Text("Voice") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
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
            )

            // --- Voice scope (single home; WP-V3 dedupe + WP-V4 honest label) ---
            VoiceScopeBanner(
                relayVoiceReady = relayVoiceReady,
                currentEngine = currentEngine,
                configState = configState,
                selectedProfile = selectedProfile,
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

            // --- Test Current Engine ---
            TestCurrentEngineCard(
                currentEngine = currentEngine,
                relayVoiceReady = relayVoiceReady,
                configState = configState,
                selectedProfile = selectedProfile,
                voiceViewModel = voiceViewModel,
            )

            // --- Coming soon (not-yet-wired controls) ---
            ComingSoonCard(
                voiceSettings = voiceSettings,
                prefsRepo = prefsRepo,
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
                        text = "Voice scope",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    ExperimentalBadge("Global voice")
                }
                Text(
                    text = "This connection speaks through your Hermes server's " +
                        "configured TTS and STT (config.yaml on the server, or the " +
                        "dashboard's Audio settings) — it's host-wide, not per " +
                        "profile. Pair Relay to pick providers, models, and voices " +
                        "from the phone and carry them per profile.",
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
                    text = "Voice scope",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (config == null) {
                    Text(
                        text = "Resolving the active profile's voice scope…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ProviderRow(
                        label = "Profile",
                        value = voiceProfileLabel(profileRaw, selectedProfile),
                    )
                    ProviderRow(
                        label = "Scope",
                        value = voiceScopeLabel(scopeRaw, fallback),
                    )
                    Text(
                        text = "Engine, route, and voice picks below are saved for " +
                            "this profile.",
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
    SectionCard(title = "Voice for this profile") {
        Text(
            text = "Voice engine",
            style = MaterialTheme.typography.labelLarge,
        )
        listOf(
            VoiceEngineMode.HermesVoiceOutput to Triple(
                "Hermes Chat + Voice Output",
                "Hermes handles chat, tools, and memory; speech runs over the standard " +
                    "Hermes dashboard or Relay — whichever the STT/TTS route below picks.",
                false,
            ),
            VoiceEngineMode.RealtimeAgent to Triple(
                "Realtime Agent",
                "Provider-native realtime speech with Hermes-brokered tools. Requires a paired Relay.",
                true,
            ),
        ).forEach { (engine, copy) ->
            val (label, detail, experimental) = copy
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = currentEngine == engine,
                        onClick = {
                            scope.launch { prefsRepo.setEngineMode(engine) }
                        },
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = currentEngine == engine,
                    onClick = null,
                )
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        if (experimental) ExperimentalBadge("Experimental")
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
                    text = "No Relay is configured for this connection, so the Realtime " +
                        "Agent can't start. Pair Relay in Settings → Connections, or " +
                        "switch back to Hermes Chat + Voice Output.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (currentEngine == VoiceEngineMode.HermesVoiceOutput) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "STT/TTS route",
                style = MaterialTheme.typography.labelLarge,
            )
            val standardStatus = when (standardVoiceAvailability) {
                StandardVoiceAvailability.Ready -> "Ready"
                StandardVoiceAvailability.SignInRequired -> "Dashboard sign-in required"
                StandardVoiceAvailability.Unreachable -> "Dashboard unreachable"
                StandardVoiceAvailability.Unsupported -> "Not available on this Hermes build"
                StandardVoiceAvailability.Unknown -> "Checking..."
            }
            val standardOk = standardVoiceAvailability == StandardVoiceAvailability.Ready
            val relayStatus = if (relayVoiceReady) "Ready" else "Relay not configured"
            val autoStatus = when {
                relayVoiceReady -> "Ready — using Relay"
                standardOk -> "Ready — using standard Hermes"
                else -> "No route available yet"
            }
            listOf(
                RouteOption(
                    route = VoiceAudioRoute.Auto,
                    label = "Auto",
                    detail = "Relay when paired; otherwise the standard Hermes dashboard. Recommended.",
                    status = autoStatus,
                    statusOk = relayVoiceReady || standardOk,
                ),
                RouteOption(
                    route = VoiceAudioRoute.Standard,
                    label = "Vanilla Hermes",
                    detail = "The dashboard audio path Hermes Desktop uses — works on a " +
                        "vanilla Hermes install, no Relay plugin required.",
                    status = standardStatus,
                    statusOk = standardOk,
                ),
                RouteOption(
                    route = VoiceAudioRoute.Relay,
                    label = "Relay",
                    detail = "Relay plugin voice — profile-aware providers and streaming voice output.",
                    status = relayStatus,
                    statusOk = relayVoiceReady,
                    badge = "Optional",
                ),
            ).forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = currentAudioRoute == option.route,
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
                    )
                    Spacer(Modifier.size(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(option.label, style = MaterialTheme.typography.bodyMedium)
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
                            "You're connected over the $standardVoiceSignInRouteHint " +
                                "route, and dashboard sign-ins are per-host — a sign-in " +
                                "from your home network doesn't carry over. Sign in once " +
                                "in Manage while on this route to unlock voice here too."
                        } else {
                            "Your Hermes dashboard requires sign-in before standard " +
                                "voice can transcribe or speak. Signing in once in Manage " +
                                "unlocks it for this connection."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onOpenManage != null) {
                        TextButton(onClick = onOpenManage) {
                            Text("Sign in via Manage")
                        }
                    }
                }
                StandardVoiceAvailability.Unsupported -> {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "This Hermes server build doesn't expose the dashboard " +
                            "audio routes yet. Update hermes-agent on the server, or " +
                            "pair Relay to use Relay voice.",
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
    SectionCard(title = "Text-to-Speech") {
        if (showStreaming) {
            Text(
                text = "Streaming output (/voice/output)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "The provider, model, and voice the agent speaks with on the Relay path.",
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
            text = "Basic synthesize fallback (/voice/synthesize)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Always available as the stable speech safety net when provider-native " +
                "voice is unavailable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        ProviderRow(
            label = "Provider",
            value = configState.voiceConfig?.tts?.provider
                ?: (configState.voiceConfigError?.let { "unavailable" } ?: "loading..."),
        )
        configState.voiceConfig?.tts?.let { tts ->
            ProviderRow(label = "Enabled", value = if (tts.isEnabled) "yes" else "no")
        }
        configState.voiceConfig?.tts?.model?.let { model ->
            ProviderRow(label = "Model", value = model)
        }
        configState.voiceConfig?.tts?.displayVoice?.let { voice ->
            ProviderRow(label = "Voice", value = voice)
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
                "gemini" -> "Gemini"
                "xai" -> "xAI"
                else -> enhanced.provider?.replaceFirstChar { it.uppercase() } ?: "Provider"
            }
            var enhancedOpen by remember { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { enhancedOpen = !enhancedOpen }) {
                Text(
                    if (enhancedOpen) {
                        "Hide enhanced voice ($providerLabel)"
                    } else {
                        "Advanced: enhanced voice ($providerLabel)"
                    }
                )
            }
            if (enhancedOpen) {
                Text(
                    text = "Pick a voice and tone for the $providerLabel TTS provider. " +
                        "Leave a field on \"Server default\" to use the relay's saved config.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                // Curated dropdown when the relay enumerates voices (Gemini);
                // free-text entry otherwise (xAI's catalog isn't enumerated).
                if (enhanced.voices.isNotEmpty()) {
                    val voiceChoices = buildList {
                        add(VoiceChoice(value = "", label = "Server default"))
                        enhanced.voices.forEach { add(VoiceChoice(value = it, label = it)) }
                    }
                    VoiceChoiceDropdown(
                        label = "Voice",
                        value = voiceSettings.enhancedVoice,
                        choices = voiceChoices,
                        onValueChange = { scope.launch { prefsRepo.setEnhancedVoice(it) } },
                    )
                } else {
                    OutlinedTextField(
                        value = voiceSettings.enhancedVoice,
                        onValueChange = { scope.launch { prefsRepo.setEnhancedVoice(it) } },
                        label = { Text("Voice (blank = server default)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))

                if (enhanced.models.isNotEmpty()) {
                    val modelChoices = buildList {
                        add(VoiceChoice(value = "", label = "Server default"))
                        enhanced.models.forEach { model ->
                            add(
                                VoiceChoice(
                                    value = model,
                                    label = model,
                                    detail = if (model in enhanced.audioTagModels) {
                                        "supports tone tags"
                                    } else {
                                        null
                                    },
                                ),
                            )
                        }
                    }
                    VoiceChoiceDropdown(
                        label = "Model",
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
                                "Let the model add tone cues (e.g. [whispers], " +
                                    "[excitedly]) to the spoken script."
                            } else {
                                "Requires a Gemini 3.1 TTS model — pick one above to enable."
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
                        label = { Text("Voice direction (optional)") },
                        placeholder = { Text("e.g. Warm, calm narrator; unhurried pace.") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }

                if (enhanced.supportsLanguage) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = voiceSettings.enhancedLanguage,
                        onValueChange = { scope.launch { prefsRepo.setEnhancedLanguage(it) } },
                        label = { Text("Language (optional, e.g. en)") },
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

    ProviderRow(
        label = "Status",
        value = when {
            config?.enabled == true -> "active"
            configState.voiceOutputConfigError != null -> "unavailable"
            config != null -> "disabled"
            else -> "loading..."
        },
    )
    // Which path actually renders speech, for troubleshooting: streaming
    // /voice/output when enabled, else the basic /voice/synthesize fallback.
    ProviderRow(
        label = "Render path",
        value = when {
            config?.enabled == true -> "streaming (/voice/output)"
            config != null -> "basic synthesize (/voice/synthesize)"
            else -> "loading..."
        },
    )
    ProviderRow(
        label = "Provider",
        value = config?.default_provider
            ?: (configState.voiceOutputConfigError?.let { "unavailable" } ?: "loading..."),
    )
    config?.default_model?.let { model ->
        ProviderRow(label = "Model", value = model)
    }
    config?.default_voice?.let { voice ->
        ProviderRow(label = "Voice", value = voice)
    }
    config?.let { c ->
        ProviderRow(label = "Sample rate", value = "${c.sample_rate} Hz")
        ProviderRow(label = "Language", value = c.language)
        ProviderRow(label = "Fallback", value = if (c.fallback_enabled) "legacy TTS on error" else "off")
        ProviderRow(label = "Advertised", value = voiceOutputProviderList(c))
        ProviderRow(label = "Auth", value = voiceOutputAuthLabel(c))
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Enabled", style = MaterialTheme.typography.bodyLarge)
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
        label = "Provider",
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
    )?.let { status ->
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    VoiceChoiceDropdown(
        label = "Model",
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
        label = "Voice",
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
    )?.let { notice ->
        Text(
            text = notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    VoiceChoiceDropdown(
        label = "Language",
        value = voiceOutputLanguage,
        choices = commonLanguages(voiceOutputLanguage, selectedOutputProvider),
        onValueChange = { voiceOutputLanguage = it },
        enabled = voiceClient != null,
    )
    VoiceChoiceDropdown(
        label = "Sample rate",
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
            label = { Text("Provider ID") },
        )
        OutlinedTextField(
            value = voiceOutputModel,
            onValueChange = { voiceOutputModel = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Model ID") },
        )
        OutlinedTextField(
            value = voiceOutputVoice,
            onValueChange = { voiceOutputVoice = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Voice ID") },
        )
        OutlinedTextField(
            value = voiceOutputSampleRate,
            onValueChange = { voiceOutputSampleRate = it.filter(Char::isDigit) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Sample rate") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = voiceOutputLanguage,
            onValueChange = { voiceOutputLanguage = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Language") },
        )
    }

    Text(
        text = "Streaming latency: ${voiceOutputLatency.toInt()}",
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
            Text("Fallback", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Use legacy Hermes TTS if streaming output fails before audio starts",
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
                Text("Expressive speech tags", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Let xAI add tone cues (whisper, laugh, sigh, pitch " +
                        "shifts) to streamed speech.",
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

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = {
                val client = voiceClient ?: return@FilledTonalButton
                val sampleRate = voiceOutputSampleRate.toIntOrNull()
                if (sampleRate == null) {
                    settingsViewModel.setVoiceOutputError("Sample rate must be a number")
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
                    validationIssue(validationResult.getOrNull())?.let { issue ->
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
                    validationWarning(validationResult.getOrNull())?.let { warning ->
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
            Text(if (voiceOutputSaving) "Saving..." else "Save")
        }
        FilledTonalButton(
            onClick = {
                val client = voiceClient ?: return@FilledTonalButton
                val sampleRate = voiceOutputSampleRate.toIntOrNull()
                if (sampleRate == null) {
                    settingsViewModel.setVoiceOutputError("Sample rate must be a number")
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
                    validationIssue(validationResult.getOrNull())?.let { issue ->
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
                    validationWarning(validationResult.getOrNull())?.let { warning ->
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
            Text("Save & test")
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

    val config = configState.realtimeConfig
    LaunchedEffect(
        config?.enabled,
        config?.default_provider,
        config?.default_model,
        config?.default_voice,
        config?.sample_rate,
    ) {
        val c = config ?: return@LaunchedEffect
        realtimeEnabled = c.enabled
        realtimeProvider = c.default_provider.orEmpty()
        realtimeModel = c.default_model.orEmpty()
        realtimeVoice = c.default_voice.orEmpty()
        realtimeSampleRate = c.sample_rate.toString()
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

    SectionCard(title = "Realtime Agent", badge = "Experimental") {
        Text(
            text = "Hermes still owns tools and confirmations. Realtime mode may fall back to stable voice if the provider disconnects.",
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
                Text("Detailed trace", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Show compact Hermes status and result provenance in the timeline",
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
                Text("Persistent session", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Keep one provider conversation open across turns. Turn off to fall back to a fresh session per utterance.",
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
        ProviderRow(
            label = "Status",
            value = when {
                config?.enabled == true -> "available"
                configState.realtimeConfigError != null -> "unavailable"
                config != null -> "disabled"
                else -> "loading..."
            },
        )
        ProviderRow(
            label = "Provider",
            value = config?.default_provider
                ?: (configState.realtimeConfigError?.let { "unavailable" } ?: "loading..."),
        )
        config?.default_model?.let { model ->
            ProviderRow(label = "Model", value = model)
        }
        config?.default_voice?.let { voice ->
            ProviderRow(label = "Voice", value = voice)
        }
        config?.let { c ->
            ProviderRow(label = "Advertised", value = realtimeProviderList(c))
            ProviderRow(label = "Auth", value = realtimeAuthLabel(c))
        }

        config?.promotion?.let { promo ->
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("Background tasks", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Long Hermes tasks keep running in the background so the conversation stays responsive; the answer is spoken when it's ready.",
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
                    Text("Promote long tasks", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Detach a slow run after ${promo.promoteAfterMs} ms instead of waiting silently",
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
                        Text("Spoken handoff", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Say a short \"I'm on it\" when a task moves to the background",
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
                Text(
                    "When the answer is ready",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(4.dp))
                val deliveryOptions = listOf(
                    "speak_when_idle",
                    "notify_then_speak",
                    "visual_only",
                )
                val deliveryLabels = listOf("Speak", "Notify", "Show only")
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

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enabled", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Server-side realtime voice agent defaults for this profile",
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
            label = "Provider",
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
        )?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        VoiceChoiceDropdown(
            label = "Model",
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
            },
            enabled = voiceClient != null,
        )
        VoiceChoiceDropdown(
            label = "Voice",
            value = realtimeVoice,
            choices = voiceChoices(
                selectedRealtimeProvider,
                realtimeVoice,
                realtimeModel,
            ),
            onValueChange = { realtimeVoice = it },
            enabled = voiceClient != null,
        )
        compatibilityNotice(
            selectedRealtimeProvider,
            realtimeModel,
            realtimeVoice,
        )?.let { notice ->
            Text(
                text = notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        VoiceChoiceDropdown(
            label = "Sample rate",
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
                label = { Text("Provider ID") },
            )
            OutlinedTextField(
                value = realtimeModel,
                onValueChange = { realtimeModel = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Model ID") },
            )
            OutlinedTextField(
                value = realtimeVoice,
                onValueChange = { realtimeVoice = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Voice ID") },
            )
            OutlinedTextField(
                value = realtimeSampleRate,
                onValueChange = { realtimeSampleRate = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Sample rate") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }

        FilledTonalButton(
            onClick = {
                val client = voiceClient ?: return@FilledTonalButton
                val sampleRate = realtimeSampleRate.toIntOrNull()
                if (sampleRate == null) {
                    settingsViewModel.setRealtimeError("Sample rate must be a number")
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
                    validationIssue(validationResult.getOrNull())?.let { issue ->
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
                    validationWarning(validationResult.getOrNull())?.let { warning ->
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
            Text(if (realtimeSaving) "Saving..." else "Save realtime agent")
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
    SectionCard(title = "Global Voice Controls") {
        Text(
            text = "These settings apply to both voice engines, on every profile.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text(
            text = "Interaction mode",
            style = MaterialTheme.typography.labelLarge,
        )
        val currentMode = voiceSettings.interactionMode
        listOf(
            "tap" to "Tap to talk",
            "hold" to "Hold to talk",
            "continuous" to "Continuous",
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
            text = "Silence threshold: ${voiceSettings.silenceThresholdMs / 1000}s",
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = "Auto-stop listening after this much silence",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = voiceSettings.silenceThresholdMs.toFloat(),
            onValueChange = { newValue ->
                scope.launch { prefsRepo.setSilenceThresholdMs(newValue.toLong()) }
            },
            valueRange = 1000f..10000f,
            steps = 8,
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
    SectionCard(title = "Barge-in", badge = "Experimental") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Interrupt when I speak", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Experimental: may hear speaker output and falsely interrupt or record itself. Best with headphones.",
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
                    text = "Your device may have limited echo cancellation. Barge-in quality will vary.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (bargeInPrefs.enabled) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Sensitivity",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "Off disables detection without flipping the toggle above.",
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
            val sensitivityLabels = listOf("Off", "Low", "Default", "High")
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
                        "Resume after interruption",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Continue from the next sentence if you don't keep talking",
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
    SectionCard(title = "Speech-to-Text") {
        if (!relayVoiceReady) {
            Text(
                text = "Transcription runs on your Hermes server with its " +
                    "configured STT provider.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ProviderRow(
                label = "Provider",
                value = configState.voiceConfig?.stt?.provider
                    ?: (configState.voiceConfigError?.let { "unavailable" } ?: "loading..."),
            )
            configState.voiceConfig?.stt?.let { stt ->
                ProviderRow(label = "Enabled", value = if (stt.isEnabled) "yes" else "no")
            }
            configState.voiceConfig?.stt?.model?.let { model ->
                ProviderRow(label = "Model", value = model)
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
    var currentEngineTestRunning by remember { mutableStateOf(false) }
    var currentEngineTestResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentEngine) {
        currentEngineTestRunning = false
        currentEngineTestResult = null
    }

    SectionCard(title = "Test Current Engine") {
        ProviderRow(
            label = "Engine",
            value = when (currentEngine) {
                VoiceEngineMode.HermesVoiceOutput -> "Hermes Chat + Voice Output"
                VoiceEngineMode.RealtimeAgent -> "Realtime Agent"
            },
        )
        if (currentEngine == VoiceEngineMode.HermesVoiceOutput) {
            if (relayVoiceReady) {
                ProviderRow(
                    label = "Profile",
                    value = configState.voiceOutputConfig?.let { config ->
                        voiceProfileLabel(config.profile, selectedProfile)
                    } ?: voiceProfileLabel(null, selectedProfile),
                )
                ProviderRow(
                    label = "Voice",
                    value = listOfNotNull(
                        configState.voiceOutputConfig?.default_provider,
                        configState.voiceOutputConfig?.default_model,
                        configState.voiceOutputConfig?.default_voice,
                    ).joinToString(" / ").ifBlank { "loading..." },
                )
            } else {
                ProviderRow(label = "Route", value = "standard Hermes")
                ProviderRow(label = "Voice", value = "server-configured TTS")
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Play a short sample through the active voice route.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = {
                    currentEngineTestRunning = true
                    currentEngineTestResult = "Playing Voice Output sample..."
                    voiceViewModel.testVoice { result ->
                        currentEngineTestRunning = false
                        currentEngineTestResult = if (result.isSuccess) {
                            "Voice Output test successful."
                        } else {
                            "Voice Output test failed: ${result.exceptionOrNull()?.message ?: "playback error"}"
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
                Text("Play Voice Output Test")
            }
        } else {
            // Realtime: show the saved/active realtime config (the editor card
            // holds the unsaved draft; here we report what's actually live).
            val realtime = configState.realtimeConfig
            ProviderRow(label = "Provider", value = realtime?.default_provider?.takeIf { it.isNotBlank() } ?: "not set")
            ProviderRow(label = "Model", value = realtime?.default_model?.takeIf { it.isNotBlank() } ?: "not set")
            ProviderRow(label = "Voice", value = realtime?.default_voice?.takeIf { it.isNotBlank() } ?: "not set")
            ProviderRow(label = "Sample rate", value = "${realtime?.sample_rate ?: 0} Hz")
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Open a provider-native Realtime Agent session and play a short sample through the relay.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = {
                    currentEngineTestRunning = true
                    currentEngineTestResult = "Starting Realtime Agent sample..."
                    voiceViewModel.testRealtimeAgent { result ->
                        currentEngineTestRunning = false
                        currentEngineTestResult = if (result.isSuccess) {
                            "Realtime Agent test successful."
                        } else {
                            "Realtime Agent test failed: ${result.exceptionOrNull()?.message ?: "provider error"}"
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
                Text("Play Realtime Agent Test")
            }
        }
        currentEngineTestResult?.let { result ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = result,
                style = MaterialTheme.typography.bodySmall,
                color = if (
                    result.contains("validated", ignoreCase = true) ||
                    result.contains("Playing", ignoreCase = true) ||
                    result.contains("Starting", ignoreCase = true) ||
                    result.contains("successful", ignoreCase = true)
                ) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Coming soon — not-yet-wired controls, collapsed by default so they don't
// read as broken (WP-V3 item 3).
// ---------------------------------------------------------------------------

@Composable
private fun ComingSoonCard(
    voiceSettings: VoiceSettings,
    prefsRepo: VoicePreferencesRepository,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    SectionCard(title = "Coming soon", badge = "Not wired yet") {
        Text(
            text = "Controls that are saved but not yet wired end-to-end. Shown for " +
                "reference; they don't change voice behaviour today.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide" else "Show")
        }
        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-TTS", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Read aloud non-voice assistant messages (coming soon)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    // Disabled while Auto-TTS is unimplemented — a live toggle
                    // that does nothing reads as broken. Re-enable when wired.
                    enabled = false,
                    checked = voiceSettings.autoTts,
                    onCheckedChange = { enabled ->
                        scope.launch { prefsRepo.setAutoTts(enabled) }
                    },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "STT language",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "Stored only — the relay uses its own auto-detect for now",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val languages = listOf(
                "" to "Auto",
                "en" to "English",
                "es" to "Spanish",
                "fr" to "French",
                "de" to "German",
                "ja" to "Japanese",
                "zh" to "Chinese",
            )
            languages.forEach { (code, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = voiceSettings.language == code,
                            onClick = {
                                scope.launch { prefsRepo.setLanguage(code) }
                            },
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = voiceSettings.language == code,
                        onClick = null,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// ===========================================================================
// Pure helpers (unchanged from the pre-refactor screen; providerOptionStatus
// moved to VoiceSettingsViewModel with the fetch).
// ===========================================================================

private fun realtimeProviderList(config: RealtimeVoiceConfig): String {
    val ids = config.providers.map { provider -> provider.id }.filter { it.isNotBlank() }
    return ids.joinToString(", ").ifBlank { "none" }
}

private fun voiceOutputProviderList(config: VoiceOutputConfig): String {
    val ids = config.providers.map { provider -> provider.id }.filter { it.isNotBlank() }
    return ids.joinToString(", ").ifBlank { "none" }
}

private fun realtimeAuthLabel(config: RealtimeVoiceConfig): String {
    val auth = config.auth ?: return "not reported"
    return when {
        auth.xai_oauth -> "Hermes xAI OAuth"
        auth.xai_env -> "xAI env"
        auth.openai_env -> "OpenAI env"
        else -> "server managed"
    }
}

private fun voiceOutputAuthLabel(config: VoiceOutputConfig): String {
    val auth = config.auth ?: return "not reported"
    return when {
        auth.xai_oauth -> "xAI OAuth"
        auth.xai_env -> "xAI env"
        auth.openai_env -> "OpenAI env"
        else -> "server managed"
    }
}

private fun voiceProfileLabel(profile: String?, selectedProfile: Profile?): String {
    val selectedName = selectedProfile?.name?.takeIf { it.isNotBlank() }
    val selectedLabel = selectedProfile?.description?.takeIf { it.isNotBlank() }
        ?: selectedName
    return when {
        !profile.isNullOrBlank() && selectedLabel != null && profile == selectedName ->
            "$selectedLabel ($profile)"
        !profile.isNullOrBlank() -> profile
        selectedLabel != null -> "$selectedLabel (pending)"
        else -> "server default"
    }
}

private fun voiceScopeLabel(scope: String?, fallbackToGlobal: Boolean): String {
    val base = when (scope) {
        "profile" -> "profile config"
        "relay" -> "relay config"
        "global" -> "global Hermes config"
        else -> scope?.takeIf { it.isNotBlank() } ?: "server default"
    }
    return if (fallbackToGlobal) "$base fallback" else base
}

private data class RouteOption(
    val route: VoiceAudioRoute,
    val label: String,
    val detail: String,
    val status: String,
    val statusOk: Boolean,
    val badge: String? = null,
)

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

private fun voiceChoices(
    provider: RealtimeProviderInfo?,
    current: String,
    model: String,
): List<VoiceChoice> {
    if (provider == null) return emptyList<VoiceChoice>().withCurrent(current)
    val orderedValues = mutableListOf<String>()
    val groupLabels = mutableMapOf<String, String>()
    val groupCustom = mutableMapOf<String, Boolean>()
    provider.voice_groups.forEach { group ->
        val label = group.label?.takeIf { it.isNotBlank() }
            ?: group.id?.takeIf { it.isNotBlank() }
            ?: "Voices"
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
                if (isRecommended) add("recommended")
                if (isCustom) add("custom")
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

private fun intChoices(values: List<Int>, current: String): List<VoiceChoice> =
    values.distinct().map { VoiceChoice(it.toString(), "${it} Hz") }.withCurrent(current)

private fun List<VoiceChoice>.withCurrent(current: String): List<VoiceChoice> {
    val trimmed = current.trim()
    if (trimmed.isBlank() || any { it.value == trimmed }) return this
    return listOf(
        VoiceChoice(
            value = trimmed,
            label = "$trimmed (current)",
            detail = "manual entry",
            group = "Manual",
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
): String? {
    provider ?: return null
    val compatible = voiceCompatibilityFor(provider, model) ?: return null
    if (voice.isBlank() || voice in compatible) return null
    return "Voice is not advertised for the selected model"
}

private fun validationIssue(validation: VoiceProviderValidationResponse?): String? {
    if (validation == null || validation.valid) return null
    val message = validation.checks.firstOrNull { it.status == "error" }
        ?.message
        ?.takeIf { it.isNotBlank() }
    return message ?: "Provider selection is not valid"
}

private fun validationWarning(validation: VoiceProviderValidationResponse?): String? {
    validation ?: return null
    val message = validation.checks.firstOrNull { it.status == "warning" }
        ?.message
        ?.takeIf { it.isNotBlank() }
    return message?.let { "Saved with warning: $it" }
}

private fun providerOptionsStatusText(
    loading: Boolean,
    status: String?,
): String? = when {
    loading -> "Refreshing provider options..."
    !status.isNullOrBlank() -> status
    else -> null
}

private fun commonLanguages(current: String, provider: RealtimeProviderInfo?): List<VoiceChoice> {
    val base = provider?.languages.orEmpty().ifEmpty {
        listOf("en", "es", "fr", "de", "ja", "zh")
    }
    return valueChoices(base, current, provider?.language_labels.orEmpty())
}

private fun voiceOutputSummary(
    profile: Profile?,
    currentEngine: VoiceEngineMode,
    output: VoiceOutputConfig?,
    realtime: RealtimeVoiceConfig?,
): Pair<String, String> {
    val profileLabel = profile?.description?.takeIf { it.isNotBlank() }
        ?: profile?.name?.takeIf { it.isNotBlank() }
        ?: "Server default"
    val outputLabel = output?.let { config ->
        val provider = config.default_provider?.takeIf { it.isNotBlank() } ?: "provider ..."
        val voice = config.default_voice?.takeIf { it.isNotBlank() } ?: "voice ..."
        val model = config.default_model?.takeIf { it.isNotBlank() }
        if (model == null) "$provider / $voice" else "$provider / $model / $voice"
    } ?: "loading voice output..."
    val realtimeLabel = realtime?.let { config ->
        val provider = config.default_provider?.takeIf { it.isNotBlank() } ?: "realtime ..."
        val voice = config.default_voice?.takeIf { it.isNotBlank() } ?: "voice ..."
        "$provider / $voice"
    } ?: "realtime loading..."
    return profileLabel to when (currentEngine) {
        VoiceEngineMode.HermesVoiceOutput -> "Hermes Chat + Voice Output - $outputLabel"
        VoiceEngineMode.RealtimeAgent -> "Realtime Agent - $realtimeLabel"
    }
}

@Composable
private fun VoiceProfileSummaryCard(
    selectedProfile: Profile?,
    currentEngine: VoiceEngineMode,
    output: VoiceOutputConfig?,
    realtime: RealtimeVoiceConfig?,
) {
    val (title, subtitle) = voiceOutputSummary(
        profile = selectedProfile,
        currentEngine = currentEngine,
        output = output,
        realtime = realtime,
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
                    label = { Text("Search") },
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
                            Text(choice.label)
                            choice.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            text = "No matches",
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
        Text(if (expanded) "Hide advanced manual entry" else "Advanced manual entry")
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
            modifier = Modifier.weight(0.62f),
        )
    }
}
