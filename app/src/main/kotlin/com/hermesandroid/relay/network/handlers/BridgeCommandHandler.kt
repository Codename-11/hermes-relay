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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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
 *  - `/long_press` body `{x?, y?, node_id?, duration?}`
 *  - `/type` body `{text}`
 *  - `/swipe` body `{start_x, start_y, end_x, end_y, duration_ms?}`
 *  - `/drag` body `{start_x, start_y, end_x, end_y, duration_ms?}` (100-3000ms)
 *  - `/scroll` body `{direction}` — `up`/`down`/`left`/`right`
 *  - `/press_key` body `{key}` — `home`/`back`/`recents`/etc
 *  - `/wait` body `{ms}`
 *  - `/media` body `{action}` — `play`/`pause`/`toggle`/`next`/`previous`
 *  - `/screen` → returns full `ScreenContent` JSON
 *  - `/find_nodes` body `{text?, class_name?, clickable?, limit?}` →
 *    returns `{matches: [ScreenNode...], count}` filtered by criteria
 *  - `/screen_hash` → returns `{hash, node_count, truncated}` — cheap change
 *    detection for navigation loops (A5, see [ScreenHasher])
 *  - `/diff_screen` body `{previous_hash}` → returns `{changed, hash,
 *    node_count, truncated}` — compares current hash to [previous_hash]
 *  - `/screenshot` → returns `{media: "MEDIA:hermes-relay://<token>"}`
 *  - `/current_app` → returns `{package: "com.whatever"}`
 *  - `/clipboard` (GET) → returns `{text: "..."}` (empty string = nothing copied)
 *  - `/clipboard` (POST) body `{text}` → returns `{success: true}`
 *  - `/describe_node` body `{nodeId}` — A4: full property bag for a node
 *
 * # nodeId semantics (A4)
 *
 * `/tap` and `/scroll` accept an optional `nodeId` in the body. When
 * present, we resolve it against the live window tree via
 * [ScreenReader.findNodeById], extract the node's screen-bounds center,
 * and dispatch the gesture against those coords. `nodeId` wins over any
 * explicit `(x, y)` in the same body — matches the "prefer node_id"
 * contract documented on the Python `android_tap` / `android_scroll` tools.
 * A non-resolvable nodeId returns a 404-style error envelope.
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
        val method = envelope.payload["method"]?.jsonPrimitive?.content.orEmpty()
        val body = envelope.payload["body"] as? JsonObject
            ?: envelope.payload["params"] as? JsonObject
            ?: buildJsonObject { }

        // Dispatch on a coroutine so suspend actions (gestures, screenshot)
        // don't block the multiplexer thread. Every branch must resolve by
        // calling [respond] exactly once — we leak a request otherwise.
        scope.launch {
            try {
                dispatch(requestId, path, method, body)
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

    private suspend fun dispatch(
        requestId: String,
        path: String,
        method: String,
        body: JsonObject,
    ) {
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
                // A4: accept an optional `nodeId` from the body. Python side
                // (android_tool.android_tap) already forwards this — we were
                // ignoring it. If present, resolve via ScreenReader.findNodeById,
                // take the node bounds center, and dispatch the gesture there.
                // nodeId wins over (x,y) when both are provided — matches the
                // Python tool's "prefer node_id" docstring.
                val nodeId = body["nodeId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                if (nodeId != null) {
                    val roots = service.snapshotAllWindows()
                    if (roots.isEmpty()) {
                        respond(
                            requestId, 500,
                            buildJsonObject { put("error", "no active window available") }
                        )
                        return
                    }
                    val node = service.reader.findNodeById(roots, nodeId)
                    if (node == null) {
                        recycleWindowRoots(roots)
                        respond(
                            requestId, 404,
                            buildJsonObject { put("error", "node not resolvable: $nodeId") }
                        )
                        return
                    }
                    val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                    @Suppress("DEPRECATION")
                    try { node.recycle() } catch (_: Throwable) { }
                    recycleWindowRoots(roots)
                    if (rect.isEmpty) {
                        respond(
                            requestId, 400,
                            buildJsonObject {
                                put("error", "node '$nodeId' has empty bounds (off-screen?)")
                            }
                        )
                        return
                    }
                    val cx = rect.centerX()
                    val cy = rect.centerY()
                    val duration = body["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 100L
                    respondFromResult(requestId, executor.tap(cx, cy, duration))
                    return
                }

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

            // A1 long_press — body `{x?, y?, node_id?, duration?}` with
            // exactly one of (x,y) or node_id. Duration defaults to 500ms
            // and is clamped by ActionExecutor.longPress to 100..3000.
            "/long_press" -> {
                val lx = body["x"]?.jsonPrimitive?.content?.toIntOrNull()
                val ly = body["y"]?.jsonPrimitive?.content?.toIntOrNull()
                val nodeId = body["node_id"]?.jsonPrimitive?.content
                    ?: body["nodeId"]?.jsonPrimitive?.content
                val duration = body["duration"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: body["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: 500L
                respondFromResult(
                    requestId,
                    executor.longPress(lx, ly, nodeId, duration)
                )
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

            "/drag" -> {
                val sx = body["start_x"]?.jsonPrimitive?.content?.toIntOrNull()
                val sy = body["start_y"]?.jsonPrimitive?.content?.toIntOrNull()
                val ex = body["end_x"]?.jsonPrimitive?.content?.toIntOrNull()
                val ey = body["end_y"]?.jsonPrimitive?.content?.toIntOrNull()
                if (sx == null || sy == null || ex == null || ey == null) {
                    respond(
                        requestId, 400,
                        buildJsonObject {
                            put("error", "drag requires start_x, start_y, end_x, end_y")
                        }
                    )
                    return
                }
                val duration = body["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 500L
                respondFromResult(requestId, executor.drag(sx, sy, ex, ey, duration))
            }

            "/scroll" -> {
                val direction = body["direction"]?.jsonPrimitive?.content.orEmpty()
                // A4: optional nodeId targets the scroll at a specific
                // scrollable container. If present, resolve to its bounds
                // center; otherwise fall back to the legacy root-window
                // centered scroll. nodeId wins over any explicit center coords
                // in the body (though the Python tool doesn't send those).
                val nodeId = body["nodeId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                if (nodeId != null) {
                    val roots = service.snapshotAllWindows()
                    if (roots.isEmpty()) {
                        respond(
                            requestId, 500,
                            buildJsonObject { put("error", "no active window available") }
                        )
                        return
                    }
                    val node = service.reader.findNodeById(roots, nodeId)
                    if (node == null) {
                        recycleWindowRoots(roots)
                        respond(
                            requestId, 404,
                            buildJsonObject { put("error", "node not resolvable: $nodeId") }
                        )
                        return
                    }
                    val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
                    @Suppress("DEPRECATION")
                    try { node.recycle() } catch (_: Throwable) { }
                    recycleWindowRoots(roots)
                    if (rect.isEmpty) {
                        respond(
                            requestId, 400,
                            buildJsonObject {
                                put("error", "node '$nodeId' has empty bounds (off-screen?)")
                            }
                        )
                        return
                    }
                    respondFromResult(
                        requestId,
                        executor.scroll(
                            direction = direction,
                            centerX = rect.centerX(),
                            centerY = rect.centerY(),
                        )
                    )
                    return
                }
                respondFromResult(requestId, executor.scroll(direction))
            }

            "/describe_node" -> {
                // A4: return the full property bag for a specific node by ID.
                val targetId = body["nodeId"]?.jsonPrimitive?.content
                    ?: body["node_id"]?.jsonPrimitive?.content
                if (targetId.isNullOrBlank()) {
                    respond(
                        requestId, 400,
                        buildJsonObject { put("error", "missing 'nodeId' in body") }
                    )
                    return
                }
                val roots = service.snapshotAllWindows()
                if (roots.isEmpty()) {
                    respond(
                        requestId, 500,
                        buildJsonObject { put("error", "no active window available") }
                    )
                    return
                }
                val described = service.reader.describeNode(roots, targetId)
                recycleWindowRoots(roots)
                if (described.found && described.properties != null) {
                    respond(requestId, 200, described.properties)
                } else {
                    respond(
                        requestId, 404,
                        buildJsonObject {
                            put("error", described.error ?: "node not found: $targetId")
                        }
                    )
                }
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

            // A6: clipboard bridge. HTTP shape is `GET /clipboard`
            // (read) and `POST /clipboard` (write) — one path, two
            // methods — so we dispatch on `method` here instead of
            // splitting paths like /get_apps etc. Empty clipboard
            // returns `{"text": ""}` (NOT an error); empty-string
            // writes are allowed (effectively clear the clipboard).
            "/clipboard" -> {
                when (method.uppercase()) {
                    "GET" -> respondFromResult(requestId, executor.clipboardRead())
                    "POST" -> {
                        val text = body["text"]?.jsonPrimitive?.content
                        if (text == null) {
                            respond(
                                requestId, 400,
                                buildJsonObject { put("error", "missing 'text' in body") }
                            )
                            return
                        }
                        respondFromResult(requestId, executor.clipboardWrite(text))
                    }
                    else -> respond(
                        requestId, 405,
                        buildJsonObject {
                            put("error", "unsupported method '$method' for /clipboard")
                        }
                    )
                }
            }

            // A7: system-wide media-key broadcast (play/pause/next/previous/toggle)
            "/media" -> {
                val action = body["action"]?.jsonPrimitive?.content.orEmpty()
                if (action.isBlank()) {
                    respond(
                        requestId, 400,
                        buildJsonObject { put("error", "missing 'action' in body") }
                    )
                    return
                }
                respondFromResult(requestId, executor.mediaControl(action))
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

            "/find_nodes" -> {
                // Filtered targeted search — avoids dumping the full tree for
                // simple existence queries. All three filters (text/class_name/
                // clickable) AND together; omitted filters mean "any".
                val searchText = body["text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val searchClass = body["class_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val searchClickable = body["clickable"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                val limit = body["limit"]?.jsonPrimitive?.content?.toIntOrNull()
                    ?.coerceIn(1, ScreenReader.MAX_NODES)
                    ?: 20

                val roots = service.snapshotAllWindows()
                if (roots.isEmpty()) {
                    return respond(
                        requestId, 500,
                        buildJsonObject { put("error", "no active window available") }
                    )
                }

                try {
                    val matches = service.reader.searchNodes(
                        roots = roots,
                        text = searchText,
                        className = searchClass,
                        clickable = searchClickable,
                        limit = limit,
                    )
                    val matchesJson = buildJsonArray {
                        for (node in matches) {
                            add(
                                json.encodeToJsonElement(
                                    ScreenReader.ScreenNode.serializer(),
                                    node
                                )
                            )
                        }
                    }
                    respond(
                        requestId, 200,
                        buildJsonObject {
                            put("matches", matchesJson)
                            put("count", matches.size)
                        }
                    )
                } finally {
                    @Suppress("DEPRECATION")
                    for (r in roots) {
                        try { r.recycle() } catch (_: Throwable) { }
                    }
                }
            }

            "/screen_hash" -> {
                // A5 — cheap change detection. Walks the multi-window
                // accessibility tree from Wave 1 / P1's snapshotAllWindows
                // and returns SHA-256 of a stable per-node fingerprint.
                val roots = service.snapshotAllWindows()
                if (roots.isEmpty()) {
                    respond(
                        requestId, 500,
                        buildJsonObject { put("error", "no active windows available") }
                    )
                    return
                }
                try {
                    val result = service.hasher.screenHash(roots)
                    respond(
                        requestId, 200,
                        buildJsonObject {
                            put("hash", result.hash)
                            put("node_count", result.nodeCount)
                            put("truncated", result.truncated)
                        }
                    )
                } finally {
                    // snapshotAllWindows() hands us fresh references the
                    // caller owns — recycle them on the way out, matching
                    // the /screen path's contract.
                    for (r in roots) {
                        @Suppress("DEPRECATION")
                        try { r.recycle() } catch (_: Throwable) { }
                    }
                }
            }

            "/diff_screen" -> {
                val previousHash = body["previous_hash"]?.jsonPrimitive?.content.orEmpty()
                if (previousHash.isBlank()) {
                    respond(
                        requestId, 400,
                        buildJsonObject { put("error", "missing 'previous_hash' in body") }
                    )
                    return
                }
                val roots = service.snapshotAllWindows()
                if (roots.isEmpty()) {
                    respond(
                        requestId, 500,
                        buildJsonObject { put("error", "no active windows available") }
                    )
                    return
                }
                try {
                    val result = service.hasher.diffScreen(roots, previousHash)
                    respond(
                        requestId, 200,
                        buildJsonObject {
                            put("changed", result.changed)
                            put("hash", result.hash)
                            put("node_count", result.nodeCount)
                            put("truncated", result.truncated)
                        }
                    )
                } finally {
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

    /**
     * A4: recycle a list of window roots returned by
     * [HermesAccessibilityService.snapshotAllWindows]. Matches the same
     * contract the A1 `/screen` handler uses after serializing.
     */
    private fun recycleWindowRoots(roots: List<android.view.accessibility.AccessibilityNodeInfo>) {
        for (root in roots) {
            @Suppress("DEPRECATION")
            try { root.recycle() } catch (_: Throwable) { }
        }
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
