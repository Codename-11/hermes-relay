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
import androidx.compose.ui.geometry.Rect
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
import com.hermesandroid.relay.ui.components.pet.PetObstacle
import com.hermesandroid.relay.ui.components.pet.PetPlacement
import com.hermesandroid.relay.ui.components.pet.PetPoint
import com.hermesandroid.relay.ui.components.pet.PetSafeBounds
import com.hermesandroid.relay.ui.components.pet.expandObstaclesForPet
import com.hermesandroid.relay.ui.components.pet.projectIntoSafeBounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val FLOATING_PET_COMPACT_HEIGHT_DP = 700
internal const val CHAT_PET_WALK_REGION = "chat-composer-perch"

internal fun shouldCompactFloatingPet(imeVisible: Boolean, screenHeightDp: Int): Boolean =
    imeVisible || screenHeightDp < FLOATING_PET_COMPACT_HEIGHT_DP

internal fun floatingPetVisualSizeDp(compact: Boolean): Int = if (compact) 40 else 48

internal fun shouldPauseFloatingPet(
    alreadyPaused: Boolean,
    animationEnabled: Boolean,
    isScrolling: Boolean,
): Boolean = alreadyPaused || !animationEnabled || isScrolling

internal fun floatingPetAlpha(isScrolling: Boolean): Float = if (isScrolling) 0.6f else 1f

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
    walkRegionKey: String?,
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
    var locomotion by remember(pet.id) { mutableStateOf(PetLocomotion.None) }
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
    val rawWalkRegion = walkRegionKey?.let { registry.walkRegions[it] }
    val walkBounds = rawWalkRegion?.toPetPerchBounds(radius, safeBounds)
    val registeredObstacles = remember(rawWalkRegion, targetSizePx, safeMarginPx) {
        rawWalkRegion?.let { rect ->
            expandObstaclesForPet(
                obstacles = listOf(PetObstacle(rect.left, rect.top, rect.right, rect.bottom)),
                footprint = PetFootprint(targetSizePx, targetSizePx, safeMarginPx / 2f),
            )
        }.orEmpty()
    }
    val manualHomePoint = remember(placement, safeBounds, petLayoutDirection, registeredObstacles) {
        val requested = placement.sanitized().resolve(safeBounds, petLayoutDirection)
        projectIntoSafeBounds(requested, safeBounds, registeredObstacles) ?: requested
    }
    val roamingHomePoint = remember(placement.edge, walkBounds, petLayoutDirection) {
        walkBounds?.let { rail ->
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
    val canRoam = shouldRoamFloatingPet(
        roamingEnabled = roamingEnabled,
        roamingAllowed = roamingAllowed,
        hasWalkRegion = walkBounds != null,
        state = state.state,
        animationEnabled = animationEnabled,
        appForeground = appForeground,
        osAnimations = accessibleMotion.osAnimations,
        touchExploration = accessibleMotion.touchExploration,
        paused = state.paused,
        isScrolling = isScrolling,
        dragging = dragging,
        menuExpanded = menuExpanded,
    )

    LaunchedEffect(homePoint, dragging, canRoam) {
        if (!dragging && !canRoam) {
            locomotion = PetLocomotion.None
            x.snapTo(homePoint.x)
            y.snapTo(homePoint.y)
        }
    }

    LaunchedEffect(pet.id, canRoam, walkBounds, homePoint) {
        val rail = walkBounds ?: return@LaunchedEffect
        if (!canRoam) return@LaunchedEffect
        x.snapTo(homePoint.x)
        y.snapTo(homePoint.y)
        while (true) {
            delay(8_000L)
            val destinationX = if (abs(x.value - rail.left) <= abs(x.value - rail.right)) {
                rail.right
            } else {
                rail.left
            }
            locomotion = if (destinationX < x.value) PetLocomotion.RunLeft else PetLocomotion.RunRight
            val duration = ((abs(destinationX - x.value) / density.density) * 18f)
                .roundToInt()
                .coerceIn(1_800, 6_000)
            x.animateTo(destinationX, tween(duration))
            locomotion = PetLocomotion.None
            delay(2_400L)
            if (x.value != homePoint.x) {
                locomotion = if (homePoint.x < x.value) PetLocomotion.RunLeft else PetLocomotion.RunRight
                x.animateTo(homePoint.x, tween(duration))
                locomotion = PetLocomotion.None
            }
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

    fun persistAt(point: PetPoint) {
        onPlacementChanged(safeBounds.snapToEdge(point, petLayoutDirection, placement.edge))
    }

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
                .alpha(renderedAlpha)
                .graphicsLayer {
                    val scale = if (dragging) 1.10f else 1f
                    scaleX = scale
                    scaleY = scale
                }
                .pointerInput(pet.id, safeBounds, walkBounds, registeredObstacles) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
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
                            dragging = false
                            if (roamingEnabled) onRoamingEnabledChanged(false)
                            persistAt(dropped)
                            scope.launch {
                                x.snapTo(dropped.x)
                                y.snapTo(dropped.y)
                                draggedPoint = null
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
                        petLocomotion = locomotion,
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

/** Center bounds for standing on an existing element's top edge as an overlay. */
private fun Rect.toPetPerchBounds(
    radius: Float,
    outer: PetSafeBounds,
): PetSafeBounds? {
    val left = (this.left + radius).coerceIn(outer.left, outer.right)
    val right = (this.right - radius).coerceIn(outer.left, outer.right)
    // The interactive target stays entirely above the measured element. Any
    // transparent sprite padding remains inside this target and cannot steal
    // touches from the composer's top edge.
    val centerY = (top - radius).coerceIn(outer.top, outer.bottom)
    if (right < left) return null
    return PetSafeBounds(left, centerY, right, centerY)
}

private fun SphereState.floatingPetStateLabelRes(): Int = when (this) {
    SphereState.Idle -> R.string.floating_pet_state_idle
    SphereState.Thinking -> R.string.floating_pet_state_thinking
    SphereState.Streaming -> R.string.floating_pet_state_streaming
    SphereState.Listening -> R.string.floating_pet_state_listening
    SphereState.Speaking -> R.string.floating_pet_state_speaking
    SphereState.Error -> R.string.floating_pet_state_error
}
