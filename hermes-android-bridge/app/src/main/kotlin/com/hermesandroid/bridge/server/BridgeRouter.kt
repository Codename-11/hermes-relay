package com.hermesandroid.bridge.server

import com.google.gson.Gson
import com.hermesandroid.bridge.auth.PairingManager
import com.hermesandroid.bridge.executor.ActionExecutor
import com.hermesandroid.bridge.executor.ScreenReader
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val gson = Gson()

fun Application.configureRouting() {
    routing {

        get("/ping") {
            val serviceRunning = BridgeAccessibilityService.instance != null
            val authHeader = call.request.header(HttpHeaders.Authorization)
            val authenticated = PairingManager.validateToken(authHeader)
            call.respond(mapOf(
                "status" to "ok",
                "accessibilityService" to serviceRunning,
                "authenticated" to authenticated,
                "version" to "0.1.0"
            ))
        }

        get("/screen") {
            val bounds = call.request.queryParameters["bounds"] == "true"
            val tree = withContext(Dispatchers.Main) {
                ScreenReader.readCurrentScreen(bounds)
            }
            call.respond(mapOf("tree" to tree, "count" to countNodes(tree)))
        }

        post("/tap") {
            data class TapRequest(val x: Int? = null, val y: Int? = null, val nodeId: String? = null)
            val req = call.receive<TapRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.tap(req.x, req.y, req.nodeId)
            }
            call.respond(result)
        }

        post("/tap_text") {
            data class TapTextRequest(val text: String, val exact: Boolean = false)
            val req = call.receive<TapTextRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.tapText(req.text, req.exact)
            }
            call.respond(result)
        }

        post("/type") {
            data class TypeRequest(val text: String, val clearFirst: Boolean = false)
            val req = call.receive<TypeRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.typeText(req.text, req.clearFirst)
            }
            call.respond(result)
        }

        post("/swipe") {
            data class SwipeRequest(val direction: String, val distance: String = "medium")
            val req = call.receive<SwipeRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.swipe(req.direction, req.distance)
            }
            call.respond(result)
        }

        post("/open_app") {
            val body = call.receiveText()
            val json = gson.fromJson(body, Map::class.java)
            val pkg = json["package"] as? String
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing package")
            val result = ActionExecutor.openApp(pkg)
            call.respond(result)
        }

        post("/press_key") {
            data class PressKeyRequest(val key: String)
            val req = call.receive<PressKeyRequest>()
            val result = ActionExecutor.pressKey(req.key)
            call.respond(result)
        }

        get("/screenshot") {
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.takeScreenshot()
            }
            call.respond(result)
        }

        post("/scroll") {
            data class ScrollRequest(val direction: String, val nodeId: String? = null)
            val req = call.receive<ScrollRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.swipe(req.direction, "medium")
            }
            call.respond(result)
        }

        post("/wait") {
            data class WaitRequest(
                val text: String? = null,
                val className: String? = null,
                val timeoutMs: Int = 5000
            )
            val req = call.receive<WaitRequest>()
            val result = ActionExecutor.waitForElement(req.text, req.className, req.timeoutMs)
            call.respond(result)
        }

        get("/apps") {
            val apps = withContext(Dispatchers.Main) {
                ActionExecutor.getInstalledApps()
            }
            call.respond(mapOf("apps" to apps, "count" to apps.size))
        }

        get("/current_app") {
            val result = withContext(Dispatchers.Main) {
                val service = BridgeAccessibilityService.instance
                val windows = service?.windows
                val foreground = windows?.firstOrNull()?.root
                mapOf(
                    "package" to (foreground?.packageName ?: "unknown"),
                    "className" to (foreground?.className ?: "unknown")
                )
            }
            call.respond(result)
        }
    }
}

private fun countNodes(nodes: List<Any>): Int {
    return nodes.size
}
