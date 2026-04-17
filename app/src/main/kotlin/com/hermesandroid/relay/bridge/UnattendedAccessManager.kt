package com.hermesandroid.relay.bridge

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v0.4.1 — sideload-only "unattended access" mode.
 *
 * # What this does
 *
 * When the user opts in via the Bridge tab toggle, this manager:
 *
 *  1. Acquires a screen-bright wake lock (`SCREEN_BRIGHT_WAKE_LOCK |
 *     ACQUIRE_CAUSES_WAKEUP | ON_AFTER_RELEASE`) inside [acquireForAction]
 *     so the agent's gesture lands on a lit screen instead of vanishing
 *     into a dimmed-off display.
 *  2. Calls [KeyguardManager.requestDismissKeyguard] from a host activity
 *     when one is available, which silently clears None / Swipe locks and
 *     reports failure for credential locks (PIN / pattern / biometric).
 *  3. Reports a [WakeOutcome.KeyguardBlocked] result to the caller when
 *     the screen woke but the lock didn't clear, so [BridgeCommandHandler]
 *     can surface a structured `keyguard_blocked` error_code in the
 *     bridge response.
 *
 * # Wake-lock flag choice
 *
 * `SCREEN_BRIGHT_WAKE_LOCK` is technically deprecated since API 17 in
 * favor of `Window.FLAG_KEEP_SCREEN_ON` / `Activity.setTurnScreenOn(true)` —
 * but that guidance assumes you have an Activity Window to attach to.
 * We do not: this manager is invoked from a background bridge context
 * where the only "UI surface" is the optional WindowManager overlay,
 * and overlay views can't drive screen wake-up. So we use the
 * historical wake-lock path under an explicit @Suppress, with the same
 * 30-second timeout as a foreground Activity's screen-on attribute would
 * give us, ref-counted across nested commands so a tap-burst doesn't
 * thrash the lock.
 *
 * `ACQUIRE_CAUSES_WAKEUP` forces the screen to come on the moment we
 * acquire — the difference between "wake the screen" and "keep the
 * screen on if it's already on". `ON_AFTER_RELEASE` lets the screen
 * timeout naturally instead of clicking dark the instant we release,
 * which makes the user-visible behaviour feel like a normal phone
 * unlock-and-dim sequence.
 *
 * # Keyguard handling
 *
 * `KeyguardManager.requestDismissKeyguard(activity, callback)` only
 * dismisses None and Swipe locks on third-party apps — Android does
 * not let arbitrary apps walk past a credential lock (PIN / pattern /
 * biometric), and that's not a bug we can work around. The onDismissError
 * / onDismissCancelled callbacks fire with no actionable error code, so
 * we treat any non-success as `keyguard_blocked` for the purposes of
 * the bridge response.
 *
 * If no MainActivity is currently registered with [setHostActivity] (eg.
 * the user is on the home launcher with the app fully backgrounded),
 * we skip the dismiss attempt entirely and rely on the wake-lock alone.
 * The agent will see a lit screen but a present keyguard if a credential
 * lock is set.
 *
 * # Why this is separate from WakeLockManager
 *
 * [com.hermesandroid.relay.power.WakeLockManager] holds a
 * `PARTIAL_WAKE_LOCK` for the CPU during gesture dispatch — it does NOT
 * wake the screen and is always on while the bridge is enabled. This
 * manager is conditional on the unattended-access opt-in, holds a
 * SCREEN_BRIGHT lock specifically to wake the display, and does not
 * fire when unattended is off. Two distinct wake lifecycles, two
 * distinct files; keeps the unattended-access opt-in surgical and easy
 * to audit for security review.
 *
 * # Scoping
 *
 * Sideload-only. Both the Compose toggle row and the `acquireForAction`
 * call sites are gated on `BuildFlavor.isSideload`, which folds the
 * gating away in release builds via R8. The googlePlay flavor never
 * acquires this lock and never invokes requestDismissKeyguard.
 */
object UnattendedAccessManager {

    private const val TAG = "UnattendedAccess"
    private const val WAKE_LOCK_TAG = "HermesRelay::Unattended"

    /**
     * Hard cap on how long we hold the screen-bright lock per action.
     * Long enough to cover a user-visible page load + the gesture itself,
     * short enough that a stuck or buggy command can never pin the
     * display awake indefinitely.
     */
    private const val WAKE_LOCK_TIMEOUT_MS: Long = 30_000L

    /** Backing PowerManager handle, captured once on [initialize]. */
    @Volatile
    private var powerManager: PowerManager? = null

    /** Backing KeyguardManager handle, captured once on [initialize]. */
    @Volatile
    private var keyguardManager: KeyguardManager? = null

    /**
     * Live activity reference used for `requestDismissKeyguard` calls.
     * Held weakly via direct assignment + clear-on-stop semantics to
     * avoid leaking the Activity past its lifecycle. MainActivity sets
     * this in onResume and clears in onPause.
     */
    @Volatile
    private var hostActivity: android.app.Activity? = null

    /**
     * StateFlow surface for the user-toggle ON/OFF state. Mirrored from
     * BridgeSafetySettings.unattendedAccessEnabled by [BridgeViewModel]
     * via [setEnabled]. Read by `acquireForAction` for the fast-path
     * skip when off, and by the Bridge UI for the chip + warning dialog.
     */
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * Snapshot of whether a credential lock (PIN / pattern / biometric)
     * is currently set on the device. Refreshed on demand via
     * [refreshKeyguardState]. Drives the persistent chip on the Bridge
     * screen explaining the credential-lock limitation.
     *
     * Note: this is "is a credential lock CONFIGURED" (`isDeviceSecure`),
     * not "is the device CURRENTLY locked" (`isKeyguardLocked`). The
     * limitation we surface to the user is structural — a configured
     * credential lock means our wake will stop at the lock screen even
     * when the device is currently unlocked at the moment they enable
     * the toggle.
     */
    private val _credentialLockDetected = MutableStateFlow(false)
    val credentialLockDetected: StateFlow<Boolean> = _credentialLockDetected.asStateFlow()

    private val countLock = Any()

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null
    private var lockCount: Int = 0

    /**
     * One-shot initializer. Call from `Application.onCreate` with the
     * application context. Idempotent.
     */
    fun initialize(context: Context) {
        synchronized(countLock) {
            if (powerManager != null) return
            powerManager = context.applicationContext
                .getSystemService(Context.POWER_SERVICE) as? PowerManager
            keyguardManager = context.applicationContext
                .getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            if (powerManager == null) {
                Log.w(TAG, "PowerManager unavailable — unattended-access will no-op")
            }
            // Seed credential-lock state from the live KeyguardManager so the
            // UI doesn't show a stale "no lock" badge on the first frame.
            refreshKeyguardState()
        }
    }

    /**
     * Mirror the BridgeSafetySettings.unattendedAccessEnabled value into
     * this singleton so call sites without DataStore access (eg. bridge
     * command dispatch) can read the live state via [enabled.value].
     * Called from [BridgeViewModel] on every settings tick.
     */
    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        // When the user just turned the feature on, re-probe the keyguard
        // state — they may have changed their lock screen between app
        // launches and the cached value would be stale. When turning off,
        // refresh anyway so the chip reflects current reality.
        refreshKeyguardState()
    }

    /**
     * Wire the activity that should receive `requestDismissKeyguard`
     * calls. Called from `MainActivity.onResume` / `onPause` (set / clear
     * respectively). Multiple activity instances would overwrite each
     * other, but Hermes-Relay is single-activity by design so that's
     * fine.
     */
    fun setHostActivity(activity: android.app.Activity?) {
        hostActivity = activity
    }

    /**
     * Re-read [KeyguardManager.isDeviceSecure] and update the
     * [credentialLockDetected] flow. Cheap — no IPC. Called from
     * [setEnabled], from MainActivity.onResume (so the badge updates
     * if the user just changed their lock), and from [requestDismiss]
     * before deciding whether to attempt dismiss at all.
     */
    fun refreshKeyguardState() {
        val km = keyguardManager ?: return
        // isDeviceSecure: API 23+. Our minSdk is 26, so always available.
        val secure = try {
            km.isDeviceSecure
        } catch (t: Throwable) {
            Log.w(TAG, "isDeviceSecure threw: ${t.message}")
            false
        }
        _credentialLockDetected.value = secure
    }

    /**
     * Outcome of a wake attempt. The bridge command handler maps this
     * onto a structured response so the agent / LLM can react:
     *
     *  - [Success] — screen woke, keyguard cleared (or wasn't present).
     *    Action can proceed normally.
     *  - [SuccessNoKeyguardChange] — screen woke, no dismiss attempted
     *    or no keyguard to dismiss. Action can proceed.
     *  - [KeyguardBlocked] — screen woke but the keyguard refused to
     *    dismiss (credential lock present, or activity not foregrounded).
     *    Action will likely fail; surface as `keyguard_blocked`.
     *  - [Disabled] — feature is off (user hasn't opted in, or this is
     *    a googlePlay build). No-op fast path.
     */
    enum class WakeOutcome {
        Success,
        SuccessNoKeyguardChange,
        KeyguardBlocked,
        Disabled,
    }

    /**
     * Acquire the screen-bright wake lock + opportunistically request
     * keyguard dismiss. Synchronous — does not suspend. The caller (
     * [com.hermesandroid.relay.accessibility.ActionExecutor] wrapper, or
     * [com.hermesandroid.relay.network.handlers.BridgeCommandHandler]
     * pre-dispatch hook) holds onto the result and decides whether to
     * proceed with the action.
     *
     * Returns [WakeOutcome.Disabled] when the user hasn't opted in —
     * this is the fast path, no wake lock acquired. When enabled,
     * returns [WakeOutcome.Success] / [SuccessNoKeyguardChange] /
     * [KeyguardBlocked] depending on the dismiss attempt outcome.
     *
     * The wake lock auto-releases via the platform's 30s timeout — we
     * don't release explicitly per call because the bridge command may
     * take several gestures to complete and we want one continuous
     * wake-up, not a stutter. [release] is provided for the master
     * toggle off path.
     *
     * # Compatibility shim
     *
     * SCREEN_BRIGHT_WAKE_LOCK is API-21+ but @Suppress'd as deprecated
     * since API 17 — see class-level KDoc for why we accept the warning.
     */
    @Suppress("DEPRECATION")
    fun acquireForAction(): WakeOutcome {
        if (!_enabled.value) return WakeOutcome.Disabled

        val pm = powerManager ?: run {
            Log.w(TAG, "acquireForAction: PowerManager not initialized")
            return WakeOutcome.Disabled
        }

        // Build the wake lock lazily — first call after enable creates it,
        // subsequent calls re-use. setReferenceCounted(false) so a manual
        // release() always fully releases regardless of acquire() count.
        synchronized(countLock) {
            if (wakeLock == null) {
                wakeLock = try {
                    pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                        WAKE_LOCK_TAG,
                    ).apply { setReferenceCounted(false) }
                } catch (t: Throwable) {
                    Log.w(TAG, "newWakeLock threw: ${t.message}")
                    return WakeOutcome.Disabled
                }
            }
            val lock = wakeLock ?: return WakeOutcome.Disabled
            try {
                if (!lock.isHeld) {
                    lock.acquire(WAKE_LOCK_TIMEOUT_MS)
                }
                lockCount += 1
            } catch (t: Throwable) {
                Log.w(TAG, "wakeLock.acquire threw: ${t.message}")
                return WakeOutcome.Disabled
            }
        }

        // Best-effort keyguard dismiss. The result feeds into the
        // returned WakeOutcome — we don't actually block on the
        // callback because the bridge command's own gesture dispatch
        // will fail-then-retry naturally if the keyguard is still
        // present, and waiting for the dismiss callback would add
        // noticeable latency to every command.
        return requestDismiss()
    }

    /**
     * Synchronous keyguard dismiss attempt. Returns:
     *  - [WakeOutcome.SuccessNoKeyguardChange] when there's no keyguard
     *    to dismiss, no host activity registered, or the device is
     *    pre-API-26 (we minSdk=26 so this is unreachable but defensive).
     *  - [WakeOutcome.Success] when we fired the dismiss request and
     *    the device has no credential lock (None / Swipe locks dismiss
     *    silently).
     *  - [WakeOutcome.KeyguardBlocked] when we tried to dismiss but the
     *    device has a credential lock — the dismiss will fail and the
     *    bridge command should surface this so the LLM doesn't blindly
     *    keep tapping at the lock screen.
     *
     * The actual `requestDismissKeyguard(activity, callback)` call is
     * fire-and-forget — the platform invokes the callback async, but
     * we make the success/failure decision based on isDeviceSecure
     * rather than waiting for the callback. If isDeviceSecure() is
     * true, dismiss WILL fail; if false, dismiss WILL succeed. No need
     * to suspend the caller for a callback we can predict.
     */
    @Suppress("MissingPermission")
    private fun requestDismiss(): WakeOutcome {
        refreshKeyguardState()

        val km = keyguardManager ?: return WakeOutcome.SuccessNoKeyguardChange
        val activity = hostActivity ?: return WakeOutcome.SuccessNoKeyguardChange

        val isLocked = try {
            km.isKeyguardLocked
        } catch (t: Throwable) {
            Log.w(TAG, "isKeyguardLocked threw: ${t.message}")
            false
        }
        if (!isLocked) {
            // Nothing to dismiss — screen wake alone is sufficient.
            return WakeOutcome.SuccessNoKeyguardChange
        }

        // requestDismissKeyguard requires API 26 — our minSdk matches.
        // The system ignores the request silently if the activity isn't
        // foregrounded, which is fine (we'd report KeyguardBlocked
        // anyway based on the credential-lock predict).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                km.requestDismissKeyguard(activity, /* callback = */ null)
            } catch (t: Throwable) {
                Log.w(TAG, "requestDismissKeyguard threw: ${t.message}")
                return WakeOutcome.KeyguardBlocked
            }
        }

        return if (_credentialLockDetected.value) {
            WakeOutcome.KeyguardBlocked
        } else {
            WakeOutcome.Success
        }
    }

    /**
     * Release the screen-bright wake lock immediately. Called from the
     * master-toggle-off path so the screen returns to its natural
     * timeout behaviour as soon as the user disables the bridge — no
     * residual "screen stuck on" 30 seconds after disable.
     *
     * Safe to call when no lock is held (no-op).
     */
    fun release() {
        synchronized(countLock) {
            val lock = wakeLock ?: return
            lockCount = 0
            try {
                if (lock.isHeld) lock.release()
            } catch (t: Throwable) {
                Log.w(TAG, "wakeLock.release threw: ${t.message}")
            }
        }
    }
}
