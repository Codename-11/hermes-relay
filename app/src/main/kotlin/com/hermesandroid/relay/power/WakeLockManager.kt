package com.hermesandroid.relay.power

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * A8 — Wake-scope wrapper for bridge gesture dispatch.
 *
 * ## Why this exists
 *
 * When the phone is idle (screen dimmed or the CPU has drifted into a
 * low-power state), `AccessibilityService.dispatchGesture` and
 * `ACTION_SET_TEXT` sometimes silently fail or land after a multi-second
 * lag — the gesture's `GestureResultCallback` never fires because the
 * looper stalls. The symptom on-device is a bridge command that "worked"
 * over the wire but produced no visible effect.
 *
 * Wrapping the gesture-dispatching entry points of [com.hermesandroid.relay.accessibility.ActionExecutor]
 * in [wakeForAction] holds a wake lock just long enough for the gesture
 * to be issued, dispatched, and its completion callback delivered, then
 * releases. Ref-counted so nested calls (e.g. `tapText` → `tap`) don't
 * release each other's lock prematurely.
 *
 * ## Wake-lock flag choice
 *
 * We use [PowerManager.PARTIAL_WAKE_LOCK]. The classic "screen stays on"
 * flags (`SCREEN_BRIGHT_WAKE_LOCK`, `FULL_WAKE_LOCK`) have been deprecated
 * since API 17 — Google's guidance is that anything needing the screen
 * awake should use `Window.FLAG_KEEP_SCREEN_ON` or
 * `Activity.setTurnScreenOn(true)` / `KeyguardManager.requestDismissKeyguard`
 * from a visible surface. We are in a background service with no window,
 * so those APIs don't apply — but we also don't need the screen bright;
 * we just need the CPU to stay scheduled long enough for the gesture to
 * land and its callback to fire. `PARTIAL_WAKE_LOCK` does exactly that
 * and is the only non-deprecated wake-lock flag remaining under our
 * minSdk = 26 / targetSdk = 35 constraints.
 *
 * Note: this does **not** wake a fully-off screen. If the device is
 * genuinely locked, the gesture will still no-op against the lock screen
 * UI — that's a `KeyguardManager` / foreground-activity problem, not a
 * wake-lock problem, and is intentionally out of scope for this unit.
 *
 * ## Safety rails
 *
 * - **Ref-counted** under a `synchronized` block so nested calls share
 *   one physical wake lock.
 * - **10-second hard timeout** on the underlying [PowerManager.WakeLock]
 *   so a crashed or long-stalled gesture can never pin the CPU awake.
 *   Even a bridge command that somehow hangs for minutes will see the
 *   lock self-release after 10s.
 * - **`try { block() } finally { release }`** so throwing gesture code
 *   still releases the lock.
 * - **Non-reference-counted underlying [PowerManager.WakeLock]** — we
 *   manage the count ourselves in [lockCount] rather than letting
 *   `WakeLock.acquire/release` do it, because PowerManager's ref-count
 *   is a process-global footgun that can outlive our suspend frames on
 *   coroutine cancellation.
 */
object WakeLockManager {

    private const val TAG = "WakeLockManager"
    private const val WAKE_LOCK_TAG = "HermesRelay::BridgeAction"

    /**
     * Hard cap — the lock will self-release after this many ms even if
     * the block is still running. Long-held wake locks are a classic
     * battery-drain bug; gesture dispatch should never take this long.
     */
    private const val TIMEOUT_MS: Long = 10_000L

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    private val countLock = Any()
    private var lockCount: Int = 0

    /**
     * One-shot initializer. Call from `Application.onCreate` with the
     * application context so we can build the underlying wake lock
     * without leaking an Activity. Idempotent — subsequent calls are
     * no-ops.
     */
    fun initialize(context: Context) {
        if (wakeLock != null) return
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm == null) {
            Log.w(TAG, "PowerManager unavailable — WakeLockManager will no-op")
            return
        }
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            // We manage ref-counting ourselves; setReferenceCounted(false)
            // means release() always fully releases, regardless of how many
            // acquire() calls preceded it.
            setReferenceCounted(false)
        }
    }

    /**
     * Run [block] with a partial wake lock held. Ref-counted so nested
     * calls (e.g. `tapText` inside `tap`) share one physical lock. The
     * lock is released in a `finally` so exceptions still clean up.
     *
     * If [initialize] was never called (e.g. the manager was never
     * wired up in `Application.onCreate`), this falls through to simply
     * running [block] with no wake lock held — the safer failure mode
     * is "bridge works but may glitch when idle", not "bridge crashes".
     */
    suspend fun <T> wakeForAction(block: suspend () -> T): T {
        val acquired = tryAcquire()
        return try {
            block()
        } finally {
            if (acquired) {
                tryRelease()
            }
        }
    }

    /**
     * Acquire the wake lock if this is the outermost call. Returns true
     * if the caller is responsible for releasing (i.e. they bumped the
     * ref count — this matters for the finally block in [wakeForAction]).
     */
    private fun tryAcquire(): Boolean {
        val lock = wakeLock ?: return false
        synchronized(countLock) {
            if (lockCount == 0) {
                try {
                    lock.acquire(TIMEOUT_MS)
                } catch (t: Throwable) {
                    Log.w(TAG, "wakeLock.acquire threw: ${t.message}")
                    return false
                }
            }
            lockCount += 1
            return true
        }
    }

    /**
     * Decrement the ref count and release the underlying lock when the
     * last waiter drops off. Tolerant of already-timed-out locks —
     * `isHeld` check avoids an `IllegalStateException` on release of a
     * lock that the 10-second timeout already reaped.
     */
    private fun tryRelease() {
        val lock = wakeLock ?: return
        synchronized(countLock) {
            if (lockCount <= 0) {
                // Shouldn't happen, but if it does, don't let an
                // underflow wedge the counter.
                lockCount = 0
                return
            }
            lockCount -= 1
            if (lockCount == 0) {
                try {
                    if (lock.isHeld) {
                        lock.release()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "wakeLock.release threw: ${t.message}")
                }
            }
        }
    }
}
