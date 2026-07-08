package com.hermesandroid.relay.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.hermesandroid.relay.ui.theme.LocalBrand
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.FeatureFlags
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.ui.components.UpdateDebugOverride
import com.hermesandroid.relay.update.UpdateStatus
import com.hermesandroid.relay.ui.theme.gradientBorder
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch

/**
 * Dedicated Developer Options screen. Gated behind the tap-version-7x
 * unlock. Hosts feature flags (voice/terminal/bridge/etc.), data management
 * (clear session / wipe caches), and any experimental toggles.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperSettingsScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onNavigateToRealtimeVoice: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDarkTheme = LocalBrand.current.isDark

    val relayEnabled by FeatureFlags.relayEnabled(context).collectAsState(initial = FeatureFlags.isDevBuild)

    // Data management local state — unfolded from the private
    // DataManagementSection helper in the old SettingsScreen.
    var showResetDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var backupJson by remember { mutableStateOf<String?>(null) }

    // SAF file picker for export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && backupJson != null) {
            connectionViewModel.writeBackupToUri(uri, backupJson!!) { success ->
                Toast.makeText(
                    context,
                    if (success) context.getString(R.string.dev_settings_exported) else context.getString(R.string.dev_settings_export_failed),
                    Toast.LENGTH_SHORT
                ).show()
                backupJson = null
            }
        }
    }

    // SAF file picker for import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            connectionViewModel.importFromUri(uri) { success ->
                Toast.makeText(
                    context,
                    if (success) context.getString(R.string.dev_settings_imported) else context.getString(R.string.dev_settings_import_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dev_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.dev_settings_back),
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
            // Data Management section
            Text(
                text = stringResource(R.string.dev_settings_data_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Reset Onboarding
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dev_settings_reset_onboarding),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.dev_settings_reset_onboarding_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            connectionViewModel.resetOnboarding()
                            Toast.makeText(context, context.getString(R.string.dev_settings_onboarding_reset_toast), Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.RestartAlt,
                                contentDescription = stringResource(R.string.dev_settings_reset_onboarding_cd)
                            )
                        }
                    }

                    HorizontalDivider()

                    // Export Settings
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dev_settings_export_settings),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.dev_settings_export_settings_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showExportDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.FileDownload,
                                contentDescription = stringResource(R.string.dev_settings_export_settings_cd)
                            )
                        }
                    }

                    // Import Settings
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dev_settings_import_settings),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.dev_settings_import_settings_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showImportDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.FileUpload,
                                contentDescription = stringResource(R.string.dev_settings_import_settings_cd)
                            )
                        }
                    }

                    HorizontalDivider()

                    // Reset All Data
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dev_settings_reset_all_data),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = stringResource(R.string.dev_settings_reset_all_data_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showResetDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.dev_settings_reset_all_data_cd),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Developer Options section
            Text(
                text = stringResource(R.string.dev_options_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary
            )

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
                    // Relay features toggle
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
                                Icon(
                                    imageVector = Icons.Filled.Science,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    text = stringResource(R.string.dev_settings_relay_features),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Text(
                                text = stringResource(R.string.dev_settings_relay_features_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = relayEnabled,
                            onCheckedChange = { scope.launch { FeatureFlags.setRelayEnabled(context, it) } }
                        )
                    }

                    HorizontalDivider()

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
                                Icon(
                                    imageVector = Icons.Filled.Science,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    text = stringResource(R.string.dev_settings_realtime_voice_lab),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Text(
                                text = stringResource(R.string.dev_settings_realtime_voice_lab_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onNavigateToRealtimeVoice) {
                            Icon(
                                imageVector = Icons.Filled.Science,
                                contentDescription = stringResource(R.string.dev_settings_open_realtime_voice_lab_cd)
                            )
                        }
                    }

                    HorizontalDivider()

                    // Lock developer options
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dev_settings_lock_dev_options),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(R.string.dev_settings_lock_dev_options_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            scope.launch { FeatureFlags.lockDevOptions(context) }
                            Toast.makeText(context, context.getString(R.string.dev_settings_locked_toast), Toast.LENGTH_SHORT).show()
                            onBack()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = stringResource(R.string.dev_settings_lock_dev_options_cd)
                            )
                        }
                    }
                }
            }

            // Test harness — debug builds only. Triggers for surfaces that
            // unit tests can't reach and that don't occur on demand (a crash, a
            // logged error, a live update). Gated by isDevBuild so it never
            // ships in a release APK.
            if (FeatureFlags.isDevBuild) {
                Text(
                    text = stringResource(R.string.dev_settings_test_harness),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .gradientBorder(
                            shape = RoundedCornerShape(12.dp),
                            isDarkTheme = isDarkTheme,
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TestHarnessRow(
                            title = stringResource(R.string.dev_settings_test_emit_diagnostics),
                            subtitle = stringResource(R.string.dev_settings_test_emit_diagnostics_desc),
                            icon = Icons.Filled.Science,
                            onClick = {
                                DiagnosticsLog.record(
                                    category = DiagnosticCategory.Api,
                                    severity = DiagnosticSeverity.Info,
                                    title = context.getString(R.string.dev_settings_sample_info_title),
                                    detail = context.getString(R.string.dev_settings_sample_info_detail),
                                )
                                DiagnosticsLog.record(
                                    category = DiagnosticCategory.Relay,
                                    severity = DiagnosticSeverity.Warning,
                                    title = context.getString(R.string.dev_settings_sample_warning_title),
                                    detail = context.getString(R.string.dev_settings_sample_warning_detail),
                                )
                                DiagnosticsLog.recordError(
                                    category = DiagnosticCategory.Voice,
                                    title = context.getString(R.string.dev_settings_sample_error_title),
                                    detail = context.getString(R.string.dev_settings_sample_error_detail),
                                    throwable = RuntimeException(
                                        context.getString(R.string.dev_settings_sample_stacktrace),
                                    ),
                                )
                                Toast.makeText(context, context.getString(R.string.dev_settings_diagnostics_emitted_toast), Toast.LENGTH_SHORT).show()
                            },
                        )

                        HorizontalDivider()

                        TestHarnessRow(
                            title = stringResource(R.string.dev_settings_test_preview_update_banner),
                            subtitle = stringResource(R.string.dev_settings_test_preview_update_banner_desc),
                            icon = Icons.Filled.Science,
                            onClick = {
                                UpdateDebugOverride.cycle()
                                val state = when (UpdateDebugOverride.flow.value) {
                                    is UpdateStatus.Available -> context.getString(R.string.dev_settings_update_state_available)
                                    is UpdateStatus.Downloaded -> context.getString(R.string.dev_settings_update_state_downloaded)
                                    else -> context.getString(R.string.dev_settings_update_state_off)
                                }
                                Toast.makeText(context, context.getString(R.string.dev_settings_update_banner_preview_toast, state), Toast.LENGTH_SHORT).show()
                            },
                        )

                        HorizontalDivider()

                        TestHarnessRow(
                            title = stringResource(R.string.dev_settings_test_show_whats_new),
                            subtitle = stringResource(R.string.dev_settings_test_show_whats_new_desc),
                            icon = Icons.Filled.Science,
                            onClick = {
                                connectionViewModel.showWhatsNewNow()
                                onBack()
                            },
                        )

                        HorizontalDivider()

                        TestHarnessRow(
                            title = stringResource(R.string.dev_settings_test_force_crash),
                            subtitle = stringResource(R.string.dev_settings_test_force_crash_desc),
                            icon = Icons.Filled.Warning,
                            tint = MaterialTheme.colorScheme.error,
                            onClick = {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    throw RuntimeException("Test crash from Developer options")
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.dev_settings_export_sensitive_backup_title)) },
            text = {
                Text(
                    stringResource(R.string.dev_settings_export_sensitive_backup_body)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        connectionViewModel.exportSettings { json ->
                            backupJson = json
                            exportLauncher.launch("hermes-relay-sensitive-backup.json")
                        }
                    }
                ) {
                    Text(stringResource(R.string.dev_settings_export_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text(stringResource(R.string.dev_settings_cancel))
                }
            }
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(stringResource(R.string.dev_settings_import_backup_title)) },
            text = {
                Text(
                    stringResource(R.string.dev_settings_import_backup_body)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        importLauncher.launch(arrayOf("application/json"))
                    }
                ) {
                    Text(stringResource(R.string.dev_settings_choose_file))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text(stringResource(R.string.dev_settings_cancel))
                }
            }
        )
    }

    // Confirmation dialog for data reset
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.dev_settings_reset_all_app_data_title)) },
            text = {
                Text(
                    stringResource(R.string.dev_settings_reset_all_app_data_body)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        connectionViewModel.resetAppData()
                        Toast.makeText(context, context.getString(R.string.dev_settings_app_data_reset_toast), Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.dev_settings_reset_action), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.dev_settings_cancel))
                }
            }
        )
    }
}

@Composable
private fun TestHarnessRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.tertiary,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onClick) {
            Icon(imageVector = icon, contentDescription = title, tint = tint)
        }
    }
}
