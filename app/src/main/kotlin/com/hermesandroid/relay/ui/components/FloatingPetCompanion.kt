package com.hermesandroid.relay.ui.components

import android.os.SystemClock
import android.util.Log
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
import androidx.compose.runtime.CompositionLocalProvider
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
import com.hermesandroid.relay.data.PetTemperament
import com.hermesandroid.relay.ui.components.avatar.AgentAvatar
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState
import com.hermesandroid.relay.ui.components.avatar.LocalPetGroundOpaqueBottom
import com.hermesandroid.relay.ui.components.avatar.PetLocomotion
import com.hermesandroid.relay.ui.components.pet.LocalPetSafeAreaRegistry
import com.hermesandroid.relay.ui.components.pet.PetLayoutDirection
import com.hermesandroid.relay.ui.components.pet.PetLogicalEdge
import com.hermesandroid.relay.ui.components.pet.PetFootprint
import com.hermesandroid.relay.ui.components.pet.PetBubbleEdgeSide
import com.hermesandroid.relay.ui.components.pet.PetMeasuredPerch
import com.hermesandroid.relay.ui.components.pet.PetMeasuredObstacle
import com.hermesandroid.relay.ui.components.pet.PetObstacle
import com.hermesandroid.relay.ui.components.pet.PetPlacement
import com.hermesandroid.relay.ui.components.pet.PetPoint
import com.hermesandroid.relay.ui.components.pet.PetRailExplorationMode
import com.hermesandroid.relay.ui.components.pet.PetRailExplorationPlan
import com.hermesandroid.relay.ui.components.pet.PetRailJourneyStep
import com.hermesandroid.relay.ui.components.pet.PetRoamingRail
import com.hermesandroid.relay.ui.components.pet.PetRoute
import com.hermesandroid.relay.ui.components.pet.PetSafeBounds
import com.hermesandroid.relay.ui.components.pet.PetSettledChatHabitat
import com.hermesandroid.relay.ui.components.pet.PetSettledChatMode
import com.hermesandroid.relay.ui.components.pet.PetVisitReadiness
import com.hermesandroid.relay.ui.components.pet.PetVisitRequest
import com.hermesandroid.relay.ui.components.pet.choosePetRailTransfer
import com.hermesandroid.relay.ui.components.pet.expandObstaclesForPet
import com.hermesandroid.relay.ui.components.pet.findExactOverlayRoute
import com.hermesandroid.relay.ui.components.pet.findLocalPetEscapeRoute
import com.hermesandroid.relay.ui.components.pet.findOverlayRoute
import com.hermesandroid.relay.ui.components.pet.length
import com.hermesandroid.relay.ui.components.pet.petBubbleEntryRoute
import com.hermesandroid.relay.ui.components.pet.petBubbleExitRoute
import com.hermesandroid.relay.ui.components.pet.petBubbleEdgeTraversalObstacle
import com.hermesandroid.relay.ui.components.pet.petRouteFitsStepLimit
import com.hermesandroid.relay.ui.components.pet.planPetBubbleExcursion
import com.hermesandroid.relay.ui.components.pet.planPetBubbleEdgeFootholds
import com.hermesandroid.relay.ui.components.pet.planPetBubbleExploration
import com.hermesandroid.relay.ui.components.pet.planPetDebugRouteGraph
import com.hermesandroid.relay.ui.components.pet.planPetRailExploration
import com.hermesandroid.relay.ui.components.pet.planPetRailJourney
import com.hermesandroid.relay.ui.components.pet.planSettledChatHabitat
import com.hermesandroid.relay.ui.components.pet.petPerchSegments
import com.hermesandroid.relay.ui.components.pet.petPerchTouchdownRail
import com.hermesandroid.relay.ui.components.pet.petTopSupportedObstacle
import com.hermesandroid.relay.ui.components.pet.projectIntoSafeBounds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal const val FLOATING_PET_COMPACT_HEIGHT_DP = 700
internal const val FLOATING_PET_SUPPORTED_RAIL_CLEARANCE_DP = 0f
internal const val CHAT_PET_WALK_REGION = "chat-composer-perch"
internal const val CHAT_PET_MESSAGE_PERCH_PREFIX = "chat-message-perch:"
internal const val CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX = "${CHAT_PET_MESSAGE_PERCH_PREFIX}assistant:"
internal const val CHAT_PET_USER_MESSAGE_PERCH_PREFIX = "${CHAT_PET_MESSAGE_PERCH_PREFIX}user:"
internal const val CHAT_PET_STEP_MESSAGE_MARKER = "step:"
private const val PET_ROAM_REPEAT_DELAY_MS = 4_800L
private const val PET_AMBIENT_HOP_HEIGHT_DP = 24
private const val PET_SCROLL_REACTION_LIFT_DP = 12
private const val PET_TURN_PAUSE_MS = 220L
private const val PET_WAVE_DURATION_MS = 1_200L
private const val PET_WALK_CYCLE_MS = 480
private const val PET_WALK_SPEED_DP_PER_SECOND = 44f
private const val PET_PATROL_CYCLES_BETWEEN_BUBBLE_VISITS = 2
private const val PET_MAX_DIRECT_HOP_DP = 210f
private const val PET_MAX_CLEAR_MESSAGE_HOP_DP = 360f
private const val PET_AIRBORNE_SPEED_DP_PER_SECOND = 240f
private const val PET_AIRBORNE_MIN_DURATION_MS = 340
private const val PET_AIRBORNE_MAX_DURATION_MS = 1_400
private const val PET_EXPLORATION_WALK_DP = 56f
private const val PET_TOUCHDOWN_MIN_SUPPORT_RATIO = 0.35f
private const val PET_TOUCHDOWN_PAUSE_MS = 120L

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

internal fun petBubbleExplorationStops(temperament: PetTemperament): Int = when (temperament) {
    PetTemperament.Calm -> 1
    PetTemperament.Balanced -> 2
    PetTemperament.Playful -> 3
}

internal fun petTerrainGateLabel(
    roamingEnabled: Boolean,
    roamingAllowed: Boolean,
    hasRails: Boolean,
    surfaceScrolling: Boolean,
    dragging: Boolean,
    dropping: Boolean,
    menuExpanded: Boolean,
    animationEnabled: Boolean,
    appForeground: Boolean,
    osAnimations: Boolean,
    touchExploration: Boolean,
    paused: Boolean,
    agentState: SphereState,
    responseVisitPending: Boolean,
    canPatrol: Boolean,
): String = when {
    !roamingEnabled -> "roaming off"
    !roamingAllowed -> "route unsupported"
    !hasRails -> "no measured rails"
    surfaceScrolling -> "scrolling"
    dragging -> "dragging"
    dropping -> "dropping"
    menuExpanded -> "menu open"
    !animationEnabled -> "animations off"
    !appForeground -> "app background"
    !osAnimations -> "system animations off"
    touchExploration -> "touch exploration"
    paused -> "pet paused"
    agentState != SphereState.Idle -> "agent ${agentState.name.lowercase()}"
    responseVisitPending -> "response visit"
    canPatrol -> "roaming"
    else -> "idle"
}

internal fun isPetStepMessagePerchKey(key: String): Boolean =
    key.startsWith("$CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX$CHAT_PET_STEP_MESSAGE_MARKER") ||
        key.startsWith("$CHAT_PET_USER_MESSAGE_PERCH_PREFIX$CHAT_PET_STEP_MESSAGE_MARKER")

internal fun shouldPauseFloatingPet(
    alreadyPaused: Boolean,
    animationEnabled: Boolean,
): Boolean = alreadyPaused || !animationEnabled

/** A moving screen owns the gesture; an invisible/unpositioned target must never intercept it. */
internal fun floatingPetAcceptsPointerInput(
    positioned: Boolean,
    surfaceScrolling: Boolean,
): Boolean = positioned && !surfaceScrolling

/** The overlay's pre-measure zero size is not a valid coordinate space. */
internal fun shouldInitializeFloatingPet(
    positioned: Boolean,
    viewportWidth: Int,
    viewportHeight: Int,
    terrainReady: Boolean = true,
): Boolean = !positioned && viewportWidth > 0 && viewportHeight > 0 && terrainReady

/** Collision geometry must contain both the pointer target and rendered art. */
internal fun floatingPetCollisionSizePx(targetSizePx: Float, visualSizePx: Float): Float =
    maxOf(targetSizePx, visualSizePx)

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

/**
 * Keep the next terrain tour warm while the behavior director handles pacing.
 * Replanning is event-driven from measured terrain and supported waypoints; an
 * in-flight jump keeps its already validated route instead of changing course.
 */
internal fun planPetTerrainLookahead(
    current: PetPoint,
    activeRailKey: String?,
    rails: Iterable<PetRoamingRail>,
    bounds: PetSafeBounds,
    uiObstacles: Iterable<PetObstacle>,
    footprint: PetFootprint,
    maximumStepLength: Float,
    maxExtraStops: Int,
    mode: PetRailExplorationMode,
): PetRailExplorationPlan? {
    val candidates = rails.distinctBy { it.key }
    val origin = activeRailKey
        ?.let { key -> candidates.firstOrNull { it.key == key } }
        ?.takeIf { rail -> petRailSupportingPoint(listOf(rail), current) != null }
        ?: petRailSupportingPoint(candidates, current)
        ?: return null
    val supportedStart = PetPoint(
        x = current.x.coerceIn(origin.bounds.left, origin.bounds.right),
        y = origin.bounds.top,
    )
    return planPetRailExploration(
        originRail = origin,
        start = supportedStart,
        candidateRails = candidates,
        bounds = bounds,
        uiObstacles = uiObstacles,
        footprint = footprint,
        maximumStepLength = maximumStepLength,
        maxExtraStops = maxExtraStops,
        mode = mode,
    ).takeIf { it.continuation.isNotEmpty() }
}

internal fun petAirborneDurationMs(distanceDp: Float): Int {
    val safeDistance = abs(distanceDp).takeIf { it.isFinite() } ?: 0f
    return ((safeDistance / PET_AIRBORNE_SPEED_DP_PER_SECOND) * 1_000f)
        .roundToInt()
        .coerceIn(PET_AIRBORNE_MIN_DURATION_MS, PET_AIRBORNE_MAX_DURATION_MS)
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
    val landingPoint: PetPoint,
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
    debugTerrainOverlay: Boolean = false,
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
    onExitTerrainDebug: () -> Unit = {},
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
    var activeDebugRoute by remember(pet.id) { mutableStateOf<PetDebugActiveRoute?>(null) }
    var plannedDebugRoute by remember(pet.id) { mutableStateOf<PetDebugPlannedRoute?>(null) }
    var lookaheadDebugRoute by remember(pet.id) { mutableStateOf<PetDebugPlannedRoute?>(null) }
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
    val visualSizePx = with(density) { visualSize.toPx() }
    val collisionSizePx = floatingPetCollisionSizePx(targetSizePx, visualSizePx)
    val collisionSize = with(density) { collisionSizePx.toDp() }
    val heldLiftPx = with(density) { 6.dp.toPx() }
    val scrollReactionLiftPx = with(density) { PET_SCROLL_REACTION_LIFT_DP.dp.toPx() }
    val maximumDirectHopPx = with(density) { PET_MAX_DIRECT_HOP_DP.dp.toPx() }
    val maximumMessageHopPx = with(density) { PET_MAX_CLEAR_MESSAGE_HOP_DP.dp.toPx() }
    val safeMarginPx = with(density) { 12.dp.toPx() }
    // A supported collision box sits directly on its measured rail. Obstacle
    // clearance remains part of [footprint]; adding it again here makes the
    // visible pet hover above the surface.
    val perchClearancePx = with(density) { FLOATING_PET_SUPPORTED_RAIL_CLEARANCE_DP.dp.toPx() }
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
    val footprint = remember(collisionSizePx, safeMarginPx) {
        PetFootprint(collisionSizePx, collisionSizePx, safeMarginPx / 2f)
    }
    // Bubble-edge hops are visual traversal, not persistent placement. Keep
    // the larger accessible target for controls and viewport containment while
    // allowing the rendered sprite to use a genuinely clear message gutter.
    val visualFootprint = remember(visualSizePx, safeMarginPx) {
        PetFootprint(visualSizePx, visualSizePx, safeMarginPx / 2f)
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
    val visibleMessagePerches = remember(safeAreaSnapshot.perches) {
        safeAreaSnapshot.perches.filter { perch ->
            perch.key.startsWith(CHAT_PET_MESSAGE_PERCH_PREFIX)
        }
    }
    val messageTouchdownRails = remember(
        safeAreaSnapshot,
        roamingRails,
        footprint,
        safeBounds,
        targetSizePx,
        perchClearancePx,
    ) {
        val messageWalkPerchKeys = roamingRails.asSequence()
            .map(PetRoamingRail::perchKey)
            .filter { it.startsWith(CHAT_PET_MESSAGE_PERCH_PREFIX) }
            .toSet()
        val expandedRegisteredObstacles = expandObstaclesForPet(
            safeAreaSnapshot.obstacles.map { it.bounds },
            footprint,
        )
        safeAreaSnapshot.perches.mapNotNull { perch ->
            if (
                !perch.key.startsWith(CHAT_PET_MESSAGE_PERCH_PREFIX) ||
                perch.key in messageWalkPerchKeys
            ) return@mapNotNull null
            val bounds = petPerchTouchdownRail(
                perch = perch,
                footprint = footprint,
                outer = safeBounds,
                minimumSurfaceWidth = targetSizePx * PET_TOUCHDOWN_MIN_SUPPORT_RATIO,
                verticalClearance = perchClearancePx,
            ) ?: return@mapNotNull null
            val point = PetPoint(bounds.left, bounds.top)
            if (expandedRegisteredObstacles.any { obstacle -> obstacle.contains(point) }) {
                return@mapNotNull null
            }
            PetRoamingRail(
                key = "${perch.key}:touchdown",
                perchKey = perch.key,
                bounds = bounds,
            )
        }
    }
    val messageWalkRails = remember(roamingRails) {
        roamingRails.filter { rail -> rail.perchKey.startsWith(CHAT_PET_MESSAGE_PERCH_PREFIX) }
    }
    val messageJourneyObstacles = remember(
        safeAreaSnapshot.obstacles,
        visibleMessagePerches,
        footprint,
        visualFootprint,
        petLayoutDirection,
    ) {
        val radiusDelta = (footprint.horizontalRadius - visualFootprint.horizontalRadius)
            .coerceAtLeast(0f)
        // The message journey router expands these again by visualFootprint.
        // Pre-expanding controls preserves their full touch-target clearance;
        // message bodies intentionally retain only visible-sprite clearance.
        safeAreaSnapshot.obstacles.map { measured ->
            measured.bounds.expanded(radiusDelta, radiusDelta)
        } + visibleMessagePerches.map { perch ->
            val startAligned = perch.key.startsWith(CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX)
            val useLeftEdge = if (startAligned) {
                petLayoutDirection == PetLayoutDirection.Rtl
            } else {
                petLayoutDirection == PetLayoutDirection.Ltr
            }
            petBubbleEdgeTraversalObstacle(
                perch = perch,
                traversalFootprint = visualFootprint,
                useLeftEdge = useLeftEdge,
                openBothEdges = true,
            ).let(::petTopSupportedObstacle)
        }
    }
    val messageSideFootholdRails = remember(
        visibleMessagePerches,
        messageJourneyObstacles,
        visualFootprint,
        safeBounds,
        maximumMessageHopPx,
        petLayoutDirection,
    ) {
        visibleMessagePerches.flatMap { perch ->
            val startAligned = perch.key.startsWith(CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX)
            val preferLeft = if (startAligned) {
                petLayoutDirection == PetLayoutDirection.Rtl
            } else {
                petLayoutDirection == PetLayoutDirection.Ltr
            }
            val plan = planPetBubbleEdgeFootholds(
                bubble = perch,
                bounds = safeBounds,
                uiObstacles = messageJourneyObstacles,
                traversalFootprint = visualFootprint,
                maximumStepLength = maximumMessageHopPx,
                preferredSide = if (preferLeft) PetBubbleEdgeSide.Left else PetBubbleEdgeSide.Right,
                allowInsetEdge = true,
            ) ?: return@flatMap emptyList()
            plan.footholds.mapIndexed { index, point ->
                PetRoamingRail(
                    key = "${perch.key}:side-hop:$index",
                    perchKey = perch.key,
                    bounds = PetSafeBounds(point.x, point.y, point.x, point.y),
                )
            }
        }
    }
    val messageTraversalRails = remember(
        messageWalkRails,
        messageTouchdownRails,
        messageSideFootholdRails,
    ) {
        messageWalkRails + messageTouchdownRails + messageSideFootholdRails
    }
    val autonomousRouteObstacles = remember(safeAreaSnapshot.obstacles, visibleMessagePerches) {
        safeAreaSnapshot.obstacles.map { it.bounds } +
            visibleMessagePerches.map(::petTopSupportedObstacle)
    }
    val debugPossibleRoutes = remember(
        debugTerrainOverlay,
        route,
        roamingRails,
        composerRails,
        messageTraversalRails,
        autonomousRouteObstacles,
        messageJourneyObstacles,
        footprint,
        visualFootprint,
        safeBounds,
        maximumDirectHopPx,
        maximumMessageHopPx,
    ) {
        if (!debugTerrainOverlay) {
            emptyList()
        } else {
            val chatSurface = route == "chat"
            planPetDebugRouteGraph(
                rails = if (chatSurface) {
                    composerRails + messageTraversalRails
                } else {
                    roamingRails
                },
                bounds = safeBounds,
                uiObstacles = if (chatSurface) messageJourneyObstacles else autonomousRouteObstacles,
                footprint = if (chatSurface) visualFootprint else footprint,
                maximumRouteLength = if (chatSurface) maximumMessageHopPx else maximumDirectHopPx,
            )
        }
    }
    LaunchedEffect(
        debugTerrainOverlay,
        positioned,
        safeBounds,
        composerRails,
        messageWalkRails,
        messageTouchdownRails,
        messageSideFootholdRails,
        debugPossibleRoutes,
        surfaceScrolling,
        footprint,
        visualFootprint,
        maximumDirectHopPx,
        maximumMessageHopPx,
        petLayoutDirection,
        behaviorPreferences.temperament,
        dragging,
    ) {
        plannedDebugRoute = null
        if (debugTerrainOverlay && positioned) {
            Log.d(
                "PetTerrain",
                buildString {
                    append("bounds=").append(safeBounds)
                    append(" composer=").append(composerRails)
                    append(" walk=").append(messageWalkRails)
                    append(" perches=").append(visibleMessagePerches)
                    append(" obstacles=").append(messageJourneyObstacles)
                    append(" touchdown=").append(messageTouchdownRails)
                    append(" side=").append(messageSideFootholdRails)
                    append(" possible=").append(debugPossibleRoutes.size)
                    append(" footprint=").append(footprint)
                    append(" visual=").append(visualFootprint)
                    append(" directHop=").append(maximumDirectHopPx)
                    append(" messageHop=").append(maximumMessageHopPx)
                    append(" pet=").append(PetPoint(x.value, y.value))
                },
            )
            Log.d(
                "PetTerrain",
                "planner-config footprint=$footprint visual=$visualFootprint " +
                    "directHop=$maximumDirectHopPx messageHop=$maximumMessageHopPx",
            )
        }
    }
    val settledMessagePerch = remember(safeAreaSnapshot.perches) {
        safeAreaSnapshot.perches.firstOrNull {
            (
                it.key.startsWith(CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX) ||
                    it.key.startsWith(CHAT_PET_USER_MESSAGE_PERCH_PREFIX)
                ) && !isPetStepMessagePerchKey(it.key)
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
    val canRecoverSupport = roamingEnabled && roamingAllowed && positioned &&
        animationEnabled && appForeground && accessibleMotion.osAnimations &&
        !accessibleMotion.touchExploration && !state.paused && !surfaceScrolling &&
        !dragging && pendingDrop == null && !menuExpanded && !visitActive
    val debugGateLabel = petTerrainGateLabel(
        roamingEnabled = roamingEnabled,
        roamingAllowed = roamingAllowed,
        hasRails = roamingRails.isNotEmpty(),
        surfaceScrolling = surfaceScrolling,
        dragging = dragging,
        dropping = pendingDrop != null,
        menuExpanded = menuExpanded,
        animationEnabled = animationEnabled,
        appForeground = appForeground,
        osAnimations = accessibleMotion.osAnimations,
        touchExploration = accessibleMotion.touchExploration,
        paused = state.paused,
        agentState = state.state,
        responseVisitPending = visitRequest != null || visitActive,
        canPatrol = canPatrol,
    )
    LaunchedEffect(
        debugTerrainOverlay,
        positioned,
        canPatrol,
        route,
        activeRailKey,
        roamingRails,
        composerRails,
        messageTraversalRails,
        settledHabitat,
        autonomousRouteObstacles,
        messageJourneyObstacles,
        footprint,
        visualFootprint,
        safeBounds,
        maximumDirectHopPx,
        maximumMessageHopPx,
        behaviorPreferences.temperament,
        surfaceScrolling,
        dragging,
        pendingDrop,
    ) {
        if (
            !debugTerrainOverlay || !positioned || !canPatrol || surfaceScrolling ||
            dragging || pendingDrop != null
        ) {
            lookaheadDebugRoute = null
            return@LaunchedEffect
        }
        val chatSurface = route == "chat"
        val lookahead = planPetTerrainLookahead(
            current = PetPoint(x.value, y.value),
            activeRailKey = activeRailKey,
            rails = if (chatSurface) {
                (composerRails + messageTraversalRails + listOfNotNull(settledHabitat?.rail))
                    .distinctBy { it.key }
            } else {
                roamingRails
            },
            bounds = safeBounds,
            uiObstacles = if (chatSurface) messageJourneyObstacles else autonomousRouteObstacles,
            footprint = if (chatSurface) visualFootprint else footprint,
            maximumStepLength = if (chatSurface) maximumMessageHopPx else maximumDirectHopPx,
            maxExtraStops = petBubbleExplorationStops(behaviorPreferences.temperament),
            mode = if (chatSurface) {
                PetRailExplorationMode.Ascending
            } else {
                PetRailExplorationMode.AnyDirection
            },
        )
        lookaheadDebugRoute = lookahead?.let { plan ->
            petDebugPlannedRoute(
                targetLabel = petTerrainCompactPerchKey(plan.orderedRails.last().perchKey),
                routes = buildList {
                    plan.continuation.forEach { step ->
                        step.approach?.let(::add)
                        add(step.route)
                    }
                },
            )
        }
        if (lookaheadDebugRoute != null) {
            Log.d(
                "PetTerrain",
                "lookahead target=${lookaheadDebugRoute?.targetLabel} " +
                    "stops=${lookaheadDebugRoute?.stops?.size ?: 0}",
            )
        }
    }
    val visitTarget = remember(safeAreaSnapshot, visitRequest) {
        visitRequest?.let { request ->
            safeAreaSnapshot.visitTargets.firstOrNull { it.key == request.targetKey }
        }
    }

    suspend fun <T> withActiveDebugRoute(
        route: PetRoute,
        kind: PetDebugRouteKind = PetDebugRouteKind.Autonomous,
        block: suspend () -> T,
    ): T {
        if (!debugTerrainOverlay) return block()
        val previous = activeDebugRoute
        val active = PetDebugActiveRoute(route, kind)
        activeDebugRoute = active
        return try {
            block()
        } finally {
            if (activeDebugRoute == active) activeDebugRoute = previous
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

    suspend fun animateBallisticVerticalTo(
        destinationY: Float,
        debugKind: PetDebugRouteKind = PetDebugRouteKind.Autonomous,
    ) {
        val debugRoute = PetRoute(
            listOf(PetPoint(x.value, y.value), PetPoint(x.value, destinationY)),
        )
        return withActiveDebugRoute(debugRoute, debugKind) {
        val startY = y.value
        val arcHeight = with(density) { PET_AMBIENT_HOP_HEIGHT_DP.dp.toPx() }
        val apexY = (minOf(startY, destinationY) - arcHeight).coerceAtLeast(safeBounds.top)
        val totalDuration = petAirborneDurationMs(
            (abs(destinationY - startY) + arcHeight * 2f) / density.density,
        )
        val ascentDuration = (totalDuration * 0.45f).roundToInt()
        val descentDuration = totalDuration - ascentDuration

        // A small squash is the anticipation cue; the atlas itself stays in a
        // valid state until physical ascent starts.
        landingSquash.animateTo(0.55f, tween(durationMillis = 90))
        landingSquash.animateTo(0f, tween(durationMillis = 70))
        locomotion = PetLocomotion.Jump
        coroutineScope {
            launch { y.animateTo(apexY, tween(durationMillis = ascentDuration)) }
            launch { airborneProgress.animateTo(1f, tween(durationMillis = ascentDuration)) }
        }
        locomotion = PetLocomotion.Fall
        coroutineScope {
            launch { y.animateTo(destinationY, tween(durationMillis = descentDuration)) }
            launch { airborneProgress.animateTo(0f, tween(durationMillis = descentDuration)) }
        }
        locomotion = PetLocomotion.None
        animateLanding()
    }

    }

    suspend fun animatePetRoute(
        routePlan: PetRoute,
        debugKind: PetDebugRouteKind = PetDebugRouteKind.Autonomous,
    ) {
        return withActiveDebugRoute(routePlan, debugKind) {
        val livePoint = PetPoint(x.value, y.value)
        if (routePlan.start.distanceSquaredTo(livePoint) > 1f) return@withActiveDebugRoute
        routePlan.points.drop(1).forEach { waypoint ->
            val segmentStartY = y.value
            val movesHorizontally = abs(waypoint.x - x.value) > 1f
            val movesVertically = abs(waypoint.y - segmentStartY) > 1f
            if (movesVertically && !movesHorizontally) {
                val durationMillis = petAirborneDurationMs(
                    abs(waypoint.y - segmentStartY) / density.density,
                )
                val descending = waypoint.y > segmentStartY
                locomotion = if (descending) PetLocomotion.Fall else PetLocomotion.Jump
                coroutineScope {
                    launch { y.animateTo(waypoint.y, tween(durationMillis = durationMillis)) }
                    launch {
                        airborneProgress.animateTo(
                            if (descending) 0f else 1f,
                            tween(durationMillis = durationMillis),
                        )
                    }
                }
                if (descending) animateLanding()
            } else if (!movesVertically && movesHorizontally) {
                animateHorizontalTo(waypoint.x)
            } else if (movesHorizontally) {
                // Router-generated diagonal detours have already been checked
                // against obstacles. Preserve their straight segment instead
                // of curving outside the validated corridor.
                val descending = waypoint.y > segmentStartY
                val segmentDistanceDp = sqrt(
                    (waypoint.x - x.value) * (waypoint.x - x.value) +
                        (waypoint.y - segmentStartY) * (waypoint.y - segmentStartY),
                ) / density.density
                val durationMillis = petAirborneDurationMs(segmentDistanceDp)
                locomotion = petVerticalLocomotion(segmentStartY, waypoint.y)
                coroutineScope {
                    launch { x.animateTo(waypoint.x, tween(durationMillis = durationMillis)) }
                    launch { y.animateTo(waypoint.y, tween(durationMillis = durationMillis)) }
                    launch {
                        airborneProgress.animateTo(
                            if (descending) 0f else 1f,
                            tween(durationMillis = durationMillis),
                        )
                    }
                }
                if (descending) animateLanding()
            }
        }
        locomotion = PetLocomotion.None
        }
    }

    suspend fun animateAirborneRoute(
        routePlan: PetRoute,
        debugKind: PetDebugRouteKind = PetDebugRouteKind.Autonomous,
    ): Boolean {
        return withActiveDebugRoute(routePlan, debugKind) {
        val livePoint = PetPoint(x.value, y.value)
        if (routePlan.start.distanceSquaredTo(livePoint) > 1f) {
            return@withActiveDebugRoute false
        }
        landingSquash.animateTo(0.55f, tween(durationMillis = 90))
        landingSquash.animateTo(0f, tween(durationMillis = 70))
        val overallAscending = routePlan.destination.y < routePlan.start.y
        routePlan.points.drop(1).forEachIndexed { index, waypoint ->
            val startX = x.value
            val startY = y.value
            val dx = waypoint.x - startX
            val dy = waypoint.y - startY
            val distanceDp = sqrt(dx * dx + dy * dy) / density.density
            val durationMillis = petAirborneDurationMs(distanceDp)
            val descending = dy > 1f || (abs(dy) <= 1f && !overallAscending)
            val finalSegment = index == routePlan.points.lastIndex - 1
            locomotion = if (descending) PetLocomotion.Fall else PetLocomotion.Jump
            coroutineScope {
                launch { x.animateTo(waypoint.x, tween(durationMillis = durationMillis)) }
                launch { y.animateTo(waypoint.y, tween(durationMillis = durationMillis)) }
                launch {
                    airborneProgress.animateTo(
                        if (finalSegment && descending) 0f else 1f,
                        tween(durationMillis = durationMillis),
                    )
                }
            }
        }
        if (airborneProgress.value > 0f) {
            locomotion = PetLocomotion.Fall
            airborneProgress.animateTo(0f, tween(durationMillis = 110))
        }
        locomotion = PetLocomotion.None
        animateLanding()
        true
        }
    }

    suspend fun animateRailJourneyStep(
        step: PetRailJourneyStep,
        reverse: Boolean = false,
    ): Boolean {
        if (!reverse) {
            step.approach?.let { approach ->
                val livePoint = PetPoint(x.value, y.value)
                if (approach.start.distanceSquaredTo(livePoint) > 1f) return false
                animateHorizontalTo(approach.destination.x)
            }
            return animateAirborneRoute(step.route)
        }

        val reverseRoute = PetRoute(step.route.points.asReversed())
        if (!animateAirborneRoute(reverseRoute)) return false
        step.approach?.let { approach ->
            animateHorizontalTo(approach.start.x)
        }
        return true
    }

    /**
     * Execute the selected surface tour and retrace the exact validated legs.
     * Horizontal inspection uses the mirrored Petdex walk rows; each vertical
     * transfer continues through the shared jump/fall animator above.
     */
    suspend fun animatePlannedRailExploration(
        exploration: PetRailExplorationPlan,
    ): Boolean {
        val inspectionDistancePx = with(density) { PET_EXPLORATION_WALK_DP.dp.toPx() }
        exploration.continuation.forEach { step ->
            if (!animateRailJourneyStep(step)) return false
            activeRailKey = step.rail.key
            val landingX = step.route.destination.x
            val leftRoom = landingX - step.rail.bounds.left
            val rightRoom = step.rail.bounds.right - landingX
            val inspectionX = if (rightRoom >= leftRoom) {
                landingX + minOf(rightRoom, inspectionDistancePx)
            } else {
                landingX - minOf(leftRoom, inspectionDistancePx)
            }
            if (step.rail.bounds.width > 0f) {
                animateHorizontalTo(inspectionX)
                delay(PET_TURN_PAUSE_MS)
                animateHorizontalTo(landingX)
            } else {
                delay(PET_TOUCHDOWN_PAUSE_MS)
            }
        }
        exploration.continuation.indices.reversed().forEach { index ->
            if (!animateRailJourneyStep(exploration.continuation[index], reverse = true)) {
                return false
            }
            activeRailKey = exploration.orderedRails[index].key
        }
        return true
    }

    suspend fun animateExactPetRoute(
        routePlan: PetRoute,
        debugKind: PetDebugRouteKind = PetDebugRouteKind.Autonomous,
    ): Boolean {
        val livePoint = PetPoint(x.value, y.value)
        if (routePlan.start.distanceSquaredTo(livePoint) > 1f) return false
        if (!petRouteFitsStepLimit(routePlan, maximumDirectHopPx)) return false
        animatePetRoute(routePlan, debugKind)
        return true
    }

    suspend fun animateScrollLanding(destination: PetPoint): Boolean {
        val start = PetPoint(x.value, y.value)
        val route = findExactOverlayRoute(
            start = start,
            requestedDestination = destination,
            bounds = safeBounds,
            uiObstacles = autonomousRouteObstacles,
            footprint = footprint,
        ) ?: run {
            val escape = findLocalPetEscapeRoute(
                start = start,
                bounds = safeBounds,
                uiObstacles = autonomousRouteObstacles,
                footprint = footprint,
                maximumLength = maximumDirectHopPx,
            ) ?: return false
            animatePetRoute(escape, PetDebugRouteKind.Recovery)
            return false
        }
        return animateExactPetRoute(route, PetDebugRouteKind.Recovery)
    }

    suspend fun performFootholdBubbleExcursion(
        bubblePerch: PetMeasuredPerch,
    ): PetRoamingRail? {
        val targetRail = messageWalkRails
            .filter { it.perchKey == bubblePerch.key }
            .maxByOrNull { it.bounds.width }
            ?: run {
                if (debugTerrainOverlay) {
                    Log.d("PetTerrain", "excursion-deferred target=${bubblePerch.key} no-target-rail")
                }
                return null
            }
        val candidatePlans = mutableListOf<Triple<PetRoamingRail, PetRoute, List<PetRailJourneyStep>>>()
        val livePoint = PetPoint(x.value, y.value)
        val supportedHabitatRail = settledHabitat?.rail?.takeIf { rail ->
            livePoint.x in rail.bounds.left..rail.bounds.right &&
                abs(livePoint.y - rail.bounds.top) <= 1f
        }
        val journeyOrigins = (listOfNotNull(supportedHabitatRail) + composerRails)
            .distinctBy(PetRoamingRail::key)
        journeyOrigins.forEach { originRail ->
            val approach = originRail.bounds.clamp(livePoint)
            val routeToApproach = if (originRail.key == supportedHabitatRail?.key) {
                PetRoute(listOf(livePoint))
            } else {
                findExactOverlayRoute(
                    start = livePoint,
                    requestedDestination = approach,
                    bounds = safeBounds,
                    uiObstacles = messageJourneyObstacles,
                    footprint = visualFootprint,
                )
            }
            if (routeToApproach == null) {
                if (debugTerrainOverlay) {
                    Log.d("PetTerrain", "foothold-reject rail=${originRail.key} no-approach-route")
                }
                return@forEach
            }
            if (!petRouteFitsStepLimit(routeToApproach, maximumDirectHopPx)) {
                if (debugTerrainOverlay) {
                    Log.d(
                        "PetTerrain",
                        "foothold-reject rail=${originRail.key} approach-too-long " +
                            "length=${routeToApproach.length}",
                    )
                }
                return@forEach
            }
            val ascent = planPetRailJourney(
                startRail = originRail,
                start = approach,
                targetRail = targetRail,
                rails = composerRails + messageTraversalRails,
                bounds = safeBounds,
                uiObstacles = messageJourneyObstacles,
                footprint = visualFootprint,
                maximumStepLength = maximumMessageHopPx,
            )
            if (ascent == null || ascent.isEmpty()) {
                if (debugTerrainOverlay) {
                    Log.d("PetTerrain", "foothold-reject rail=${originRail.key} no-ascent-chain")
                }
                return@forEach
            }
            candidatePlans += Triple(originRail, routeToApproach, ascent)
        }
        val plan = candidatePlans.minByOrNull { (_, routeToApproach, ascent) ->
            routeToApproach.length + ascent.sumOf { it.route.length.toDouble() }.toFloat()
        } ?: run {
            if (debugTerrainOverlay) {
                Log.d("PetTerrain", "excursion-deferred target=${bubblePerch.key} no-foothold-plan")
            }
            return null
        }

        val (originRail, routeToApproach, ascent) = plan
        val landingPoint = ascent.last().route.destination
        val exploration = planPetRailExploration(
            originRail = targetRail,
            start = landingPoint,
            candidateRails = messageTraversalRails,
            bounds = safeBounds,
            uiObstacles = messageJourneyObstacles,
            footprint = visualFootprint,
            maximumStepLength = maximumMessageHopPx,
            maxExtraStops = petBubbleExplorationStops(behaviorPreferences.temperament),
        )
        // The descent is the already validated ascent in reverse. Nothing
        // moves until the complete round trip exists.
        if (debugTerrainOverlay) {
            plannedDebugRoute = petDebugPlannedRoute(
                targetLabel = petTerrainCompactPerchKey(bubblePerch.key),
                routes = buildList {
                    add(routeToApproach)
                    ascent.forEach { step ->
                        step.approach?.let(::add)
                        add(step.route)
                    }
                    exploration.continuation.forEach { step ->
                        step.approach?.let(::add)
                        add(step.route)
                    }
                },
            )
        }
        if (routeToApproach.points.size > 1 && !animateExactPetRoute(routeToApproach)) return null
        ascent.forEach { step ->
            if (!animateRailJourneyStep(step)) return null
            activeRailKey = step.rail.key
        }

        val landingX = x.value
        val oppositeX = if (
            abs(landingX - targetRail.bounds.left) <=
            abs(landingX - targetRail.bounds.right)
        ) {
            targetRail.bounds.right
        } else {
            targetRail.bounds.left
        }
        animateHorizontalTo(oppositeX)
        delay(PET_TURN_PAUSE_MS)
        locomotion = PetLocomotion.Wave
        delay(PET_WAVE_DURATION_MS)
        locomotion = PetLocomotion.None
        animateHorizontalTo(landingX)

        if (!animatePlannedRailExploration(exploration)) return null
        animateHorizontalTo(landingX)

        ascent.indices.reversed().forEach { index ->
            if (!animateRailJourneyStep(ascent[index], reverse = true)) return null
            activeRailKey = if (index == 0) originRail.key else ascent[index - 1].rail.key
        }
        return originRail
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
                    uiObstacles = autonomousRouteObstacles,
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
            ?: run {
                if (debugTerrainOverlay) {
                    Log.d("PetTerrain", "excursion-fallback target=${bubblePerch.key} no-direct-plan")
                }
                return performFootholdBubbleExcursion(bubblePerch)
            }
        val (composerRail, excursion) = planned
        val targetRail = messageWalkRails
            .filter { it.perchKey == bubblePerch.key }
            .maxByOrNull { it.bounds.width }
        val directEntryRoute = petBubbleEntryRoute(excursion)
        val directExitRoute = petBubbleExitRoute(excursion)
        val usesTerrainJourney = directEntryRoute.length > maximumDirectHopPx
        if (usesTerrainJourney && targetRail == null) {
            if (debugTerrainOverlay) {
                Log.d("PetTerrain", "excursion-fallback target=${bubblePerch.key} no-direct-target")
            }
            return performFootholdBubbleExcursion(bubblePerch)
        }
        val journeyObstacles = messageJourneyObstacles
        val terrainAscent = if (usesTerrainJourney) {
            planPetRailJourney(
                startRail = composerRail,
                start = excursion.composerApproach,
                targetRail = requireNotNull(targetRail),
                rails = messageTraversalRails,
                bounds = safeBounds,
                uiObstacles = journeyObstacles,
                footprint = visualFootprint,
                maximumStepLength = maximumMessageHopPx,
            ) ?: run {
                if (debugTerrainOverlay) {
                    Log.d("PetTerrain", "excursion-fallback target=${bubblePerch.key} no-direct-ascent")
                }
                return performFootholdBubbleExcursion(bubblePerch)
            }
        } else {
            emptyList()
        }
        val routeToComposerApproach = findExactOverlayRoute(
            start = PetPoint(x.value, y.value),
            requestedDestination = excursion.composerApproach,
            bounds = safeBounds,
            uiObstacles = autonomousRouteObstacles,
            footprint = footprint,
        ) ?: run {
            if (debugTerrainOverlay) {
                Log.d("PetTerrain", "excursion-fallback target=${bubblePerch.key} no-composer-approach")
            }
            return performFootholdBubbleExcursion(bubblePerch)
        }
        if (!petRouteFitsStepLimit(routeToComposerApproach, maximumDirectHopPx)) {
            if (debugTerrainOverlay) {
                Log.d("PetTerrain", "excursion-fallback target=${bubblePerch.key} composer-approach-too-long")
            }
            return performFootholdBubbleExcursion(bubblePerch)
        }
        if (debugTerrainOverlay) {
            plannedDebugRoute = petDebugPlannedRoute(
                targetLabel = petTerrainCompactPerchKey(bubblePerch.key),
                routes = buildList {
                    add(routeToComposerApproach)
                    if (usesTerrainJourney) {
                        terrainAscent.forEach { step ->
                            step.approach?.let(::add)
                            add(step.route)
                        }
                    } else {
                        add(directEntryRoute)
                    }
                },
            )
        }
        if (!animateExactPetRoute(routeToComposerApproach)) return null

        if (usesTerrainJourney) {
            terrainAscent.forEach { step ->
                if (!animateRailJourneyStep(step)) return null
                activeRailKey = step.rail.key
            }
        } else {
            if (!animateAirborneRoute(directEntryRoute)) return null
            activeRailKey = targetRail?.key
        }

        val greetedRail = targetRail
        val firstEdge = greetedRail?.let { rail ->
            if (abs(x.value - rail.bounds.left) <= abs(x.value - rail.bounds.right)) {
                rail.bounds.left
            } else {
                rail.bounds.right
            }
        } ?: excursion.entry.x
        val oppositeEdge = greetedRail?.let { rail ->
            if (firstEdge == rail.bounds.left) rail.bounds.right else rail.bounds.left
        } ?: excursion.opposite.x
        animateHorizontalTo(firstEdge)
        animateHorizontalTo(oppositeEdge)
        delay(PET_TURN_PAUSE_MS)
        locomotion = PetLocomotion.Wave
        delay(PET_WAVE_DURATION_MS)
        locomotion = PetLocomotion.None
        delay(PET_TURN_PAUSE_MS)
        animateHorizontalTo(firstEdge)

        if (greetedRail != null) {
            val exploration = planPetBubbleExploration(
                newestRail = greetedRail,
                start = PetPoint(x.value, y.value),
                visibleMessageRails = messageTraversalRails,
                bounds = safeBounds,
                uiObstacles = journeyObstacles,
                footprint = visualFootprint,
                maximumStepLength = maximumMessageHopPx,
                maxExtraStops = petBubbleExplorationStops(behaviorPreferences.temperament),
            )
            if (debugTerrainOverlay && exploration.continuation.isNotEmpty()) {
                val existing = plannedDebugRoute
                plannedDebugRoute = petDebugPlannedRoute(
                    targetLabel = existing?.targetLabel ?: petTerrainCompactPerchKey(bubblePerch.key),
                    routes = buildList {
                        addAll(existing?.outboundRoutes.orEmpty())
                        exploration.continuation.forEach { step ->
                            step.approach?.let(::add)
                            add(step.route)
                        }
                    },
                )
            }
            if (!animatePlannedRailExploration(exploration)) return null
            animateHorizontalTo(firstEdge)
        }

        if (usesTerrainJourney) {
            val ascentLandingX = terrainAscent.lastOrNull()?.route?.destination?.x
                ?: return null
            animateHorizontalTo(ascentLandingX)
            terrainAscent.indices.reversed().forEach { index ->
                if (!animateRailJourneyStep(terrainAscent[index], reverse = true)) return null
                activeRailKey = if (index == 0) {
                    composerRail.key
                } else {
                    terrainAscent[index - 1].rail.key
                }
            }
        } else {
            animateHorizontalTo(excursion.entry.x)
            if (!animateAirborneRoute(directExitRoute)) return null
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
                    uiObstacles = autonomousRouteObstacles,
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
        val entryRoute = petBubbleEntryRoute(excursion)
        if (entryRoute.length > maximumDirectHopPx) return false
        val routeToApproach = findExactOverlayRoute(
            start = PetPoint(x.value, y.value),
            requestedDestination = excursion.composerApproach,
            bounds = safeBounds,
            uiObstacles = autonomousRouteObstacles,
            footprint = footprint,
        ) ?: return false
        if (!animateExactPetRoute(routeToApproach)) return false
        if (!animateAirborneRoute(entryRoute)) return false
        animateHorizontalTo(
            x.value.coerceIn(habitat.rail.bounds.left, habitat.rail.bounds.right),
        )
        return true
    }

    // Chat roaming owns a measured composer rail. Publishing before that rail
    // exists lets initialization race route registration and can strand the
    // pet on transient fallback geometry until direct manipulation.
    val initialTerrainReady = route != "chat" || !roamingEnabled || !roamingAllowed ||
        composerRails.isNotEmpty()
    LaunchedEffect(
        pet.id,
        homePoint,
        viewportWidth,
        viewportHeight,
        initialTerrainReady,
    ) {
        if (
            shouldInitializeFloatingPet(
                positioned = positioned,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                terrainReady = initialTerrainReady,
            )
        ) {
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
            scrollTrackingRails.firstOrNull { rail ->
                rail.key == key && petRailSupportingPoint(listOf(rail), current) != null
            }
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
                    if (!animateScrollLanding(destination)) {
                        activeRailKey = null
                        return@LaunchedEffect
                    }
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

    LaunchedEffect(pendingDrop?.positionSettled, placement) {
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
        val route = PetRoute(listOf(PetPoint(x.value, y.value), pending.landingPoint))
        val durationMillis = petAirborneDurationMs(
            with(density) { route.length.toDp().value },
        )
        withActiveDebugRoute(route, PetDebugRouteKind.DirectManipulation) {
            coroutineScope {
                launch { x.animateTo(pending.landingPoint.x, tween(durationMillis)) }
                launch { y.animateTo(pending.landingPoint.y, tween(durationMillis)) }
                launch { airborneProgress.animateTo(0f, tween(durationMillis)) }
            }
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
            if (debugTerrainOverlay) plannedDebugRoute = null
            locomotion = PetLocomotion.None
            visitActive = false
        }
    }

    LaunchedEffect(
        pet.id,
        route,
        canPatrol,
        canRecoverSupport,
        roamingRails,
        autonomousRouteObstacles,
        messageTraversalRails,
        messageJourneyObstacles,
        visualFootprint,
        settledHabitat,
        settledMessagePerch,
        homePoint,
        positioned,
        behaviorPacing,
    ) {
        if ((!canPatrol && !canRecoverSupport) || !positioned) return@LaunchedEffect
        val pacing = behaviorPacing ?: return@LaunchedEffect
        val bubblePerches = safeAreaSnapshot.perches.filter {
            it.key.startsWith(CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX) &&
                !isPetStepMessagePerchKey(it.key)
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
            val routePlan = plannedRoute ?: findExactOverlayRoute(
                start = PetPoint(x.value, y.value),
                requestedDestination = PetPoint(destinationX, rail.bounds.top),
                bounds = safeBounds,
                uiObstacles = autonomousRouteObstacles,
                footprint = footprint,
            ) ?: return false
            if (!animateExactPetRoute(routePlan)) return false
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

        suspend fun recoverToComposerViaMessageFootholds(): PetRoamingRail? {
            val live = PetPoint(x.value, y.value)
            val localEscape = findLocalPetEscapeRoute(
                start = live,
                bounds = safeBounds,
                uiObstacles = messageJourneyObstacles,
                footprint = visualFootprint,
                maximumLength = maximumDirectHopPx,
            )
            val journeyStart = localEscape?.destination ?: live
            val startRail = PetRoamingRail(
                key = "message-edge-recovery:start",
                perchKey = "message-edge-recovery",
                bounds = PetSafeBounds(
                    journeyStart.x,
                    journeyStart.y,
                    journeyStart.x,
                    journeyStart.y,
                ),
            )
            val planned = composerRails.asSequence().mapNotNull { composerRail ->
                planPetRailJourney(
                    startRail = startRail,
                    start = journeyStart,
                    targetRail = composerRail,
                    rails = messageTraversalRails,
                    bounds = safeBounds,
                    uiObstacles = messageJourneyObstacles,
                    footprint = visualFootprint,
                    maximumStepLength = maximumMessageHopPx,
                )?.takeIf { it.isNotEmpty() }?.let { journey -> composerRail to journey }
            }.minByOrNull { (_, journey) ->
                journey.sumOf { it.route.length.toDouble() }
            } ?: return null

            if (localEscape != null) {
                activeRailKey = null
                animatePetRoute(localEscape, PetDebugRouteKind.Recovery)
            }
            planned.second.forEach { step ->
                if (!animateRailJourneyStep(step)) return null
                activeRailKey = step.rail.key
            }
            return planned.first
        }

        // A route change or user drop replans from the live point. The nearest
        // measured ledge wins; the transfer uses the Petdex jump row.
        try {
            var supportedRail = railSupporting(PetPoint(x.value, y.value))
            var recoveredViaMessageFootholds = false
            if (supportedRail == null) {
                supportedRail = recoverToComposerViaMessageFootholds()
                recoveredViaMessageFootholds = supportedRail != null
                if (supportedRail == null) {
                    val escape = findLocalPetEscapeRoute(
                        start = PetPoint(x.value, y.value),
                        bounds = safeBounds,
                        uiObstacles = autonomousRouteObstacles,
                        footprint = footprint,
                        maximumLength = maximumDirectHopPx,
                    )
                    if (escape != null) {
                        activeRailKey = null
                        animatePetRoute(escape, PetDebugRouteKind.Recovery)
                        return@LaunchedEffect
                    }
                }
            }
            if (!canPatrol) return@LaunchedEffect
            var rail = (if (recoveredViaMessageFootholds) supportedRail else settledHabitat?.rail)
                ?: supportedRail
                ?: patrolRails.minByOrNull {
                    it.bounds.clamp(PetPoint(x.value, y.value)).distanceSquaredTo(PetPoint(x.value, y.value))
                }
                ?: return@LaunchedEffect
            activeRailKey = supportedRail?.key
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

                // Curated non-chat surfaces (Settings, Appearance, About,
                // Terminal) expose measured ledges through the same registry.
                // Visit several connected levels as one validated out-and-back
                // tour instead of getting stranded by single-neighbor hops.
                if (route != "chat" && cyclesUntilBubbleVisit <= 0 && patrolRails.size > 1) {
                    val exploration = planPetRailExploration(
                        originRail = rail,
                        start = PetPoint(x.value, y.value),
                        candidateRails = patrolRails,
                        bounds = safeBounds,
                        uiObstacles = autonomousRouteObstacles,
                        footprint = footprint,
                        maximumStepLength = maximumDirectHopPx,
                        maxExtraStops = petBubbleExplorationStops(
                            behaviorPreferences.temperament,
                        ),
                        mode = PetRailExplorationMode.AnyDirection,
                    )
                    if (exploration.continuation.isNotEmpty()) {
                        if (debugTerrainOverlay) {
                            plannedDebugRoute = petDebugPlannedRoute(
                                targetLabel = petTerrainCompactPerchKey(
                                    exploration.orderedRails.last().perchKey,
                                ),
                                routes = buildList {
                                    exploration.continuation.forEach { step ->
                                        step.approach?.let(::add)
                                        add(step.route)
                                    }
                                },
                            )
                        }
                        try {
                            if (!animatePlannedRailExploration(exploration)) {
                                return@LaunchedEffect
                            }
                        } finally {
                            if (debugTerrainOverlay) plannedDebugRoute = null
                        }
                        rail = exploration.orderedRails.first()
                        activeRailKey = rail.key
                        cyclesUntilBubbleVisit = PET_PATROL_CYCLES_BETWEEN_BUBBLE_VISITS
                        continue
                    }
                }

                if (shouldAttemptAmbientBubbleVisit(cyclesUntilBubbleVisit, bubblePerches.isNotEmpty())) {
                    if (debugTerrainOverlay) plannedDebugRoute = null
                    val orderedBubbles = bubblePerches.sortedBy { perch ->
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
                    val railKeyBeforeVisit = activeRailKey
                    val pointBeforeVisit = PetPoint(x.value, y.value)
                    var returnRail: PetRoamingRail? = null
                    for (bubble in orderedBubbles) {
                        if (debugTerrainOverlay) {
                            Log.d("PetTerrain", "ambient-attempt target=${bubble.key}")
                        }
                        returnRail = try {
                            performBubbleExcursion(bubble)
                        } finally {
                            if (debugTerrainOverlay) plannedDebugRoute = null
                        }
                        if (returnRail != null) break
                        if (debugTerrainOverlay) plannedDebugRoute = null
                        if (
                            activeRailKey != railKeyBeforeVisit ||
                            PetPoint(x.value, y.value).distanceSquaredTo(pointBeforeVisit) > 1f
                        ) {
                            return@LaunchedEffect
                        }
                    }
                    if (returnRail != null) {
                        if (debugTerrainOverlay) {
                            Log.d("PetTerrain", "ambient-complete return=${returnRail.key}")
                        }
                        rail = if (settledHabitat != null && moveToSettledHabitat()) {
                            settledHabitat.rail
                        } else {
                            returnRail
                        }
                        activeRailKey = rail.key
                        cyclesUntilBubbleVisit = PET_PATROL_CYCLES_BETWEEN_BUBBLE_VISITS
                        continue
                    }
                    if (debugTerrainOverlay) {
                        Log.d("PetTerrain", "ambient-deferred no-complete-round-trip")
                    }
                    if (activeRailKey != railKeyBeforeVisit) return@LaunchedEffect
                }
                cyclesUntilBubbleVisit--

                // Different ledges retain Desktop's overlap rule. Android may
                // also hop between sibling segments when its registered-control
                // router proves an above-perch route around the obstacle.
                val transfer = if (settledHabitat != null || route != "chat") null else choosePetRailTransfer(
                    currentRail = rail,
                    current = PetPoint(x.value, y.value),
                    // Chat response bubbles are visited only by the explicit
                    // enter/cross/return/exit sequence. Ambient routing may
                    // never jump onto one from an arbitrary point.
                    rails = patrolRails,
                    bounds = safeBounds,
                    uiObstacles = autonomousRouteObstacles,
                    footprint = footprint,
                    maximumRouteLength = maximumDirectHopPx,
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
            if (debugTerrainOverlay) plannedDebugRoute = null
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
        if (debugTerrainOverlay && positioned) {
            PetTerrainDebugOverlay(
                model = PetTerrainDebugModel(
                    routeLabel = route,
                    safeBounds = safeBounds,
                    perches = safeAreaSnapshot.perches,
                    rails = roamingRails,
                    touchdownRails = messageTouchdownRails + messageSideFootholdRails,
                    activeRailKey = activeRailKey,
                    expandedObstacles = registeredObstacles,
                    footprint = footprint,
                    petCenter = draggedPoint ?: PetPoint(x.value, y.value),
                    possibleRoutes = debugPossibleRoutes,
                    plannedRoute = plannedDebugRoute ?: lookaheadDebugRoute,
                    activeRoute = activeDebugRoute,
                    locomotionLabel = locomotion.name,
                    gateLabel = debugGateLabel,
                ),
                onExit = onExitTerrainDebug,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .offset {
                    val displayed = draggedPoint ?: PetPoint(x.value, y.value)
                    IntOffset(
                        (displayed.x - collisionSizePx / 2f).roundToInt(),
                        (displayed.y - collisionSizePx / 2f).roundToInt(),
                    )
                }
                // The host, route footprint, and visible art share one center.
                // A smaller pointer-only host shifts bottom-aligned art upward
                // whenever the authored visual is larger than its touch target.
                .size(collisionSize)
                .alpha(if (positioned) 1f else 0f)
                .graphicsLayer {
                    val scale = 1f + heldProgress * 0.10f
                    scaleX = scale * (1f + landingSquash.value * 0.08f)
                    scaleY = scale * (1f - landingSquash.value * 0.10f)
                    translationY = -heldLiftPx * heldProgress
                }
                .pointerInput(
                    pet.id,
                    safeBounds,
                    roamingRails,
                    settledHabitat,
                    positioned,
                    surfaceScrolling,
                ) {
                    if (!floatingPetAcceptsPointerInput(positioned, surfaceScrolling)) {
                        return@pointerInput
                    }
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
                            val landingRail = choosePetScrollLandingRail(
                                rails = roamingRails + listOfNotNull(settledHabitat?.rail),
                                point = dropped,
                            )
                            val landingPoint = landingRail?.bounds?.clamp(dropped)
                                ?: safeBounds.clamp(dropped)
                            val updatedPlacement = safeBounds.snapToEdge(
                                landingPoint,
                                petLayoutDirection,
                                placement.edge,
                            )
                            pendingDrop = PendingPetDrop(
                                point = dropped,
                                landingPoint = landingPoint,
                                expectedPlacement = updatedPlacement,
                            )
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
                            val freeDragBounds = PetSafeBounds(
                                left = targetSizePx / 2f,
                                top = targetSizePx / 2f,
                                right = (viewportWidth - targetSizePx / 2f)
                                    .coerceAtLeast(targetSizePx / 2f),
                                bottom = (viewportHeight - targetSizePx / 2f)
                                    .coerceAtLeast(targetSizePx / 2f),
                            )
                            draggedPoint = freeDragBounds.clamp(
                                PetPoint(current.x + dragAmount.x, current.y + dragAmount.y),
                            )
                        },
                    )
                }
                .clickable(
                    enabled = floatingPetAcceptsPointerInput(positioned, surfaceScrolling),
                ) {
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
                CompositionLocalProvider(LocalPetGroundOpaqueBottom provides true) {
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
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .size(visualSize),
                    )
                }
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
