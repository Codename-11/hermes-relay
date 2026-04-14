package com.hermesandroid.relay.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Path
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
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
class ActionExecutor(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "ActionExecutor"

        /** Default tap duration in milliseconds. Below ~50ms some IMEs ignore. */
        private const val DEFAULT_TAP_DURATION_MS = 100L

        /** Default swipe duration in milliseconds. */
        private const val DEFAULT_SWIPE_DURATION_MS = 400L

        /** Hard cap on `wait` — prevents runaway agents from pinning the channel. */
        private const val MAX_WAIT_MS = 15_000L

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
        val expectedParts = try {
            smsManager.divideMessage(body).size.coerceAtLeast(1)
        } catch (t: Throwable) {
            1
        }

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
            val parts = smsManager.divideMessage(body)
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
