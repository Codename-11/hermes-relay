package com.hermesandroid.relay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.theme.purpleGlow
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermesandroid.relay.ui.components.MorphingSphere
import com.hermesandroid.relay.ui.components.ConnectionChip
import com.hermesandroid.relay.ui.components.ConnectionSwitcherSheet
import com.hermesandroid.relay.ui.components.UnattendedGlobalBanner
import com.hermesandroid.relay.ui.components.UpdateBanner
import com.hermesandroid.relay.update.UpdateCheckResult
import com.hermesandroid.relay.viewmodel.UpdateViewModel
import com.hermesandroid.relay.ui.components.WhatsNewDialog
import com.hermesandroid.relay.data.BridgePreferencesRepository
import com.hermesandroid.relay.data.BridgeSafetyPreferencesRepository
import com.hermesandroid.relay.data.BuildFlavor
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.hermesandroid.relay.util.HumanError
import kotlinx.coroutines.delay
import com.hermesandroid.relay.ui.onboarding.OnboardingScreen
import com.hermesandroid.relay.ui.screens.AboutScreen
import com.hermesandroid.relay.ui.screens.AnalyticsScreen
import com.hermesandroid.relay.ui.screens.AppearanceSettingsScreen
import com.hermesandroid.relay.ui.screens.BridgeScreen
// === PHASE3-safety-rails: bridge safety route ===
import com.hermesandroid.relay.ui.screens.BridgeSafetySettingsScreen
// === END PHASE3-safety-rails ===
import com.hermesandroid.relay.ui.screens.ChatScreen
import com.hermesandroid.relay.ui.screens.ChatSettingsScreen
import com.hermesandroid.relay.ui.screens.ConnectionSettingsScreen
import com.hermesandroid.relay.ui.screens.DeveloperSettingsScreen
import com.hermesandroid.relay.ui.screens.MediaSettingsScreen
import com.hermesandroid.relay.ui.screens.PairedDevicesScreen
import com.hermesandroid.relay.ui.screens.ConnectionsSettingsScreen
import com.hermesandroid.relay.ui.screens.ProfileInspectorScreen
import com.hermesandroid.relay.ui.screens.SettingsScreen
import com.hermesandroid.relay.ui.screens.TerminalScreen
import com.hermesandroid.relay.ui.screens.NotificationCompanionSettingsScreen
import com.hermesandroid.relay.ui.screens.VoiceSettingsScreen
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.network.RelayProfileInspectorClient
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.ProfileInspectorViewModel
import com.hermesandroid.relay.viewmodel.TerminalViewModel
import com.hermesandroid.relay.viewmodel.VoiceViewModel
import com.hermesandroid.relay.audio.VoicePlayer
import com.hermesandroid.relay.audio.VoiceRecorder
import com.hermesandroid.relay.audio.VoiceSfxPlayer
import com.hermesandroid.relay.network.RelayVoiceClient
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
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)

    // Non-bottom-nav destinations — reached by explicit navigation, not the
    // NavigationBar. Paired Devices is opened from Settings → Connection.
    data object PairedDevices : Screen("paired_devices", "Paired Devices", Icons.Filled.Settings)
    // Full-screen pair wizard route. Replaces the old in-Settings Dialog
    // launch so the chooser + Confirm + Verify steps + the camera viewport
    // get a real fullscreen surface (the Dialog wasn't actually filling the
    // window — Settings cards were leaking through behind it).
    //
    // Multi-connection: accepts an optional `connectionId` query arg so
    // the ConnectionsSettings "Re-pair" button can target a specific
    // connection. When null, the flow creates a new connection on success
    // (handoff: Worker B still needs
    // `ConnectionViewModel.addConnectionFromPairing(...)` for that).
    data object Pair : Screen("pair?connectionId={connectionId}", "Pair", Icons.Filled.Settings) {
        const val ARG_CONNECTION_ID: String = "connectionId"
        fun route(connectionId: String? = null): String =
            if (connectionId == null) "pair" else "pair?connectionId=$connectionId"
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
    data object ConnectionSettings : Screen("settings/connection", "Connection", Icons.Filled.Settings)
    data object ChatSettings : Screen("settings/chat", "Chat", Icons.Filled.Settings)
    data object MediaSettings : Screen("settings/media", "Media", Icons.Filled.Settings)
    data object AppearanceSettings : Screen("settings/appearance", "Appearance", Icons.Filled.Settings)
    data object Analytics : Screen("settings/analytics", "Analytics", Icons.Filled.Settings)
    data object DeveloperSettings : Screen("settings/developer", "Developer", Icons.Filled.Settings)
    data object About : Screen("settings/about", "About", Icons.Filled.Settings)

    // Profile Inspector — full-screen read-only viewer with 4 tabs
    // (Config / SOUL / Memory / Skills) for a single profile. The
    // `profileName` path segment survives process death via Android's
    // SavedStateHandle arg propagation; the route template is registered
    // in the NavHost with a typed `StringType` arg, and the concrete
    // URI is built by `route(profileName)`.
    data object ProfileInspector : Screen(
        "settings/profile_inspector/{profileName}",
        "Profile Inspector",
        Icons.Filled.Settings,
    ) {
        const val ARG_PROFILE_NAME: String = "profileName"
        fun route(profileName: String): String {
            // Percent-encode the profile name before splicing it into the
            // path — profile names are typically ASCII identifiers but
            // nothing structurally forbids spaces or non-ASCII, and an
            // unescaped `/` in the name would blow up the route parser.
            val encoded = java.net.URLEncoder.encode(profileName, "UTF-8")
                .replace("+", "%20")
            return "settings/profile_inspector/$encoded"
        }
    }
}

private val bottomNavScreens = listOf(
    Screen.Chat,
    Screen.Terminal,
    Screen.Bridge,
    Screen.Settings
)

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

    // Initialize ChatViewModel reactively when API client becomes available
    val apiClient by connectionViewModel.apiClient.collectAsState()
    val lastSessionId by connectionViewModel.lastSessionId.collectAsState()
    var sessionResumed by remember { mutableStateOf(false) }

    val mediaContext = androidx.compose.ui.platform.LocalContext.current

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
            relayUrlProvider = { connectionViewModel.relayUrl.value },
            sessionTokenProvider = {
                (connectionViewModel.authState.value as? AuthState.Paired)?.token
            },
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
            relayUrlProvider = { connectionViewModel.relayUrl.value },
            sessionTokenProvider = {
                (connectionViewModel.authState.value as? AuthState.Paired)?.token
            },
        )
    }
    // Remembered so the AudioTrack buffers are synthesized once per process.
    // VoiceSfxPlayer is internally crash-proof — failed AudioTrack builds
    // become null-tracks that no-op — so we don't need an outer try/catch.
    val voiceSfxPlayer = remember { VoiceSfxPlayer(mediaContext) }
    LaunchedEffect(Unit) {
        val recorder = VoiceRecorder(mediaContext, voiceViewModel.viewModelScope)
        val player = VoicePlayer(mediaContext)
        voiceViewModel.initialize(
            voiceClient = voiceClient,
            chatViewModel = chatViewModel,
            recorder = recorder,
            player = player,
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
            localBridgeDispatcher = connectionViewModel.bridgeCommandHandler::handleLocalCommand,
            // === END PHASE3-voice-intents-localdispatch ===
            // 2026-04-17: persist the interaction-mode preference across
            // app restarts. VoicePreferencesRepository is the same repo
            // VoiceSettingsScreen reads/writes.
            voicePreferences = com.hermesandroid.relay.data.VoicePreferencesRepository(mediaContext),
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

    LaunchedEffect(apiClient) {
        apiClient?.let { client ->
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

            // Wire session persistence callback
            chatViewModel.onSessionChanged = { sessionId ->
                connectionViewModel.saveLastSessionId(sessionId)
            }

            // Resume last session on first connection
            if (!sessionResumed && lastSessionId != null) {
                chatViewModel.resumeSession(lastSessionId!!)
                sessionResumed = true
            }
        }
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
    // sessions API picks /v1/runs for chat (which has live tool events)
    // while still using /api/sessions/* for browse/rename/delete.
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
        // Brief sphere intro after system splash fades
        var introComplete by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(3000L) // show sphere intro for 3s
            introComplete = true
        }

        val navController = rememberNavController()

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

        // startDestination uses the route TEMPLATE so it matches the
        // composable registered below; optional args default to null/false.
        val startDestination = if (onboardingCompleted) Screen.Chat.route else Screen.Onboarding.route

        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val isOnboarding = navBackStackEntry?.destination?.route == Screen.Onboarding.route

        val isDarkTheme = isSystemInDarkTheme()
        val density = LocalDensity.current
        val imeBottom = WindowInsets.ime.getBottom(density)
        val isKeyboardVisible = imeBottom > 0

        // Voice mode is a full-screen modality — while it's active we hide the
        // bottom navigation bar so the voice overlay can own the entire screen
        // without the Chat/Terminal/Bridge/Settings tabs peeking through below.
        val voiceUiState by voiceViewModel.uiState.collectAsState()

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
            !voiceUiState.voiceMode
        // === END v0.4.1 polish ===

        // Multi-connection: top-bar ConnectionChip state. Visible on the
        // four primary tabs (Chat / Terminal / Bridge / Settings) per
        // decisions.md §19 — connection first, personality second. Hidden
        // while onboarding or during voice-mode full-screen takeover.
        // Tapping opens the switcher sheet declared below the Scaffold.
        val currentRoute = navBackStackEntry?.destination?.route
        val connections by connectionViewModel.connections.collectAsState()
        val activeConnection by connectionViewModel.activeConnection.collectAsState()
        val activeConnectionId by connectionViewModel.activeConnectionId.collectAsState()
        var connectionSheetVisible by remember { mutableStateOf(false) }
        // Only surface the chip when there's something to switch between.
        // With a single connection (the default after migration) the strip
        // is just dead chrome above every screen — users reach it via
        // Settings → Connections → Add instead. Hidden during onboarding
        // and voice-mode takeovers regardless.
        val connectionChipVisible = !isOnboarding &&
            !voiceUiState.voiceMode &&
            currentRoute in bottomNavScreens.map { it.route } &&
            connections.size >= 2

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

        // Multi-connection: app-wide connection chip. Rendered above the
        // Scaffold (outside the per-screen TopAppBars) so it's visible on
        // every primary tab without having to edit Chat / Terminal /
        // Bridge / Settings screens individually — all four screens own
        // their own TopAppBar, and doubling up by stuffing the chip inside
        // each one would be a much bigger surface-area change. Aligned to
        // the end so it reads like a header action rather than a primary
        // title.
        AnimatedVisibility(
            visible = connectionChipVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Background BEFORE statusBarsPadding so the surface
                    // colour extends up under the status bar (otherwise
                    // there's a dead rectangle of system-window behind the
                    // time / wifi icons that doesn't match the app chrome).
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ConnectionChip(
                    label = activeConnection?.label ?: "No connection",
                    onClick = { connectionSheetVisible = true },
                )
            }
        }
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
                    if (showUnattendedBanner || connectionChipVisible) {
                        Modifier.consumeWindowInsets(WindowInsets.statusBars)
                    } else {
                        Modifier
                    }
                ),
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!isOnboarding && !isKeyboardVisible && !voiceUiState.voiceMode) {
                    NavigationBar(
                        containerColor = if (isDarkTheme) {
                            Color(0xFF1A1A2E).copy(alpha = 0.9f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ) {
                        val currentDestination = navBackStackEntry?.destination

                        bottomNavScreens.forEach { screen ->
                            val isSelected = currentDestination?.hierarchy?.any {
                                it.route == screen.route
                            } == true

                            NavigationBarItem(
                                icon = {
                                    Box(
                                        modifier = if (isSelected && isDarkTheme) {
                                            Modifier.purpleGlow(
                                                radius = 18.dp,
                                                alpha = 0.4f,
                                                isDarkTheme = true
                                            )
                                        } else Modifier
                                    ) {
                                        Icon(
                                            imageVector = screen.icon,
                                            contentDescription = screen.label
                                        )
                                    }
                                },
                                label = { Text(screen.label) },
                                selected = isSelected,
                                colors = if (isDarkTheme) {
                                    NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    NavigationBarItemDefaults.colors()
                                },
                                onClick = {
                                    // Chat's route is a template with an
                                    // optional `?openAgentSheet` arg — always
                                    // navigate to the concrete bare-"chat"
                                    // URI from bottom nav so we don't leak
                                    // the `{openAgentSheet}` placeholder into
                                    // the destination and so tab-switching
                                    // never re-opens the AgentInfoSheet.
                                    val target = when (screen) {
                                        is Screen.Chat -> Screen.Chat.route(openAgentSheet = false)
                                        else -> screen.route
                                    }
                                    navController.navigate(target) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            CompositionLocalProvider(LocalSnackbarHost provides snackbarHostState) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(innerPadding)
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
                    )
                }
                composable(Screen.Terminal.route) {
                    TerminalScreen(
                        terminalViewModel = terminalViewModel,
                        connectionViewModel = connectionViewModel
                    )
                }
                composable(Screen.Bridge.route) {
                    // === PHASE3-bridge-ui: BridgeScreen wiring ===
                    // BridgeScreen owns its own BridgeViewModel via the
                    // default `viewModel()` parameter — no shared state with
                    // ChatViewModel / ConnectionViewModel is plumbed through
                    // here yet. Once Agent accessibility lands HermesAccessibilityService
                    // and we need to observe its runtime state from RelayApp
                    // scope, a shared holder or explicit VM param gets added
                    // here.
                    BridgeScreen(
                        // === PHASE3-safety-rails: bridge safety route ===
                        onNavigateToBridgeSafety = {
                            navController.navigate(Screen.BridgeSafetySettings.route)
                        },
                        // === END PHASE3-safety-rails ===
                    )
                    // === END PHASE3-bridge-ui ===
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        connectionViewModel = connectionViewModel,
                        chatViewModel = chatViewModel,
                        // Tap on the Active Agent card: land on Chat with
                        // the AgentInfoSheet auto-opened. launchSingleTop
                        // avoids stacking duplicate Chat entries; popUpTo
                        // Settings (inclusive=false) preserves Settings on
                        // the back stack so pressing Back from Chat
                        // returns to Settings rather than exiting.
                        onNavigateToChatWithAgentSheet = {
                            navController.navigate(
                                Screen.Chat.route(openAgentSheet = true),
                            ) {
                                launchSingleTop = true
                                popUpTo(Screen.Settings.route) { inclusive = false }
                            }
                        },
                        onNavigateToConnections = {
                            navController.navigate(Screen.ConnectionsSettings.route)
                        },
                        onNavigateToConnectionSettings = {
                            navController.navigate(Screen.ConnectionSettings.route)
                        },
                        onNavigateToChatSettings = {
                            navController.navigate(Screen.ChatSettings.route)
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
                    BridgeSafetySettingsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
                // === END PHASE3-safety-rails ===
                composable(Screen.PairedDevices.route) {
                    PairedDevicesScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() },
                        onRequestRepair = {
                            // Pop back to Settings so the user lands on the
                            // "Scan Pairing QR" button rather than getting
                            // stranded on an empty devices list.
                            navController.popBackStack(Screen.Settings.route, inclusive = false)
                        }
                    )
                }
                composable(Screen.ConnectionSettings.route) {
                    ConnectionSettingsScreen(
                        connectionViewModel = connectionViewModel,
                        onBack = { navController.popBackStack() },
                        onNavigateToPairedDevices = {
                            navController.navigate(Screen.PairedDevices.route)
                        },
                        onNavigateToPair = {
                            navController.navigate(Screen.Pair.route(null))
                        }
                    )
                }
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
                            // Re-pair targets the connection via the nav
                            // arg — PairScreen onComplete reads it to
                            // decide between new-connection add and
                            // in-place re-pair. We still need
                            // switchConnection here so the pairing-payload
                            // apply lands on the right connection's auth
                            // store.
                            connectionViewModel.switchConnection(id)
                            navController.navigate(Screen.Pair.route(id))
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
                            navController.navigate(Screen.Pair.route(null))
                        },
                        onBack = { navController.popBackStack() },
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
                    ),
                ) { backStackEntry ->
                    val connectionIdArg = backStackEntry.arguments
                        ?.getString(Screen.Pair.ARG_CONNECTION_ID)
                    com.hermesandroid.relay.ui.screens.PairScreen(
                        connectionViewModel = connectionViewModel,
                        onComplete = {
                            // Multi-connection: on successful pair,
                            //  - connectionIdArg == null → new-connection
                            //    add path. Call addConnectionFromPairing
                            //    which creates a fresh Connection (with
                            //    its own tokenStoreKey) and switches to it.
                            //
                            //    **v1 pairing-token limitation:** the pair
                            //    that just completed wrote its session token
                            //    into the previously-active connection's
                            //    token store — NOT the new connection's
                            //    (because applyPairingPayload runs against
                            //    the live authManager, which was still
                            //    bound to the outgoing connection when the
                            //    pair completed). The new connection
                            //    therefore lands in an unpaired state and
                            //    the user must re-pair it (scan a second
                            //    QR while the new connection is active). A
                            //    cleaner fix — targeting
                            //    applyPairingPayload at a specific store
                            //    BEFORE the pair runs — is a deferred
                            //    follow-up.
                            //  - connectionIdArg != null → re-pair in place.
                            //    applyPairingPayload already wrote to the
                            //    active connection's auth store (the
                            //    calling site switched to it before
                            //    navigating), so there's nothing extra to
                            //    do here.
                            if (connectionIdArg == null) {
                                connectionSwitchScope.launch {
                                    connectionViewModel.addConnectionFromPairing(
                                        label = null,
                                        apiServerUrl = connectionViewModel.apiServerUrl.value,
                                        relayUrl = connectionViewModel.relayUrl.value,
                                    ).onFailure { err ->
                                        snackbarHostState.showSnackbar(
                                            err.message ?: "Could not save connection",
                                        )
                                    }
                                }
                            }
                            navController.popBackStack()
                        },
                        onCancel = { navController.popBackStack() },
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
                        onBack = { navController.popBackStack() }
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
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            } // end CompositionLocalProvider
        }
        } // end Column (wraps banner + Scaffold)

        // Multi-connection: connection switcher sheet. Hoisted to Box
        // scope so the sheet floats over whatever screen is active —
        // ModalBottomSheet renders as a popup, so the placement in the
        // composition tree only affects state ownership, not visual
        // stacking.
        if (connectionSheetVisible) {
            ConnectionSwitcherSheet(
                connections = connections,
                activeConnectionId = activeConnectionId,
                onSelectConnection = { id -> connectionViewModel.switchConnection(id) },
                onManageConnections = {
                    connectionSheetVisible = false
                    navController.navigate(Screen.ConnectionsSettings.route)
                },
                onDismiss = { connectionSheetVisible = false },
            )
        }

        // Sphere intro overlay — fades out after 1.5s to reveal main UI
        AnimatedVisibility(
            visible = !introComplete && onboardingCompleted,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(600))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A2E)),
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
                        color = Color.White.copy(alpha = 0.9f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "agent interface",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.5f),
                        letterSpacing = 2.sp
                    )
                }
            }
        }
        } // end Box
    }
}
