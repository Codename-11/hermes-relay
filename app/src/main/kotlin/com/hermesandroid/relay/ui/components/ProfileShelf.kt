package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.ProfilePresentation
import com.hermesandroid.relay.data.ProfilePresentationPolicy
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import java.io.File

data class ProfileChoice(
    val key: String,
    val profile: Profile?,
) {
    val isServerDefault: Boolean get() = key == AgentDisplay.SERVER_DEFAULT_PROFILE_KEY
}

object ProfileShelfPolicy {
    fun choices(
        profiles: List<Profile>,
        presentation: ProfilePresentation,
        selectedProfileName: String?,
        serverDefaultProfileName: String? = null,
    ): List<ProfileChoice> {
        val selectedKey = AgentDisplay.profileSessionKey(selectedProfileName)
        val choices = ProfilePresentationPolicy
            .visibleKeys(profiles, presentation, selectedKey)
            .mapNotNull { key ->
                if (key == AgentDisplay.SERVER_DEFAULT_PROFILE_KEY) {
                    ProfileChoice(key, null)
                } else {
                    profiles.firstOrNull { it.name == key }?.let { ProfileChoice(key, it) }
                }
            }
        // Group only exact upstream identities, never matching display labels.
        // Keep the selected choice's request/presentation key unchanged.
        val hasDefault = choices.any { it.isServerDefault }
        val hasResolved = choices.any { it.key == serverDefaultProfileName }
        if (!hasDefault || !hasResolved) return choices
        val omittedKey = if (selectedProfileName == serverDefaultProfileName) {
            AgentDisplay.SERVER_DEFAULT_PROFILE_KEY
        } else {
            serverDefaultProfileName
        }
        return choices.filterNot { it.key == omittedKey }
    }

    fun canSwitch(isStreaming: Boolean, streamingEndpoint: String): Boolean =
        !isStreaming || streamingEndpoint == "gateway"

    fun isSelected(choice: ProfileChoice, selectedProfileName: String?): Boolean =
        choice.key == AgentDisplay.profileSessionKey(selectedProfileName)

    /** Local presentation assets follow the selected choice key, not the sticky profile it resolves to. */
    fun iconProfileName(choice: ProfileChoice): String? = choice.profile?.name
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileShelf(
    connectionViewModel: ConnectionViewModel,
    profiles: List<Profile>,
    selectedProfile: Profile?,
    resolvedProfile: Profile?,
    presentation: ProfilePresentation,
    activeDisplayName: String,
    isProfileLocked: Boolean,
    lockedProfileName: String?,
    switchEnabled: Boolean,
    onSelect: (Profile?) -> Unit,
    onOpenPassport: () -> Unit,
    onOpenSwitcher: () -> Unit,
    onInspect: (String) -> Unit,
    onLock: (Profile?) -> Unit,
    onUnlock: () -> Unit,
    onHide: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val serverDefaultProfile by connectionViewModel.serverDefaultDisplayProfile.collectAsState()
    val choices = remember(profiles, presentation, selectedProfile?.name, serverDefaultProfile) {
        ProfileShelfPolicy.choices(profiles, presentation, selectedProfile?.name, serverDefaultProfile?.name)
    }
    if (choices.size <= 1) return

    var actionChoice by remember { mutableStateOf<ProfileChoice?>(null) }
    val lockedKey = lockedProfileName ?: AgentDisplay.SERVER_DEFAULT_PROFILE_KEY

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 55.dp)
                    .padding(start = 8.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    choices.forEach { choice ->
                        val selected = ProfileShelfPolicy.isSelected(choice, selectedProfile?.name)
                        val label = if (selected) {
                            activeDisplayName
                        } else {
                            profileChoiceLabel(choice, serverDefaultProfile)
                        }
                        if (selected) {
                            val openPassportDescription = stringResource(R.string.profile_shelf_open_passport)
                            Box(
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .widthIn(max = 190.dp)
                                    .combinedClickable(
                                        role = Role.Button,
                                        onClick = onOpenPassport,
                                        onLongClick = { actionChoice = choice },
                                    )
                                    .semantics {
                                        contentDescription = "$label. $openPassportDescription"
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(22.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        ProfileChoiceAvatar(
                                            connectionViewModel,
                                            choice,
                                            serverDefaultProfile,
                                            label,
                                            36,
                                        )
                                        Text(
                                            text = label,
                                            modifier = Modifier.weight(1f, fill = false),
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        } else {
                            val enabled = switchEnabled && !isProfileLocked
                            val switchToDescription = stringResource(R.string.profile_shelf_switch_to)
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .combinedClickable(
                                        enabled = true,
                                        role = Role.Button,
                                        onClick = { if (enabled) onSelect(choice.profile) },
                                        onLongClick = { actionChoice = choice },
                                    )
                                    .semantics {
                                        if (!enabled) disabled()
                                        contentDescription = if (enabled) {
                                            "$label. $switchToDescription"
                                        } else {
                                            label
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                ProfileChoiceAvatar(
                                    connectionViewModel,
                                    choice,
                                    serverDefaultProfile,
                                    label,
                                    36,
                                )
                            }
                        }
                    }
                }
                IconButton(
                    onClick = onOpenSwitcher,
                    modifier = Modifier.size(48.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                        ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.MoreHoriz,
                                contentDescription = stringResource(R.string.profile_shelf_all_profiles),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f))
        }
    }

    actionChoice?.let { choice ->
        val selected = ProfileShelfPolicy.isSelected(choice, selectedProfile?.name)
        val target = choice.profile ?: serverDefaultProfile
        val label = profileChoiceLabel(choice, serverDefaultProfile)
        ProfileShelfActionsDialog(
            label = label,
            canSwitch = switchEnabled && !isProfileLocked,
            selected = selected,
            canInspect = target != null,
            lockedToChoice = isProfileLocked && lockedKey == choice.key,
            onInspect = {
                actionChoice = null
                target?.name?.let(onInspect)
            },
            onPassport = {
                actionChoice = null
                if (!selected && switchEnabled && !isProfileLocked) onSelect(choice.profile)
                onOpenPassport()
            },
            onToggleLock = {
                actionChoice = null
                if (isProfileLocked && lockedKey == choice.key) onUnlock() else onLock(choice.profile)
            },
            onHide = {
                actionChoice = null
                onHide(choice.profile?.name)
            },
            onDismiss = { actionChoice = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProfileSwitcherSheet(
    connectionViewModel: ConnectionViewModel,
    profiles: List<Profile>,
    selectedProfile: Profile?,
    resolvedProfile: Profile?,
    presentation: ProfilePresentation,
    isProfileLocked: Boolean,
    switchEnabled: Boolean,
    onSelect: (Profile?) -> Unit,
    onManageDisplay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val serverDefaultProfile by connectionViewModel.serverDefaultDisplayProfile.collectAsState()
    val choices = remember(profiles, presentation, selectedProfile?.name, serverDefaultProfile) {
        ProfileShelfPolicy.choices(profiles, presentation, selectedProfile?.name, serverDefaultProfile?.name)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.profile_shelf_switch_agent),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (isProfileLocked) {
                Text(
                    text = stringResource(R.string.profile_shelf_locked_hint),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            choices.forEach { choice ->
                val selected = ProfileShelfPolicy.isSelected(choice, selectedProfile?.name)
                val label = profileChoiceLabel(choice, serverDefaultProfile)
                val target = if (choice.isServerDefault) serverDefaultProfile else choice.profile
                val defaultGroup = serverDefaultProfile != null &&
                    (choice.isServerDefault || choice.key == serverDefaultProfile?.name)
                val detail = listOfNotNull(
                    stringResource(R.string.conn_info_server_default).takeIf { defaultGroup },
                    target?.name?.takeIf { it != label },
                    target?.model?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                ListItem(
                    modifier = Modifier.selectable(
                        selected = selected,
                        enabled = selected || (switchEnabled && !isProfileLocked),
                        role = Role.RadioButton,
                        onClick = {
                            if (!selected) onSelect(choice.profile)
                            onDismiss()
                        },
                    ),
                    headlineContent = {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = if (detail.isNotBlank()) {
                        { Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    } else {
                        null
                    },
                    leadingContent = {
                        ProfileChoiceAvatar(connectionViewModel, choice, serverDefaultProfile, label, 42)
                    },
                    trailingContent = if (selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
                if (defaultGroup && selected) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(
                                value = choice.isServerDefault,
                                enabled = switchEnabled && !isProfileLocked,
                                role = Role.Checkbox,
                                onValueChange = {
                                    onSelect(if (choice.isServerDefault) serverDefaultProfile else null)
                                    onDismiss()
                                },
                            ).padding(start = 64.dp, end = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = choice.isServerDefault, onCheckedChange = null,
                            enabled = switchEnabled && !isProfileLocked)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.profile_follow_server_default),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            TextButton(
                onClick = onManageDisplay,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Filled.Tune, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.conn_info_manage_profiles))
            }
        }
    }
}

@Composable
private fun ProfileChoiceAvatar(
    connectionViewModel: ConnectionViewModel,
    choice: ProfileChoice,
    resolvedProfile: Profile?,
    label: String,
    size: Int,
) {
    val iconProfileName = ProfileShelfPolicy.iconProfileName(choice)
    val iconPath by connectionViewModel.profileIconFlow(iconProfileName).collectAsState(initial = null)
    Box(modifier = Modifier.size(size.dp)) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            ),
        ) {
            when {
                !iconPath.isNullOrBlank() -> AsyncImage(
                    model = File(iconPath.orEmpty()),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                resolvedProfile == null && choice.isServerDefault -> Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Home,
                        contentDescription = null,
                        modifier = Modifier.size((size * 0.48f).dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                else -> Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = label.trim().firstOrNull()?.uppercase() ?: "H",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (choice.isServerDefault && resolvedProfile != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size((size * 0.38f).dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surface),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Home,
                        contentDescription = null,
                        modifier = Modifier.size((size * 0.22f).dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileShelfActionsDialog(
    label: String,
    canSwitch: Boolean,
    selected: Boolean,
    canInspect: Boolean,
    lockedToChoice: Boolean,
    onInspect: () -> Unit,
    onPassport: () -> Unit,
    onToggleLock: () -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                ProfileActionRow(Icons.Filled.Visibility, stringResource(R.string.profile_shelf_inspect), canInspect, onInspect)
                ProfileActionRow(
                    Icons.Filled.Person,
                    stringResource(R.string.conn_info_agent_passport),
                    selected || canSwitch,
                    onPassport,
                )
                ProfileActionRow(
                    Icons.Filled.Lock,
                    stringResource(if (lockedToChoice) R.string.settings_unlock else R.string.settings_profile_lock),
                    true,
                    onToggleLock,
                )
                ProfileActionRow(
                    Icons.Filled.VisibilityOff,
                    stringResource(R.string.profile_shelf_hide),
                    !selected,
                    onHide,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_dismiss)) } },
    )
}

@Composable
private fun ProfileActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.size(12.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun profileChoiceLabel(choice: ProfileChoice, resolvedProfile: Profile?): String =
    if (choice.isServerDefault) {
        AgentDisplay.profileDisplayName(resolvedProfile)
            ?: stringResource(R.string.conn_info_server_default)
    } else {
        choice.profile?.let(AgentDisplay::profileDisplayName)
            ?: choice.profile?.name?.replaceFirstChar { it.uppercase() }
            ?: resolvedProfile?.let(AgentDisplay::profileDisplayName)
            ?: stringResource(R.string.chat_agent_default)
    }
