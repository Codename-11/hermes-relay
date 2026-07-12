package com.hermesandroid.relay.ui.screens

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.hermesandroid.relay.ui.theme.LocalBrand
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.upstream.ServerCapabilities
import com.hermesandroid.relay.ui.components.ChatTransportStatus
import com.hermesandroid.relay.ui.components.DotMatrixIndicator
import com.hermesandroid.relay.ui.components.StreamingDots
import com.hermesandroid.relay.ui.components.ThinkingMatrixColor
import com.hermesandroid.relay.ui.components.ThinkingMatrixPattern
import com.hermesandroid.relay.ui.components.toColor
import com.hermesandroid.relay.ui.components.ChatTransportTier
import com.hermesandroid.relay.ui.components.ChatTransportTone
import com.hermesandroid.relay.ui.components.resolveChatTransportStatus
import com.hermesandroid.relay.ui.components.sourceBadge
import com.hermesandroid.relay.ui.components.textColor
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
    val showSystemMessages by connectionViewModel.showSystemMessages.collectAsState()
    val streamingEndpoint by connectionViewModel.streamingEndpoint.collectAsState()
    val maxAttachmentMb by connectionViewModel.maxAttachmentMb.collectAsState()
    val maxMessageLength by connectionViewModel.maxMessageLength.collectAsState()

    val isDarkTheme = LocalBrand.current.isDark

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_chat)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_settings_back),
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
            // (Quick Controls — Persistent connection etc. — moved to the
            // top-level Settings landing, since they're connection-level controls
            // flipped frequently, not chat-specific. See SettingsScreen's
            // QuickControlsCard.)
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
                                text = stringResource(R.string.chat_settings_smooth_auto_scroll),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.chat_settings_smooth_auto_scroll_desc),
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

                    // Thinking indicator style — the in-bubble "working"
                    // animation shown while a reply streams. Live preview on the
                    // right reflects the current choice.
                    val thinkingIndicatorStyle by
                        connectionViewModel.thinkingIndicatorStyle.collectAsState()
                    val thinkingMatrixPattern by
                        connectionViewModel.thinkingMatrixPattern.collectAsState()
                    val thinkingMatrixColor by
                        connectionViewModel.thinkingMatrixColor.collectAsState()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.chat_settings_thinking_indicator),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(R.string.chat_settings_thinking_indicator_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box(
                                modifier = Modifier.padding(start = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (thinkingIndicatorStyle == "matrix") {
                                    DotMatrixIndicator(
                                        color = ThinkingMatrixColor.fromKey(thinkingMatrixColor)
                                            .toColor(autoColor = MaterialTheme.colorScheme.onSurface),
                                        pattern = ThinkingMatrixPattern.fromKey(thinkingMatrixPattern),
                                    )
                                } else {
                                    StreamingDots(color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        val styleOptions = listOf("dots", "matrix")
                        val styleLabels = listOf(stringResource(R.string.chat_settings_dots), stringResource(R.string.chat_settings_matrix))
                        val selectedStyleIndex =
                            styleOptions.indexOf(thinkingIndicatorStyle).coerceAtLeast(0)
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            styleOptions.forEachIndexed { index, option ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = styleOptions.size
                                    ),
                                    onClick = { connectionViewModel.setThinkingIndicatorStyle(option) },
                                    selected = index == selectedStyleIndex
                                ) {
                                    Text(
                                        text = styleLabels[index],
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }

                        // Matrix-only: which authored motion the grid plays.
                        AnimatedVisibility(visible = thinkingIndicatorStyle == "matrix") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.chat_settings_pattern),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val patternOptions = ThinkingMatrixPattern.entries
                                val selectedPatternIndex = patternOptions
                                    .indexOfFirst { it.key == thinkingMatrixPattern }
                                    .coerceAtLeast(0)
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    patternOptions.forEachIndexed { index, p ->
                                        SegmentedButton(
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = index,
                                                count = patternOptions.size
                                            ),
                                            onClick = {
                                                connectionViewModel.setThinkingMatrixPattern(p.key)
                                            },
                                            selected = index == selectedPatternIndex,
                                            // Drop the check icon — with 4 segments its
                                            // reserved width crunches the labels.
                                            icon = {},
                                        ) {
                                            Text(
                                                text = p.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }
                                    }
                                }

                                // Color — Auto (match text) + brand-accent swatches.
                                // Accents come from the active theme, so the same
                                // choice re-themes (e.g. Amber → bronze in Ember).
                                Text(
                                    text = stringResource(R.string.chat_settings_color),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ThinkingMatrixColor.entries.forEach { choice ->
                                        val swatch = choice.toColor(
                                            autoColor = MaterialTheme.colorScheme.onSurface
                                        )
                                        val selected = choice.key == thinkingMatrixColor
                                        Box(
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clip(CircleShape)
                                                .background(swatch)
                                                .border(
                                                    width = if (selected) 2.dp else 1.dp,
                                                    color = if (selected) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.outlineVariant
                                                    },
                                                    shape = CircleShape
                                                )
                                                .clickable {
                                                    connectionViewModel.setThinkingMatrixColor(choice.key)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            // Mark Auto so it doesn't read as a literal color swatch.
                                            if (choice == ThinkingMatrixColor.Auto) {
                                                Text(
                                                    text = "A",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.surface
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
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
                                text = stringResource(R.string.chat_settings_close_sessions_on_send),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.chat_settings_close_sessions_on_send_desc),
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

                    // Session sources — hide noisy gateway lanes from the drawer.
                    // Edits the same persisted set the drawer source filter uses;
                    // lists the common externals so you can hide one even before
                    // it appears in the list.
                    val hiddenSources by connectionViewModel.hiddenSources.collectAsState()
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.chat_settings_session_sources),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.chat_settings_session_sources_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        listOf("discord", "telegram", "cron", "webhook", "web").forEach { src ->
                            val badge = sourceBadge(src)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = badge?.label ?: src.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = badge?.color ?: MaterialTheme.colorScheme.onSurface,
                                )
                                Switch(
                                    checked = src !in hiddenSources,
                                    onCheckedChange = { show ->
                                        connectionViewModel.setSourceHidden(src, !show)
                                    },
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    val recentPromptsEnabled by
                        connectionViewModel.chatRecentPromptsEnabled.collectAsState()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.chat_settings_recent_prompt_chips),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.chat_settings_recent_prompt_chips_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = recentPromptsEnabled,
                            onCheckedChange = { connectionViewModel.setChatRecentPromptsEnabled(it) }
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
                                text = stringResource(R.string.chat_settings_keep_keyboard_open),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.chat_settings_keep_keyboard_open_desc),
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
                                text = stringResource(R.string.chat_settings_notify_when_finishes),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.chat_settings_notify_when_finishes_desc),
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
                                text = stringResource(R.string.chat_settings_share_phone_status),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.chat_settings_share_phone_status_desc),
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
                                        text = stringResource(R.string.chat_settings_bridge_permissions),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = stringResource(R.string.chat_settings_bridge_permissions_desc),
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
                                        text = stringResource(R.string.chat_settings_foreground_app),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = stringResource(R.string.chat_settings_foreground_app_desc),
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
                                        text = stringResource(R.string.chat_settings_battery_level),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = stringResource(R.string.chat_settings_battery_level_desc),
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
                                        text = stringResource(R.string.chat_settings_safety_rails),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = stringResource(R.string.chat_settings_safety_rails_desc),
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
                                        text = stringResource(R.string.chat_settings_preview),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = previewText ?: stringResource(R.string.chat_settings_no_system_message),
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
                    val transportStatus = remember(
                        streamingEndpoint,
                        gatewayAvailability,
                        serverCaps,
                    ) {
                        resolveChatTransportStatus(
                            streamingEndpoint = streamingEndpoint,
                            gatewayAvailability = gatewayAvailability,
                            serverCapabilities = serverCaps,
                        )
                    }

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
                                    text = stringResource(R.string.chat_settings_parse_tool_annotations),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(R.string.chat_settings_experimental),
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
                                    stringResource(R.string.chat_settings_parse_tool_annotations_desc)
                                } else {
                                    stringResource(R.string.chat_settings_parse_tool_annotations_note)
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

                    // Show system messages (debug) — render the server's hidden
                    // role:system steering markers ("[System: …]" model /
                    // personality-change notes). Off by default for desktop/TUI
                    // parity; on to inspect what the server injects.
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
                                Text(
                                    text = stringResource(R.string.chat_settings_show_system_messages),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(R.string.chat_settings_debug),
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
                                text = stringResource(R.string.chat_settings_show_system_messages_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = showSystemMessages,
                            onCheckedChange = { connectionViewModel.setShowSystemMessages(it) }
                        )
                    }

                    HorizontalDivider()

                    // Streaming endpoint selector
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.chat_settings_streaming_endpoint),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        val resolvedHelp = when (streamingEndpoint) {
                            "auto" -> {
                                stringResource(R.string.chat_settings_streaming_endpoint_auto_prefix) +
                                        resolvedStreamingEndpoint +
                                        when {
                                            resolvedStreamingEndpoint == "gateway" ->
                                                stringResource(R.string.chat_settings_gateway_suffix)
                                            !serverCaps.sessionsChatStream && serverCaps.portable ->
                                                stringResource(R.string.chat_settings_chat_completions_suffix)
                                            !serverCaps.sessionsChatStream && serverCaps.runs ->
                                                stringResource(R.string.chat_settings_runs_suffix)
                                            else -> ""
                                        }
                            }
                            "gateway" -> stringResource(R.string.chat_settings_gateway_desc)
                            "sessions" -> stringResource(R.string.chat_settings_sessions_desc)
                            "completions" -> stringResource(R.string.chat_settings_chat_desc)
                            "runs" -> stringResource(R.string.chat_settings_runs_desc)
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
                            GatewaySignInCallout()
                        }

                        TransportTierLadder(
                            status = transportStatus,
                            serverCapabilities = serverCaps,
                            gatewayAvailability = gatewayAvailability,
                        )

                        val endpointOptions = listOf("auto", "gateway", "sessions", "completions", "runs")
                        val endpointLabels = listOf(stringResource(R.string.chat_settings_auto), stringResource(R.string.chat_settings_gateway), stringResource(R.string.chat_settings_sessions), stringResource(R.string.settings_chat), stringResource(R.string.chat_settings_runs))
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
                            text = stringResource(R.string.chat_settings_limits),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Icon(
                            imageVector = if (limitsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (limitsExpanded) stringResource(R.string.chat_settings_collapse) else stringResource(R.string.chat_settings_expand),
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
                                        text = stringResource(R.string.chat_settings_max_attachment_size),
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
                                        text = stringResource(R.string.chat_settings_max_message_length),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        text = "${maxMessageLength} chars",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                val lengthOptions = listOf(1024, 2048, 4096, 8192, 16384)
                                val lengthLabels = listOf(stringResource(R.string.chat_settings_1k), stringResource(R.string.chat_settings_2k), stringResource(R.string.chat_settings_4k), stringResource(R.string.chat_settings_8k), stringResource(R.string.chat_settings_16k))
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

@Composable
private fun TransportTierLadder(
    status: ChatTransportStatus,
    serverCapabilities: ServerCapabilities,
    gatewayAvailability: GatewayAvailability,
) {
    val tiers = listOf(
        ChatTransportTier.Completions,
        ChatTransportTier.Runs,
        ChatTransportTier.Sessions,
        ChatTransportTier.Gateway,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tiers.forEach { tier ->
                TransportTierCell(
                    tier = tier,
                    active = status.tier == tier,
                    available = tierAvailable(
                        tier = tier,
                        serverCapabilities = serverCapabilities,
                        gatewayAvailability = gatewayAvailability,
                    ),
                    fallback = status.tier == tier && status.tone == ChatTransportTone.Fallback,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            text = status.reason,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = status.textColor(),
        )
    }
}

@Composable
private fun TransportTierCell(
    tier: ChatTransportTier,
    active: Boolean,
    available: Boolean,
    fallback: Boolean,
    modifier: Modifier = Modifier,
) {
    val contentColor = when {
        active && fallback -> MaterialTheme.colorScheme.onTertiaryContainer
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        available -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f)
    }
    val containerColor = when {
        active && fallback -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f)
        active -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        available -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.28f)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(7.dp),
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(
            width = 1.dp,
            color = when {
                active && fallback -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.62f)
                active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.62f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f)
            },
        ),
    ) {
        Text(
            text = when (tier) {
                ChatTransportTier.Gateway -> stringResource(R.string.chat_settings_gateway)
                ChatTransportTier.Sessions -> stringResource(R.string.chat_settings_sessions)
                ChatTransportTier.Completions -> stringResource(R.string.chat_settings_completions)
                ChatTransportTier.Runs -> stringResource(R.string.chat_settings_runs)
                ChatTransportTier.Offline -> stringResource(R.string.chat_settings_offline)
            },
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (active) {
                FontWeight.Bold
            } else {
                FontWeight.Medium
            }),
            color = contentColor,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun GatewaySignInCallout() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.58f),
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.chat_settings_gateway_needs_signin),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = stringResource(R.string.chat_settings_gateway_needs_signin_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun tierAvailable(
    tier: ChatTransportTier,
    serverCapabilities: ServerCapabilities,
    gatewayAvailability: GatewayAvailability,
): Boolean =
    when (tier) {
        ChatTransportTier.Completions -> serverCapabilities.healthy && serverCapabilities.portable
        ChatTransportTier.Runs -> serverCapabilities.healthy && serverCapabilities.runs
        ChatTransportTier.Sessions -> serverCapabilities.healthy && serverCapabilities.sessionsChatStream
        ChatTransportTier.Gateway -> gatewayAvailability == GatewayAvailability.Ready
        ChatTransportTier.Offline -> false
    }
