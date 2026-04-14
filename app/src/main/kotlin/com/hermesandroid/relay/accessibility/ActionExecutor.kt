package com.hermesandroid.relay.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
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

    suspend fun tapText(needle: String): ActionResult = WakeLockManager.wakeForAction {
        if (needle.isBlank()) {
            return@wakeForAction ActionResult.failure("tap_text: text must be non-blank")
        }

        // P1 — prefer the multi-window snapshot so we can tap text inside
        // system overlays, popup menus, and notification shade content.
        // Falls back to a single-root list derived from rootInActiveWindow
        // when the service is a bare AccessibilityService stub (tests) or
        // when windows is unavailable.
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
            val bounds = reader.findNodeBoundsByText(ownedRoots, needle)
                ?: return@wakeForAction ActionResult.failure(
                    "no node matching text '$needle' on screen"
                )
            if (bounds.isEmpty) {
                return@wakeForAction ActionResult.failure(
                    "matched node has empty bounds (off-screen?)"
                )
            }
            // Nested wakeForAction: the inner tap() call will re-enter
            // WakeLockManager, bump the ref count, and share our lock —
            // no premature release.
            return@wakeForAction tap(bounds.centerX, bounds.centerY)
        } finally {
            for (r in ownedRoots) {
                @Suppress("DEPRECATION")
                try { r.recycle() } catch (_: Throwable) { }
            }
        }
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
     */
    suspend fun scroll(
        direction: String,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS,
    ): ActionResult = WakeLockManager.wakeForAction {
        val root = service.rootInActiveWindow
            ?: return@wakeForAction ActionResult.failure("no active window available")
        val bounds = android.graphics.Rect().also { root.getBoundsInScreen(it) }
        val cx = bounds.centerX()
        val cy = bounds.centerY()
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
