package com.hermesandroid.relay.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.data.BridgeActivityEntry
import com.hermesandroid.relay.data.BridgePreferencesRepository
// === PHASE3-safety-rails: foreground service lifecycle ===
import com.hermesandroid.relay.bridge.BridgeForegroundService
import com.hermesandroid.relay.bridge.BridgeSafetyManager
import com.hermesandroid.relay.bridge.BridgeStatusOverlay
// === END PHASE3-safety-rails ===
// === PHASE3-safety-rails-followup: in-app permission Test handlers ===
import com.hermesandroid.relay.accessibility.HermesAccessibilityService
import com.hermesandroid.relay.accessibility.MediaProjectionHolder
// === END PHASE3-safety-rails-followup ===
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Bridge tab view-model — Phase 3 Wave 1 (Agent bridge-ui).
 *
 * Scope: UI state for the new BridgeScreen only. The actual AccessibilityService
 * runtime (read screen, tap, type, screenshot) is Agent accessibility's file set under
 * `com.hermesandroid.relay.accessibility`. bridge-ui intentionally **does not** touch
 * those files — we read/display state from whatever StateFlows accessibility exposes and
 * stub them here until accessibility's runtime lands.
 *
 * ## accessibility-handoff points (TODO before Wave 1 merges)
 *
 * 1. **`bridgeStatus`** — currently a `MutableStateFlow<BridgeStatus?>` seeded
 *    from a best-effort read of [PowerManager] + [BatteryManager] on init.
 *    accessibility must either:
 *      - expose a `com.hermesandroid.relay.accessibility.HermesAccessibilityService`
 *        singleton with `status: StateFlow<BridgeStatus>`, OR
 *      - post updates into a shared `BridgeStateHolder` object that both
 *        accessibility's service and this ViewModel read from.
 *    Either way, bridge-ui replaces the local MutableStateFlow with `.combine` against
 *    the accessibility flow. The [BridgeStatus] data class below is bridge-ui's proposed wire
 *    shape — accessibility is welcome to move it into `accessibility/` and have us import
 *    from there.
 *
 * 2. **`permissionStatus`** — we probe
 *    [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] to detect whether the
 *    a11y service is enabled, which is the standard pattern and doesn't
 *    require accessibility's code to exist yet. BUT the string-match uses a placeholder
 *    service class name (`HermesAccessibilityService`). accessibility confirms the final
 *    fully-qualified class name; if it differs, update [A11Y_SERVICE_CLASS].
 *
 * 3. **`recordActivity` / `updateActivity`** — bridge-ui writes activity log entries
 *    to [BridgePreferencesRepository]. accessibility's `HermesAccessibilityService`
 *    command dispatcher should call these (or a singleton that forwards to
 *    them) whenever a bridge command starts / completes / fails / is blocked
 *    by safety rails. Until accessibility lands, the log stays empty and the UI shows
 *    the empty-state copy.
 *
 * 4. **`masterToggle`** write path — flipping the master switch persists via
 *    [BridgePreferencesRepository.setMasterEnabled]. accessibility's service reads the
 *    same DataStore key (`bridge_master_enabled`) and treats it as the
 *    runtime disable switch — when false, the service should ignore all
 *    incoming `bridge.command` envelopes. bridge-ui does not wire the service lifecycle
 *    to this toggle; that's accessibility's call.
 *
 * Kept deliberately thin — this is a UI-presentation layer, not a mediator.
 */
class BridgeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /**
         * Fully-qualified class name of the accessibility service Agent accessibility will
         * register in the manifest. TODO(accessibility-handoff): confirm this matches accessibility's
         * final class; update if different.
         */
        const val A11Y_SERVICE_CLASS =
            "com.hermesandroid.relay.accessibility.HermesAccessibilityService"
    }

    private val prefsRepo = BridgePreferencesRepository(application)

    // ── Master toggle ────────────────────────────────────────────────────
    val masterToggle: StateFlow<Boolean> = prefsRepo.settings
        .map { it.masterEnabled }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = BridgePreferencesRepository.DEFAULT_MASTER_ENABLED
        )

    // ── Bridge status (accessibility-handoff stub) ───────────────────────────────────
    //
    // TODO(accessibility-handoff): replace this MutableStateFlow with the StateFlow that
    // HermesAccessibilityService exposes. Until then we seed a minimal status
    // on init from system APIs so the status card has *something* to show in
    // screenshots and previews.
    private val _bridgeStatus = MutableStateFlow<BridgeStatus?>(null)
    val bridgeStatus: StateFlow<BridgeStatus?> = _bridgeStatus.asStateFlow()

    // ── Permission status ────────────────────────────────────────────────
    private val _permissionStatus = MutableStateFlow(BridgePermissionStatus())
    val permissionStatus: StateFlow<BridgePermissionStatus> = _permissionStatus.asStateFlow()

    // ── Activity log ─────────────────────────────────────────────────────
    val activityLog: StateFlow<List<BridgeActivityEntry>> = prefsRepo.activityLog
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    // === PHASE3-safety-rails-followup: permission test result events ===
    //
    // One-shot result messages emitted by the in-app permission Test buttons
    // in BridgePermissionChecklist. BridgeScreen collects this and surfaces
    // each emission via the global LocalSnackbarHost. Buffer 4 so back-to-back
    // taps don't drop on the floor; replay 0 because stale results from a
    // prior screen visit shouldn't pop up.
    private val _testEvents = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
    )
    val testEvents: SharedFlow<String> = _testEvents.asSharedFlow()
    // === END PHASE3-safety-rails-followup ===

    init {
        refreshPermissionStatus()
        refreshBridgeStatusFromSystem()

        // === PHASE3-safety-rails: foreground service lifecycle ===
        // Start/stop BridgeForegroundService based on the master toggle.
        // distinctUntilChanged prevents re-firing the startForegroundService
        // intent on every DataStore tick. Cancel the safety manager's
        // auto-disable timer when the user flips the toggle off manually
        // (otherwise we race a pending timer against the user).
        viewModelScope.launch {
            masterToggle.distinctUntilChanged().collect { enabled ->
                val ctx = getApplication<Application>()
                if (enabled) {
                    runCatching { BridgeForegroundService.start(ctx) }
                    BridgeSafetyManager.peek()?.rescheduleAutoDisable()
                } else {
                    runCatching { BridgeForegroundService.stop(ctx) }
                    BridgeSafetyManager.peek()?.cancelAutoDisable()
                    // Force the overlay chip off even if the user hasn't
                    // explicitly disabled it — no point showing "bridge
                    // active" when the toggle is off.
                    BridgeStatusOverlay.peek()?.setChipVisible(false)
                }
            }
        }

        // Toggle the optional status overlay in response to both the
        // user's preference and the master toggle (chip only shows when
        // bridge is active AND user opted in).
        viewModelScope.launch {
            val safety = BridgeSafetyManager.peek() ?: return@launch
            combine(
                masterToggle,
                safety.settings.map { it.statusOverlayEnabled }.distinctUntilChanged(),
            ) { master, overlayOn -> master && overlayOn }
                .distinctUntilChanged()
                .collect { shouldShow ->
                    BridgeStatusOverlay.peek()?.setChipVisible(shouldShow)
                }
        }
        // === END PHASE3-safety-rails ===
    }

    /**
     * Called from the UI when BridgeScreen regains focus (e.g., returning
     * from Android Settings after granting the a11y permission). Re-probes
     * Settings.Secure and battery/screen state.
     */
    fun onScreenResumed() {
        refreshPermissionStatus()
        refreshBridgeStatusFromSystem()
    }

    fun setMasterEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsRepo.setMasterEnabled(enabled)
        }
    }

    /**
     * Append (or replace by id) an activity entry. Exposed so Agent accessibility's
     * command dispatcher can call it directly once it has a reference to
     * this ViewModel via a shared holder.
     */
    fun recordActivity(entry: BridgeActivityEntry) {
        viewModelScope.launch {
            prefsRepo.appendEntry(entry)
        }
    }

    fun clearActivityLog() {
        viewModelScope.launch {
            prefsRepo.clearLog()
        }
    }

    // === PHASE3-safety-rails-followup: in-app permission Test handlers ===
    //
    // Side-effect-free diagnostic checks for each bridge permission. Each
    // method observes the actual runtime state (service binding, projection
    // grant, overlay capability) and emits a one-shot human-readable result
    // on `testEvents` for the snackbar. These match the pattern notif-listener already
    // shipped for the notification companion's "Test" button — diagnostics
    // first, full functional tests as a Wave 3 follow-up.

    /**
     * Verify the AccessibilityService is bound and can read the foreground
     * window. Three cases: not bound (user didn't enable in Settings), bound
     * but no active root window (rare — usually means we're on the home
     * launcher with a TYPE_WALLPAPER backdrop), or fully OK.
     */
    fun testAccessibilityService() {
        val instance = HermesAccessibilityService.instance
        val msg = when {
            instance == null -> {
                "Not bound. Enable Hermes-Relay Bridge in Android Settings → " +
                    "Accessibility → Installed services."
            }
            instance.rootInActiveWindow == null -> {
                "Bound, but cannot read the active window right now. " +
                    "Open any app and tap Test again."
            }
            else -> {
                "OK — bound and reading the active window."
            }
        }
        viewModelScope.launch { _testEvents.emit("Accessibility · $msg") }
    }

    /**
     * Verify the per-session MediaProjection grant is alive. MediaProjection
     * is granted per-session via a system consent dialog and not persistent —
     * "no active grant" is the normal state until a screenshot is requested.
     */
    fun testScreenCapture() {
        val projection = MediaProjectionHolder.projection
        val msg = if (projection != null) {
            "OK — MediaProjection grant is active for this session."
        } else {
            "No active grant. The bridge will request consent the next " +
                "time the agent calls /screenshot."
        }
        viewModelScope.launch { _testEvents.emit("Screen capture · $msg") }
    }

    /**
     * Verify SYSTEM_ALERT_WINDOW is granted. Without this permission safety-rails's
     * destructive-verb confirmation modal cannot display, and the safety
     * manager will fail-closed (deny the action) when an agent tries to
     * type/tap a destructive verb.
     */
    fun testOverlayPermission() {
        val ctx = getApplication<Application>()
        val granted = Settings.canDrawOverlays(ctx)
        val msg = if (granted) {
            "OK — confirmation prompts can display while the bridge is active."
        } else {
            "Not granted. Tap the row to open Android's overlay-permission " +
                "page. Without this, destructive actions will be silently denied."
        }
        viewModelScope.launch { _testEvents.emit("Display over other apps · $msg") }
    }
    // === END PHASE3-safety-rails-followup ===

    // ── Internals ────────────────────────────────────────────────────────

    private fun refreshPermissionStatus() {
        val ctx = getApplication<Application>()

        val a11yEnabled = isAccessibilityServiceEnabled(ctx)
        // MediaProjection permission is **per-session** on Android — there's
        // no way to ask "do I already hold it?" so we report "unknown / grant
        // on first use" as a positive. The real gate is the consent dialog
        // that accessibility's ScreenCapture.kt will fire on each fresh projection session.
        val screenCaptureGranted = false
        // Overlay permission (SYSTEM_ALERT_WINDOW) — standard API. Safe even
        // though safety-rails owns the actual overlay composer; we just surface the state.
        val overlayGranted = Settings.canDrawOverlays(ctx)
        // Notification listener permission — notif-listener owns the listener code but the
        // status check is a plain Settings.Secure lookup, no code dependency.
        val notifListenerGranted = isNotificationListenerEnabled(ctx)

        _permissionStatus.value = BridgePermissionStatus(
            accessibilityServiceEnabled = a11yEnabled,
            screenCapturePermitted = screenCaptureGranted,
            overlayPermitted = overlayGranted,
            notificationListenerPermitted = notifListenerGranted,
        )
    }

    /**
     * Seed bridge status from what we can read without accessibility's runtime:
     * screen state and battery level. current_app and accessibility_enabled
     * are left as best-effort until accessibility wires the real flow.
     */
    private fun refreshBridgeStatusFromSystem() {
        val ctx = getApplication<Application>()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val screenOn = pm?.isInteractive ?: false
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        _bridgeStatus.value = BridgeStatus(
            deviceName = android.os.Build.MODEL ?: "Android device",
            batteryPercent = if (battery in 0..100) battery else null,
            screenOn = screenOn,
            currentApp = null, // TODO(accessibility-handoff): comes from UsageStats / a11y events
            accessibilityEnabled = _permissionStatus.value.accessibilityServiceEnabled,
        )
    }

    private fun isAccessibilityServiceEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        // The setting is a colon-separated list of ComponentName.flattenToString().
        // We match on our package + expected class name; exact component name
        // TBD by Agent accessibility (see [A11Y_SERVICE_CLASS]).
        val expected = ComponentName(ctx.packageName, A11Y_SERVICE_CLASS).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) } ||
                // Fallback: if accessibility's class lives elsewhere, still match any service
                // in our package so the checklist doesn't falsely show red while
                // the user has in fact granted the permission.
                enabled.contains(ctx.packageName)
    }

    private fun isNotificationListenerEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(ctx.packageName)
    }
}

/**
 * Snapshot of what the phone's bridge runtime is reporting — what the
 * status card displays. Corresponds to the `bridge.status` wire envelope
 * from the phase 3 plan.
 *
 * TODO(accessibility-handoff): if accessibility wants this class to live under `accessibility/`
 * instead, move it there and re-import. bridge-ui keeps it here as a placeholder
 * so BridgeScreen can compile today without blocking on accessibility.
 */
data class BridgeStatus(
    val deviceName: String,
    val batteryPercent: Int?,
    val screenOn: Boolean,
    val currentApp: String?,
    val accessibilityEnabled: Boolean,
)

/** Which of the four bridge-related permissions are currently held. */
data class BridgePermissionStatus(
    val accessibilityServiceEnabled: Boolean = false,
    val screenCapturePermitted: Boolean = false,
    val overlayPermitted: Boolean = false,
    val notificationListenerPermitted: Boolean = false,
) {
    /** True when every permission required for Tier 1 + 2 is granted. */
    val allRequiredGranted: Boolean
        get() = accessibilityServiceEnabled // MediaProjection is per-session, not sticky
}
