package com.hermesandroid.relay.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
// === PHASE3-safety-rails: safety summary card ===
import com.hermesandroid.relay.bridge.BridgeSafetyManager
import com.hermesandroid.relay.data.BridgeSafetySettings
import com.hermesandroid.relay.ui.components.BridgeSafetySummaryCard
// === END PHASE3-safety-rails ===
import com.hermesandroid.relay.ui.LocalSnackbarHost
import com.hermesandroid.relay.ui.components.BridgeActivityLog
import com.hermesandroid.relay.ui.components.BridgeMasterToggle
import com.hermesandroid.relay.ui.components.BridgePermissionChecklist
import com.hermesandroid.relay.ui.components.BridgeStatusCard
import com.hermesandroid.relay.viewmodel.BridgeViewModel

/**
 * Bridge tab — phase 3 Wave 1 rewrite (Agent bridge-ui, `bridge-screen-ui`).
 *
 * Replaces the Phase 0 "Coming Soon" placeholder with the real control
 * surface described in `Plans/Phase 3 — Bridge Channel.md` §5. Four stacked
 * cards in a verticalScroll column:
 *
 *   1. [BridgeMasterToggle]        — "Allow Agent Control" + live status
 *   2. [BridgePermissionChecklist] — accessibility / capture / overlay / notif
 *   3. [BridgeActivityLog]         — scrollable recent-command history
 *   4. Safety placeholder          — stub owned by Agent safety-rails in Wave 2
 *
 * State comes from [BridgeViewModel] which in turn reads from the
 * [com.hermesandroid.relay.data.BridgePreferencesRepository] DataStore for
 * anything persistent, and stubs the live bridge-runtime state until
 * Agent accessibility's `HermesAccessibilityService` exposes it. See [BridgeViewModel]'s
 * KDoc for the exact accessibility-handoff surface.
 *
 * Lifecycle: we re-probe permission status on every ON_RESUME so that
 * returning from Android Settings immediately flips the accessibility
 * checklist row from red to green without needing to navigate away and back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeScreen(
    viewModel: BridgeViewModel = viewModel(),
    // === PHASE3-safety-rails: safety summary card ===
    onNavigateToBridgeSafety: () -> Unit = {},
    // === END PHASE3-safety-rails ===
) {
    val masterToggle by viewModel.masterToggle.collectAsState()
    val permissionStatus by viewModel.permissionStatus.collectAsState()
    val bridgeStatus by viewModel.bridgeStatus.collectAsState()
    val activityLog by viewModel.activityLog.collectAsState()

    // === PHASE3-safety-rails-followup: surface permission Test results via snackbar ===
    val snackbarHost = LocalSnackbarHost.current
    LaunchedEffect(viewModel) {
        viewModel.testEvents.collect { message ->
            snackbarHost.showSnackbar(message)
        }
    }
    // === END PHASE3-safety-rails-followup ===

    val context = LocalContext.current

    // === PHASE3-safety-rails: safety summary card ===
    // Pulls live safety settings + countdown off the process-wide
    // BridgeSafetyManager singleton. No ViewModel wiring required — the
    // manager is installed by ConnectionViewModel at app start.
    val safetyManager = BridgeSafetyManager.peek()
    val safetySettings by (safetyManager?.settings
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(BridgeSafetySettings()) })
        .collectAsState()
    val autoDisableAtMs by (safetyManager?.autoDisableAtMs
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow<Long?>(null) })
        .collectAsState()
    // === END PHASE3-safety-rails ===

    // Re-run permission + system-status probes whenever the screen resumes.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onScreenResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bridge") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // === PHASE3-safety-rails-followup: overlay-permission nag banner ===
            // Bridge is enabled but the user hasn't granted Display Over Other
            // Apps. Without that permission, the destructive-verb confirmation
            // modal can't render and BridgeSafetyManager.awaitConfirmation
            // fails closed (denies the action) — silently from the user's
            // perspective. Show a prominent banner so the failure isn't
            // mysterious.
            if (masterToggle && !permissionStatus.overlayPermitted) {
                OverlayPermissionNagCard(
                    onTap = {
                        runCatching {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            context.startActivity(intent)
                        }
                    },
                )
            }
            // === END PHASE3-safety-rails-followup ===

            BridgeMasterToggle(
                enabled = masterToggle,
                status = bridgeStatus,
                accessibilityGranted = permissionStatus.accessibilityServiceEnabled,
                onToggle = { viewModel.setMasterEnabled(it) }
            )

            BridgeStatusCard(
                status = bridgeStatus,
                // TODO(accessibility-handoff): once accessibility exposes a `bridgeConnected`
                // StateFlow from HermesAccessibilityService, drive this off
                // that instead of the a11y-granted flag.
                isConnected = permissionStatus.accessibilityServiceEnabled && masterToggle,
            )

            BridgePermissionChecklist(
                status = permissionStatus,
                // === PHASE3-safety-rails-followup: in-app permission Test handlers ===
                onTestAccessibility = { viewModel.testAccessibilityService() },
                onTestScreenCapture = { viewModel.testScreenCapture() },
                onTestOverlay = { viewModel.testOverlayPermission() },
                // === END PHASE3-safety-rails-followup ===
                // === PHASE3-bridge-ui-followup: extended interactions ===
                onRequestScreenCapture = { viewModel.requestScreenCapture() },
                onTestNotificationListener = { viewModel.testNotificationListener() },
                // === END PHASE3-bridge-ui-followup ===
            )

            BridgeActivityLog(
                entries = activityLog,
                onClear = { viewModel.clearActivityLog() }
            )

            // === PHASE3-safety-rails: safety summary card ===
            BridgeSafetySummaryCard(
                settings = safetySettings,
                autoDisableAtMs = autoDisableAtMs,
                onManage = onNavigateToBridgeSafety,
            )
            // === END PHASE3-safety-rails ===

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Shown at the top of [BridgeScreen] when the master toggle is on but the
 * user hasn't granted SYSTEM_ALERT_WINDOW. Without that permission,
 * `BridgeSafetyManager.awaitConfirmation` fails closed and destructive
 * actions get silently denied — the user has no way to know unless we
 * tell them. Tap to open the overlay-permission Settings page.
 *
 * Phase 3 / safety-rails followup. Goes away on its own once
 * `Settings.canDrawOverlays(context)` flips true.
 */
@Composable
private fun OverlayPermissionNagCard(onTap: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Grant 'Display over other apps'",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "Without this, confirmation prompts can't show when " +
                        "the agent acts. Destructive actions will be silently denied. " +
                        "Tap to grant.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun OverlayPermissionNagCardPreview() {
    MaterialTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OverlayPermissionNagCard(onTap = {})
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun BridgeScreenPreview() {
    // Previews can't construct a real AndroidViewModel without an Application
    // context — we just render the summary card on its own to verify spacing.
    // Individual components have their own full previews.
    MaterialTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BridgeSafetySummaryCard(
                settings = BridgeSafetySettings(),
                autoDisableAtMs = null,
                onManage = {},
            )
        }
    }
}
