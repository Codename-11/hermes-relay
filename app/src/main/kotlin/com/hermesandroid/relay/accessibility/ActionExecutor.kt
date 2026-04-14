package com.hermesandroid.relay.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hermesandroid.relay.power.WakeLockManager
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

        /** Default drag duration in milliseconds. */
        private const val DEFAULT_DRAG_DURATION_MS = 500L

        /** Drag duration clamp — below ~100ms gesture is treated as a fling. */
        private const val MIN_DRAG_DURATION_MS = 100L

        /**
         * Drag duration clamp — above ~3000ms the accessibility gesture
         * dispatcher starts timing out strokes.
         */
        private const val MAX_DRAG_DURATION_MS = 3000L

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
    ): ActionResult = WakeLockManager.wakeForAction {
        if (x < 0 || y < 0) {
            return@wakeForAction ActionResult.failure(
                "tap coordinates must be non-negative (got x=$x, y=$y)"
            )
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture)
        if (dispatched) {
            ActionResult.ok(mapOf("x" to x, "y" to y, "duration_ms" to durationMs))
        } else {
            ActionResult.failure("gesture dispatch failed or was cancelled")
        }
    }

    /**
     * Three-tier fallback cascade for text-based tapping, wrapped in the
     * A8 wake scope and running against the P1 multi-window snapshot.
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
     * ### Multi-window (P1)
     *
     * We walk every live accessibility window's root in order (top-of-stack
     * first) using [HermesAccessibilityService.snapshotAllWindows], so
     * tap_text works against system overlays, popup menus, notification
     * shade, split-screen siblings. Each window is probed via the A9
     * `findNodeByText` helper and the first match wins — matching
     * `ScreenReader.findNodeBoundsByText` behaviour but preserving the
     * node reference for the Tier 2 parent walk.
     *
     * ### Recycling contract
     *
     * Every `AccessibilityNodeInfo.parent` call returns a fresh reference
     * that must be explicitly recycled. The walk-up loop recycles the
     * previous node before reassigning, but **defers** recycling the
     * originally-matched node until the Tier 3 coordinate path has
     * captured its bounds. The `try/finally` at the bottom guarantees
     * both `matched` and `current` (plus all window roots from
     * snapshotAllWindows) are released on every exit path, including
     * exception propagation.
     */
    suspend fun tapText(needle: String): ActionResult = WakeLockManager.wakeForAction {
        if (needle.isBlank()) {
            return@wakeForAction ActionResult.failure("tap_text: text must be non-blank")
        }

        // P1 — prefer the multi-window snapshot so we can tap text inside
        // system overlays, popup menus, and notification shade content.
        val hermes = service as? HermesAccessibilityService
        val ownedRoots: List<AccessibilityNodeInfo> = if (hermes != null) {
            hermes.snapshotAllWindows()
        } else {
            val legacy = service.rootInActiveWindow
            if (legacy != null) listOf(legacy) else emptyList()
        }
        if (ownedRoots.isEmpty()) {
            return@wakeForAction ActionResult.failure("no active window available")
        }

        try {
            // Scan every window root for the needle; first match wins.
            var matched: AccessibilityNodeInfo? = null
            for (r in ownedRoots) {
                matched = findNodeByText(r, needle)
                if (matched != null) break
            }
            if (matched == null) {
                return@wakeForAction ActionResult.failure(
                    "no node found matching text: $needle"
                )
            }

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
                            return@wakeForAction ActionResult.ok(
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
                    return@wakeForAction ActionResult.failure(
                        "no clickable ancestor within $PARENT_WALK_MAX levels and bounds read failed: ${t.message}"
                    )
                }
                if (rect.isEmpty) {
                    return@wakeForAction ActionResult.failure(
                        "no clickable ancestor within $PARENT_WALK_MAX levels and matched node has empty bounds (off-screen?)"
                    )
                }
                val cx = rect.centerX()
                val cy = rect.centerY()
                val tapResult = tap(cx, cy)
                if (!tapResult.ok) return@wakeForAction tapResult
                return@wakeForAction ActionResult.ok(
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
        } finally {
            for (r in ownedRoots) {
                @Suppress("DEPRECATION")
                try { r.recycle() } catch (_: Throwable) { }
            }
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
    ): ActionResult = WakeLockManager.wakeForAction {
        if (durationMs <= 0) {
            return@wakeForAction ActionResult.failure(
                "swipe duration must be positive (got $durationMs)"
            )
        }
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture)
        if (dispatched) {
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

    /**
     * Precise point-to-point drag from (startX, startY) to (endX, endY)
     * over [durationMs] milliseconds.
     *
     * Distinct from [swipe] in intent: swipe is "flick in a direction",
     * drag is "touch down, move slowly, release" — the kind of gesture
     * that rearranges home screen icons, pulls the notification shade
     * a deliberate distance, drags map pins, or reorders list items.
     *
     * Internally builds a single-stroke [GestureDescription] with a
     * straight line from A → B. The stroke duration is clamped to
     * [MIN_DRAG_DURATION_MS]..[MAX_DRAG_DURATION_MS] because the
     * accessibility gesture dispatcher rejects strokes outside that
     * window (flings on the low end, timeouts on the high end).
     *
     * Wrapped in [WakeLockManager.wakeForAction] so the drag still
     * completes when the screen is about to sleep — accessibility
     * gestures only run against the interactive window, and without
     * a wake scope a backgrounded drag can get cancelled mid-stroke.
     */
    suspend fun drag(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = DEFAULT_DRAG_DURATION_MS,
    ): ActionResult = WakeLockManager.wakeForAction {
        if (startX < 0 || startY < 0 || endX < 0 || endY < 0) {
            return@wakeForAction ActionResult.failure(
                "drag coordinates must be non-negative " +
                    "(got start=($startX,$startY) end=($endX,$endY))"
            )
        }
        val clamped = durationMs.coerceIn(MIN_DRAG_DURATION_MS, MAX_DRAG_DURATION_MS)
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, clamped)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture)
        if (dispatched) {
            ActionResult.ok(
                mapOf(
                    "start_x" to startX,
                    "start_y" to startY,
                    "end_x" to endX,
                    "end_y" to endY,
                    "duration_ms" to clamped,
                )
            )
        } else {
            ActionResult.failure("drag gesture dispatch failed")
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
     *
     * NOTE: `nodeId` here is an Android `viewIdResourceName` (e.g.
     * `com.app:id/button`) — distinct from P1's `w<n>:<idx>` sequential
     * walk IDs. We walk all live accessibility windows per the P1
     * multi-window pattern so long-press can target system overlays and
     * popups too.
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
            // P1 — walk every live accessibility window so we can
            // long-press nodes inside system overlays, popup menus, and
            // split-screen siblings. Falls back to rootInActiveWindow
            // when running against a bare AccessibilityService stub.
            val hermes = service as? HermesAccessibilityService
            val ownedRoots: List<AccessibilityNodeInfo> = if (hermes != null) {
                hermes.snapshotAllWindows()
            } else {
                val legacy = service.rootInActiveWindow
                if (legacy != null) listOf(legacy) else emptyList()
            }
            if (ownedRoots.isEmpty()) {
                return@wakeForAction ActionResult.failure("no active window available")
            }

            try {
                var target: AccessibilityNodeInfo? = null
                for (r in ownedRoots) {
                    target = findNodeByResourceId(r, nodeId!!)
                    if (target != null) break
                }
                if (target == null) {
                    return@wakeForAction ActionResult.failure(
                        "no node matching node_id '$nodeId' on screen"
                    )
                }

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
            } finally {
                for (r in ownedRoots) {
                    @Suppress("DEPRECATION")
                    try { r.recycle() } catch (_: Throwable) { }
                }
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

    suspend fun typeText(text: String): ActionResult = WakeLockManager.wakeForAction {
        // P1 — look for the focused input across every live window so an
        // IME-hosted text field, a dialog field, or a split-screen sibling
        // can be targeted too.
        val hermes = service as? HermesAccessibilityService
        val ownedRoots: List<AccessibilityNodeInfo> = if (hermes != null) {
            hermes.snapshotAllWindows()
        } else {
            val legacy = service.rootInActiveWindow
            if (legacy != null) listOf(legacy) else emptyList()
        }
        if (ownedRoots.isEmpty()) {
            return@wakeForAction ActionResult.failure("no active window available")
        }

        val reader = hermes?.reader ?: ScreenReader()
        try {
            val focused = reader.findFocusedInput(ownedRoots)
                ?: return@wakeForAction ActionResult.failure("no focused editable field")

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

            if (performed) {
                ActionResult.ok(mapOf("length" to text.length))
            } else {
                ActionResult.failure("ACTION_SET_TEXT refused by target view")
            }
        } finally {
            for (r in ownedRoots) {
                @Suppress("DEPRECATION")
                try { r.recycle() } catch (_: Throwable) { }
            }
        }
    }

    /**
     * Scroll the first scrollable container that contains the given point.
     *
     * `direction`: `"up"`, `"down"`, `"left"`, `"right"`. Internally we
     * dispatch a swipe gesture in the opposite direction (to scroll the
     * viewport *up*, fingers move *down*), with a generous starting offset
     * so the gesture begins safely inside the viewport.
     *
     * A4: `centerX` / `centerY` are optional override coordinates. When
     * supplied (via `/scroll` with a `nodeId`), `BridgeCommandHandler`
     * resolves the node's bounds center and hands them in here so we swipe
     * within the targeted scrollable instead of the whole root. When null
     * (the legacy path — `/scroll` with just a direction), we fall back to
     * the root-window bounds as before. The width/height used for the swipe
     * offset comes from the root window either way — it's a "length of
     * swipe" proxy, not a "where the scrollable lives" coordinate.
     */
    suspend fun scroll(
        direction: String,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS,
        centerX: Int? = null,
        centerY: Int? = null,
    ): ActionResult = WakeLockManager.wakeForAction {
        val root = service.rootInActiveWindow
            ?: return@wakeForAction ActionResult.failure("no active window available")
        val bounds = android.graphics.Rect().also { root.getBoundsInScreen(it) }
        val cx = centerX ?: bounds.centerX()
        val cy = centerY ?: bounds.centerY()
        val qx = bounds.width() / 4
        val qy = bounds.height() / 4

        // The inner swipe() re-enters WakeLockManager; ref-counting
        // ensures both scopes share one physical lock.
        when (direction.lowercase()) {
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
     * System-wide media playback control via [Intent.ACTION_MEDIA_BUTTON]
     * ordered broadcast. Works against whatever media app is currently
     * playing (Spotify, YouTube Music, Pocket Casts, etc.) — every
     * compliant media app registers a receiver for this broadcast.
     *
     * Actions:
     *   * `play`     → [KeyEvent.KEYCODE_MEDIA_PLAY]
     *   * `pause`    → [KeyEvent.KEYCODE_MEDIA_PAUSE]
     *   * `toggle`   → [KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE]
     *   * `next`     → [KeyEvent.KEYCODE_MEDIA_NEXT]
     *   * `previous` → [KeyEvent.KEYCODE_MEDIA_PREVIOUS]
     *
     * We send an ACTION_DOWN broadcast followed by an ACTION_UP with the
     * same keycode — the DOWN+UP pair is what compliant media apps
     * actually react to. No special permissions required: media button
     * broadcasts are a system-wide interface available to any app.
     *
     * Synchronous — [Intent.sendOrderedBroadcast] fires and returns; the
     * receivers handle delivery on their own. We do not block on the
     * result.
     */
    fun mediaControl(action: String): ActionResult {
        val keycode = when (action.lowercase().trim()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return ActionResult.failure("Unknown media action: $action")
        }
        return try {
            val context = service.applicationContext ?: service
            val down = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keycode))
            }
            context.sendOrderedBroadcast(down, null)
            val up = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keycode))
            }
            context.sendOrderedBroadcast(up, null)
            ActionResult.ok(mapOf("action" to action, "keycode" to keycode))
        } catch (t: Throwable) {
            Log.w(TAG, "mediaControl broadcast failed: ${t.message}", t)
            ActionResult.failure("media broadcast failed: ${t.message ?: "unknown error"}")
        }
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

    // ─── clipboard (A6) ───────────────────────────────────────────────────

    /**
     * Read the current system clipboard as plain text.
     *
     * Returns `{"text": ""}` when the clipboard is empty — empty is NOT an
     * error condition, it's a legitimate state ("user hasn't copied anything
     * yet"). Callers should treat an empty string and a failure differently.
     *
     * Android's [ClipboardManager] API must be touched from the main thread
     * on several Android versions (framework internals post IPC to the main
     * looper), so we hop to [Dispatchers.Main] here instead of relying on
     * whatever dispatcher the command handler coroutine happens to be on.
     *
     * Android 12+ privacy note: reading the clipboard from a background app
     * on API 31+ shows a system toast "Hermes-Relay pasted from your
     * clipboard". We can't suppress that — it's a system-level privacy
     * feature and it's the right call. Document this in the tool description
     * so the LLM knows to expect it.
     */
    suspend fun clipboardRead(): ActionResult = withContext(Dispatchers.Main) {
        try {
            val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@withContext ActionResult.failure("ClipboardManager unavailable")
            val clip = cm.primaryClip
            val text = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0)?.text?.toString() ?: ""
            } else {
                ""
            }
            ActionResult.ok(mapOf("text" to text))
        } catch (t: Throwable) {
            Log.w(TAG, "clipboardRead threw: ${t.message}")
            ActionResult.failure("clipboardRead failed: ${t.message ?: "unknown error"}")
        }
    }

    /**
     * Write a plain-text value to the system clipboard.
     *
     * We label the [ClipData] with `"hermes"` so any other app that inspects
     * `primaryClipDescription.label` can see the content came from
     * Hermes-Relay (e.g., for attribution or audit). Empty strings are
     * allowed — they effectively clear the clipboard from our perspective —
     * so we do NOT reject them.
     *
     * Same main-thread hop as [clipboardRead]: [ClipboardManager.setPrimaryClip]
     * is documented as safe on any thread but has historically thrown on
     * some OEM Android builds when called off-main, and hopping is cheap.
     *
     * Android 12+ privacy note: on API 31+, writing to the clipboard shows a
     * system toast like "Hermes-Relay copied". This is a system-level
     * privacy feature we can't suppress and shouldn't try to. Document this
     * in the tool description.
     */
    suspend fun clipboardWrite(text: String): ActionResult = withContext(Dispatchers.Main) {
        try {
            val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return@withContext ActionResult.failure("ClipboardManager unavailable")
            val clip = ClipData.newPlainText("hermes", text)
            cm.setPrimaryClip(clip)
            ActionResult.ok(mapOf("success" to true, "length" to text.length))
        } catch (t: Throwable) {
            Log.w(TAG, "clipboardWrite threw: ${t.message}")
            ActionResult.failure("clipboardWrite failed: ${t.message ?: "unknown error"}")
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
