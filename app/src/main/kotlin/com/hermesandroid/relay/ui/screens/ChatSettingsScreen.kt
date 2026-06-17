package com.hermesandroid.relay.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.network.GatewayAvailability
import com.hermesandroid.relay.ui.theme.gradientBorder
import com.hermesandroid.relay.viewmodel.ConnectionViewModel

/**
 * Dedicated Chat settings screen. Hosts message length, attachment size,
 * chat endpoint preference, smooth auto-scroll, and related chat behavior knobs.
 * Reasoning and tool-progress visibility are server display settings managed
 * through the Manage tab so Android matches desktop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
) {
    val appContextEnabled by connectionViewModel.appContextEnabled.collectAsState()
    // === PHASE3-status: granular phone-status sub-toggles ===
    val appContextBridgeState by connectionViewModel.appContextBridgeState.collectAsState()
    val appContextCurrentApp by connectionViewModel.appContextCurrentApp.collectAsState()
    val appContextBattery by connectionViewModel.appContextBattery.collectAsState()
    val appContextSafetyStatus by connectionViewModel.appContextSafetyStatus.collectAsState()
    // === END PHASE3-status ===
    val parseToolAnnotations by connectionViewModel.parseToolAnnotations.collectAsState()
    val streamingEndpoint by connectionViewModel.streamingEndpoint.collectAsState()
    val maxAttachmentMb by connectionViewModel.maxAttachmentMb.collectAsState()
    val maxMessageLength by connectionViewModel.maxMessageLength.collectAsState()

    val isDarkTheme = isSystemInDarkTheme()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat") },
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
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .gradientBorder(
                        shape = RoundedCornerShape(12.dp),
                        isDarkTheme = isDarkTheme
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Smooth auto-scroll toggle
                    val smoothAutoScroll by connectionViewModel.smoothAutoScroll.collectAsState()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Smooth auto-scroll",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Follow new messages while streaming. Scroll up to pause; tap the arrow or scroll back to the bottom to resume.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = smoothAutoScroll,
                            onCheckedChange = { connectionViewModel.setSmoothAutoScroll(it) }
                        )
                    }

                    HorizontalDivider()

                    val closeDrawerOnSend by connectionViewModel.closeDrawerOnSend.collectAsState()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Close sessions on send",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Return to the conversation after sending from the session drawer. Off keeps the drawer open for session triage.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = closeDrawerOnSend,
                            onCheckedChange = { connectionViewModel.setCloseDrawerOnSend(it) }
                        )
                    }

                    HorizontalDivider()

                    val keepComposerFocusedOnSend by
                        connectionViewModel.keepComposerFocusedOnSend.collectAsState()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Keep keyboard open on send",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Stay in the composer after sending. Turn off to dismiss the keyboard after each sent message.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = keepComposerFocusedOnSend,
                            onCheckedChange = {
                                connectionViewModel.setKeepComposerFocusedOnSend(it)
                            }
                        )
                    }

                    HorizontalDivider()

                    // Turn-complete notification toggle. First enable on
                    // API 33+ runs the POST_NOTIFICATIONS request (the
                    // BridgeScreen master-toggle precedent); if the user
                    // denies, the notifier silently no-ops at post time.
                    val notifyTurnComplete by connectionViewModel.notifyTurnComplete.collectAsState()
                    val settingsContext = LocalContext.current
                    val notifyPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { /* Notifier re-checks the grant at post time. */ }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Notify when Hermes finishes",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Post a notification when a reply completes while the app is in the background",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = notifyTurnComplete,
                            onCheckedChange = { enabled ->
                                connectionViewModel.setNotifyTurnComplete(enabled)
                                if (enabled &&
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    androidx.core.content.ContextCompat.checkSelfPermission(
                                        settingsContext,
                                        android.Manifest.permission.POST_NOTIFICATIONS,
                                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                                ) {
                                    notifyPermissionLauncher.launch(
                                        android.Manifest.permission.POST_NOTIFICATIONS
                                    )
                                }
                            }
                        )
                    }

                    HorizontalDivider()

                    // === PHASE3-status: granular phone-status prompt block ===
                    // Master toggle — hides every sub-toggle and the preview
                    // card when off. Privacy-sensitive sub-toggles default off.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Share phone status with agent",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Include a short system message about the app and phone on every chat turn",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = appContextEnabled,
                            onCheckedChange = { connectionViewModel.setAppContext(it) }
                        )
                    }

                    AnimatedVisibility(visible = appContextEnabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Sub-toggle: bridge state
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Bridge + permissions",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "Whether accessibility, screen capture, overlay, notifications are granted",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = appContextBridgeState,
                                    onCheckedChange = { connectionViewModel.setAppContextBridgeState(it) }
                                )
                            }

                            // Sub-toggle: current app (privacy-sensitive, default off)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Foreground app",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "The package name of whatever app is currently visible. Off by default.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = appContextCurrentApp,
                                    onCheckedChange = { connectionViewModel.setAppContextCurrentApp(it) }
                                )
                            }

                            // Sub-toggle: battery (privacy-sensitive, default off)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Battery level",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "Current battery percent. Off by default.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = appContextBattery,
                                    onCheckedChange = { connectionViewModel.setAppContextBattery(it) }
                                )
                            }

                            // Sub-toggle: safety rails
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Safety rails",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "Blocklist count, destructive-verb count, and auto-disable timer",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = appContextSafetyStatus,
                                    onCheckedChange = { connectionViewModel.setAppContextSafetyStatus(it) }
                                )
                            }

                            // Live preview of the exact text that will be sent.
                            // Rebuilds on every toggle change via remember(key1..key5).
                            val previewText = remember(
                                appContextEnabled,
                                appContextBridgeState,
                                appContextCurrentApp,
                                appContextBattery,
                                appContextSafetyStatus,
                            ) {
                                com.hermesandroid.relay.util.buildPromptBlock(
                                    settings = com.hermesandroid.relay.util.AppContextSettings(
                                        master = appContextEnabled,
                                        bridgeState = appContextBridgeState,
                                        currentApp = appContextCurrentApp,
                                        battery = appContextBattery,
                                        safetyStatus = appContextSafetyStatus,
                                    ),
                                    // Preview uses representative placeholder values (NOT the
                                    // user's real phone state) so each enabled toggle visibly
                                    // contributes its line — an empty snapshot left the
                                    // Foreground app / Battery / Safety rails toggles looking
                                    // inert because their lines guard on snapshot data.
                                    snapshot = com.hermesandroid.relay.util.PhoneSnapshot(
                                        currentApp = "com.android.chrome",
                                        batteryPercent = 82,
                                        blocklistCount = 3,
                                        destructiveVerbCount = 5,
                                        autoDisableMinutes = 15,
                                    ),
                                )
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Preview",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = previewText ?: "(no system message will be sent)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (previewText == null)
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        else
                                            MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                    // === END PHASE3-status ===

                    HorizontalDivider()

                    val serverCaps by connectionViewModel.serverCapabilities.collectAsState()
                    val gatewayAvailability by connectionViewModel.gatewayAvailability.collectAsState()
                    val resolvedStreamingEndpoint =
                        connectionViewModel.resolveStreamingEndpoint(streamingEndpoint)

                    // Parse tool annotations toggle (text-stream endpoints only)
                    val isTextAnnotationMode = resolvedStreamingEndpoint == "sessions" ||
                        resolvedStreamingEndpoint == "completions"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (!isTextAnnotationMode) Modifier.alpha(0.5f) else Modifier),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Parse tool annotations",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Experimental",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiary,
                                    modifier = Modifier
                                        .background(
                                            color = MaterialTheme.colorScheme.tertiary,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = if (isTextAnnotationMode) {
                                    "Detect tool usage from text markers on endpoints without structured tool events."
                                } else {
                                    "Only available for text-stream endpoints — Runs should provide structured tool events."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = parseToolAnnotations && isTextAnnotationMode,
                            onCheckedChange = { connectionViewModel.setParseToolAnnotations(it) },
                            enabled = isTextAnnotationMode
                        )
                    }

                    HorizontalDivider()

                    // Streaming endpoint selector
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Streaming endpoint",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        val resolvedHelp = when (streamingEndpoint) {
                            "auto" -> {
                                "Auto: picks the best path based on what your server exposes. " +
                                        "Currently using: $resolvedStreamingEndpoint" +
                                        when {
                                            resolvedStreamingEndpoint == "gateway" ->
                                                " (live thinking via the dashboard WebSocket)"
                                            !serverCaps.sessionsChatStream && serverCaps.portable ->
                                                " (chat via /v1/chat/completions)"
                                            !serverCaps.sessionsChatStream && serverCaps.runs ->
                                                " (chat via explicitly streamed /v1/runs)"
                                            else -> ""
                                        }
                            }
                            "gateway" -> "Gateway: live thinking + rich tool events over the " +
                                "dashboard WebSocket (/api/ws) — what the desktop app uses. " +
                                "Requires Manage sign-in; falls back to SSE per turn when unavailable."
                            "sessions" -> "Sessions: Hermes-native /api/sessions/{id}/chat/stream."
                            "completions" -> "Chat: OpenAI-compatible SSE via /v1/chat/completions."
                            "runs" -> "Runs: use only when your server streams /v1/runs directly."
                            else -> ""
                        }
                        Text(
                            text = resolvedHelp,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (gatewayAvailability == GatewayAvailability.SignInRequired &&
                            (streamingEndpoint == "gateway" || streamingEndpoint == "auto")
                        ) {
                            Text(
                                text = "Sign in via the Manage tab to enable Gateway streaming " +
                                    "(live thinking).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }

                        val endpointOptions = listOf("auto", "gateway", "sessions", "completions", "runs")
                        val endpointLabels = listOf("Auto", "Gateway", "Sessions", "Chat", "Runs")
                        val selectedEndpointIndex = endpointOptions.indexOf(streamingEndpoint).coerceAtLeast(0)

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            endpointOptions.forEachIndexed { index, option ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = endpointOptions.size
                                    ),
                                    onClick = { connectionViewModel.setStreamingEndpoint(option) },
                                    selected = index == selectedEndpointIndex,
                                    // Drop the default check icon — with 5 segments its
                                    // reserved width pushed "Gateway"/"Sessions" onto a
                                    // second line. Selection still reads via the fill.
                                    icon = {},
                                ) {
                                    Text(
                                        text = endpointLabels[index],
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    // Keep connected in background — opt-in, both flavors.
                    run {
                        val gatewayKeepAlive by connectionViewModel.gatewayKeepAlive.collectAsState()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Keep connected in background",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Hold the chat connection open while the app is in the " +
                                        "background via a persistent notification, so replies stay " +
                                        "instant. Uses more battery; off by default.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = gatewayKeepAlive,
                                onCheckedChange = { connectionViewModel.setGatewayKeepAlive(it) }
                            )
                        }

                        HorizontalDivider()
                    }

                    // Limits — expandable
                    var limitsExpanded by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { limitsExpanded = !limitsExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Limits",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Icon(
                            imageVector = if (limitsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (limitsExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    AnimatedVisibility(visible = limitsExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Max attachment size
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Max attachment size",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "${maxAttachmentMb} MB",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                val attachmentOptions = listOf(1, 5, 10, 25, 50)
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    attachmentOptions.forEachIndexed { index, mb ->
                                        SegmentedButton(
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = index,
                                                count = attachmentOptions.size
                                            ),
                                            onClick = { connectionViewModel.setMaxAttachmentMb(mb) },
                                            selected = mb == maxAttachmentMb
                                        ) {
                                            Text("${mb}MB", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }

                            // Max message length
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Max message length",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "${maxMessageLength} chars",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                val lengthOptions = listOf(1024, 2048, 4096, 8192, 16384)
                                val lengthLabels = listOf("1K", "2K", "4K", "8K", "16K")
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    lengthOptions.forEachIndexed { index, len ->
                                        SegmentedButton(
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = index,
                                                count = lengthOptions.size
                                            ),
                                            onClick = { connectionViewModel.setMaxMessageLength(len) },
                                            selected = len == maxMessageLength
                                        ) {
                                            Text(lengthLabels[index], style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
