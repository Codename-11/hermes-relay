package com.hermesandroid.relay.ui.screens

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.SupervisedAttachmentCategory
import com.hermesandroid.relay.data.SupervisedModePolicy
import com.hermesandroid.relay.data.SupervisedSessionActions
import com.hermesandroid.relay.data.SupervisedVisibilityPreset
import com.hermesandroid.relay.ui.components.avatar.LocalAvailablePets
import com.hermesandroid.relay.ui.components.avatar.SphereAvatar
import com.hermesandroid.relay.ui.theme.AppThemes
import com.hermesandroid.relay.ui.theme.ThemeMode
import com.hermesandroid.relay.ui.mayEnableSupervisedMode
import com.hermesandroid.relay.ui.theme.LocalBrand
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
import com.hermesandroid.relay.ui.theme.gradientBorder
import com.hermesandroid.relay.viewmodel.ConnectionViewModel

/**
 * The settings surface available while supervised mode is locked.
 *
 * This is a separate allowlisted composition rather than a filtered copy of
 * [SettingsScreen]. New full-settings categories therefore stay unavailable
 * until they are deliberately added here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisedSettingsScreen(
    connectionViewModel: ConnectionViewModel,
    policy: SupervisedModePolicy,
    onPolicyChange: (SupervisedModePolicy) -> Unit,
    onBack: (() -> Unit)?,
    onParentAccessGranted: () -> Unit,
) {
    val context = LocalContext.current
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val effectiveProfile by connectionViewModel.effectiveDisplayProfile.collectAsState()
    val profileAlias by connectionViewModel.profileDisplayAlias.collectAsState()
    val fontScale by connectionViewModel.fontScale.collectAsState()
    val isDarkTheme = LocalBrand.current.isDark
    var authError by remember { mutableStateOf<String?>(null) }
    var showAbout by remember { mutableStateOf(false) }

    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            authError = null
            onParentAccessGranted()
        }
    }

    fun requestParentAccess() {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val intent = keyguard?.createConfirmDeviceCredentialIntent(
            "Parent access",
            "Unlock full Hermes settings and supervised-mode controls. Device credentials verify an enrolled device user, not a distinct parent identity.",
        )
        if (intent == null) {
            authError = "Set a device screen lock before using parent access."
        } else {
            credentialLauncher.launch(intent)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                title = { Text("Settings") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val pinnedProfile = effectiveProfile?.takeIf {
                it.name.equals(policy.pinnedProfileName, ignoreCase = true)
            }
            val agentName = AgentDisplay.profileDisplayName(pinnedProfile)
                ?: profileAlias?.takeIf { pinnedProfile != null }
                ?: policy.pinnedProfileName?.let(::profileLabel)
                ?: "Supervised chat unavailable"
            SupervisedSummaryCard(
                agentName = agentName,
                connectionLabel = activeConnection?.label,
                policy = policy,
                isDarkTheme = isDarkTheme,
            )

            SupervisedSectionLabel("Appearance")
            SupervisedCard(isDarkTheme) {
                SupervisedThemeControls(policy, onPolicyChange)

                HorizontalDivider()
                Text("Text size", style = MaterialTheme.typography.titleSmall)
                val scaleOptions = listOf(0.85f to "Small", 1f to "Default", 1.15f to "Large", 1.3f to "Larger")
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val selectedScale = scaleOptions.indices.minByOrNull {
                        kotlin.math.abs(scaleOptions[it].first - fontScale)
                    } ?: 1
                    scaleOptions.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = index == selectedScale,
                            onClick = { connectionViewModel.setFontScale(option.first) },
                            shape = SegmentedButtonDefaults.itemShape(index, scaleOptions.size),
                        ) { Text(option.second) }
                    }
                }

            }

            if (
                policy.appearance.allowProfileIconChanges ||
                policy.appearance.allowBackgroundChanges
            ) {
                SupervisedSectionLabel("Agent look")
                SupervisedCard(isDarkTheme) {
                    SupervisedAgentLookControls(
                        connectionViewModel = connectionViewModel,
                        allowProfileIconChanges = policy.appearance.allowProfileIconChanges,
                        allowBackgroundChanges = policy.appearance.allowBackgroundChanges,
                    )
                }
            }

            SupervisedSectionLabel("Help")
            SupervisedNavigationRow(
                icon = Icons.Filled.Info,
                title = "About Hermes Relay",
                subtitle = "About this supervised client",
                onClick = { showAbout = true },
                isDarkTheme = isDarkTheme,
            )

            SupervisedSectionLabel("Parent")
            SupervisedNavigationRow(
                icon = Icons.Filled.Lock,
                title = "Parent access",
                subtitle = "Unlock full settings with the device screen lock",
                onClick = ::requestParentAccess,
                isDarkTheme = isDarkTheme,
            )
            authError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("Hermes Relay") },
            text = {
                Text(
                    "Supervised mode provides a parent-configured, restricted Android chat interface. " +
                        "The selected Hermes profile owns the agent's tool and content restrictions.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("Close") }
            },
        )
    }
}

/** Parent-only editor for the client-side supervised policy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupervisedControlsScreen(
    connectionViewModel: ConnectionViewModel,
    policy: SupervisedModePolicy,
    profiles: List<Profile>,
    onPolicyChange: (SupervisedModePolicy) -> Unit,
    onBack: () -> Unit,
    onReturnToSupervisedView: () -> Unit,
) {
    val context = LocalContext.current
    val keyguardManager = remember(context) {
        context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }
    val deviceSecure = keyguardManager?.isDeviceSecure == true
    val isDarkTheme = LocalBrand.current.isDark
    var showProfilePicker by remember { mutableStateOf(false) }
    var sessionActionsExpanded by remember { mutableStateOf(false) }
    var enableAuthError by remember { mutableStateOf<String?>(null) }
    var enableRequested by remember { mutableStateOf(false) }
    val enableCredentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val shouldEnable = enableRequested && mayEnableSupervisedMode(
            policy = policy,
            deviceSecure = deviceSecure,
            deviceCredentialConfirmed = result.resultCode == Activity.RESULT_OK,
        )
        enableRequested = false
        if (shouldEnable) {
            enableAuthError = null
            onPolicyChange(policy.copy(enabled = true))
        }
    }

    fun requestFirstEnable() {
        val intent = keyguardManager?.createConfirmDeviceCredentialIntent(
            "Enable supervised mode",
            "Confirm with an enrolled device credential. This does not verify a distinct parent identity.",
        )
        if (!deviceSecure || intent == null) {
            enableAuthError = "Set a secure device screen lock before enabling supervised mode."
            return
        }
        enableRequested = true
        enableCredentialLauncher.launch(intent)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Supervised mode") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SupervisedCard(isDarkTheme) {
                SupervisedSwitchRow(
                    title = "Use supervised mode",
                    subtitle = when {
                        policy.pinnedProfileName.isNullOrBlank() ->
                            "Choose an agent profile before enabling"
                        !deviceSecure ->
                            "Set a device screen lock before enabling"
                        else ->
                            "Show only the approved Android chat surfaces"
                    },
                    checked = policy.enabled,
                    enabled = policy.enabled ||
                        (!policy.pinnedProfileName.isNullOrBlank() && deviceSecure),
                    onCheckedChange = { enabled ->
                        if (enabled) requestFirstEnable()
                        else onPolicyChange(policy.copy(enabled = false))
                    },
                )
                enableAuthError?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider()
                SupervisedValueRow(
                    title = "Agent profile",
                    value = policy.pinnedProfileName?.let(::profileLabel) ?: "Choose profile",
                    onClick = { showProfilePicker = true },
                )
            }

            Text(
                "This mode restricts this Android client. The selected Hermes profile remains responsible for agent tools and content policy.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Android device credentials authenticate an enrolled device user; they do not establish a separate parent identity. Use a parent-only device credential or managed-device policy where that distinction matters.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SupervisedSectionLabel("Supervised appearance")
            SupervisedCard(isDarkTheme) {
                SupervisedThemeControls(policy, onPolicyChange)
                HorizontalDivider()
                SupervisedSwitchRow(
                    title = "Show pet",
                    subtitle = "Show the pet selected in the full Appearance settings",
                    checked = policy.appearance.showPet,
                    onCheckedChange = {
                        onPolicyChange(policy.copy(appearance = policy.appearance.copy(showPet = it)))
                    },
                )
                HorizontalDivider()
                SupervisedSwitchRow(
                    title = "Let supervised user change the agent icon",
                    subtitle = "The parent can always change the phone-local icon",
                    checked = policy.appearance.allowProfileIconChanges,
                    onCheckedChange = {
                        onPolicyChange(
                            policy.copy(
                                appearance = policy.appearance.copy(allowProfileIconChanges = it),
                            ),
                        )
                    },
                )
                HorizontalDivider()
                SupervisedSwitchRow(
                    title = "Let supervised user change the background",
                    subtitle = "The parent can always choose the supervised chat background",
                    checked = policy.appearance.allowBackgroundChanges,
                    onCheckedChange = {
                        onPolicyChange(
                            policy.copy(
                                appearance = policy.appearance.copy(allowBackgroundChanges = it),
                            ),
                        )
                    },
                )
            }

            SupervisedSectionLabel("Parent-set agent look")
            SupervisedCard(isDarkTheme) {
                Text(
                    "These controls remain available to the parent even when supervised-user changes are off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SupervisedAgentLookControls(
                    connectionViewModel = connectionViewModel,
                    allowProfileIconChanges = true,
                    allowBackgroundChanges = true,
                )
            }

            SupervisedSectionLabel("Allowed features")
            SupervisedCard(isDarkTheme) {
                SupervisedSwitchRow("Attachments", "Allow only the approved file types below", policy.capabilities.attachments) {
                    onPolicyChange(policy.copy(capabilities = policy.capabilities.copy(attachments = it)))
                }
                HorizontalDivider()
                SupervisedSwitchRow("Voice", "Allow standard Hermes voice", policy.capabilities.voice) {
                    onPolicyChange(policy.copy(capabilities = policy.capabilities.copy(voice = it)))
                }
                HorizontalDivider()
                SupervisedSwitchRow("Generated images", "Show images returned in chat", policy.capabilities.generatedImages) {
                    onPolicyChange(policy.copy(capabilities = policy.capabilities.copy(generatedImages = it)))
                }
                HorizontalDivider()
                SupervisedSwitchRow("Conversation history", "Allow previous chats with this agent", policy.capabilities.conversationHistory) {
                    onPolicyChange(policy.copy(capabilities = policy.capabilities.copy(conversationHistory = it)))
                }
                HorizontalDivider()
                SupervisedSwitchRow("Share generated images", "Allow Android sharing and saving", policy.capabilities.shareGeneratedImages) {
                    onPolicyChange(policy.copy(capabilities = policy.capabilities.copy(shareGeneratedImages = it)))
                }
                if (policy.capabilities.attachments) {
                    HorizontalDivider()
                    Text("Attachment count", style = MaterialTheme.typography.titleSmall)
                    val countOptions = listOf(1, 2, 4, 8)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        countOptions.forEachIndexed { index, count ->
                            SegmentedButton(
                                selected = policy.capabilities.attachmentMaxCount == count,
                                onClick = {
                                    onPolicyChange(
                                        policy.copy(
                                            capabilities = policy.capabilities.copy(
                                                attachmentMaxCount = count,
                                            ),
                                        ),
                                    )
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, countOptions.size),
                            ) { Text(count.toString()) }
                        }
                    }
                    Text("Maximum size per attachment", style = MaterialTheme.typography.titleSmall)
                    val sizeOptions = listOf(5, 10, 25, 50)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        sizeOptions.forEachIndexed { index, sizeMb ->
                            SegmentedButton(
                                selected = policy.capabilities.attachmentMaxFileMb == sizeMb,
                                onClick = {
                                    onPolicyChange(
                                        policy.copy(
                                            capabilities = policy.capabilities.copy(
                                                attachmentMaxFileMb = sizeMb,
                                            ),
                                        ),
                                    )
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, sizeOptions.size),
                            ) { Text("$sizeMb MB") }
                        }
                    }
                    Text("Attachment types", style = MaterialTheme.typography.titleSmall)
                    SupervisedAttachmentCategory.entries.forEach { category ->
                        val enabled = category in policy.capabilities.attachmentCategories
                        SupervisedSwitchRow(
                            title = category.displayLabel(),
                            checked = enabled,
                            onCheckedChange = { checked ->
                                val updated = if (checked) {
                                    policy.capabilities.attachmentCategories + category
                                } else {
                                    policy.capabilities.attachmentCategories - category
                                }
                                if (updated.isNotEmpty()) {
                                    onPolicyChange(
                                        policy.copy(
                                            capabilities = policy.capabilities.copy(
                                                attachmentCategories = updated,
                                            ),
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }

            SupervisedSectionLabel("Conversation actions")
            SupervisedCard(isDarkTheme) {
                CapabilitySwitch("New chat", policy.capabilities.newChat) {
                    onPolicyChange(policy.copy(capabilities = policy.capabilities.copy(newChat = it)))
                }
                CapabilitySwitch("Cancel response", policy.capabilities.cancelResponse) {
                    onPolicyChange(policy.copy(capabilities = policy.capabilities.copy(cancelResponse = it)))
                }
                CapabilitySwitch("Steer response", policy.capabilities.steerResponse) {
                    onPolicyChange(policy.copy(capabilities = policy.capabilities.copy(steerResponse = it)))
                }
                CapabilitySwitch("Retry response", policy.capabilities.retryResponse) {
                    onPolicyChange(policy.copy(capabilities = policy.capabilities.copy(retryResponse = it)))
                }
                CapabilitySwitch("Copy responses", policy.capabilities.copyResponses) {
                    onPolicyChange(policy.copy(capabilities = policy.capabilities.copy(copyResponses = it)))
                }
                CapabilitySwitch("Quote replies", policy.capabilities.quoteReplies) {
                    onPolicyChange(policy.copy(capabilities = policy.capabilities.copy(quoteReplies = it)))
                }
                CapabilitySwitch("Edit and resend", policy.capabilities.editAndResend, divider = false) {
                    onPolicyChange(policy.copy(capabilities = policy.capabilities.copy(editAndResend = it)))
                }
            }

            SupervisedSectionLabel("Session options")
            SupervisedCard(isDarkTheme) {
                val actions = policy.capabilities.sessionActions
                SupervisedValueRow(
                    title = "History actions",
                    value = sessionActionsSummary(actions),
                    onClick = { sessionActionsExpanded = !sessionActionsExpanded },
                )
                Text(
                    if (policy.capabilities.conversationHistory) {
                        "Choose which actions appear on previous chats. Delete still asks for confirmation."
                    } else {
                        "Selections are saved, but previous-chat actions stay unavailable until Conversation history is on."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sessionActionsExpanded) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = actions.allEnabled,
                            onClick = {
                                onPolicyChange(
                                    policy.copy(
                                        capabilities = policy.capabilities.copy(
                                            sessionActions = actions.withAll(true),
                                        ),
                                    ),
                                )
                            },
                            label = { Text("Allow all") },
                        )
                        FilterChip(
                            selected = actions.noneEnabled,
                            onClick = {
                                onPolicyChange(
                                    policy.copy(
                                        capabilities = policy.capabilities.copy(
                                            sessionActions = actions.withAll(false),
                                        ),
                                    ),
                                )
                            },
                            label = { Text("Allow none") },
                        )
                    }
                    SessionActionSwitch("Pin and unpin", actions.pin) {
                        onPolicyChange(policy.withSessionActions(actions.copy(pin = it)))
                    }
                    SessionActionSwitch("Rename", actions.rename) {
                        onPolicyChange(policy.withSessionActions(actions.copy(rename = it)))
                    }
                    SessionActionSwitch("Archive and restore", actions.archive) {
                        onPolicyChange(policy.withSessionActions(actions.copy(archive = it)))
                    }
                    SessionActionSwitch("Share transcript", actions.shareTranscript) {
                        onPolicyChange(policy.withSessionActions(actions.copy(shareTranscript = it)))
                    }
                    SessionActionSwitch("Delete", actions.delete, divider = false) {
                        onPolicyChange(policy.withSessionActions(actions.copy(delete = it)))
                    }
                }
            }

            SupervisedSectionLabel("What appears in chat")
            SupervisedCard(isDarkTheme) {
                val presets = SupervisedVisibilityPreset.entries
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    presets.forEachIndexed { index, preset ->
                        SegmentedButton(
                            selected = policy.visibility.preset == preset,
                            onClick = {
                                onPolicyChange(policy.copy(visibility = policy.visibility.copy(preset = preset)))
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, presets.size),
                        ) { Text(preset.displayLabel()) }
                    }
                }
                Text(
                    when (policy.visibility.preset) {
                        SupervisedVisibilityPreset.Simple -> "Conversation only, with minimal technical detail"
                        SupervisedVisibilityPreset.Transparent -> "Adds status, timestamps, and useful context"
                        SupervisedVisibilityPreset.Custom -> "Choose each visible surface below"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (policy.visibility.preset == SupervisedVisibilityPreset.Custom) {
                    HorizontalDivider()
                    VisibilitySwitch("Agent name and avatar", policy.visibility.showAgentIdentity) {
                        onPolicyChange(policy.copy(visibility = policy.visibility.copy(showAgentIdentity = it)))
                    }
                    VisibilitySwitch("Model name", policy.visibility.showModelName) {
                        onPolicyChange(policy.copy(visibility = policy.visibility.copy(showModelName = it)))
                    }
                    VisibilitySwitch("Profile name", policy.visibility.showProfileName) {
                        onPolicyChange(policy.copy(visibility = policy.visibility.copy(showProfileName = it)))
                    }
                    VisibilitySwitch("Connection status", policy.visibility.showConnectionStatus) {
                        onPolicyChange(policy.copy(visibility = policy.visibility.copy(showConnectionStatus = it)))
                    }
                    VisibilitySwitch("Technical route", policy.visibility.showTechnicalRoute) {
                        onPolicyChange(policy.copy(visibility = policy.visibility.copy(showTechnicalRoute = it)))
                    }
                    VisibilitySwitch("Message timestamps", policy.visibility.showTimestamps) {
                        onPolicyChange(policy.copy(visibility = policy.visibility.copy(showTimestamps = it)))
                    }
                    VisibilitySwitch("Working status", policy.visibility.showWorkingStatus) {
                        onPolicyChange(policy.copy(visibility = policy.visibility.copy(showWorkingStatus = it)))
                    }
                    VisibilitySwitch("Tool names", policy.visibility.showToolNames) {
                        onPolicyChange(policy.copy(visibility = policy.visibility.copy(showToolNames = it)))
                    }
                    VisibilitySwitch("Tool details", policy.visibility.showToolDetails) {
                        onPolicyChange(policy.copy(visibility = policy.visibility.copy(showToolDetails = it)))
                    }
                    VisibilitySwitch("Reasoning", policy.visibility.showReasoning) {
                        onPolicyChange(policy.copy(visibility = policy.visibility.copy(showReasoning = it)))
                    }
                    VisibilitySwitch("Usage", policy.visibility.showUsage, divider = false) {
                        onPolicyChange(policy.copy(visibility = policy.visibility.copy(showUsage = it)))
                    }
                }
            }

            SupervisedSectionLabel("Parent access")
            SupervisedCard(isDarkTheme) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("Device authentication", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Full features require the device screen lock. This verifies an enrolled device user, not a distinct parent identity.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                SupervisedSwitchRow(
                    title = "Relock when the app leaves the screen",
                    subtitle = "Recommended for shared devices",
                    checked = policy.parentAccess.relockOnBackground,
                    onCheckedChange = {
                        onPolicyChange(
                            policy.copy(
                                parentAccess = policy.parentAccess.copy(relockOnBackground = it),
                            ),
                        )
                    },
                )
                HorizontalDivider()
                Text("Automatic relock", style = MaterialTheme.typography.titleSmall)
                val timeoutOptions = listOf(1, 5, 15, 60)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    timeoutOptions.forEachIndexed { index, minutes ->
                        SegmentedButton(
                            selected = policy.parentAccess.timeoutMinutes == minutes,
                            onClick = {
                                onPolicyChange(
                                    policy.copy(
                                        parentAccess = policy.parentAccess.copy(
                                            timeoutMinutes = minutes,
                                        ),
                                    ),
                                )
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, timeoutOptions.size),
                        ) { Text(if (minutes == 60) "1 hr" else "$minutes min") }
                    }
                }
            }

            if (policy.enabled) {
                TextButton(
                    onClick = onReturnToSupervisedView,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                    Text("Return to supervised view", modifier = Modifier.padding(start = 8.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showProfilePicker) {
        AlertDialog(
            onDismissRequest = { showProfilePicker = false },
            title = { Text("Choose agent profile") },
            text = {
                Column {
                    profiles.forEach { profile ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPolicyChange(policy.copy(pinnedProfileName = profile.name))
                                    showProfilePicker = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = policy.pinnedProfileName == profile.name,
                                onClick = null,
                            )
                            Text(AgentDisplay.profileDisplayName(profile) ?: profileLabel(profile.name))
                        }
                    }
                    if (profiles.isEmpty()) {
                        Text("Profiles are not available yet. Connect to Hermes and try again.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showProfilePicker = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun SupervisedSummaryCard(
    agentName: String,
    connectionLabel: String?,
    policy: SupervisedModePolicy,
    isDarkTheme: Boolean,
) {
    SupervisedCard(isDarkTheme) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 12.dp)) {
                Text(agentName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                connectionLabel?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val features = buildList {
            if (policy.capabilities.attachments) add("Attachments")
            if (policy.capabilities.voice) add("Voice")
            if (policy.capabilities.generatedImages) add("Generated images")
        }
        if (features.isNotEmpty()) {
            Text(
                features.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SupervisedCard(
    isDarkTheme: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBorder(
                shape = appearanceRoundedCornerShape(12.dp),
                isDarkTheme = isDarkTheme,
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun SupervisedSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun SupervisedNavigationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    isDarkTheme: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBorder(
                shape = appearanceRoundedCornerShape(12.dp),
                isDarkTheme = isDarkTheme,
            )
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun SupervisedSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

@Composable
private fun CapabilitySwitch(
    title: String,
    checked: Boolean,
    divider: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SupervisedSwitchRow(title = title, checked = checked, onCheckedChange = onCheckedChange)
    if (divider) HorizontalDivider()
}

@Composable
private fun VisibilitySwitch(
    title: String,
    checked: Boolean,
    divider: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) = CapabilitySwitch(title, checked, divider, onCheckedChange)

@Composable
private fun SupervisedValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

private fun SupervisedVisibilityPreset.displayLabel(): String = when (this) {
    SupervisedVisibilityPreset.Simple -> "Simple"
    SupervisedVisibilityPreset.Transparent -> "Transparent"
    SupervisedVisibilityPreset.Custom -> "Custom"
}

private fun profileLabel(value: String): String = value
    .replace('_', ' ')
    .replace('-', ' ')
    .replaceFirstChar { it.uppercase() }

private fun SupervisedAttachmentCategory.displayLabel(): String = when (this) {
    SupervisedAttachmentCategory.Images -> "Images"
    SupervisedAttachmentCategory.Documents -> "Documents"
    SupervisedAttachmentCategory.Audio -> "Audio"
    SupervisedAttachmentCategory.Video -> "Video"
}

private fun SupervisedModePolicy.withSessionActions(
    actions: SupervisedSessionActions,
): SupervisedModePolicy = copy(
    capabilities = capabilities.copy(sessionActions = actions),
)

private fun sessionActionsSummary(actions: SupervisedSessionActions): String = when {
    actions.allEnabled -> "All allowed"
    actions.noneEnabled -> "None allowed"
    else -> "${actions.enabledCount} of ${SupervisedSessionActions.TOTAL} allowed"
}

@Composable
private fun SessionActionSwitch(
    title: String,
    checked: Boolean,
    divider: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) = CapabilitySwitch(title, checked, divider, onCheckedChange)

@Composable
private fun SupervisedThemeControls(
    policy: SupervisedModePolicy,
    onPolicyChange: (SupervisedModePolicy) -> Unit,
) {
    var showThemePicker by remember { mutableStateOf(false) }
    val selectedTheme = AppThemes.byId(policy.appearance.appThemeId)

    SupervisedValueRow(
        title = "Theme",
        value = selectedTheme.label,
        onClick = { showThemePicker = true },
    )
    if (selectedTheme.mode == ThemeMode.BOTH) {
        val modeOptions = listOf("auto" to "System", "light" to "Light", "dark" to "Dark")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modeOptions.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = policy.appearance.themePreference == option.first,
                    onClick = {
                        onPolicyChange(
                            policy.copy(
                                appearance = policy.appearance.copy(themePreference = option.first),
                            ),
                        )
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, modeOptions.size),
                ) { Text(option.second) }
            }
        }
    } else {
        Text(
            if (selectedTheme.mode == ThemeMode.LIGHT_ONLY) "Fixed light theme" else "Fixed dark theme",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showThemePicker) {
        AlertDialog(
            onDismissRequest = { showThemePicker = false },
            title = { Text("Choose supervised theme") },
            text = {
                Column {
                    AppThemes.ALL.forEach { theme ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPolicyChange(
                                        policy.copy(
                                            appearance = policy.appearance.copy(appThemeId = theme.id),
                                        ),
                                    )
                                    showThemePicker = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selectedTheme.id == theme.id, onClick = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(theme.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    theme.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemePicker = false }) { Text("Close") }
            },
        )
    }
}

@Composable
private fun SupervisedAgentLookControls(
    connectionViewModel: ConnectionViewModel,
    allowProfileIconChanges: Boolean,
    allowBackgroundChanges: Boolean,
) {
    val localProfileIcon by connectionViewModel.localProfileIcon.collectAsState()
    val backgroundEnabled by connectionViewModel.backgroundVisualizationEnabled.collectAsState()
    val backgroundAvatar by connectionViewModel.backgroundAvatar.collectAsState()
    val availableBackgrounds = LocalAvailablePets.current
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(connectionViewModel::setProfileIcon)
    }

    if (allowProfileIconChanges) {
        Text("Agent icon", style = MaterialTheme.typography.titleSmall)
        Text(
            if (localProfileIcon.isNullOrBlank()) {
                "Using the profile's current icon"
            } else {
                "Using a phone-local icon for this profile"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { iconPicker.launch("image/*") }) {
                Text("Choose image")
            }
            if (!localProfileIcon.isNullOrBlank()) {
                TextButton(onClick = connectionViewModel::clearProfileIcon) {
                    Text("Use profile icon")
                }
            }
        }
    }

    if (allowProfileIconChanges && allowBackgroundChanges) HorizontalDivider()

    if (allowBackgroundChanges) {
        Text("Chat background", style = MaterialTheme.typography.titleSmall)
        Text(
            "Choose from backgrounds already installed by the parent.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !backgroundEnabled,
                onClick = { connectionViewModel.setBackgroundVisualizationEnabled(false) },
                label = { Text("Off") },
            )
            FilterChip(
                selected = backgroundEnabled && backgroundAvatar == SphereAvatar.id,
                onClick = { connectionViewModel.setBackgroundAvatar(SphereAvatar.id) },
                label = { Text("Sphere") },
            )
            availableBackgrounds.forEach { avatar ->
                FilterChip(
                    selected = backgroundEnabled && backgroundAvatar == avatar.id,
                    onClick = { connectionViewModel.setBackgroundAvatar(avatar.id) },
                    label = { Text(avatar.label) },
                )
            }
        }
    }
}
