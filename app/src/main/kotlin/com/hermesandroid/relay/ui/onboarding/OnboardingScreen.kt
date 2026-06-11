package com.hermesandroid.relay.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.components.MorphingSphere
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.components.ConnectionWizard
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch

/** Page identifiers for dynamic onboarding flow. */
private enum class OnboardingPage { Welcome, Chat, Manage, Power, Connect }

/**
 * Standard-first onboarding:
 *
 *  1. **Feature pages** (Welcome / Chat / Manage / Power tools) — standard
 *     Hermes API/dashboard features first, Relay-only power tools second.
 *  2. **Connect page** — embeds the shared [ConnectionWizard] so onboarding
 *     uses the exact same Standard API/dashboard and optional Relay pairing
 *     flow as Settings → Connections.
 *
 * The previous separate "ConnectPage" + "RelayPage" pair has been removed —
 * it discarded the QR's relay block, never applied per-channel grants, never
 * walked the user through the TTL picker, and let users exit onboarding in a
 * "half-paired" state that broke the relay/voice/bridge features.
 */
@Composable
fun OnboardingScreen(
    // === onboarding-pair-bug-fix (2026-04-13) ===
    // CRITICAL: the ConnectionViewModel MUST be passed in from the top
    // level of RelayApp, not fetched here via `viewModel()`. A bare
    // `viewModel()` call inside a composable that lives under a
    // `composable(Screen.Onboarding.route) { ... }` block binds to the
    // NavBackStackEntry's ViewModelStore, not the Activity's. When
    // onboarding completes and we `popUpTo(Onboarding) { inclusive = true }`
    // during navigation to Chat, that backstack entry is destroyed and
    // the scoped VM's `onCleared()` runs → `connectionManager.shutdown()`
    // → WSS `close(1000)` → the freshly-minted session token is thrown
    // away. Meanwhile Chat uses the Activity-scoped instance (a DIFFERENT
    // ConnectionViewModel), which never saw the pair and has no token.
    // Symptom: "onboarding reports success but only the API URL survived."
    //
    // Passing the VM in explicitly forces us to share the Activity-scoped
    // instance that Chat / Settings / Bridge all use, so the pair state
    // lands on the right VM and survives the Onboarding→Chat transition.
    connectionViewModel: ConnectionViewModel,
    onComplete: () -> Unit,
    onManageSignIn: () -> Unit = onComplete,
) {
    val pages = remember {
        buildList {
            add(OnboardingPage.Welcome)
            add(OnboardingPage.Chat)
            add(OnboardingPage.Manage)
            add(OnboardingPage.Power)
            add(OnboardingPage.Connect)
        }
    }
    val pageCount = pages.size
    val lastPage = pageCount - 1

    val pagerState = rememberPagerState(pageCount = { pageCount })
    val coroutineScope = rememberCoroutineScope()
    var showSkipConfirm by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (showSkipConfirm) {
            AlertDialog(
                onDismissRequest = { showSkipConfirm = false },
                title = { Text("Skip setup?") },
                text = {
                    Text(
                        "You can configure your Hermes connection later in Settings → Connections. " +
                            "Without a connection, Chat and Manage won't load. Relay pairing can " +
                            "be added later for power tools."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showSkipConfirm = false
                        onComplete()
                    }) {
                        Text("Skip anyway")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSkipConfirm = false }) {
                        Text("Go back")
                    }
                }
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar with Skip — only on informational pages, not the wizard
            // (which has its own "Skip for now" affordance).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pages[pagerState.currentPage] != OnboardingPage.Connect) {
                    TextButton(onClick = { showSkipConfirm = true }) {
                        Text(
                            text = "Skip",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { pageIndex ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    when (pages[pageIndex]) {
                        OnboardingPage.Welcome -> WelcomePage()
                        OnboardingPage.Chat -> ChatPage()
                        OnboardingPage.Manage -> ManagePage()
                        OnboardingPage.Power -> PowerToolsPage()
                        OnboardingPage.Connect -> ConnectPage(
                            connectionViewModel = connectionViewModel,
                            onComplete = onComplete,
                            onManageSignIn = onManageSignIn,
                            onSkip = { showSkipConfirm = true },
                        )
                    }
                }
            }

            // Bottom navigation only on informational pages — the wizard
            // owns its own back/pair affordances.
            if (pages[pagerState.currentPage] != OnboardingPage.Connect) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .padding(bottom = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PageIndicator(
                        pageCount = pageCount,
                        currentPage = pagerState.currentPage
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AnimatedVisibility(
                            visible = pagerState.currentPage > 0,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            TextButton(
                                onClick = {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                }
                            ) {
                                Text(text = "Back")
                            }
                        }
                        if (pagerState.currentPage == 0) {
                            Spacer(modifier = Modifier.width(1.dp))
                        }

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(
                                        (pagerState.currentPage + 1).coerceAtMost(lastPage)
                                    )
                                }
                            }
                        ) {
                            Text(text = if (pagerState.currentPage == lastPage - 1) "Connect" else "Next")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    val context = LocalContext.current
    OnboardingPage(
        icon = Icons.Outlined.RocketLaunch,
        title = "Hermes-Relay for Android",
        description = "Chat with Hermes and manage your dashboard from your phone.",
        transparentHero = true,
        heroContent = {
            Box(modifier = Modifier.fillMaxSize()) {
                MorphingSphere(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    state = SphereState.Idle,
                    intensity = 0.12f,
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Welcome",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
                        .padding(start = 7.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = "Hermes logo",
                        modifier = Modifier.size(30.dp)
                    )
                    Text(
                        text = "Hermes-Relay",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SetupPathSummary(
                label = "Standard",
                description = "Connect to your running Hermes dashboard and API. No Relay install or pairing required.",
            )
            SetupPathSummary(
                label = "Advanced",
                description = "Add Hermes-Relay for Terminal, Bridge, relay sessions, and channel grants.",
            )
        }

        Text(
            text = "The setup guide has copy/paste commands when you need to start Hermes on a computer or server.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://codename-11.github.io/hermes-relay/guide/getting-started"))
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Setup Guide")
            }

            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://hermes-agent.nousresearch.com/docs"))
                    )
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Hermes Docs")
            }
        }

        Text(
            text = "Hermes API server docs",
            style = MaterialTheme.typography.bodySmall.copy(
                textDecoration = TextDecoration.Underline
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server")))
            }
        )
    }
}

@Composable
private fun SetupPathSummary(
    label: String,
    description: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(78.dp),
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ChatPage() {
    OnboardingPage(
        icon = Icons.Outlined.Forum,
        title = "Chat",
        description = "Your Hermes agent, streaming in real time.",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SetupPathSummary(
                label = "Streaming",
                description = "Live responses with tool progress, markdown, and rich cards as the agent works.",
            )
            SetupPathSummary(
                label = "Profiles",
                description = "Switch agent profiles mid-flow — each keeps its own sessions, model, and persona.",
            )
            SetupPathSummary(
                label = "Voice",
                description = "Tap the mic to talk. Speech runs through your Hermes server — no extra install.",
            )
        }
    }
}

@Composable
private fun ManagePage() {
    OnboardingPage(
        icon = Icons.Filled.Settings,
        title = "Manage",
        description = "Your Hermes dashboard, pocket-sized.",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SetupPathSummary(
                label = "Control",
                description = "Profiles, skills, automations, MCP servers, models, and provider keys.",
            )
            SetupPathSummary(
                label = "Skills hub",
                description = "Search the hub, read a skill before installing, and install from your phone.",
            )
            SetupPathSummary(
                label = "One sign-in",
                description = "Signing into the dashboard once also unlocks voice for this connection.",
            )
        }
    }
}

@Composable
private fun PowerToolsPage() {
    OnboardingPage(
        icon = Icons.Outlined.Terminal,
        title = "Power tools",
        description = "Pair the optional Relay when you want more than standard.",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SetupPathSummary(
                label = "Terminal",
                description = "A real tmux session on your server, in your pocket.",
            )
            SetupPathSummary(
                label = "Bridge",
                description = "Let the agent operate this phone — with safety rails (sideload builds).",
            )
            SetupPathSummary(
                label = "Realtime",
                description = "Provider-native realtime voice agent and profile-aware voice providers.",
            )
        }
    }
}

@Composable
private fun ConnectPage(
    connectionViewModel: ConnectionViewModel,
    onComplete: () -> Unit,
    onManageSignIn: () -> Unit,
    onSkip: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        ConnectionWizard(
            connectionViewModel = connectionViewModel,
            onComplete = onComplete,
            onCancel = onSkip,
            onManageSignIn = onManageSignIn,
            showSkip = true,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun OnboardingScreenPreview() {
    HermesRelayTheme {
        // Preview uses whatever ViewModelStoreOwner Compose-Preview mocks;
        // at preview time there's no NavHost so the scope collision that
        // the production entry point has to avoid doesn't apply here.
        OnboardingScreen(
            connectionViewModel = viewModel(),
            onComplete = {},
        )
    }
}

@Preview(showSystemUi = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OnboardingScreenDarkPreview() {
    HermesRelayTheme(themePreference = "dark") {
        OnboardingScreen(
            connectionViewModel = viewModel(),
            onComplete = {},
        )
    }
}
