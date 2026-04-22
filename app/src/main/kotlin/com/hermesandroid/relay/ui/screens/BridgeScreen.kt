package com.hermesandroid.relay.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.hermesandroid.relay.data.BuildFlavor
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
// === v0.4.1 unattended-access ===
import com.hermesandroid.relay.ui.components.UnattendedAccessRow
// === END v0.4.1 unattended-access ===
import com.hermesandroid.relay.viewmodel.BridgeViewModel
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch

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
    // Relay dependency — Bridge commands arrive over the WSS relay, so
    // surfacing the relay-connected state on this screen lets the user
    // see "why is my bridge not responding" before the first missed
    // command, not after. Optional (default null) so legacy call sites
    // / preview fixtures that don't have a ConnectionViewModel still
    // compile; the nag banner simply hides when it's absent.
    connectionViewModel: ConnectionViewModel? = null,
    // === PHASE3-safety-rails: safety summary card ===
    onNavigateToBridgeSafety: () -> Unit = {},
    // === END PHASE3-safety-rails ===
) {
    val masterToggle by viewModel.masterToggle.collectAsState()
    val permissionStatus by viewModel.permissionStatus.collectAsState()
    val bridgeStatus by viewModel.bridgeStatus.collectAsState()
    val activityLog by viewModel.activityLog.collectAsState()
    // Relay-ready signal for the dependency banner. Falls back to `true`
    // (hides the banner) when connectionViewModel is null — lets the
    // @Preview fixture + any future caller-without-VM path render
    // cleanly without a stale warning.
    val relayReady by (connectionViewModel?.relayReady
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow(true) })
        .collectAsState()
    // === v0.4.1 unattended-access ===
    val unattendedEnabled by viewModel.unattendedEnabled.collectAsState()
    val unattendedWarningSeen by viewModel.unattendedWarningSeen.collectAsState()
    val credentialLockDetected by viewModel.credentialLockDetected.collectAsState()
    // === END v0.4.1 unattended-access ===

    // === PHASE3-safety-rails-followup: surface permission Test results via snackbar ===
    val snackbarHost = LocalSnackbarHost.current
    LaunchedEffect(viewModel) {
        viewModel.testEvents.collect { message ->
            snackbarHost.showSnackbar(message)
        }
    }
    // === END PHASE3-safety-rails-followup ===

    val context = LocalContext.current

    // Result callback used by every runtime-permission launcher on this screen.
    // If the user has permanently denied the permission (two declines on
    // Android 11+, or an explicit "Don't ask again"), the next launch() call
    // returns false instantly without showing a dialog — leaving the user
    // tapping the checklist row with no visible response. Detect that case via
    // shouldShowRequestPermissionRationale and fall back to app-details
    // Settings so the user always has a path to grant.
    val onPermissionResult: (String, Boolean) -> Unit = { permission, granted ->
        if (!granted) {
            maybeOpenAppDetailsOnPermanentDenial(context, permission)
        }
        viewModel.onScreenResumed()
    }

    // Notification-permission launcher is still used by the master-toggle
    // auto-request path below (onToggle → launches if API 33+ and not yet
    // permitted). The six other runtime-permission launchers that used to
    // live here (mic / camera / contacts / sms / phone / location) were
    // removed — their only consumers were the checklist rows, which now
    // go straight to Settings via openAppDetailsSettings() to give users
    // a visible, reliable path instead of the silent-no-op that a
    // permanently-denied permission would otherwise produce.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        onPermissionResult(android.Manifest.permission.POST_NOTIFICATIONS, granted)
    }

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
    // Trusted-verb set — drives the "Trusted actions" row below the safety
    // summary. Empty-set fallback when safetyManager is null so previews
    // / test harnesses render cleanly.
    val trustedVerbs by (safetyManager?.trustedDestructiveVerbs
        ?: remember { kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet()) })
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
            // Relay-not-connected banner. Bridge commands arrive over the
            // relay's WSS — when relay is Unpaired / Disconnected / URL
            // blank, the AccessibilityService + foreground service will
            // come up on master-toggle but will never receive commands
            // from the agent. Surface that dependency at the top of the
            // page so users don't assume "Bridge is on = bridge is
            // working". Matches the soft-gate pattern used by
            // TerminalScreen's "relay disconnected" subtitle.
            //
            // Banner renders whenever connectionViewModel is provided AND
            // relay isn't ready. We intentionally do NOT block the master
            // toggle here — letting users pre-configure permissions /
            // safety rails before a relay pairs is valuable, and the
            // BridgeViewModel's own state won't fire commands until the
            // relay connects anyway.
            if (connectionViewModel != null && !relayReady) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Column {
                            Text(
                                text = "Relay not connected",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = "Bridge commands travel over the relay. " +
                                    "Pair a relay in Settings → Connection for " +
                                    "the bridge to actually do anything.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            // === PHASE3-safety-rails-followup: overlay-permission nag banner ===
            // Bridge is enabled but the user hasn't granted Display Over Other
            // Apps. Without that permission, the destructive-verb confirmation
            // modal can't render and BridgeSafetyManager.awaitConfirmation
            // fails closed (denies the action) — silently from the user's
            // perspective. Show a prominent banner so the failure isn't
            // mysterious. Sideload only — googlePlay has no destructive-verb
            // modal (action routes are blocked) so the overlay permission isn't
            // needed and the nag would confuse users + reviewers.
            if (BuildFlavor.isSideload &&
                masterToggle &&
                !permissionStatus.overlayPermitted
            ) {
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

            // 1. Master toggle (with inline status rows — device, battery,
            //    screen, current app). This is the parent gate for the page.
            val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
            BridgeMasterToggle(
                enabled = masterToggle,
                status = bridgeStatus,
                accessibilityGranted = permissionStatus.accessibilityServiceEnabled,
                onToggle = { enabled ->
                    viewModel.setMasterEnabled(enabled)
                    if (enabled &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !permissionStatus.notificationsPermitted
                    ) {
                        notificationPermissionLauncher.launch(
                            android.Manifest.permission.POST_NOTIFICATIONS
                        )
                    }
                },
                // Feedback for the "user taps switch but accessibility is
                // missing" dead-tap path. Before this wiring, Android's
                // disabled Switch swallowed the tap silently and users had
                // to notice the red helper text below the switch to know
                // what was wrong. Now: show a snackbar, offer a direct
                // jump into Android's Accessibility Settings page.
                onAccessibilityNeeded = {
                    coroutineScope.launch {
                        val result = snackbarHost.showSnackbar(
                            message = "Accessibility Service must be enabled first.",
                            actionLabel = "Open Settings",
                            duration = androidx.compose.material3.SnackbarDuration.Long,
                        )
                        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                            runCatching {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        }
                    }
                },
                // googlePlay: the toggle is "Bridge Mode" (read-only screen
                // reading). Sideload: "Agent Control" (full phone control).
                // The label shapes user expectation + is what reviewers read.
                label = if (BuildFlavor.isSideload)
                    "Allow Agent Control"
                else
                    "Enable Bridge Mode",
            )

            // 2. Permissions — prerequisites come before advanced features.
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
                onRequestNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Same "tap always goes to Settings" treatment as the
                    // other runtime-permission rows. The master-toggle
                    // auto-request path (BridgeMasterToggle onToggle) still
                    // uses the launcher directly since that's a programmatic
                    // flow where a dialog is appropriate.
                    { openAppDetailsSettings(context) }
                } else null,
                // === v0.4.1 polish: runtime-permission row taps go to Settings ====
                // Earlier iteration tried launcher.launch(permission) with a
                // post-denial fallback to app-details Settings. That
                // fallback depended on (context as? Activity) succeeding
                // which quietly returned null in the Compose context chain
                // — so taps after a permanent denial were a silent no-op.
                // Simpler + matches user expectation: tap ALWAYS opens the
                // app-details Settings page. Parity with Accessibility /
                // Notification Listener / Overlay rows which also open
                // Settings unconditionally. First-time grant is one extra
                // tap vs. a system dialog — acceptable trade-off for
                // "tap always does something visible".
                onRequestMicrophone = { openAppDetailsSettings(context) },
                onRequestCamera = { openAppDetailsSettings(context) },
                onRequestContacts = { openAppDetailsSettings(context) },
                onRequestSms = { openAppDetailsSettings(context) },
                onRequestPhone = { openAppDetailsSettings(context) },
                onRequestLocation = { openAppDetailsSettings(context) },
                // === END v0.4.1 polish ====
            )

            // 3. Advanced section — unattended access + safety. Sideload
            //    only — these features don't exist on googlePlay (no wake
            //    lock, no destructive-verb routes).
            if (BuildFlavor.isSideload) {
                AdvancedSectionHeader()

                // 4. Unattended access — a SUB-FEATURE of the master
                //    toggle. Gated: Switch is non-interactive when the
                //    master toggle is off so users can't flip it and
                //    observe nothing happening (the acquire path
                //    short-circuits when master is off anyway).
                UnattendedAccessRow(
                    enabled = unattendedEnabled,
                    warningSeen = unattendedWarningSeen,
                    credentialLockDetected = credentialLockDetected,
                    onToggle = { viewModel.setUnattendedAccessEnabled(it) },
                    onWarningSeen = { viewModel.markUnattendedWarningSeen() },
                    masterEnabled = masterToggle,
                )

                // 5. Safety summary — auto-disable, destructive verbs,
                //    blocklist. Belongs adjacent to unattended because
                //    they share the "advanced / opt-in / sideload-only"
                //    mental model.
                BridgeSafetySummaryCard(
                    settings = safetySettings,
                    autoDisableAtMs = autoDisableAtMs,
                    onManage = onNavigateToBridgeSafety,
                )

                // 5b. Trusted actions — "Don't ask again" escape hatch.
                //     Sits next to the safety summary so users who change
                //     their minds can find it without digging into
                //     developer options. Only shown when the safety
                //     manager is wired (same gate as the summary).
                if (safetyManager != null) {
                    TrustedActionsRow(
                        trustedCount = trustedVerbs.size,
                        onReset = { safetyManager.clearTrustedDestructiveVerbs() },
                    )
                }
            }

            // 6. Activity log — goes last because it's a history view,
            //    not configuration. Users scroll past the controls to
            //    reach it, which is the right mental model.
            BridgeActivityLog(
                entries = activityLog,
                onClear = { viewModel.clearActivityLog() }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Visual separator between the "required setup" stack (master toggle +
 * permissions) and the "opt-in advanced features" stack (unattended
 * access, safety rails, activity log). Keeps hierarchy explicit so
 * users don't read unattended as a peer of the master toggle.
 */
@Composable
private fun AdvancedSectionHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Advanced",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
        )
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

/**
 * "Trusted actions" row. Shows how many destructive verbs the user has
 * marked "don't ask again" for, plus a Reset button (gated on a confirm
 * dialog) that clears the set so every destructive action prompts again.
 *
 * Copy choices:
 *  - Empty state says "Every action still prompts" — deliberately
 *    reassuring instead of promotional. We don't want to nudge users
 *    into bypassing their own safety rails by advertising the feature
 *    here; it's surfaced at the point of use inside the confirmation
 *    dialog itself.
 *  - Non-empty count is stated factually ("{N} actions bypass
 *    confirmation") so the state is visible at a glance without opening
 *    a sub-screen.
 *
 * Reset flow is double-gated (button + AlertDialog) because a single tap
 * shouldn't un-do weeks of "don't ask again" decisions by accident.
 * Reset is disabled when count == 0 to avoid dead-tap confusion in the
 * safe default.
 */
@Composable
private fun TrustedActionsRow(
    trustedCount: Int,
    onReset: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Trusted actions",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (trustedCount == 0) {
                        "Every action still prompts"
                    } else {
                        "$trustedCount action${if (trustedCount == 1) "" else "s"} " +
                            "bypass confirmation"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.TextButton(
                onClick = { showConfirm = true },
                enabled = trustedCount > 0,
            ) {
                Text("Reset")
            }
        }
    }
    if (showConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Reset trusted actions?") },
            text = {
                Text(
                    "After reset, every destructive action will prompt " +
                        "for confirmation again. You can re-enable " +
                        "\"Don't ask again\" from any future confirmation dialog."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        onReset()
                        showConfirm = false
                    },
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showConfirm = false },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Walk up the ContextWrapper chain until we find an Activity, or null if
 * none. `LocalContext.current` in a Compose-in-ComponentActivity setup
 * often returns a ContextThemeWrapper (inserted by MaterialTheme or the
 * edge-to-edge insets layer) rather than the Activity itself, so a plain
 * `context as? Activity` cast silently returns null. This walks the
 * baseContext chain so callers reliably get the Activity.
 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Open the system "App info" page for our package. Users land directly
 * on the page that lists our permissions with per-permission toggles. A
 * runtime permission granted here has the same effect as accepting the
 * corresponding system dialog, and is the only path to flip a
 * permanently-denied permission back on.
 *
 * Wrapped in runCatching because some OEM skins have stripped or renamed
 * the ACTION_APPLICATION_DETAILS_SETTINGS intent; we'd rather no-op than
 * crash the Bridge screen.
 */
private fun openAppDetailsSettings(context: Context) {
    runCatching {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/**
 * Post-callback fallback for launcher-driven permission requests: if the
 * user has permanently denied [permission] (Android 11+ auto-deny after
 * two refusals, or the pre-11 "Don't ask again" checkbox), the system
 * permission dialog won't appear on the next launch() call — it just
 * returns granted=false silently. Route them to Settings so the path
 * forward is visible.
 *
 * Heuristic: shouldShowRequestPermissionRationale returns true ONLY when
 * the user has declined at least once but is still eligible to see the
 * dialog. It returns false on both (a) first-ever request and (b) permanent
 * denial. Since this helper runs inside a launcher result callback,
 * case (a) can't apply — the callback only fires after a request — so a
 * false return here reliably means permanent denial.
 *
 * Uses [findActivity] to walk up the ContextWrapper chain — the earlier
 * direct `context as? Activity` cast silently returned null inside
 * Compose's themed context wrappers, making the fallback a no-op.
 */
private fun maybeOpenAppDetailsOnPermanentDenial(context: Context, permission: String) {
    val activity = context.findActivity() ?: return
    if (ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)) return
    openAppDetailsSettings(context)
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
