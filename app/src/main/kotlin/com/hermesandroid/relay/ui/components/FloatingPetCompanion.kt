package com.hermesandroid.relay.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.components.avatar.AgentAvatar
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import com.hermesandroid.relay.ui.components.avatar.PetLocomotion
import com.hermesandroid.relay.ui.components.pet.LocalPetSafeAreaRegistry
import com.hermesandroid.relay.ui.components.pet.PetLayoutDirection
import com.hermesandroid.relay.ui.components.pet.PetLogicalEdge
import com.hermesandroid.relay.ui.components.pet.PetFootprint
import com.hermesandroid.relay.ui.components.pet.PetPlacement
import com.hermesandroid.relay.ui.components.pet.PetPoint
import com.hermesandroid.relay.ui.components.pet.PetRoamingRail
import com.hermesandroid.relay.ui.components.pet.PetRoute
import com.hermesandroid.relay.ui.components.pet.PetSafeBounds
import com.hermesandroid.relay.ui.components.pet.choosePetRailTransfer
import com.hermesandroid.relay.ui.components.pet.expandObstaclesForPet
import com.hermesandroid.relay.ui.components.pet.findOverlayRoute
import com.hermesandroid.relay.ui.components.pet.petPerchSegments
import com.hermesandroid.relay.ui.components.pet.projectIntoSafeBounds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val FLOATING_PET_COMPACT_HEIGHT_DP = 700
internal const val CHAT_PET_WALK_REGION = "chat-composer-perch"
private const val PET_ROAM_REPEAT_DELAY_MS = 4_800L

internal fun shouldCompactFloatingPet(imeVisible: Boolean, screenHeightDp: Int): Boolean =
    imeVisible || screenHeightDp < FLOATING_PET_COMPACT_HEIGHT_DP

internal fun floatingPetVisualSizeDp(compact: Boolean): Int = if (compact) 40 else 48

internal fun shouldPauseFloatingPet(
    alreadyPaused: Boolean,
    animationEnabled: Boolean,
    isScrolling: Boolean,
): Boolean = alreadyPaused || !animationEnabled || isScrolling

internal fun floatingPetAlpha(isScrolling: Boolean): Float = if (isScrolling) 0.6f else 1f

internal fun floatingPetRoamDelayMs(hasMoved: Boolean): Long =
    if (hasMoved) PET_ROAM_REPEAT_DELAY_MS else 0L

internal fun petVerticalLocomotion(fromY: Float, toY: Float): PetLocomotion =
    if (toY > fromY) PetLocomotion.Fall else PetLocomotion.Jump

internal fun presentedPetLocomotion(dragging: Boolean, movement: PetLocomotion): PetLocomotion =
    if (dragging) PetLocomotion.Held else movement

internal fun shouldReleasePendingPetDrop(
    expectedPlacement: PetPlacement?,
    positionSettled: Boolean,
    roamingEnabled: Boolean,
    observedPlacement: PetPlacement,
): Boolean = expectedPlacement != null && positionSettled && !roamingEnabled &&
    observedPlacement == expectedPlacement

private data class PendingPetDrop(
    val point: PetPoint,
    val expectedPlacement: PetPlacement,
    val positionSettled: Boolean = false,
)

internal fun shouldRoamFloatingPet(
    roamingEnabled: Boolean,
    roamingAllowed: Boolean,
    hasWalkRegion: Boolean,
    state: SphereState,
    animationEnabled: Boolean,
    appForeground: Boolean,
    osAnimations: Boolean,
    touchExploration: Boolean,
    paused: Boolean,
    isScrolling: Boolean,
    dragging: Boolean,
    menuExpanded: Boolean,
): Boolean = roamingEnabled && roamingAllowed && hasWalkRegion &&
    state == SphereState.Idle && animationEnabled && appForeground && osAnimations &&
    !touchExploration && !paused && !isScrolling && !dragging && !menuExpanded

/**
 * One app-level, Petdex-compatible companion. Only the pet-sized child accepts
 * pointer input; the full-screen positioning box remains click-through.
 * Autonomous walking is restricted to screen-registered UI perches.
 */
@Composable
fun FloatingPetCompanion(
    pet: AgentAvatar,
    state: AvatarRenderState,
    placement: PetPlacement,
    roamingEnabled: Boolean,
    roamingAllowed: Boolean,
    isScrolling: Boolean,
    compact: Boolean,
    animationEnabled: Boolean,
    appForeground: Boolean,
    route: String?,
    onPlacementChanged: (PetPlacement) -> Unit,
    onRoamingEnabledChanged: (Boolean) -> Unit,
    onResetPlacement: () -> Unit,
    onHide: () -> Unit,
    onOpenAppearance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember(pet.id) { mutableStateOf(false) }
    var dragging by remember(pet.id) { mutableStateOf(false) }
    var draggedPoint by remember(pet.id) { mutableStateOf<PetPoint?>(null) }
    var pendingDrop by remember(pet.id) { mutableStateOf<PendingPetDrop?>(null) }
    var locomotion by remember(pet.id) { mutableStateOf(PetLocomotion.None) }
    var positioned by remember(pet.id) { mutableStateOf(false) }
    var viewportWidth by remember { mutableStateOf(0) }
    var viewportHeight by remember { mutableStateOf(0) }
    val x = remember(pet.id) { Animatable(0f) }
    val y = remember(pet.id) { Animatable(0f) }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val petLayoutDirection = if (layoutDirection == LayoutDirection.Ltr) {
        PetLayoutDirection.Ltr
    } else {
        PetLayoutDirection.Rtl
    }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val accessibleMotion = rememberAccessibleMotionState()
    val targetSize = if (compact) 48.dp else 56.dp
    val visualSize = floatingPetVisualSizeDp(compact).dp
    val targetSizePx = with(density) { targetSize.toPx() }
    val heldLiftPx = with(density) { 6.dp.toPx() }
    val safeMarginPx = with(density) { 12.dp.toPx() }
    val topClearancePx = with(density) { 76.dp.toPx() }
    val bottomClearancePx = with(density) { (if (compact) 84.dp else 104.dp).toPx() }
    val radius = targetSizePx / 2f
    val safeBounds = remember(
        viewportWidth,
        viewportHeight,
        targetSizePx,
        safeMarginPx,
        topClearancePx,
        bottomClearancePx,
    ) {
        val left = radius + safeMarginPx
        val top = radius + topClearancePx
        val right = (viewportWidth - radius - safeMarginPx).coerceAtLeast(left)
        val bottom = (viewportHeight - radius - bottomClearancePx).coerceAtLeast(top)
        PetSafeBounds(left, top, right, bottom)
    }
    val registry = LocalPetSafeAreaRegistry.current
    val footprint = remember(targetSizePx, safeMarginPx) {
        PetFootprint(targetSizePx, targetSizePx, safeMarginPx / 2f)
    }
    val safeAreaSnapshot = registry.snapshot(route)
    val roamingRails = remember(safeAreaSnapshot, footprint, safeBounds) {
        safeAreaSnapshot.perches.flatMap { perch ->
            petPerchSegments(
                perch = perch,
                obstacles = safeAreaSnapshot.obstacles,
                footprint = footprint,
                outer = safeBounds,
                minimumWidth = targetSizePx / 2f,
            ).mapIndexed { index, bounds ->
                PetRoamingRail(
                    key = "${perch.key}:$index",
                    perchKey = perch.key,
                    bounds = bounds,
                )
            }
        }
    }
    val registeredObstacles = remember(safeAreaSnapshot, footprint) {
        expandObstaclesForPet(
            obstacles = safeAreaSnapshot.obstacles.map { it.bounds } +
                safeAreaSnapshot.perches.map { it.bounds },
            footprint = footprint,
        )
    }
    val manualHomePoint = remember(placement, safeBounds, petLayoutDirection, registeredObstacles) {
        val requested = placement.sanitized().resolve(safeBounds, petLayoutDirection)
        projectIntoSafeBounds(requested, safeBounds, registeredObstacles) ?: requested
    }
    val homeRail = remember(roamingRails, manualHomePoint) {
        roamingRails.minByOrNull { rail -> rail.bounds.clamp(manualHomePoint).distanceSquaredTo(manualHomePoint) }
    }
    val roamingHomePoint = remember(placement.edge, homeRail, petLayoutDirection) {
        homeRail?.bounds?.let { rail ->
            val xAtEdge = if (
                (placement.edge == PetLogicalEdge.Start) == (petLayoutDirection == PetLayoutDirection.Ltr)
            ) rail.left else rail.right
            PetPoint(xAtEdge, rail.top)
        }
    }
    // Enabling roaming explicitly docks onto the screen-owned safe rail. A
    // manual drag or vertical accessibility action pauses roaming first, so the
    // persisted free-form placement remains visible and authoritative.
    val homePoint = if (roamingEnabled && roamingAllowed) {
        roamingHomePoint ?: manualHomePoint
    } else {
        manualHomePoint
    }

    val targetAlpha = floatingPetAlpha(isScrolling)
    val renderedAlpha = if (animationEnabled) {
        val animated by animateFloatAsState(
            targetValue = targetAlpha,
            animationSpec = tween(durationMillis = 140),
            label = "floating-pet-alpha",
        )
        animated
    } else {
        targetAlpha
    }
    val heldProgress by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = tween(durationMillis = 140),
        label = "floating-pet-held",
    )
    val canRoam = shouldRoamFloatingPet(
        roamingEnabled = roamingEnabled,
        roamingAllowed = roamingAllowed,
        hasWalkRegion = roamingRails.isNotEmpty(),
        state = state.state,
        animationEnabled = animationEnabled,
        appForeground = appForeground,
        osAnimations = accessibleMotion.osAnimations,
        touchExploration = accessibleMotion.touchExploration,
        paused = state.paused,
        isScrolling = isScrolling,
        dragging = dragging || pendingDrop != null,
        menuExpanded = menuExpanded,
    )

    LaunchedEffect(pet.id, homePoint) {
        if (!positioned) {
            x.snapTo(homePoint.x)
            y.snapTo(homePoint.y)
            positioned = true
        }
    }

    LaunchedEffect(homePoint, dragging, canRoam, pendingDrop) {
        if (positioned && !dragging && pendingDrop == null && !canRoam) {
            locomotion = PetLocomotion.None
            x.snapTo(homePoint.x)
            y.snapTo(homePoint.y)
        }
    }

    LaunchedEffect(pendingDrop, roamingEnabled, placement) {
        val pending = pendingDrop ?: return@LaunchedEffect
        if (
            shouldReleasePendingPetDrop(
                expectedPlacement = pending.expectedPlacement,
                positionSettled = pending.positionSettled,
                roamingEnabled = roamingEnabled,
                observedPlacement = placement,
            )
        ) {
            draggedPoint = null
            pendingDrop = null
        }
    }

    LaunchedEffect(pet.id, canRoam, roamingRails, homePoint, positioned) {
        if (!canRoam || !positioned) return@LaunchedEffect

        fun railSupporting(point: PetPoint): PetRoamingRail? = roamingRails.firstOrNull { rail ->
            point.x in rail.bounds.left..rail.bounds.right && abs(point.y - rail.bounds.top) <= 1f
        }

        suspend fun jumpToRail(
            rail: PetRoamingRail,
            requestedX: Float = x.value,
            plannedRoute: PetRoute? = null,
        ): Boolean {
            val destinationX = requestedX.coerceIn(rail.bounds.left, rail.bounds.right)
            val currentPoint = PetPoint(x.value, y.value)
            val routePlan = plannedRoute ?: findOverlayRoute(
                start = PetPoint(x.value, y.value),
                requestedDestination = PetPoint(destinationX, rail.bounds.top),
                bounds = safeBounds,
                uiObstacles = safeAreaSnapshot.obstacles.map { it.bounds },
                footprint = footprint,
            ) ?: return false
            // A valid autonomous route must begin at the live pet position.
            // Silently accepting a projected start would visually teleport the
            // pet and could skip across the control that caused the projection.
            if (routePlan.start.distanceSquaredTo(currentPoint) > 1f) return false
            routePlan.points.drop(1).forEach { waypoint ->
                locomotion = petVerticalLocomotion(y.value, waypoint.y)
                coroutineScope {
                    launch { x.animateTo(waypoint.x, tween(durationMillis = 460)) }
                    launch { y.animateTo(waypoint.y, tween(durationMillis = 460)) }
                }
            }
            locomotion = PetLocomotion.None
            return true
        }

        // A route change or user drop replans from the live point. The nearest
        // measured ledge wins; the transfer uses the Petdex jump row.
        try {
            var rail = railSupporting(PetPoint(x.value, y.value))
                ?: roamingRails.minByOrNull {
                    it.bounds.clamp(PetPoint(x.value, y.value)).distanceSquaredTo(PetPoint(x.value, y.value))
                }
                ?: return@LaunchedEffect
            if (railSupporting(PetPoint(x.value, y.value)) == null && !jumpToRail(rail)) {
                return@LaunchedEffect
            }

            var hasMoved = false
            while (true) {
                val delayMs = floatingPetRoamDelayMs(hasMoved)
                if (delayMs > 0L) delay(delayMs)
                val destinationX = if (abs(x.value - rail.bounds.left) <= abs(x.value - rail.bounds.right)) {
                    rail.bounds.right
                } else {
                    rail.bounds.left
                }
                locomotion = if (destinationX < x.value) PetLocomotion.WalkLeft else PetLocomotion.WalkRight
                val duration = ((abs(destinationX - x.value) / density.density) * 18f)
                    .roundToInt()
                    .coerceIn(1_800, 6_000)
                x.animateTo(destinationX, tween(duration))
                locomotion = PetLocomotion.None
                hasMoved = true
                delay(2_400L)

                // Different ledges retain Desktop's overlap rule. Android may
                // also hop between sibling segments when its registered-control
                // router proves an above-perch route around the obstacle.
                val transfer = choosePetRailTransfer(
                    currentRail = rail,
                    current = PetPoint(x.value, y.value),
                    rails = roamingRails,
                    bounds = safeBounds,
                    uiObstacles = safeAreaSnapshot.obstacles.map { it.bounds },
                    footprint = footprint,
                )
                if (transfer != null) {
                    val nextRail = transfer.rail
                    val hopX = transfer.destinationX
                    if (!transfer.siblingSegment && abs(x.value - hopX) > 1f) {
                        locomotion = if (hopX < x.value) PetLocomotion.WalkLeft else PetLocomotion.WalkRight
                        x.animateTo(hopX, tween(durationMillis = 900))
                        locomotion = PetLocomotion.None
                    }
                    if (jumpToRail(nextRail, hopX, transfer.route)) rail = nextRail
                }
            }
        } finally {
            locomotion = PetLocomotion.None
        }
    }

    val stateLabel = stringResource(state.state.floatingPetStateLabelRes())
    val companionDescription = stringResource(
        R.string.floating_pet_companion_description,
        pet.label,
        stateLabel,
    )
    val moveStartLabel = stringResource(R.string.floating_pet_action_move_start)
    val moveEndLabel = stringResource(R.string.floating_pet_action_move_end)
    val moveUpLabel = stringResource(R.string.floating_pet_action_move_up)
    val moveDownLabel = stringResource(R.string.floating_pet_action_move_down)
    val resetLabel = stringResource(R.string.floating_pet_action_reset)
    val appearanceLabel = stringResource(R.string.floating_pet_menu_appearance)
    val hideLabel = stringResource(R.string.floating_pet_menu_hide)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                viewportWidth = it.width
                viewportHeight = it.height
            },
    ) {
        Box(
            modifier = Modifier
                .offset {
                    val displayed = draggedPoint ?: PetPoint(x.value, y.value)
                    IntOffset(
                        (displayed.x - targetSizePx / 2f).roundToInt(),
                        (displayed.y - targetSizePx / 2f).roundToInt(),
                    )
                }
                .size(targetSize)
                .alpha(if (positioned) renderedAlpha else 0f)
                .graphicsLayer {
                    val scale = 1f + heldProgress * 0.10f
                    scaleX = scale
                    scaleY = scale
                    translationY = -heldLiftPx * heldProgress
                }
                .pointerInput(pet.id, safeBounds, roamingRails, registeredObstacles) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            pendingDrop = null
                            dragging = true
                            draggedPoint = PetPoint(x.value, y.value)
                            locomotion = PetLocomotion.None
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scope.launch {
                                x.stop()
                                y.stop()
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            draggedPoint = null
                        },
                        onDragEnd = {
                            val dropped = draggedPoint ?: PetPoint(x.value, y.value)
                            val updatedPlacement = safeBounds.snapToEdge(
                                dropped,
                                petLayoutDirection,
                                placement.edge,
                            )
                            pendingDrop = PendingPetDrop(dropped, updatedPlacement)
                            if (roamingEnabled) onRoamingEnabledChanged(false)
                            onPlacementChanged(updatedPlacement)
                            scope.launch {
                                x.snapTo(dropped.x)
                                y.snapTo(dropped.y)
                                locomotion = PetLocomotion.None
                                pendingDrop = pendingDrop?.let { pending ->
                                    if (
                                        pending.point == dropped &&
                                        pending.expectedPlacement == updatedPlacement
                                    ) {
                                        pending.copy(positionSettled = true)
                                    } else {
                                        pending
                                    }
                                }
                                dragging = false
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val current = draggedPoint ?: PetPoint(x.value, y.value)
                            val projected = projectIntoSafeBounds(
                                requested = PetPoint(current.x + dragAmount.x, current.y + dragAmount.y),
                                bounds = safeBounds,
                                obstacles = registeredObstacles,
                            ) ?: return@detectDragGesturesAfterLongPress
                            draggedPoint = projected
                        },
                    )
                }
                .clickable { menuExpanded = true }
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = companionDescription
                    stateDescription = stateLabel
                    customActions = listOf(
                        CustomAccessibilityAction(moveStartLabel) {
                            onPlacementChanged(placement.copy(edge = PetLogicalEdge.Start)); true
                        },
                        CustomAccessibilityAction(moveEndLabel) {
                            onPlacementChanged(placement.copy(edge = PetLogicalEdge.End)); true
                        },
                        CustomAccessibilityAction(moveUpLabel) {
                            if (roamingEnabled) onRoamingEnabledChanged(false)
                            onPlacementChanged(placement.copy(verticalFraction = placement.verticalFraction - 0.15f)); true
                        },
                        CustomAccessibilityAction(moveDownLabel) {
                            if (roamingEnabled) onRoamingEnabledChanged(false)
                            onPlacementChanged(placement.copy(verticalFraction = placement.verticalFraction + 0.15f)); true
                        },
                        CustomAccessibilityAction(resetLabel) { onResetPlacement(); true },
                        CustomAccessibilityAction(appearanceLabel) { onOpenAppearance(); true },
                        CustomAccessibilityAction(hideLabel) { onHide(); true },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-3).dp)
                    .size(width = visualSize * 0.62f, height = 5.dp)
                    .background(
                        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.20f),
                        shape = CircleShape,
                    ),
            )
            key(pet.id) {
                pet.Render(
                    state = state.copy(
                        petLocomotion = presentedPetLocomotion(dragging, locomotion),
                        paused = shouldPauseFloatingPet(
                            alreadyPaused = state.paused || !accessibleMotion.osAnimations ||
                                accessibleMotion.touchExploration,
                            animationEnabled = animationEnabled,
                            isScrolling = isScrolling,
                        ),
                    ),
                    modifier = Modifier.size(visualSize),
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("${pet.label} · $stateLabel", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = {},
                    enabled = false,
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (roamingEnabled) R.string.floating_pet_menu_pause_roaming
                                else R.string.floating_pet_menu_enable_roaming,
                            ),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onRoamingEnabledChanged(!roamingEnabled)
                    },
                )
                DropdownMenuItem(
                    text = { Text(resetLabel) },
                    onClick = {
                        menuExpanded = false
                        onResetPlacement()
                    },
                )
                DropdownMenuItem(
                    text = { Text(appearanceLabel) },
                    onClick = {
                        menuExpanded = false
                        onOpenAppearance()
                    },
                )
                DropdownMenuItem(
                    text = { Text(hideLabel) },
                    onClick = {
                        menuExpanded = false
                        onHide()
                    },
                )
            }
        }
    }
}

private fun SphereState.floatingPetStateLabelRes(): Int = when (this) {
    SphereState.Idle -> R.string.floating_pet_state_idle
    SphereState.Thinking -> R.string.floating_pet_state_thinking
    SphereState.Streaming -> R.string.floating_pet_state_streaming
    SphereState.Listening -> R.string.floating_pet_state_listening
    SphereState.Speaking -> R.string.floating_pet_state_speaking
    SphereState.Error -> R.string.floating_pet_state_error
}
