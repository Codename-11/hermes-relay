package com.hermesandroid.companion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hermesandroid.companion.auth.AuthState
import com.hermesandroid.companion.network.ConnectionState
import com.hermesandroid.companion.viewmodel.ConnectionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    connectionViewModel: ConnectionViewModel
) {
    val connectionState by connectionViewModel.connectionState.collectAsState()
    val authState by connectionViewModel.authState.collectAsState()
    val serverUrl by connectionViewModel.serverUrl.collectAsState()
    val pairingCode by connectionViewModel.pairingCode.collectAsState()
    val theme by connectionViewModel.theme.collectAsState()

    var urlInput by remember(serverUrl) { mutableStateOf(serverUrl) }
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        TopAppBar(
            title = { Text("Settings") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection section
            Text(
                text = "Connection",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Server URL
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("Server URL") },
                        placeholder = { Text("wss://your-server:8767") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Connect/Disconnect buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                connectionViewModel.connect(urlInput)
                            },
                            enabled = connectionState == ConnectionState.Disconnected &&
                                urlInput.startsWith("wss://")
                        ) {
                            Text("Connect")
                        }
                        OutlinedButton(
                            onClick = { connectionViewModel.disconnect() },
                            enabled = connectionState != ConnectionState.Disconnected
                        ) {
                            Text("Disconnect")
                        }
                    }

                    HorizontalDivider()

                    // Connection status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Circle,
                            contentDescription = null,
                            tint = when (connectionState) {
                                ConnectionState.Connected -> MaterialTheme.colorScheme.primary
                                ConnectionState.Connecting,
                                ConnectionState.Reconnecting -> MaterialTheme.colorScheme.tertiary
                                ConnectionState.Disconnected -> MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = when (connectionState) {
                                ConnectionState.Connected -> "Connected"
                                ConnectionState.Connecting -> "Connecting..."
                                ConnectionState.Reconnecting -> "Reconnecting..."
                                ConnectionState.Disconnected -> "Disconnected"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Pairing section
            Text(
                text = "Pairing",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Pairing code display
                    Text(
                        text = "Pairing Code",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = pairingCode,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = MaterialTheme.typography.headlineMedium.fontSize * 0.15
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(pairingCode))
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy pairing code"
                            )
                        }

                        IconButton(onClick = {
                            connectionViewModel.regeneratePairingCode()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Generate new code"
                            )
                        }
                    }

                    HorizontalDivider()

                    // Session token status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Session:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = when (authState) {
                                is AuthState.Paired -> "Paired"
                                is AuthState.Pairing -> "Pairing..."
                                is AuthState.Unpaired -> "Unpaired"
                                is AuthState.Failed -> "Failed: ${(authState as AuthState.Failed).reason}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (authState) {
                                is AuthState.Paired -> MaterialTheme.colorScheme.primary
                                is AuthState.Failed -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    if (authState is AuthState.Paired) {
                        OutlinedButton(onClick = { connectionViewModel.clearSession() }) {
                            Text("Clear Session")
                        }
                    }
                }
            }

            // Theme section
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val themeOptions = listOf("auto", "light", "dark")
                    val themeLabels = listOf("Auto", "Light", "Dark")
                    val selectedIndex = themeOptions.indexOf(theme).coerceAtLeast(0)

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        themeOptions.forEachIndexed { index, option ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = themeOptions.size
                                ),
                                onClick = { connectionViewModel.setTheme(option) },
                                selected = index == selectedIndex
                            ) {
                                Text(themeLabels[index])
                            }
                        }
                    }
                }
            }

            // Data Management section
            Text(
                text = "Data",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            DataManagementSection(connectionViewModel = connectionViewModel)

            // About section
            Text(
                text = "About",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "App Version",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "0.1.0",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Build",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "1 (MVP)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DataManagementSection(connectionViewModel: ConnectionViewModel) {
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }
    var backupJson by remember { mutableStateOf<String?>(null) }

    // SAF file picker for export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && backupJson != null) {
            connectionViewModel.writeBackupToUri(uri, backupJson!!) { success ->
                Toast.makeText(
                    context,
                    if (success) "Settings exported" else "Export failed",
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
                    if (success) "Settings imported" else "Import failed — invalid file",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
                        text = "Reset Onboarding",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Show the setup guide again on next launch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    connectionViewModel.resetOnboarding()
                    Toast.makeText(context, "Onboarding will show on next launch", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = "Reset onboarding"
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
                        text = "Export Settings",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Save settings to a file (no tokens)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    connectionViewModel.exportSettings { json ->
                        backupJson = json
                        exportLauncher.launch("hermes-companion-backup.json")
                    }
                }) {
                    Icon(
                        imageVector = Icons.Filled.FileDownload,
                        contentDescription = "Export settings"
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
                        text = "Import Settings",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Restore settings from a backup file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    importLauncher.launch(arrayOf("application/json"))
                }) {
                    Icon(
                        imageVector = Icons.Filled.FileUpload,
                        contentDescription = "Import settings"
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
                        text = "Reset All Data",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "Clear all settings, tokens, and cached data",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showResetDialog = true }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Reset all data",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    // Confirmation dialog for data reset
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset All Data?") },
            text = { Text("This will clear all settings, authentication tokens, and cached data. You'll need to re-pair with your server. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        connectionViewModel.resetAppData()
                        Toast.makeText(context, "App data reset", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
