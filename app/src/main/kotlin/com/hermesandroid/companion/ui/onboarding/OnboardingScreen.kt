package com.hermesandroid.companion.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.hermesandroid.companion.ui.theme.HermesCompanionTheme
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 5
private const val LAST_PAGE = PAGE_COUNT - 1

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onSkipToSettings: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val coroutineScope = rememberCoroutineScope()
    var serverUrl by rememberSaveable { mutableStateOf("wss://localhost:8767") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar with Skip and Skip to Settings
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Skip to Settings - visible on pages 1-4
                AnimatedVisibility(
                    visible = pagerState.currentPage < LAST_PAGE,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    TextButton(onClick = onSkipToSettings) {
                        Text(
                            text = "Skip to Settings",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Skip button - always visible
                TextButton(onClick = onComplete) {
                    Text(
                        text = "Skip",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    when (page) {
                        0 -> WelcomePage()
                        1 -> ChatPage()
                        2 -> TerminalPage()
                        3 -> BridgePage()
                        4 -> ConnectPage(
                            serverUrl = serverUrl,
                            onServerUrlChange = { serverUrl = it },
                            onGetStarted = onComplete
                        )
                    }
                }
            }

            // Bottom section: page indicator + navigation button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Page indicator
                PageIndicator(
                    pageCount = PAGE_COUNT,
                    currentPage = pagerState.currentPage
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Next / Get Started button
                if (pagerState.currentPage < LAST_PAGE) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Next")
                    }
                } else {
                    Button(
                        onClick = onComplete,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Get Started")
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    OnboardingPage(
        icon = Icons.Filled.Stars,
        title = "Hermes Companion",
        description = "Your AI agent, in your pocket. Chat, control, and connect — all from your phone."
    )
}

@Composable
private fun ChatPage() {
    OnboardingPage(
        icon = Icons.Filled.Chat,
        title = "Talk to Your Agent",
        description = "Stream conversations with any Hermes profile. Ask questions, run tasks, and collaborate in real time."
    )
}

@Composable
private fun TerminalPage() {
    OnboardingPage(
        icon = Icons.Filled.Terminal,
        title = "Remote Terminal",
        description = "Secure shell access to your agent's host machine, right from your phone. Coming soon."
    )
}

@Composable
private fun BridgePage() {
    OnboardingPage(
        icon = Icons.Filled.PhoneAndroid,
        title = "Device Bridge",
        description = "Let your agent interact with your phone — read notifications, tap buttons, and automate workflows. Coming soon."
    )
}

@Composable
private fun ConnectPage(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    onGetStarted: () -> Unit
) {
    OnboardingPage(
        icon = Icons.Filled.Link,
        title = "Let's Connect",
        description = "Enter your companion relay server URL. You'll pair with a 6-character code after connecting."
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text("Server URL") },
            placeholder = { Text("wss://your-server:8767") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "A 6-digit pairing code will appear on screen after your first connection to verify the link.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onGetStarted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Get Started")
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun OnboardingScreenPreview() {
    HermesCompanionTheme {
        OnboardingScreen(
            onComplete = {},
            onSkipToSettings = {}
        )
    }
}

@Preview(showSystemUi = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OnboardingScreenDarkPreview() {
    HermesCompanionTheme(themePreference = "dark") {
        OnboardingScreen(
            onComplete = {},
            onSkipToSettings = {}
        )
    }
}
