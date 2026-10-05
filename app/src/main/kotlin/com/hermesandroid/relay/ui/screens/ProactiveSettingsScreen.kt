package com.hermesandroid.relay.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hermesandroid.relay.R
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.FcmClientConfig
import com.hermesandroid.relay.data.FcmClientConfigParser
import com.hermesandroid.relay.viewmodel.ConnectionViewModel

/**
 * "Threads" — the opt-in surface that lets the agent proactively
 * message this phone (the `phone` Hermes platform). Off by default.
 *
 * Phase 1d ships just the enablement toggle + notification-permission prompt.
 * Phase 3 expands this same screen with quiet hours / DND, per-profile scope,
 * and rate limiting (backed by `ProactivePreferences`).
 *
 * Delivery requires three things, surfaced here so the user understands why
 * nothing arrives if one is missing:
 *  1. This toggle ON (sends `proactive.subscribe` to the relay).
 *  2. A paired relay session (the push rides the existing phone WSS).
 *  3. The server admin enabling the platform (`PHONE_ENABLED`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProactiveSettingsScreen(
    connectionViewModel: ConnectionViewModel,
    onOpenChat: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val enabled by connectionViewModel.proactiveEnabled.collectAsState()
    val authState by connectionViewModel.authState.collectAsState()
    val paired = authState is AuthState.Paired

    // The notifier no-ops without POST_NOTIFICATIONS, so there's nothing to do
    // with the grant result — requesting it when the user opts in is the whole
    // point (so messages actually surface).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result handled implicitly — notifier gates on the live permission */ }

    fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.proactive_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.proactive_back),
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProactiveSectionCard(title = stringResource(R.string.proactive_let_hermes_message_me)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.proactive_allow_proactive_messages),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.proactive_allow_proactive_messages_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            connectionViewModel.setProactiveEnabled(checked)
                            if (checked) requestNotifPermissionIfNeeded()
                        },
                    )
                }

                if (enabled && !paired) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.proactive_not_paired_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            ProactiveSectionCard(title = stringResource(R.string.proactive_your_threads)) {
                Text(
                    text = stringResource(R.string.proactive_your_threads_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onOpenChat,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Message,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.proactive_open_chat))
                }
            }

            ProactiveSectionCard(title = stringResource(R.string.proactive_about)) {
                Text(
                    text = stringResource(R.string.proactive_about_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.proactive_about_server_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (connectionViewModel.fcmSupported) {
                FcmByoSection(connectionViewModel = connectionViewModel, paired = paired)
            }
        }
    }
}

@Composable
private fun FcmByoSection(
    connectionViewModel: ConnectionViewModel,
    paired: Boolean,
) {
    val prefs by connectionViewModel.fcmPushPreferences.collectAsState()
    val status by connectionViewModel.fcmStatusMessage.collectAsState()
    var projectId by remember(prefs.config.projectId) { mutableStateOf(prefs.config.projectId) }
    var applicationId by remember(prefs.config.applicationId) {
        mutableStateOf(prefs.config.applicationId)
    }
    var apiKey by remember(prefs.config.apiKey) { mutableStateOf(prefs.config.apiKey) }
    var gcmSenderId by remember(prefs.config.gcmSenderId) {
        mutableStateOf(prefs.config.gcmSenderId)
    }
    var pasteJson by remember { mutableStateOf("") }
    var enabled by remember(prefs.enabled) { mutableStateOf(prefs.enabled) }

    ProactiveSectionCard(title = stringResource(R.string.proactive_fcm_title)) {
        Text(
            text = stringResource(R.string.proactive_fcm_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.proactive_fcm_enable),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pasteJson,
            onValueChange = { pasteJson = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.proactive_fcm_paste_json)) },
            minLines = 3,
            maxLines = 8,
        )
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = {
                val parsed = FcmClientConfigParser.parse(pasteJson)
                parsed.getOrNull()?.let { cfg ->
                    projectId = cfg.projectId
                    applicationId = cfg.applicationId
                    apiKey = cfg.apiKey
                    gcmSenderId = cfg.gcmSenderId
                }
            },
            enabled = pasteJson.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.proactive_fcm_import_json))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = projectId,
            onValueChange = { projectId = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.proactive_fcm_project_id)) },
            singleLine = true,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = applicationId,
            onValueChange = { applicationId = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.proactive_fcm_application_id)) },
            singleLine = true,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.proactive_fcm_api_key)) },
            singleLine = true,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = gcmSenderId,
            onValueChange = { gcmSenderId = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.proactive_fcm_sender_id)) },
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                connectionViewModel.saveFcmClientConfig(
                    FcmClientConfig(
                        projectId = projectId,
                        applicationId = applicationId,
                        apiKey = apiKey,
                        gcmSenderId = gcmSenderId,
                    ),
                    enabled = enabled,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.proactive_fcm_save))
        }
        TextButton(
            onClick = { connectionViewModel.clearFcmClientConfig() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.proactive_fcm_clear))
        }
        if (prefs.lastToken.isNotBlank()) {
            Text(
                text = stringResource(
                    R.string.proactive_fcm_token_status,
                    prefs.lastToken.take(12) + "…",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (status.isNotBlank()) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (enabled && !paired) {
            Text(
                text = stringResource(R.string.proactive_fcm_not_paired),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = stringResource(R.string.proactive_fcm_mac_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProactiveSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
