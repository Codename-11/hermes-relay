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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import com.hermesandroid.relay.ui.onboarding.OnboardingScreen
import com.hermesandroid.relay.ui.screens.BridgeScreen
import com.hermesandroid.relay.ui.screens.ChatScreen
import com.hermesandroid.relay.ui.screens.SettingsScreen
import com.hermesandroid.relay.ui.screens.TerminalScreen
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.ChatViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import com.hermesandroid.relay.viewmodel.TerminalViewModel

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

    // One-time init: the terminal channel ViewModel registers with the shared
    // multiplexer and observes the relay connection state so it can attach/
    // reattach automatically on network changes.
    LaunchedEffect(Unit) {
        terminalViewModel.initialize(
            multiplexer = connectionViewModel.multiplexer,
            connectionState = connectionViewModel.relayConnectionState
        )
    }

    // Initialize ChatViewModel reactively when API client becomes available
    val apiClient by connectionViewModel.apiClient.collectAsState()
    val lastSessionId by connectionViewModel.lastSessionId.collectAsState()
    var sessionResumed by remember { mutableStateOf(false) }

    val mediaContext = androidx.compose.ui.platform.LocalContext.current
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

    // Sync streaming endpoint preference to chat
    val streamingEndpoint by connectionViewModel.streamingEndpoint.collectAsState()
    LaunchedEffect(streamingEndpoint) {
        chatViewModel.streamingEndpoint = streamingEndpoint
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

    HermesRelayTheme(themePreference = themePreference) {
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

        Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (!isOnboarding && !isKeyboardVisible) {
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
                    BridgeScreen()
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(connectionViewModel = connectionViewModel)
                }
            }
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
