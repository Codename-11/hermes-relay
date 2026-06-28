package com.hermesandroid.relay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
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
import com.hermesandroid.relay.ui.components.CrashReportGate
import com.hermesandroid.relay.ui.components.DemoModeBanner
import com.hermesandroid.relay.ui.components.DemoUnavailableContent
import com.hermesandroid.relay.ui.components.MessageBannerHost
import com.hermesandroid.relay.ui.components.LocalAgentIconPath
import com.hermesandroid.relay.ui.components.LocalAvailableSphereSkins
import com.hermesandroid.relay.ui.components.LocalSphereSkin
import com.hermesandroid.relay.ui.components.SphereRegistry
import com.hermesandroid.relay.ui.components.SphereSkinLoader
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.components.avatar.AgentAvatar
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import com.hermesandroid.relay.ui.components.avatar.LocalAgentAvatar
import com.hermesandroid.relay.ui.components.avatar.LocalAvailableAvatars
import com.hermesandroid.relay.ui.components.avatar.LocalPetPlaybackSpeed
import com.hermesandroid.relay.ui.components.avatar.LocalPetStabilize
import com.hermesandroid.relay.ui.components.avatar.PetLoader
import com.hermesandroid.relay.ui.components.avatar.SphereAvatar
import com.hermesandroid.relay.ui.components.ConnectionStatusBanner
import com.hermesandroid.relay.ui.components.ConnectionStatusToast
import com.hermesandroid.relay.ui.components.ConnectionSwitcherSheet
import com.hermesandroid.relay.ui.components.ChatTransportStatusBadge
import com.hermesandroid.relay.ui.components.ChatTransportTier
import com.hermesandroid.relay.ui.components.ConnectionSecurityGlyph
import com.hermesandroid.relay.ui.components.PowerFeatureGateScreen
import com.hermesandroid.relay.ui.components.PowerFeatureGateStatus
import com.hermesandroid.relay.ui.components.RelayStatusStrip
import com.hermesandroid.relay.ui.components.UnattendedGlobalBanner
import com.hermesandroid.relay.ui.components.UpdateAvailableBanner
import com.hermesandroid.relay.ui.components.rememberUpdateAvailability
import com.hermesandroid.relay.ui.components.resolveChatTransportStatus
import com.hermesandroid.relay.ui.components.WhatsNewDialog
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.BridgePreferencesRepository
import com.hermesandroid.relay.data.BridgeSafetyPreferencesRepository
import com.hermesandroid.relay.data.BuildFlavor
import com.hermesandroid.relay.data.EnhancedVoiceOverrides
import com.hermesandroid.relay.data.VoiceAudioRoute
import com.hermesandroid.relay.data.VoicePreferencesRepository
import com.hermesandroid.relay.data.VoiceSettings
import com.hermesandroid.relay.data.displayLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.hermesandroid.relay.util.HumanError
import kotlinx.coroutines.delay
import com.hermesandroid.relay.ui.onboarding.OnboardingScreen
import com.hermesandroid.relay.ui.screens.AboutScreen
import com.hermesandroid.relay.ui.screens.AnalyticsScreen
import com.hermesandroid.relay.ui.screens.AppearanceSettingsScreen
import com.hermesandroid.relay.ui.screens.BridgeCoreScreen
import com.hermesandroid.relay.ui.screens.DiagnosticsScreen
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
import com.hermesandroid.relay.ui.screens.PermissionsStatusScreen
import com.hermesandroid.relay.ui.screens.ProfileInspectorScreen
import com.hermesandroid.relay.ui.screens.RealtimeVoiceTestScreen
import com.hermesandroid.relay.ui.screens.SettingsScreen
import com.hermesandroid.relay.ui.screens.TerminalScreen
import com.hermesandroid.relay.ui.screens.NotificationCompanionSettingsScreen
import com.hermesandroid.relay.ui.screens.VoiceSettingsScreen
import com.hermesandroid.relay.ui.screens.prewarmDashboardManage
import com.hermesandroid.relay.ui.theme.AppThemes
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.ui.theme.RelayRefresh
import com.hermesandroid.relay.ui.theme.relayGridTexture
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.network.relay.RelayProfileInspectorClient
import com.hermesandroid.relay.network.shared.AutoVoiceAudioClient
import com.hermesandroid.relay.network.upstream.DynamicDashboardCookieJar
import com.hermesandroid.relay.network.relay.RelayVoiceAudioClientAdapter
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionStatusTone
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.ProfileInspectorViewModel
import com.hermesandroid.relay.viewmodel.TerminalViewModel
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import com.hermesandroid.relay.audio.VoicePlayer
import com.hermesandroid.relay.audio.VoiceRecorder
import com.hermesandroid.relay.audio.VoiceSfxPlayer
import com.hermesandroid.relay.audio.RealtimePcmPlayer
import com.hermesandroid.relay.network.relay.RelayVoiceClient
import com.hermesandroid.relay.network.upstream.StandardHermesVoiceClient
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
    data object PermissionsSettings : Screen("settings/permissions", "Permissions", Icons.Filled.Settings)
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
    data object Diagnostics : Screen("settings/diagnostics", "Diagnostics", Icons.Filled.Settings)
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
    val profileSelectionSettled by connectionViewModel.profileSelectionSettled.collectAsState()
    val agentProfiles by connectionViewModel.agentProfiles.collectAsState()
    val profileDisplayAlias by connectionViewModel.profileDisplayAlias.collectAsState()
    val activeConnectionId by connectionViewModel.activeConnectionId.collectAsState()

    val mediaContext = androidx.compose.ui.platform.LocalContext.current
    val voicePreferences = remember(mediaContext) { VoicePreferencesRepository(mediaContext) }
    val voiceSettings by voicePreferences.settings.collectAsState(initial = VoiceSettings())
    val selectedAudioRoute = VoiceAudioRoute.fromStorage(voiceSettings.audioRoute)
    val selectedAudioRouteState = rememberUpdatedState(selectedAudioRoute)
    val standardVoiceReady by connectionViewModel.standardVoiceReady.collectAsState()
    val standardVoiceAvailability by connectionViewModel.standardVoiceAvailability.collectAsState()
    val relayVoiceReady by connectionViewModel.relayVoiceReady.collectAsState()
    val standardVoiceReadyState = rememberUpdatedState(standardVoiceReady)
    val relayVoiceReadyState = rememberUpdatedState(relayVoiceReady)
    // Latest enhanced-voice overrides (null when nothing is set). Read lazily by
    // the relay TTS adapter so changes apply without rebuilding it.
    val enhancedOverridesState = rememberUpdatedState(EnhancedVoiceOverrides.fromSettings(voiceSettings))

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
    // Standard voice talks to the dashboard web server (hermes-desktop's
    // /api/audio/* contract) and authenticates with the same per-connection
    // cookie session Manage signs in with. Dashboard URLs are connection-level
    // (no per-profile dashboard exists), so unlike chat this client does not
    // route through ProfileApiUrlResolver.
    val standardVoiceClient = remember {
        StandardHermesVoiceClient(
            context = mediaContext,
            okHttpClient = okhttp3.OkHttpClient.Builder()
                .cookieJar(
                    DynamicDashboardCookieJar {
                        connectionViewModel.activeDashboardCookieStore()
                    },
                )
                .readTimeout(2, java.util.concurrent.TimeUnit.MINUTES)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
            dashboardUrlProvider = { connectionViewModel.activeDashboardUrl() },
            // Live read (null for the default profile) — sent defensively on
            // /api/audio/speak; upstream ignores it, so standard voice stays the
            // host's global TTS. Same live source the relay voice client uses.
            profileProvider = {
                AgentDisplay.profileRequestName(connectionViewModel.selectedProfile.value?.name)
            },
        )
    }
    val voiceAudioClient = remember {
        AutoVoiceAudioClient(
            standardClient = standardVoiceClient,
            relayClient = RelayVoiceAudioClientAdapter(
                voiceClient,
                enhancedOverridesProvider = { enhancedOverridesState.value },
            ),
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
        // Mirror chat streaming state so a mid-turn route change defers its
        // client rebuild instead of cancelling the live turn (the gateway
        // socket rides the blip via its own reconnect).
        launch {
            chatViewModel.isStreaming.collect { connectionViewModel.setChatStreaming(it) }
        }
    }

    LaunchedEffect(chatApiClient) {
        val client = chatApiClient ?: return@LaunchedEffect
        val handler = connectionViewModel.chatHandler
        // A route handoff / reconnect rebuilds the API client (new instance)
        // while the chat is unchanged — the bound handler is the same. Take the
        // cheap path: swap the client reference only, no re-init. This is what
        // keeps the chat surface from repainting/reloading on a LAN↔Tailscale
        // switch or a reconnect. A genuine re-bind (different handler) falls
        // through to the full one-time wiring below.
        if (chatViewModel.boundHandler === handler) {
            chatViewModel.updateApiClient(client)
            return@LaunchedEffect
        }

        chatViewModel.initialize(client, handler)

        // Wire inbound-media dependencies. Idempotent rewire of the
        // ChatHandler callbacks.
        chatViewModel.initializeMedia(
            context = mediaContext,
            relayHttpClient = connectionViewModel.relayHttpClient,
            mediaSettingsRepo = connectionViewModel.mediaSettingsRepo,
            mediaCacheWriter = connectionViewModel.mediaCacheWriter
        )

        // Agent-profile pick provider (Pass 2). Lambda reads the latest
        // StateFlow value on every send, so ChatViewModel never needs a
        // direct reference to ConnectionViewModel. The lambda captures the
        // long-lived VM, not the (per-connection) apiClient.
        chatViewModel.setSelectedProfileProvider {
            connectionViewModel.selectedProfile.value
        }
        chatViewModel.setEffectiveProfileProvider {
            AgentDisplay.effectiveProfile(
                selectedProfile = connectionViewModel.selectedProfile.value,
                profiles = connectionViewModel.agentProfiles.value,
            )
        }
        chatViewModel.setDisplayProfileProvider {
            AgentDisplay.effectiveDisplayProfile(
                selectedProfile = connectionViewModel.selectedProfile.value,
                profiles = connectionViewModel.agentProfiles.value,
            )
        }
        chatViewModel.setDisplayAliasProvider {
            connectionViewModel.profileDisplayAlias.value
        }

        // Drawer session list, scoped to the active profile on gateway
        // connections (dashboard `/api/sessions?profile=`). Returns null off the
        // dashboard surface so refreshSessions() falls back to the shared list.
        chatViewModel.setProfileSessionLister {
            connectionViewModel.listProfileScopedSessions()
        }
        // …and load a tapped session's transcript from that same profile's DB.
        chatViewModel.setProfileMessageLoader { sessionId ->
            connectionViewModel.loadProfileScopedMessages(sessionId)
        }
        // …and delete from that same profile's DB so a non-default profile's
        // session can't be resurrected by the next profile-scoped list.
        chatViewModel.profileSessionDeleter = { sessionId ->
            connectionViewModel.deleteProfileScopedSession(sessionId)
        }
        // …and rename in that same profile's DB so a non-default profile's
        // title actually persists (the unscoped api_server PATCH hits the
        // shared DB). Write twin of the scoped list/delete.
        chatViewModel.profileSessionRenamer = { sessionId, title ->
            connectionViewModel.renameProfileScopedSession(sessionId, title)
        }

        // Wire session persistence callback
        chatViewModel.onSessionChanged = { sessionId ->
            connectionViewModel.saveLastSessionId(sessionId)
        }
    }

    // Reload sessions / switch profile context only on a SEMANTIC change
    // (connection, profile, or restored session) — NOT on every API-client
    // instance swap. Keying on a readiness flag instead of the client instance
    // means a route handoff (which churns the client) no longer triggers a
    // refreshSessions() that would flash/reload the chat. `switchProfileContext`
    // already no-ops when the context key + session are unchanged.
    val chatClientReady = chatApiClient != null
    LaunchedEffect(chatClientReady, activeConnectionId, selectedProfile?.name, lastSessionId, profileSelectionSettled) {
        if (!chatClientReady) return@LaunchedEffect
        // Cold-start profile-isolation guard: hold the first profile-scoped load
        // until the persisted profile selection has SETTLED, so the session
        // drawer (and the restored session context) don't briefly load the
        // SERVER-DEFAULT profile and then visibly snap to the real one. While a
        // non-default profile is still resolving we wait on a backstop instead of
        // fetching now; this effect re-fires the instant the profile resolves
        // (selectedProfile / profileSelectionSettled change), cancelling the wait
        // so only the correct, profile-scoped load lands. The backstop guarantees
        // the drawer is never permanently empty if the profile list never lands.
        if (!profileSelectionSettled) {
            delay(2_500L)
        } else {
            // Coalesce the rapid lastSessionId null→value churn a profile switch
            // produces: selectProfile() nulls lastSessionId, then the persisted
            // per-profile session resolves a tick later. This effect re-fires on
            // that change, cancelling the delay below before it commits — so we
            // skip painting the intermediate empty draft and land straight on the
            // resolved session (or a genuine fresh draft when the profile has no
            // history).
            delay(160)
        }
        chatViewModel.switchProfileContext(
            contextKey = AgentDisplay.profileContextKey(
                connectionId = activeConnectionId,
                profileName = selectedProfile?.name,
            ),
            sessionId = lastSessionId,
        )
        chatViewModel.refreshSessions()
    }

    LaunchedEffect(activeConnectionId, selectedProfile?.name) {
        // WP-V2: namespace per-profile voice prefs by BOTH the active connection
        // and the profile so two connections exposing a same-named profile don't
        // collide. Set the connection id first so onProfileChanged re-seeds from
        // the correctly-scoped keys.
        voiceViewModel.setVoicePrefsConnection(activeConnectionId)
        voiceViewModel.onProfileChanged(
            AgentDisplay.profileRequestName(selectedProfile?.name)
        )
    }

    LaunchedEffect(selectedProfile?.name, agentProfiles, profileDisplayAlias) {
        chatViewModel.refreshAgentDisplayName(relabelGenericMessages = true)
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

    // Mirror the "Notify when Hermes finishes" setting into ChatViewModel —
    // same pattern as appContextSettings; the VM reads the plain field at
    // turn-complete time instead of holding a ConnectionViewModel reference.
    val notifyTurnComplete by connectionViewModel.notifyTurnComplete.collectAsState()
    LaunchedEffect(notifyTurnComplete) {
        chatViewModel.notifyOnTurnComplete = notifyTurnComplete
    }

    // Sync tool annotation parsing toggle to ChatHandler
    val parseAnnotations by connectionViewModel.parseToolAnnotations.collectAsState()
    LaunchedEffect(parseAnnotations) {
        connectionViewModel.chatHandler.parseToolAnnotations = parseAnnotations
    }

    // Sync "show system messages" debug toggle to ChatHandler
    val showSystemMessages by connectionViewModel.showSystemMessages.collectAsState()
    LaunchedEffect(showSystemMessages) {
        connectionViewModel.chatHandler.showSystemMarkers = showSystemMessages
    }

    // Sync streaming endpoint preference to chat. Resolves "auto" against the
    // current server capabilities so vanilla upstream + bootstrap-injected
    // sessions API picks /v1/chat/completions for portable SSE chat while
    // still using /api/sessions/* for browse/rename/delete. Gateway
    // availability is a key so a Manage sign-in mid-session re-resolves
    // "auto" to the gateway transport (live thinking) without an app restart.
    val streamingEndpoint by connectionViewModel.streamingEndpoint.collectAsState()
    val serverCapabilities by connectionViewModel.serverCapabilities.collectAsState()
    val gatewayAvailability by connectionViewModel.gatewayAvailability.collectAsState()
    // The resolved API route is a key so a mid-turn route switch (LAN→Tailscale)
    // re-runs activeGatewayChatClient(), which RETARGETS the in-flight gateway
    // client to follow the new dashboard route instead of stranding the turn on
    // the dead one.
    val effectiveApiUrl by connectionViewModel.effectiveApiServerUrl.collectAsState()
    LaunchedEffect(streamingEndpoint, serverCapabilities, gatewayAvailability, effectiveApiUrl) {
        val resolved = connectionViewModel.resolveStreamingEndpoint(streamingEndpoint)
        chatViewModel.streamingEndpoint = resolved
        chatViewModel.sseFallbackEndpoint = connectionViewModel.resolveSseStreamingEndpoint()
        chatViewModel.updateGatewayClient(
            if (resolved == "gateway") connectionViewModel.activeGatewayChatClient() else null,
        )
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
    val appThemeId by connectionViewModel.appTheme.collectAsState()
    val fontScale by connectionViewModel.fontScale.collectAsState()
    val appFontId by connectionViewModel.appFont.collectAsState()

    // Resolve the active sphere skin (built-in / adaptive / user-loaded) and
    // publish it + the full available set so every MorphingSphere picks it up
    // via LocalSphereSkin without per-call-site threading. Adaptive skins read
    // the brand lazily inside MorphingSphere, so this can sit outside the theme.
    val sphereSkinId by connectionViewModel.sphereSkin.collectAsState()
    val sphereContext = androidx.compose.ui.platform.LocalContext.current
    val availableSphereSkins by produceState(
        initialValue = SphereRegistry.builtIns,
        key1 = sphereContext,
    ) {
        value = SphereRegistry.builtIns +
            withContext(Dispatchers.IO) { SphereSkinLoader.loadUserSkins(sphereContext) }
    }
    val activeSphereSkin = remember(sphereSkinId, appThemeId, availableSphereSkins) {
        SphereRegistry.resolve(
            selectedId = sphereSkinId,
            themeDefaultSkinId = AppThemes.byId(appThemeId).defaultSphereSkinId,
            available = availableSphereSkins,
        )
    }

    // Agent avatar seam (P2/P3): the built-in sphere plus any user-loaded "pets"
    // (P3). The sphere nests the skin system one level below (avatar → skin).
    // Published beside the skin locals so every avatar call site resolves it via
    // LocalAgentAvatar without per-call-site threading. An unknown selected id
    // (e.g. a pet pack was removed) falls back to the sphere.
    val agentAvatarId by connectionViewModel.agentAvatar.collectAsState()
    // Re-scans the pets/ dir whenever the tick bumps (in-app import/delete, or the
    // Appearance screen opening), so newly added/removed pets appear everywhere
    // without an app restart.
    val avatarsRefreshTick by connectionViewModel.avatarsRefreshTick.collectAsState()
    val availableAgentAvatars by produceState(
        initialValue = listOf<AgentAvatar>(SphereAvatar),
        key1 = sphereContext,
        key2 = avatarsRefreshTick,
    ) {
        value = listOf<AgentAvatar>(SphereAvatar) +
            withContext(Dispatchers.IO) { PetLoader.loadPets(sphereContext) }
    }
    val activeAgentAvatar = remember(agentAvatarId, availableAgentAvatars) {
        availableAgentAvatars.firstOrNull { it.id == agentAvatarId } ?: SphereAvatar
    }
    val petSpeed by connectionViewModel.petSpeed.collectAsState()
    val petStabilize by connectionViewModel.petStabilize.collectAsState()
    val agentIconPath by connectionViewModel.profileIcon.collectAsState()

    CompositionLocalProvider(
        LocalSphereSkin provides activeSphereSkin,
        LocalAvailableSphereSkins provides availableSphereSkins,
        LocalAgentAvatar provides activeAgentAvatar,
        LocalAvailableAvatars provides availableAgentAvatars,
        LocalPetPlaybackSpeed provides petSpeed,
        LocalPetStabilize provides petStabilize,
        LocalAgentIconPath provides agentIconPath,
    ) {
    HermesRelayTheme(
        appThemeId = appThemeId,
        themePreference = themePreference,
        fontScale = fontScale,
        appFontId = appFontId,
    ) {
        // Surface a crash report from a previous session, if any. Renders a
        // platform Dialog (own window) so tree position is z-order-agnostic;
        // it just needs to be inside the theme for Material colors.
        CrashReportGate()

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

        // Offline Demo / Explore mode. Treated like "onboarding complete" for
        // CHROME purposes (so the demo Chat shows the normal scaffold + status
        // strip and the user can move around) WITHOUT actually completing
        // onboarding — exiting demo returns to the real Connect flow. The demo
        // is entered by navigating to Chat on top of Onboarding, so a process
        // restart cleanly lands back in setup.
        val isDemoMode by connectionViewModel.isDemoMode.collectAsState()

        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route
        val isOnboarding = currentRoute == Screen.Onboarding.route
        val suppressGlobalChrome = (!onboardingCompleted && !isDemoMode) || isOnboarding

        // Safety net: landing on a real connect surface (onboarding or the
        // Connect/Pair wizard) while demo is still active — via the banner's
        // Connect action OR a system-back out of the demo Chat — drops demo so
        // the offline network guards don't block the real connection the user
        // is now setting up.
        LaunchedEffect(currentRoute, isDemoMode) {
            if (isDemoMode &&
                (currentRoute == Screen.Onboarding.route || currentRoute == Screen.Pair.route)
            ) {
                connectionViewModel.exitDemoMode()
            }
        }
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
        val connectionSecurity by connectionViewModel.connectionSecurity.collectAsState()
        val serverModelName by chatViewModel.serverModelName.collectAsState()
        val gatewayCurrentModel by chatViewModel.gatewayCurrentModel.collectAsState()
        val appReady by connectionViewModel.isReady.collectAsState()
        val initialChatSettled by chatViewModel.initialChatSettled.collectAsState()
        // The SAME readiness signal ChatScreen renders its "Connect Standard
        // Hermes" CTA from (chat client exists + reachable verdict). The gate
        // must release on this — releasing on the resolver's earlier
        // evidence alone left a window where the reveal showed the CTA for
        // the few hundred ms until the client-based health verdict landed.
        val chatReady by connectionViewModel.chatReady.collectAsState()
        var startupGateMinElapsed by remember { mutableStateOf(false) }
        var startupGateTimedOut by remember { mutableStateOf(false) }
        var startupGateReleased by remember { mutableStateOf(false) }
        var startupUnreachableSettled by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            delay(650L)
            startupGateMinElapsed = true
        }
        // Backstop only. The old 5.5s value force-hid the sphere
        // (showStartupSphere checked !timedOut) while startup was genuinely
        // still working, dumping users into half-hydrated UI — a "connect"
        // CTA they were never meant to see, then the connected state, then
        // the conversation, one reveal at a time. Now the timeout RELEASES
        // the gate like any other condition and the sphere narrates
        // progress, so a longer ceiling is watchable instead of broken.
        LaunchedEffect(Unit) {
            delay(12_000L)
            startupGateTimedOut = true
        }

        val hasStartupConnection = activeConnection?.apiServerUrl?.isNotBlank() == true
        // A published activeEndpoint counts as "hermes online": the route
        // resolver only publishes a winner after a successful HEAD /health
        // probe against that route's API URL. At cold start this evidence
        // lands within ~1s — long before the client-based health probe,
        // which can't run until the API client exists (the client build
        // used to queue behind the Keystore decrypt; see
        // apiKeyForClientBuild in ConnectionViewModel).
        val startupApiUp = apiReachable ||
            apiHealth == ConnectionViewModel.HealthStatus.Reachable ||
            activeEndpoint != null

        // An Unreachable verdict only counts after it SURVIVES a settle
        // window: the first health probe often runs against the persisted
        // (e.g. LAN) URL moments before the route resolver lands on
        // Tailscale and the client is rebuilt. Releasing the gate on that
        // first verdict was what flashed the disconnected chat UI at users
        // who were connected-just-waiting. The keyed effect restarts on
        // every health flip, cancelling a pending settle.
        LaunchedEffect(apiHealth, startupGateReleased) {
            if (startupGateReleased) return@LaunchedEffect
            if (apiHealth == ConnectionViewModel.HealthStatus.Unreachable) {
                delay(3_000L)
                startupUnreachableSettled = true
            } else {
                startupUnreachableSettled = false
            }
        }

        // ---- Startup narration: real states the checklist verifies ----
        val startupEndpoint = activeEndpoint
        val startupCheckTargets = if (!hasStartupConnection) {
            emptyList()
        } else {
            listOf(
                if (appReady) {
                    StartupCheck(StartupCheckState.Done, "state restored")
                } else {
                    StartupCheck(StartupCheckState.Active, "restoring state")
                },
                when {
                    startupEndpoint != null -> StartupCheck(
                        StartupCheckState.Done,
                        "route · ${startupEndpoint.displayLabel()}",
                    )
                    startupApiUp ->
                        StartupCheck(StartupCheckState.Done, "route · direct")
                    appReady ->
                        StartupCheck(StartupCheckState.Active, "resolving route")
                    else -> StartupCheck(StartupCheckState.Pending, "route")
                },
                when {
                    startupApiUp ->
                        StartupCheck(StartupCheckState.Done, "hermes online")
                    apiHealth == ConnectionViewModel.HealthStatus.Unreachable ->
                        StartupCheck(StartupCheckState.Failed, "hermes unreachable")
                    appReady ->
                        StartupCheck(StartupCheckState.Active, "contacting hermes")
                    else -> StartupCheck(StartupCheckState.Pending, "hermes")
                },
                // Done is keyed on chatReady — the signal ChatScreen itself
                // renders from — so this row can never tick while the chat
                // surface would still show its connect CTA.
                when {
                    chatReady && initialChatSettled ->
                        StartupCheck(StartupCheckState.Done, "conversation ready")
                    startupApiUp ->
                        StartupCheck(StartupCheckState.Active, "loading conversation")
                    else -> StartupCheck(StartupCheckState.Pending, "conversation")
                },
            )
        }

        // ---- Narration choreography ----
        // With the key-less fast path every readiness signal can be
        // satisfied before the sphere finishes fading in — an all-✓-at-once
        // reveal reads as "nothing was actually checked". So rows resolve
        // strictly top-to-bottom and each one holds a spinner beat before
        // its verdict lands, even when the underlying state was already
        // true. The gate's happy path waits for the narration to finish;
        // error and timeout releases don't.
        var startupNarrationStage by remember { mutableStateOf(0) }
        LaunchedEffect(startupCheckTargets, startupNarrationStage, startupGateReleased) {
            if (startupGateReleased) return@LaunchedEffect
            if (startupNarrationStage >= startupCheckTargets.size) return@LaunchedEffect
            val target = startupCheckTargets[startupNarrationStage].state
            if (target == StartupCheckState.Done || target == StartupCheckState.Failed) {
                delay(350L)
                startupNarrationStage += 1
            }
        }
        val startupNarrationComplete =
            startupNarrationStage >= startupCheckTargets.size

        val startupConnectionResolved = appReady && (
            !hasStartupConnection ||
                // Happy path: the chat surface's OWN readiness signal is
                // true (client built + reachable verdict — what its connect
                // CTA renders from), the last conversation has been restored
                // (or there was none), and the checklist has visibly
                // finished ticking. Anything weaker (e.g. the resolver's
                // earlier health evidence) reveals a chat screen that still
                // shows "Connect Vanilla Hermes" for the few hundred ms
                // until the client-based verdict catches up.
                (chatReady && initialChatSettled && startupNarrationComplete) ||
                // Error path: a settled unreachable reveals the normal UI,
                // which owns offline presentation (status pill, retry).
                startupUnreachableSettled ||
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
                // When the 12s backstop (not readiness, not a settled error)
                // is what opened the gate, leave a diagnostic naming the
                // conditions still unmet — the demo-video session measured
                // 6–28s launch variance against the same LAN server and had
                // no way to see why from the device.
                val happyPathReady =
                    chatReady && initialChatSettled && startupNarrationComplete
                if (
                    hasStartupConnection &&
                    !happyPathReady &&
                    !startupUnreachableSettled &&
                    startupGateTimedOut
                ) {
                    DiagnosticsLog.record(
                        category = DiagnosticCategory.Api,
                        severity = DiagnosticSeverity.Warning,
                        title = "Startup gate released by timeout",
                        detail = "chatReady=$chatReady " +
                            "historySettled=$initialChatSettled " +
                            "narration=$startupNarrationStage/${startupCheckTargets.size} " +
                            "health=$apiHealth " +
                            "route=${activeEndpoint?.role ?: "unresolved"}",
                    )
                }
                startupGateReleased = true
            }
        }
        val showStartupSphere =
            !suppressGlobalChrome &&
                !startupGateReleased &&
                !voiceUiState.voiceMode &&
                // Demo mode skips the startup connect-narration sphere entirely
                // — there's no server to contact, so the canned chat shows
                // immediately.
                !isDemoMode

        // Hydrate the Manage payload cache from its plain-JSON disk mirror
        // as early as possible — independent of connectivity or auth, so a
        // cold process renders last-seen dashboard data instantly. Entries
        // keep their original fetch timestamps, making them stale by
        // definition: the pre-warm below and the screen's
        // stale-while-revalidate path refresh them quietly.
        val hydrateContext =
            androidx.compose.ui.platform.LocalContext.current.applicationContext
        LaunchedEffect(Unit) {
            com.hermesandroid.relay.ui.screens.hydrateDashboardManageCache(
                hydrateContext.cacheDir,
            )
        }

        // Pre-warm the Manage tab's payload cache when the persisted
        // dashboard snapshot says this connection was reachable and signed
        // in (or auth-free) — a cold app start then lands on populated
        // Manage data instead of skeletons. Keyed on the effective URL so a
        // LAN↔Tailscale handoff re-warms the new host's cache; the delay
        // debounces resolver flaps during startup (each key change cancels
        // the previous run). The pre-warm fills cold keys and refreshes
        // stale (disk-hydrated) ones, then mirrors results back to disk.
        val effectiveDashboardUrl by connectionViewModel.effectiveDashboardUrl.collectAsState()
        LaunchedEffect(activeConnection?.id, effectiveDashboardUrl) {
            val connection = activeConnection ?: return@LaunchedEffect
            if (effectiveDashboardUrl.isBlank()) return@LaunchedEffect
            val snapshot = connection.dashboardLastStatus ?: return@LaunchedEffect
            val dashboardUsable = snapshot.reachable &&
                (snapshot.authRequired == false || snapshot.authenticated == true)
            if (!dashboardUsable) return@LaunchedEffect
            delay(1_500L)
            // The VM's cached per-connection store — the prewarm must NOT
            // construct its own (each instance lazily pays a multi-second
            // Keystore keyset build under a process-global Tink lock).
            val cookieStore = connectionViewModel.activeDashboardCookieStore()
                ?: return@LaunchedEffect
            prewarmDashboardManage(
                cookieStore = cookieStore,
                connectionId = connection.id,
                dashboardUrl = effectiveDashboardUrl,
                cacheDir = hydrateContext.cacheDir,
            )
        }

        // Single snackbar host for the whole app — exposed via LocalSnackbarHost
        // so voice/chat/settings screens can call showHumanError from their
        // error-collector LaunchedEffects without threading state downwards.
        val snackbarHostState = remember { SnackbarHostState() }

        // Relay-pushed `profiles.updated` announcements. AuthManager
        // filters out idempotent pushes (same names + same count), so
        // this only fires when the profile list actually changed.
        LaunchedEffect(connectionViewModel) {
            connectionViewModel.profilesUpdatedEvents.collect {
                UiMessageBus.success("Profiles updated")
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
            !suppressGlobalChrome &&
            !showStartupSphere &&
            !voiceUiState.voiceMode
        // Persistent Demo-mode strip — visible on every demo surface so the
        // user always knows the chat is sample data with no live server, and
        // can exit into the real Connect flow with one tap.
        val showDemoBanner = isDemoMode && !voiceUiState.voiceMode
        // Transient info/status banner (UiMessageBus) — thin, takes its own
        // space, auto-dismisses. Folded into the inset accounting below so a
        // child TopAppBar doesn't double-pad when this banner owns the top edge.
        val activeMessageCount by UiMessageBus.activeCount.collectAsState()
        val showMessageBanner = activeMessageCount > 0
        // Update availability (unified): googlePlay = Play In-App Update FLEXIBLE,
        // sideload = GitHub releases. The handle filters dismissed versions +
        // throttles checks internally, exposing a surfaceable status for the
        // floating overlay (mirrors the connection toast treatment).
        val updateHandle = rememberUpdateAvailability()
        val availableUpdateStatus by updateHandle.visibleStatus

        // Content-identity key so a swipe-up dismiss sticks for THIS status but
        // a genuinely new status (different title/tone/phase) re-shows.
        var dismissedStatusKey by remember { mutableStateOf<String?>(null) }
        val currentStatusKey = globalConnectionStatus?.let {
            "${it.title}|${it.tone}|${it.active}|${it.success}|${it.route}"
        }
        val showConnectionStatusToast =
            globalConnectionStatus != null &&
                currentStatusKey != dismissedStatusKey &&
                !suppressGlobalChrome &&
                !showStartupSphere &&
                !voiceUiState.voiceMode
        // Split the connection-status surface by severity (user request): the
        // frequent transient/active/warning states render as a take-space top
        // BANNER (content slides down, no overlay), while a persistent ERROR
        // keeps the floating overlay so it still demands attention. Steady
        // state is null (buildGlobalConnectionStatus → else null), so the
        // banner only occupies space during a transition/problem.
        val connectionStatusIsError =
            globalConnectionStatus?.tone == ConnectionStatusTone.Error
        val showConnectionStatusBanner = showConnectionStatusToast && !connectionStatusIsError
        val showConnectionStatusOverlay = showConnectionStatusToast && connectionStatusIsError
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

        // --- Offline Demo mode navigation ---------------------------------
        // Enter: load the canned transcript + bind it to the chat VM (no
        // network), then land on Chat WITHOUT completing onboarding. Binding
        // synchronously before navigating means ChatScreen's first composition
        // already sees the demo messages. Exit: clear demo + return to the
        // real Connect flow (onboarding for a fresh install, the Pair wizard
        // for an already-set-up app).
        val enterDemo: () -> Unit = {
            connectionViewModel.enterDemoMode()
            chatViewModel.bindDemoHandler(connectionViewModel.chatHandler)
            navController.navigate(Screen.Chat.route(openAgentSheet = false)) {
                launchSingleTop = true
            }
        }
        val exitDemoToConnect: () -> Unit = {
            connectionViewModel.exitDemoMode()
            if (onboardingCompleted) {
                navController.navigate(Screen.Pair.route()) { launchSingleTop = true }
            } else {
                navController.navigate(Screen.Onboarding.route) {
                    popUpTo(Screen.Chat.route) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }

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

        AnimatedVisibility(
            visible = showDemoBanner,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            DemoModeBanner(onConnect = exitDemoToConnect)
        }

        // Connection status as a take-space banner (non-error). Replaces the
        // floating ConnectionStatusToast for the frequent transient/active/
        // warning states so content slides down instead of being covered.
        AnimatedVisibility(
            visible = showConnectionStatusBanner,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            ConnectionStatusBanner(
                status = globalConnectionStatus,
                includeStatusBarPadding = !showUnattendedBanner && !showDemoBanner,
                onClick = onConnectionStatusBannerClick,
            )
        }

        // Transient info/status banner. Sits below the persistent banners and
        // owns the status-bar inset only when no banner is above it (otherwise
        // that banner already padded the top — avoid double padding).
        MessageBannerHost(
            includeStatusBarPadding =
                !showUnattendedBanner && !showDemoBanner && !showConnectionStatusBanner,
        )

        // The update banner AND the connection-status indicator now render as
        // floating overlay TOASTS in the Box below (see the top-overlay Column
        // after the Scaffold), so they slide down OVER the content instead of
        // taking layout space — no UI resize/cut on update / handoff / reconnect.

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
                    // The connection-status toast is now a floating overlay and
                    // doesn't occupy space above the Scaffold, so it no longer
                    // participates in the top-inset accounting.
                    if (showUnattendedBanner || showDemoBanner || connectionChipVisible ||
                        showMessageBanner || showConnectionStatusBanner
                    ) {
                        Modifier.consumeWindowInsets(WindowInsets.statusBars)
                    } else {
                        Modifier
                    }
                ),
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!suppressGlobalChrome && !isKeyboardVisible && !showStartupSphere && !voiceUiState.voiceMode) {
                    val routeLabel = activeEndpoint?.displayLabel()
                        ?: activeConnection?.label
                        ?: "no route"
                    val transportStatus = resolveChatTransportStatus(
                        streamingEndpoint = streamingEndpoint,
                        gatewayAvailability = gatewayAvailability,
                        serverCapabilities = serverCapabilities,
                    )
                    val transportRouteLabel = if (transportStatus.tier == ChatTransportTier.Offline) {
                        ""
                    } else {
                        routeLabel
                    }
                    val profileLabel = selectedProfile?.name?.takeIf { it.isNotBlank() } ?: "default"
                    val displayProfile = AgentDisplay.effectiveDisplayProfile(
                        selectedProfile = selectedProfile,
                        profiles = agentProfiles,
                    )
                    val modelLabel = AgentDisplay.displayModelName(gatewayCurrentModel)
                        ?: AgentDisplay.displayModelName(displayProfile?.model)
                        ?: AgentDisplay.displayModelName(serverModelName)
                        ?: "model pending"
                    val safetyLabel = if (BuildFlavor.isSideload && masterEnabled) {
                        "safety: ${if (unattendedEnabled) "unattended" else "on"}"
                    } else {
                        "profile: $profileLabel"
                    }
                    val openConnections = {
                        navController.navigate(Screen.ConnectionsSettings.route) {
                            launchSingleTop = true
                        }
                    }
                    RelayStatusStrip(
                        leadingBadge = {
                            ChatTransportStatusBadge(
                                status = transportStatus,
                                onClick = openConnections,
                            )
                        },
                        routeLabel = transportRouteLabel,
                        trailing = "$modelLabel / $safetyLabel",
                        // Tap the persistent status/route readout to open
                        // Connections — preserves the affordance the dropped
                        // header endpoint chip used to provide.
                        onClick = openConnections,
                        securityGlyph = if (transportStatus.tier != ChatTransportTier.Offline) {
                            { ConnectionSecurityGlyph(connectionSecurity) }
                        } else {
                            null
                        },
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
                        },
                        onOpenPermissions = {
                            navController.navigate(Screen.PermissionsSettings.route)
                        },
                        onTryDemo = enterDemo,
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
                    // Responsive bubble width based on screen width. The "Blend"
                    // chat look favors wider bubbles: on compact phones the cap is
                    // raised so long turns fill most of the row (binding on the
                    // available width minus the assistant avatar gutter) instead
                    // of wrapping early in a narrow column.
                    val configuration = LocalConfiguration.current
                    val screenWidthDp = configuration.screenWidthDp.dp
                    val maxBubbleWidth = when {
                        screenWidthDp >= 840.dp -> 640.dp  // Expanded (tablet)
                        screenWidthDp >= 600.dp -> 520.dp  // Medium (landscape / small tablet)
                        else -> 340.dp                      // Compact (phone portrait)
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
                        // Empty-chat "needs connection" card also offers the offline
                        // demo, so a skipped / never-connected first run can explore
                        // without leaving Chat. Safe here — this state only shows when
                        // nothing is configured, so there's no placeholder in flight.
                        onTryDemo = enterDemo,
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
                        onNavigateToVoiceSettings = {
                            navController.navigate(Screen.VoiceSettings.route) {
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
                    if (isDemoMode) {
                        // Demo is offline — Manage talks to the live dashboard,
                        // so show a friendly demo empty state instead of
                        // attempting a sign-in / fetch.
                        DemoUnavailableContent(
                            feature = "Manage",
                            onConnect = exitDemoToConnect,
                        )
                    } else {
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
                }
                composable(Screen.Terminal.route) {
                    if (coldStartAuthState is AuthState.Paired) {
                        TerminalScreen(
                            terminalViewModel = terminalViewModel,
                            connectionViewModel = connectionViewModel,
                            onBack = { navController.popBackStack() },
                        )
                    } else {
                        PowerFeatureGateScreen(
                            title = "Terminal",
                            summary = "Open a server shell through your paired relay session.",
                            status = PowerFeatureGateStatus.fromRelayAuth(coldStartAuthState),
                            onPrimaryAction = {
                                navController.navigate(Screen.Pair.route())
                            },
                            onBack = { navController.popBackStack() },
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
                        onBack = { navController.popBackStack() },
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
                        onNavigateToDiagnostics = {
                            navController.navigate(Screen.Diagnostics.route)
                        },
                        onNavigateToVoiceSettings = {
                            navController.navigate(Screen.VoiceSettings.route)
                        },
                        onNavigateToNotificationCompanion = {
                            navController.navigate(Screen.NotificationCompanionSettings.route)
                        },
                        onNavigateToPermissions = {
                            navController.navigate(Screen.PermissionsSettings.route)
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
                    if (isDemoMode) {
                        // Voice runs through the live server (transcribe /
                        // synthesize) — show the demo empty state offline.
                        DemoUnavailableContent(
                            feature = "Voice",
                            onConnect = exitDemoToConnect,
                        )
                    } else {
                    val standardVoiceSignInRouteHint by
                        connectionViewModel.standardVoiceSignInRouteHint.collectAsState()
                    VoiceSettingsScreen(
                        voiceViewModel = voiceViewModel,
                        voiceClient = voiceClient,
                        connectionId = activeConnectionId,
                        selectedProfile = selectedProfile,
                        standardVoiceAvailability = standardVoiceAvailability,
                        standardVoiceSignInRouteHint = standardVoiceSignInRouteHint,
                        relayVoiceReady = relayVoiceReady,
                        onOpenManage = {
                            navController.navigate(Screen.Manage.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onBack = { navController.popBackStack() }
                    )
                    }
                }
                // === PHASE3-notif-listener-followup: notification companion route ===
                composable(Screen.NotificationCompanionSettings.route) {
                    NotificationCompanionSettingsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
                // === END PHASE3-notif-listener-followup ===
                composable(Screen.PermissionsSettings.route) {
                    PermissionsStatusScreen(
                        onBack = { navController.popBackStack() },
                        onOpenBridge = {
                            navController.navigate(Screen.Bridge.route) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
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
                            UiMessageBus.status("Reconnecting to relay…")
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
                        // Offer demo only on the bare "Connect" entry (the
                        // "No Hermes connection" path) — not on add-connection /
                        // re-pair flows, which have a placeholder connection in
                        // flight that enterDemo would leave un-discarded.
                        onTryDemo = if (connectionIdArg == null) enterDemo else null,
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
                composable(Screen.Diagnostics.route) {
                    DiagnosticsScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() },
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

        // Floating overlay toasts (update + connection status). Rendered in the
        // Box, stacked top-down in one status-bar-padded Column so they slide
        // down OVER the content without resizing it — no UI cut/resize on update
        // / handoff / reconnect. Both gated off during onboarding / startup
        // sphere / voice mode. The Column self-pads the status bar once; the
        // children don't (so two stacked toasts don't double-pad).
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            AnimatedVisibility(
                visible = availableUpdateStatus != null && !suppressGlobalChrome &&
                    !showStartupSphere && !voiceUiState.voiceMode,
                enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(160)),
            ) {
                availableUpdateStatus?.let { status ->
                    UpdateAvailableBanner(
                        status = status,
                        onUpdate = updateHandle.onUpdateClick,
                        onDismiss = updateHandle.onDismiss,
                        includeStatusBarPadding = false,
                    )
                }
            }
            AnimatedVisibility(
                visible = showConnectionStatusOverlay,
                enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(200)) { -it } + fadeOut(tween(160)),
            ) {
                ConnectionStatusToast(
                    status = globalConnectionStatus,
                    includeStatusBarPadding = false,
                    onClick = onConnectionStatusBannerClick,
                    onDismiss = { dismissedStatusKey = currentStatusKey },
                )
            }
        }

        // (The ConnectionSwitcherSheet modal that used to live here was
        // driven by the removed top-bar ConnectionChip. Switching is now
        // inline in AgentInfoSheet's Connection section — see
        // ConnectionInfoSheet.kt's "Multi-connection switcher" block.
        // ConnectionSwitcherSheet.kt itself is kept so any future programmatic
        // callers (deep links, automation) can still invoke it if needed.)

        // Startup connection gate. The sphere is the loading screen: it
        // holds until the app is actually presentable (connected + last
        // conversation restored, or a settled error, or the backstop
        // timeout) and narrates progress as terminal-style check lines so
        // a longer wait reads as work, not a hang.
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
                // Avatar fills background (sphere by default; routed through the
                // seam so a future pet appears on the startup screen too).
                LocalAgentAvatar.current.Render(
                    state = AvatarRenderState(state = SphereState.Idle),
                    modifier = Modifier.fillMaxSize(),
                )

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

                // Startup checks — all rows are always laid out (pending
                // ones dimmed) so the column never reflows and the branding
                // above never shifts. What renders is the CHOREOGRAPHED view
                // of startupCheckTargets: rows above the narration stage show
                // their real verdict, the stage row spins (unless it already
                // failed), rows below sit dimmed with their short labels.
                if (startupCheckTargets.isNotEmpty()) {
                    val pendingLabels = listOf("state", "route", "hermes", "conversation")
                    val displayedChecks = startupCheckTargets.mapIndexed { index, check ->
                        when {
                            index < startupNarrationStage -> check
                            index == startupNarrationStage ->
                                if (check.state == StartupCheckState.Failed) {
                                    check
                                } else {
                                    check.copy(state = StartupCheckState.Active)
                                }
                            else -> StartupCheck(
                                StartupCheckState.Pending,
                                pendingLabels.getOrElse(index) { check.label },
                            )
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                    ) {
                        displayedChecks.forEach { check -> StartupCheckRow(check) }
                    }
                }
            }
        }
        } // end Box
    }
    } // end CompositionLocalProvider (sphere skin)
}

/** One line of the startup sphere's progress narration. */
private data class StartupCheck(
    val state: StartupCheckState,
    val label: String,
)

private val STARTUP_SPINNER_FRAMES = listOf("|", "/", "-", "\\")

private enum class StartupCheckState { Pending, Active, Done, Failed }

/**
 * Terminal-style check line for the startup sphere: monospace glyph + label,
 * dimmed while pending and fading up as the underlying state lands. Failed
 * is visible only briefly — a settled failure releases the gate and the
 * normal UI takes over error presentation.
 */
@Composable
private fun StartupCheckRow(check: StartupCheck) {
    val targetAlpha = when (check.state) {
        StartupCheckState.Pending -> 0.28f
        StartupCheckState.Active -> 0.85f
        else -> 0.95f
    }
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(400),
        label = "startup-check-alpha",
    )
    // Classic ASCII spinner for the active row — guaranteed glyphs in the
    // platform monospace font (fancier braille spinners render as tofu on
    // some devices) and on-theme for terminal-style narration.
    var spinnerFrame by remember { mutableStateOf(0) }
    if (check.state == StartupCheckState.Active) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(120L)
                spinnerFrame = (spinnerFrame + 1) % STARTUP_SPINNER_FRAMES.size
            }
        }
    }
    val glyph = when (check.state) {
        StartupCheckState.Pending -> "·"
        StartupCheckState.Active -> STARTUP_SPINNER_FRAMES[spinnerFrame]
        StartupCheckState.Done -> "✓"
        StartupCheckState.Failed -> "✕"
    }
    val glyphColor = when (check.state) {
        StartupCheckState.Done -> RelayRefresh.Green
        StartupCheckState.Failed -> RelayRefresh.Danger
        else -> RelayRefresh.Muted
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.alpha(alpha),
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = glyphColor,
        )
        Text(
            text = check.label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = if (check.state == StartupCheckState.Active) {
                RelayRefresh.Paper.copy(alpha = 0.9f)
            } else {
                RelayRefresh.Muted
            },
        )
    }
}
