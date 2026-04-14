package com.hermesandroid.relay.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.hermesandroid.relay.power.WakeLockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
// service is typed as the HermesAccessibilityService subclass (not the framework
// AccessibilityService base) so this class can reach the subclass-only members
// added in wave 1/2/3 — primarily `service.reader` for the M4 long-press
// node_id resolver. The single caller (HermesAccessibilityService.actionExecutor
// getter) passes `this` so the narrowed type is always satisfied; all existing
// AccessibilityService calls (dispatchGesture, performGlobalAction, etc.)
// still work because the subclass IS an AccessibilityService.
class ActionExecutor(private val service: HermesAccessibilityService) {

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

        // ─── Tier C constants (C1 location / C4 send_sms) ────────────────
        /** Over this threshold, `location()` tags the reading as stale. */
        private const val STALE_LOCATION_THRESHOLD_MS = 5 * 60 * 1000L

        /** How long [sendSms] waits for the radio to acknowledge the send. */
        private const val SEND_SMS_TIMEOUT_MS = 15_000L
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
                // M4 fix: previously this used findNodeByResourceId which
                // matches on viewIdResourceName (e.g. "com.foo:id/button"),
                // but /tap and /scroll resolve via reader.findNodeById which
                // matches on the P1 sequential scheme ("w0:42"). The Python
                // schema for android_long_press advertises node_id as the
                // value returned by android_read_screen — i.e. the sequential
                // form. Aligning the resolver here makes the round trip
                // android_read_screen → android_long_press(node_id=...) work.
                val target: AccessibilityNodeInfo? =
                    service.reader.findNodeById(ownedRoots, nodeId!!)
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

    // M4 fix: removed unused `findNodeByResourceId` helper. The longPress
    // nodeId branch now uses ScreenReader.findNodeById (the P1 sequential
    // scheme) so it matches /tap and /scroll.

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
        // H3 fix: previously `service.rootInActiveWindow` was read into a
        // local but never recycled, leaking an AccessibilityNodeInfo handle
        // on every scroll. Sustained agent scroll loops would exhaust the
        // system's node pool. Read the bounds inside a try/finally and
        // recycle on every path before delegating to swipe().
        val root = service.rootInActiveWindow
            ?: return@wakeForAction ActionResult.failure("no active window available")
        val cx: Int
        val cy: Int
        val qx: Int
        val qy: Int
        try {
            val bounds = android.graphics.Rect().also { root.getBoundsInScreen(it) }
            cx = centerX ?: bounds.centerX()
            cy = centerY ?: bounds.centerY()
            qx = bounds.width() / 4
            qy = bounds.height() / 4
        } finally {
            @Suppress("DEPRECATION")
            try { root.recycle() } catch (_: Throwable) { }
        }

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

    // ─── send_intent / broadcast (B4) ─────────────────────────────────────
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

    // ─── Tier C: location / contacts / call / SMS ─────────────────────────
    //
    // All four methods below are sideload-only tools (see Tier C in the
    // 2026-04-13 bridge-feature-expansion plan). Permission declarations
    // live in `app/src/sideload/AndroidManifest.xml` only; `BridgeCommandHandler`
    // gates each case on `BuildFlavor.isSideload` so googlePlay builds never
    // reach these methods. We still runtime-check `ContextCompat.checkSelfPermission`
    // at every call site because the user has to grant the permission in
    // system Settings — the sideload manifest only earns the *right to ask*.

    /**
     * C1 — return the phone's last-known GPS location.
     *
     * Queries every available [LocationManager] provider and returns the
     * most accurate (smallest `accuracy` in meters). Does NOT request a
     * fresh fix — background services can't reliably do that, and the
     * whole point of this tool is a cheap "where are we right now" for
     * contextual agent tasks. If the best fix is older than 5 minutes
     * we still return it, but add a `staleness_ms` marker + a warning
     * string so the agent can tell the user to open a maps app briefly
     * to refresh.
     */
    fun location(): ActionResult {
        val ctx: Context = service
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.failure(
                "Grant location permission in Settings > Apps > Hermes-Relay > Permissions"
            )
        }

        val lm = try {
            ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        } catch (t: Throwable) {
            Log.w(TAG, "getSystemService(LOCATION_SERVICE) threw: ${t.message}")
            null
        } ?: return ActionResult.failure("LocationManager unavailable on this device")

        // Iterate over all providers in descending preference — GPS is the
        // ground-truth; network/fused are cheaper but less accurate; passive
        // returns whatever the last app that asked got, which is surprisingly
        // often the best we have. Collect all available fixes and pick the
        // one with the smallest `accuracy` reading.
        val providers = try {
            lm.getProviders(true)
        } catch (t: Throwable) {
            Log.w(TAG, "getProviders threw: ${t.message}")
            emptyList<String>()
        }
        if (providers.isEmpty()) {
            return ActionResult.failure(
                "No location providers available — enable GPS in system Settings"
            )
        }

        val fixes = mutableListOf<Location>()
        for (provider in providers) {
            try {
                // Suppressed: we DO hold the runtime permission — checked
                // against ContextCompat above. The linter can't follow the
                // guard across the function boundary.
                @SuppressLint("MissingPermission")
                val loc = lm.getLastKnownLocation(provider)
                if (loc != null) fixes.add(loc)
            } catch (se: SecurityException) {
                Log.w(TAG, "getLastKnownLocation($provider) denied: ${se.message}")
                return ActionResult.failure(
                    "Location permission revoked — re-grant in system Settings"
                )
            } catch (t: Throwable) {
                Log.w(TAG, "getLastKnownLocation($provider) threw: ${t.message}")
                // Don't bail — maybe the next provider works.
            }
        }

        if (fixes.isEmpty()) {
            return ActionResult.failure(
                "No last-known location from any provider — open a maps app " +
                    "briefly to get a fix, then retry"
            )
        }

        // `accuracy` is meters; smaller is better. Some providers report 0
        // or negative which means "we have no idea" — push those to the
        // back of the ranking.
        val best = fixes.minByOrNull { loc ->
            val a = loc.accuracy
            if (a <= 0f) Float.MAX_VALUE else a
        } ?: fixes.first()

        val nowMs = System.currentTimeMillis()
        val stalenessMs = (nowMs - best.time).coerceAtLeast(0L)
        val stale = stalenessMs > STALE_LOCATION_THRESHOLD_MS

        val data = mutableMapOf<String, Any?>(
            "latitude" to best.latitude,
            "longitude" to best.longitude,
            "accuracy" to best.accuracy.toDouble(),
            "provider" to (best.provider ?: "unknown"),
            "timestamp" to best.time,
            "staleness_ms" to stalenessMs,
        )
        if (best.hasAltitude()) data["altitude"] = best.altitude
        if (stale) {
            data["warning"] =
                "Last known location is >5min old; open a maps app briefly to refresh the fix."
        }
        return ActionResult.ok(data)
    }

    /**
     * C2 — search the phone's contact database by name and return matching
     * entries with their phone numbers.
     *
     * Uses [ContactsContract.Contacts.CONTENT_FILTER_URI] for the fast
     * native text search, then resolves phone numbers per-contact via
     * [ContactsContract.CommonDataKinds.Phone.CONTENT_URI]. SQL-side
     * `LIMIT N` in the sortOrder keeps this fast on devices with
     * thousands of contacts.
     */
    fun searchContacts(query: String, limit: Int = 20): ActionResult {
        if (query.isBlank()) {
            return ActionResult.failure("search query must be non-blank")
        }
        val ctx: Context = service
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.failure(
                "Grant contacts permission in Settings > Apps > Hermes-Relay > Permissions"
            )
        }
        val clampedLimit = limit.coerceIn(1, 100)

        val results = mutableListOf<Map<String, Any?>>()
        val resolver = ctx.contentResolver
        val filterUri = Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_FILTER_URI,
            Uri.encode(query)
        )
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
        )
        val sortOrder =
            "${ContactsContract.Contacts.DISPLAY_NAME} ASC LIMIT $clampedLimit"

        var cursor = try {
            resolver.query(filterUri, projection, null, null, sortOrder)
        } catch (se: SecurityException) {
            return ActionResult.failure(
                "Contacts permission revoked — re-grant in system Settings"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "contacts filter query threw: ${t.message}")
            return ActionResult.failure("contacts query failed: ${t.message}")
        }

        try {
            if (cursor == null) {
                return ActionResult.ok(
                    mapOf("count" to 0, "contacts" to emptyList<Map<String, Any?>>())
                )
            }
            val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                val phones = fetchPhonesForContact(ctx, id)
                results.add(
                    mapOf(
                        "id" to id,
                        "name" to (name ?: "(unknown)"),
                        "phones" to phones.joinToString(", "),
                    )
                )
            }
        } finally {
            try { cursor?.close() } catch (_: Throwable) { }
            cursor = null
        }

        return ActionResult.ok(
            mapOf(
                "count" to results.size,
                // Log the search term but NOT the results — matched names
                // and numbers stay out of the activity log for privacy.
                "query" to query,
                "contacts" to results,
            )
        )
    }

    private fun fetchPhonesForContact(ctx: Context, contactId: Long): List<String> {
        val phones = mutableListOf<String>()
        val resolver = ctx.contentResolver
        var phoneCursor = try {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null
            )
        } catch (t: Throwable) {
            Log.w(TAG, "phone query for contact $contactId threw: ${t.message}")
            null
        }
        try {
            if (phoneCursor != null) {
                val numIdx = phoneCursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                while (phoneCursor.moveToNext()) {
                    val num = if (numIdx >= 0) phoneCursor.getString(numIdx) else null
                    if (!num.isNullOrBlank()) phones.add(num)
                }
            }
        } finally {
            try { phoneCursor?.close() } catch (_: Throwable) { }
            phoneCursor = null
        }
        return phones
    }

    /**
     * C3 — dial a phone number.
     *
     * Two modes: with `CALL_PHONE` granted (sideload flavor), we auto-dial
     * via [Intent.ACTION_CALL]. Without it — either because the user denied
     * the permission or because this is a googlePlay build where the
     * permission isn't even declared — we fall back to [Intent.ACTION_DIAL]
     * which just opens the system dialer pre-populated, requires no
     * permission, and the user taps Call manually. This is "shippable in
     * googlePlay as a dialer-opener" per the plan.
     *
     * The destructive-verb confirmation modal is fired in
     * [com.hermesandroid.relay.network.handlers.BridgeCommandHandler]
     * before we even get here — by the time this method runs, the user
     * has explicitly approved the call.
     */
    fun makeCall(number: String): ActionResult {
        if (number.isBlank()) {
            return ActionResult.failure("call: number must be non-blank")
        }
        // Light sanity check — block obvious bad input. We don't try to
        // validate E.164 here because international + extension + pause
        // formats are wildly inconsistent and Android's dialer is better
        // at recovering from weird input than we'd be.
        if (!number.matches(Regex("^[+0-9 ()\\-.,*#pPwW]{2,}$"))) {
            return ActionResult.failure("call: number contains invalid characters")
        }

        val ctx: Context = service
        val hasCallPerm = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:$number")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            ctx.startActivity(intent)
            if (hasCallPerm) {
                ActionResult.ok(
                    mapOf(
                        "number" to number,
                        "mode" to "auto_dial",
                    )
                )
            } else {
                ActionResult.ok(
                    mapOf(
                        "number" to number,
                        "mode" to "dialer_opened",
                        "note" to "opened dialer — user must tap Call manually",
                    )
                )
            }
        } catch (se: SecurityException) {
            ActionResult.failure(
                "permission denied despite grant — revoke in system settings and re-enable"
            )
        } catch (ae: ActivityNotFoundException) {
            ActionResult.failure("no dialer installed")
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity(ACTION_CALL/DIAL) threw: ${t.message}")
            ActionResult.failure("call failed: ${t.message}")
        }
    }

    /**
     * C4 — send an SMS directly via [SmsManager].
     *
     * Replaces the old voice-to-bridge flow that tapped through the default
     * SMS app's UI, which was fragile against carrier / OEM variants. This
     * path is one API call + a `PendingIntent` result callback so we can
     * actually wait for sent/failed delivery notifications and return a
     * meaningful [ActionResult] instead of fire-and-forget.
     *
     * Multi-part messages (>160 chars) go through [SmsManager.divideMessage]
     * + [SmsManager.sendMultipartTextMessage]. API-version-aware getSystemService
     * pattern per Android 31+ deprecation of [SmsManager.getDefault].
     *
     * Destructive-verb confirmation is done by [BridgeCommandHandler] before
     * we reach this method.
     */
    suspend fun sendSms(to: String, body: String): ActionResult {
        if (to.isBlank()) {
            return ActionResult.failure("send_sms: recipient must be non-blank")
        }
        if (body.isEmpty()) {
            return ActionResult.failure("send_sms: body must be non-empty")
        }
        if (!to.matches(Regex("^[+0-9 ()\\-.]{2,}$"))) {
            return ActionResult.failure("send_sms: recipient contains invalid characters")
        }

        val ctx: Context = service
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ActionResult.failure(
                "Grant SMS permission in Settings > Apps > Hermes-Relay > Permissions"
            )
        }

        val smsManager: SmsManager? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getSystemService(SmsManager) threw: ${t.message}")
            null
        }
        if (smsManager == null) {
            return ActionResult.failure("SmsManager unavailable on this device")
        }

        // Pick one intent action value — the receiver identifies us by
        // action match, and we'll pass a one-shot broadcast receiver that
        // unregisters after delivery.
        val sentAction = "com.hermesandroid.relay.SMS_SENT_${System.nanoTime()}"
        val filter = IntentFilter(sentAction)
        val deferred = kotlinx.coroutines.CompletableDeferred<Int>()

        // Pack a list of part-result codes so we know whether EVERY part
        // of a multi-part message succeeded.
        val partsResults = mutableListOf<Int>()
        // H4 fix: divideMessage was previously called twice (here and again
        // inside the send try block at line ~1505). On OEMs with encoding
        // quirks the two calls could theoretically produce different
        // segmentations, leaving expectedParts and the actual sentPi count
        // out of sync — the receiver would then wait forever for parts that
        // were never sent and trip the 15s timeout. Cache the result once.
        val parts: ArrayList<String> = try {
            ArrayList(smsManager.divideMessage(body) ?: emptyList())
        } catch (t: Throwable) {
            ArrayList<String>().apply { add(body) }
        }
        if (parts.isEmpty()) {
            parts.add(body)
        }
        val expectedParts = parts.size

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx2: Context?, intent: Intent?) {
                val code = resultCode
                synchronized(partsResults) {
                    partsResults.add(code)
                    if (partsResults.size >= expectedParts && !deferred.isCompleted) {
                        // Report the first non-OK code, or OK if all succeeded.
                        val worst = partsResults.firstOrNull { it != android.app.Activity.RESULT_OK }
                            ?: android.app.Activity.RESULT_OK
                        deferred.complete(worst)
                    }
                }
            }
        }

        // Android 14+ (API 34) requires RECEIVER_EXPORTED/NOT_EXPORTED. Our
        // SENT action is private to this process, so NOT_EXPORTED is the
        // safe default.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED
        } else {
            0
        }
        try {
            ContextCompat.registerReceiver(ctx, receiver, filter, flags)
        } catch (t: Throwable) {
            Log.w(TAG, "registerReceiver for SMS_SENT threw: ${t.message}")
            return ActionResult.failure("sms receiver registration failed: ${t.message}")
        }

        // One PendingIntent per part — SmsManager fires the broadcast with
        // a result code we inspect above.
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_ONE_SHOT
        }

        try {
            // H4: reuse the cached `parts` from above instead of calling
            // divideMessage a second time. `expectedParts == parts.size`.
            if (parts.size <= 1) {
                val sentPi = PendingIntent.getBroadcast(
                    ctx, 0, Intent(sentAction).setPackage(ctx.packageName), pendingFlags
                )
                smsManager.sendTextMessage(to, null, body, sentPi, null)
            } else {
                val sentPis = ArrayList<PendingIntent>(parts.size)
                for (i in parts.indices) {
                    sentPis.add(
                        PendingIntent.getBroadcast(
                            ctx, i, Intent(sentAction).setPackage(ctx.packageName), pendingFlags
                        )
                    )
                }
                smsManager.sendMultipartTextMessage(to, null, parts, sentPis, null)
            }
        } catch (se: SecurityException) {
            try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) { }
            return ActionResult.failure(
                "SMS permission revoked or restricted — re-grant in system Settings"
            )
        } catch (t: Throwable) {
            try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) { }
            Log.w(TAG, "sendTextMessage threw: ${t.message}")
            return ActionResult.failure("send_sms failed: ${t.message}")
        }

        // Wait for the receiver to complete — with a 15s cap. If the radio
        // is off or the carrier never acks we don't want the bridge
        // request to hang forever.
        val result = withTimeoutOrNull(SEND_SMS_TIMEOUT_MS) { deferred.await() }
        try { ctx.unregisterReceiver(receiver) } catch (_: Throwable) { }

        if (result == null) {
            return ActionResult.failure(
                "send_sms timeout after ${SEND_SMS_TIMEOUT_MS}ms — carrier never acked"
            )
        }
        return if (result == android.app.Activity.RESULT_OK) {
            ActionResult.ok(
                mapOf(
                    "to" to to,
                    "length" to body.length,
                    "parts" to expectedParts,
                )
            )
        } else {
            val reason = when (result) {
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
                SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
                SmsManager.RESULT_ERROR_NULL_PDU -> "null pdu"
                SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off (airplane mode?)"
                else -> "result code $result"
            }
            ActionResult.failure("send_sms failed: $reason")
        }
    }

}
