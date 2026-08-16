package com.hermesandroid.relay.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.hermesandroid.relay.R
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import java.io.File

/** Shared Hermes identity and a separately-scoped phone presentation override. */
@Composable
fun AgentIconRow(connectionViewModel: ConnectionViewModel) {
    val localIconPath by connectionViewModel.localProfileIcon.collectAsState()
    val serverAvatarPath by connectionViewModel.serverProfileAvatar.collectAsState()
    val useLocalOverride by connectionViewModel.useLocalProfileIconOverride.collectAsState()
    val hostImportState by connectionViewModel.hostProfileIconImportState.collectAsState()
    val sharedState by connectionViewModel.sharedProfileAvatarState.collectAsState()
    var confirmSharedRemoval by remember { mutableStateOf(false) }

    val sharedLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let(connectionViewModel::setSharedProfileAvatar) }
    val localLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let(connectionViewModel::setProfileIcon) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.agent_icon_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )

        AvatarSourceCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AvatarPreview(serverAvatarPath)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.agent_icon_shared_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = if (serverAvatarPath.isNullOrBlank()) {
                            stringResource(R.string.agent_icon_shared_empty)
                        } else {
                            stringResource(R.string.agent_icon_shared_active)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        sharedLauncher.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
                    },
                    enabled = !sharedState.loading,
                ) {
                    Text(stringResource(R.string.agent_icon_change_shared))
                }
                if (!serverAvatarPath.isNullOrBlank()) {
                    TextButton(
                        onClick = { confirmSharedRemoval = true },
                        enabled = !sharedState.loading,
                    ) {
                        Text(
                            text = stringResource(R.string.agent_icon_remove_shared),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        AvatarSourceCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AvatarPreview(localIconPath)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.agent_icon_phone_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.agent_icon_phone_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = useLocalOverride,
                    onCheckedChange = connectionViewModel::setUseLocalProfileIconOverride,
                )
            }
            OutlinedButton(
                onClick = { localLauncher.launch(arrayOf("image/*")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = useLocalOverride,
            ) {
                Text(
                    if (localIconPath.isNullOrBlank()) {
                        stringResource(R.string.agent_icon_choose_phone)
                    } else {
                        stringResource(R.string.agent_icon_change_phone)
                    },
                )
            }
            OutlinedButton(
                onClick = connectionViewModel::importProfileIconFromHost,
                modifier = Modifier.fillMaxWidth(),
                enabled = useLocalOverride && !hostImportState.loading,
            ) {
                Text(
                    if (hostImportState.loading) {
                        stringResource(R.string.agent_icon_importing_host)
                    } else {
                        stringResource(R.string.agent_icon_import_host)
                    },
                )
            }
            if (!localIconPath.isNullOrBlank()) {
                TextButton(
                    onClick = connectionViewModel::clearProfileIcon,
                    enabled = useLocalOverride,
                ) {
                    Text(stringResource(R.string.agent_icon_remove_phone))
                }
            }
            Text(
                text = stringResource(R.string.agent_icon_animation_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        hostImportState.error?.let { AvatarError(it) }
        sharedState.error?.let { AvatarError(it) }
    }

    if (confirmSharedRemoval) {
        AlertDialog(
            onDismissRequest = { confirmSharedRemoval = false },
            title = { Text(stringResource(R.string.agent_icon_remove_shared_title)) },
            text = { Text(stringResource(R.string.agent_icon_remove_shared_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmSharedRemoval = false
                        connectionViewModel.clearSharedProfileAvatar()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.agent_icon_remove_shared),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSharedRemoval = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun AvatarSourceCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun AvatarPreview(path: String?) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!path.isNullOrBlank()) {
            AsyncImage(
                model = File(path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun AvatarError(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}
