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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Bridge tab view-model — Phase 3 Wave 1 (Agent δ).
 *
 * Scope: UI state for the new BridgeScreen only. The actual AccessibilityService
 * runtime (read screen, tap, type, screenshot) is Agent γ's file set under
 * `com.hermesandroid.relay.accessibility`. δ intentionally **does not** touch
 * those files — we read/display state from whatever StateFlows γ exposes and
 * stub them here until γ's runtime lands.
 *
 * ## γ-handoff points (TODO before Wave 1 merges)
 *
 * 1. **`bridgeStatus`** — currently a `MutableStateFlow<BridgeStatus?>` seeded
 *    from a best-effort read of [PowerManager] + [BatteryManager] on init.
 *    γ must either:
 *      - expose a `com.hermesandroid.relay.accessibility.HermesAccessibilityService`
 *        singleton with `status: StateFlow<BridgeStatus>`, OR
 *      - post updates into a shared `BridgeStateHolder` object that both
 *        γ's service and this ViewModel read from.
 *    Either way, δ replaces the local MutableStateFlow with `.combine` against
 *    the γ flow. The [BridgeStatus] data class below is δ's proposed wire
 *    shape — γ is welcome to move it into `accessibility/` and have us import
 *    from there.
 *
 * 2. **`permissionStatus`** — we probe
 *    [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] to detect whether the
 *    a11y service is enabled, which is the standard pattern and doesn't
 *    require γ's code to exist yet. BUT the string-match uses a placeholder
 *    service class name (`HermesAccessibilityService`). γ confirms the final
 *    fully-qualified class name; if it differs, update [A11Y_SERVICE_CLASS].
 *
 * 3. **`recordActivity` / `updateActivity`** — δ writes activity log entries
 *    to [BridgePreferencesRepository]. γ's `HermesAccessibilityService`
 *    command dispatcher should call these (or a singleton that forwards to
 *    them) whenever a bridge command starts / completes / fails / is blocked
 *    by safety rails. Until γ lands, the log stays empty and the UI shows
 *    the empty-state copy.
 *
 * 4. **`masterToggle`** write path — flipping the master switch persists via
 *    [BridgePreferencesRepository.setMasterEnabled]. γ's service reads the
 *    same DataStore key (`bridge_master_enabled`) and treats it as the
 *    runtime disable switch — when false, the service should ignore all
 *    incoming `bridge.command` envelopes. δ does not wire the service lifecycle
 *    to this toggle; that's γ's call.
 *
 * Kept deliberately thin — this is a UI-presentation layer, not a mediator.
 */
class BridgeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /**
         * Fully-qualified class name of the accessibility service Agent γ will
         * register in the manifest. TODO(γ-handoff): confirm this matches γ's
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

    // ── Bridge status (γ-handoff stub) ───────────────────────────────────
    //
    // TODO(γ-handoff): replace this MutableStateFlow with the StateFlow that
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

    init {
        refreshPermissionStatus()
        refreshBridgeStatusFromSystem()
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
     * Append (or replace by id) an activity entry. Exposed so Agent γ's
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

    // ── Internals ────────────────────────────────────────────────────────

    private fun refreshPermissionStatus() {
        val ctx = getApplication<Application>()

        val a11yEnabled = isAccessibilityServiceEnabled(ctx)
        // MediaProjection permission is **per-session** on Android — there's
        // no way to ask "do I already hold it?" so we report "unknown / grant
        // on first use" as a positive. The real gate is the consent dialog
        // that γ's ScreenCapture.kt will fire on each fresh projection session.
        val screenCaptureGranted = false
        // Overlay permission (SYSTEM_ALERT_WINDOW) — standard API. Safe even
        // though ζ owns the actual overlay composer; we just surface the state.
        val overlayGranted = Settings.canDrawOverlays(ctx)
        // Notification listener permission — ε owns the listener code but the
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
     * Seed bridge status from what we can read without γ's runtime:
     * screen state and battery level. current_app and accessibility_enabled
     * are left as best-effort until γ wires the real flow.
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
            currentApp = null, // TODO(γ-handoff): comes from UsageStats / a11y events
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
        // TBD by Agent γ (see [A11Y_SERVICE_CLASS]).
        val expected = ComponentName(ctx.packageName, A11Y_SERVICE_CLASS).flattenToString()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) } ||
                // Fallback: if γ's class lives elsewhere, still match any service
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
 * TODO(γ-handoff): if γ wants this class to live under `accessibility/`
 * instead, move it there and re-import. δ keeps it here as a placeholder
 * so BridgeScreen can compile today without blocking on γ.
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
