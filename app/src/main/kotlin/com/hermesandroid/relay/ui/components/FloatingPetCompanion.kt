package com.hermesandroid.relay.ui.components

import android.os.SystemClock
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.hermesandroid.relay.data.MAX_PET_SIZE_SCALE
import com.hermesandroid.relay.data.MIN_PET_SIZE_SCALE
import com.hermesandroid.relay.data.PetBehaviorPreferences
import com.hermesandroid.relay.ui.components.avatar.AgentAvatar
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import com.hermesandroid.relay.ui.components.avatar.PetLocomotion
import com.hermesandroid.relay.ui.components.pet.LocalPetSafeAreaRegistry
import com.hermesandroid.relay.ui.components.pet.PetBubbleEntryMode
import com.hermesandroid.relay.ui.components.pet.PetLayoutDirection
import com.hermesandroid.relay.ui.components.pet.PetLogicalEdge
import com.hermesandroid.relay.ui.components.pet.PetFootprint
import com.hermesandroid.relay.ui.components.pet.PetMeasuredPerch
import com.hermesandroid.relay.ui.components.pet.PetMeasuredObstacle
import com.hermesandroid.relay.ui.components.pet.PetObstacle
import com.hermesandroid.relay.ui.components.pet.PetPlacement
import com.hermesandroid.relay.ui.components.pet.PetPoint
import com.hermesandroid.relay.ui.components.pet.PetRoamingRail
import com.hermesandroid.relay.ui.components.pet.PetRoute
import com.hermesandroid.relay.ui.components.pet.PetSafeBounds
import com.hermesandroid.relay.ui.components.pet.PetSettledChatHabitat
import com.hermesandroid.relay.ui.components.pet.PetSettledChatMode
import com.hermesandroid.relay.ui.components.pet.PetVisitReadiness
import com.hermesandroid.relay.ui.components.pet.PetVisitRequest
import com.hermesandroid.relay.ui.components.pet.choosePetRailTransfer
import com.hermesandroid.relay.ui.components.pet.expandObstaclesForPet
import com.hermesandroid.relay.ui.components.pet.findOverlayRoute
import com.hermesandroid.relay.ui.components.pet.planPetBubbleExcursion
import com.hermesandroid.relay.ui.components.pet.planSettledChatHabitat
import com.hermesandroid.relay.ui.components.pet.petPerchSegments
import com.hermesandroid.relay.ui.components.pet.projectIntoSafeBounds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val FLOATING_PET_COMPACT_HEIGHT_DP = 700
internal const val CHAT_PET_WALK_REGION = "chat-composer-perch"
internal const val CHAT_PET_MESSAGE_PERCH_PREFIX = "chat-message-perch:"
internal const val CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX = "${CHAT_PET_MESSAGE_PERCH_PREFIX}assistant:"
internal const val CHAT_PET_USER_MESSAGE_PERCH_PREFIX = "${CHAT_PET_MESSAGE_PERCH_PREFIX}user:"
private const val PET_ROAM_REPEAT_DELAY_MS = 4_800L
private const val PET_AMBIENT_HOP_HEIGHT_DP = 24
private const val PET_SCROLL_REACTION_LIFT_DP = 12
private const val PET_TURN_PAUSE_MS = 220L
private const val PET_WAVE_DURATION_MS = 1_200L
private const val PET_WALK_CYCLE_MS = 480
private const val PET_WALK_SPEED_DP_PER_SECOND = 44f
private const val PET_PATROL_CYCLES_BETWEEN_BUBBLE_VISITS = 2

internal enum class PetAmbientAction {
    Hop,
    Wave,
    Rest,
}

internal enum class PetBehaviorPriority {
    UserInteraction,
    AgentActivity,
    ResponseVisit,
    Roam,
    Idle,
}

internal fun shouldCompactFloatingPet(imeVisible: Boolean, screenHeightDp: Int): Boolean =
    imeVisible || screenHeightDp < FLOATING_PET_COMPACT_HEIGHT_DP

internal fun floatingPetVisualSizeDp(compact: Boolean): Int = if (compact) 50 else 60

internal data class FloatingPetDimensions(
    val visualSizeDp: Float,
    val targetSizeDp: Float,
)

/** One scale drives the art, pointer target, collision footprint, and routing. */
internal fun floatingPetDimensions(compact: Boolean, sizeScale: Float): FloatingPetDimensions {
    val safeScale = sizeScale.takeIf(Float::isFinite)
        ?.coerceIn(MIN_PET_SIZE_SCALE, MAX_PET_SIZE_SCALE) ?: 1f
    val baseVisual = floatingPetVisualSizeDp(compact).toFloat()
    val baseTarget = if (compact) 60f else 70f
    return FloatingPetDimensions(
        visualSizeDp = baseVisual * safeScale,
        targetSizeDp = maxOf(48f, baseTarget * safeScale),
    )
}

internal fun shouldPauseFloatingPet(
    alreadyPaused: Boolean,
    animationEnabled: Boolean,
): Boolean = alreadyPaused || !animationEnabled

internal fun floatingPetRoamDelayMs(
    hasMoved: Boolean,
    roamIntervalMs: Long = PET_ROAM_REPEAT_DELAY_MS,
): Long {
    require(roamIntervalMs > 0L) { "Roam interval must be positive." }
    return if (hasMoved) roamIntervalMs else 0L
}

internal fun petAmbientAction(step: Int): PetAmbientAction = when (step % 3) {
    0 -> PetAmbientAction.Hop
    1 -> PetAmbientAction.Wave
    else -> PetAmbientAction.Rest
}

internal fun shouldAttemptAmbientBubbleVisit(
    cyclesUntilVisit: Int,
    hasVisibleBubble: Boolean,
): Boolean = hasVisibleBubble && cyclesUntilVisit <= 0

internal fun shouldPaceSettledHabitat(mode: PetSettledChatMode): Boolean =
    mode == PetSettledChatMode.SidePocketPace || mode == PetSettledChatMode.BubbleTop

internal fun petVerticalLocomotion(fromY: Float, toY: Float): PetLocomotion =
    if (toY > fromY) PetLocomotion.Fall else PetLocomotion.Jump

internal fun petBehaviorPriority(
    userInteraction: Boolean,
    agentActivity: Boolean,
    responseVisitPending: Boolean,
    roamReady: Boolean,
): PetBehaviorPriority = when {
    userInteraction -> PetBehaviorPriority.UserInteraction
    agentActivity -> PetBehaviorPriority.AgentActivity
    responseVisitPending -> PetBehaviorPriority.ResponseVisit
    roamReady -> PetBehaviorPriority.Roam
    else -> PetBehaviorPriority.Idle
}

internal fun presentedPetLocomotion(
    dragging: Boolean,
    dropping: Boolean,
    agentState: SphereState,
    movement: PetLocomotion,
): PetLocomotion = when {
    dragging -> PetLocomotion.Held
    dropping -> PetLocomotion.Fall
    agentState != SphereState.Idle -> PetLocomotion.None
    else -> movement
}

internal fun petWalkDurationMs(distanceDp: Float): Int {
    if (!distanceDp.isFinite() || distanceDp <= 0f) return PET_WALK_CYCLE_MS
    val rawDuration = distanceDp / PET_WALK_SPEED_DP_PER_SECOND * 1_000f
    val cycles = (rawDuration / PET_WALK_CYCLE_MS).roundToInt().coerceAtLeast(1)
    return (cycles * PET_WALK_CYCLE_MS).coerceAtMost(PET_WALK_CYCLE_MS * 12)
}

internal fun petShadowScale(airborneProgress: Float): Float =
    1f - 0.42f * airborneProgress.coerceIn(0f, 1f)

internal fun petShadowAlpha(airborneProgress: Float): Float =
    0.20f - 0.12f * airborneProgress.coerceIn(0f, 1f)

internal fun shouldReleasePendingPetDrop(
    expectedPlacement: PetPlacement?,
    positionSettled: Boolean,
    animationFinished: Boolean,
    observedPlacement: PetPlacement,
): Boolean = expectedPlacement != null && positionSettled && animationFinished &&
    observedPlacement == expectedPlacement

internal fun shouldAnimatePendingPetDrop(
    expectedPlacement: PetPlacement?,
    positionSettled: Boolean,
    animationFinished: Boolean,
    observedPlacement: PetPlacement,
): Boolean = expectedPlacement != null && positionSettled && !animationFinished &&
    observedPlacement == expectedPlacement

internal fun shouldDockFloatingPet(
    roamingEnabled: Boolean,
    roamingAllowed: Boolean,
): Boolean = !roamingEnabled || !roamingAllowed

internal fun petRailSupportingPoint(
    rails: Iterable<PetRoamingRail>,
    point: PetPoint,
    verticalTolerancePx: Float = 2f,
): PetRoamingRail? = rails.firstOrNull { rail ->
    point.x in rail.bounds.left..rail.bounds.right &&
        abs(point.y - rail.bounds.top) <= verticalTolerancePx
}

/** Prefer falling to visible terrain below the pet; jump upward only as recovery. */
internal fun choosePetScrollLandingRail(
    rails: Iterable<PetRoamingRail>,
    point: PetPoint,
): PetRoamingRail? {
    val candidates = rails.toList()
    if (candidates.isEmpty()) return null
    val below = candidates.filter { it.bounds.top >= point.y - 1f }
    return (below.ifEmpty { candidates }).minByOrNull { rail ->
        rail.bounds.clamp(point).distanceSquaredTo(point)
    }
}

internal fun petScrollTrackingRails(
    roamingRails: Iterable<PetRoamingRail>,
    settledHabitat: PetSettledChatHabitat?,
): List<PetRoamingRail> = (roamingRails + listOfNotNull(settledHabitat?.rail))
    .distinctBy { it.key }

internal fun petScrollLandingRails(
    roamingRails: Iterable<PetRoamingRail>,
    settledHabitat: PetSettledChatHabitat?,
): List<PetRoamingRail> = petScrollTrackingRails(roamingRails, settledHabitat)
    .filterNot { rail -> rail.perchKey.startsWith(CHAT_PET_MESSAGE_PERCH_PREFIX) }

internal fun petPositionNeedsEscape(
    point: PetPoint,
    bounds: PetSafeBounds,
    uiObstacles: List<PetObstacle>,
    footprint: PetFootprint,
): Boolean {
    if (point.x !in bounds.left..bounds.right || point.y !in bounds.top..bounds.bottom) {
        return true
    }
    return expandObstaclesForPet(uiObstacles, footprint).any { obstacle ->
        obstacle.contains(point)
    }
}

private data class PendingPetDrop(
    val point: PetPoint,
    val expectedPlacement: PetPlacement,
    val positionSettled: Boolean = false,
    val animationFinished: Boolean = false,
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
    behaviorPreferences: PetBehaviorPreferences,
    sizeScale: Float = behaviorPreferences.sizeScale,
    surfaceScrolling: Boolean,
    compact: Boolean,
    animationEnabled: Boolean,
    appForeground: Boolean,
    route: String?,
    visitRequest: PetVisitRequest?,
    onVisitRequestConsumed: (String) -> Unit,
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
    var visitActive by remember(pet.id) { mutableStateOf(false) }
    var tapReactionNonce by remember(pet.id) { mutableStateOf(0) }
    var tapReactionActive by remember(pet.id) { mutableStateOf(false) }
    var locomotion by remember(pet.id) { mutableStateOf(PetLocomotion.None) }
    var positioned by remember(pet.id) { mutableStateOf(false) }
    var activeRailKey by remember(pet.id) { mutableStateOf<String?>(null) }
    var scrollingRailKey by remember(pet.id) { mutableStateOf<String?>(null) }
    var scrollSupportLost by remember(pet.id) { mutableStateOf(false) }
    var scrollRecoveryPending by remember(pet.id) { mutableStateOf(false) }
    var viewportWidth by remember { mutableStateOf(0) }
    var viewportHeight by remember { mutableStateOf(0) }
    val x = remember(pet.id) { Animatable(0f) }
    val y = remember(pet.id) { Animatable(0f) }
    val airborneProgress = remember(pet.id) { Animatable(0f) }
    val landingSquash = remember(pet.id) { Animatable(0f) }
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
    val dimensions = floatingPetDimensions(compact, sizeScale)
    val targetSize = dimensions.targetSizeDp.dp
    val visualSize = dimensions.visualSizeDp.dp
    val targetSizePx = with(density) { targetSize.toPx() }
    val heldLiftPx = with(density) { 6.dp.toPx() }
    val scrollReactionLiftPx = with(density) { PET_SCROLL_REACTION_LIFT_DP.dp.toPx() }
    val safeMarginPx = with(density) { 12.dp.toPx() }
    val perchClearancePx = with(density) { 6.dp.toPx() }
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
    val roamingRails = remember(
        safeAreaSnapshot,
        footprint,
        safeBounds,
        perchClearancePx,
        petLayoutDirection,
    ) {
        safeAreaSnapshot.perches.flatMap { perch ->
            // A visible assistant response is a real raised ledge, not merely
            // an edge marker. Entry and exit are still handled by the explicit
            // gutter excursion below, so the sprite never traverses content.
            val segments = petPerchSegments(
                perch = perch,
                obstacles = safeAreaSnapshot.obstacles,
                footprint = footprint,
                outer = safeBounds,
                minimumWidth = targetSizePx / 2f,
                verticalClearance = perchClearancePx,
            )
            segments.mapIndexed { index, bounds ->
                PetRoamingRail(
                    key = "${perch.key}:$index",
                    perchKey = perch.key,
                    bounds = bounds,
                )
            }
        }
    }
    val latestSurfaceScrolling by rememberUpdatedState(surfaceScrolling)
    val composerRails = remember(roamingRails) {
        roamingRails.filter { it.perchKey == CHAT_PET_WALK_REGION }
    }
    val settledMessagePerch = remember(safeAreaSnapshot.perches) {
        safeAreaSnapshot.perches.firstOrNull {
            it.key.startsWith(CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX) ||
                it.key.startsWith(CHAT_PET_USER_MESSAGE_PERCH_PREFIX)
        }
    }
    val settledHabitat = remember(
        settledMessagePerch,
        composerRails,
        safeAreaSnapshot.obstacles,
        safeAreaSnapshot.perches,
        footprint,
        safeBounds,
        petLayoutDirection,
        perchClearancePx,
    ) {
        settledMessagePerch?.let { bubble ->
            val startAligned = bubble.key.startsWith(CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX)
            val useLeftPocket = if (startAligned) {
                petLayoutDirection == PetLayoutDirection.Rtl
            } else {
                petLayoutDirection == PetLayoutDirection.Ltr
            }
            val occupiedSurfaceObstacles = safeAreaSnapshot.obstacles +
                safeAreaSnapshot.perches
                    .filter { perch ->
                        perch.key != bubble.key && perch.key != CHAT_PET_WALK_REGION
                    }
                    .map { perch ->
                        PetMeasuredObstacle("occupied:${perch.key}", perch.bounds, perch.routeScope)
                    }
            planSettledChatHabitat(
                bubble = bubble,
                composerRails = composerRails,
                obstacles = occupiedSurfaceObstacles,
                footprint = footprint,
                outer = safeBounds,
                useLeftPocket = useLeftPocket,
                verticalClearance = perchClearancePx,
            )
        }
    }
    val scrollTrackingRails = remember(roamingRails, settledHabitat) {
        petScrollTrackingRails(roamingRails, settledHabitat)
    }
    val scrollLandingRails = remember(roamingRails, settledHabitat) {
        petScrollLandingRails(roamingRails, settledHabitat)
    }
    val latestScrollLandingRails by rememberUpdatedState(scrollLandingRails)
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
    val homeRail = remember(roamingRails, manualHomePoint, settledHabitat) {
        val distanceToHome: (PetRoamingRail) -> Float = { rail ->
            rail.bounds.clamp(manualHomePoint).distanceSquaredTo(manualHomePoint)
        }
        settledHabitat?.rail
            ?: roamingRails.filter { it.perchKey == CHAT_PET_WALK_REGION }.minByOrNull(distanceToHome)
            ?: roamingRails.minByOrNull(distanceToHome)
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
        isScrolling = surfaceScrolling,
        dragging = dragging || pendingDrop != null || scrollRecoveryPending,
        menuExpanded = menuExpanded,
    )
    val behaviorPacing = behaviorPreferences.pacingWhenMotionAllowed(
        motionAllowed = animationEnabled && appForeground && accessibleMotion.osAnimations &&
            !accessibleMotion.touchExploration && !state.paused,
    )
    val behaviorPriority = petBehaviorPriority(
        userInteraction = dragging || pendingDrop != null || tapReactionActive,
        agentActivity = state.state != SphereState.Idle,
        responseVisitPending = visitRequest != null || visitActive,
        roamReady = canRoam,
    )
    val canPatrol = behaviorPriority == PetBehaviorPriority.Roam
    val visitTarget = remember(safeAreaSnapshot, visitRequest) {
        visitRequest?.let { request ->
            safeAreaSnapshot.visitTargets.firstOrNull { it.key == request.targetKey }
        }
    }

    suspend fun animateLanding() {
        landingSquash.snapTo(0f)
        landingSquash.animateTo(1f, tween(durationMillis = 90))
        landingSquash.animateTo(0f, tween(durationMillis = 150))
    }

    suspend fun animateHorizontalTo(destinationX: Float) {
        val distancePx = abs(destinationX - x.value)
        if (distancePx <= 1f) return
        locomotion = if (destinationX < x.value) PetLocomotion.WalkLeft else PetLocomotion.WalkRight
        val distanceDp = distancePx / density.density
        x.animateTo(destinationX, tween(durationMillis = petWalkDurationMs(distanceDp)))
        locomotion = PetLocomotion.None
    }

    suspend fun animateBallisticVerticalTo(destinationY: Float) {
        val startY = y.value
        val arcHeight = with(density) { PET_AMBIENT_HOP_HEIGHT_DP.dp.toPx() }
        val apexY = (minOf(startY, destinationY) - arcHeight).coerceAtLeast(safeBounds.top)

        // A small squash is the anticipation cue; the atlas itself stays in a
        // valid state until physical ascent starts.
        landingSquash.animateTo(0.55f, tween(durationMillis = 90))
        landingSquash.animateTo(0f, tween(durationMillis = 70))
        locomotion = PetLocomotion.Jump
        coroutineScope {
            launch { y.animateTo(apexY, tween(durationMillis = 230)) }
            launch { airborneProgress.animateTo(1f, tween(durationMillis = 230)) }
        }
        locomotion = PetLocomotion.Fall
        coroutineScope {
            launch { y.animateTo(destinationY, tween(durationMillis = 280)) }
            launch { airborneProgress.animateTo(0f, tween(durationMillis = 280)) }
        }
        locomotion = PetLocomotion.None
        animateLanding()
    }

    suspend fun animateBallisticTransferTo(destination: PetPoint) {
        val startX = x.value
        val startY = y.value
        val midpointX = (startX + destination.x) / 2f
        val arcHeight = with(density) { PET_AMBIENT_HOP_HEIGHT_DP.dp.toPx() }
        val apexY = (minOf(startY, destination.y) - arcHeight).coerceAtLeast(safeBounds.top)

        landingSquash.animateTo(0.55f, tween(durationMillis = 90))
        landingSquash.animateTo(0f, tween(durationMillis = 70))
        locomotion = PetLocomotion.Jump
        coroutineScope {
            launch { x.animateTo(midpointX, tween(durationMillis = 230)) }
            launch { y.animateTo(apexY, tween(durationMillis = 230)) }
            launch { airborneProgress.animateTo(1f, tween(durationMillis = 230)) }
        }
        locomotion = PetLocomotion.Fall
        coroutineScope {
            launch { x.animateTo(destination.x, tween(durationMillis = 280)) }
            launch { y.animateTo(destination.y, tween(durationMillis = 280)) }
            launch { airborneProgress.animateTo(0f, tween(durationMillis = 280)) }
        }
        locomotion = PetLocomotion.None
        animateLanding()
    }

    suspend fun animatePetRoute(routePlan: PetRoute) {
        val livePoint = PetPoint(x.value, y.value)
        if (routePlan.start.distanceSquaredTo(livePoint) > 1f) return
        routePlan.points.drop(1).forEach { waypoint ->
            val segmentStartY = y.value
            val movesHorizontally = abs(waypoint.x - x.value) > 1f
            val movesVertically = abs(waypoint.y - segmentStartY) > 1f
            if (movesVertically && !movesHorizontally) {
                animateBallisticVerticalTo(waypoint.y)
            } else if (!movesVertically && movesHorizontally) {
                animateHorizontalTo(waypoint.x)
            } else if (movesHorizontally) {
                // Router-generated diagonal detours have already been checked
                // against obstacles. Preserve their straight segment instead
                // of curving outside the validated corridor.
                val descending = waypoint.y > segmentStartY
                locomotion = petVerticalLocomotion(segmentStartY, waypoint.y)
                coroutineScope {
                    launch { x.animateTo(waypoint.x, tween(durationMillis = 460)) }
                    launch { y.animateTo(waypoint.y, tween(durationMillis = 460)) }
                    launch {
                        airborneProgress.animateTo(
                            if (descending) 0f else 1f,
                            tween(durationMillis = 460),
                        )
                    }
                }
                if (descending) animateLanding()
            }
        }
        locomotion = PetLocomotion.None
    }

    suspend fun animatePetRouteOrEscape(
        routePlan: PetRoute,
        uiObstacles: List<PetObstacle>,
    ): Boolean {
        val livePoint = PetPoint(x.value, y.value)
        if (routePlan.start.distanceSquaredTo(livePoint) > 1f) {
            if (!petPositionNeedsEscape(livePoint, safeBounds, uiObstacles, footprint)) {
                return false
            }
            // The router projected an already-invalid position to the nearest
            // safe edge. Animate that correction as a visible hop rather than
            // accepting a teleport or leaving roaming permanently stalled.
            animateBallisticTransferTo(routePlan.start)
        }
        animatePetRoute(routePlan)
        return true
    }

    suspend fun animateScrollLanding(destination: PetPoint) {
        if (destination.y < y.value - 1f) {
            animateBallisticTransferTo(destination)
            return
        }
        locomotion = PetLocomotion.Fall
        airborneProgress.snapTo(maxOf(airborneProgress.value, 0.45f))
        coroutineScope {
            launch { x.animateTo(destination.x, tween(durationMillis = 260)) }
            launch { y.animateTo(destination.y, tween(durationMillis = 260)) }
            launch { airborneProgress.animateTo(0f, tween(durationMillis = 260)) }
        }
        locomotion = PetLocomotion.None
        animateLanding()
    }

    suspend fun performBubbleExcursion(bubblePerch: PetMeasuredPerch): PetRoamingRail? {
        val planned = roamingRails.asSequence()
            .filter { it.perchKey == CHAT_PET_WALK_REGION }
            .mapNotNull { composerRail ->
                planPetBubbleExcursion(
                    bubble = bubblePerch,
                    composerRail = composerRail,
                    footprint = footprint,
                    outer = safeBounds,
                    uiObstacles = safeAreaSnapshot.obstacles.map { it.bounds },
                    useLeftGutter = if (
                        bubblePerch.key.startsWith(CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX)
                    ) {
                        petLayoutDirection == PetLayoutDirection.Rtl
                    } else {
                        petLayoutDirection == PetLayoutDirection.Ltr
                    },
                    verticalClearance = perchClearancePx,
                    minimumWalkWidth = targetSizePx / 2f,
                )?.let { excursion -> composerRail to excursion }
            }
            .minByOrNull { (_, excursion) ->
                excursion.composerApproach.distanceSquaredTo(PetPoint(x.value, y.value))
            }
            ?: return null
        val (composerRail, excursion) = planned
        val routeObstacles = safeAreaSnapshot.obstacles.map { it.bounds } + bubblePerch.bounds
        val routeToComposerApproach = findOverlayRoute(
            start = PetPoint(x.value, y.value),
            requestedDestination = excursion.composerApproach,
            bounds = safeBounds,
            uiObstacles = routeObstacles,
            footprint = footprint,
        ) ?: return null
        if (!animatePetRouteOrEscape(routeToComposerApproach, routeObstacles)) return null

        when (excursion.entryMode) {
            PetBubbleEntryMode.ClearGutter -> {
                animateBallisticVerticalTo(excursion.gutter.y)
                animateHorizontalTo(excursion.entry.x)
            }
            PetBubbleEntryMode.EdgeHop -> animateBallisticTransferTo(excursion.entry)
        }
        animateHorizontalTo(excursion.opposite.x)
        delay(PET_TURN_PAUSE_MS)
        locomotion = PetLocomotion.Wave
        delay(PET_WAVE_DURATION_MS)
        locomotion = PetLocomotion.None
        delay(PET_TURN_PAUSE_MS)
        animateHorizontalTo(excursion.entry.x)
        when (excursion.entryMode) {
            PetBubbleEntryMode.ClearGutter -> {
                animateHorizontalTo(excursion.gutter.x)
                animateBallisticVerticalTo(excursion.composerApproach.y)
            }
            PetBubbleEntryMode.EdgeHop -> animateBallisticTransferTo(excursion.composerApproach)
        }
        return composerRail
    }

    suspend fun enterBubbleTop(
        bubblePerch: PetMeasuredPerch,
        habitat: PetSettledChatHabitat,
    ): Boolean {
        val planned = composerRails.asSequence()
            .mapNotNull { composerRail ->
                planPetBubbleExcursion(
                    bubble = bubblePerch,
                    composerRail = composerRail,
                    footprint = footprint,
                    outer = safeBounds,
                    uiObstacles = safeAreaSnapshot.obstacles.map { it.bounds },
                    useLeftGutter = if (
                        bubblePerch.key.startsWith(CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX)
                    ) {
                        petLayoutDirection == PetLayoutDirection.Rtl
                    } else {
                        petLayoutDirection == PetLayoutDirection.Ltr
                    },
                    verticalClearance = perchClearancePx,
                    minimumWalkWidth = 0f,
                )?.let { excursion -> composerRail to excursion }
            }
            .minByOrNull { (_, excursion) ->
                excursion.composerApproach.distanceSquaredTo(PetPoint(x.value, y.value))
            }
            ?: return false
        val (_, excursion) = planned
        val routeObstacles = safeAreaSnapshot.obstacles.map { it.bounds } + bubblePerch.bounds
        val routeToApproach = findOverlayRoute(
            start = PetPoint(x.value, y.value),
            requestedDestination = excursion.composerApproach,
            bounds = safeBounds,
            uiObstacles = routeObstacles,
            footprint = footprint,
        ) ?: return false
        if (!animatePetRouteOrEscape(routeToApproach, routeObstacles)) return false
        when (excursion.entryMode) {
            PetBubbleEntryMode.ClearGutter -> {
                animateBallisticVerticalTo(excursion.gutter.y)
                animateHorizontalTo(excursion.entry.x)
            }
            PetBubbleEntryMode.EdgeHop -> animateBallisticTransferTo(excursion.entry)
        }
        animateHorizontalTo(
            x.value.coerceIn(habitat.rail.bounds.left, habitat.rail.bounds.right),
        )
        return true
    }

    LaunchedEffect(pet.id, homePoint) {
        if (!positioned) {
            x.snapTo(homePoint.x)
            y.snapTo(homePoint.y)
            positioned = true
        }
    }

    // A measured scrolling ledge moves under the app-level overlay. Ride that
    // ledge while it remains valid; if it leaves the viewport, retain the last
    // safe screen coordinate and show the falling row until scrolling settles.
    LaunchedEffect(surfaceScrolling, scrollTrackingRails, positioned, dragging, pendingDrop) {
        if (!surfaceScrolling || !positioned || dragging || pendingDrop != null) {
            return@LaunchedEffect
        }
        val current = PetPoint(x.value, y.value)
        val supporting = activeRailKey?.let { key ->
            scrollTrackingRails.firstOrNull { it.key == key }
        } ?: petRailSupportingPoint(scrollTrackingRails, current)
        if (scrollingRailKey == null) {
            scrollingRailKey = supporting?.key
            scrollRecoveryPending = supporting != null
        }
        val attached = scrollingRailKey?.let { key ->
            scrollTrackingRails.firstOrNull { it.key == key }
        }
        if (attached != null) {
            x.snapTo(x.value.coerceIn(attached.bounds.left, attached.bounds.right))
            val scrollY = if (
                animationEnabled && accessibleMotion.osAnimations &&
                !accessibleMotion.touchExploration
            ) {
                (attached.bounds.top - scrollReactionLiftPx).coerceAtLeast(safeBounds.top)
            } else {
                attached.bounds.top
            }
            y.snapTo(scrollY)
            activeRailKey = attached.key
            scrollSupportLost = false
            if (scrollY < attached.bounds.top) {
                locomotion = PetLocomotion.Jump
                airborneProgress.snapTo(0.35f)
            } else {
                locomotion = PetLocomotion.None
                airborneProgress.snapTo(0f)
            }
        } else if (scrollRecoveryPending) {
            activeRailKey = null
            scrollSupportLost = true
            locomotion = PetLocomotion.Fall
            airborneProgress.snapTo(maxOf(airborneProgress.value, 0.35f))
        }
    }

    // Once the gesture/fling settles, land on the attached ledge at its new
    // coordinate or fall to the nearest visible lower rail if support vanished.
    LaunchedEffect(
        surfaceScrolling,
        scrollRecoveryPending,
        positioned,
        dragging,
        pendingDrop,
    ) {
        if (
            surfaceScrolling || !scrollRecoveryPending || !positioned || dragging ||
            pendingDrop != null
        ) return@LaunchedEffect
        try {
            // Read current terrain without keying this effect to every measured
            // bounds update. Streaming bubbles and AnimatedVisibility may keep
            // publishing geometry while this landing is in flight.
            val rails = latestScrollLandingRails
            val current = PetPoint(x.value, y.value)
            val attached = scrollingRailKey?.let { key ->
                rails.firstOrNull { it.key == key }
            }
            val landingRail = attached ?: choosePetScrollLandingRail(rails, current)
            if (landingRail != null) {
                val destination = PetPoint(
                    x = x.value.coerceIn(landingRail.bounds.left, landingRail.bounds.right),
                    y = landingRail.bounds.top,
                )
                if (
                    animationEnabled && accessibleMotion.osAnimations &&
                    !accessibleMotion.touchExploration &&
                    (scrollSupportLost || current.distanceSquaredTo(destination) > 4f)
                ) {
                    animateScrollLanding(destination)
                } else {
                    x.snapTo(destination.x)
                    y.snapTo(destination.y)
                    locomotion = PetLocomotion.None
                    airborneProgress.snapTo(0f)
                }
                activeRailKey = landingRail.key
            } else {
                locomotion = PetLocomotion.None
                airborneProgress.snapTo(0f)
                activeRailKey = null
            }
        } finally {
            // A recovery request is a temporary gate, never durable state. If
            // layout, direct interaction, or route changes cancel the landing,
            // clear it so autonomous roaming cannot remain stranded. Preserve
            // it only when a new scroll has already started.
            if (!latestSurfaceScrolling) {
                withContext(NonCancellable) {
                    if (
                        locomotion == PetLocomotion.Jump ||
                        locomotion == PetLocomotion.Fall
                    ) {
                        locomotion = PetLocomotion.None
                    }
                    airborneProgress.snapTo(0f)
                    scrollingRailKey = null
                    scrollSupportLost = false
                    scrollRecoveryPending = false
                }
            }
        }
    }

    val shouldDock = shouldDockFloatingPet(roamingEnabled, roamingAllowed)
    LaunchedEffect(homePoint, dragging, shouldDock, pendingDrop) {
        if (positioned && !dragging && pendingDrop == null && shouldDock) {
            locomotion = PetLocomotion.None
            x.snapTo(homePoint.x)
            y.snapTo(homePoint.y)
        }
    }

    LaunchedEffect(pendingDrop, placement) {
        val pending = pendingDrop ?: return@LaunchedEffect
        if (
            shouldReleasePendingPetDrop(
                expectedPlacement = pending.expectedPlacement,
                positionSettled = pending.positionSettled,
                animationFinished = pending.animationFinished,
                observedPlacement = placement,
            )
        ) {
            draggedPoint = null
            pendingDrop = null
        }
    }

    LaunchedEffect(pendingDrop?.positionSettled, homePoint, registeredObstacles, placement) {
        val pending = pendingDrop ?: return@LaunchedEffect
        if (
            !shouldAnimatePendingPetDrop(
                expectedPlacement = pending.expectedPlacement,
                positionSettled = pending.positionSettled,
                animationFinished = pending.animationFinished,
                observedPlacement = placement,
            )
        ) return@LaunchedEffect

        // Releasing a held pet is a visible fall rather than a state snap. The
        // active manual or roaming destination is reached through the existing
        // obstacle router; the fall clip owns the descent, followed by a small
        // landing squash. Dragging never changes the roaming preference.
        locomotion = PetLocomotion.Fall
        airborneProgress.snapTo(0.45f)
        val route = findOverlayRoute(
            start = PetPoint(x.value, y.value),
            requestedDestination = homePoint,
            bounds = safeBounds,
            uiObstacles = safeAreaSnapshot.obstacles.map { it.bounds } +
                safeAreaSnapshot.perches.map { it.bounds },
            footprint = footprint,
        )
        if (route != null && route.start.distanceSquaredTo(PetPoint(x.value, y.value)) <= 1f) {
            route.points.drop(1).forEach { waypoint ->
                coroutineScope {
                    launch { x.animateTo(waypoint.x, tween(durationMillis = 180)) }
                    launch { y.animateTo(waypoint.y, tween(durationMillis = 180)) }
                    launch { airborneProgress.animateTo(0f, tween(durationMillis = 180)) }
                }
            }
        } else {
            x.snapTo(homePoint.x)
            y.snapTo(homePoint.y)
            airborneProgress.snapTo(0f)
        }
        animateLanding()
        locomotion = PetLocomotion.None
        pendingDrop = pendingDrop?.let { current ->
            if (current.point == pending.point && current.expectedPlacement == pending.expectedPlacement) {
                current.copy(animationFinished = true)
            } else {
                current
            }
        }
    }

    LaunchedEffect(tapReactionNonce, state.state, dragging, pendingDrop) {
        if (
            tapReactionNonce == 0 || state.state != SphereState.Idle || dragging || pendingDrop != null
        ) return@LaunchedEffect
        tapReactionActive = true
        try {
            locomotion = PetLocomotion.Wave
            delay(PET_WAVE_DURATION_MS)
        } finally {
            locomotion = PetLocomotion.None
            tapReactionActive = false
        }
    }

    LaunchedEffect(
        visitRequest,
        visitTarget,
        canRoam,
        positioned,
        safeAreaSnapshot.obstacles,
        footprint,
        safeBounds,
    ) {
        val request = visitRequest ?: return@LaunchedEffect
        val now = SystemClock.elapsedRealtime()
        when (request.readinessAt(now)) {
            PetVisitReadiness.Expired -> {
                onVisitRequestConsumed(request.assistantUiKey)
                return@LaunchedEffect
            }
            PetVisitReadiness.CoolingDown -> {
                delay(request.notBeforeElapsedMs - now)
            }
            PetVisitReadiness.Ready -> Unit
        }
        if (!canRoam || !positioned) return@LaunchedEffect
        val target = visitTarget
        if (target == null) {
            val remaining = request.expiresAtElapsedMs - SystemClock.elapsedRealtime()
            if (remaining >= 0L) delay(remaining + 1L)
            onVisitRequestConsumed(request.assistantUiKey)
            return@LaunchedEffect
        }
        visitActive = true
        try {
            x.stop()
            y.stop()
            val bubblePerchKey = request.targetKey.replace(
                oldValue = "chat-message:",
                newValue = CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX,
            )
            val bubblePerch = safeAreaSnapshot.perches.firstOrNull { it.key == bubblePerchKey }
                ?: run {
                    onVisitRequestConsumed(request.assistantUiKey)
                    return@LaunchedEffect
                }
            performBubbleExcursion(bubblePerch)
            onVisitRequestConsumed(request.assistantUiKey)
        } finally {
            locomotion = PetLocomotion.None
            visitActive = false
        }
    }

    LaunchedEffect(
        pet.id,
        canPatrol,
        roamingRails,
        settledHabitat,
        settledMessagePerch,
        homePoint,
        positioned,
        behaviorPacing,
    ) {
        if (!canPatrol || !positioned) return@LaunchedEffect
        val pacing = behaviorPacing ?: return@LaunchedEffect
        val bubblePerches = safeAreaSnapshot.perches.filter {
            it.key.startsWith(CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX)
        }
        val patrolRails = (roamingRails.filterNot {
            it.perchKey.startsWith(CHAT_PET_MESSAGE_PERCH_PREFIX)
        } + listOfNotNull(settledHabitat?.rail)).distinctBy { it.key }
        if (patrolRails.isEmpty()) return@LaunchedEffect

        fun railSupporting(point: PetPoint): PetRoamingRail? = patrolRails.firstOrNull { rail ->
            point.x in rail.bounds.left..rail.bounds.right && abs(point.y - rail.bounds.top) <= 1f
        }

        suspend fun jumpToRail(
            rail: PetRoamingRail,
            requestedX: Float = x.value,
            plannedRoute: PetRoute? = null,
        ): Boolean {
            val destinationX = requestedX.coerceIn(rail.bounds.left, rail.bounds.right)
            val routeObstacles = safeAreaSnapshot.obstacles.map { it.bounds } +
                if (
                    rail.key == settledHabitat?.rail?.key &&
                    settledHabitat.mode != PetSettledChatMode.BubbleTop
                ) {
                    listOfNotNull(settledMessagePerch?.bounds)
                } else {
                    emptyList()
                }
            val routePlan = plannedRoute ?: findOverlayRoute(
                start = PetPoint(x.value, y.value),
                requestedDestination = PetPoint(destinationX, rail.bounds.top),
                bounds = safeBounds,
                uiObstacles = routeObstacles,
                footprint = footprint,
            ) ?: return false
            if (!animatePetRouteOrEscape(routePlan, routeObstacles)) return false
            activeRailKey = rail.key
            return true
        }

        suspend fun moveToSettledHabitat(): Boolean {
            val habitat = settledHabitat ?: return false
            val bubble = settledMessagePerch
            return if (habitat.mode == PetSettledChatMode.BubbleTop && bubble != null) {
                enterBubbleTop(bubble, habitat)
            } else {
                jumpToRail(habitat.rail)
            }
        }

        // A route change or user drop replans from the live point. The nearest
        // measured ledge wins; the transfer uses the Petdex jump row.
        try {
            val supportedRail = railSupporting(PetPoint(x.value, y.value))
            var rail = settledHabitat?.rail
                ?: supportedRail
                ?: patrolRails.minByOrNull {
                    it.bounds.clamp(PetPoint(x.value, y.value)).distanceSquaredTo(PetPoint(x.value, y.value))
                }
                ?: return@LaunchedEffect
            activeRailKey = supportedRail?.key ?: activeRailKey
            if (supportedRail?.key != rail.key) {
                val moved = if (settledHabitat?.rail?.key == rail.key) {
                    moveToSettledHabitat()
                } else jumpToRail(rail)
                if (!moved) return@LaunchedEffect
                activeRailKey = rail.key
            }

            var hasMoved = false
            var ambientStep = 0
            var cyclesUntilBubbleVisit = 0
            var nextIdleReactionAt = SystemClock.elapsedRealtime() + pacing.idleReactionCadenceMs
            while (true) {
                val delayMs = floatingPetRoamDelayMs(hasMoved, pacing.roamIntervalMs)
                if (delayMs > 0L) delay(delayMs)
                val paceCurrentRail = settledHabitat?.takeIf { it.rail.key == rail.key }
                    ?.let { shouldPaceSettledHabitat(it.mode) }
                    ?: true
                val destinationX = if (!paceCurrentRail) {
                    x.value
                } else if (abs(x.value - rail.bounds.left) <= abs(x.value - rail.bounds.right)) {
                    rail.bounds.right
                } else {
                    rail.bounds.left
                }
                if (paceCurrentRail) {
                    val horizontalDistance = abs(destinationX - x.value)
                    if (horizontalDistance > 1f) {
                        animateHorizontalTo(destinationX)
                    } else {
                        locomotion = PetLocomotion.Wave
                        delay(PET_WAVE_DURATION_MS)
                        locomotion = PetLocomotion.None
                    }
                }
                hasMoved = true
                delay(2_400L)

                if (shouldAttemptAmbientBubbleVisit(cyclesUntilBubbleVisit, bubblePerches.isNotEmpty())) {
                    val bubble = bubblePerches.minByOrNull { perch ->
                        val rail = petPerchSegments(
                            perch = perch,
                            obstacles = safeAreaSnapshot.obstacles,
                            footprint = footprint,
                            outer = safeBounds,
                            minimumWidth = targetSizePx / 2f,
                            verticalClearance = perchClearancePx,
                        ).firstOrNull()
                        rail?.clamp(PetPoint(x.value, y.value))
                            ?.distanceSquaredTo(PetPoint(x.value, y.value)) ?: Float.MAX_VALUE
                    }
                    val returnRail = bubble?.let { performBubbleExcursion(it) }
                    if (returnRail != null) {
                        rail = if (settledHabitat != null && moveToSettledHabitat()) {
                            settledHabitat.rail
                        } else {
                            returnRail
                        }
                        activeRailKey = rail.key
                        cyclesUntilBubbleVisit = PET_PATROL_CYCLES_BETWEEN_BUBBLE_VISITS
                        continue
                    }
                }
                cyclesUntilBubbleVisit--

                // Different ledges retain Desktop's overlap rule. Android may
                // also hop between sibling segments when its registered-control
                // router proves an above-perch route around the obstacle.
                val transfer = if (settledHabitat != null) null else choosePetRailTransfer(
                    currentRail = rail,
                    current = PetPoint(x.value, y.value),
                    // Chat response bubbles are visited only by the explicit
                    // enter/cross/return/exit sequence. Ambient routing may
                    // never jump onto one from an arbitrary point.
                    rails = patrolRails,
                    bounds = safeBounds,
                    uiObstacles = safeAreaSnapshot.obstacles.map { it.bounds },
                    footprint = footprint,
                )
                if (transfer != null) {
                    val nextRail = transfer.rail
                    val hopX = transfer.destinationX
                    val walkedToTransfer = !transfer.siblingSegment && abs(x.value - hopX) > 1f
                    if (walkedToTransfer) {
                        animateHorizontalTo(hopX)
                    }
                    val plannedRoute = transfer.route.takeUnless { walkedToTransfer }
                    if (jumpToRail(nextRail, hopX, plannedRoute)) {
                        rail = nextRail
                        activeRailKey = rail.key
                    }
                } else if (SystemClock.elapsedRealtime() >= nextIdleReactionAt) {
                    when (petAmbientAction(ambientStep++)) {
                        PetAmbientAction.Hop -> {
                            val railY = y.value
                            animateBallisticVerticalTo(railY)
                        }
                        PetAmbientAction.Wave -> {
                            locomotion = PetLocomotion.Wave
                            delay(PET_WAVE_DURATION_MS)
                            locomotion = PetLocomotion.None
                        }
                        PetAmbientAction.Rest -> Unit
                    }
                    nextIdleReactionAt = SystemClock.elapsedRealtime() + pacing.idleReactionCadenceMs
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
                .alpha(if (positioned) 1f else 0f)
                .graphicsLayer {
                    val scale = 1f + heldProgress * 0.10f
                    scaleX = scale * (1f + landingSquash.value * 0.08f)
                    scaleY = scale * (1f - landingSquash.value * 0.10f)
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
                            locomotion = PetLocomotion.None
                        },
                        onDragEnd = {
                            val dropped = draggedPoint ?: PetPoint(x.value, y.value)
                            val updatedPlacement = safeBounds.snapToEdge(
                                dropped,
                                petLayoutDirection,
                                placement.edge,
                            )
                            pendingDrop = PendingPetDrop(dropped, updatedPlacement)
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
                                draggedPoint = null
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
                .clickable {
                    tapReactionNonce += 1
                    menuExpanded = true
                }
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
                    .graphicsLayer {
                        val shadowScale = petShadowScale(airborneProgress.value)
                        scaleX = shadowScale
                        scaleY = shadowScale
                        alpha = petShadowAlpha(airborneProgress.value)
                    }
                    .background(
                        color = androidx.compose.ui.graphics.Color.Black,
                        shape = CircleShape,
                    ),
            )
            key(pet.id) {
                pet.Render(
                    state = state.copy(
                        petLocomotion = presentedPetLocomotion(
                            dragging = dragging,
                            dropping = pendingDrop != null,
                            agentState = state.state,
                            movement = locomotion,
                        ),
                        paused = shouldPauseFloatingPet(
                            alreadyPaused = state.paused || !accessibleMotion.osAnimations ||
                                accessibleMotion.touchExploration,
                            animationEnabled = animationEnabled,
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
