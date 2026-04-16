package com.hermesandroid.relay.bridge

import android.content.Context
import android.util.Log
import com.hermesandroid.relay.data.BridgeSafetyPreferencesRepository
import com.hermesandroid.relay.data.BridgeSafetySettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 3 — safety-rails `bridge-safety-rails`
 *
 * Central enforcement point for Tier 5 safety: per-app blocklist, destructive
 * verb confirmation, and idle-based auto-disable. Owned as a singleton-per-
 * process by [ConnectionViewModel] and injected into [BridgeCommandHandler].
 *
 * # Integration surface
 *
 *  - [checkPackageAllowed] — called with the currently foregrounded package
 *    (from `HermesAccessibilityService.currentApp`). Returns false if the
 *    user has that package on the blocklist. Caller maps false → HTTP 403.
 *
 *  - [requiresConfirmation] — cheap synchronous predicate: does the agent's
 *    requested action (`/tap_text` / `/type`) carry one of the user's
 *    destructive verbs in its body? Returns false for every other path.
 *
 *  - [awaitConfirmation] — suspend function that shows a system-overlay
 *    modal and waits for the user to tap Allow / Deny. Returns true on
 *    allow, false on deny or on [BridgeSafetySettings.confirmationTimeoutSeconds]
 *    timeout. The suspending `BridgeCommandHandler` action coroutine is
 *    what's held here — the phone's agent call stays open until the user
 *    reacts, which is exactly the UX we want (the server sees a slow
 *    response, not a denial race).
 *
 *  - [rescheduleAutoDisable] — every accepted command bumps the idle timer
 *    forward; after [BridgeSafetySettings.autoDisableMinutes] of silence
 *    the master toggle flips off and a one-shot notification fires.
 *    [cancelAutoDisable] cancels the pending timer (called when the master
 *    toggle flips off manually, so we don't race the timer against the
 *    user).
 *
 * # Overlay <-> coroutine wiring
 *
 * The confirmation modal lives in a [SYSTEM_ALERT_WINDOW]-backed overlay
 * (see [ConfirmationOverlayHost]). The `awaitConfirmation` coroutine
 * registers a pending entry keyed by a monotonic request id, shows the
 * overlay, and then waits on the entry's `CompletableDeferred<Boolean>`
 * under a `withTimeout`. The overlay's Allow / Deny buttons complete the
 * deferred. On timeout we dismiss the overlay from the manager side and
 * return false ("treat silence as deny"). If the overlay host is not
 * available — e.g. the user hasn't granted `SYSTEM_ALERT_WINDOW` yet — we
 * fail-closed and return false so no destructive action slips through.
 *
 * # No WorkManager
 *
 * The Android app does not depend on androidx.work. [AutoDisableWorker]
 * documents the canonical pattern, but the live path is a coroutine
 * `Job` owned by this manager, delayed by the configured minutes. This is
 * acceptable because we are the in-memory owner of the master-toggle flow
 * — no inter-process or cross-restart scheduling is needed. On process
 * death the master toggle is simply evaluated fresh from DataStore, and
 * any command not explicitly sent within the idle window never actually
 * happens because the app isn't running.
 */
class BridgeSafetyManager(
    context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "BridgeSafetyMgr"

        @Volatile
        private var INSTANCE: BridgeSafetyManager? = null

        /**
         * Process-wide accessor. Both [BridgeCommandHandler] and the overlay
         * host need to reach the same manager instance without a DI graph;
         * [ConnectionViewModel] calls [install] once at init time.
         */
        fun peek(): BridgeSafetyManager? = INSTANCE

        fun install(context: Context, scope: CoroutineScope): BridgeSafetyManager {
            val existing = INSTANCE
            if (existing != null) return existing
            val created = BridgeSafetyManager(context.applicationContext, scope)
            INSTANCE = created
            return created
        }
    }

    private val appContext: Context = context.applicationContext
    private val prefsRepo = BridgeSafetyPreferencesRepository(appContext)

    /** Latest settings snapshot — UI + checks read this via [settings]. */
    private val _settings = MutableStateFlow(BridgeSafetySettings())
    val settings: StateFlow<BridgeSafetySettings> = _settings.asStateFlow()

    /** True once the DataStore collector has ticked at least once. */
    @Volatile
    private var settingsHydrated: Boolean = false

    /**
     * Pending confirmation requests keyed by a monotonic id. The overlay's
     * Allow / Deny callbacks complete the deferred by looking up the id the
     * overlay was opened for. [ConcurrentHashMap] because the completion
     * happens on the Compose overlay thread and registration on the bridge
     * scope's dispatcher.
     */
    private val pendingConfirmations = ConcurrentHashMap<Long, PendingConfirmation>()
    private val nextRequestId = AtomicLong(0L)

    /** Coroutine job that fires auto-disable after idle. */
    @Volatile
    private var autoDisableJob: Job? = null

    /**
     * Remaining time (epoch millis) for the current auto-disable job, or
     * null when idle. BridgeSafetySummaryCard reads this as a countdown.
     */
    private val _autoDisableAtMs = MutableStateFlow<Long?>(null)
    val autoDisableAtMs: StateFlow<Long?> = _autoDisableAtMs.asStateFlow()

    init {
        // Eagerly observe DataStore — writes from the settings screen flow
        // into the cache so checkPackageAllowed / requiresConfirmation can
        // read synchronously without a suspend hop.
        scope.launch {
            prefsRepo.settings.collect { latest ->
                _settings.value = latest
                settingsHydrated = true
            }
        }
    }

    // ── Blocklist ────────────────────────────────────────────────────────

    /**
     * Returns false iff [packageName] is explicitly blocklisted. A null
     * or blank package (accessibility service hasn't seen a window yet)
     * is treated as allowed — we can't block what we don't know.
     */
    suspend fun checkPackageAllowed(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return true
        val snapshot = currentSettings()
        return packageName !in snapshot.blocklist
    }

    // ── Destructive verbs ────────────────────────────────────────────────

    suspend fun requiresConfirmation(method: String, text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        // Pre-0.4.0 only /tap_text and /type were gated — node-id taps
        // slipped past because the gate never looked up the tapped
        // node's text. Bailey 2026-04-15 hit this: after denying an
        // SMS, the agent fell back to android_open_app + android_tap
        // (by nodeId) on the Messages app's "Send" button, bypassing
        // the verb modal. BridgeCommandHandler.extractDestructiveVerbText
        // now resolves the node's text for /tap + /long_press too, and
        // this method gates on any of the four paths as long as the
        // caller supplies a text argument.
        if (method != "/tap_text" &&
            method != "/type" &&
            method != "/tap" &&
            method != "/long_press"
        ) return false
        val verbs = currentSettings().destructiveVerbs
        if (verbs.isEmpty()) return false
        return containsDestructiveVerb(text, verbs)
    }

    /**
     * Show the confirmation overlay and suspend until the user reacts or
     * the timeout elapses. Returns true to allow, false to deny.
     *
     * Fail-closed: if the SYSTEM_ALERT_WINDOW permission hasn't been
     * granted, or if the overlay host can't be reached for any reason, we
     * return false and log a warning. Better a missed agent command than
     * a silently-allowed destructive action.
     */
    suspend fun awaitConfirmation(method: String, text: String?): Boolean {
        val snapshot = currentSettings()
        val timeoutMs = snapshot.confirmationTimeoutSeconds * 1000L
        val requestId = nextRequestId.incrementAndGet()

        val deferred = CompletableDeferred<Boolean>()
        val pending = PendingConfirmation(
            id = requestId,
            method = method,
            text = text.orEmpty(),
            verb = text?.let { firstMatchedVerb(it, snapshot.destructiveVerbs) }.orEmpty(),
            deferred = deferred,
        )
        pendingConfirmations[requestId] = pending

        val host = ConfirmationOverlayHost.instance
        if (host == null) {
            Log.w(TAG, "awaitConfirmation: no overlay host installed — failing closed (deny)")
            pendingConfirmations.remove(requestId)
            return false
        }

        // ComposeView creation + setContent inside BridgeStatusOverlay.showConfirmation
        // must run on the Main thread. This awaitConfirmation call can originate
        // from either the WSS-incoming BridgeCommandHandler path (already on Main
        // via ChannelMultiplexer's dispatcher) OR from the in-process voice-intent
        // local-dispatch path (Dispatchers.Default, via RealVoiceBridgeIntentHandler's
        // own scope). Using Dispatchers.Main.immediate makes the first case a no-op
        // and only schedules on Main for the second — one line handles both callers.
        //
        // Before this fix the off-thread call threw from ComposeView.setContent,
        // the outer runCatching swallowed it, and the log message mislabelled the
        // cause as "likely overlay permission missing" which sent debugging up a
        // wrong tree (2026-04-15 on-device test with overlay permission granted
        // but voice SMS still never showed the modal).
        val shown = runCatching {
            withContext(Dispatchers.Main.immediate) {
                host.showConfirmation(pending) { resolution ->
                    resolveConfirmation(requestId, resolution)
                }
            }
        }
        if (shown.isFailure) {
            Log.w(TAG, "awaitConfirmation: host.showConfirmation threw — denying", shown.exceptionOrNull())
            pendingConfirmations.remove(requestId)
            return false
        }

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            Log.i(TAG, "awaitConfirmation: timed out after ${timeoutMs}ms — denying")
            host.dismissConfirmation(requestId)
            pendingConfirmations.remove(requestId)
            false
        } catch (t: Throwable) {
            Log.w(TAG, "awaitConfirmation: unexpected failure — denying", t)
            host.dismissConfirmation(requestId)
            pendingConfirmations.remove(requestId)
            false
        }
    }

    /**
     * Called from the overlay UI when the user taps Allow / Deny (or when
     * the overlay is dismissed programmatically). Safe to call for an
     * unknown id (we just drop on the floor).
     */
    fun resolveConfirmation(requestId: Long, allowed: Boolean) {
        val pending = pendingConfirmations.remove(requestId) ?: return
        pending.deferred.complete(allowed)
    }

    // ── Auto-disable timer ───────────────────────────────────────────────

    /**
     * Cancel any pending timer and arm a fresh one. Called on every accepted
     * bridge command — an actively-used bridge never auto-disables.
     */
    fun rescheduleAutoDisable() {
        val minutes = _settings.value.autoDisableMinutes
        val delayMs = minutes * 60_000L
        val fireAt = System.currentTimeMillis() + delayMs
        autoDisableJob?.cancel()
        _autoDisableAtMs.value = fireAt

        autoDisableJob = (scope + SupervisorJob()).launch {
            try {
                delay(delayMs)
                Log.i(TAG, "Auto-disable fired after $minutes min of idle")
                // Hand off to the canonical worker so both code paths look
                // identical from a behavioral standpoint (notification +
                // master-toggle flip).
                AutoDisableWorker(appContext).run()
            } catch (_: Throwable) {
                // Cancellation is expected on reschedule — swallow quietly.
            } finally {
                if (_autoDisableAtMs.value == fireAt) _autoDisableAtMs.value = null
            }
        }
    }

    fun cancelAutoDisable() {
        autoDisableJob?.cancel()
        autoDisableJob = null
        _autoDisableAtMs.value = null
    }

    // ── Internals ────────────────────────────────────────────────────────

    private suspend fun currentSettings(): BridgeSafetySettings {
        // Prefer the cached value once the DataStore collector has ticked
        // at least once. Before that, fall back to a one-shot read of
        // DataStore so the very first command after install() doesn't
        // race the collector and see stale defaults.
        if (settingsHydrated) return _settings.value
        return try {
            val first = prefsRepo.settings.first()
            _settings.value = first
            settingsHydrated = true
            first
        } catch (t: Throwable) {
            Log.w(TAG, "currentSettings: DataStore read failed — using defaults", t)
            BridgeSafetySettings()
        }
    }

    private fun containsDestructiveVerb(text: String, verbs: Set<String>): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        for (verb in verbs) {
            val v = verb.lowercase()
            if (v.isBlank()) continue
            // \b<verb>\b — word-boundary match, so "send" matches "send it"
            // but not "sender" or "sendmail". Kotlin's Regex `\b` uses the
            // JVM Pattern engine; we pre-escape the verb in case a user
            // added something like `pay.` via the settings screen.
            val pattern = Regex("\\b${Regex.escape(v)}\\b", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(lower)) return true
        }
        return false
    }

    private fun firstMatchedVerb(text: String, verbs: Set<String>): String? {
        if (text.isBlank()) return null
        val lower = text.lowercase()
        return verbs.firstOrNull { v ->
            val vl = v.lowercase()
            vl.isNotBlank() && Regex("\\b${Regex.escape(vl)}\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(lower)
        }
    }
}

/**
 * One in-flight confirmation modal. Held in [BridgeSafetyManager.pendingConfirmations]
 * and surfaced to the overlay host so the Compose dialog can render the
 * full context.
 */
data class PendingConfirmation(
    val id: Long,
    val method: String,
    val text: String,
    val verb: String,
    val deferred: CompletableDeferred<Boolean>,
)

/**
 * Abstraction the safety manager talks to when it needs a modal on screen.
 * The live implementation lives in [BridgeStatusOverlay] (it's the same
 * [WindowManager] pipeline that hosts the ambient status dot, so we only
 * hold one SYSTEM_ALERT_WINDOW attachment per process).
 */
interface ConfirmationOverlayHost {
    /**
     * Render the confirmation modal. Must be idempotent per [request.id] —
     * calling twice with the same id is a no-op. [onResult] is invoked once
     * when the user reacts (or when the modal is dismissed externally, in
     * which case pass `false`).
     */
    fun showConfirmation(
        request: PendingConfirmation,
        onResult: (allowed: Boolean) -> Unit,
    )

    /** Dismiss a modal without resolving the deferred (the manager side does that). */
    fun dismissConfirmation(requestId: Long)

    companion object {
        @Volatile
        var instance: ConfirmationOverlayHost? = null
    }
}
