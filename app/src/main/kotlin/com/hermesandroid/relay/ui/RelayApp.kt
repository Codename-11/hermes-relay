package com.hermesandroid.relay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermesandroid.relay.ui.components.MorphingSphere
import com.hermesandroid.relay.ui.components.ConnectionStatusBanner
import com.hermesandroid.relay.ui.components.ConnectionSwitcherSheet
import com.hermesandroid.relay.ui.components.PowerFeatureGateScreen
import com.hermesandroid.relay.ui.components.PowerFeatureGateStatus
import com.hermesandroid.relay.ui.components.RelayStatusStrip
import com.hermesandroid.relay.ui.components.UnattendedGlobalBanner
import com.hermesandroid.relay.ui.components.UpdateBanner
import com.hermesandroid.relay.update.UpdateCheckResult
import com.hermesandroid.relay.viewmodel.UpdateViewModel
import com.hermesandroid.relay.ui.components.WhatsNewDialog
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.BridgePreferencesRepository
import com.hermesandroid.relay.data.BridgeSafetyPreferencesRepository
import com.hermesandroid.relay.data.BuildFlavor
import com.hermesandroid.relay.data.VoiceAudioRoute
import com.hermesandroid.relay.data.VoicePreferencesRepository
import com.hermesandroid.relay.data.VoiceSettings
import com.hermesandroid.relay.data.displayLabel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import com.hermesandroid.relay.util.HumanError
import kotlinx.coroutines.delay
import com.hermesandroid.relay.ui.onboarding.OnboardingScreen
import com.hermesandroid.relay.ui.screens.AboutScreen
import com.hermesandroid.relay.ui.screens.AnalyticsScreen
import com.hermesandroid.relay.ui.screens.AppearanceSettingsScreen
import com.hermesandroid.relay.ui.screens.BridgeCoreScreen
import com.hermesandroid.relay.ui.screens.BridgeScreen
// === PHASE3-safety-rails: bridge safety route ===
import com.hermesandroid.relay.ui.screens.BridgeSafetySettingsScreen
// === END PHASE3-safety-rails ===
import com.hermesandroid.relay.ui.screens.ChatScreen
import com.hermesandroid.relay.ui.screens.ChatSettingsScreen
import com.hermesandroid.relay.ui.screens.DashboardManagementScreen
import com.hermesandroid.relay.ui.screens.DeveloperSettingsScreen
import com.hermesandroid.relay.ui.screens.MediaSettingsScreen
import com.hermesandroid.relay.ui.screens.PairedDevicesScreen
import com.hermesandroid.relay.ui.screens.ConnectionsSettingsScreen
import com.hermesandroid.relay.ui.screens.ProfileInspectorScreen
import com.hermesandroid.relay.ui.screens.RealtimeVoiceTestScreen
import com.hermesandroid.relay.ui.screens.SettingsScreen
import com.hermesandroid.relay.ui.screens.TerminalScreen
import com.hermesandroid.relay.ui.screens.NotificationCompanionSettingsScreen
import com.hermesandroid.relay.ui.screens.VoiceSettingsScreen
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.relayGridTexture
import com.hermesandroid.relay.network.RelayProfileInspectorClient
import com.hermesandroid.relay.network.AutoVoiceAudioClient
import com.hermesandroid.relay.network.ProfileApiUrlResolver
import com.hermesandroid.relay.network.RelayVoiceAudioClientAdapter
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.ProfileInspectorViewModel
import com.hermesandroid.relay.viewmodel.TerminalViewModel
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import com.hermesandroid.relay.audio.VoicePlayer
import com.hermesandroid.relay.audio.VoiceRecorder
import com.hermesandroid.relay.audio.VoiceSfxPlayer
import com.hermesandroid.relay.audio.RealtimePcmPlayer
import com.hermesandroid.relay.network.RelayVoiceClient
import com.hermesandroid.relay.network.StandardHermesVoiceClient
import com.hermesandroid.relay.auth.AuthState
import androidx.lifecycle.viewModelScope

// Global snackbar host so any screen can surface a HumanError without
// plumbing a host through every ViewModel. Provided by RelayApp below.
val LocalSnackbarHost = staticCompositionLocalOf<SnackbarHostState> {
    error("LocalSnackbarHost not provided — wrap your UI in RelayApp's CompositionLocalProvider")
}

// Short-lived snackbar by default; retryable errors get Long so users have
// time to tap the action before it auto-dismisses.
suspend fun SnackbarHostState.showHumanError(err: HumanError) {
    showSnackbar(
        message = err.body,
        actionLabel = err.actionLabel,
        duration = if (err.retryable) SnackbarDuration.Long else SnackbarDuration.Short,
    )
}

sealed class Screen(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Onboarding : Screen("onboarding", "Onboarding", Icons.Filled.Settings)
    // `openAgentSheet` — optional flag that tells ChatScreen to auto-open
    // its consolidated AgentInfoSheet on first composition. Added so the
    // Settings → Active Agent card can bounce the user straight to Chat
    // with the sheet pre-opened (Connection / Profile / Personality
    // editing lives inside the sheet). The arg is consumed once and then
    // cleared from the back-stack entry's arguments so returning to the
    // Chat tab later via bottom nav does NOT re-open the sheet.
    //
    // `route` stays the NavHost route template — matching the pattern used
    // by Screen.Pair — while [route] (the function) builds concrete URIs.
    // The bottom-nav selected-state hierarchy check keys off [route]
    // (the template), so the template must match what's registered in the
    // NavHost, and the NavigationBarItem click must navigate via [route]()
    // so no unresolved `{openAgentSheet}` leaks into the destination.
    data object Chat : Screen(
        "chat?openAgentSheet={openAgentSheet}",
        "Chat",
        Icons.AutoMirrored.Filled.Chat,
    ) {
        const val ARG_OPEN_AGENT_SHEET: String = "openAgentSheet"
        fun route(openAgentSheet: Boolean = false): String =
            if (openAgentSheet) "chat?openAgentSheet=true" else "chat"
    }
    data object Terminal : Screen("terminal", "Terminal", Icons.Filled.Code)
    data object Bridge : Screen("bridge", "Bridge", Icons.Filled.PhoneAndroid)
    data object Manage : Screen("manage", "Manage", Icons.Filled.Settings)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)

    // Non-bottom-nav destinations — reached by explicit navigation, not the
    // NavigationBar. "Relay sessions" is the user-facing label; the Kotlin
    // object name keeps `PairedDevices` to avoid churning navigation identifiers
    // and deep-link routes. Opened from Settings → Relay sessions and from the
    // active connection card's Security section.
    data object PairedDevices : Screen("paired_devices", "Relay sessions", Icons.Filled.Settings)
    // Full-screen pair wizard route. Replaces the old in-Settings Dialog
    // launch so the chooser + Confirm + Verify steps + the camera viewport
    // get a real fullscreen surface (the Dialog wasn't actually filling the
    // window — Settings cards were leaking through behind it).
    //
    // Multi-connection: accepts an optional `connectionId` query arg —
    // the ConnectionsSettings "Re-pair" button targets a specific
    // connection. The "Add connection" path pre-creates a placeholder
    // via `ConnectionViewModel.beginAddConnection()` and routes here
    // with that id, so the wizard's standard connect / applyPairingPayload lands in the
    // new connection's auth store instead of the outgoing one's.
    data object Pair : Screen(
        "pair?connectionId={connectionId}&autoStart={autoStart}",
        "Connect",
        Icons.Filled.Settings,
    ) {
        const val ARG_CONNECTION_ID: String = "connectionId"
        /**
         * Optional "skip the chooser, jump into this pair method" hint.
         * Currently only `"scan"` is recognised — the "Add connection" FAB
         * on the Connections screen passes it so the camera opens
         * immediately instead of forcing the user through the Method step.
         * Standard add/re-pair flows leave this null so the full chooser
         * remains available.
         */
        const val ARG_AUTO_START: String = "autoStart"
        fun route(connectionId: String? = null, autoStart: String? = null): String {
            val params = buildList {
                if (connectionId != null) add("connectionId=$connectionId")
                if (autoStart != null) add("autoStart=$autoStart")
            }
            return if (params.isEmpty()) "pair" else "pair?${params.joinToString("&")}"
        }
    }
    data object ConnectionsSettings : Screen("settings/connections", "Connections", Icons.Filled.Settings)
    data object VoiceSettings : Screen("voice_settings", "Voice", Icons.Filled.Settings)
    // === PHASE3-notif-listener-followup ===
    data object NotificationCompanionSettings :
        Screen("settings/notifications", "Notification companion", Icons.Filled.Settings)
    // === END PHASE3-notif-listener-followup ===
    // === PHASE3-safety-rails: bridge safety route ===
    data object BridgeSafetySettings :
        Screen("settings/bridge_safety", "Bridge safety", Icons.Filled.Settings)
    // === END PHASE3-safety-rails ===
    // Per-category settings sub-screens — split out of the mega SettingsScreen
    // following the VoiceSettingsScreen pattern (see DEVLOG 2026-04-11).
    // (The singular `ConnectionSettings` object was removed on 2026-04-21
    // when its underlying screen was collapsed into the active card of
    // the plural `ConnectionsSettings` subpage. See `ConnectionsSettings`
    // above for the surviving route.)
    data object ChatSettings : Screen("settings/chat", "Chat", Icons.Filled.Settings)
    data object MediaSettings : Screen("settings/media", "Media", Icons.Filled.Settings)
    data object AppearanceSettings : Screen("settings/appearance", "Appearance", Icons.Filled.Settings)
    data object Analytics : Screen("settings/analytics", "Analytics", Icons.Filled.Settings)
    data object DeveloperSettings : Screen("settings/developer", "Developer", Icons.Filled.Settings)
    data object RealtimeVoiceTest : Screen("settings/developer/realtime_voice", "Realtime voice", Icons.Filled.Settings)
    data object About : Screen("settings/about", "About", Icons.Filled.Settings)

    // Profile Inspector — full-screen read-only viewer with 4 tabs
    // (Config / SOUL / Memory / Skills) for a single profile. The
    // `profileName` path segment survives process death via Android's
    // SavedStateHandle arg propagation; the route template is registered
    // in the NavHost with a typed `StringType` arg, and the concrete
    // URI is built by `route(profileName)`.
    data object ProfileInspector : Screen(
        "settings/profile_inspector/{profileName}?section={section}",
        "Profile Inspector",
        Icons.Filled.Settings,
    ) {
        const val ARG_PROFILE_NAME: String = "profileName"
        const val ARG_SECTION: String = "section"

        /** Tab sections accepted by the `section` query arg. */
        const val SECTION_CONFIG: String = "config"
        const val SECTION_SOUL: String = "soul"
        const val SECTION_MEMORY: String = "memory"
        const val SECTION_SKILLS: String = "skills"

        /**
         * Build a concrete nav URI for the Profile Inspector.
         *
         * @param profileName the profile to inspect (required).
         * @param section     which tab to land on. One of
         *                    [SECTION_CONFIG] / [SECTION_SOUL] /
         *                    [SECTION_MEMORY] / [SECTION_SKILLS].
         *                    Defaults to [SECTION_CONFIG] so callers
         *                    that don't care about the tab — the
         *                    common "Inspect" card entry — land on
         *                    Config, matching the pre-deep-link
         *                    behaviour.
         *
         * Backwards compat: the route template still accepts a
         * call without `?section=` because the query arg has a
         * default value in the navArgument declaration. Old
         * deep-links without the arg resolve to Config.
         */
        fun route(profileName: String, section: String = SECTION_CONFIG): String {
            val encoded = java.net.URLEncoder.encode(profileName, "UTF-8")
                .replace("+", "%20")
            return "settings/profile_inspector/$encoded?section=$section"
        }
    }
}

@Composable
fun RelayApp() {
    val connectionViewModel: ConnectionViewModel = viewModel()
    val chatViewModel: ChatViewModel = viewModel()
    val terminalViewModel: TerminalViewModel = viewModel()
    val voiceViewModel: VoiceViewModel = viewModel()
    val updateViewModel: UpdateViewModel = viewModel()

    // Composition-scoped coroutine scope for firing connection-store suspend
    // writes off of UI click handlers (rename/revoke/remove) —
    // ConnectionStore's mutations are all suspend fns and we don't want to
    // block the main dispatcher from inside the composable body.
    val connectionSwitchScope = rememberCoroutineScope()

    // One-time init: the terminal channel ViewModel registers with the shared
    // multiplexer and observes the relay connection state so it can attach/
    // reattach automatically on network changes.
    val terminalAppContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        terminalViewModel.initialize(
            multiplexer = connectionViewModel.multiplexer,
            connectionState = connectionViewModel.relayConnectionState,
            authState = connectionViewModel.authState,
            authManager = connectionViewModel.authManager,
            tabNameStore = com.hermesandroid.relay.data.TerminalTabNameStore(terminalAppContext),
        )
    }

    // Cold-start relay kick.
    //
    // The ON_RESUME observer installed below in DisposableEffect misses the
    // Activity's very first ON_RESUME because DisposableEffect attaches the
    // observer AFTER the Activity has already resumed — LifecycleEventObserver
    // does not fire state transitions retroactively, it only sees *future*
    // events. That meant cold-start users had a disconnected relay UI until
    // they navigated to Settings (whose own `LaunchedEffect(Unit)` fires
    // `reconnectIfStale()` on entry) or backgrounded + foregrounded the app
    // to trigger a fresh ON_RESUME.
    //
    // Watching authState here handles both branches: on cold start the
    // persisted session rehydrates asynchronously from AuthManager and flips
    // authState → Paired after DataStore + crypto init. That transition fires
    // this LaunchedEffect, which kicks the WSS handshake regardless of which
    // tab the user is looking at. reconnectIfStale() is cheap and
    // self-guarding (paired && disconnected && hasUrl) so the second firing
    // on any later Paired-refresh is a no-op.
    val coldStartAuthState by connectionViewModel.authState.collectAsState()
    LaunchedEffect(coldStartAuthState) {
        if (coldStartAuthState is AuthState.Paired) {
            connectionViewModel.reconnectIfStale()
        }
    }

    // Lifecycle-aware revalidation. ON_RESUME (every time the app comes
    // to the foreground) flips both health badges to Probing and fires a
    // fresh API + relay /health probe. Without this hook, badges showed
    // stale Connected/Disconnected for up to 30s after foregrounding —
    // the entire StateFlow snapshot was preserved across backgrounding
    // even when the underlying server had died or the network had flipped.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                connectionViewModel.revalidate()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Initialize ChatViewModel reactively when the chat-routed API client becomes available
    val chatApiClient by connectionViewModel.chatApiClient.collectAsState()
    val lastSessionId by connectionViewModel.lastSessionId.collectAsState()
    val selectedProfile by connectionViewModel.selectedProfile.collectAsState()
    val activeConnectionId by connectionViewModel.activeConnectionId.collectAsState()

    val mediaContext = androidx.compose.ui.platform.LocalContext.current
    val voicePreferences = remember(mediaContext) { VoicePreferencesRepository(mediaContext) }
    val voiceSettings by voicePreferences.settings.collectAsState(initial = VoiceSettings())
    val selectedAudioRoute = VoiceAudioRoute.fromStorage(voiceSettings.audioRoute)
    val selectedAudioRouteState = rememberUpdatedState(selectedAudioRoute)
    val standardVoiceReady by connectionViewModel.standardVoiceReady.collectAsState()
    val relayVoiceReady by connectionViewModel.relayVoiceReady.collectAsState()
    val standardVoiceReadyState = rememberUpdatedState(standardVoiceReady)
    val relayVoiceReadyState = rememberUpdatedState(relayVoiceReady)

    // Voice pipeline wiring — mirrors ChatViewModel.initializeMedia (above).
    // We build a dedicated OkHttpClient so voice requests don't contend with
    // media fetches on the same dispatcher queue, then hand VoiceViewModel
    // the client + recorder + player it needs for the turn state machine.
    val voiceClient = remember {
        RelayVoiceClient(
            context = mediaContext,
            okHttpClient = okhttp3.OkHttpClient.Builder()
                .readTimeout(2, java.util.concurrent.TimeUnit.MINUTES)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
            relayUrlProvider = { connectionViewModel.effectiveRelayUrl.value },
            relayRouteChangesProvider = {
                connectionViewModel.activeEndpoint.mapNotNull { it?.relay?.url }
            },
            routeProbeRequester = { connectionViewModel.probeNow() },
            profileNameProvider = {
                AgentDisplay.profileRequestName(connectionViewModel.selectedProfile.value?.name)
            },
            sessionTokenProvider = {
                (connectionViewModel.authState.value as? AuthState.Paired)?.token
            },
            apiBearerTokenProvider = {
                connectionViewModel.getApiKey()
            },
        )
    }
    val standardVoiceClient = remember {
        StandardHermesVoiceClient(
            context = mediaContext,
            okHttpClient = okhttp3.OkHttpClient.Builder()
                .readTimeout(2, java.util.concurrent.TimeUnit.MINUTES)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
            apiUrlProvider = {
                val baseApiUrl = ProfileApiUrlResolver.normalize(
                    connectionViewModel.effectiveApiServerUrl.value,
                )
                ProfileApiUrlResolver.resolveForConnection(
                    profileApiUrl = connectionViewModel.selectedProfile.value?.apiServerUrl,
                    baseApiUrl = baseApiUrl,
                ) ?: connectionViewModel.effectiveApiServerUrl.value
            },
            apiBearerTokenProvider = { connectionViewModel.getApiKey() },
        )
    }
    val voiceAudioClient = remember {
        AutoVoiceAudioClient(
            standardClient = standardVoiceClient,
            relayClient = RelayVoiceAudioClientAdapter(voiceClient),
            routeProvider = { selectedAudioRouteState.value },
            standardReadyProvider = { standardVoiceReadyState.value },
            relayReadyProvider = { relayVoiceReadyState.value },
        )
    }

    // Profile Inspector client. Shares the same lazy relay URL + bearer
    // token providers as the voice client so any rotation/re-pair is
    // automatically picked up on the next fetch. Process-stable via
    // remember {} so the OkHttpClient isn't rebuilt on recomposition.
    val profileInspectorClient = remember {
        RelayProfileInspectorClient(
            okHttpClient = okhttp3.OkHttpClient.Builder()
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
            relayUrlProvider = { connectionViewModel.effectiveRelayUrl.value },
            sessionTokenProvider = {
                (connectionViewModel.authState.value as? AuthState.Paired)?.token
            },
        )
    }
    // Remembered so the AudioTrack buffers are synthesized once per process.
    // VoiceSfxPlayer is internally crash-proof — failed AudioTrack builds
    // become null-tracks that no-op — so we don't need an outer try/catch.
    val voiceSfxPlayer = remember { VoiceSfxPlayer(mediaContext) }
    val realtimePcmPlayer = remember { RealtimePcmPlayer(mediaContext) }
    LaunchedEffect(Unit) {
        val recorder = VoiceRecorder(mediaContext, voiceViewModel.viewModelScope)
        val player = VoicePlayer(mediaContext)
        voiceViewModel.initialize(
            voiceClient = voiceClient,
            voiceAudioClient = voiceAudioClient,
            chatViewModel = chatViewModel,
            recorder = recorder,
            player = player,
            realtimePcmPlayer = realtimePcmPlayer,
            sfxPlayer = voiceSfxPlayer,
            // === PHASE3-voice-intents-localdispatch ===
            // Wire the local in-process dispatcher so voice intents go
            // through `BridgeCommandHandler.handleLocalCommand` (same
            // dispatch + Tier 5 safety pipeline as WSS-incoming commands)
            // instead of round-tripping through the relay. The relay
            // correctly rejects phone-originated bridge.command envelopes
            // as "unexpected from phone" — the wire protocol is server→
            // phone for commands, and voice intents are phone-local.
            //
            // The multiplexer is still passed for non-bridge envelope use
            // cases and as a debug fallback (with a WARN log) if the
            // local dispatcher is somehow null at runtime.
            //
            // Discovered + fixed 2026-04-14 — see ROADMAP.md v0.4.1
            // "voice intent local dispatch loop" entry and the multiplexer
            // wiring fix in commit a568366 that unblocked the dispatch
            // path enough to surface this protocol mismatch.
            bridgeMultiplexer = connectionViewModel.multiplexer,
            localBridgeDispatcher = if (BuildFlavor.isSideload) {
                connectionViewModel.bridgeCommandHandler::handleLocalCommand
            } else {
                null
            },
            // === END PHASE3-voice-intents-localdispatch ===
            // 2026-04-17: persist the interaction-mode preference across
            // app restarts. VoicePreferencesRepository is the same repo
            // VoiceSettingsScreen reads/writes.
            voicePreferences = voicePreferences,
            voiceRelayPreflight = { connectionViewModel.verifyRelayForVoice() },
            voiceHandoffReporter = { connectionViewModel.recordVoiceHandoff(it) },
            bargeInPreferences = com.hermesandroid.relay.data.BargeInPreferencesRepository(mediaContext),
            vadEngineFactory = { com.hermesandroid.relay.audio.VadEngine(mediaContext) },
            bargeInListenerFactory = { vad, audioSessionIdProvider ->
                com.hermesandroid.relay.audio.BargeInListener.create(
                    mediaContext,
                    vad,
                    audioSessionIdProvider,
                )
            },
        )
    }

    // Multi-connection: wire ChatViewModel / VoiceViewModel into the
    // connection switch coordinator. Registered once per composition — the
    // coordinator stores these as callbacks and fires them in order when
    // switchConnection() is invoked so in-flight streams don't keep
    // scribbling into the outgoing connection's state after the swap.
    //
    // Voice callback is gated on sideload: googlePlay still builds the
    // VoiceViewModel (it's wired unconditionally above) but voice mode is a
    // sideload-only feature, so there's no live turn to stop on stock
    // googlePlay builds. The stop-callback is harmless either way; the gate
    // mirrors the flavor check on the global unattended banner below.
    LaunchedEffect(Unit) {
        connectionViewModel.registerStreamCancelCallback {
            chatViewModel.cancelStream()
        }
        if (BuildFlavor.isSideload) {
            // VoiceViewModel.stop() isn't defined — use exitVoiceMode() which
            // is the closest semantic match (tears down the active turn and
            // returns the UI to text mode). Worker B2 confirmed no stop()
            // method exists as of the connection-switch pass, so this rename
            // is intentional and kept as-is. If a proper stop() is added
            // later, swap this for vvm.stop().
            connectionViewModel.registerVoiceStopCallback {
                voiceViewModel.exitVoiceMode()
            }
        }
        chatViewModel.observeConnectionSwitches(connectionViewModel.connectionSwitchEvents)
    }

    LaunchedEffect(chatApiClient) {
        chatApiClient?.let { client ->
            chatViewModel.initialize(client, connectionViewModel.chatHandler)
            chatViewModel.updateApiClient(client)

            // Wire inbound-media dependencies. Safe to call on every reinit —
            // idempotent rewire of the ChatHandler callbacks.
            chatViewModel.initializeMedia(
                context = mediaContext,
                relayHttpClient = connectionViewModel.relayHttpClient,
                mediaSettingsRepo = connectionViewModel.mediaSettingsRepo,
                mediaCacheWriter = connectionViewModel.mediaCacheWriter
            )

            // Agent-profile pick provider (Pass 2). Lambda reads the latest
            // StateFlow value on every send, so ChatViewModel never needs a
            // direct reference to ConnectionViewModel. Safe to rewire on
            // every API-client swap — the lambda captures the long-lived
            // VM, not the (per-connection) apiClient.
            chatViewModel.setSelectedProfileProvider {
                connectionViewModel.selectedProfile.value
            }
            chatViewModel.setEffectiveProfileProvider {
                AgentDisplay.effectiveProfile(
                    selectedProfile = connectionViewModel.selectedProfile.value,
                    profiles = connectionViewModel.agentProfiles.value,
                )
            }

            // Wire session persistence callback
            chatViewModel.onSessionChanged = { sessionId ->
                connectionViewModel.saveLastSessionId(sessionId)
            }
        }
    }

    LaunchedEffect(chatApiClient, activeConnectionId, selectedProfile?.name, lastSessionId) {
        if (chatApiClient == null) return@LaunchedEffect
        chatViewModel.switchProfileContext(
            contextKey = AgentDisplay.profileContextKey(
                connectionId = activeConnectionId,
                profileName = selectedProfile?.name,
            ),
            sessionId = lastSessionId,
        )
        chatViewModel.refreshSessions()
    }

    LaunchedEffect(selectedProfile?.name) {
        voiceViewModel.onProfileChanged(
            AgentDisplay.profileRequestName(selectedProfile?.name)
        )
    }

    // === PHASE3-status: sync granular phone-status settings to chat ===
    val appContextEnabled by connectionViewModel.appContextEnabled.collectAsState()
    val appContextBridgeState by connectionViewModel.appContextBridgeState.collectAsState()
    val appContextCurrentApp by connectionViewModel.appContextCurrentApp.collectAsState()
    val appContextBattery by connectionViewModel.appContextBattery.collectAsState()
    val appContextSafetyStatus by connectionViewModel.appContextSafetyStatus.collectAsState()
    LaunchedEffect(
        appContextEnabled,
        appContextBridgeState,
        appContextCurrentApp,
        appContextBattery,
        appContextSafetyStatus,
    ) {
        chatViewModel.appContextSettings = com.hermesandroid.relay.util.AppContextSettings(
            master = appContextEnabled,
            bridgeState = appContextBridgeState,
            currentApp = appContextCurrentApp,
            battery = appContextBattery,
            safetyStatus = appContextSafetyStatus,
        )
    }
    // === END PHASE3-status ===

    // Sync tool annotation parsing toggle to ChatHandler
    val parseAnnotations by connectionViewModel.parseToolAnnotations.collectAsState()
    LaunchedEffect(parseAnnotations) {
        connectionViewModel.chatHandler.parseToolAnnotations = parseAnnotations
    }

    // Sync streaming endpoint preference to chat. Resolves "auto" against the
    // current server capabilities so vanilla upstream + bootstrap-injected
    // sessions API picks /v1/chat/completions for portable SSE chat while
    // still using /api/sessions/* for browse/rename/delete.
    val streamingEndpoint by connectionViewModel.streamingEndpoint.collectAsState()
    val serverCapabilities by connectionViewModel.serverCapabilities.collectAsState()
    LaunchedEffect(streamingEndpoint, serverCapabilities) {
        chatViewModel.streamingEndpoint = connectionViewModel.resolveStreamingEndpoint(streamingEndpoint)
    }

    // What's New auto-show
    val showWhatsNew by connectionViewModel.showWhatsNew.collectAsState()

    if (showWhatsNew) {
        WhatsNewDialog(onDismiss = { connectionViewModel.dismissWhatsNew() })
    }

    // Mark version as seen on first launch (when there's no previous version)
    val onboardingCompleted by connectionViewModel.onboardingCompleted.collectAsState()
    LaunchedEffect(onboardingCompleted) {
        if (onboardingCompleted) {
            connectionViewModel.markVersionSeen()
        }
    }

    // Observe theme preference
    val themePreference by connectionViewModel.theme.collectAsState()
    val fontScale by connectionViewModel.fontScale.collectAsState()

    HermesRelayTheme(themePreference = themePreference, fontScale = fontScale) {
        val navController = rememberNavController()
        var postOnboardingRoute by remember { mutableStateOf<String?>(null) }

        // === PHASE3-safety-rails-followup: cross-layer deep-link nav ===
        // Collect navigation requests posted by external launchers (e.g., the
        // BridgeForegroundService notification's "Settings" action). The
        // service sets EXTRA_NAV_ROUTE on its launch intent → MainActivity's
        // onCreate / onNewIntent reads it and pumps it onto NavRouteRequest →
        // we forward each emission to the NavController. Single observer at
        // the app root so every screen benefits.
        LaunchedEffect(navController) {
            com.hermesandroid.relay.util.NavRouteRequest.requests.collect { route ->
                navController.navigate(route) {
                    launchSingleTop = true
                }
            }
        }
        // === END PHASE3-safety-rails-followup ===

        LaunchedEffect(onboardingCompleted, postOnboardingRoute) {
            val route = postOnboardingRoute
            if (onboardingCompleted && route != null) {
                postOnboardingRoute = null
                navController.navigate(route) {
                    popUpTo(Screen.Onboarding.route) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }

        // startDestination uses the route TEMPLATE so it matches the
        // composable registered below; optional args default to null/false.
        val startDestination = if (onboardingCompleted) Screen.Chat.route else Screen.Onboarding.route

        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route
        val isOnboarding = currentRoute == Screen.Onboarding.route
        var bridgePrimaryReturnRoute by remember { mutableStateOf<String?>(null) }
        var bridgePrimaryReturnLabel by remember { mutableStateOf<String?>(null) }

        fun rememberBridgeReturn(route: String, label: String) {
            bridgePrimaryReturnRoute = route
            bridgePrimaryReturnLabel = label
        }

        fun clearBridgeReturn() {
            bridgePrimaryReturnRoute = null
            bridgePrimaryReturnLabel = null
        }

        val bridgeReturnTitle = bridgePrimaryReturnLabel?.let { "Return to $it" }
        val bridgeReturnSubtitle = when (bridgePrimaryReturnLabel) {
            "Chat" -> "Back to conversation"
            "Manage" -> "Back to management"
            else -> "Back to previous tab"
        }
        val bridgeReturnAction: (() -> Unit)? = bridgePrimaryReturnRoute?.let { route ->
            {
                clearBridgeReturn()
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }

        LaunchedEffect(currentRoute) {
            if (
                currentRoute == Screen.Chat.route ||
                currentRoute == Screen.Manage.route ||
                currentRoute == Screen.Settings.route ||
                currentRoute == Screen.Onboarding.route
            ) {
                clearBridgeReturn()
            }
        }

        val density = LocalDensity.current
        val imeBottom = WindowInsets.ime.getBottom(density)
        val isKeyboardVisible = imeBottom > 0

        // Voice mode is a full-screen modality — while it's active we hide the
        // bottom navigation bar so the voice overlay can own the entire screen
        // without the Chat/Terminal/Bridge/Settings tabs peeking through below.
        val voiceUiState by voiceViewModel.uiState.collectAsState()
        val globalConnectionStatus by connectionViewModel.globalConnectionStatus.collectAsState()
        val apiReachable by connectionViewModel.apiServerReachable.collectAsState()
        val apiHealth by connectionViewModel.apiServerHealth.collectAsState()
        val relayReady by connectionViewModel.relayReady.collectAsState()
        val activeConnection by connectionViewModel.activeConnection.collectAsState()
        val activeEndpoint by connectionViewModel.activeEndpoint.collectAsState()
        val serverModelName by chatViewModel.serverModelName.collectAsState()
        val appReady by connectionViewModel.isReady.collectAsState()
        var startupGateMinElapsed by remember { mutableStateOf(false) }
        var startupGateTimedOut by remember { mutableStateOf(false) }
        var startupGateReleased by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            delay(650L)
            startupGateMinElapsed = true
        }
        LaunchedEffect(Unit) {
            delay(5_500L)
            startupGateTimedOut = true
        }

        val hasStartupConnection = activeConnection?.apiServerUrl?.isNotBlank() == true
        val startupConnectionResolved = appReady && (
            !hasStartupConnection ||
                apiReachable ||
                apiHealth == ConnectionViewModel.HealthStatus.Reachable ||
                apiHealth == ConnectionViewModel.HealthStatus.Unreachable ||
                startupGateTimedOut
            )
        LaunchedEffect(
            onboardingCompleted,
            startupGateMinElapsed,
            startupConnectionResolved,
        ) {
            if (
                onboardingCompleted &&
                !startupGateReleased &&
                startupGateMinElapsed &&
                startupConnectionResolved
            ) {
                startupGateReleased = true
            }
        }
        val showStartupSphere =
            onboardingCompleted &&
                !startupGateReleased &&
                !startupGateTimedOut &&
                !voiceUiState.voiceMode

        // Single snackbar host for the whole app — exposed via LocalSnackbarHost
        // so voice/chat/settings screens can call showHumanError from their
        // error-collector LaunchedEffects without threading state downwards.
        val snackbarHostState = remember { SnackbarHostState() }

        // Relay-pushed `profiles.updated` announcements. AuthManager
        // filters out idempotent pushes (same names + same count), so
        // this only fires when the profile list actually changed.
        LaunchedEffect(connectionViewModel) {
            connectionViewModel.profilesUpdatedEvents.collect {
                snackbarHostState.showSnackbar("Profiles updated")
            }
        }

        // === v0.4.1 polish: global unattended-access banner ===
        // Rendered at the top of the scaffold on every tab when BOTH the
        // master toggle is ON and unattended access is ON. The per-screen
        // UnattendedAccessRow inside Bridge already surfaces this, but the
        // user's typical workflow after enabling it is to leave the Bridge
        // tab — we don't want them to forget about a screen-waking opt-in
        // just because they're reading Chat history. Kept as a thin 28dp
        // amber strip so its footprint doesn't eat scroll space.
        //
        // State is read directly from the two DataStore repos instead of
        // going through BridgeViewModel. The VM is Bridge-tab-scoped; the
        // banner lives at app-root scope. Reading the repos here avoids
        // instantiating the entire BridgeViewModel (with its many side-
        // effectful init blocks) just to peek at two StateFlows.
        // Key the remembers off applicationContext (process-stable) instead
        // of LocalContext.current (changes on rotation, dark-mode swap,
        // locale change). Keying off a transient context would re-construct
        // the repos — and their DataStore handles — on every config change.
        val bridgeAppCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
        val bridgePrefsRepo = remember(bridgeAppCtx) { BridgePreferencesRepository(bridgeAppCtx) }
        val safetyPrefsRepo = remember(bridgeAppCtx) { BridgeSafetyPreferencesRepository(bridgeAppCtx) }
        // Stabilize the mapped flows in `remember` — invoking `.map` directly
        // inside composition trips the `FlowOperatorInvokedInComposition` lint
        // rule because a fresh Flow instance would be created on every
        // recomposition, defeating collectAsState's state-preservation.
        val masterEnabledFlow = remember(bridgePrefsRepo) {
            bridgePrefsRepo.settings.map { it.masterEnabled }
        }
        val unattendedEnabledFlow = remember(safetyPrefsRepo) {
            safetyPrefsRepo.settings.map { it.unattendedAccessEnabled }
        }
        val masterEnabled by masterEnabledFlow.collectAsState(initial = false)
        val unattendedEnabled by unattendedEnabledFlow.collectAsState(initial = false)
        // Sideload-only: googlePlay has no wake lock and the unattended
        // flag never gets written there — gating here is defence in depth
        // and makes the check cheap via R8 in release builds.
        val showUnattendedBanner = BuildFlavor.isSideload &&
            masterEnabled &&
            unattendedEnabled &&
            !isOnboarding &&
            !showStartupSphere &&
            !voiceUiState.voiceMode
        val showConnectionStatusBanner =
            globalConnectionStatus != null &&
                !isOnboarding &&
                !showStartupSphere &&
                !voiceUiState.voiceMode
        val onConnectionStatusBannerClick: () -> Unit = {
            val title = globalConnectionStatus?.title.orEmpty()
            val destination = when {
                title.contains("No Hermes connection", ignoreCase = true) -> Screen.Pair.route()
                title.contains("dashboard", ignoreCase = true) -> Screen.Manage.route
                else -> Screen.ConnectionsSettings.route
            }
            navController.navigate(destination) {
                launchSingleTop = true
            }
        }
        // === END v0.4.1 polish ===

        // Multi-connection switcher has moved into the AgentInfoSheet's
        // Connection section (see ConnectionInfoSheet.kt) — the top-bar
        // ConnectionChip row that used to live here was duplicating that
        // surface and eating vertical space. The `connectionSheetVisible`
        // state and the ConnectionSwitcherSheet declaration further down
        // are kept for any callers that still need the modal switcher
        // (e.g. programmatic routes), but nothing in the default UI opens
        // them anymore.
        //
        // Kept as a named const so the `if (showUnattendedBanner ||
        // connectionChipVisible)` window-inset conditional below still
        // compiles without a deeper rewrite. Collapses to false now that
        // the chip is gone; when the unattended banner is absent too, the
        // Scaffold goes back to default TopAppBar status-bar padding.
        val connectionChipVisible = false

        Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        // The banner takes its own vertical space above the Scaffold so
        // no screen's content is covered by it (unlike floating overlays
        // that would need per-screen padding compensation). Use
        // AnimatedVisibility to fade in/out on state transitions.
        AnimatedVisibility(
            visible = showUnattendedBanner,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            UnattendedGlobalBanner(
                onTap = {
                    navController.navigate(Screen.Bridge.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }

        // Sideload-only update banner. UpdateViewModel short-circuits on
        // googlePlay so this block is effectively dead on that flavor.
        // bannerState hides the banner for versions the user has
        // dismissed, re-appearing automatically on a newer release.
        val updateBannerState by updateViewModel.bannerState.collectAsState()
        val availableUpdate = (updateBannerState as? UpdateCheckResult.Available)?.update
        AnimatedVisibility(
            visible = availableUpdate != null && !isOnboarding,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            availableUpdate?.let { upd ->
                UpdateBanner(
                    update = upd,
                    onDismiss = { updateViewModel.dismiss(upd.latestVersion) },
                )
            }
        }

        AnimatedVisibility(
            visible = showConnectionStatusBanner,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(180)),
        ) {
            ConnectionStatusBanner(
                status = globalConnectionStatus,
                includeStatusBarPadding = !showUnattendedBanner && availableUpdate == null,
                onClick = onConnectionStatusBannerClick,
            )
        }

        // (The app-wide ConnectionChip row that used to live here has been
        // removed. Multi-connection switching is now reachable from the
        // AgentInfoSheet's Connection section — see ConnectionInfoSheet.kt's
        // "Multi-connection switcher" block. Keeps the top chrome tidy and
        // puts the control next to the related Profile + Personality
        // radios.)
        Scaffold(
            // weight(1f) instead of fillMaxSize(): Column arranges children
            // top-down, so a fillMaxSize child would try to claim the full
            // parent height and overflow past the banner. weight(1f) takes
            // exactly the remaining main-axis space after the banner (which
            // is 0 when the banner is hidden).
            //
            // consumeWindowInsets is conditional on banner visibility: the
            // banner pads itself for WindowInsets.statusBars, and without
            // this consume, child TopAppBars (e.g. ChatScreen's) also pad
            // for status bars — yielding ~24dp of double-padding between
            // the banner and the first row of screen content. When the
            // banner is hidden we want the default behavior (TopAppBar
            // self-pads below the status bar).
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(
                    // Any banner / chip sitting above the Scaffold that
                    // pads itself for the status bar means child TopAppBars
                    // would otherwise double-pad and render too far down.
                    // Consume the inset here so the Scaffold tree treats
                    // the top edge as already handled.
                    if (showUnattendedBanner || showConnectionStatusBanner || connectionChipVisible) {
                        Modifier.consumeWindowInsets(WindowInsets.statusBars)
                    } else {
                        Modifier
                    }
                ),
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!isOnboarding && !isKeyboardVisible && !showStartupSphere && !voiceUiState.voiceMode) {
                    val leading = when {
                        apiReachable -> "api online"
                        relayReady -> "relay connected"
                        else -> "offline"
                    }
                    val leadingColor = when {
                        apiReachable -> RelayRefresh.Green
                        relayReady -> RelayRefresh.Relay
                        else -> RelayRefresh.Danger
                    }
                    val routeLabel = activeEndpoint?.displayLabel()
                        ?: activeConnection?.label
                        ?: "no route"
                    val profileLabel = selectedProfile?.name?.takeIf { it.isNotBlank() } ?: "default"
                    val modelLabel = serverModelName.takeIf { it.isNotBlank() } ?: "model pending"
                    val safetyLabel = if (BuildFlavor.isSideload && masterEnabled) {
                        "safety: ${if (unattendedEnabled) "unattended" else "on"}"
                    } else {
                        "profile: $profileLabel"
                    }
                    RelayStatusStrip(
                        leading = "$leading / $routeLabel",
                        trailing = "$modelLabel / $safetyLabel",
                        leadingColor = leadingColor,
                    )
                }
            }
        ) { innerPadding ->
            CompositionLocalProvider(LocalSnackbarHost provides snackbarHostState) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                composable(Screen.Onboarding.route) {
                    // The wizard inside OnboardingScreen now owns credential
                    // application via ConnectionViewModel.applyPairingPayload,
                    // so the callback collapses to "mark complete + navigate
                    // to chat". The legacy 4-arg signature was discarding the
                    // relay block entirely.
                    //
                    // CRITICAL: pass the Activity-scoped connectionViewModel
                    // explicitly instead of letting OnboardingScreen fetch
                    // its own via `viewModel()`. A bare `viewModel()` call
                    // inside a `composable(...)` block binds to the
                    // NavBackStackEntry's store, so the onboarding VM gets
                    // destroyed by `popUpTo(Onboarding) { inclusive = true }`
                    // on navigation to Chat — taking the freshly-minted
                    // session token with it. See the full writeup on the
                    // OnboardingScreen function definition.
                    OnboardingScreen(
                        connectionViewModel = connectionViewModel,
                        onComplete = {
                            connectionViewModel.completeOnboarding()
                            // Concrete bare-"chat" URI — the Screen.Chat.route
                            // field is the route TEMPLATE (contains
                            // `{openAgentSheet}`) and must not be navigated
                            // to directly; build the URI via Screen.Chat.route(...).
                            navController.navigate(Screen.Chat.route(openAgentSheet = false)) {
                                popUpTo(Screen.Onboarding.route) { inclusive = true }
                            }
                        },
                        onManageSignIn = {
                            postOnboardingRoute = Screen.Manage.route
                            connectionViewModel.completeOnboarding()
                        }
                    )
                }
                composable(
                    route = Screen.Chat.route,
                    arguments = listOf(
                        navArgument(Screen.Chat.ARG_OPEN_AGENT_SHEET) {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
                ) { backStackEntry ->
                    // Responsive bubble width based on screen width
                    val configuration = LocalConfiguration.current
                    val screenWidthDp = configuration.screenWidthDp.dp
                    val maxBubbleWidth = when {
                        screenWidthDp >= 840.dp -> 600.dp  // Expanded (tablet)
                        screenWidthDp >= 600.dp -> 480.dp  // Medium (landscape / small tablet)
                        else -> 300.dp                      // Compact (phone portrait)
                    }

                    // Consume-once semantics: ChatScreen only treats the
                    // flag as "open the sheet on entry" while it's still
                    // `true` in the back-stack entry's arguments. After
                    // firing, ChatScreen writes `false` back into the same
                    // arguments bundle so recompositions (tab switches,
                    // config changes, process resume) don't re-open the
                    // sheet.
                    val openAgentSheetArg = backStackEntry.arguments
                        ?.getBoolean(Screen.Chat.ARG_OPEN_AGENT_SHEET, false) == true

                    ChatScreen(
                        chatViewModel = chatViewModel,
                        connectionViewModel = connectionViewModel,
                        voiceViewModel = voiceViewModel,
                        voiceClient = voiceClient,
                        maxBubbleWidth = maxBubbleWidth,
                        openAgentSheetOnEntry = openAgentSheetArg,
                        onAgentSheetArgConsumed = {
                            backStackEntry.arguments?.putBoolean(
                                Screen.Chat.ARG_OPEN_AGENT_SHEET, false,
                            )
                        },
                        // AgentInfoSheet footer jumps straight into the full
                        // Connections CRUD screen — saves a detour through
                        // Settings → Connections.
                        onNavigateToConnections = {
                            navController.navigate(Screen.ConnectionsSettings.route)
                        },
                        onNavigateToConnect = {
                            navController.navigate(Screen.Pair.route()) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToManage = {
                            navController.navigate(Screen.Manage.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToBridge = {
                            rememberBridgeReturn(
                                route = Screen.Chat.route(openAgentSheet = false),
                                label = "Chat",
                            )
                            navController.navigate(Screen.Bridge.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToTerminal = {
                            navController.navigate(Screen.Terminal.route) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToSettings = {
                            navController.navigate(Screen.Settings.route) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToProfileInspector = { profileName ->
                            navController.navigate(Screen.ProfileInspector.route(profileName)) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable(Screen.Manage.route) {
                    DashboardManagementScreen(
                        connectionViewModel = connectionViewModel,
                        onNavigateToConnections = {
                            navController.navigate(Screen.ConnectionsSettings.route)
                        },
                        onNavigateToChat = {
                            navController.navigate(Screen.Chat.route(openAgentSheet = false)) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToBridge = {
                            rememberBridgeReturn(
                                route = Screen.Manage.route,
                                label = "Manage",
                            )
                            navController.navigate(Screen.Bridge.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onNavigateToTerminal = {
                            navController.navigate(Screen.Terminal.route) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToSettings = {
                            navController.navigate(Screen.Settings.route) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
                composable(Screen.Terminal.route) {
                    if (coldStartAuthState is AuthState.Paired) {
                        TerminalScreen(
                            terminalViewModel = terminalViewModel,
                            connectionViewModel = connectionViewModel
                        )
                    } else {
                        PowerFeatureGateScreen(
                            title = "Terminal",
                            summary = "Open a server shell through your paired relay session.",
                            status = PowerFeatureGateStatus.fromRelayAuth(coldStartAuthState),
                            onPrimaryAction = {
                                navController.navigate(Screen.Pair.route())
                            },
                        )
                    }
                }
                composable(Screen.Bridge.route) {
                    if (coldStartAuthState !is AuthState.Paired) {
                        PowerFeatureGateScreen(
                            title = "Bridge",
                            summary = "Let Hermes send approved bridge commands to this phone.",
                            status = PowerFeatureGateStatus.fromRelayAuth(coldStartAuthState),
                            onPrimaryAction = {
                                navController.navigate(Screen.Pair.route())
                            },
                            onBack = bridgeReturnAction,
                        )
                    } else {
                        if (BuildFlavor.isSideload) {
                            BridgeScreen(
                                connectionViewModel = connectionViewModel,
                                returnTitle = bridgeReturnTitle,
                                returnSubtitle = bridgeReturnSubtitle,
                                returnLabel = bridgePrimaryReturnLabel ?: "Back",
                                onReturn = bridgeReturnAction,
                                onNavigateToBridgeSafety = {
                                    navController.navigate(Screen.BridgeSafetySettings.route)
                                },
                                onNavigateToChat = {
                                    clearBridgeReturn()
                                    navController.navigate(Screen.Chat.route(openAgentSheet = false)) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onNavigateToManage = {
                                    clearBridgeReturn()
                                    navController.navigate(Screen.Manage.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onNavigateToSettings = {
                                    navController.navigate(Screen.Settings.route) {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        } else {
                            BridgeCoreScreen(
                                connectionViewModel = connectionViewModel,
                                returnTitle = bridgeReturnTitle,
                                returnSubtitle = bridgeReturnSubtitle,
                                returnLabel = bridgePrimaryReturnLabel ?: "Back",
                                onReturn = bridgeReturnAction,
                                onNavigateToConnections = {
                                    navController.navigate(Screen.ConnectionsSettings.route)
                                },
                                onNavigateToChat = {
                                    clearBridgeReturn()
                                    navController.navigate(Screen.Chat.route(openAgentSheet = false)) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onNavigateToManage = {
                                    clearBridgeReturn()
                                    navController.navigate(Screen.Manage.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onNavigateToTerminal = {
                                    navController.navigate(Screen.Terminal.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                onNavigateToVoiceSettings = {
                                    navController.navigate(Screen.VoiceSettings.route)
                                },
                                onNavigateToNotificationCompanion = {
                                    navController.navigate(Screen.NotificationCompanionSettings.route)
                                },
                                onNavigateToMediaSettings = {
                                    navController.navigate(Screen.MediaSettings.route)
                                },
                                onNavigateToRelaySessions = {
                                    navController.navigate(Screen.PairedDevices.route)
                                },
                                onNavigateToSettings = {
                                    navController.navigate(Screen.Settings.route) {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                    }
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        connectionViewModel = connectionViewModel,
                        chatViewModel = chatViewModel,
                        // (The `onNavigateToChatWithAgentSheet` callback that
                        // used to live here was removed 2026-04-21. Tapping
                        // the Active Agent card on Settings now opens the
                        // AgentInfoSheet inline on THAT screen — closing the
                        // sheet returns the user to Settings instead of
                        // leaving them on Chat after a confusing redirect.)
                        onNavigateToConnections = {
                            navController.navigate(Screen.ConnectionsSettings.route)
                        },
                        onNavigateToManage = {
                            navController.navigate(Screen.Manage.route)
                        },
                        onNavigateToChatSettings = {
                            navController.navigate(Screen.ChatSettings.route)
                        },
                        onNavigateToTerminal = {
                            navController.navigate(Screen.Terminal.route)
                        },
                        onNavigateToBridge = {
                            clearBridgeReturn()
                            navController.navigate(Screen.Bridge.route)
                        },
                        onNavigateToMediaSettings = {
                            navController.navigate(Screen.MediaSettings.route)
                        },
                        onNavigateToAppearanceSettings = {
                            navController.navigate(Screen.AppearanceSettings.route)
                        },
                        onNavigateToAnalytics = {
                            navController.navigate(Screen.Analytics.route)
                        },
                        onNavigateToVoiceSettings = {
                            navController.navigate(Screen.VoiceSettings.route)
                        },
                        onNavigateToNotificationCompanion = {
                            navController.navigate(Screen.NotificationCompanionSettings.route)
                        },
                        // === PHASE3-safety-rails: bridge safety route ===
                        onNavigateToBridgeSafety = {
                            navController.navigate(Screen.BridgeSafetySettings.route)
                        },
                        // === END PHASE3-safety-rails ===
                        onNavigateToPairedDevices = {
                            navController.navigate(Screen.PairedDevices.route)
                        },
                        onNavigateToDeveloperSettings = {
                            navController.navigate(Screen.DeveloperSettings.route)
                        },
                        onNavigateToAbout = {
                            navController.navigate(Screen.About.route)
                        },
                        onNavigateToProfileInspector = { profileName ->
                            navController.navigate(
                                Screen.ProfileInspector.route(profileName),
                            )
                        },
                    )
                }
                composable(Screen.VoiceSettings.route) {
                    VoiceSettingsScreen(
                        voiceViewModel = voiceViewModel,
                        voiceClient = voiceClient,
                        selectedProfile = selectedProfile,
                        onBack = { navController.popBackStack() }
                    )
                }
                // === PHASE3-notif-listener-followup: notification companion route ===
                composable(Screen.NotificationCompanionSettings.route) {
                    NotificationCompanionSettingsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
                // === END PHASE3-notif-listener-followup ===
                // === PHASE3-safety-rails: bridge safety route ===
                composable(Screen.BridgeSafetySettings.route) {
                    if (BuildFlavor.isSideload) {
                        BridgeSafetySettingsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    } else {
                        BridgeCoreScreen(
                            connectionViewModel = connectionViewModel,
                            onNavigateToConnections = {
                                navController.navigate(Screen.ConnectionsSettings.route)
                            },
                            onNavigateToChat = {
                                navController.navigate(Screen.Chat.route(openAgentSheet = false)) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onNavigateToManage = {
                                navController.navigate(Screen.Manage.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onNavigateToTerminal = {
                                navController.navigate(Screen.Terminal.route)
                            },
                            onNavigateToVoiceSettings = {
                                navController.navigate(Screen.VoiceSettings.route)
                            },
                            onNavigateToNotificationCompanion = {
                                navController.navigate(Screen.NotificationCompanionSettings.route)
                            },
                            onNavigateToMediaSettings = {
                                navController.navigate(Screen.MediaSettings.route)
                            },
                            onNavigateToRelaySessions = {
                                navController.navigate(Screen.PairedDevices.route)
                            },
                            onNavigateToSettings = {
                                navController.navigate(Screen.Settings.route) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    }
                }
                // === END PHASE3-safety-rails ===
                composable(Screen.PairedDevices.route) {
                    if (coldStartAuthState is AuthState.Paired) {
                        PairedDevicesScreen(
                            connectionViewModel = connectionViewModel,
                            onBack = { navController.popBackStack() },
                            onRequestRepair = {
                                navController.navigate(Screen.Pair.route())
                            }
                        )
                    } else {
                        PowerFeatureGateScreen(
                            title = "Relay sessions",
                            summary = "Review and revoke devices paired with this relay.",
                            status = PowerFeatureGateStatus.fromRelayAuth(coldStartAuthState),
                            onPrimaryAction = {
                                navController.navigate(Screen.Pair.route())
                            },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                // (The `composable(Screen.ConnectionSettings.route)` block
                // that used to live here — hosting the singular, legacy
                // 1400-line `ConnectionSettingsScreen` — was removed on
                // 2026-04-21 as part of the connection-settings
                // unification. Everything that screen did (pair, manual
                // URL config, TLS/insecure toggle, manual pairing code
                // fallback) now lives inline on the active card of the
                // plural `ConnectionsSettings` screen below, via the
                // expandable sections in `ActiveConnectionSections.kt`.
                // The corresponding `data object ConnectionSettings` was
                // also removed from the `Screen` sealed class, and the
                // `onNavigateToConnectionSettings` param on
                // `SettingsScreen` was dropped.)
                composable(Screen.ConnectionsSettings.route) {
                    val connectionsList by connectionViewModel.connections.collectAsState()
                    val activeId by connectionViewModel.activeConnectionId.collectAsState()
                    val activeRelayUiState by connectionViewModel.relayUiState.collectAsState()
                    ConnectionsSettingsScreen(
                        connections = connectionsList,
                        activeConnectionId = activeId,
                        activeRelayUiState = activeRelayUiState,
                        onReconnectActive = {
                            connectionViewModel.connectRelay()
                            connectionSwitchScope.launch {
                                snackbarHostState.showSnackbar("Reconnecting to relay…")
                            }
                        },
                        // Multi-connection: typed VM helpers (Worker B2)
                        // handle the full mutations — rename persists via
                        // ConnectionStore.updateConnection; revoke issues
                        // the server-side /sessions/{prefix} DELETE and
                        // clears local auth; remove deletes the backing
                        // EncryptedSharedPreferences via ConnectionStore.
                        onRenameConnection = { id, newLabel ->
                            connectionSwitchScope.launch {
                                connectionViewModel.renameConnection(id, newLabel)
                                    .onFailure { err ->
                                        snackbarHostState.showSnackbar(
                                            err.message ?: "Rename failed",
                                        )
                                    }
                            }
                        },
                        onRepairConnection = { id ->
                            connectionSwitchScope.launch {
                                // Wait for the AuthManager swap before the
                                // scanner can apply a QR payload.
                                connectionViewModel.switchConnection(id).join()
                                navController.navigate(Screen.Pair.route(id))
                            }
                        },
                        onRevokeConnection = { id ->
                            connectionSwitchScope.launch {
                                val result = connectionViewModel.revokeConnection(id)
                                if (result.isFailure) {
                                    // v1 constraint: revokeConnection only
                                    // works on the active connection.
                                    // Surface a snackbar so the user
                                    // understands why nothing happened.
                                    snackbarHostState.showSnackbar(
                                        "Only the active connection can be revoked right now",
                                    )
                                }
                            }
                        },
                        onRemoveConnection = { id ->
                            connectionSwitchScope.launch {
                                connectionViewModel.removeConnection(id)
                            }
                        },
                        onAddConnection = {
                            connectionSwitchScope.launch {
                                // Create and switch to the placeholder before
                                // opening the wizard. Otherwise a fast scan or
                                // standard save can write into the outgoing
                                // connection's auth store.
                                val id = connectionViewModel.beginAddConnection(
                                    preAllocatedId = java.util.UUID.randomUUID().toString(),
                                )
                                navController.navigate(
                                    Screen.Pair.route(connectionId = id)
                                )
                            }
                        },
                        onBack = { navController.popBackStack() },
                        onNavigateToManage = {
                            navController.navigate(Screen.Manage.route) {
                                launchSingleTop = true
                            }
                        },
                        onNavigateToPairedDevices = {
                            navController.navigate(Screen.PairedDevices.route)
                        },
                        // Pass the VM so the active card can render the
                        // shared EndpointsCard inline AND the unified
                        // Advanced section (manual URL / insecure toggle /
                        // manual pairing code). Null-safe — if the VM
                        // isn't wired (tests, previews), the active card
                        // degrades to the flat layout.
                        connectionViewModel = connectionViewModel,
                    )
                }
                composable(
                    route = Screen.Pair.route,
                    arguments = listOf(
                        navArgument(Screen.Pair.ARG_CONNECTION_ID) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument(Screen.Pair.ARG_AUTO_START) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { backStackEntry ->
                    val connectionIdArg = backStackEntry.arguments
                        ?.getString(Screen.Pair.ARG_CONNECTION_ID)
                    val autoStartArg = backStackEntry.arguments
                        ?.getString(Screen.Pair.ARG_AUTO_START)
                    com.hermesandroid.relay.ui.screens.PairScreen(
                        connectionViewModel = connectionViewModel,
                        autoStart = autoStartArg,
                        onComplete = {
                            // Both "add new" and "re-pair in place" now
                            // route to this screen with connectionIdArg
                            // set — add-new goes through
                            // ConnectionViewModel.beginAddConnection()
                            // which pre-creates the placeholder + switches
                            // to it before navigating here, so
                            // applyPairingPayload lands on the correct
                            // auth store. Nothing extra to do on success
                            // beyond popping the backstack.
                            navController.popBackStack()
                        },
                        onManageSignIn = {
                            navController.popBackStack()
                            navController.navigate(Screen.Manage.route) {
                                launchSingleTop = true
                            }
                        },
                        onCancel = {
                            // If the user bailed out before completing a
                            // pair, discard the placeholder we pre-created
                            // on entry. Safe no-op for real (paired)
                            // connections; only removes placeholders that
                            // never got a pairedAt stamp.
                            if (connectionIdArg != null) {
                                connectionSwitchScope.launch {
                                    connectionViewModel.discardPlaceholderConnection(connectionIdArg)
                                }
                            }
                            navController.popBackStack()
                        },
                    )
                }
                composable(Screen.ChatSettings.route) {
                    ChatSettingsScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Screen.MediaSettings.route) {
                    MediaSettingsScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Screen.AppearanceSettings.route) {
                    AppearanceSettingsScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Screen.Analytics.route) {
                    AnalyticsScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() },
                        voiceViewModel = voiceViewModel,
                        chatViewModel = chatViewModel,
                    )
                }
                composable(Screen.DeveloperSettings.route) {
                    DeveloperSettingsScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() },
                        onNavigateToRealtimeVoice = {
                            navController.navigate(Screen.RealtimeVoiceTest.route)
                        },
                    )
                }
                composable(Screen.RealtimeVoiceTest.route) {
                    RealtimeVoiceTestScreen(
                        voiceClient = voiceClient,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Screen.About.route) {
                    AboutScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = Screen.ProfileInspector.route,
                    arguments = listOf(
                        navArgument(Screen.ProfileInspector.ARG_PROFILE_NAME) {
                            type = NavType.StringType
                        },
                        // Optional `section` query arg — which tab to
                        // land on. Defaults to "config" so existing
                        // deep-links without the arg keep their
                        // pre-deep-link behaviour.
                        navArgument(Screen.ProfileInspector.ARG_SECTION) {
                            type = NavType.StringType
                            defaultValue = Screen.ProfileInspector.SECTION_CONFIG
                        },
                    ),
                ) { backStackEntry ->
                    // Build the VM with the shared inspector client and
                    // the nav-back-stack's SavedStateHandle. A small
                    // factory keeps the VM scoped to this destination —
                    // leaving the screen (popBackStack) destroys it, so
                    // entering a different profile later gets a fresh
                    // VM rather than reusing stale state.
                    //
                    // Keyed on the profile-name arg so navigating from
                    // profile A → profile B (unlikely in v1 but possible
                    // via deep link) yields a fresh VM rather than
                    // reusing the A VM with A's loaded state.
                    val profileNameArg = backStackEntry.arguments
                        ?.getString(Screen.ProfileInspector.ARG_PROFILE_NAME)
                        .orEmpty()
                    val sectionArg = backStackEntry.arguments
                        ?.getString(Screen.ProfileInspector.ARG_SECTION)
                        ?: Screen.ProfileInspector.SECTION_CONFIG
                    if (coldStartAuthState !is AuthState.Paired) {
                        PowerFeatureGateScreen(
                            title = "Profile Inspector",
                            summary = "Inspect relay-backed profile config, SOUL, memory files, and skills.",
                            status = PowerFeatureGateStatus.fromRelayAuth(coldStartAuthState),
                            onPrimaryAction = {
                                navController.navigate(Screen.Pair.route())
                            },
                            onBack = { navController.popBackStack() },
                        )
                        return@composable
                    }
                    val inspectorViewModel: ProfileInspectorViewModel = viewModel(
                        viewModelStoreOwner = backStackEntry,
                        key = "profile-inspector-$profileNameArg",
                        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : androidx.lifecycle.ViewModel> create(
                                modelClass: Class<T>,
                                extras: androidx.lifecycle.viewmodel.CreationExtras,
                            ): T {
                                // createSavedStateHandle() pulls the
                                // typed nav args out of extras — the
                                // backStackEntry is the SavedStateRegistry
                                // owner here, so the resulting
                                // SavedStateHandle contains our
                                // `profileName` arg automatically.
                                val ssh = extras.createSavedStateHandle()
                                return ProfileInspectorViewModel(
                                    client = profileInspectorClient,
                                    savedStateHandle = ssh,
                                ) as T
                            }
                        },
                    )

                    // Pull the model label off the current activeProfile
                    // for the top-bar subtitle — read-only snapshot,
                    // falls back to null when the selected profile
                    // doesn't happen to match the one we're inspecting
                    // (shouldn't normally happen since the entry is
                    // keyed off the same Profile).
                    val selectedProfile by connectionViewModel
                        .selectedProfile.collectAsState()
                    val modelLabel = selectedProfile
                        ?.takeIf { it.name == profileNameArg }
                        ?.model

                    ProfileInspectorScreen(
                        viewModel = inspectorViewModel,
                        profileModel = modelLabel,
                        initialSection = sectionArg,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            } // end bridge-return wrapper column
            } // end CompositionLocalProvider
        }
        } // end Column (wraps banner + Scaffold)

        // (The ConnectionSwitcherSheet modal that used to live here was
        // driven by the removed top-bar ConnectionChip. Switching is now
        // inline in AgentInfoSheet's Connection section — see
        // ConnectionInfoSheet.kt's "Multi-connection switcher" block.
        // ConnectionSwitcherSheet.kt itself is kept so any future programmatic
        // callers (deep links, automation) can still invoke it if needed.)

        // Startup connection gate. Keeps transient "connect" prompts hidden
        // until the first saved-connection health decision resolves.
        AnimatedVisibility(
            visible = showStartupSphere,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(600))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(RelayRefresh.Background)
                    .relayGridTexture(alpha = 0.14f),
                contentAlignment = Alignment.Center
            ) {
                // Sphere fills background
                MorphingSphere(modifier = Modifier.fillMaxSize())

                // Branding overlaid at bottom third
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 120.dp)
                ) {
                    Text(
                        text = "Hermes-Relay",
                        style = MaterialTheme.typography.headlineMedium,
                        color = RelayRefresh.Paper.copy(alpha = 0.92f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "agent interface",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RelayRefresh.Muted.copy(alpha = 0.72f),
                        letterSpacing = 2.sp
                    )
                }
            }
        }
        } // end Box
    }
}
