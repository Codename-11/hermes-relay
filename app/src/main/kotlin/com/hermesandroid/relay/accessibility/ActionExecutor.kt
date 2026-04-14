package com.hermesandroid.relay.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Phase 3 — accessibility `accessibility-runtime`
 *
 * Phone-side action dispatcher. Every method maps 1:1 to a Tier 1 bridge
 * command (`tap`, `tap_text`, `type`, `swipe`, `press_key`, `scroll`,
 * `wait`) and returns an [ActionResult] that [BridgeCommandHandler]
 * serializes into a `bridge.response` envelope.
 *
 * ## Why suspend functions
 *
 * Android's `dispatchGesture` API is fundamentally async — it takes a
 * callback on a Handler. Wrapping it as a suspend function keeps the
 * command handler's routing code synchronous-looking while preserving
 * the "wait for the gesture to actually complete before responding" contract.
 *
 * ## Key codes
 *
 * `press_key` accepts a small vocabulary of string names (`home`, `back`,
 * `recents`, `notifications`, `power`, `volume_up`, `volume_down`). Some
 * map to [AccessibilityService] global actions (home/back/recents), others
 * map to [KeyEvent] codes dispatched via [AccessibilityService.performGlobalAction]
 * where available. Power and volume are out of scope — they require
 * system-privileged APIs that AccessibilityService can't reach.
 */
class ActionExecutor(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "ActionExecutor"

        /** Default tap duration in milliseconds. Below ~50ms some IMEs ignore. */
        private const val DEFAULT_TAP_DURATION_MS = 100L

        /** Default swipe duration in milliseconds. */
        private const val DEFAULT_SWIPE_DURATION_MS = 400L

        /** Hard cap on `wait` — prevents runaway agents from pinning the channel. */
        private const val MAX_WAIT_MS = 15_000L
    }

    /**
     * Result of an action. Maps onto the `bridge.response` wire shape:
     * the handler turns [ok] into an HTTP-style `status` field and
     * merges [data] into the `result` object, while [error] becomes a
     * `{"error": "..."}` payload on failure.
     */
    data class ActionResult(
        val ok: Boolean,
        val data: Map<String, Any?> = emptyMap(),
        val error: String? = null,
    ) {
        companion object {
            fun ok(data: Map<String, Any?> = mapOf("ok" to true)): ActionResult =
                ActionResult(ok = true, data = data)

            fun failure(message: String): ActionResult =
                ActionResult(ok = false, error = message)
        }
    }

    // ─── tap / swipe / tap_text ───────────────────────────────────────────

    suspend fun tap(
        x: Int,
        y: Int,
        durationMs: Long = DEFAULT_TAP_DURATION_MS,
    ): ActionResult {
        if (x < 0 || y < 0) {
            return ActionResult.failure("tap coordinates must be non-negative (got x=$x, y=$y)")
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture)
        return if (dispatched) {
            ActionResult.ok(mapOf("x" to x, "y" to y, "duration_ms" to durationMs))
        } else {
            ActionResult.failure("gesture dispatch failed or was cancelled")
        }
    }

    suspend fun tapText(needle: String): ActionResult {
        if (needle.isBlank()) {
            return ActionResult.failure("tap_text: text must be non-blank")
        }
        val root = (service as? HermesAccessibilityService)?.snapshotRoot()
            ?: service.rootInActiveWindow
            ?: return ActionResult.failure("no active window available")

        val reader = (service as? HermesAccessibilityService)?.reader ?: ScreenReader()
        val bounds = reader.findNodeBoundsByText(root, needle)
            ?: return ActionResult.failure("no node matching text '$needle' on screen")
        if (bounds.isEmpty) {
            return ActionResult.failure("matched node has empty bounds (off-screen?)")
        }
        return tap(bounds.centerX, bounds.centerY)
    }

    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS,
    ): ActionResult {
        if (durationMs <= 0) {
            return ActionResult.failure("swipe duration must be positive (got $durationMs)")
        }
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture)
        return if (dispatched) {
            ActionResult.ok(
                mapOf(
                    "start_x" to startX,
                    "start_y" to startY,
                    "end_x" to endX,
                    "end_y" to endY,
                    "duration_ms" to durationMs,
                )
            )
        } else {
            ActionResult.failure("swipe gesture dispatch failed")
        }
    }

    // ─── type / scroll / press_key / wait ─────────────────────────────────

    fun typeText(text: String): ActionResult {
        val root = (service as? HermesAccessibilityService)?.snapshotRoot()
            ?: service.rootInActiveWindow
            ?: return ActionResult.failure("no active window available")

        val reader = (service as? HermesAccessibilityService)?.reader ?: ScreenReader()
        val focused = reader.findFocusedInput(root)
            ?: return ActionResult.failure("no focused editable field")

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val performed = try {
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            Log.w(TAG, "ACTION_SET_TEXT threw: ${t.message}")
            false
        } finally {
            @Suppress("DEPRECATION")
            try { focused.recycle() } catch (_: Throwable) { }
        }

        return if (performed) {
            ActionResult.ok(mapOf("length" to text.length))
        } else {
            ActionResult.failure("ACTION_SET_TEXT refused by target view")
        }
    }

    /**
     * Scroll the first scrollable container that contains the given point.
     *
     * `direction`: `"up"`, `"down"`, `"left"`, `"right"`. Internally we
     * dispatch a swipe gesture in the opposite direction (to scroll the
     * viewport *up*, fingers move *down*), with a generous starting offset
     * so the gesture begins safely inside the viewport.
     */
    suspend fun scroll(
        direction: String,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS,
    ): ActionResult {
        val root = service.rootInActiveWindow
            ?: return ActionResult.failure("no active window available")
        val bounds = android.graphics.Rect().also { root.getBoundsInScreen(it) }
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val qx = bounds.width() / 4
        val qy = bounds.height() / 4

        return when (direction.lowercase()) {
            "up" -> swipe(cx, cy - qy, cx, cy + qy, durationMs)
            "down" -> swipe(cx, cy + qy, cx, cy - qy, durationMs)
            "left" -> swipe(cx - qx, cy, cx + qx, cy, durationMs)
            "right" -> swipe(cx + qx, cy, cx - qx, cy, durationMs)
            else -> ActionResult.failure(
                "scroll direction must be one of up/down/left/right (got '$direction')"
            )
        }
    }

    /**
     * `press_key` maps string names to [AccessibilityService] global
     * actions. We deliberately do NOT accept numeric [KeyEvent] codes —
     * only a curated vocabulary — so the agent can't inject arbitrary
     * keypresses into sensitive system UI.
     */
    fun pressKey(keyName: String): ActionResult {
        val action = when (keyName.lowercase().trim()) {
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "recents", "recent_apps", "overview" ->
                AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" ->
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" ->
                AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" ->
                AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" ->
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            else -> return ActionResult.failure(
                "unsupported key '$keyName' — supported: home, back, recents, " +
                    "notifications, quick_settings, power_dialog, lock_screen"
            )
        }
        val ok = try {
            service.performGlobalAction(action)
        } catch (t: Throwable) {
            Log.w(TAG, "performGlobalAction threw: ${t.message}")
            false
        }
        return if (ok) ActionResult.ok(mapOf("key" to keyName))
        else ActionResult.failure("performGlobalAction returned false for '$keyName'")
    }

    /**
     * Sleep for [ms] milliseconds, clamped to [MAX_WAIT_MS]. This exists
     * so agents can chain `tap → wait → read_screen` to give the UI time
     * to settle between steps.
     */
    suspend fun wait(ms: Long): ActionResult {
        val clamped = ms.coerceIn(0L, MAX_WAIT_MS)
        delay(clamped)
        return ActionResult.ok(mapOf("slept_ms" to clamped))
    }

    // ─── send_intent / broadcast ─────────────────────────────────────────
    //
    // Phase 3 / B4 — `android_send_intent` + `android_broadcast`.
    //
    // Raw Intent escape hatch. These are NOT gesture dispatches — they go
    // through the normal `Context.startActivity` / `Context.sendBroadcast`
    // APIs so there's no `dispatchGesture` wakeup wrapper. We still run on
    // [Dispatchers.Main] because `startActivity` wants to post to the main
    // looper on some OEMs and the call is effectively free.
    //
    // Extras are restricted to `Map<String, String>` in v1 — full
    // Parcelable/Serializable support is overkill and would leak the
    // binder wire format to the agent. String extras cover the vast
    // majority of real-world intent URIs (`google.navigation:q=`,
    // `tel:`, `mailto:`, `smsto:`, custom deep links).
    //
    // FLAG_ACTIVITY_NEW_TASK is added unconditionally to activity-launching
    // intents because we're calling from a Service context — without it
    // `startActivity` throws `AndroidRuntimeException: Calling startActivity
    // from outside of an Activity context requires FLAG_ACTIVITY_NEW_TASK`.
    //
    // Blocklist gating happens at the BridgeCommandHandler level BEFORE we
    // get here — the handler looks at the target `pkg` field and refuses
    // if it's on the safety blocklist. This keeps the executor layer
    // context-free and testable.

    /**
     * Launch an Activity via an arbitrary [Intent].
     *
     * @param action Android action string, e.g. `android.intent.action.VIEW`
     * @param data Optional data URI. Parsed via [Uri.parse].
     * @param pkg Optional target package — forces the intent to a specific app.
     * @param component Optional fully-qualified component (`"pkg/classname"`).
     *        Parsed via [ComponentName.unflattenFromString]; returns an error
     *        if the format is invalid.
     * @param extras Optional string-keyed/string-valued extras. Non-string
     *        values are not supported in v1.
     * @param category Optional category to add via [Intent.addCategory].
     */
    suspend fun sendIntent(
        action: String,
        data: String?,
        pkg: String?,
        component: String?,
        extras: Map<String, String>?,
        category: String?,
    ): ActionResult {
        if (action.isBlank()) {
            return ActionResult.failure("send_intent: 'action' must be non-blank")
        }
        val intent = Intent(action)
        try {
            if (!data.isNullOrBlank()) {
                intent.data = Uri.parse(data)
            }
            if (!pkg.isNullOrBlank()) {
                intent.setPackage(pkg)
            }
            if (!component.isNullOrBlank()) {
                val cn = ComponentName.unflattenFromString(component)
                    ?: return ActionResult.failure(
                        "send_intent: invalid component '$component' " +
                            "(expected 'pkg/classname')"
                    )
                intent.component = cn
            }
            if (!category.isNullOrBlank()) {
                intent.addCategory(category)
            }
            if (extras != null) {
                for ((k, v) in extras) {
                    intent.putExtra(k, v)
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (t: Throwable) {
            Log.w(TAG, "send_intent: failed to build intent: ${t.message}")
            return ActionResult.failure("send_intent: bad intent payload: ${t.message}")
        }

        return withContext(Dispatchers.Main) {
            try {
                service.applicationContext.startActivity(intent)
                ActionResult.ok(
                    mapOf(
                        "action" to action,
                        "package" to (pkg ?: ""),
                        "component" to (component ?: ""),
                        "data" to (data ?: ""),
                    )
                )
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "send_intent: no activity found for '$action'")
                ActionResult.failure("no activity found for intent")
            } catch (e: SecurityException) {
                Log.w(TAG, "send_intent: permission denied: ${e.message}")
                ActionResult.failure("permission denied: ${e.message}")
            }
        }
    }

    /**
     * Broadcast an arbitrary [Intent] to any registered receivers.
     *
     * @param action Android action string.
     * @param data Optional data URI.
     * @param pkg Optional target package — scopes the broadcast to a single app.
     * @param extras Optional string-keyed/string-valued extras.
     */
    suspend fun sendBroadcast(
        action: String,
        data: String?,
        pkg: String?,
        extras: Map<String, String>?,
    ): ActionResult {
        if (action.isBlank()) {
            return ActionResult.failure("broadcast: 'action' must be non-blank")
        }
        val intent = Intent(action)
        try {
            if (!data.isNullOrBlank()) {
                intent.data = Uri.parse(data)
            }
            if (!pkg.isNullOrBlank()) {
                intent.setPackage(pkg)
            }
            if (extras != null) {
                for ((k, v) in extras) {
                    intent.putExtra(k, v)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "broadcast: failed to build intent: ${t.message}")
            return ActionResult.failure("broadcast: bad intent payload: ${t.message}")
        }

        return withContext(Dispatchers.Main) {
            try {
                service.applicationContext.sendBroadcast(intent)
                ActionResult.ok(
                    mapOf(
                        "action" to action,
                        "package" to (pkg ?: ""),
                        "data" to (data ?: ""),
                    )
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "broadcast: permission denied: ${e.message}")
                ActionResult.failure("permission denied: ${e.message}")
            }
        }
    }

    // ─── gesture dispatch plumbing ────────────────────────────────────────

    /**
     * Wrap [AccessibilityService.dispatchGesture] as a suspend function.
     * Returns `true` on completion, `false` on cancellation or failure.
     *
     * The callback runs on the main-thread Handler to match what the
     * Android docs recommend — all gesture-state transitions happen on
     * the main looper anyway.
     */
    private suspend fun dispatchGesture(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gesture: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gesture: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val accepted = try {
                service.dispatchGesture(gesture, callback, handler)
            } catch (t: Throwable) {
                Log.w(TAG, "dispatchGesture threw: ${t.message}")
                false
            }
            if (!accepted && cont.isActive) {
                cont.resume(false)
            }
        }
}
