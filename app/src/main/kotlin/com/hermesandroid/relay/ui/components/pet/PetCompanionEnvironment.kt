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
    private var renderState by mutableStateOf(AvatarRenderState(SphereState.Idle))
    private val surfaces = mutableStateMapOf<String, PetCompanionSurface>()

    fun publishRenderState(renderState: AvatarRenderState) {
        this.renderState = renderState
    }

    fun publishSurface(owner: String, scrolling: Boolean, hidden: Boolean) {
        require(owner.isNotBlank()) { "Pet surface owner must not be blank." }
        surfaces[owner] = PetCompanionSurface(scrolling, hidden)
    }

    fun clearSurface(owner: String) {
        surfaces.remove(owner)
    }

    fun activityFor(owner: String?): PetCompanionActivity {
        val surface = owner?.let(surfaces::get)
        return PetCompanionActivity(
            renderState = renderState,
            scrolling = surface?.scrolling == true,
            hidden = surface?.hidden == true,
        )
    }
}

private data class PetCompanionSurface(val scrolling: Boolean, val hidden: Boolean)

val LocalPetCompanionCoordinator = staticCompositionLocalOf { PetCompanionCoordinator() }

/**
 * Explicit UI surfaces measured by their owners. Their top edges become
 * walkable perches, matching Hermes Desktop's live-DOM ledge model without
 * inserting layout space or guessing from the semantics tree.
 */
@Stable
class PetSafeAreaRegistry {
    // Compatibility view consumed by the current single-rail host.
    internal val walkRegions = mutableStateMapOf<String, Rect>()
    private val perchRegions = mutableStateMapOf<String, PetMeasuredPerch>()
    private val obstacleRegions = mutableStateMapOf<String, PetMeasuredObstacle>()
    private val visitTargetRegions = mutableStateMapOf<String, PetMeasuredVisitTarget>()

    internal fun updateWalkRegion(key: String, bounds: Rect) {
        updatePerch(key, bounds, PetRouteScope())
    }

    internal fun updatePerch(key: String, bounds: Rect, routeScope: PetRouteScope) {
        walkRegions[key] = bounds
        perchRegions[key] = PetMeasuredPerch(key, bounds.toPetObstacle(), routeScope)
    }

    internal fun removeWalkRegion(key: String) {
        removePerch(key)
    }

    internal fun removePerch(key: String) {
        walkRegions.remove(key)
        perchRegions.remove(key)
    }

    internal fun updateObstacle(key: String, bounds: Rect, routeScope: PetRouteScope) {
        obstacleRegions[key] = PetMeasuredObstacle(key, bounds.toPetObstacle(), routeScope)
    }

    internal fun removeObstacle(key: String) {
        obstacleRegions.remove(key)
    }

    internal fun updateVisitTarget(key: String, bounds: Rect, routeScope: PetRouteScope) {
        visitTargetRegions[key] = PetMeasuredVisitTarget(key, bounds.toPetObstacle(), routeScope)
    }

    internal fun removeVisitTarget(key: String) {
        visitTargetRegions.remove(key)
    }

    /** Immutable, deterministic view containing only surfaces valid for [route]. */
    fun snapshot(route: String?): PetSafeAreaSnapshot = PetSafeAreaSnapshot(
        route = route,
        perches = perchRegions.values
            .filter { it.routeScope.includes(route) }
            .sortedBy { it.key },
        obstacles = obstacleRegions.values
            .filter { it.routeScope.includes(route) }
            .sortedBy { it.key },
        visitTargets = visitTargetRegions.values
            .filter { it.routeScope.includes(route) }
            .sortedBy { it.key },
    )

    private fun Rect.toPetObstacle(): PetObstacle = PetObstacle(left, top, right, bottom)
}

private fun Set<String>.toPetRouteScope(): PetRouteScope = PetRouteScope(toSet())

private fun Modifier.measuredPetSurface(
    key: String,
    routes: Set<String>,
    update: PetSafeAreaRegistry.(String, Rect, PetRouteScope) -> Unit,
    remove: PetSafeAreaRegistry.(String) -> Unit,
): Modifier = composed {
    require(key.isNotBlank()) { "Pet surface key must not be blank." }
    val registry = LocalPetSafeAreaRegistry.current
    val routeScope = routes.toPetRouteScope()
    DisposableEffect(registry, key, routeScope) {
        onDispose { remove.invoke(registry, key) }
    }
    onGloballyPositioned { coordinates ->
        update.invoke(registry, key, coordinates.boundsInRoot(), routeScope)
    }
}

val LocalPetSafeAreaRegistry = staticCompositionLocalOf { PetSafeAreaRegistry() }

/** Register an existing UI element whose top edge is a safe pet perch. */
fun Modifier.petPerchSurface(
    key: String,
    routes: Set<String> = emptySet(),
): Modifier = measuredPetSurface(
    key = key,
    routes = routes,
    update = PetSafeAreaRegistry::updatePerch,
    remove = PetSafeAreaRegistry::removePerch,
)

/** Register an existing UI element as a collision obstacle. */
fun Modifier.petObstacleSurface(
    key: String,
    routes: Set<String> = emptySet(),
): Modifier = measuredPetSurface(
    key = key,
    routes = routes,
    update = PetSafeAreaRegistry::updateObstacle,
    remove = PetSafeAreaRegistry::removeObstacle,
)

/**
 * Register an existing UI element as a temporary point of interest. Visit
 * targets are measured but never promoted to walkable rails or obstacles.
 */
fun Modifier.petVisitTargetSurface(
    key: String,
    routes: Set<String> = emptySet(),
): Modifier = measuredPetSurface(
    key = key,
    routes = routes,
    update = PetSafeAreaRegistry::updateVisitTarget,
    remove = PetSafeAreaRegistry::removeVisitTarget,
)
