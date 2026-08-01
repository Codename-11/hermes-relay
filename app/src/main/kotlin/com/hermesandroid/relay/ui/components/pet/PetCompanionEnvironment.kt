package com.hermesandroid.relay.ui.components.pet

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.DisposableEffect
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState

/** Live agent/render state published by the currently visible app surface. */
data class PetCompanionActivity(
    val renderState: AvatarRenderState = AvatarRenderState(SphereState.Idle),
    val scrolling: Boolean = false,
    val hidden: Boolean = false,
)

@Stable
class PetCompanionCoordinator {
    var activity by mutableStateOf(PetCompanionActivity())
        private set

    fun publishRenderState(renderState: AvatarRenderState) {
        activity = activity.copy(renderState = renderState)
    }

    fun publishSurface(scrolling: Boolean, hidden: Boolean) {
        activity = activity.copy(scrolling = scrolling, hidden = hidden)
    }

    fun clearSurface() {
        activity = activity.copy(scrolling = false, hidden = false)
    }
}

val LocalPetCompanionCoordinator = staticCompositionLocalOf { PetCompanionCoordinator() }

/**
 * Explicit walkable surfaces measured by their owners. The host never guesses
 * from the semantics tree: screens opt in only where a pet cannot cover a
 * control. Coordinates use the shared Compose root space.
 */
@Stable
class PetSafeAreaRegistry {
    internal val walkRegions = mutableStateMapOf<String, Rect>()

    internal fun updateWalkRegion(key: String, bounds: Rect) {
        walkRegions[key] = bounds
    }

    internal fun removeWalkRegion(key: String) {
        walkRegions.remove(key)
    }
}

val LocalPetSafeAreaRegistry = staticCompositionLocalOf { PetSafeAreaRegistry() }

/** Register a real-layout strip as a safe autonomous walking surface. */
fun Modifier.petWalkRegion(key: String): Modifier = composed {
    val registry = LocalPetSafeAreaRegistry.current
    DisposableEffect(registry, key) {
        onDispose { registry.removeWalkRegion(key) }
    }
    onGloballyPositioned { coordinates ->
        registry.updateWalkRegion(key, coordinates.boundsInRoot())
    }
}
