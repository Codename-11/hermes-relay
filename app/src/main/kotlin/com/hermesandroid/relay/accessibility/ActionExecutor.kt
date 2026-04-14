package com.hermesandroid.relay.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
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

        /**
         * Maximum depth for the Tier 2 parent-walk cascade in [tapText].
         * Real-world apps wrap clickable content 2–4 levels deep
         * (TextView → inner LinearLayout → Card → clickable row). 8 is
         * generous and still bounds pathologically deep trees.
         */
        private const val PARENT_WALK_MAX = 8
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

    /**
     * Three-tier fallback cascade for text-based tapping.
     *
     * Real-world Android apps wrap clickable content in non-clickable
     * text/image views all the time (Uber, Spotify, Instagram, Tinder).
     * An old "direct click only" strategy bails on those. The cascade:
     *
     *  1. **Direct click** — matched text node `isClickable` → fire
     *     `ACTION_CLICK` on it.
     *  2. **Parent walk** — walk the parent chain up to [PARENT_WALK_MAX]
     *     levels; first clickable ancestor wins.
     *  3. **Coordinate fallback** — tap at the matched node's
     *     `getBoundsInScreen` center, capturing bounds *before* recycling.
     *
     * The `data.via` field on the returned [ActionResult] tells the agent
     * which tier succeeded so the activity log can show "tapped via parent
     * click" / "coordinate fallback" instead of an opaque success.
     *
     * ### Recycling contract
     *
     * Every `AccessibilityNodeInfo.parent` call returns a fresh reference
     * that must be explicitly recycled. The walk-up loop recycles the
     * previous node before reassigning, but **defers** recycling the
     * originally-matched node until the Tier 3 coordinate path has
     * captured its bounds. The `try/finally` at the bottom guarantees
     * both `matched` and `current` are released on every exit path,
     * including exception propagation.
     */
    suspend fun tapText(needle: String): ActionResult {
        if (needle.isBlank()) {
            return ActionResult.failure("tap_text: text must be non-blank")
        }
        val hermesService = service as? HermesAccessibilityService
        val root = hermesService?.snapshotRoot()
            ?: service.rootInActiveWindow
            ?: return ActionResult.failure("no active window available")

        val matched = findNodeByText(root, needle)
            ?: return ActionResult.failure("no node found matching text: $needle")

        var current: AccessibilityNodeInfo? = matched
        var depth = 0
        try {
            // ─── Tier 1 + Tier 2: direct click or walk-up to clickable ancestor ───
            // PARENT_WALK_MAX bounds TOTAL iterations (matched node + up to
            // PARENT_WALK_MAX-1 ancestors). Matches the reference cascade
            // in the A9 brief and keeps pathological trees from pinning us.
            while (current != null && depth < PARENT_WALK_MAX) {
                if (current.isClickable) {
                    val clicked = try {
                        current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } catch (t: Throwable) {
                        Log.w(TAG, "performAction(ACTION_CLICK) threw: ${t.message}")
                        false
                    }
                    if (clicked) {
                        val via = if (depth == 0) "direct click" else "parent click ($depth levels up)"
                        val message = if (depth == 0) "tapped via direct click"
                        else "tapped via parent click ($depth levels up)"
                        return ActionResult.ok(
                            mapOf(
                                "ok" to true,
                                "via" to via,
                                "message" to message,
                                "depth" to depth,
                                "needle" to needle,
                            )
                        )
                    }
                    // performAction returned false → treat as "not really clickable",
                    // keep walking the ancestors to try the next candidate.
                }

                val parent = try {
                    current.parent
                } catch (t: Throwable) {
                    Log.w(TAG, "node.parent threw at depth $depth: ${t.message}")
                    null
                }
                // Recycle the previous link in the chain — but NEVER recycle
                // `matched` here; Tier 3 still needs its bounds, and the outer
                // finally owns its lifetime.
                if (current !== matched) {
                    @Suppress("DEPRECATION")
                    try { current.recycle() } catch (_: Throwable) { }
                }
                current = parent
                depth++
            }

            // ─── Tier 3: coordinate fallback at the ORIGINAL matched node's bounds ───
            val rect = Rect()
            try {
                matched.getBoundsInScreen(rect)
            } catch (t: Throwable) {
                return ActionResult.failure(
                    "no clickable ancestor within $PARENT_WALK_MAX levels and bounds read failed: ${t.message}"
                )
            }
            if (rect.isEmpty) {
                return ActionResult.failure(
                    "no clickable ancestor within $PARENT_WALK_MAX levels and matched node has empty bounds (off-screen?)"
                )
            }
            val cx = rect.centerX()
            val cy = rect.centerY()
            val tapResult = tap(cx, cy)
            if (!tapResult.ok) return tapResult
            return ActionResult.ok(
                mapOf(
                    "ok" to true,
                    "via" to "coordinate fallback",
                    "message" to "tapped via coordinate fallback at ($cx, $cy)",
                    "x" to cx,
                    "y" to cy,
                    "needle" to needle,
                )
            )
        } finally {
            // Release the walk cursor if it's distinct from `matched`.
            if (current != null && current !== matched) {
                @Suppress("DEPRECATION")
                try { current!!.recycle() } catch (_: Throwable) { }
            }
            // Release the originally-matched node last — Tier 3 is done with it.
            @Suppress("DEPRECATION")
            try { matched.recycle() } catch (_: Throwable) { }
        }
    }

    /**
     * Find the first descendant of [root] whose text or content description
     * contains [needle] (case-insensitive), and return the node itself so
     * the caller can walk parents / perform clicks / read bounds. The
     * caller takes ownership and MUST recycle the returned node.
     *
     * This is a local helper — `ScreenReader.findNodeBoundsByText` throws
     * the node away after reading bounds, which is useless for the
     * Tier 2 parent walk. We duplicate a tiny amount of traversal here
     * to preserve the node reference.
     */
    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        needle: String,
    ): AccessibilityNodeInfo? {
        val lowered = needle.lowercase()
        return findFirstNode(root) { node ->
            val text = node.text?.toString()?.lowercase()
            val desc = node.contentDescription?.toString()?.lowercase()
            (text?.contains(lowered) == true) || (desc?.contains(lowered) == true)
        }
    }

    /**
     * Depth-first scan that returns the first node matching [predicate].
     * The returned node is NOT recycled by this function — caller owns it.
     * Every non-matching child is recycled in a per-iteration `finally`.
     */
    private fun findFirstNode(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        val childCount = root.childCount
        for (i in 0 until childCount) {
            val child = root.getChild(i) ?: continue
            val hit = findFirstNode(child, predicate)
            if (hit != null) {
                // Don't recycle `child` here if it IS the hit — caller owns it.
                // But if the hit came from a deeper descendant, `child` is a
                // now-unused intermediate and must be recycled.
                if (hit !== child) {
                    @Suppress("DEPRECATION")
                    try { child.recycle() } catch (_: Throwable) { }
                }
                return hit
            }
            @Suppress("DEPRECATION")
            try { child.recycle() } catch (_: Throwable) { }
        }
        return null
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
