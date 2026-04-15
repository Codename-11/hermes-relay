package com.hermesandroid.relay.util

import android.os.Build
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Field

/**
 * Compose 1.10.6 on Android 15 can spam logcat with
 * `setRequestedFrameRate frameRate=NaN` from AndroidComposeView. Disabling the
 * global Compose ARR flag should prevent new roots from opting into that path,
 * but some roots can already exist by the time app code runs. This reflection
 * fallback force-disables ARR on the concrete AndroidComposeView instances we
 * attach so logcat stays usable until the upstream fix lands.
 */
object ComposeArrWorkaround {
    private const val ANDROID_COMPOSE_VIEW = "androidx.compose.ui.platform.AndroidComposeView"

    @Volatile
    private var cachedOwnerClass: Class<*>? = null

    @Volatile
    private var isArrEnabledField: Field? = null

    @Volatile
    private var currentFrameRateField: Field? = null

    @Volatile
    private var currentFrameRateCategoryField: Field? = null

    fun disableForViewTree(root: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        visit(root)
    }

    private fun visit(view: View) {
        disableForComposeRoot(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                visit(view.getChildAt(i))
            }
        }
    }

    private fun disableForComposeRoot(view: View) {
        if (view.javaClass.name != ANDROID_COMPOSE_VIEW) return
        val ownerClass = view.javaClass
        val arrField = field(ownerClass, "isArrEnabled") ?: return
        runCatching { arrField.setBoolean(view, false) }
        field(ownerClass, "currentFrameRate")?.let { runCatching { it.setFloat(view, 0f) } }
        field(ownerClass, "currentFrameRateCategory")?.let {
            runCatching { it.setFloat(view, 0f) }
        }
    }

    private fun field(ownerClass: Class<*>, name: String): Field? {
        if (cachedOwnerClass != ownerClass) {
            cachedOwnerClass = ownerClass
            isArrEnabledField = null
            currentFrameRateField = null
            currentFrameRateCategoryField = null
        }
        return when (name) {
            "isArrEnabled" -> isArrEnabledField ?: ownerClass.findDeclaredField(name)?.also {
                isArrEnabledField = it
            }
            "currentFrameRate" -> currentFrameRateField ?: ownerClass.findDeclaredField(name)?.also {
                currentFrameRateField = it
            }
            "currentFrameRateCategory" ->
                currentFrameRateCategoryField ?: ownerClass.findDeclaredField(name)?.also {
                    currentFrameRateCategoryField = it
                }
            else -> null
        }
    }

    private fun Class<*>.findDeclaredField(name: String): Field? =
        runCatching { getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
}
