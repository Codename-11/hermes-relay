package com.hermesandroid.relay.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermesandroid.relay.data.BridgeSafetyPreferencesRepository
import com.hermesandroid.relay.data.BridgeSafetySettings
import com.hermesandroid.relay.data.DEFAULT_BLOCKLIST
import com.hermesandroid.relay.data.MAX_AUTO_DISABLE_MINUTES
import com.hermesandroid.relay.data.MAX_CONFIRMATION_TIMEOUT_SECONDS
import com.hermesandroid.relay.data.MIN_AUTO_DISABLE_MINUTES
import com.hermesandroid.relay.data.MIN_CONFIRMATION_TIMEOUT_SECONDS
import kotlinx.coroutines.launch

/**
 * Phase 3 — safety-rails `bridge-safety-rails`
 *
 * Compose screen for Tier 5 safety configuration. Five sections:
 *
 *  1. Blocklist — searchable list of installed apps with a checkbox per
 *     row. Uses `PackageManager.getInstalledApplications` + a filter
 *     dropping system apps with no launcher intent so the list is a few
 *     dozen entries not five hundred.
 *  2. Destructive verbs — input-chip list with an "Add" text field.
 *  3. Auto-disable timer — slider 5..120 min.
 *  4. Status overlay — toggle; if flipped on without SYSTEM_ALERT_WINDOW
 *     permission, tapping the switch kicks off the permission grant flow.
 *  5. Confirmation timeout — slider 10..60s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeSafetySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { BridgeSafetyPreferencesRepository(context) }
    val settings by repo.settings.collectAsState(initial = BridgeSafetySettings())

    // Overlay-permission live check — recompute on resume so returning
    // from Settings flips the switch's availability without nav churn.
    var canDrawOverlays by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canDrawOverlays = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    // Cached installed-app list (expensive to call, so we do it once).
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var appSearch by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        installedApps = loadInstalledApps(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bridge safety") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            // ── Blocklist ───────────────────────────────────────────────
            SectionCard(title = "Blocked apps") {
                Text(
                    text = "The agent cannot act on any app in this list. Defaults ship with banking apps and password managers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = appSearch,
                    onValueChange = { appSearch = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search apps") },
                )
                Spacer(Modifier.size(8.dp))
                val filtered = remember(installedApps, appSearch, settings.blocklist) {
                    val term = appSearch.trim().lowercase()
                    val sorted = installedApps.sortedWith(
                        compareByDescending<InstalledApp> { it.packageName in settings.blocklist }
                            .thenBy { it.label.lowercase() }
                    )
                    if (term.isEmpty()) sorted
                    else sorted.filter {
                        it.label.lowercase().contains(term) ||
                            it.packageName.lowercase().contains(term)
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        val checked = app.packageName in settings.blocklist
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { nowChecked ->
                                    scope.launch {
                                        if (nowChecked) repo.addToBlocklist(app.packageName)
                                        else repo.removeFromBlocklist(app.packageName)
                                    }
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // ── Destructive verbs ───────────────────────────────────────
            SectionCard(title = "Destructive verbs") {
                Text(
                    text = "Tap_text / type commands whose text contains these words (word-boundary match) trigger a confirmation modal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                var newVerb by remember { mutableStateOf("") }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newVerb,
                        onValueChange = { newVerb = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Add a verb") },
                    )
                    IconButton(onClick = {
                        val v = newVerb.trim()
                        if (v.isNotEmpty()) {
                            scope.launch { repo.addDestructiveVerb(v) }
                            newVerb = ""
                        }
                    }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add verb")
                    }
                }
                Spacer(Modifier.size(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    settings.destructiveVerbs.sorted().chunked(3).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            row.forEach { verb ->
                                InputChip(
                                    selected = false,
                                    onClick = {},
                                    label = { Text(verb) },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = {
                                                scope.launch { repo.removeDestructiveVerb(verb) }
                                            },
                                            modifier = Modifier.size(18.dp),
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Remove",
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ── Auto-disable timer ──────────────────────────────────────
            SectionCard(title = "Auto-disable after idle") {
                Text(
                    text = "Master toggle flips off after this many minutes with no bridge commands. Rescheduled on every command.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "${settings.autoDisableMinutes} min",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = settings.autoDisableMinutes.toFloat(),
                    onValueChange = { v ->
                        scope.launch { repo.setAutoDisableMinutes(v.toInt()) }
                    },
                    valueRange = MIN_AUTO_DISABLE_MINUTES.toFloat()..MAX_AUTO_DISABLE_MINUTES.toFloat(),
                    steps = (MAX_AUTO_DISABLE_MINUTES - MIN_AUTO_DISABLE_MINUTES) / 5 - 1,
                )
            }

            // ── Status overlay ──────────────────────────────────────────
            SectionCard(title = "Status overlay") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Show floating indicator",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = if (canDrawOverlays) {
                                "Permission granted."
                            } else {
                                "Tap to grant overlay permission."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.statusOverlayEnabled && canDrawOverlays,
                        onCheckedChange = { wanted ->
                            if (wanted && !canDrawOverlays) {
                                // Kick off the SYSTEM_ALERT_WINDOW grant flow.
                                runCatching {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                }
                            } else {
                                scope.launch { repo.setStatusOverlayEnabled(wanted) }
                            }
                        }
                    )
                }
            }

            // ── Confirmation timeout ────────────────────────────────────
            SectionCard(title = "Confirmation timeout") {
                Text(
                    text = "Destructive-verb modal waits this long for your response before defaulting to Deny.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "${settings.confirmationTimeoutSeconds} s",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = settings.confirmationTimeoutSeconds.toFloat(),
                    onValueChange = { v ->
                        scope.launch { repo.setConfirmationTimeoutSeconds(v.toInt()) }
                    },
                    valueRange = MIN_CONFIRMATION_TIMEOUT_SECONDS.toFloat()..MAX_CONFIRMATION_TIMEOUT_SECONDS.toFloat(),
                    steps = (MAX_CONFIRMATION_TIMEOUT_SECONDS - MIN_CONFIRMATION_TIMEOUT_SECONDS) / 5 - 1,
                )
            }

            Spacer(modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = DividerDefaults.Thickness,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
            )
            content()
        }
    }
}

private data class InstalledApp(
    val packageName: String,
    val label: String,
)

/**
 * Enumerate installed apps that have a launcher intent + all apps in the
 * default blocklist (even if they're not installed — so users still see
 * which entries the defaults cover). Deduped by package name.
 */
private fun loadInstalledApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
    val launcherable = runCatching { pm.queryIntentActivities(launcher, 0) }
        .getOrDefault(emptyList())
        .mapNotNull { resolve ->
            val info = resolve.activityInfo?.applicationInfo ?: return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(info).toString() }
                .getOrDefault(info.packageName ?: return@mapNotNull null)
            InstalledApp(packageName = info.packageName, label = label)
        }

    val defaults = DEFAULT_BLOCKLIST.map { pkg ->
        val label = runCatching {
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
        InstalledApp(packageName = pkg, label = label)
    }

    return (launcherable + defaults)
        .distinctBy { it.packageName }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun BridgeSafetySettingsScreenPreview() {
    MaterialTheme {
        // The real screen needs a Context for PackageManager lookups, so
        // this preview just shows one section to verify styling.
        Column(Modifier.padding(16.dp)) {
            SectionCard(title = "Destructive verbs") {
                Text(
                    text = "Tap_text / type commands whose text contains these words trigger a confirmation modal.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
