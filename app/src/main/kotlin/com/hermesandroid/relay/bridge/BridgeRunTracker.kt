package com.hermesandroid.relay.bridge

import android.util.Log

/**
 * Coordinates return to Hermes-Relay after an actual agent run completion.
 *
 * [BridgeCommandHandler.respond] marks successful foreground-shifting commands
 * via [markForegroundChanged], and clears that state via [markReturnedToHermes]
 * after a successful explicit `/return_to_hermes`.
 *
 * `ChatViewModel.finalizeTurnSideEffects` calls [notifyRunCompleted] after
 * successful completion, including recovered turns.
 * [com.hermesandroid.relay.viewmodel.ConnectionViewModel] registers the local
 * `/return_to_hermes` dispatch through [registerAutoReturnCallback]. Runs from
 * other frontends that do not deliver completion to the phone must explicitly
 * request `/return_to_hermes` when finished.
 *
 * Bridge inactivity is not a completion signal: reasoning, other tools, and
 * user interaction can leave arbitrary gaps between phone commands. No timer
 * may steal the foreground while that work is still in progress.
 */
object BridgeRunTracker {

    private const val TAG = "BridgeRunTracker"

    @Volatile
    private var foregroundChangedDuringRun: Boolean = false

    @Volatile
    private var autoReturnCallback: (() -> Unit)? = null

    /** Record a successful foreground-shifting command for run completion. */
    fun markForegroundChanged() {
        foregroundChangedDuringRun = true
    }

    /** Compatibility hook. Command activity no longer schedules an idle return. */
    fun onBridgeCommandActivity() = Unit

    /**
     * Clear a pending return after successful `/return_to_hermes`, whether
     * requested explicitly or dispatched by the run-completion callback.
     */
    fun markReturnedToHermes() {
        foregroundChangedDuringRun = false
    }

    /** Compatibility with existing tests; idle timeouts no longer trigger returns. */
    @Suppress("UNUSED_PARAMETER")
    internal fun configureIdleTimeout(ms: Long) = Unit

    /**
     * Wire the local `/return_to_hermes` dispatch. Replaces any prior callback.
     * The callback decides whether the current foreground still needs a return.
     */
    fun registerAutoReturnCallback(callback: () -> Unit) {
        autoReturnCallback = callback
    }

    /**
     * Handle actual `run.completed`, returning only if this run shifted the
     * foreground. Clear the flag even without a callback so duplicate completion
     * signals and subsequent runs cannot repeat the return.
     */
    fun notifyRunCompleted() {
        if (foregroundChangedDuringRun) {
            foregroundChangedDuringRun = false
            val cb = autoReturnCallback
            if (cb != null) {
                Log.i(TAG, "run.completed + bridge touched foreground — firing auto-return")
                runCatching { cb() }.onFailure {
                    Log.w(TAG, "auto-return callback threw: ${it.message}")
                }
            } else {
                Log.v(TAG, "run.completed + bridge touched foreground, but no callback registered")
            }
        }
    }

    /** Test-only reset. */
    internal fun reset() {
        foregroundChangedDuringRun = false
        autoReturnCallback = null
    }
}
