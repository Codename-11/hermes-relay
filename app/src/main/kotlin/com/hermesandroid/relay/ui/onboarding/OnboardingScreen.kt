package com.hermesandroid.relay.ui.onboarding

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.components.ConnectionWizard
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch

/** Page identifiers for dynamic onboarding flow. */
private enum class OnboardingPage { Welcome, Chat, Manage, Power, Connect }

private val OnboardingAccent = Color(0xFF7B55F6)

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
    onOpenPermissions: () -> Unit = {},
    /**
     * Enter offline Demo mode from the Connect page's "Try the demo" button.
     * RelayApp wires this to enter demo + navigate to Chat without completing
     * onboarding. Defaults to no-op so previews/older callers still compile.
     */
    onTryDemo: () -> Unit = {},
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
                title = { Text(stringResource(R.string.onboarding_skip_setup_title)) },
                text = {
                    Text(stringResource(R.string.onboarding_skip_setup_text))
                },
                confirmButton = {
                    TextButton(onClick = {
                        showSkipConfirm = false
                        onComplete()
                    }) {
                        Text(stringResource(R.string.onboarding_skip_for_now))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSkipConfirm = false }) {
                        Text(stringResource(R.string.onboarding_go_back))
                    }
                }
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            val currentPage = pagerState.currentPage
            val currentPageType = pages[currentPage]

            // The selected welcome frame has no toolbar. Later information
            // pages keep quiet navigation without reserving space above it.
            if (currentPage in 1 until lastPage) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            coroutineScope.launch { pagerState.animateScrollToPage(currentPage - 1) }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.onboarding_back))
                    }
                    TextButton(onClick = { showSkipConfirm = true }) {
                        Text(stringResource(R.string.onboarding_skip))
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
                        OnboardingPage.Power -> PowerToolsPage(
                            onOpenPermissions = onOpenPermissions,
                        )
                        OnboardingPage.Connect -> ConnectPage(
                            connectionViewModel = connectionViewModel,
                            onComplete = onComplete,
                            onManageSignIn = onManageSignIn,
                            onSkip = { showSkipConfirm = true },
                            onTryDemo = onTryDemo,
                        )
                    }
                }
            }

            // Bottom navigation only on informational pages — the wizard
            // owns its own back/pair affordances.
            if (currentPageType != OnboardingPage.Connect) {
                val compactHeight = LocalConfiguration.current.screenHeightDp < 620
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 42.dp)
                        .padding(bottom = if (compactHeight) 10.dp else 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp),
                ) {
                    GradientOnboardingButton(
                        label = when {
                            currentPage == 0 -> stringResource(R.string.onboarding_get_started)
                            currentPage == lastPage - 1 -> stringResource(R.string.onboarding_connect)
                            else -> stringResource(R.string.onboarding_next)
                        },
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage((currentPage + 1).coerceAtMost(lastPage))
                            }
                        },
                    )

                    if (currentPage == 0) {
                        TextButton(onClick = onTryDemo) {
                            Text(
                                text = stringResource(R.string.chat_try_demo),
                                color = OnboardingAccent,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }

                    SegmentedOnboardingProgress(
                        pageCount = pageCount,
                        currentPage = currentPage,
                    )
                    Text(
                        text = stringResource(R.string.onboarding_step_count, currentPage + 1, pageCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                    )
                }
            }
        }
    }
}

@Composable
private fun GradientOnboardingButton(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = Color.Transparent,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF7047F5), Color(0xFF6446F0)),
                    ),
                )
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.width(24.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun SegmentedOnboardingProgress(
    pageCount: Int,
    currentPage: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (index == currentPage) {
                            OnboardingAccent
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun WelcomePage() {
    val titleParts = stringResource(R.string.onboarding_welcome_title).split("\n", limit = 2)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.onboarding_hero_option1),
                contentDescription = stringResource(R.string.onboarding_hermes_logo),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(horizontalBias = 0f, verticalBias = -0.5f),
            )
        }

        Text(
            text = buildAnnotatedString {
                append(titleParts.first())
                if (titleParts.size > 1) append("\n")
                withStyle(SpanStyle(color = OnboardingAccent)) {
                    if (titleParts.size > 1) append(titleParts[1])
                }
            },
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 38.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_description),
            style = MaterialTheme.typography.bodyLarge.copy(
                lineHeight = 27.sp,
                fontWeight = FontWeight.Normal,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        WelcomeCapabilityRow(
            icon = Icons.Filled.Dashboard,
            label = stringResource(R.string.onboarding_chat_manage_label),
            description = stringResource(R.string.onboarding_chat_manage_description),
        )
        Spacer(Modifier.height(14.dp))
        WelcomeCapabilityRow(
            icon = Icons.Filled.Bolt,
            label = stringResource(R.string.onboarding_power_tools_label),
            description = stringResource(R.string.onboarding_power_tools_description),
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun WelcomeCapabilityRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 47.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        Box {
            Surface(
                modifier = Modifier.size(58.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
                ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = OnboardingAccent,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF57E389)),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SetupPathSummary(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f),
            ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(25.dp),
                    tint = OnboardingAccent,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatPage() {
    OnboardingPage(
        icon = Icons.Outlined.Forum,
        title = stringResource(R.string.onboarding_chat_title),
        description = stringResource(R.string.onboarding_chat_description),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SetupPathSummary(
                icon = Icons.Filled.Bolt,
                label = stringResource(R.string.onboarding_streaming_label),
                description = stringResource(R.string.onboarding_streaming_description),
            )
            SetupPathSummary(
                icon = Icons.Filled.Person,
                label = stringResource(R.string.onboarding_profiles_label),
                description = stringResource(R.string.onboarding_profiles_description),
            )
            SetupPathSummary(
                icon = Icons.Filled.Mic,
                label = stringResource(R.string.onboarding_voice_label),
                description = stringResource(R.string.onboarding_voice_description),
            )
        }
    }
}

@Composable
private fun ManagePage() {
    OnboardingPage(
        icon = Icons.Filled.Settings,
        title = stringResource(R.string.onboarding_manage_title),
        description = stringResource(R.string.onboarding_manage_description),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SetupPathSummary(
                icon = Icons.Filled.Tune,
                label = stringResource(R.string.onboarding_control_label),
                description = stringResource(R.string.onboarding_control_description),
            )
            SetupPathSummary(
                icon = Icons.Filled.Extension,
                label = stringResource(R.string.onboarding_skills_hub_label),
                description = stringResource(R.string.onboarding_skills_hub_description),
            )
            SetupPathSummary(
                icon = Icons.Filled.Lock,
                label = stringResource(R.string.onboarding_one_sign_in_label),
                description = stringResource(R.string.onboarding_one_sign_in_description),
            )
        }
    }
}

@Composable
private fun PowerToolsPage(
    onOpenPermissions: () -> Unit,
) {
    OnboardingPage(
        icon = Icons.Outlined.Terminal,
        title = stringResource(R.string.onboarding_power_title),
        description = stringResource(R.string.onboarding_power_description),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SetupPathSummary(
                icon = Icons.Outlined.Terminal,
                label = stringResource(R.string.onboarding_terminal_label),
                description = stringResource(R.string.onboarding_terminal_description),
            )
            SetupPathSummary(
                icon = Icons.Filled.PhoneAndroid,
                label = stringResource(R.string.onboarding_bridge_label),
                description = stringResource(R.string.onboarding_bridge_description),
            )
            SetupPathSummary(
                icon = Icons.Filled.GraphicEq,
                label = stringResource(R.string.onboarding_realtime_label),
                description = stringResource(R.string.onboarding_realtime_description),
            )
            OutlinedButton(
                onClick = onOpenPermissions,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.onboarding_review_permissions))
            }
        }
    }
}

@Composable
private fun ConnectPage(
    connectionViewModel: ConnectionViewModel,
    onComplete: () -> Unit,
    onManageSignIn: () -> Unit,
    onSkip: () -> Unit,
    onTryDemo: () -> Unit = {},
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
            onTryDemo = onTryDemo,
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
