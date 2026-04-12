package com.hermesandroid.relay.ui.screens

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
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.ui.theme.gradientBorder
import com.hermesandroid.relay.viewmodel.ConnectionViewModel

/**
 * Dedicated Chat settings screen. Hosts message length, attachment size,
 * tool display mode, chat endpoint preference, show-reasoning toggle,
 * smooth auto-scroll, and related chat behavior knobs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
) {
    val showThinkingSetting by connectionViewModel.showThinking.collectAsState()
    val toolDisplay by connectionViewModel.toolDisplay.collectAsState()
    val appContextEnabled by connectionViewModel.appContextEnabled.collectAsState()
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
                        val serverCaps by connectionViewModel.serverCapabilities.collectAsState()
                        val resolvedHelp = when (streamingEndpoint) {
                            "auto" -> {
                                val resolved = serverCaps.preferredChatEndpoint()
                                "Auto: picks the best path based on what your server exposes. " +
                                        "Currently using: $resolved" +
                                        if (!serverCaps.sessionsChatStream && serverCaps.sessionsApi)
                                            " (sessions browse via /api/sessions, chat via /v1/runs)"
                                        else ""
                            }
                            "sessions" -> "Sessions: tool calls shown as inline text annotations."
                            "runs" -> "Runs: structured tool events with real-time progress cards."
                            else -> ""
                        }
                        Text(
                            text = resolvedHelp,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        val endpointOptions = listOf("auto", "sessions", "runs")
                        val endpointLabels = listOf("Auto", "Sessions", "Runs")
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
        }
    }
}
