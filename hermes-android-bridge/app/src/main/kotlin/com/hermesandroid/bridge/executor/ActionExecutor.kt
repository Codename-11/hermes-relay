package com.hermesandroid.bridge.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.hermesandroid.bridge.model.ActionResult
import com.hermesandroid.bridge.power.WakeLockManager
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume

object ActionExecutor {

    suspend fun tap(x: Int? = null, y: Int? = null, nodeId: String? = null): ActionResult =
        WakeLockManager.wakeForAction {
        val service = BridgeAccessibilityService.instance
            ?: return@wakeForAction ActionResult(false, "Accessibility service not running")

        if (nodeId != null) {
            val node = findNodeById(nodeId)
                ?: return@wakeForAction ActionResult(false, "Node not found: $nodeId")
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return@wakeForAction ActionResult(result, if (result) "Tapped node $nodeId" else "Click action failed")
        }

        if (x != null && y != null) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            var done = false
            var success = false
            service.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) { success = true; done = true }
                override fun onCancelled(gestureDescription: GestureDescription) { success = false; done = true }
            }, null)
            var waited = 0
            while (!done && waited < 2000) { delay(50); waited += 50 }
            return@wakeForAction ActionResult(success, if (success) "Tapped ($x, $y)" else "Tap gesture cancelled")
        }

        ActionResult(false, "Provide either (x, y) or nodeId")
    }

    suspend fun tapText(text: String, exact: Boolean = false): ActionResult =
        WakeLockManager.wakeForAction {
        val node = ScreenReader.findNodeByText(text, exact)
            ?: return@wakeForAction ActionResult(false, "Element with text '$text' not found")
        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ActionResult(result, if (result) "Tapped '$text'" else "Click failed on '$text'")
    }

    suspend fun typeText(text: String, clearFirst: Boolean = false): ActionResult =
        WakeLockManager.wakeForAction {
        val service = BridgeAccessibilityService.instance
            ?: return@wakeForAction ActionResult(false, "Accessibility service not running")

        val focusedNode = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (clearFirst) {
            val bundle = Bundle()
            bundle.putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0
            )
            bundle.putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                focusedNode?.text?.length ?: 0
            )
            focusedNode?.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, bundle)
            focusedNode?.performAction(AccessibilityNodeInfo.ACTION_CUT)
        }

        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        val result = focusedNode?.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT, arguments
        ) ?: false
        ActionResult(result, if (result) "Typed text" else "No focused input found")
    }

    suspend fun swipe(direction: String, distance: String = "medium"): ActionResult =
        WakeLockManager.wakeForAction {
        val service = BridgeAccessibilityService.instance
            ?: return@wakeForAction ActionResult(false, "Accessibility service not running")

        val displayMetrics = service.resources.displayMetrics
        val w = displayMetrics.widthPixels
        val h = displayMetrics.heightPixels

        val shortDist = 0.2f
        val mediumDist = 0.4f
        val longDist = 0.7f
        val dist = when (distance) { "short" -> shortDist; "long" -> longDist; else -> mediumDist }

        val (startX, startY, endX, endY) = when (direction) {
            "up" ->    arrayOf(w / 2f, h * 0.7f, w / 2f, h * (0.7f - dist))
            "down" ->  arrayOf(w / 2f, h * 0.3f, w / 2f, h * (0.3f + dist))
            "left" ->  arrayOf(w * 0.8f, h / 2f, w * (0.8f - dist), h / 2f)
            "right" -> arrayOf(w * 0.2f, h / 2f, w * (0.2f + dist), h / 2f)
            else -> return@wakeForAction ActionResult(false, "Unknown direction: $direction")
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        service.dispatchGesture(gesture, null, null)
        delay(400)
        ActionResult(true, "Swiped $direction ($distance)")
    }

    fun openApp(packageName: String): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult(false, "App not found: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        return ActionResult(true, "Opening $packageName")
    }

    fun pressKey(key: String): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        val action = when (key) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "power" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            else -> return ActionResult(false, "Unknown key: $key")
        }
        val result = service.performGlobalAction(action)
        return ActionResult(result, if (result) "Pressed $key" else "Key press failed")
    }

    suspend fun waitForElement(
        text: String? = null,
        className: String? = null,
        timeoutMs: Int = 5000
    ): ActionResult {
        val interval = 500L
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            val nodes = ScreenReader.readCurrentScreen(false)
            val found = findInTree(nodes, text, className)
            if (found != null) {
                return ActionResult(true, "Element found", found)
            }
            delay(interval)
            elapsed += interval
        }
        return ActionResult(false, "Timeout waiting for element (text=$text, class=$className)")
    }

    suspend fun takeScreenshot(): ActionResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ActionResult(false, "Screenshot requires Android 11 (API 30) or higher")
        }
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")

        return suspendCancellableCoroutine { cont ->
            val executor = Executor { it.run() }
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hwBitmap = Bitmap.wrapHardwareBuffer(
                            result.hardwareBuffer, result.colorSpace
                        )
                        if (hwBitmap == null) {
                            cont.resume(ActionResult(false, "Failed to create bitmap"))
                            result.hardwareBuffer.close()
                            return
                        }
                        // Convert to software bitmap for compression
                        val bitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        hwBitmap.recycle()
                        result.hardwareBuffer.close()

                        val w = bitmap.width
                        val h = bitmap.height
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
                        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                        bitmap.recycle()

                        cont.resume(ActionResult(true, "Screenshot captured", mapOf(
                            "image" to base64,
                            "width" to w,
                            "height" to h,
                            "format" to "jpeg",
                            "encoding" to "base64"
                        )))
                    }

                    override fun onFailure(errorCode: Int) {
                        cont.resume(ActionResult(false, "Screenshot failed with error code $errorCode"))
                    }
                }
            )
        }
    }

    fun getInstalledApps(): List<Map<String, String>> {
        val service = BridgeAccessibilityService.instance ?: return emptyList()
        val pm = service.packageManager
        // Use queryIntentActivities to get all launchable apps (works on Android 11+)
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(launchIntent, 0).mapNotNull { resolveInfo ->
            val appInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
            mapOf(
                "packageName" to appInfo.packageName,
                "label" to pm.getApplicationLabel(appInfo).toString()
            )
        }.distinctBy { it["packageName"] }.sortedBy { it["label"] }
    }

    private fun findNodeById(nodeId: String): AccessibilityNodeInfo? {
        val service = BridgeAccessibilityService.instance ?: return null
        return service.windows
            .flatMapIndexed { wi, window ->
                window.root?.let { findNodeByIdInTree(it, nodeId, "$wi") } ?: emptyList()
            }
            .firstOrNull()
    }

    /** DFS search matching the stable ID format from ScreenReader.buildNode */
    private fun findNodeByIdInTree(
        info: AccessibilityNodeInfo, targetId: String, path: String
    ): List<AccessibilityNodeInfo> {
        val r = android.graphics.Rect()
        info.getBoundsInScreen(r)
        val id = "${info.packageName ?: "?"}_${info.className ?: "?"}_${path}_${r.left}_${r.top}_${r.right}_${r.bottom}"
        if (id == targetId) return listOf(info)
        val results = mutableListOf<AccessibilityNodeInfo>()
        for (i in 0 until info.childCount) {
            val child = info.getChild(i) ?: continue
            val found = findNodeByIdInTree(child, targetId, "${path}_$i")
            if (found.isNotEmpty()) {
                results.addAll(found)
                break
            } else {
                child.recycle()
            }
        }
        return results
    }

    private fun findInTree(
        nodes: List<com.hermesandroid.bridge.model.ScreenNode>,
        text: String?,
        className: String?
    ): com.hermesandroid.bridge.model.ScreenNode? {
        for (node in nodes) {
            val textMatch = text == null || node.text?.contains(text, true) == true ||
                    node.contentDescription?.contains(text, true) == true
            val classMatch = className == null || node.className == className
            if (textMatch && classMatch) return node
            val childMatch = findInTree(node.children, text, className)
            if (childMatch != null) return childMatch
        }
        return null
    }
}
