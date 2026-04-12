package com.hermesandroid.relay.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.ui.components.ConnectionWizard
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch

/** Page identifiers for dynamic onboarding flow. */
private enum class OnboardingPage { Welcome, Chat, Terminal, Bridge, Connect }

/**
 * Three-stage onboarding:
 *
 *  1. **Feature pages** (Welcome / Chat / Terminal / Bridge) — informational
 *     swipe-able introduction. Bridge / Terminal pages only show when the
 *     relay feature is enabled (Developer Options).
 *  2. **Connect page** — embeds the shared [ConnectionWizard] so onboarding
 *     uses the exact same scan → confirm → verify flow as Settings → Connection.
 *     The wizard owns credential application; on success / skip it calls back
 *     into [onComplete] to finish onboarding and navigate to chat.
 *
 * The previous separate "ConnectPage" + "RelayPage" pair has been removed —
 * it discarded the QR's relay block, never applied per-channel grants, never
 * walked the user through the TTL picker, and let users exit onboarding in a
 * "half-paired" state that broke the relay/voice/bridge features.
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val relayEnabled by FeatureFlags.relayEnabled(context).collectAsState(initial = FeatureFlags.isDevBuild)
    val connectionViewModel: ConnectionViewModel = viewModel()

    // Build page list dynamically based on feature flags
    val pages = remember(relayEnabled) {
        buildList {
            add(OnboardingPage.Welcome)
            add(OnboardingPage.Chat)
            if (relayEnabled) {
                add(OnboardingPage.Terminal)
                add(OnboardingPage.Bridge)
            }
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
                    Text("You can configure your server connection later in Settings → Connection. Without pairing, chat and voice features won't work yet.")
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
                        OnboardingPage.Terminal -> TerminalPage()
                        OnboardingPage.Bridge -> BridgePage()
                        OnboardingPage.Connect -> ConnectPage(
                            connectionViewModel = connectionViewModel,
                            onComplete = onComplete,
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
        title = "Hermes-Relay",
        description = "Your Hermes agent, in your pocket."
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "hermes-agent.nousresearch.com",
            style = MaterialTheme.typography.bodySmall.copy(
                textDecoration = TextDecoration.Underline
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://hermes-agent.nousresearch.com")))
            }
        )
    }
}

@Composable
private fun ChatPage() {
    OnboardingPage(
        icon = Icons.Outlined.Forum,
        title = "Chat",
        description = "Talk to any Hermes agent profile with real-time streaming responses, tool progress, and full markdown."
    )
}

@Composable
private fun TerminalPage() {
    OnboardingPage(
        icon = Icons.Outlined.Terminal,
        title = "Terminal",
        description = "Secure remote shell access to your server via tmux. Coming soon."
    )
}

@Composable
private fun BridgePage() {
    OnboardingPage(
        icon = Icons.Outlined.PhonelinkSetup,
        title = "Bridge",
        description = "Let your agent control your device — taps, typing, screenshots, and automation. Coming soon."
    )
}

@Composable
private fun ConnectPage(
    connectionViewModel: ConnectionViewModel,
    onComplete: () -> Unit,
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
            showSkip = true,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun OnboardingScreenPreview() {
    HermesRelayTheme {
        OnboardingScreen(onComplete = {})
    }
}

@Preview(showSystemUi = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OnboardingScreenDarkPreview() {
    HermesRelayTheme(themePreference = "dark") {
        OnboardingScreen(onComplete = {})
    }
}
