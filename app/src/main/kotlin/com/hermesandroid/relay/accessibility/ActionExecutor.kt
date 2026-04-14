package com.hermesandroid.relay.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hermesandroid.relay.power.WakeLockManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
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

        // ── A1 long_press ────────────────────────────────────────────────
        /** Default long-press duration. Android's ViewConfiguration default
         *  is ~500 ms, which is what the platform itself uses to decide
         *  "this was a long press". */
        private const val DEFAULT_LONG_PRESS_DURATION_MS = 500L

        /** Minimum accepted long-press duration (ms). Below this you're
         *  really asking for a regular tap. */
        private const val MIN_LONG_PRESS_DURATION_MS = 100L

        /** Maximum accepted long-press duration (ms). Above this the user
         *  has almost certainly made a mistake — anything over 3 s ties up
         *  the gesture queue and risks ANRing the target app. */
        private const val MAX_LONG_PRESS_DURATION_MS = 3_000L
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

    // ─── long_press (A1) ──────────────────────────────────────────────────

    /**
     * Perform a long press either at a screen coordinate or on an
     * accessibility node.
     *
     * Two entry points — exactly one of `(x, y)` or `nodeId` must be
     * provided. The nodeId path prefers [AccessibilityNodeInfo.ACTION_LONG_CLICK]
     * which is the semantic equivalent of the platform's own long-press;
     * the coord path falls back to a single-stroke [GestureDescription]
     * whose duration determines how long the finger "holds".
     *
     * [duration] is clamped to the `[MIN_LONG_PRESS_DURATION_MS,
     * MAX_LONG_PRESS_DURATION_MS]` range. Values outside that range are
     * rejected rather than silently clamped — if an agent asked for
     * `duration=50` they probably meant `tap`, and if they asked for
     * `duration=10_000` they're about to ANR the target app.
     *
     * The entire body runs inside [WakeLockManager.wakeForAction] so the
     * screen stays lit through the gesture — long presses are useless if
     * the device falls asleep halfway through the hold.
     */
    suspend fun longPress(
        x: Int?,
        y: Int?,
        nodeId: String?,
        duration: Long = DEFAULT_LONG_PRESS_DURATION_MS,
    ): ActionResult = WakeLockManager.wakeForAction {
        // ── arg validation ────────────────────────────────────────────
        val hasCoords = x != null && y != null
        val hasNodeId = !nodeId.isNullOrBlank()
        if (!hasCoords && !hasNodeId) {
            return@wakeForAction ActionResult.failure(
                "long_press requires either (x, y) or node_id"
            )
        }
        if (hasCoords && hasNodeId) {
            return@wakeForAction ActionResult.failure(
                "long_press accepts (x, y) OR node_id, not both"
            )
        }
        if (duration !in MIN_LONG_PRESS_DURATION_MS..MAX_LONG_PRESS_DURATION_MS) {
            return@wakeForAction ActionResult.failure(
                "long_press duration must be " +
                    "$MIN_LONG_PRESS_DURATION_MS..$MAX_LONG_PRESS_DURATION_MS ms " +
                    "(got $duration)"
            )
        }

        // ── nodeId branch: ACTION_LONG_CLICK on the matched node ──────
        if (hasNodeId) {
            val root = (service as? HermesAccessibilityService)?.snapshotRoot()
                ?: service.rootInActiveWindow
                ?: return@wakeForAction ActionResult.failure("no active window available")

            val target = findNodeByResourceId(root, nodeId!!)
                ?: return@wakeForAction ActionResult.failure(
                    "no node matching node_id '$nodeId' on screen"
                )

            val performed = try {
                target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            } catch (t: Throwable) {
                Log.w(TAG, "ACTION_LONG_CLICK threw: ${t.message}")
                false
            } finally {
                @Suppress("DEPRECATION")
                try { target.recycle() } catch (_: Throwable) { }
            }

            return@wakeForAction if (performed) {
                ActionResult.ok(
                    mapOf(
                        "node_id" to nodeId,
                        "mode" to "action_long_click",
                    )
                )
            } else {
                ActionResult.failure(
                    "ACTION_LONG_CLICK refused by node '$nodeId' " +
                        "(node may not be long-clickable)"
                )
            }
        }

        // ── coordinate branch: held-finger GestureDescription ─────────
        val safeX = x!!
        val safeY = y!!
        if (safeX < 0 || safeY < 0) {
            return@wakeForAction ActionResult.failure(
                "long_press coordinates must be non-negative (got x=$safeX, y=$safeY)"
            )
        }
        val path = Path().apply { moveTo(safeX.toFloat(), safeY.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture)
        return@wakeForAction if (dispatched) {
            ActionResult.ok(
                mapOf(
                    "x" to safeX,
                    "y" to safeY,
                    "duration_ms" to duration,
                    "mode" to "gesture",
                )
            )
        } else {
            ActionResult.failure("long_press gesture dispatch failed or was cancelled")
        }
    }

    /**
     * Walk the accessibility tree rooted at [root] and return the first
     * node whose `viewIdResourceName` equals [nodeId]. This matches the
     * `viewId` field emitted by [ScreenReader] in `/screen` responses —
     * agents pass that value straight back as `node_id`.
     *
     * The caller owns the returned node and must `recycle()` it.
     */
    private fun findNodeByResourceId(
        root: AccessibilityNodeInfo,
        nodeId: String,
    ): AccessibilityNodeInfo? {
        if (root.viewIdResourceName == nodeId) {
            return AccessibilityNodeInfo.obtain(root)
        }
        val childCount = root.childCount
        for (i in 0 until childCount) {
            val child = root.getChild(i) ?: continue
            try {
                val hit = findNodeByResourceId(child, nodeId)
                if (hit != null) return hit
            } finally {
                @Suppress("DEPRECATION")
                try { child.recycle() } catch (_: Throwable) { }
            }
        }
        return null
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
