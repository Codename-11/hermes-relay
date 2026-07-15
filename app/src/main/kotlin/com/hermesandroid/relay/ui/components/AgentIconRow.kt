package com.hermesandroid.relay.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.compose.ui.res.stringResource
import com.hermesandroid.relay.R
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import java.io.File

/**
 * Per-profile agent-icon picker — the visual twin of the local-name (alias) row.
 * The chosen image is copied into app storage and shown beside the agent's name
 * in chat. Client-side only: never sent to Hermes. Keyed per `(connection,
 * profile)` by [ConnectionViewModel.setProfileIcon] / `ProfileIconStore`.
 */
@Composable
fun AgentIconRow(connectionViewModel: ConnectionViewModel) {
    val iconPath by connectionViewModel.profileIcon.collectAsState()
    val hostImportState by connectionViewModel.hostProfileIconImportState.collectAsState()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { connectionViewModel.setProfileIcon(it) } }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.agent_icon_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val path = iconPath
                if (!path.isNullOrBlank()) {
                    AsyncImage(
                        model = File(path),
                        contentDescription = stringResource(R.string.agent_icon_title),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            OutlinedButton(onClick = { launcher.launch(arrayOf("image/*")) }) {
                Text(if (iconPath.isNullOrBlank()) stringResource(R.string.agent_icon_set) else stringResource(R.string.agent_icon_change))
            }
            if (!iconPath.isNullOrBlank()) {
                TextButton(onClick = { connectionViewModel.clearProfileIcon() }) {
                    Text(stringResource(R.string.agent_icon_clear))
                }
            }
        }
        OutlinedButton(
            onClick = { connectionViewModel.importProfileIconFromHost() },
            enabled = !hostImportState.loading,
        ) {
            Text(
                if (hostImportState.loading) {
                    stringResource(R.string.agent_icon_importing_host)
                } else {
                    stringResource(R.string.agent_icon_import_host)
                }
            )
        }
        hostImportState.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = stringResource(R.string.agent_icon_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
