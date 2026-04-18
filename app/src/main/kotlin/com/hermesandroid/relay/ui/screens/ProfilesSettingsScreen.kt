package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.data.Profile
import java.util.concurrent.TimeUnit

/**
 * Full-screen manager for the list of connection profiles. Reachable via
 * Settings → Profiles and via the "Manage profiles…" button in the profile
 * switcher bottom sheet.
 *
 * Each profile is a card with:
 *  - Label (tappable → rename dialog)
 *  - Hostname + paired status subtitle
 *  - Re-pair / Revoke / Remove actions
 *  - "Active" badge + tonal highlight on the currently-active profile
 *
 * An Extended FAB pinned bottom-right launches the add-profile pairing flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesSettingsScreen(
    profiles: List<Profile>,
    activeProfileId: String?,
    onRenameProfile: (id: String, newLabel: String) -> Unit,
    onRepairProfile: (id: String) -> Unit,
    onRevokeProfile: (id: String) -> Unit,
    onRemoveProfile: (id: String) -> Unit,
    onAddProfile: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profiles") },
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddProfile,
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                    )
                },
                text = { Text("Add profile") },
            )
        },
    ) { innerPadding ->
        if (profiles.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "No profiles yet",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Pair with a Hermes server to add your first profile.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        isActive = profile.id == activeProfileId,
                        onRename = { newLabel -> onRenameProfile(profile.id, newLabel) },
                        onRepair = { onRepairProfile(profile.id) },
                        onRevoke = { onRevokeProfile(profile.id) },
                        onRemove = { onRemoveProfile(profile.id) },
                    )
                }
                // Footer spacer so the last card isn't hidden by the FAB.
                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: Profile,
    isActive: Boolean,
    onRename: (String) -> Unit,
    onRepair: () -> Unit,
    onRevoke: () -> Unit,
    onRemove: () -> Unit,
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showRevokeConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isActive) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(
                            text = "Active",
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
            }

            val hostname = Profile.extractDefaultLabel(profile.apiServerUrl)
            val pairedStatus = profile.pairedAt?.let { formatPairedRelative(it) }
                ?: "Not paired"
            Text(
                text = "$hostname • $pairedStatus",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = { showRenameDialog = true }) {
                    Text("Rename")
                }
                TextButton(onClick = onRepair) {
                    Text("Re-pair")
                }
                TextButton(onClick = { showRevokeConfirm = true }) {
                    Text("Revoke")
                }
                TextButton(onClick = { showRemoveConfirm = true }) {
                    Text(
                        text = "Remove",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (showRenameDialog) {
        RenameProfileDialog(
            initialLabel = profile.label,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newLabel ->
                onRename(newLabel)
                showRenameDialog = false
            },
        )
    }

    if (showRevokeConfirm) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirm = false },
            title = { Text("Revoke this profile?") },
            text = {
                Text(
                    "The server session will be invalidated. The profile stays " +
                        "on this device but will need to be re-paired to use again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRevoke()
                        showRevokeConfirm = false
                    },
                ) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text("Remove this profile?") },
            text = {
                Text(
                    "The profile will be deleted from this device along with its " +
                        "saved session token. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove()
                        showRemoveConfirm = false
                    },
                ) {
                    Text(
                        text = "Remove",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun RenameProfileDialog(
    initialLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initialLabel) }
    // Validate on every keystroke so the Save button disables + the
    // supporting text appears without needing a failed submit first.
    val validationError = com.hermesandroid.relay.data.ProfileValidation.validateLabel(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename profile") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("Label") },
                isError = validationError != null && value.isNotEmpty(),
                supportingText = {
                    // Only show the error message once the user has typed
                    // *something* — starting with the initial value we don't
                    // want the dialog to appear with a red "can't be blank"
                    // on first open.
                    if (validationError != null && value.isNotEmpty()) {
                        Text(validationError)
                    }
                },
            )
        },
        confirmButton = {
            TextButton(
                enabled = validationError == null,
                onClick = { onConfirm(value.trim()) },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Coarse-grained relative time — "Paired 3d ago". We don't need exact
 * precision here, and rolling our own avoids adding android.text.format or
 * ThreeTenABP dependencies just for one subtitle.
 */
private fun formatPairedRelative(pairedAtMillis: Long): String {
    val deltaMs = System.currentTimeMillis() - pairedAtMillis
    if (deltaMs < 0) return "Paired"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMs)
    val hours = TimeUnit.MILLISECONDS.toHours(deltaMs)
    val days = TimeUnit.MILLISECONDS.toDays(deltaMs)
    return when {
        minutes < 1L -> "Paired just now"
        minutes < 60L -> "Paired ${minutes}m ago"
        hours < 24L -> "Paired ${hours}h ago"
        days < 30L -> "Paired ${days}d ago"
        else -> "Paired ${days / 30L}mo ago"
    }
}
