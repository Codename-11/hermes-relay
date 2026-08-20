package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.bridge.BridgeCapability
import com.hermesandroid.relay.bridge.BridgeCapabilityPolicy

@Composable
fun BridgeAgentAccessCard(
    policy: BridgeCapabilityPolicy,
    unattendedEnabled: Boolean,
    nowMs: Long,
    onSetUp: () -> Unit,
    onManage: () -> Unit,
    onAllowScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasGrant = policy.hasAnyGrant(nowMs)
    val preset = policy.displayPreset()
    val timed = policy.activeTimedCapabilities(nowMs)
    val nextExpiry = policy.timedExpiriesMs.filterValues { it > nowMs }.values.maxOrNull()
    val screenActive = timed.isNotEmpty()
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.bridge_access_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                AccessStatePill(
                    text = when (preset) {
                        BridgeAccessPreset.READ_ONLY -> stringResource(R.string.bridge_access_preset_read_only)
                        BridgeAccessPreset.READ_CONFIRMED -> stringResource(R.string.bridge_access_preset_confirmed_short)
                        BridgeAccessPreset.CUSTOM -> stringResource(R.string.bridge_access_preset_custom)
                        null -> stringResource(R.string.bridge_access_not_set_up)
                    },
                )
            }
            Text(
                text = stringResource(R.string.bridge_access_summary_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!hasGrant) {
                Button(onClick = onSetUp, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bridge_access_set_up))
                }
            } else {
                AccessSummaryRow(
                    title = stringResource(R.string.bridge_access_always),
                    subtitle = stringResource(
                        R.string.bridge_access_enabled_count,
                        policy.permanentGrants.size,
                        BridgeCapability.entries.count { !it.timed },
                    ),
                    trailing = policy.permanentGrants.size.toString(),
                    onClick = onManage,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                AccessSummaryRow(
                    title = stringResource(R.string.bridge_access_screen),
                    subtitle = if (screenActive) {
                        stringResource(R.string.bridge_access_screen_active)
                    } else {
                        stringResource(R.string.bridge_access_screen_off)
                    },
                    trailing = nextExpiry?.let { formatRemaining(it - nowMs) }
                        ?: stringResource(R.string.bridge_access_allow_duration),
                    onClick = onAllowScreen,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                AccessSummaryRow(
                    title = stringResource(R.string.unattended_title),
                    subtitle = if (screenActive) {
                        if (unattendedEnabled) {
                            stringResource(R.string.bridge_access_unattended_on)
                        } else {
                            stringResource(R.string.bridge_access_unattended_available)
                        }
                    } else {
                        stringResource(R.string.bridge_access_unattended_needs_screen)
                    },
                    trailing = if (unattendedEnabled && screenActive) {
                        stringResource(R.string.bmt_on)
                    } else {
                        stringResource(R.string.bmt_off)
                    },
                    onClick = onManage,
                )
            }
        }
    }
}

@Composable
fun BridgeAndroidAccessSummaryCard(
    summary: BridgeAndroidAccessSummary,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (summary.allReady) Icons.Filled.CheckCircle else Icons.Filled.Security,
                contentDescription = null,
                tint = if (summary.allReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.bridge_android_access_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        summary.required.isEmpty() -> stringResource(R.string.bridge_android_access_none)
                        summary.allReady -> stringResource(R.string.bridge_android_access_ready)
                        else -> stringResource(
                            R.string.bridge_android_access_missing,
                            summary.ready.size,
                            summary.required.size,
                            summary.missing.size,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (expanded) {
                    stringResource(R.string.bridge_android_access_hide)
                } else {
                    stringResource(R.string.bridge_android_access_review)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
fun BridgeSelectedAndroidAccessCard(
    summary: BridgeAndroidAccessSummary,
    onOpenAccessibility: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenOverlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (summary.missing.isEmpty()) return
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.bridge_android_selected_needs),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(R.string.bridge_android_selected_needs_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            summary.missing.forEach { requirement ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(requirement.labelResource()),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(
                        onClick = when (requirement) {
                            BridgeAndroidRequirement.ACCESSIBILITY -> onOpenAccessibility
                            BridgeAndroidRequirement.OVERLAY -> onOpenOverlay
                            else -> onOpenAppSettings
                        },
                    ) {
                        Text(stringResource(R.string.bridge_android_open_settings))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeAccessSetupSheet(
    selected: BridgeAccessPreset,
    onSelected: (BridgeAccessPreset) -> Unit,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(600.dp)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.bridge_access_choose_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.bridge_access_choose_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PresetChoice(
                    title = stringResource(R.string.bridge_access_preset_read_only),
                    description = stringResource(R.string.bridge_access_preset_read_only_desc),
                    selected = selected == BridgeAccessPreset.READ_ONLY,
                    recommended = true,
                    onClick = { onSelected(BridgeAccessPreset.READ_ONLY) },
                )
                PresetChoice(
                    title = stringResource(R.string.bridge_access_preset_confirmed),
                    description = stringResource(R.string.bridge_access_preset_confirmed_desc),
                    selected = selected == BridgeAccessPreset.READ_CONFIRMED,
                    onClick = { onSelected(BridgeAccessPreset.READ_CONFIRMED) },
                )
                PresetChoice(
                    title = stringResource(R.string.bridge_access_preset_custom),
                    description = stringResource(R.string.bridge_access_preset_custom_desc),
                    selected = selected == BridgeAccessPreset.CUSTOM,
                    onClick = { onSelected(BridgeAccessPreset.CUSTOM) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.bridge_cancel)) }
                    Button(onClick = onContinue) { Text(stringResource(R.string.bridge_access_continue)) }
                }
                Spacer(Modifier.size(4.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeTimedAccessSheet(
    inspectEnabled: Boolean,
    controlEnabled: Boolean,
    durationMinutes: Int,
    accessibilityReady: Boolean,
    overlayReady: Boolean,
    currentlyActive: Boolean,
    onInspectChanged: (Boolean) -> Unit,
    onControlChanged: (Boolean) -> Unit,
    onDurationChanged: (Int) -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onDismiss: () -> Unit,
    onAllow: () -> Unit,
    onEndNow: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(680.dp)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.bridge_timed_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.bridge_timed_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TimedChoice(
                    title = stringResource(R.string.bss_capability_screen_inspection),
                    description = stringResource(R.string.bridge_timed_inspection_desc),
                    checked = inspectEnabled,
                    onCheckedChange = onInspectChanged,
                )
                TimedChoice(
                    title = stringResource(R.string.bss_capability_screen_control),
                    description = stringResource(R.string.bridge_timed_control_desc),
                    checked = controlEnabled,
                    onCheckedChange = onControlChanged,
                )
                Text(
                    stringResource(R.string.bridge_timed_ends_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 30, 120).forEach { minutes ->
                        FilterChip(
                            selected = durationMinutes == minutes,
                            onClick = { onDurationChanged(minutes) },
                            label = { Text(formatDuration(minutes)) },
                        )
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.bridge_timed_prerequisites),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        PrerequisiteRow(
                            stringResource(R.string.bpc_accessibility),
                            accessibilityReady,
                            onOpenAccessibility,
                        )
                        if (controlEnabled) {
                            PrerequisiteRow(
                                stringResource(R.string.bpc_overlay),
                                overlayReady,
                                onOpenOverlay,
                            )
                        }
                        Text(
                            stringResource(R.string.bridge_timed_capture_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (currentlyActive) {
                        TextButton(onClick = onEndNow) {
                            Text(stringResource(R.string.bridge_timed_end_now))
                        }
                    } else {
                        Spacer(Modifier.size(1.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.bridge_cancel)) }
                        Button(
                            onClick = onAllow,
                            enabled = (inspectEnabled || controlEnabled) &&
                                accessibilityReady && (!controlEnabled || overlayReady),
                        ) {
                            Text(stringResource(R.string.bridge_timed_allow))
                        }
                    }
                }
                Spacer(Modifier.size(4.dp))
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}

@Composable
private fun AccessSummaryRow(
    title: String,
    subtitle: String,
    trailing: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(trailing, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun PresetChoice(
    title: String,
    description: String,
    selected: Boolean,
    recommended: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { role = Role.RadioButton }
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (recommended) {
                    Text(
                        stringResource(R.string.bridge_access_recommended),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimedChoice(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PrerequisiteRow(label: String, ready: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(label, modifier = Modifier.padding(start = 8.dp).weight(1f))
        Text(
            if (ready) stringResource(R.string.bridge_android_ready_short)
            else stringResource(R.string.bridge_android_missing_short),
            style = MaterialTheme.typography.labelLarge,
            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.bridge_android_open_settings),
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun AccessStatePill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun formatRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) / 1_000L).toInt()
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatDuration(minutes: Int): String =
    if (minutes == 120) "2 hr" else "$minutes min"

private fun BridgeAndroidRequirement.labelResource(): Int = when (this) {
    BridgeAndroidRequirement.ACCESSIBILITY -> R.string.bpc_accessibility
    BridgeAndroidRequirement.CONTACTS -> R.string.bpc_contacts
    BridgeAndroidRequirement.LOCATION -> R.string.bpc_location
    BridgeAndroidRequirement.SMS -> R.string.bpc_sms
    BridgeAndroidRequirement.PHONE -> R.string.bpc_phone
    BridgeAndroidRequirement.OVERLAY -> R.string.bpc_overlay
}
