package com.hermesandroid.relay.bridge

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v0.4.1 polish — cross-layer tracker that coordinates "agent run
 * completed → auto-return to Hermes-Relay" across two independent
 * completion signals:
 *
 *  1. **Chat-tab SSE `run.completed`** — fires fast (<50ms after the
 *     server emits the event) but ONLY when the active chat is driven
 *     from the phone's own Chat tab. Discord-originated runs, CLI
 *     runs, or any other frontend never deliver SSE to the phone.
 *
 *  2. **Bridge-idle timer** — fires [IDLE_AUTO_RETURN_MS] after the
 *     last bridge command dispatch. Works for ANY frontend because
 *     the phone sees all bridge traffic by definition. This is the
 *     catch-all fallback for Discord / CLI / Slack / external clients.
 *
 * Whichever signal arrives first wins. The other is cancelled.
 *
 * # The problem
 *
 * `android_return_to_hermes` is an LLM-called tool — the agent is
 * prompted to call it as the FINAL step of any phone-control task, but
 * LLMs forget, especially on longer runs or when the frontend isn't
 * the phone itself. When that happens the phone is left on Starbucks /
 * Chrome / wherever, and the user has to manually switch back to see
 * the agent's response.
 *
 * # Wiring (two callers)
 *
 * Bridge-side (works for all frontends):
 *  - `BridgeCommandHandler.dispatch()` → [onBridgeCommandActivity]
 *    on every non-polling command (resets the idle timer).
 *  - `BridgeCommandHandler.respond()` → [markForegroundChanged]
 *    on successful `/open_app` / `/send_intent` (arms the idle timer).
 *  - `BridgeCommandHandler.respond()` → [markReturnedToHermes] on
 *    successful `/return_to_hermes` (cancels the idle timer).
 *
 * Chat-side (fast path when applicable):
 *  - `ChatViewModel.onCompleteCb` → [notifyRunCompleted] on SSE
 *    `run.completed`.
 *
 * Either path converges on the same [autoReturnCallback], which
 * [com.hermesandroid.relay.viewmodel.ConnectionViewModel] wires to a
 * local `/return_to_hermes` dispatch.
 *
 * # Why a singleton
 *
 * Chat and Bridge live in separate ViewModels owned by the same
 * Activity. Plumbing a cross-VM reference through RelayApp just for
 * this coordination is heavier than the process-scoped state needs.
 *
 * # Concurrency
 *
 * Flag mutations are `@Volatile`; the idle timer Job is cancel-safe
 * under race. Multiple concurrent `/open_app` responses during a run
 * all call [markForegroundChanged] → the Job is cancel-and-restart,
 * so the timer always extends to IDLE_AUTO_RETURN_MS from the most
 * recent activity. Spurious double calls to [notifyRunCompleted]
 * after the flag is cleared are no-ops.
 */
object BridgeRunTracker {

    private const val TAG = "BridgeRunTracker"

    /**
     * How long to wait after the last bridge command before assuming
     * the agent run is done and firing an auto-return.
     *
     * 12s is long enough to cover slow LLM reasoning gaps between
     * tool calls (image-analysis turns can take 5–10s on claude-opus
     * and similar), but short enough that the user isn't stranded for
     * an obvious delay after a forgotten return. Callers can adjust
     * via [configureIdleTimeout] if their agent's pacing differs.
     */
    private const val IDLE_AUTO_RETURN_MS: Long = 12_000L

    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var idleTimeoutMs: Long = IDLE_AUTO_RETURN_MS

    @Volatile
    private var foregroundChangedDuringRun: Boolean = false

    @Volatile
    private var autoReturnCallback: (() -> Unit)? = null

    @Volatile
    private var idleJob: Job? = null

    /**
     * Flag that the bridge moved the foreground app away from
     * Hermes-Relay during the currently-active agent run, and arm the
     * idle-timer auto-return fallback. Idempotent — repeated calls
     * within one run extend the idle window to
     * [IDLE_AUTO_RETURN_MS] from the most recent call.
     *
     * Called by [BridgeCommandHandler.respond] on successful dispatch
     * of paths in its `foregroundShiftingPaths` set.
     */
    fun markForegroundChanged() {
        foregroundChangedDuringRun = true
        armIdleTimer()
    }

    /**
     * Reset the idle timer without changing the flag. Called from
     * [BridgeCommandHandler.dispatch] on every non-polling bridge
     * command so a long multi-step run keeps the timer alive —
     * otherwise a slow LLM reasoning gap would trigger a premature
     * auto-return mid-run.
     *
     * No-op when the flag is false: we only care about idle tracking
     * when there's a pending return to fire, and we don't want every
     * /screen poll to spin up a coroutine during runs that never
     * touched the foreground.
     */
    fun onBridgeCommandActivity() {
        if (foregroundChangedDuringRun) {
            armIdleTimer()
        }
    }

    /**
     * Clear the foreground-changed flag because we're back on Hermes
     * and cancel any pending idle timer. Called by
     * [BridgeCommandHandler.respond] on successful dispatch of
     * `/return_to_hermes` — whether the agent called the tool
     * explicitly, the idle timer fired, or the Chat SSE path fired.
     * Without this, an LLM-initiated return would leave the flag set
     * and fire a redundant (though harmless) auto-return on
     * `run.completed`.
     */
    fun markReturnedToHermes() {
        foregroundChangedDuringRun = false
        idleJob?.cancel()
        idleJob = null
    }

    /**
     * Test-only override for the idle timeout. Kept internal so
     * production code isn't tempted to fiddle with it from UI.
     */
    internal fun configureIdleTimeout(ms: Long) {
        idleTimeoutMs = ms
    }

    private fun armIdleTimer() {
        idleJob?.cancel()
        idleJob = timerScope.launch {
            delay(idleTimeoutMs)
            // Re-check flag after the delay — it may have been cleared
            // by Chat SSE or an explicit /return_to_hermes while we
            // were asleep. If still set, fire auto-return.
            if (foregroundChangedDuringRun) {
                foregroundChangedDuringRun = false
                val cb = autoReturnCallback
                if (cb != null) {
                    Log.i(TAG, "idle-timer expired + bridge touched foreground — firing auto-return")
                    runCatching { cb() }.onFailure {
                        Log.w(TAG, "idle-timer auto-return callback threw: ${it.message}")
                    }
                } else {
                    Log.v(TAG, "idle-timer expired but no auto-return callback registered")
                }
            }
            idleJob = null
        }
    }

    /**
     * Wire the auto-return action. Called once from
     * [com.hermesandroid.relay.viewmodel.ConnectionViewModel] init
     * with a lambda that fires a local `/return_to_hermes` command
     * via `BridgeCommandHandler.handleLocalCommand`.
     *
     * Overwrites any previously-registered callback — intended to be
     * called exactly once per process lifetime, but idempotent if a
     * future caller needs to re-register (e.g. tests).
     */
    fun registerAutoReturnCallback(callback: () -> Unit) {
        autoReturnCallback = callback
    }

    /**
     * Called by `ChatViewModel.onCompleteCb` when the SSE
     * `run.completed` event fires. If the bridge moved foreground
     * during this run AND an auto-return callback is registered, fire
     * it. Clears the flag regardless so the next run starts clean.
     *
     * The callback itself is responsible for deciding whether
     * `/return_to_hermes` is actually needed (e.g. skip if the current
     * foreground is already Hermes because the user manually
     * navigated back). This tracker only answers "did we touch the
     * foreground during this run?"
     */
    fun notifyRunCompleted() {
        // Cancel the idle timer — SSE beat it to the punch, no point
        // letting it fire a duplicate callback.
        idleJob?.cancel()
        idleJob = null
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
        idleJob?.cancel()
        idleJob = null
    }
}
