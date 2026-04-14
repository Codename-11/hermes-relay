package com.hermesandroid.relay.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.hermesandroid.relay.data.relayDataStore
import com.hermesandroid.relay.event.EventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Phase 3 — accessibility `accessibility-runtime`
 *
 * Hermes's master `AccessibilityService` subclass. Provides the phone-side
 * execution layer for the bridge channel: the agent reads the screen,
 * taps/types/swipes, and captures screenshots through this service.
 *
 * # Lifecycle & singleton pattern
 *
 * Android instantiates `AccessibilityService` subclasses itself (through the
 * manifest declaration + user opt-in in Settings → Accessibility), so we
 * can't pass collaborators in via a constructor. The canonical workaround
 * is a weak-referenced singleton: on [onServiceConnected] the instance
 * registers itself with [Companion.instance], and on [onUnbind]/destroy it
 * clears itself. Any other code that needs to dispatch gestures (the
 * `BridgeCommandHandler`) asks for [instance] and bails out if it's null
 * (service not running / user hasn't granted permission).
 *
 * # Master enable / disable
 *
 * The Android system toggle in `Settings → Accessibility → Hermes Relay` is
 * the hard switch — if it's off we never receive events. On top of that the
 * user can flip a soft master in Settings (`bridge_master_enabled`); when
 * that's false we still run (Android requires it to stay connected) but we
 * refuse to execute commands. [isMasterEnabled] is a StateFlow the UI
 * observes and the command handler checks before dispatching actions.
 *
 * # Event handling
 *
 * We subscribe to a minimal event set — `TYPE_WINDOW_STATE_CHANGED` to
 * track the foreground package (for [currentApp] status), and nothing else.
 * The XML resource (flavor-provided by Agent flavor-split) controls the exact flag
 * bitset. We deliberately do NOT process text / content-change events —
 * those fire thousands of times a minute and are pointless for our use
 * case (we read the UI tree on demand via `rootInActiveWindow`).
 */
class HermesAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HermesA11yService"

        /** Master-enable DataStore key — read + toggled from Settings UI. */
        val KEY_BRIDGE_MASTER_ENABLED = booleanPreferencesKey("bridge_master_enabled")

        /**
         * Static reference to the live service instance, or null if the
         * service is not running. Written on [onServiceConnected],
         * cleared on [onUnbind] / [onDestroy].
         *
         * Read by [com.hermesandroid.relay.network.handlers.BridgeCommandHandler]
         * and by the Bridge UI screen (bridge-ui) to check live status.
         */
        @Volatile
        var instance: HermesAccessibilityService? = null
            private set

        /**
         * Observe the DataStore-backed master enable flag for the bridge.
         * UI can collect this to drive the master-toggle switch; the service
         * itself also polls it via [isMasterEnabled] before executing commands.
         */
        fun masterEnabledFlow(context: Context): Flow<Boolean> =
            context.applicationContext.relayDataStore.data
                .map { prefs -> prefs[KEY_BRIDGE_MASTER_ENABLED] ?: false }

        /**
         * Persist a new master-enable value. Called from Settings UI when the
         * user flips the switch, and from [BridgeStatusReporter] / safety
         * rails when auto-disable timers fire.
         */
        suspend fun setMasterEnabled(context: Context, enabled: Boolean) {
            context.applicationContext.relayDataStore.edit { prefs ->
                prefs[KEY_BRIDGE_MASTER_ENABLED] = enabled
            }
        }
    }

    private val screenReader = ScreenReader()
    private var _actionExecutor: ActionExecutor? = null

    /**
     * Service-scoped coroutine context. Started fresh in
     * [onServiceConnected], cancelled in [onUnbind] / [onDestroy]. Used to
     * observe the DataStore-backed master toggle and pump it into
     * [cachedMasterEnabled] so [BridgeCommandHandler] can do a non-suspend
     * gate check on every inbound command.
     *
     * Without this collector the cache stays at its `false` default and
     * the gate refuses every command — that was the original Phase 3
     * "bridge always disabled" bug.
     *
     * Re-created on each connect because cancelled `SupervisorJob`s can't
     * be reused, and Android may rebind the same service instance after
     * an unbind on rare config changes.
     */
    @Volatile
    private var serviceScope: CoroutineScope? = null

    /**
     * Cached package name of the currently-foregrounded app. Updated on
     * every `TYPE_WINDOW_STATE_CHANGED` event. Read by the status reporter
     * for the `current_app` field in `bridge.status`.
     */
    @Volatile
    var currentApp: String? = null
        private set

    /**
     * Public accessor for the lazily-constructed [ActionExecutor]. The
     * executor needs a back-reference to the service (for `dispatchGesture`),
     * so we can only build it after Android has fully constructed us.
     */
    val actionExecutor: ActionExecutor
        get() = _actionExecutor ?: ActionExecutor(this).also { _actionExecutor = it }

    /** Convenience wrapper — the service uses its own [ScreenReader] instance. */
    val reader: ScreenReader get() = screenReader

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "HermesAccessibilityService connected")
        // Feed the master-toggle cache for the lifetime of this service
        // binding. Re-created on each connect — see [serviceScope] KDoc.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        serviceScope = scope
        scope.launch {
            masterEnabledFlow(this@HermesAccessibilityService).collect { enabled ->
                cachedMasterEnabled = enabled
                Log.d(TAG, "master toggle cached: $enabled")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (!pkg.isNullOrBlank()) {
                    currentApp = pkg
                }
            }
            else -> {
                // Other event types are declared in the config XML for
                // future safety rails (blocklist enforcement via
                // content-change events) but we deliberately no-op here
                // today. Filtering happens inside the config flag bitset
                // so we never even receive most events.
            }
        }

        // === PHASE3-event-stream: B1 android_events / android_event_stream ===
        // Feed signal-rich events into the bounded ring buffer when the
        // agent has explicitly opted in via android_event_stream(true).
        // EventStore does its own type-filter + throttle + thread-safety
        // — we just hand it the raw event.
        if (EventStore.isStreaming) {
            EventStore.append(event)
        }
        // === END PHASE3-event-stream ===
    }

    override fun onInterrupt() {
        // Called by the system when it wants us to drop any in-flight work.
        // We don't queue long-running operations — every bridge command is
        // fire-and-forget with its own callback — so there's nothing to
        // cancel here.
        Log.i(TAG, "onInterrupt — accessibility service asked to stop work")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "HermesAccessibilityService unbinding")
        if (instance === this) instance = null
        serviceScope?.cancel()
        serviceScope = null
        cachedMasterEnabled = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        serviceScope?.cancel()
        serviceScope = null
        cachedMasterEnabled = false
        super.onDestroy()
    }

    /**
     * Soft master-toggle cache. Fed by the [serviceScope] collector started
     * in [onServiceConnected] — DO NOT write directly. Read by
     * [BridgeCommandHandler] on every inbound command via [isMasterEnabled].
     *
     * Volatile because the writer runs on Dispatchers.Default and the
     * reader runs on whichever multiplexer thread the bridge envelope
     * arrives on.
     */
    @Volatile
    private var cachedMasterEnabled: Boolean = false

    fun isMasterEnabled(): Boolean = cachedMasterEnabled

    /**
     * Snapshot the current root node of the active window. Returns null if
     * no window is focused or the system refuses access (e.g. IME window).
     *
     * Callers must `recycle()` the returned node when done.
     *
     * On API 34+, [AccessibilityNodeInfo.recycle] is deprecated but still
     * safe to call — the system just no-ops. We support min SDK 26 so we
     * keep calling it for the older branch.
     */
    fun snapshotRoot(): AccessibilityNodeInfo? = try {
        rootInActiveWindow
    } catch (t: Throwable) {
        Log.w(TAG, "rootInActiveWindow threw: ${t.message}")
        null
    }

    /**
     * Best-effort indicator — `true` when the runtime is >= API 26 (always
     * true on this app, we target 26+). Exposed for completeness so the
     * Bridge UI can render a compile-time capabilities badge without
     * reading BuildConfig directly.
     */
    val supportsGestures: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}
