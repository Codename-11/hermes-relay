package com.hermesandroid.relay.accessibility

/**
 * Phase 3 — bridge-ui follow-up
 *
 * Process-singleton bridge between non-Activity code (BridgeViewModel,
 * settings screens) and the Activity-scoped `ActivityResultLauncher` that
 * fires the system MediaProjection consent dialog.
 *
 * # Why a singleton
 *
 * `MediaProjectionManager.createScreenCaptureIntent()` must be launched via
 * an `ActivityResultLauncher` registered against a `ComponentActivity` —
 * there is no way to invoke it from a ViewModel directly. We can't pass
 * the launcher into the ViewModel either, because the ViewModel outlives
 * the Activity across configuration changes and we'd leak the old Activity.
 *
 * The singleton pattern: `MainActivity` registers a launcher in `onCreate`,
 * stores a closure that calls `launcher.launch(...)` here, and clears the
 * closure in `onDestroy`. Anything that wants to ask the user for the
 * MediaProjection grant calls [request] — if the Activity is alive, the
 * system dialog appears; if not, the call returns false and the caller
 * should surface "open the app first".
 *
 * # Why not just inject the launcher into the ViewModel
 *
 * `ActivityResultLauncher` is bound to the ViewModel store of the host
 * Activity, not the ViewModel. Crossing that boundary either leaks the
 * Activity (bad) or works only until the first rotation (worse). The
 * lifecycle-respecting way is to keep the launcher Activity-scoped and
 * route requests to it through a process-wide rendezvous like this.
 */
object ScreenCaptureRequester {

    @Volatile
    private var launchAction: (() -> Unit)? = null

    /**
     * Called by `MainActivity.onCreate` (or any ComponentActivity that
     * wants to host the consent flow) with a closure that launches its
     * pre-registered `ActivityResultLauncher` for the
     * `ACTION_MEDIA_PROJECTION` intent.
     */
    fun install(launch: () -> Unit) {
        launchAction = launch
    }

    /** Called by `MainActivity.onDestroy` so we don't hold a stale Activity ref. */
    fun uninstall() {
        launchAction = null
    }

    /**
     * Trigger the consent dialog. Returns `true` if a host Activity is
     * currently installed and the request was dispatched, `false` if no
     * Activity is alive (caller should fall back to "open the app first").
     *
     * The actual grant arrives asynchronously via the launcher's result
     * callback — see `MainActivity.mediaProjectionLauncher`, which hands
     * the result to `BridgeForegroundService.grantMediaProjection` so the
     * grant lands inside a foreground service that's already running with
     * `startForeground(type=mediaProjection)` (Android 14+ requirement).
     */
    fun request(): Boolean {
        val action = launchAction ?: return false
        return try {
            action.invoke()
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Quick poll for UI — true when a host Activity is currently installed. */
    val isAvailable: Boolean get() = launchAction != null
}
