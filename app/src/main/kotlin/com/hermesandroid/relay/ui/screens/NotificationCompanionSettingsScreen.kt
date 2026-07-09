package com.hermesandroid.relay.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermesandroid.relay.R
import com.hermesandroid.relay.notifications.HermesNotificationCompanion
import java.text.DateFormat
import java.util.Date

/**
 * Notification companion settings screen — opt-in helper that lets the
 * user's Hermes assistant read notifications they've explicitly granted
 * access to.
 *
 * Sections:
 *   1. Status — granted or not granted (live, observed via lifecycle)
 *   2. Open Android Settings — fires `ACTION_NOTIFICATION_LISTENER_SETTINGS`
 *   3. About — explains what the feature does and how to revoke
 *
 * Mirrors the layout/style of [VoiceSettingsScreen] for consistency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCompanionSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Track grant state. Re-check on every ON_RESUME so the screen
    // updates immediately when the user comes back from Android
    // Settings after toggling the listener.
    var granted by remember {
        mutableStateOf(HermesNotificationCompanion.isAccessGranted(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = HermesNotificationCompanion.isAccessGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ncs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.ncs_back),
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

            // --- Status --- (action first: the user came to turn it on)
            NotifSectionCard(title = stringResource(R.string.ncs_status)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (granted) {
                                    Color(0xFF43A047) // green
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                            ),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (granted) stringResource(R.string.ncs_access_granted) else stringResource(R.string.ncs_access_not_granted),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = if (granted) {
                                stringResource(R.string.ncs_can_read_notifications)
                            } else {
                                stringResource(R.string.ncs_tap_to_enable)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = {
                        granted = HermesNotificationCompanion.isAccessGranted(context)
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.ncs_refresh_status),
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.OpenInNew,
                        contentDescription = null,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (granted) stringResource(R.string.ncs_manage_in_settings) else stringResource(R.string.ncs_open_android_settings),
                    )
                }
            }

            // --- About ---
            NotifSectionCard(title = stringResource(R.string.ncs_about)) {
                Text(
                        text = stringResource(R.string.ncs_about_body1),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.ncs_about_body2),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // --- Test (last received) ---
            NotifSectionCard(title = stringResource(R.string.ncs_test)) {
                Text(
                        text = stringResource(R.string.ncs_test_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                var lastSnapshot by remember {
                    mutableStateOf<List<TestNotificationLine>>(emptyList())
                }
                var lastError by remember { mutableStateOf<String?>(null) }

                FilledTonalButton(
                    onClick = {
                        // Pull from the live service if it's bound. We
                        // don't have a server-round-trip helper here on
                        // purpose — that would require sending a relay
                        // round-trip and we want this screen to work
                        // even when the relay is unreachable.
                        val service = HermesNotificationCompanion.active
                        if (service == null) {
                            lastSnapshot = emptyList()
                            lastError = if (granted) {
                                stringResource(R.string.ncs_listener_not_bound)
                            } else {
                                stringResource(R.string.ncs_access_not_granted_err)
                            }
                        } else {
                            val active = service.activeNotifications
                            lastError = null
                            lastSnapshot = active.orEmpty()
                                .sortedByDescending { it.postTime }
                                .take(5)
                                .map {
                                    TestNotificationLine(
                                        pkg = it.packageName,
                                        title = it.notification?.extras
                                            ?.getCharSequence(android.app.Notification.EXTRA_TITLE)
                                            ?.toString(),
                                        text = it.notification?.extras
                                            ?.getCharSequence(android.app.Notification.EXTRA_TEXT)
                                            ?.toString(),
                                        postedAt = it.postTime,
                                    )
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = null,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.ncs_fetch_recent))
                }

                lastError?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (lastSnapshot.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    val df = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
                    lastSnapshot.forEach { line ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = line.title ?: stringResource(R.string.ncs_no_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = df.format(Date(line.postedAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = line.pkg,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            line.text?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// Local tiny model for the screen's "Test" preview list — keeps the
// composable scope contained without adding to the wire models file.
private data class TestNotificationLine(
    val pkg: String,
    val title: String?,
    val text: String?,
    val postedAt: Long,
)

@Composable
private fun NotifSectionCard(
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

