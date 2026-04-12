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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.theme.purpleGlow
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hermesandroid.relay.ui.components.MorphingSphere
import com.hermesandroid.relay.ui.components.WhatsNewDialog
import com.hermesandroid.relay.util.HumanError
import kotlinx.coroutines.delay
import com.hermesandroid.relay.ui.onboarding.OnboardingScreen
import com.hermesandroid.relay.ui.screens.AboutScreen
import com.hermesandroid.relay.ui.screens.AnalyticsScreen
import com.hermesandroid.relay.ui.screens.AppearanceSettingsScreen
import com.hermesandroid.relay.ui.screens.BridgeScreen
// === PHASE3-ζ: bridge safety route ===
import com.hermesandroid.relay.ui.screens.BridgeSafetySettingsScreen
// === END PHASE3-ζ ===
import com.hermesandroid.relay.ui.screens.ChatScreen
import com.hermesandroid.relay.ui.screens.ChatSettingsScreen
import com.hermesandroid.relay.ui.screens.ConnectionSettingsScreen
import com.hermesandroid.relay.ui.screens.DeveloperSettingsScreen
import com.hermesandroid.relay.ui.screens.MediaSettingsScreen
import com.hermesandroid.relay.ui.screens.PairedDevicesScreen
import com.hermesandroid.relay.ui.screens.SettingsScreen
import com.hermesandroid.relay.ui.screens.TerminalScreen
import com.hermesandroid.relay.ui.screens.NotificationCompanionSettingsScreen
import com.hermesandroid.relay.ui.screens.VoiceSettingsScreen
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
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
    data object Chat : Screen("chat", "Chat", Icons.AutoMirrored.Filled.Chat)
    data object Terminal : Screen("terminal", "Terminal", Icons.Filled.Code)
    data object Bridge : Screen("bridge", "Bridge", Icons.Filled.PhoneAndroid)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)

    // Non-bottom-nav destinations — reached by explicit navigation, not the
    // NavigationBar. Paired Devices is opened from Settings → Connection.
    data object PairedDevices : Screen("paired_devices", "Paired Devices", Icons.Filled.Settings)
    data object VoiceSettings : Screen("voice_settings", "Voice", Icons.Filled.Settings)
    // === PHASE3-ε-followup ===
    data object NotificationCompanionSettings :
        Screen("settings/notifications", "Notification companion", Icons.Filled.Settings)
    // === END PHASE3-ε-followup ===
    // === PHASE3-ζ: bridge safety route ===
    data object BridgeSafetySettings :
        Screen("settings/bridge_safety", "Bridge safety", Icons.Filled.Settings)
    // === END PHASE3-ζ ===
    // Per-category settings sub-screens — split out of the mega SettingsScreen
    // following the VoiceSettingsScreen pattern (see DEVLOG 2026-04-11).
    data object ConnectionSettings : Screen("settings/connection", "Connection", Icons.Filled.Settings)
    data object ChatSettings : Screen("settings/chat", "Chat", Icons.Filled.Settings)
    data object MediaSettings : Screen("settings/media", "Media", Icons.Filled.Settings)
    data object AppearanceSettings : Screen("settings/appearance", "Appearance", Icons.Filled.Settings)
    data object Analytics : Screen("settings/analytics", "Analytics", Icons.Filled.Settings)
    data object DeveloperSettings : Screen("settings/developer", "Developer", Icons.Filled.Settings)
    data object About : Screen("settings/about", "About", Icons.Filled.Settings)
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

    // One-time init: the terminal channel ViewModel registers with the shared
    // multiplexer and observes the relay connection state so it can attach/
    // reattach automatically on network changes.
    LaunchedEffect(Unit) {
        terminalViewModel.initialize(
            multiplexer = connectionViewModel.multiplexer,
            connectionState = connectionViewModel.relayConnectionState,
            authState = connectionViewModel.authState,
            authManager = connectionViewModel.authManager
        )
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
    // Remembered so the AudioTrack buffers are synthesized once per process.
    // VoiceSfxPlayer is internally crash-proof — failed AudioTrack builds
    // become null-tracks that no-op — so we don't need an outer try/catch.
    val voiceSfxPlayer = remember { VoiceSfxPlayer(mediaContext) }
    LaunchedEffect(Unit) {
        val recorder = VoiceRecorder(mediaContext, voiceViewModel.viewModelScope)
        val player = VoicePlayer()
        voiceViewModel.initialize(
            voiceClient = voiceClient,
            chatViewModel = chatViewModel,
            recorder = recorder,
            player = player,
            sfxPlayer = voiceSfxPlayer,
        )
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

    // Sync app context toggle from settings to chat
    val appContextEnabled by connectionViewModel.appContextEnabled.collectAsState()
    LaunchedEffect(appContextEnabled) {
        chatViewModel.appContextEnabled = appContextEnabled
    }

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

        Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
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
                                    navController.navigate(screen.route) {
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
                    OnboardingScreen(
                        onComplete = { apiServerUrl, apiKey, relayUrl, relayPairingCode ->
                            connectionViewModel.updateApiServerUrl(apiServerUrl)
                            if (apiKey.isNotBlank()) {
                                connectionViewModel.updateApiKey(apiKey)
                            }
                            if (relayUrl.isNotBlank()) {
                                connectionViewModel.updateRelayUrl(relayUrl)
                                // Auto-flip insecure mode when the scanned
                                // relay URL is plain ws:// so the user
                                // doesn't have to hunt through Developer
                                // Options to allow the connection.
                                if (relayUrl.startsWith("ws://")) {
                                    connectionViewModel.setInsecureMode(true)
                                }
                            }
                            // Hand the server-issued pairing code (from a
                            // scanned QR) to AuthManager so the next auth
                            // envelope uses it instead of the locally-
                            // generated fallback. Consumed on first auth.ok.
                            if (!relayPairingCode.isNullOrBlank()) {
                                connectionViewModel.authManager.applyServerIssuedCode(relayPairingCode)
                            }
                            connectionViewModel.completeOnboarding()
                            navController.navigate(Screen.Chat.route) {
                                popUpTo(Screen.Onboarding.route) { inclusive = true }
                            }
                        }
                    )
                }
                composable(Screen.Chat.route) {
                    // Responsive bubble width based on screen width
                    val configuration = LocalConfiguration.current
                    val screenWidthDp = configuration.screenWidthDp.dp
                    val maxBubbleWidth = when {
                        screenWidthDp >= 840.dp -> 600.dp  // Expanded (tablet)
                        screenWidthDp >= 600.dp -> 480.dp  // Medium (landscape / small tablet)
                        else -> 300.dp                      // Compact (phone portrait)
                    }

                    ChatScreen(
                        chatViewModel = chatViewModel,
                        connectionViewModel = connectionViewModel,
                        voiceViewModel = voiceViewModel,
                        maxBubbleWidth = maxBubbleWidth
                    )
                }
                composable(Screen.Terminal.route) {
                    TerminalScreen(
                        terminalViewModel = terminalViewModel,
                        connectionViewModel = connectionViewModel
                    )
                }
                composable(Screen.Bridge.route) {
                    // === PHASE3-δ: BridgeScreen wiring ===
                    // BridgeScreen owns its own BridgeViewModel via the
                    // default `viewModel()` parameter — no shared state with
                    // ChatViewModel / ConnectionViewModel is plumbed through
                    // here yet. Once Agent γ lands HermesAccessibilityService
                    // and we need to observe its runtime state from RelayApp
                    // scope, a shared holder or explicit VM param gets added
                    // here.
                    BridgeScreen(
                        // === PHASE3-ζ: bridge safety route ===
                        onNavigateToBridgeSafety = {
                            navController.navigate(Screen.BridgeSafetySettings.route)
                        },
                        // === END PHASE3-ζ ===
                    )
                    // === END PHASE3-δ ===
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(
                        connectionViewModel = connectionViewModel,
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
                        // === PHASE3-ζ: bridge safety route ===
                        onNavigateToBridgeSafety = {
                            navController.navigate(Screen.BridgeSafetySettings.route)
                        },
                        // === END PHASE3-ζ ===
                        onNavigateToPairedDevices = {
                            navController.navigate(Screen.PairedDevices.route)
                        },
                        onNavigateToDeveloperSettings = {
                            navController.navigate(Screen.DeveloperSettings.route)
                        },
                        onNavigateToAbout = {
                            navController.navigate(Screen.About.route)
                        }
                    )
                }
                composable(Screen.VoiceSettings.route) {
                    VoiceSettingsScreen(
                        voiceViewModel = voiceViewModel,
                        voiceClient = voiceClient,
                        onBack = { navController.popBackStack() }
                    )
                }
                // === PHASE3-ε-followup: notification companion route ===
                composable(Screen.NotificationCompanionSettings.route) {
                    NotificationCompanionSettingsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
                // === END PHASE3-ε-followup ===
                // === PHASE3-ζ: bridge safety route ===
                composable(Screen.BridgeSafetySettings.route) {
                    BridgeSafetySettingsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
                // === END PHASE3-ζ ===
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
                        }
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
                        onBack = { navController.popBackStack() }
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
            }
            } // end CompositionLocalProvider
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
