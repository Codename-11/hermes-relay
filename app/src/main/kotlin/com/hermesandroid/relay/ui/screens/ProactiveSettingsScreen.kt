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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hermesandroid.relay.auth.AuthState
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
                title = { Text("Threads") },
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProactiveSectionCard(title = "Let Hermes message me") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allow proactive messages",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Your agent can reach out on its own — reminders, " +
                                "finished jobs, alerts. Its messages appear as Threads " +
                                "in Chat, where you can reply.",
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
                        text = "Not paired yet — pair with your Hermes server under " +
                            "Settings → Connections to start receiving messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            ProactiveSectionCard(title = "Your Threads") {
                Text(
                    text = "Each conversation your agent starts shows as a Thread in " +
                        "Chat — open the session list, filter to Threads, and reply " +
                        "right there.",
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
                    Text("Open Chat")
                }
            }

            ProactiveSectionCard(title = "About") {
                Text(
                    text = "When on, your phone tells the relay it's open to " +
                        "agent-initiated messages. The agent delivers them over " +
                        "the same paired connection the relay already uses — no " +
                        "new permissions beyond notifications.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Your server must also enable the phone platform " +
                        "(set PHONE_ENABLED on the server). Until both sides are " +
                        "on and the phone is paired, nothing is pushed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
