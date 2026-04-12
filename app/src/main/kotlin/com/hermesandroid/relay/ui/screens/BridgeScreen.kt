package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
// === PHASE3-ζ: safety summary card ===
import com.hermesandroid.relay.bridge.BridgeSafetyManager
import com.hermesandroid.relay.data.BridgeSafetySettings
import com.hermesandroid.relay.ui.components.BridgeSafetySummaryCard
// === END PHASE3-ζ ===
import com.hermesandroid.relay.ui.components.BridgeActivityLog
import com.hermesandroid.relay.ui.components.BridgeMasterToggle
import com.hermesandroid.relay.ui.components.BridgePermissionChecklist
import com.hermesandroid.relay.ui.components.BridgeStatusCard
import com.hermesandroid.relay.viewmodel.BridgeViewModel

/**
 * Bridge tab — phase 3 Wave 1 rewrite (Agent δ, `bridge-screen-ui`).
 *
 * Replaces the Phase 0 "Coming Soon" placeholder with the real control
 * surface described in `Plans/Phase 3 — Bridge Channel.md` §5. Four stacked
 * cards in a verticalScroll column:
 *
 *   1. [BridgeMasterToggle]        — "Allow Agent Control" + live status
 *   2. [BridgePermissionChecklist] — accessibility / capture / overlay / notif
 *   3. [BridgeActivityLog]         — scrollable recent-command history
 *   4. Safety placeholder          — stub owned by Agent ζ in Wave 2
 *
 * State comes from [BridgeViewModel] which in turn reads from the
 * [com.hermesandroid.relay.data.BridgePreferencesRepository] DataStore for
 * anything persistent, and stubs the live bridge-runtime state until
 * Agent γ's `HermesAccessibilityService` exposes it. See [BridgeViewModel]'s
 * KDoc for the exact γ-handoff surface.
 *
 * Lifecycle: we re-probe permission status on every ON_RESUME so that
 * returning from Android Settings immediately flips the accessibility
 * checklist row from red to green without needing to navigate away and back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeScreen(
    viewModel: BridgeViewModel = viewModel(),
    // === PHASE3-ζ: safety summary card ===
    onNavigateToBridgeSafety: () -> Unit = {},
    // === END PHASE3-ζ ===
) {
    val masterToggle by viewModel.masterToggle.collectAsState()
    val permissionStatus by viewModel.permissionStatus.collectAsState()
    val bridgeStatus by viewModel.bridgeStatus.collectAsState()
    val activityLog by viewModel.activityLog.collectAsState()

    // === PHASE3-ζ: safety summary card ===
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
    // === END PHASE3-ζ ===

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
            BridgeMasterToggle(
                enabled = masterToggle,
                status = bridgeStatus,
                accessibilityGranted = permissionStatus.accessibilityServiceEnabled,
                onToggle = { viewModel.setMasterEnabled(it) }
            )

            BridgeStatusCard(
                status = bridgeStatus,
                // TODO(γ-handoff): once γ exposes a `bridgeConnected`
                // StateFlow from HermesAccessibilityService, drive this off
                // that instead of the a11y-granted flag.
                isConnected = permissionStatus.accessibilityServiceEnabled && masterToggle,
            )

            BridgePermissionChecklist(status = permissionStatus)

            BridgeActivityLog(
                entries = activityLog,
                onClear = { viewModel.clearActivityLog() }
            )

            // === PHASE3-ζ: safety summary card ===
            BridgeSafetySummaryCard(
                settings = safetySettings,
                autoDisableAtMs = autoDisableAtMs,
                onManage = onNavigateToBridgeSafety,
            )
            // === END PHASE3-ζ ===

            Spacer(modifier = Modifier.height(16.dp))
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
