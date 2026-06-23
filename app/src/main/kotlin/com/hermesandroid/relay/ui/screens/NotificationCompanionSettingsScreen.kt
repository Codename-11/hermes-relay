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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermesandroid.relay.notifications.HermesNotificationCompanion
import com.hermesandroid.relay.notifications.NotificationTriggerAction
import com.hermesandroid.relay.notifications.NotificationTriggerRule
import com.hermesandroid.relay.notifications.NotificationTriggerSettings
import com.hermesandroid.relay.notifications.NotificationTriggerStore
import com.hermesandroid.relay.notifications.notificationTriggerDataStore
import com.hermesandroid.relay.notifications.summary
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Notification companion settings screen — opt-in helper that lets the
 * user's Hermes assistant read notifications they've explicitly granted
 * access to, plus the event-trigger MVP rule editor/activity log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCompanionSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val triggerStore = remember(appContext) {
        NotificationTriggerStore(appContext.notificationTriggerDataStore)
    }
    val triggerSettings by triggerStore.settings.collectAsState(
        initial = NotificationTriggerSettings(),
    )

    // Track grant state. Re-check on every ON_RESUME so the screen
    // updates immediately when the user comes back from Android Settings.
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
                title = { Text("Notification companion") },
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
            NotificationAccessCard(
                granted = granted,
                onRefresh = { granted = HermesNotificationCompanion.isAccessGranted(context) },
                onOpenSettings = {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
            )

            NotificationTriggerCard(
                settings = triggerSettings,
                onMasterChanged = { enabled ->
                    scope.launch { triggerStore.setMasterEnabled(enabled) }
                },
                onKillSwitchChanged = { enabled ->
                    scope.launch { triggerStore.setKillSwitch(enabled) }
                },
                onSaveRule = { rule ->
                    scope.launch { triggerStore.saveSingleRule(rule) }
                },
            )

            NotificationActivityLogCard(
                settings = triggerSettings,
                onClear = { scope.launch { triggerStore.clearActivityLog() } },
            )

            NotificationAboutCard()

            NotificationTestCard(granted = granted)
        }
    }
}

@Composable
private fun NotificationAccessCard(
    granted: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    NotifSectionCard(title = "Status") {
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
                        if (granted) Color(0xFF43A047) else MaterialTheme.colorScheme.outline,
                    ),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (granted) "Access granted" else "Access not granted",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (granted) {
                        "Hermes-Relay can read posted notifications"
                    } else {
                        "Tap below to enable in Android Settings"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh status",
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
            )
            Spacer(Modifier.size(8.dp))
            Text(if (granted) "Manage in Android Settings" else "Open Android Settings")
        }
    }
}

@Composable
private fun NotificationTriggerCard(
    settings: NotificationTriggerSettings,
    onMasterChanged: (Boolean) -> Unit,
    onKillSwitchChanged: (Boolean) -> Unit,
    onSaveRule: (NotificationTriggerRule) -> Unit,
) {
    val emptyRule = remember { NotificationTriggerStore.defaultRule() }
    val savedRule = settings.rules.firstOrNull() ?: emptyRule
    var ruleEnabled by remember { mutableStateOf(savedRule.enabled) }
    var label by remember { mutableStateOf(savedRule.label) }
    var appPackage by remember { mutableStateOf(savedRule.appPackage.orEmpty()) }
    var titleContains by remember { mutableStateOf(savedRule.titleContains.orEmpty()) }
    var textContains by remember { mutableStateOf(savedRule.textContains.orEmpty()) }

    LaunchedEffect(
        savedRule.id,
        savedRule.enabled,
        savedRule.label,
        savedRule.appPackage,
        savedRule.titleContains,
        savedRule.textContains,
    ) {
        ruleEnabled = savedRule.enabled
        label = savedRule.label
        appPackage = savedRule.appPackage.orEmpty()
        titleContains = savedRule.titleContains.orEmpty()
        textContains = savedRule.textContains.orEmpty()
    }

    val hasAnyFilter = appPackage.isNotBlank() || titleContains.isNotBlank() || textContains.isNotBlank()

    NotifSectionCard(title = "Event triggers (MVP)") {
        Text(
            text = "Rules are off by default. When enabled, the first MVP action is safe: post a local “Ask Hermes?” prompt when a matching notification arrives.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        LabeledSwitchRow(
            title = "Enable proactive triggers",
            subtitle = "Explicit opt-in. Existing notification forwarding still works when this is off.",
            checked = settings.masterEnabled,
            onCheckedChange = onMasterChanged,
        )
        LabeledSwitchRow(
            title = "Kill switch",
            subtitle = "Immediately pauses all trigger actions without deleting rules or the log.",
            checked = settings.killSwitch,
            onCheckedChange = onKillSwitchChanged,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Text("Rule", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "Match by app package and optional title/text contains filters. Example package: com.slack.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        LabeledSwitchRow(
            title = "Rule enabled",
            subtitle = savedRule.summary(),
            checked = ruleEnabled,
            onCheckedChange = { ruleEnabled = it },
        )
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Rule label") },
        )
        OutlinedTextField(
            value = appPackage,
            onValueChange = { appPackage = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("App package") },
            placeholder = { Text("com.example.app") },
        )
        OutlinedTextField(
            value = titleContains,
            onValueChange = { titleContains = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Title contains (optional)") },
        )
        OutlinedTextField(
            value = textContains,
            onValueChange = { textContains = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Text contains (optional)") },
        )
        Button(
            onClick = {
                onSaveRule(
                    NotificationTriggerRule(
                        id = savedRule.id,
                        label = label,
                        enabled = ruleEnabled,
                        appPackage = appPackage,
                        titleContains = titleContains,
                        textContains = textContains,
                        action = NotificationTriggerAction.AskMe,
                        requireConfirmation = false,
                    ),
                )
            },
            enabled = hasAnyFilter,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save rule")
        }
        if (!hasAnyFilter) {
            Text(
                text = "Set at least one filter before saving so triggers do not match every notification on the phone.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun NotificationActivityLogCard(
    settings: NotificationTriggerSettings,
    onClear: () -> Unit,
) {
    NotifSectionCard(title = "Activity log") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Latest trigger matches",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = onClear,
                enabled = settings.activityLog.isNotEmpty(),
            ) { Text("Clear") }
        }

        if (settings.activityLog.isEmpty()) {
            Text(
                text = "No trigger activity yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@NotifSectionCard
        }

        val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
        settings.activityLog.take(10).forEach { entry ->
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                Text(
                    text = entry.ruleLabel,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${entry.packageName} · ${df.format(Date(entry.matchedAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.title?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall)
                }
                entry.textPreview?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = entry.result,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun NotificationAboutCard() {
    NotifSectionCard(title = "About") {
        Text(
            text = "Lets your Hermes assistant help you triage notifications. When enabled, your phone forwards each notification's app, title, and text to your paired Hermes server through the Relay pairing used by phone tools.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Requires Android's notification access permission. You can grant or revoke it at any time in Android Settings. This is the same permission Wear OS, Android Auto, and Tasker use.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Confirmation policy: local prompts and local log writes can run automatically after opt-in. Anything that sends a message, replies in another app, routes content to a person/channel, uses bridge gestures, or spends/changes data still requires an explicit user confirmation first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NotificationTestCard(granted: Boolean) {
    NotifSectionCard(title = "Test") {
        Text(
            text = "Tap below to fetch the last few notifications the listener has captured this session. Helpful for verifying the connection is working end-to-end.",
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
                val service = HermesNotificationCompanion.active
                if (service == null) {
                    lastSnapshot = emptyList()
                    lastError = if (granted) {
                        "Listener has not bound yet. Try posting a test notification and re-tap."
                    } else {
                        "Notification access is not granted."
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
            Text("Fetch recent")
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
                            text = line.title ?: "(no title)",
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

@Composable
private fun LabeledSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

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
