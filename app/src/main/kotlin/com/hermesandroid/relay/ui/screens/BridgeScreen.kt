package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
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
) {
    val masterToggle by viewModel.masterToggle.collectAsState()
    val permissionStatus by viewModel.permissionStatus.collectAsState()
    val bridgeStatus by viewModel.bridgeStatus.collectAsState()
    val activityLog by viewModel.activityLog.collectAsState()

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

            SafetyPlaceholderCard()

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Stub safety card — Agent ζ (Wave 2) owns this content. Renders an inert
 * card with a "Configure in Bridge Safety Settings" label so the layout
 * doesn't reflow when ζ's real UI drops in.
 *
 * TODO(ζ-handoff): replace with the real BridgeSafetySettings entry-point
 * card once the blocklist / destructive-verb confirm / auto-disable UIs
 * are ready. Expected call: `BridgeSafetyCard(onClick = { navigateToSafety() })`.
 */
@Composable
private fun SafetyPlaceholderCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Safety",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "Configure in Bridge Safety Settings",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Per-app blocklist, destructive-verb confirmation, " +
                    "auto-disable timer — coming in Phase 3 Wave 2.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1A1A2E)
@Composable
private fun BridgeScreenPreview() {
    // Previews can't construct a real AndroidViewModel without an Application
    // context, so we render the inert SafetyPlaceholderCard on its own to at
    // least verify the spacing / typography. Individual components have their
    // own full previews.
    MaterialTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SafetyPlaceholderCard()
        }
    }
}
