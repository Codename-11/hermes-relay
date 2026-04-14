package com.hermesandroid.relay.network.handlers

import android.util.Log
import com.hermesandroid.relay.accessibility.ActionExecutor
import com.hermesandroid.relay.accessibility.HermesAccessibilityService
import com.hermesandroid.relay.accessibility.ScreenCapture
import com.hermesandroid.relay.accessibility.ScreenReader
// === PHASE3-safety-rails: safety enforcement ===
import com.hermesandroid.relay.bridge.BridgeSafetyManager
// === END PHASE3-safety-rails ===
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.models.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Phase 3 — accessibility `accessibility-runtime`
 *
 * Routes inbound `bridge.command` envelopes to [ActionExecutor] and
 * publishes the result as a `bridge.response` envelope.
 *
 * # Wire protocol (frozen — see Phase 3 plan)
 *
 * ```json
 * // server → app
 * {
 *   "channel": "bridge",
 *   "type": "bridge.command",
 *   "id": "<uuid>",
 *   "payload": {
 *     "request_id": "<uuid>",
 *     "method": "POST",                  // HTTP-style method, informational
 *     "path": "/tap",                    // canonical action name
 *     "params": { ... },                 // optional query-ish fields
 *     "body": { ... }                    // optional JSON body
 *   }
 * }
 *
 * // app → server
 * {
 *   "channel": "bridge",
 *   "type": "bridge.response",
 *   "id": "<uuid>",
 *   "payload": {
 *     "request_id": "<uuid>",
 *     "status": 200,                     // 200 ok, 400 bad request, 500 executor error
 *     "result": { "ok": true, ... }      // action-specific payload
 *   }
 * }
 * ```
 *
 * For `path` we accept:
 *
 *  - `/ping` → returns `{pong: true, ts: ...}`
 *  - `/tap` body `{x, y, duration_ms?}`
 *  - `/tap_text` body `{text}`
 *  - `/type` body `{text}`
 *  - `/swipe` body `{start_x, start_y, end_x, end_y, duration_ms?}`
 *  - `/scroll` body `{direction}` — `up`/`down`/`left`/`right`
 *  - `/press_key` body `{key}` — `home`/`back`/`recents`/etc
 *  - `/wait` body `{ms}`
 *  - `/screen` → returns full `ScreenContent` JSON
 *  - `/screenshot` → returns `{media: "MEDIA:hermes-relay://<token>"}`
 *  - `/current_app` → returns `{package: "com.whatever"}`
 *
 * # Master enable gate
 *
 * Before dispatching any action we check
 * [HermesAccessibilityService.instance] — if the user hasn't enabled the
 * service in Android Settings, we fail fast with status 503. If the
 * service is running but the soft master toggle is off we fail with 403
 * and a body explaining that Bridge is disabled in the app.
 */
class BridgeCommandHandler(
    private val multiplexer: ChannelMultiplexer,
    private val scope: CoroutineScope,
    private val screenCapture: ScreenCapture? = null,
    // === PHASE3-safety-rails: safety enforcement ===
    // Safety manager is optional so older tests that construct this handler
    // without the full DI graph still compile; in production ConnectionViewModel
    // always wires a BridgeSafetyManager instance and passes it in.
    private val safetyManager: BridgeSafetyManager? = null,
    // === END PHASE3-safety-rails ===
) {

    companion object {
        private const val TAG = "BridgeCommandHandler"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Hooked into [ChannelMultiplexer] via `registerHandler("bridge", ::onMessage)`. */
    fun onMessage(envelope: Envelope) {
        if (envelope.type != "bridge.command") {
            // bridge.response is outbound only, bridge.status is outbound
            // only — anything else is noise we drop silently.
            Log.v(TAG, "ignoring non-command bridge envelope type='${envelope.type}'")
            return
        }

        val requestId = envelope.payload["request_id"]
            ?.jsonPrimitive?.content
            ?: run {
                Log.w(TAG, "bridge.command missing request_id — dropping")
                return
            }

        val path = envelope.payload["path"]?.jsonPrimitive?.content.orEmpty()
        val body = envelope.payload["body"] as? JsonObject
            ?: envelope.payload["params"] as? JsonObject
            ?: buildJsonObject { }

        // Dispatch on a coroutine so suspend actions (gestures, screenshot)
        // don't block the multiplexer thread. Every branch must resolve by
        // calling [respond] exactly once — we leak a request otherwise.
        scope.launch {
            try {
                dispatch(requestId, path, body)
            } catch (t: Throwable) {
                Log.w(TAG, "bridge command '$path' threw: ${t.message}", t)
                respond(
                    requestId = requestId,
                    status = 500,
                    result = buildJsonObject {
                        put("error", t.message ?: "unknown executor error")
                    }
                )
            }
        }
    }

    private suspend fun dispatch(requestId: String, path: String, body: JsonObject) {
        // /ping is the only command that works without the a11y service —
        // everything else needs the service to be connected.
        if (path == "/ping") {
            respond(
                requestId, 200,
                buildJsonObject {
                    put("pong", true)
                    put("ts", System.currentTimeMillis())
                }
            )
            return
        }

        val service = HermesAccessibilityService.instance
            ?: return respond(
                requestId, 503,
                buildJsonObject {
                    put("error", "Hermes AccessibilityService not connected — enable it in Android Settings")
                }
            )

        if (!service.isMasterEnabled() && path != "/current_app") {
            return respond(
                requestId, 403,
                buildJsonObject {
                    put("error", "Bridge is disabled — enable Agent Control in the Bridge tab")
                }
            )
        }

        // === PHASE3-safety-rails: safety enforcement ===
        // Pure-read /ping and /current_app + /screen bypass Tier 5 verbs
        // (they don't perform destructive actions), but they DO still
        // respect the blocklist so a blocked app can't be screen-read.
        val currentPkg = service.currentApp
        val blocklistAllowed = safetyManager?.checkPackageAllowed(currentPkg) ?: true
        if (!blocklistAllowed) {
            return respond(
                requestId, 403,
                buildJsonObject {
                    put("error", "blocked package ${currentPkg ?: "unknown"}")
                }
            )
        }

        // Destructive-verb gate — only /tap_text and /type can carry
        // user-visible text that fires a real-world action. Other paths
        // skip the check.
        val bodyText = body["text"]?.jsonPrimitive?.content
        if (safetyManager != null && safetyManager.requiresConfirmation(path, bodyText)) {
            val allowed = safetyManager.awaitConfirmation(path, bodyText)
            if (!allowed) {
                return respond(
                    requestId, 403,
                    buildJsonObject {
                        put("error", "user denied destructive action")
                        put("reason", "confirmation_denied_or_timeout")
                    }
                )
            }
        }

        // Reschedule the idle auto-disable timer on every accepted
        // command. Safe to call even when no timer is currently armed.
        safetyManager?.rescheduleAutoDisable()
        // === END PHASE3-safety-rails ===

        val executor = service.actionExecutor

        when (path) {
            "/current_app" -> respond(
                requestId, 200,
                buildJsonObject {
                    put("package", service.currentApp ?: "unknown")
                }
            )

            "/tap" -> {
                val x = body["x"]?.jsonPrimitive?.content?.toIntOrNull()
                val y = body["y"]?.jsonPrimitive?.content?.toIntOrNull()
                if (x == null || y == null) {
                    respond(
                        requestId, 400,
                        buildJsonObject { put("error", "missing 'x' or 'y' in body") }
                    )
                    return
                }
                val duration = body["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 100L
                respondFromResult(requestId, executor.tap(x, y, duration))
            }

            "/tap_text" -> {
                val text = body["text"]?.jsonPrimitive?.content.orEmpty()
                if (text.isBlank()) {
                    respond(
                        requestId, 400,
                        buildJsonObject { put("error", "missing 'text' in body") }
                    )
                    return
                }
                respondFromResult(requestId, executor.tapText(text))
            }

            "/type" -> {
                val text = body["text"]?.jsonPrimitive?.content.orEmpty()
                if (text.isEmpty()) {
                    respond(
                        requestId, 400,
                        buildJsonObject { put("error", "missing 'text' in body") }
                    )
                    return
                }
                respondFromResult(requestId, executor.typeText(text))
            }

            "/swipe" -> {
                val sx = body["start_x"]?.jsonPrimitive?.content?.toIntOrNull()
                val sy = body["start_y"]?.jsonPrimitive?.content?.toIntOrNull()
                val ex = body["end_x"]?.jsonPrimitive?.content?.toIntOrNull()
                val ey = body["end_y"]?.jsonPrimitive?.content?.toIntOrNull()
                if (sx == null || sy == null || ex == null || ey == null) {
                    respond(
                        requestId, 400,
                        buildJsonObject {
                            put("error", "swipe requires start_x, start_y, end_x, end_y")
                        }
                    )
                    return
                }
                val duration = body["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 400L
                respondFromResult(requestId, executor.swipe(sx, sy, ex, ey, duration))
            }

            "/scroll" -> {
                val direction = body["direction"]?.jsonPrimitive?.content.orEmpty()
                respondFromResult(requestId, executor.scroll(direction))
            }

            "/press_key" -> {
                val key = body["key"]?.jsonPrimitive?.content.orEmpty()
                if (key.isBlank()) {
                    respond(
                        requestId, 400,
                        buildJsonObject { put("error", "missing 'key' in body") }
                    )
                    return
                }
                respondFromResult(requestId, executor.pressKey(key))
            }

            "/wait" -> {
                val ms = body["ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                respondFromResult(requestId, executor.wait(ms))
            }

            "/screen" -> {
                // P1 — walk every live accessibility window, not just
                // rootInActiveWindow. On googlePlay (no
                // flagRetrieveInteractiveWindows) this falls back to a
                // single-element list so behaviour is unchanged.
                val roots = service.snapshotAllWindows()
                if (roots.isEmpty()) {
                    return respond(
                        requestId, 500,
                        buildJsonObject { put("error", "no active window available") }
                    )
                }
                val includeBounds = body["include_bounds"]
                    ?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                try {
                    val screen = service.reader.readAllWindows(roots, includeBounds)
                    val screenJson = json.encodeToJsonElement(
                        ScreenReader.ScreenContent.serializer(),
                        screen
                    ).jsonObject
                    respond(requestId, 200, screenJson)
                } finally {
                    // Every root fetched via snapshotAllWindows() is a
                    // fresh AccessibilityNodeInfo and MUST be recycled.
                    for (r in roots) {
                        @Suppress("DEPRECATION")
                        try { r.recycle() } catch (_: Throwable) { }
                    }
                }
            }

            "/screenshot" -> {
                val capture = screenCapture
                    ?: return respond(
                        requestId, 503,
                        buildJsonObject {
                            put("error", "ScreenCapture not wired — Bridge UI must enable screenshots first")
                        }
                    )
                val result = capture.captureAndUpload()
                if (result.isSuccess) {
                    respond(
                        requestId, 200,
                        buildJsonObject { put("media", result.getOrNull()) }
                    )
                } else {
                    respond(
                        requestId, 500,
                        buildJsonObject {
                            put("error", result.exceptionOrNull()?.message ?: "screenshot failed")
                        }
                    )
                }
            }

            else -> respond(
                requestId, 404,
                buildJsonObject { put("error", "unknown bridge path '$path'") }
            )
        }
    }

    private fun respondFromResult(requestId: String, result: ActionExecutor.ActionResult) {
        val status = if (result.ok) 200 else 400
        val payload = buildJsonObject {
            if (result.ok) {
                for ((k, v) in result.data) {
                    when (v) {
                        null -> { /* skip null values — they carry no wire info */ }
                        is Int -> put(k, v)
                        is Long -> put(k, v)
                        is Boolean -> put(k, v)
                        is String -> put(k, v)
                        else -> put(k, v.toString())
                    }
                }
                if (result.data.isEmpty()) put("ok", true)
            } else {
                put("error", result.error ?: "unknown error")
            }
        }
        respond(requestId, status, payload)
    }

    private fun respond(requestId: String, status: Int, result: JsonObject) {
        val envelope = Envelope(
            channel = "bridge",
            type = "bridge.response",
            payload = buildJsonObject {
                put("request_id", requestId)
                put("status", status)
                put("result", result)
            }
        )
        multiplexer.send(envelope)
    }
}
