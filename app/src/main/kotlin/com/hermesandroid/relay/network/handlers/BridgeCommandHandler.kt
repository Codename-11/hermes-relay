package com.hermesandroid.relay.network.handlers

import android.content.Intent
import android.util.Log
import com.hermesandroid.relay.accessibility.ActionExecutor
import com.hermesandroid.relay.accessibility.HermesAccessibilityService
import com.hermesandroid.relay.accessibility.ScreenCapture
import com.hermesandroid.relay.accessibility.ScreenReader
// === PHASE3-safety-rails: safety enforcement ===
import com.hermesandroid.relay.bridge.BridgeSafetyManager
// === END PHASE3-safety-rails ===
// === PHASE3-event-stream: B1 EventStore polling + toggle ===
import com.hermesandroid.relay.event.EventStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
// === END PHASE3-event-stream ===
// === PHASE3-tier-C: flavor gate for sideload-only tools ===
import com.hermesandroid.relay.data.BuildFlavor
// === END PHASE3-tier-C ===
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.models.Envelope
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull

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
 *  - `/get_apps` (and `/apps` legacy alias) → returns
 *    `{apps: [{package, label}], count}` enumerated via PackageManager
 *  - `/open_app` body `{package}` → launches via `getLaunchIntentForPackage`
 *    (target-package blocklist enforced)
 *  - `/setup` → 200 no-op (host-side helper, phone has no setup work)
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

    /**
     * In-process entry point for callers that already live on the phone
     * (e.g. the sideload voice intent handler). Runs the same dispatch +
     * Tier 5 safety check pipeline as the WSS-incoming [onMessage] path,
     * but **does not send a `bridge.response` envelope** back over the
     * multiplexer.
     *
     * # Why not multiplexer.send()?
     *
     * The `bridge.command` envelope was designed as a server → phone
     * protocol: the server-side `android_*` Python tools call
     * `BridgeHandler.handle_command()` which routes a `bridge.command` to
     * the phone, and the phone replies with a `bridge.response`. The
     * voice intent handler tried to dispatch in the opposite direction —
     * phone → relay → phone — and the relay correctly logs `ignoring
     * unexpected bridge.command from phone` and drops it on the floor.
     *
     * Voice intents are phone-local: the classifier, resolver, and
     * action executor all run in this process. Round-tripping through
     * WSS adds latency, burns bandwidth, and has no semantic value. This
     * entry point lets in-process callers reuse the same dispatch
     * pipeline (including the safety modal!) without the WSS hop.
     *
     * # Local dispatch context
     *
     * The wrapper installs a [LocalDispatch] coroutine context element
     * before calling [dispatch]. Inside [respond] we check for the
     * presence of that element and skip [multiplexer.send] when it's
     * set — voice doesn't need the response payload, and sending it
     * would just bounce back through the same WSS protocol mismatch.
     *
     * # Thread safety
     *
     * The context element is per-coroutine, not per-instance, so a
     * concurrent WSS-incoming dispatch in flight at the same time will
     * NOT see the `LocalDispatch` marker and will respond normally over
     * the multiplexer. The two paths are fully independent.
     *
     * Caught by Bailey's on-device test 2026-04-14 — see the v0.4.1
     * "voice intent local dispatch loop" entry in ROADMAP.md.
     */
    suspend fun handleLocalCommand(envelope: Envelope): LocalDispatchResult {
        if (envelope.type != "bridge.command") {
            Log.v(TAG, "handleLocalCommand: ignoring non-command envelope type='${envelope.type}'")
            return LocalDispatchResult(
                status = 400,
                errorMessage = "non-command envelope",
                errorCode = null,
                resultJson = null,
            )
        }
        val requestId = envelope.payload["request_id"]?.jsonPrimitive?.content
            ?: run {
                Log.w(TAG, "handleLocalCommand: missing request_id — dropping")
                return LocalDispatchResult(
                    status = 400,
                    errorMessage = "missing request_id",
                    errorCode = null,
                    resultJson = null,
                )
            }
        val path = envelope.payload["path"]?.jsonPrimitive?.content.orEmpty()
        val method = envelope.payload["method"]?.jsonPrimitive?.content.orEmpty()
        val body = envelope.payload["body"] as? JsonObject
            ?: envelope.payload["params"] as? JsonObject
            ?: buildJsonObject { }

        // Capture slot for respond() to write into while we're inside the
        // LocalDispatch coroutine context. Voice mode reads this after
        // dispatch completes so it can emit a follow-up chat bubble
        // reflecting the real outcome (e.g. "SMS sent" vs "user denied"
        // vs "permission missing"), matching Bailey's 2026-04-15 feedback
        // that voice mode needed visible success/failure indication after
        // the safety modal resolved. Before this, local dispatch was
        // fire-and-forget — the response payload was built inside respond()
        // and dropped because of the LocalDispatch marker check.
        val sink = java.util.concurrent.atomic.AtomicReference<JsonObject?>()
        val status = java.util.concurrent.atomic.AtomicInteger(0)
        try {
            withContext(LocalDispatch(resultSink = sink, statusSink = status)) {
                dispatch(requestId, path, method, body)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "local bridge command '$path' threw: ${t.message}", t)
            return LocalDispatchResult(
                status = 500,
                errorMessage = t.message ?: "dispatch threw",
                errorCode = "dispatch_exception",
                resultJson = null,
            )
        }

        val resultJson = sink.get()
        val capturedStatus = status.get().takeIf { it != 0 } ?: 200
        val errorMessage = resultJson?.get("error")?.jsonPrimitive?.contentOrNull
        val errorCode = resultJson?.get("error_code")?.jsonPrimitive?.contentOrNull
        return LocalDispatchResult(
            status = capturedStatus,
            errorMessage = errorMessage,
            errorCode = errorCode,
            resultJson = resultJson,
        )
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

        // === PHASE3-event-stream: B1 android_events read-only polling ===
        // /events is a read-only peek at the EventStore ring buffer. The
        // buffer lives in our own process so there's no safety gate —
        // the agent already opted into streaming via /events/stream
        // which IS gated. This mirrors the /ping early-return path so
        // polling works even when the service is transiently unbound.
        if (path == "/events") {
            val limitRaw = body["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 50
            val limit = limitRaw.coerceIn(1, EventStore.MAX_ENTRIES)
            val since = body["since"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val entries = EventStore.recent(limit = limit, since = since)
            val arr: JsonArray = buildJsonArray {
                for (e in entries) {
                    add(
                        buildJsonObject {
                            put("timestamp", e.timestamp)
                            put("event_type", e.eventType)
                            e.packageName?.let { put("package_name", it) }
                            e.className?.let { put("class_name", it) }
                            e.text?.let { put("text", it) }
                            e.contentDescription?.let { put("content_description", it) }
                            put("source", e.source)
                        }
                    )
                }
            }
            respond(
                requestId, 200,
                buildJsonObject {
                    put("entries", arr)
                    put("count", entries.size)
                    put("streaming", EventStore.isStreaming)
                }
            )
            return
        }
        // === END PHASE3-event-stream ===

        // /setup exists on the relay as a legacy bridge HTTP route, but
        // android_setup() in plugin/tools/android_tool.py is host-side
        // only (it just writes ANDROID_BRIDGE_TOKEN to ~/.hermes/.env)
        // and never actually forwards anything to the phone. The route
        // is still registered on the relay, so we answer with a 200 no-op
        // instead of letting the command silent-drop into the unknown-
        // path 404. Joins the /ping + /events early-return cluster so it
        // works even when the accessibility service is transiently unbound.
        if (path == "/setup") {
            respond(
                requestId, 200,
                buildJsonObject {
                    put("ok", true)
                    put("note", "setup is host-side only — phone has no setup work")
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

        if (!service.isMasterEnabled() &&
            path != "/current_app" &&
            path != "/return_to_hermes"
        ) {
            // Crystal-clear error text + structured error_code. Bailey hit
            // 2026-04-15: when the phone was paired + a11y granted but
            // master toggle flipped off, the agent read the shorter
            // "Bridge is disabled" message as "bridge not paired" and
            // walked the user through re-pairing instead of telling them
            // to flip the toggle. LLM behavior is unreliable around
            // ambiguous phrasing — the fix is to be explicit that this
            // is NOT a pairing problem and name the exact action needed.
            return respond(
                requestId, 403,
                buildJsonObject {
                    put(
                        "error",
                        "Bridge agent control is disabled by the user. " +
                            "The phone IS paired and connected — this is " +
                            "NOT a pairing problem. The user must enable " +
                            "'Agent Control' in the Hermes Bridge tab " +
                            "master toggle before the bridge accepts commands.",
                    )
                    put("error_code", "bridge_disabled")
                    put(
                        "required_action",
                        "User toggles Agent Control ON in the Hermes Bridge tab",
                    )
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

            // === PHASE3-baseline-handlers: /get_apps + /open_app ===
            // Latent v0.3.0 regressions surfaced by the v0.4 gap-fix audit
            // on 2026-04-14: the Python relay registered these routes
            // during Wave 1 but the Kotlin dispatcher silent-dropped them
            // into the unknown-path 404 — so android_get_apps() and
            // android_open_app() have been broken since v0.3.0 shipped.
            // Pure-read /get_apps runs through the master + blocklist
            // gate above; /open_app additionally checks the *target*
            // package against the blocklist (defense-in-depth, mirrors
            // the /send_intent + /broadcast B4 pattern) since the gate
            // above only checks the foreground app.

            // /get_apps and /apps (legacy alias) — list launchable apps
            // via PackageManager. Requires the <queries> launcher-intent
            // declaration in AndroidManifest.xml, otherwise Android 11+
            // returns a near-empty list.
            "/get_apps", "/apps" -> {
                val pm = service.packageManager
                val launcher = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                val resolved = runCatching { pm.queryIntentActivities(launcher, 0) }
                    .getOrDefault(emptyList())
                val seen = HashSet<String>(resolved.size)
                val appsArray = buildJsonArray {
                    for (resolve in resolved) {
                        val info = resolve.activityInfo?.applicationInfo ?: continue
                        val pkg = info.packageName ?: continue
                        if (!seen.add(pkg)) continue
                        val label = runCatching { pm.getApplicationLabel(info).toString() }
                            .getOrDefault(pkg)
                        add(
                            buildJsonObject {
                                put("package", pkg)
                                put("label", label)
                            }
                        )
                    }
                }
                respond(
                    requestId, 200,
                    buildJsonObject {
                        put("apps", appsArray)
                        put("count", seen.size)
                    }
                )
            }

            // /open_app body {package} — launch via PackageManager.
            // Defense-in-depth: blocklist-check the *target* package
            // (the gate above only checks foreground). Returns 400 on
            // missing package, 403 on blocked, 404 on no-launch-intent,
            // 500 on startActivity failure.
            "/open_app" -> {
                val pkg = body["package"]?.jsonPrimitive?.content.orEmpty()
                if (pkg.isBlank()) {
                    respond(
                        requestId, 400,
                        buildJsonObject { put("error", "missing 'package' in body") }
                    )
                    return
                }
                val targetAllowed = safetyManager?.checkPackageAllowed(pkg) ?: true
                if (!targetAllowed) {
                    respond(
                        requestId, 403,
                        buildJsonObject { put("error", "blocked package $pkg") }
                    )
                    return
                }
                val pm = service.packageManager
                val intent = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
                if (intent == null) {
                    respond(
                        requestId, 404,
                        buildJsonObject {
                            put("error", "no launch intent for package '$pkg'")
                        }
                    )
                    return
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val launchResult = runCatching { service.startActivity(intent) }
                if (launchResult.isFailure) {
                    val err = launchResult.exceptionOrNull()
                    respond(
                        requestId, 500,
                        buildJsonObject {
                            put("error", err?.message ?: "startActivity failed for $pkg")
                        }
                    )
                    return
                }
                respond(
                    requestId, 200,
                    buildJsonObject {
                        put("ok", true)
                        put("package", pkg)
                    }
                )
            }

            // === PHASE3-return-to-hermes ===
            // Bring the Hermes Relay app back to foreground. Used by the
            // server-side agent as the final step of any multi-app task
            // (e.g. after driving Messages to send an SMS) so the user
            // sees the agent's reply in-context without manually switching
            // apps. The phone knows its own package name via service — no
            // parameter needed, works transparently on both sideload and
            // googlePlay flavors.
            //
            // Allowed even when the master toggle is off: returning focus
            // to our own app isn't a destructive action, and this tool
            // should still work if the user flips the toggle mid-session
            // so the agent can at least wrap up cleanly. Blocklist and
            // destructive-verb checks don't apply — it's a self-foreground
            // intent, not a phone-control action.
            "/return_to_hermes" -> {
                val selfPkg = service.packageName
                val intent = runCatching {
                    service.packageManager.getLaunchIntentForPackage(selfPkg)
                }.getOrNull()
                if (intent == null) {
                    respond(
                        requestId, 500,
                        buildJsonObject {
                            put("error", "couldn't resolve launch intent for self ($selfPkg)")
                        }
                    )
                    return
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val launchResult = runCatching { service.startActivity(intent) }
                if (launchResult.isFailure) {
                    respond(
                        requestId, 500,
                        buildJsonObject {
                            put("error", launchResult.exceptionOrNull()?.message
                                ?: "startActivity failed for $selfPkg")
                        }
                    )
                    return
                }
                respond(
                    requestId, 200,
                    buildJsonObject {
                        put("ok", true)
                        put("package", selfPkg)
                    }
                )
            }
            // === END PHASE3-return-to-hermes ===
            // === END PHASE3-baseline-handlers ===

            // === PHASE3-event-stream: B1 android_event_stream toggle ===
            // Toggle inbound AccessibilityEvent capture. Privacy-sensitive:
            // events can leak typed text + search queries, so the default
            // is false and transitions always wipe the buffer.
            "/events/stream" -> {
                val enabled = body["enabled"]?.jsonPrimitive?.booleanOrNull
                if (enabled == null) {
                    respond(
                        requestId, 400,
                        buildJsonObject {
                            put("error", "missing or invalid 'enabled' (must be boolean) in body")
                        }
                    )
                    return
                }
                EventStore.setStreaming(enabled)
                respond(
                    requestId, 200,
                    buildJsonObject {
                        put("streaming", EventStore.isStreaming)
                        put("buffer_cleared", true)
                    }
                )
            }
            // === END PHASE3-event-stream ===

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

            // === PHASE3-tier-C: sideload-only tools (C1-C4) ==================
            //
            // All four tools are declared sideload-only — their permissions
            // live in `app/src/sideload/AndroidManifest.xml` only, so the
            // googlePlay build doesn't even ship the manifest entries. We
            // still need a gate here because the Python tool registrations
            // are flavor-blind (Python has no compile-time flavor awareness)
            // and users on the Play track will see the tools in the agent's
            // tool list and may try to invoke them. Returning 403 with a
            // "sideload-only" body is clearer than a cryptic "permission
            // denied" crash.

            "/location" -> {
                if (!BuildFlavor.isSideload) {
                    respond(
                        requestId, 403,
                        buildJsonObject {
                            put("error", "android_location is sideload-only")
                        }
                    )
                    return
                }
                respondFromResult(requestId, executor.location())
            }

            "/search_contacts" -> {
                if (!BuildFlavor.isSideload) {
                    respond(
                        requestId, 403,
                        buildJsonObject {
                            put("error", "android_search_contacts is sideload-only")
                        }
                    )
                    return
                }
                val query = body["query"]?.jsonPrimitive?.content.orEmpty()
                if (query.isBlank()) {
                    respond(
                        requestId, 400,
                        buildJsonObject { put("error", "missing 'query' in body") }
                    )
                    return
                }
                val limit = body["limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
                respondFromResult(requestId, executor.searchContacts(query, limit))
            }

            "/call" -> {
                if (!BuildFlavor.isSideload) {
                    respond(
                        requestId, 403,
                        buildJsonObject {
                            put("error", "android_call auto-dial is sideload-only")
                        }
                    )
                    return
                }
                val number = body["number"]?.jsonPrimitive?.content.orEmpty()
                if (number.isBlank()) {
                    respond(
                        requestId, 400,
                        buildJsonObject { put("error", "missing 'number' in body") }
                    )
                    return
                }
                // Phone calls are irreversible — always go through the
                // destructive-verb confirmation modal, regardless of the
                // `bodyText` verb match result. We bypass requiresConfirmation
                // here deliberately: "make a phone call" is itself the
                // destructive action.
                //
                // M1 fix: previously the awaitConfirmation call was wrapped
                // in `if (safetyManager != null)`, which silently bypassed
                // the modal whenever safetyManager was null (test harness or
                // failed init). For irreversible actions we MUST fail closed.
                if (safetyManager == null) {
                    respond(
                        requestId, 503,
                        buildJsonObject {
                            put("error", "safety manager not initialized — refusing destructive action")
                        }
                    )
                    return
                }
                val confirmText = "Call $number?"
                val allowed = safetyManager.awaitConfirmation("/call", confirmText)
                if (!allowed) {
                    respond(
                        requestId, 403,
                        buildJsonObject {
                            put("error", "user denied destructive action")
                            put("reason", "confirmation_denied_or_timeout")
                        }
                    )
                    return
                }
                respondFromResult(requestId, executor.makeCall(number))
            }

            "/send_sms" -> {
                if (!BuildFlavor.isSideload) {
                    respond(
                        requestId, 403,
                        buildJsonObject {
                            put("error", "android_send_sms is sideload-only")
                        }
                    )
                    return
                }
                val to = body["to"]?.jsonPrimitive?.content.orEmpty()
                val smsBody = body["body"]?.jsonPrimitive?.content.orEmpty()
                if (to.isBlank() || smsBody.isEmpty()) {
                    respond(
                        requestId, 400,
                        buildJsonObject {
                            put("error", "missing 'to' or 'body' in body")
                        }
                    )
                    return
                }
                // Always confirm SMS sends — destructive action regardless
                // of verb match.
                //
                // M1 fix: same fail-closed treatment as /call above. Don't
                // silently dispatch a destructive SMS just because safety
                // wasn't wired.
                if (safetyManager == null) {
                    respond(
                        requestId, 503,
                        buildJsonObject {
                            put("error", "safety manager not initialized — refusing destructive action")
                        }
                    )
                    return
                }
                val confirmText = "Send '$smsBody' to $to?"
                val allowed = safetyManager.awaitConfirmation("/send_sms", confirmText)
                if (!allowed) {
                    respond(
                        requestId, 403,
                        buildJsonObject {
                            put("error", "user denied destructive action")
                            put("reason", "confirmation_denied_or_timeout")
                        }
                    )
                    return
                }
                respondFromResult(requestId, executor.sendSms(to, smsBody))
            }
            // === END PHASE3-tier-C ===

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

            // === PHASE3-B4: send_intent / broadcast ===
            // Raw Intent escape hatch. Both paths accept a target `pkg`
            // field; if it's on the safety blocklist we refuse here (the
            // top-level currentApp blocklist check above gates the *source*
            // app, this gates the *target* app). String-only extras map.
            "/send_intent" -> {
                val action = body["action"]?.jsonPrimitive?.content.orEmpty()
                if (action.isBlank()) {
                    respond(
                        requestId, 400,
                        buildJsonObject { put("error", "missing 'action' in body") }
                    )
                    return
                }
                val targetPkg = body["package"]?.jsonPrimitive?.contentOrNull
                val targetAllowed = safetyManager?.checkPackageAllowed(targetPkg) ?: true
                if (!targetAllowed) {
                    respond(
                        requestId, 403,
                        buildJsonObject {
                            put("error", "blocked package ${targetPkg ?: "unknown"}")
                        }
                    )
                    return
                }
                val data = body["data"]?.jsonPrimitive?.contentOrNull
                val component = body["component"]?.jsonPrimitive?.contentOrNull
                val category = body["category"]?.jsonPrimitive?.contentOrNull
                val extras = extractStringMap(body["extras"] as? JsonObject)
                respondFromResult(
                    requestId,
                    executor.sendIntent(action, data, targetPkg, component, extras, category)
                )
            }

            "/broadcast" -> {
                val action = body["action"]?.jsonPrimitive?.content.orEmpty()
                if (action.isBlank()) {
                    respond(
                        requestId, 400,
                        buildJsonObject { put("error", "missing 'action' in body") }
                    )
                    return
                }
                val targetPkg = body["package"]?.jsonPrimitive?.contentOrNull
                val targetAllowed = safetyManager?.checkPackageAllowed(targetPkg) ?: true
                if (!targetAllowed) {
                    respond(
                        requestId, 403,
                        buildJsonObject {
                            put("error", "blocked package ${targetPkg ?: "unknown"}")
                        }
                    )
                    return
                }
                val data = body["data"]?.jsonPrimitive?.contentOrNull
                val extras = extractStringMap(body["extras"] as? JsonObject)
                respondFromResult(
                    requestId,
                    executor.sendBroadcast(action, data, targetPkg, extras)
                )
            }
            // === END PHASE3-B4 ===

            else -> respond(
                requestId, 404,
                buildJsonObject { put("error", "unknown bridge path '$path'") }
            )
        }
    }

    /**
     * Convert a JSON object into a `Map<String, String>`, coercing every
     * value via [jsonPrimitive].content. Null / non-primitive values are
     * dropped rather than raising — the agent shouldn't get a 400 just
     * because it sent a nested object as an extra.
     */
    private fun extractStringMap(obj: JsonObject?): Map<String, String>? {
        if (obj == null || obj.isEmpty()) return null
        val out = LinkedHashMap<String, String>(obj.size)
        for ((k, v) in obj) {
            val s = (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            if (s != null) out[k] = s
        }
        return if (out.isEmpty()) null else out
    }

    private suspend fun respondFromResult(requestId: String, result: ActionExecutor.ActionResult) {
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
                val err = result.error ?: "unknown error"
                put("error", err)
                // M2: structured error code for the LLM tool-calling path.
                // ActionExecutor returns free-text errors like "Grant contacts
                // permission in Settings..." which LLMs CAN interpret, but
                // adding a machine-readable error_code + required_permission
                // gives the server-side agent a cleaner signal to classify
                // responses + offer actionable next steps instead of relaying
                // whatever phrasing the error string happens to use. Free
                // text stays alongside for LLMs that prefer it.
                classifyBridgeError(err)?.let { (code, perm) ->
                    put("error_code", code)
                    if (perm != null) put("required_permission", perm)
                }
            }
        }
        respond(requestId, status, payload)
    }

    /**
     * Substring-classify an ActionExecutor error string into a structured
     * (error_code, required_permission?) pair. Returns null when the error
     * doesn't match any known category — in that case the free-text
     * `error` field on the response is the only signal.
     *
     * The substring patterns are coupled to
     * [ActionExecutor.searchContacts] / [ActionExecutor.sendSms] /
     * [ActionExecutor.makeCall] phrasings. If those messages are changed,
     * update this classifier too or the `error_code` will degrade to
     * null and the LLM will fall back to reading the free text.
     */
    private fun classifyBridgeError(err: String): Pair<String, String?>? {
        val lower = err.lowercase()
        return when {
            // searchContacts upfront check + mid-query SecurityException
            "contacts permission" in lower || "contacts permission revoked" in lower ->
                "permission_denied" to "android.permission.READ_CONTACTS"
            // sendSms upfront check
            "sms permission" in lower ->
                "permission_denied" to "android.permission.SEND_SMS"
            // makeCall SecurityException recovery
            "permission denied despite grant" in lower ->
                "permission_denied" to "android.permission.CALL_PHONE"
            // Service-side failures that aren't permission-shaped
            "not connected" in lower || "service not connected" in lower ->
                "service_unavailable" to null
            "user denied destructive action" in lower ->
                "user_denied" to null
            "bridge agent control is disabled" in lower || "bridge is disabled" in lower ->
                "bridge_disabled" to null
            else -> null
        }
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

    private suspend fun respond(requestId: String, status: Int, result: JsonObject) {
        val envelope = Envelope(
            channel = "bridge",
            type = "bridge.response",
            payload = buildJsonObject {
                put("request_id", requestId)
                put("status", status)
                put("result", result)
            }
        )
        // Local dispatch: skip the multiplexer.send. The in-process caller
        // (voice intent handler today) routes commands through this path to
        // reuse the same dispatch + Tier 5 safety pipeline as WSS-incoming
        // commands, but the wire protocol is server→phone for commands and
        // phone→server for responses, so emitting a `bridge.response`
        // envelope in this direction would bounce back as "unexpected
        // bridge.response from phone" at the relay. Instead we capture the
        // payload into the sink on the LocalDispatch context element so
        // handleLocalCommand can return it to its caller. See
        // [handleLocalCommand] KDoc for the discovery story.
        val local = coroutineContext[LocalDispatch]
        if (local != null) {
            local.resultSink?.set(result)
            local.statusSink?.set(status)
            return
        }
        multiplexer.send(envelope)
    }
}

/**
 * Captured outcome of a local bridge dispatch. Voice mode reads this to
 * emit follow-up chat traces showing the real success/failure state of
 * an action after the safety modal resolves and the underlying
 * [ActionExecutor] method returns. The fields mirror what the LLM path
 * would see on a `bridge.response` envelope:
 *
 *  - [status] — HTTP-style status: 200 success, 400 client error,
 *    403 user denial / bridge disabled, 500 executor error
 *  - [errorMessage] — free-text error from the response payload, or null
 *    on success. Safe to speak / display verbatim to the user.
 *  - [errorCode] — structured classification (e.g. `permission_denied`,
 *    `bridge_disabled`, `user_denied`) when `respondFromResult` or a
 *    direct respond call includes one. Null for errors we haven't
 *    classified yet.
 *  - [resultJson] — the raw result object, for callers that need
 *    action-specific fields (e.g. the resolved phone number from
 *    /search_contacts). Optional.
 */
data class LocalDispatchResult(
    val status: Int,
    val errorMessage: String?,
    val errorCode: String?,
    val resultJson: JsonObject?,
) {
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * Coroutine context marker installed by [BridgeCommandHandler.handleLocalCommand]
 * so [BridgeCommandHandler.respond] knows the caller is in-process and
 * captures the response payload into [resultSink] + [statusSink] instead
 * of sending a `bridge.response` envelope over the multiplexer (which
 * would bounce back as "unexpected from phone" at the relay). Per-
 * coroutine, so concurrent WSS-incoming dispatches are unaffected — they
 * don't carry this element and respond() takes the multiplexer path.
 *
 * Both sinks are optional (null for call sites that don't need the
 * capture). The initial wiring from handleLocalCommand always sets them.
 */
private class LocalDispatch(
    val resultSink: java.util.concurrent.atomic.AtomicReference<JsonObject?>? = null,
    val statusSink: java.util.concurrent.atomic.AtomicInteger? = null,
) : AbstractCoroutineContextElement(LocalDispatch) {
    companion object Key : CoroutineContext.Key<LocalDispatch>
}
