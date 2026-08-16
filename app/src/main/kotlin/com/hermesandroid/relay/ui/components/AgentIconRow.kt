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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import com.hermesandroid.relay.ui.components.avatar.PetAvatar
import com.hermesandroid.relay.ui.components.avatar.toAvatar
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
    val hermesPetState by connectionViewModel.hermesPetState.collectAsState()
    val hermesPetAvatar = remember(hermesPetState.active) { hermesPetState.active?.toAvatar() }
    var confirmSharedRemoval by remember { mutableStateOf(false) }
    var showHermesPetPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { connectionViewModel.refreshHermesPet() }

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
                HermesPetPreview(hermesPetAvatar)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.agent_icon_hermes_pet_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = when {
                            hermesPetState.supported == false -> stringResource(R.string.agent_icon_hermes_pet_unsupported)
                            hermesPetState.active != null -> stringResource(
                                R.string.agent_icon_hermes_pet_active,
                                hermesPetState.active?.displayName.orEmpty(),
                            )
                            else -> stringResource(R.string.agent_icon_hermes_pet_empty)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (hermesPetState.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else if (hermesPetState.supported != false) {
                    Switch(
                        checked = hermesPetState.active != null,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                showHermesPetPicker = true
                                connectionViewModel.loadHermesPetGallery()
                            } else {
                                connectionViewModel.disableHermesPet()
                            }
                        },
                    )
                }
            }
            if (hermesPetState.supported != false) {
                OutlinedButton(
                    onClick = {
                        showHermesPetPicker = true
                        connectionViewModel.loadHermesPetGallery()
                    },
                    enabled = !hermesPetState.loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.agent_icon_hermes_pet_browse))
                }
            }
            Text(
                text = stringResource(R.string.agent_icon_hermes_pet_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
                        sharedLauncher.launch(arrayOf("image/*"))
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
            sharedState.error?.let { AvatarError(it) }
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
        hermesPetState.error?.let { AvatarError(it) }
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

    if (showHermesPetPicker) {
        AlertDialog(
            onDismissRequest = { showHermesPetPicker = false },
            title = { Text(stringResource(R.string.agent_icon_hermes_pet_picker_title)) },
            text = {
                if (hermesPetState.galleryLoading && hermesPetState.gallery.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        if (hermesPetState.gallery.isEmpty()) {
                            item {
                                Text(
                                    text = stringResource(R.string.agent_icon_hermes_pet_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                        items(hermesPetState.gallery, key = { it.slug }) { pet ->
                            LaunchedEffect(pet.slug, pet.spritesheetUrl) {
                                connectionViewModel.loadHermesPetThumbnail(pet)
                            }
                            TextButton(
                                onClick = {
                                    showHermesPetPicker = false
                                    connectionViewModel.selectHermesPet(pet.slug)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    PetGalleryPreview(hermesPetState.thumbnails[pet.slug])
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(pet.displayName, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = when {
                                                pet.slug == hermesPetState.active?.slug -> stringResource(R.string.agent_icon_hermes_pet_selected)
                                                pet.installed -> stringResource(R.string.agent_icon_hermes_pet_installed)
                                                else -> stringResource(R.string.agent_icon_hermes_pet_adopt)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showHermesPetPicker = false }) {
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
private fun HermesPetPreview(pet: PetAvatar?) {
    Box(
        modifier = Modifier.size(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        pet?.Render(
            state = AvatarRenderState(state = SphereState.Idle),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PetGalleryPreview(dataUri: String?) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (dataUri != null) {
            AsyncImage(
                model = dataUri,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(3.dp),
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
