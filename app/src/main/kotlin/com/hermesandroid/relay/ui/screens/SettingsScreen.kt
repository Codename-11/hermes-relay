package com.hermesandroid.relay.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import com.hermesandroid.relay.data.FeatureFlags
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.gradientBorder
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.network.ConnectionState
import com.hermesandroid.relay.ui.components.ConnectionStatusRow
import com.hermesandroid.relay.ui.components.QrPairingScanner
import com.hermesandroid.relay.ui.components.StatsForNerds
import com.hermesandroid.relay.ui.components.WhatsNewDialog
import com.hermesandroid.relay.viewmodel.ConnectionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    connectionViewModel: ConnectionViewModel
) {
    val relayConnectionState by connectionViewModel.relayConnectionState.collectAsState()
    val authState by connectionViewModel.authState.collectAsState()
    val apiServerUrl by connectionViewModel.apiServerUrl.collectAsState()
    val apiReachable by connectionViewModel.apiServerReachable.collectAsState()
    val relayUrl by connectionViewModel.relayUrl.collectAsState()
    val pairingCode by connectionViewModel.pairingCode.collectAsState()
    val theme by connectionViewModel.theme.collectAsState()
    val insecureMode by connectionViewModel.insecureMode.collectAsState()
    val isInsecureConnection by connectionViewModel.isInsecureConnection.collectAsState()
    val showThinkingSetting by connectionViewModel.showThinking.collectAsState()
    val toolDisplay by connectionViewModel.toolDisplay.collectAsState()
    val appContextEnabled by connectionViewModel.appContextEnabled.collectAsState()
    val parseToolAnnotations by connectionViewModel.parseToolAnnotations.collectAsState()
    val streamingEndpoint by connectionViewModel.streamingEndpoint.collectAsState()
    val maxAttachmentMb by connectionViewModel.maxAttachmentMb.collectAsState()
    val maxMessageLength by connectionViewModel.maxMessageLength.collectAsState()

    val isDarkTheme = isSystemInDarkTheme()

    var apiUrlInput by remember(apiServerUrl) { mutableStateOf(apiServerUrl) }
    var apiKeyInput by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var relayUrlInput by remember(relayUrl) { mutableStateOf(relayUrl) }
    var isTesting by remember { mutableStateOf(false) }
    var showWhatsNew by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Feature flags
    val devOptionsUnlocked by FeatureFlags.devOptionsUnlocked(context).collectAsState(initial = FeatureFlags.isDevBuild)
    val relayEnabled by FeatureFlags.relayEnabled(context).collectAsState(initial = FeatureFlags.isDevBuild)

    // Tap-to-unlock developer options (tap version 7 times)
    var versionTapCount by remember { mutableStateOf(0) }
    var lastTapTime by remember { mutableStateOf(0L) }

    // Camera permission launcher for QR scanning
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showQrScanner = true
        } else {
            Toast.makeText(context, "Camera permission needed to scan QR codes", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        TopAppBar(
            title = { Text("Settings") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // API Server section
            Text(
                text = "API Server",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

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
                    // API Server URL
                    OutlinedTextField(
                        value = apiUrlInput,
                        onValueChange = { apiUrlInput = it },
                        label = { Text("API Server URL") },
                        placeholder = { Text("http://your-server:8642") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // API Key (optional — most local setups don't need one)
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("API Key (optional)") },
                        placeholder = { Text("Leave empty if not configured") },
                        supportingText = {
                            Text("Only needed if Hermes is configured with API_SERVER_KEY")
                        },
                        singleLine = true,
                        visualTransformation = if (apiKeyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    imageVector = if (apiKeyVisible) {
                                        Icons.Filled.VisibilityOff
                                    } else {
                                        Icons.Filled.Visibility
                                    },
                                    contentDescription = if (apiKeyVisible) "Hide" else "Show"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Save & Test + Scan QR buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                connectionViewModel.updateApiServerUrl(apiUrlInput)
                                // Only update API key if user entered a value (don't erase stored key with empty input)
                                if (apiKeyInput.isNotBlank()) {
                                    connectionViewModel.updateApiKey(apiKeyInput)
                                }
                                isTesting = true
                                connectionViewModel.testApiConnection { success ->
                                    isTesting = false
                                    Toast.makeText(
                                        context,
                                        if (success) "API server reachable" else "Cannot reach API server",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            enabled = apiUrlInput.isNotBlank() && !isTesting
                        ) {
                            Text("Save & Test")
                        }

                        OutlinedButton(
                            onClick = {
                                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Scan QR")
                        }

                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }

                    HorizontalDivider()

                    // API status
                    ConnectionStatusRow(
                        label = "API Server",
                        isConnected = apiReachable,
                        statusText = if (apiReachable) "Reachable" else "Unreachable"
                    )
                }
            }

            // Chat section
            Text(
                text = "Chat",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

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
                    // Show reasoning toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Show reasoning",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Display the AI's thinking process above responses",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = showThinkingSetting,
                            onCheckedChange = { connectionViewModel.setShowThinking(it) }
                        )
                    }

                    HorizontalDivider()

                    // Tool call display mode
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Tool call display",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "How tool calls (file reads, searches, etc.) appear in chat",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val toolDisplayOptions = listOf("off", "compact", "detailed")
                        val toolDisplayLabels = listOf("Off", "Compact", "Detailed")
                        val selectedToolIndex = toolDisplayOptions.indexOf(toolDisplay).coerceAtLeast(0)

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            toolDisplayOptions.forEachIndexed { index, option ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = toolDisplayOptions.size
                                    ),
                                    onClick = { connectionViewModel.setToolDisplay(option) },
                                    selected = index == selectedToolIndex
                                ) {
                                    Text(toolDisplayLabels[index])
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    // App context prompt toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "App context prompt",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Tell the agent you're on mobile for concise responses",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = appContextEnabled,
                            onCheckedChange = { connectionViewModel.setAppContext(it) }
                        )
                    }

                    HorizontalDivider()

                    // Parse tool annotations toggle (Sessions mode only)
                    val isSessionsMode = streamingEndpoint == "sessions"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (!isSessionsMode) Modifier.alpha(0.5f) else Modifier),
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
                                text = if (isSessionsMode) {
                                    "Detect tool usage from text markers. May delay message display until stream completes."
                                } else {
                                    "Only available in Sessions mode — Runs already provides structured tool events"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = parseToolAnnotations && isSessionsMode,
                            onCheckedChange = { connectionViewModel.setParseToolAnnotations(it) },
                            enabled = isSessionsMode
                        )
                    }

                    HorizontalDivider()

                    // Streaming endpoint selector
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Streaming endpoint",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Sessions: tool calls shown as inline text annotations. Runs: structured tool events with real-time progress cards.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val endpointOptions = listOf("sessions", "runs")
                        val endpointLabels = listOf("Sessions", "Runs")
                        val selectedEndpointIndex = endpointOptions.indexOf(streamingEndpoint).coerceAtLeast(0)

                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            endpointOptions.forEachIndexed { index, option ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = endpointOptions.size
                                    ),
                                    onClick = { connectionViewModel.setStreamingEndpoint(option) },
                                    selected = index == selectedEndpointIndex
                                ) {
                                    Text(endpointLabels[index])
                                }
                            }
                        }
                    }

                    HorizontalDivider()

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

            // Relay Server section (gated behind feature flag)
            if (relayEnabled) {
            Text(
                text = "Relay Server",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

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
                    Text(
                        text = "Used for Bridge and Terminal features",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Relay URL
                    OutlinedTextField(
                        value = relayUrlInput,
                        onValueChange = { relayUrlInput = it },
                        label = { Text("Relay URL") },
                        placeholder = { Text("wss://your-server:8767") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Connect/Disconnect buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val canConnect = relayConnectionState == ConnectionState.Disconnected &&
                            (relayUrlInput.startsWith("wss://") ||
                                (insecureMode && relayUrlInput.startsWith("ws://")))
                        Button(
                            onClick = {
                                connectionViewModel.connectRelay(relayUrlInput)
                            },
                            enabled = canConnect
                        ) {
                            Text("Connect")
                        }
                        OutlinedButton(
                            onClick = { connectionViewModel.disconnectRelay() },
                            enabled = relayConnectionState != ConnectionState.Disconnected
                        ) {
                            Text("Disconnect")
                        }
                    }

                    HorizontalDivider()

                    // Relay connection status
                    ConnectionStatusRow(
                        label = "Relay",
                        isConnected = relayConnectionState == ConnectionState.Connected,
                        isConnecting = relayConnectionState == ConnectionState.Connecting ||
                            relayConnectionState == ConnectionState.Reconnecting,
                        statusText = when (relayConnectionState) {
                            ConnectionState.Connected -> "Connected"
                            ConnectionState.Connecting -> "Connecting..."
                            ConnectionState.Reconnecting -> "Reconnecting..."
                            ConnectionState.Disconnected -> "Disconnected"
                        }
                    )

                    // Insecure connection warning
                    if (isInsecureConnection && relayConnectionState == ConnectionState.Connected) {
                        HorizontalDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Insecure connection — traffic is not encrypted",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    HorizontalDivider()

                    // Insecure mode toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Allow insecure connections",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Enable ws:// and http:// for local dev/testing only",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = insecureMode,
                            onCheckedChange = { connectionViewModel.setInsecureMode(it) }
                        )
                    }
                }
            }

            } // end if (relayEnabled) — Relay Server section

            // Pairing section (gated behind feature flag)
            if (relayEnabled) {
            Text(
                text = "Pairing",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

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
                    Text(
                        text = "Used for relay authentication (Bridge/Terminal)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Pairing code display
                    Text(
                        text = "Pairing Code",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = pairingCode,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = MaterialTheme.typography.headlineMedium.fontSize * 0.15
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(pairingCode))
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy pairing code"
                            )
                        }

                        IconButton(onClick = {
                            connectionViewModel.regeneratePairingCode()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Generate new code"
                            )
                        }
                    }

                    HorizontalDivider()

                    // Session token status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Session:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = when (authState) {
                                is AuthState.Paired -> "Paired"
                                is AuthState.Pairing -> "Pairing..."
                                is AuthState.Unpaired -> "Unpaired"
                                is AuthState.Failed -> "Failed: ${(authState as AuthState.Failed).reason}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (authState) {
                                is AuthState.Paired -> MaterialTheme.colorScheme.primary
                                is AuthState.Failed -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    if (authState is AuthState.Paired) {
                        OutlinedButton(onClick = { connectionViewModel.clearSession() }) {
                            Text("Clear Session")
                        }
                    }
                }
            }

            } // end if (relayEnabled) — Pairing section

            // Theme section
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

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
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val themeOptions = listOf("auto", "light", "dark")
                    val themeLabels = listOf("Auto", "Light", "Dark")
                    val selectedIndex = themeOptions.indexOf(theme).coerceAtLeast(0)

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        themeOptions.forEachIndexed { index, option ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = themeOptions.size
                                ),
                                onClick = { connectionViewModel.setTheme(option) },
                                selected = index == selectedIndex
                            ) {
                                Text(themeLabels[index])
                            }
                        }
                    }
                }
            }

            // Animation section
            Text(
                text = "Animation",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

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
                val animEnabled by connectionViewModel.animationEnabled.collectAsState()
                val animBehindChat by connectionViewModel.animationBehindChat.collectAsState()

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Animation enabled toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ASCII sphere",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Show animated sphere on empty chat screen and ambient mode",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = animEnabled,
                            onCheckedChange = { connectionViewModel.setAnimationEnabled(it) }
                        )
                    }

                    HorizontalDivider()

                    // Behind chat toggle
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (!animEnabled) Modifier.alpha(0.5f) else Modifier),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Behind messages",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Show subtle sphere animation behind chat messages",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = animBehindChat && animEnabled,
                            onCheckedChange = { connectionViewModel.setAnimationBehindChat(it) },
                            enabled = animEnabled
                        )
                    }
                }
            }

            // Data Management section
            Text(
                text = "Data",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            DataManagementSection(connectionViewModel = connectionViewModel)

            // Stats for Nerds section
            Text(
                text = "Stats for Nerds",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            StatsForNerds()

            // About section
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

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
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Logo
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1A1A2E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = "Hermes Relay",
                            modifier = Modifier.size(80.dp)
                        )
                    }

                    Text(
                        text = "Hermes Relay",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Native Android client for Hermes Agent",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Version info (dynamic)
                    val versionName = remember {
                        try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
                        } catch (_: Exception) { "—" }
                    }
                    val versionCode = remember {
                        try {
                            @Suppress("DEPRECATION")
                            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toString()
                        } catch (_: Exception) { "—" }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (devOptionsUnlocked) return@clickable
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime > 2000) {
                                    versionTapCount = 1
                                } else {
                                    versionTapCount++
                                }
                                lastTapTime = now
                                val remaining = 7 - versionTapCount
                                when {
                                    remaining <= 0 -> {
                                        scope.launch { FeatureFlags.unlockDevOptions(context) }
                                        Toast.makeText(context, "Developer options unlocked", Toast.LENGTH_SHORT).show()
                                        versionTapCount = 0
                                    }
                                    remaining <= 3 -> {
                                        Toast.makeText(context, "$remaining taps to unlock developer options", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Version",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "$versionName ($versionCode)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    HorizontalDivider()

                    // Links
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Codename-11/hermes-relay"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_github),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("GitHub")
                        }
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://codename-11.github.io/hermes-relay/"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("App Docs")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://hermes-agent.nousresearch.com"))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Code,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Hermes Docs")
                        }
                    }

                    // Privacy policy link
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Codename-11/hermes-relay/blob/main/docs/privacy.md"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Privacy Policy")
                    }

                    // What's New
                    TextButton(onClick = { showWhatsNew = true }) {
                        Text("What's New in This Version")
                    }

                    // Credits
                    Text(
                        text = "Axiom Labs \u2764\uFE0F Hermes Agent · Nous Research",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Developer Options section (only visible when unlocked)
            if (devOptionsUnlocked) {
                Text(
                    text = "Developer Options",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )

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
                        // Relay features toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Science,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.tertiary
                                    )
                                    Text(
                                        text = "Relay features",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Text(
                                    text = "Show Relay Server and Pairing settings for Bridge/Terminal development",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = relayEnabled,
                                onCheckedChange = { scope.launch { FeatureFlags.setRelayEnabled(context, it) } }
                            )
                        }

                        HorizontalDivider()

                        // Lock developer options
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Lock developer options",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "Hide this section and disable experimental features",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                scope.launch { FeatureFlags.lockDevOptions(context) }
                                Toast.makeText(context, "Developer options locked", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = "Lock developer options"
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // What's New dialog
    if (showWhatsNew) {
        WhatsNewDialog(onDismiss = { showWhatsNew = false })
    }

    // QR Scanner overlay
    if (showQrScanner) {
        QrPairingScanner(
            onPairingDetected = { payload ->
                apiUrlInput = payload.serverUrl
                apiKeyInput = payload.key
                showQrScanner = false

                // Auto-save and test
                connectionViewModel.updateApiServerUrl(payload.serverUrl)
                connectionViewModel.updateApiKey(payload.key)
                isTesting = true
                connectionViewModel.testApiConnection { success ->
                    isTesting = false
                    Toast.makeText(
                        context,
                        if (success) "Paired — server reachable" else "Paired — but server unreachable",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onDismiss = { showQrScanner = false }
        )
    }
}

@Composable
private fun DataManagementSection(connectionViewModel: ConnectionViewModel) {
    val isDarkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }
    var backupJson by remember { mutableStateOf<String?>(null) }

    // SAF file picker for export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && backupJson != null) {
            connectionViewModel.writeBackupToUri(uri, backupJson!!) { success ->
                Toast.makeText(
                    context,
                    if (success) "Settings exported" else "Export failed",
                    Toast.LENGTH_SHORT
                ).show()
                backupJson = null
            }
        }
    }

    // SAF file picker for import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            connectionViewModel.importFromUri(uri) { success ->
                Toast.makeText(
                    context,
                    if (success) "Settings imported" else "Import failed — invalid file",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Reset Onboarding
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Reset Onboarding",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Show the setup guide again on next launch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    connectionViewModel.resetOnboarding()
                    Toast.makeText(context, "Onboarding will show on next launch", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = "Reset onboarding"
                    )
                }
            }

            HorizontalDivider()

            // Export Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Export Settings",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Save settings to a file (no tokens or API keys)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    connectionViewModel.exportSettings { json ->
                        backupJson = json
                        exportLauncher.launch("hermes-relay-backup.json")
                    }
                }) {
                    Icon(
                        imageVector = Icons.Filled.FileDownload,
                        contentDescription = "Export settings"
                    )
                }
            }

            // Import Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Import Settings",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Restore settings from a backup file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    importLauncher.launch(arrayOf("application/json"))
                }) {
                    Icon(
                        imageVector = Icons.Filled.FileUpload,
                        contentDescription = "Import settings"
                    )
                }
            }

            HorizontalDivider()

            // Reset All Data
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Reset All Data",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Clear all settings, tokens, API keys, and cached data",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showResetDialog = true }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Reset all data",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    // Confirmation dialog for data reset
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset All Data?") },
            text = { Text("This will clear all settings, API keys, authentication tokens, and cached data. You'll need to reconfigure your API server and re-pair with your relay. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        connectionViewModel.resetAppData()
                        Toast.makeText(context, "App data reset", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
