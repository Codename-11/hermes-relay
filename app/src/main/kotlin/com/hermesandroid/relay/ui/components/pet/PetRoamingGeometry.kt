package com.hermesandroid.relay.ui.components.pet

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

private const val PET_ROUTE_EPSILON = 0.001f
private const val PET_MAX_BUBBLE_EXPLORATION_STOPS = 3

/** Logical docking edge so persisted placement remains correct under RTL. */
enum class PetLogicalEdge { Start, End }

enum class PetLayoutDirection { Ltr, Rtl }

/**
 * Durable placement: an edge plus a vertical fraction within the current safe
 * bounds. Pixel coordinates are deliberately not persisted.
 */
data class PetPlacement(
    val edge: PetLogicalEdge,
    val verticalFraction: Float,
) {
    fun sanitized(): PetPlacement = copy(verticalFraction = verticalFraction.normalizedFraction())

    fun resolve(bounds: PetSafeBounds, layoutDirection: PetLayoutDirection): PetPoint {
        val physicalStart = if (layoutDirection == PetLayoutDirection.Ltr) bounds.left else bounds.right
        val physicalEnd = if (layoutDirection == PetLayoutDirection.Ltr) bounds.right else bounds.left
        return PetPoint(
            x = if (edge == PetLogicalEdge.Start) physicalStart else physicalEnd,
            y = bounds.top + bounds.height * verticalFraction.normalizedFraction(),
        )
    }
}

data class PetPoint(val x: Float, val y: Float) {
    fun distanceSquaredTo(other: PetPoint): Float {
        val dx = x - other.x
        val dy = y - other.y
        return dx * dx + dy * dy
    }
}

/** Insets already occupied by system chrome or registered app chrome. */
data class PetInsets(
    val start: Float = 0f,
    val top: Float = 0f,
    val end: Float = 0f,
    val bottom: Float = 0f,
) {
    init {
        require(valuesAreFinite(start, top, end, bottom)) { "Insets must be finite." }
        require(start >= 0f && top >= 0f && end >= 0f && bottom >= 0f) {
            "Insets must be non-negative."
        }
    }
}

/** Pet hit-target dimensions plus clearance from registered UI obstacles. */
data class PetFootprint(
    val width: Float,
    val height: Float,
    val clearance: Float = 0f,
) {
    init {
        require(valuesAreFinite(width, height, clearance)) { "Pet footprint must be finite." }
        require(width >= 0f && height >= 0f && clearance >= 0f) {
            "Pet footprint must be non-negative."
        }
    }

    val horizontalRadius: Float get() = width / 2f + clearance
    val verticalRadius: Float get() = height / 2f + clearance
}

/** Allowed range for the pet's center point after applying insets and pet size. */
data class PetSafeBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(valuesAreFinite(left, top, right, bottom)) { "Safe bounds must be finite." }
        require(right >= left && bottom >= top) { "Safe bounds must not be inverted." }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(point: PetPoint): Boolean =
        point.x in left..right && point.y in top..bottom

    fun clamp(point: PetPoint): PetPoint = PetPoint(
        x = point.x.finiteOr(left).coerceIn(left, right),
        y = point.y.finiteOr(top).coerceIn(top, bottom),
    )

    fun snapToEdge(
        point: PetPoint,
        layoutDirection: PetLayoutDirection,
        tieBreaker: PetLogicalEdge = PetLogicalEdge.Start,
    ): PetPlacement {
        val clamped = clamp(point)
        val distanceToLeft = abs(clamped.x - left)
        val distanceToRight = abs(right - clamped.x)
        val physicalLeft = when {
            distanceToLeft < distanceToRight -> true
            distanceToRight < distanceToLeft -> false
            else -> when (layoutDirection) {
                PetLayoutDirection.Ltr -> tieBreaker == PetLogicalEdge.Start
                PetLayoutDirection.Rtl -> tieBreaker == PetLogicalEdge.End
            }
        }
        val logicalEdge = when (layoutDirection) {
            PetLayoutDirection.Ltr -> if (physicalLeft) PetLogicalEdge.Start else PetLogicalEdge.End
            PetLayoutDirection.Rtl -> if (physicalLeft) PetLogicalEdge.End else PetLogicalEdge.Start
        }
        val fraction = if (height == 0f) 0f else (clamped.y - top) / height
        return PetPlacement(logicalEdge, fraction.normalizedFraction())
    }
}

/**
 * Convert the full overlay viewport into center-coordinate bounds. No layout
 * slot is reserved: system/app chrome is represented only by [insets], and the
 * pet's own hit target is inset once through [footprint].
 */
fun overlaySafeBounds(
    viewportWidth: Float,
    viewportHeight: Float,
    insets: PetInsets,
    footprint: PetFootprint,
    layoutDirection: PetLayoutDirection,
): PetSafeBounds? {
    if (!valuesAreFinite(viewportWidth, viewportHeight) || viewportWidth < 0f || viewportHeight < 0f) {
        return null
    }
    val physicalLeftInset = if (layoutDirection == PetLayoutDirection.Ltr) insets.start else insets.end
    val physicalRightInset = if (layoutDirection == PetLayoutDirection.Ltr) insets.end else insets.start
    val left = physicalLeftInset + footprint.horizontalRadius
    val top = insets.top + footprint.verticalRadius
    val right = viewportWidth - physicalRightInset - footprint.horizontalRadius
    val bottom = viewportHeight - insets.bottom - footprint.verticalRadius
    return if (right >= left && bottom >= top) PetSafeBounds(left, top, right, bottom) else null
}

/** Axis-aligned obstacle in the same center-coordinate space as [PetSafeBounds]. */
data class PetObstacle(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(valuesAreFinite(left, top, right, bottom)) { "Obstacle bounds must be finite." }
        require(right >= left && bottom >= top) { "Obstacle bounds must not be inverted." }
    }

    fun expanded(horizontal: Float, vertical: Float = horizontal): PetObstacle {
        require(horizontal >= 0f && vertical >= 0f) { "Obstacle expansion must be non-negative." }
        return PetObstacle(
            left = left - horizontal,
            top = top - vertical,
            right = right + horizontal,
            bottom = bottom + vertical,
        )
    }

    fun contains(point: PetPoint): Boolean =
        point.x in left..right && point.y in top..bottom

    fun intersects(other: PetObstacle): Boolean =
        left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top

    /** Inclusive line-segment/AABB intersection using the slab method. */
    fun intersectsSegment(start: PetPoint, end: PetPoint): Boolean {
        var minimum = 0f
        var maximum = 1f
        val dx = end.x - start.x
        val dy = end.y - start.y

        fun clip(origin: Float, delta: Float, min: Float, max: Float): Boolean {
            if (delta == 0f) return origin in min..max
            var entry = (min - origin) / delta
            var exit = (max - origin) / delta
            if (entry > exit) entry = exit.also { exit = entry }
            minimum = maxOf(minimum, entry)
            maximum = minOf(maximum, exit)
            return minimum <= maximum
        }

        return clip(start.x, dx, left, right) && clip(start.y, dy, top, bottom)
    }
}

/**
 * Route scope for a measured pet surface. An empty set is app-wide. Route
 * templates may use a whole-segment `{argument}` placeholder and query strings
 * are ignored, matching Navigation Compose destination templates.
 */
data class PetRouteScope(val routes: Set<String> = emptySet()) {
    val isGlobal: Boolean get() = routes.isEmpty()

    fun includes(route: String?): Boolean =
        isGlobal || (route != null && routes.any { template -> petRouteMatches(template, route) })
}

/** Immutable, named UI ledge measured in root coordinates. */
data class PetMeasuredPerch(
    val key: String,
    val bounds: PetObstacle,
    val routeScope: PetRouteScope = PetRouteScope(),
) {
    init {
        require(key.isNotBlank()) { "Perch key must not be blank." }
    }
}

/** Immutable, named UI obstacle measured in root coordinates. */
data class PetMeasuredObstacle(
    val key: String,
    val bounds: PetObstacle,
    val routeScope: PetRouteScope = PetRouteScope(),
) {
    init {
        require(key.isNotBlank()) { "Obstacle key must not be blank." }
    }
}

/** Immutable, named point of interest that does not become walkable terrain. */
data class PetMeasuredVisitTarget(
    val key: String,
    val bounds: PetObstacle,
    val routeScope: PetRouteScope = PetRouteScope(),
) {
    init {
        require(key.isNotBlank()) { "Visit target key must not be blank." }
    }
}

/** Route-filtered registry view. It cannot leak stale surfaces from another destination. */
data class PetSafeAreaSnapshot(
    val route: String?,
    val perches: List<PetMeasuredPerch>,
    val obstacles: List<PetMeasuredObstacle>,
    val visitTargets: List<PetMeasuredVisitTarget> = emptyList(),
)

/** One collision-trimmed segment of a measured perch. */
data class PetRoamingRail(
    val key: String,
    val perchKey: String,
    val bounds: PetSafeBounds,
)

/** A deterministic, already-validated transfer to another roaming rail. */
data class PetRailTransfer(
    val rail: PetRoamingRail,
    val destinationX: Float,
    val siblingSegment: Boolean,
    val route: PetRoute,
)

/** One bounded hop in a multi-level journey toward a destination rail. */
data class PetRailJourneyStep(
    val rail: PetRoamingRail,
    val route: PetRoute,
    /** Optional same-rail walk required before the airborne route begins. */
    val approach: PetRoute? = null,
)

/** Physical side of a bubble used by transient exterior footholds. */
enum class PetBubbleEdgeSide { Left, Right }

/**
 * Bottom-to-top contact points along one collision-free bubble edge. These are
 * traversal-only footholds, not rails: callers must never expose them to idle,
 * patrol, home, or scroll-support selection.
 */
data class PetBubbleEdgeFootholdPlan(
    val side: PetBubbleEdgeSide,
    val footholds: List<PetPoint>,
    val overlapsBubbleEdge: Boolean = false,
) {
    val legs: List<PetRoute>
        get() = footholds.zipWithNext { start, destination ->
            PetRoute(listOf(start, destination))
        }
}

enum class PetRailExplorationMode {
    Ascending,
    AnyDirection,
}

/**
 * A bounded surface tour. [orderedRails] starts with the origin and
 * [continuation] contains only the additional collision-checked transfers.
 */
data class PetRailExplorationPlan(
    val orderedRails: List<PetRoamingRail>,
    val continuation: List<PetRailJourneyStep>,
) {
    init {
        require(orderedRails.isNotEmpty()) { "A rail exploration needs its origin rail." }
        require(continuation.size == orderedRails.size - 1) {
            "Each additional exploration rail needs one transfer."
        }
    }
}

/**
 * One collision-checked chat response excursion. A roomy response uses
 * [gutter]; a phone-width response uses a direct [PetBubbleEntryMode.EdgeHop]
 * between the composer edge and raised [entry]. [entry] and [opposite] keep
 * the complete pet footprint above the bubble while it walks across.
 */
data class PetBubbleExcursion(
    val composerApproach: PetPoint,
    val gutter: PetPoint,
    val entry: PetPoint,
    val opposite: PetPoint,
    val entryMode: PetBubbleEntryMode = PetBubbleEntryMode.ClearGutter,
)

enum class PetSettledChatMode {
    SidePocketPace,
    SidePocketIdle,
    BubbleTop,
    ComposerCorner,
}

/** The preferred text-safe habitat after Chat settles at the bottom. */
data class PetSettledChatHabitat(
    val mode: PetSettledChatMode,
    val rail: PetRoamingRail,
)

enum class PetBubbleEntryMode { ClearGutter, EdgeHop }

/** Pure Navigation-style route matching used by registry snapshots. */
fun petRouteMatches(template: String, actual: String): Boolean {
    fun segments(value: String): List<String> = value
        .substringBefore('?')
        .trim()
        .trim('/')
        .takeIf(String::isNotEmpty)
        ?.split('/')
        .orEmpty()

    val expected = segments(template)
    val observed = segments(actual)
    if (expected.size != observed.size) return false
    return expected.zip(observed).all { (left, right) ->
        (left.startsWith('{') && left.endsWith('}') && left.length > 2) || left == right
    }
}

/** Center-coordinate rail that stands the pet on the measured surface's top edge. */
fun petPerchRail(
    perch: PetMeasuredPerch,
    footprint: PetFootprint,
    outer: PetSafeBounds,
    verticalClearance: Float = 0f,
): PetSafeBounds? {
    require(verticalClearance >= 0f && verticalClearance.isFinite()) {
        "Perch clearance must be finite and non-negative."
    }
    val left = maxOf(perch.bounds.left + footprint.horizontalRadius, outer.left)
    val right = minOf(perch.bounds.right - footprint.horizontalRadius, outer.right)
    if (right < left) return null
    val centerY = perch.bounds.top - footprint.height / 2f - verticalClearance
    if (centerY !in outer.top..outer.bottom) return null
    return PetSafeBounds(left, centerY, right, centerY)
}

/**
 * A centered, zero-width touchdown for a measured perch that is too narrow to
 * carry the complete pet footprint as a walking rail. Touchdowns are transient
 * route steps only: callers must not expose them to autonomous rail patrol or
 * treat them as persistent support.
 */
fun petPerchTouchdownRail(
    perch: PetMeasuredPerch,
    footprint: PetFootprint,
    outer: PetSafeBounds,
    minimumSurfaceWidth: Float,
    verticalClearance: Float = 0f,
): PetSafeBounds? {
    require(minimumSurfaceWidth >= 0f && minimumSurfaceWidth.isFinite()) {
        "Minimum touchdown width must be finite and non-negative."
    }
    require(verticalClearance >= 0f && verticalClearance.isFinite()) {
        "Touchdown clearance must be finite and non-negative."
    }
    val visibleLeft = maxOf(perch.bounds.left, outer.left - footprint.horizontalRadius)
    val visibleRight = minOf(perch.bounds.right, outer.right + footprint.horizontalRadius)
    if (visibleRight - visibleLeft < minimumSurfaceWidth) return null

    val centerX = ((visibleLeft + visibleRight) / 2f).coerceIn(outer.left, outer.right)
    val centerY = perch.bounds.top - footprint.verticalRadius - verticalClearance
    if (centerY !in outer.top..outer.bottom) return null
    return PetSafeBounds(centerX, centerY, centerX, centerY)
}

/**
 * A zero-width landing point beside a measured perch. The preferred side is
 * used when the full pet footprint fits in that gutter; otherwise the opposite
 * side is tried. Returning null is safer than overlapping the surface.
 */
fun petPerchEdgeRail(
    perch: PetMeasuredPerch,
    footprint: PetFootprint,
    outer: PetSafeBounds,
    useLeftEdge: Boolean,
    verticalClearance: Float = 0f,
): PetSafeBounds? {
    require(verticalClearance >= 0f && verticalClearance.isFinite()) {
        "Perch clearance must be finite and non-negative."
    }
    val centerY = perch.bounds.top - footprint.verticalRadius - verticalClearance
    if (centerY !in outer.top..outer.bottom) return null
    val sides = if (useLeftEdge) listOf(true, false) else listOf(false, true)
    val edgeX = sides.firstNotNullOfOrNull { leftSide ->
        val requested = if (leftSide) {
            perch.bounds.left - footprint.horizontalRadius - verticalClearance
        } else {
            perch.bounds.right + footprint.horizontalRadius + verticalClearance
        }
        requested.takeIf { it in outer.left..outer.right }
    } ?: return null
    return PetSafeBounds(edgeX, centerY, edgeX, centerY)
}

/**
 * Derive a deterministic ladder beside the visible portion of a tall bubble.
 *
 * [traversalFootprint] is deliberately caller supplied. The overlay may retain
 * a larger accessible touch target while collision routing uses the visible
 * sprite footprint. A side is eligible only when every foothold and complete
 * straight leg fits the center-safe bounds, remains outside the bubble and all
 * other expanded obstacles, and stays within [maximumStepLength]. The preferred
 * side is tried first, followed by the opposite side.
 *
 * The returned points run from bottom to top. Ingress from a persistent rail
 * and egress onto the bubble-top rail remain separate exact graph edges that
 * callers must validate before starting the traversal.
 */
fun planPetBubbleEdgeFootholds(
    bubble: PetMeasuredPerch,
    bounds: PetSafeBounds,
    uiObstacles: Iterable<PetObstacle>,
    traversalFootprint: PetFootprint,
    maximumStepLength: Float,
    preferredSide: PetBubbleEdgeSide,
    allowInsetEdge: Boolean = false,
): PetBubbleEdgeFootholdPlan? {
    require(maximumStepLength > 0f && maximumStepLength.isFinite()) {
        "Maximum foothold step length must be finite and positive."
    }

    val top = maxOf(
        bounds.top,
        bubble.bounds.top - traversalFootprint.verticalRadius,
    )
    val bottom = minOf(
        bounds.bottom,
        bubble.bounds.bottom + traversalFootprint.verticalRadius,
    )
    val visibleSpan = bottom - top
    if (visibleSpan <= PET_ROUTE_EPSILON) return null

    // Even a short bubble contributes an entry and exit point. Several
    // ordinary-height bubbles can then form the staircase that bridges a chat
    // viewport whose cumulative gap exceeds one bounded hop.
    val legCount = maxOf(1, ceil(visibleSpan / maximumStepLength).toInt())
    val expandedObstacles = expandObstaclesForPet(uiObstacles, traversalFootprint)
    val sides = if (preferredSide == PetBubbleEdgeSide.Left) {
        listOf(PetBubbleEdgeSide.Left, PetBubbleEdgeSide.Right)
    } else {
        listOf(PetBubbleEdgeSide.Right, PetBubbleEdgeSide.Left)
    }

    return sides.firstNotNullOfOrNull { side ->
        val exteriorX = when (side) {
            PetBubbleEdgeSide.Left ->
                bubble.bounds.left - traversalFootprint.horizontalRadius - PET_ROUTE_EPSILON
            PetBubbleEdgeSide.Right ->
                bubble.bounds.right + traversalFootprint.horizontalRadius + PET_ROUTE_EPSILON
        }
        val insetX = when (side) {
            PetBubbleEdgeSide.Left ->
                bubble.bounds.left + traversalFootprint.horizontalRadius - PET_ROUTE_EPSILON
            PetBubbleEdgeSide.Right ->
                bubble.bounds.right - traversalFootprint.horizontalRadius + PET_ROUTE_EPSILON
        }
        val candidates = buildList {
            add(exteriorX to false)
            if (allowInsetEdge) add(insetX to true)
        }
        candidates.firstNotNullOfOrNull candidateLoop@ { (x, overlaps) ->
            if (x !in bounds.left..bounds.right) return@candidateLoop null
            val footholds = List(legCount + 1) { index ->
                val progress = index.toFloat() / legCount.toFloat()
                PetPoint(x = x, y = bottom - visibleSpan * progress)
            }
            if (footholds.any { point ->
                    !bounds.contains(point) || expandedObstacles.any { it.contains(point) }
                }
            ) return@candidateLoop null
            val legs = footholds.zipWithNext()
            if (legs.any { (start, destination) ->
                    sqrt(start.distanceSquaredTo(destination)) >
                        maximumStepLength + PET_ROUTE_EPSILON ||
                        expandedObstacles.any { it.intersectsSegment(start, destination) }
                }
            ) return@candidateLoop null
            PetBubbleEdgeFootholdPlan(
                side = side,
                footholds = footholds,
                overlapsBubbleEdge = overlaps,
            )
        }
    }
}

/**
 * Protect message content while opening one sprite-wide, traversal-only lane
 * along the selected edge. The generic router expands this raw obstacle by the
 * same visual footprint, leaving exactly one visible-sprite-width hop lane.
 */
fun petBubbleEdgeTraversalObstacle(
    perch: PetMeasuredPerch,
    traversalFootprint: PetFootprint,
    useLeftEdge: Boolean,
    openBothEdges: Boolean = false,
): PetObstacle {
    val laneDepth = traversalFootprint.horizontalRadius * 2f
    if (openBothEdges) {
        val contractedLeft = minOf(perch.bounds.left + laneDepth, perch.bounds.right)
        val contractedRight = maxOf(perch.bounds.right - laneDepth, perch.bounds.left)
        return if (contractedLeft <= contractedRight) {
            perch.bounds.copy(left = contractedLeft, right = contractedRight)
        } else {
            val center = (perch.bounds.left + perch.bounds.right) / 2f
            perch.bounds.copy(left = center, right = center)
        }
    }
    return if (useLeftEdge) {
        perch.bounds.copy(left = minOf(perch.bounds.left + laneDepth, perch.bounds.right))
    } else {
        perch.bounds.copy(right = maxOf(perch.bounds.right - laneDepth, perch.bounds.left))
    }
}

/**
 * Plan a deterministic composer -> bubble -> composer visit. Prefer the
 * trailing-side gutter; when the viewport cannot fit a complete pet there,
 * use a short edge-biased ballistic hop to the raised top rail. Returning null
 * means an obstacle blocks both safe shapes.
 */
fun planPetBubbleExcursion(
    bubble: PetMeasuredPerch,
    composerRail: PetRoamingRail,
    footprint: PetFootprint,
    outer: PetSafeBounds,
    uiObstacles: Iterable<PetObstacle>,
    useLeftGutter: Boolean,
    verticalClearance: Float = 0f,
    minimumWalkWidth: Float = 1f,
): PetBubbleExcursion? {
    require(verticalClearance >= 0f && verticalClearance.isFinite()) {
        "Perch clearance must be finite and non-negative."
    }
    require(minimumWalkWidth >= 0f && minimumWalkWidth.isFinite()) {
        "Minimum walk width must be finite and non-negative."
    }
    val bubbleRail = petPerchRail(
        perch = bubble,
        footprint = footprint,
        outer = outer,
        verticalClearance = verticalClearance,
    ) ?: return null
    if (bubbleRail.width < minimumWalkWidth) return null

    val requestedGutterX = if (useLeftGutter) {
        bubble.bounds.left - footprint.horizontalRadius - verticalClearance
    } else {
        bubble.bounds.right + footprint.horizontalRadius + verticalClearance
    }
    val entryX = if (useLeftGutter) bubbleRail.left else bubbleRail.right
    val oppositeX = if (useLeftGutter) bubbleRail.right else bubbleRail.left
    val entry = PetPoint(entryX, bubbleRail.top)
    val opposite = PetPoint(oppositeX, bubbleRail.top)

    val expanded = expandObstaclesForPet(uiObstacles, footprint)
    if (pathIntersectsObstacle(entry, opposite, expanded)) return null

    val clearGutterFits = requestedGutterX in outer.left..outer.right &&
        requestedGutterX in composerRail.bounds.left..composerRail.bounds.right
    val entryMode = if (clearGutterFits) {
        PetBubbleEntryMode.ClearGutter
    } else {
        PetBubbleEntryMode.EdgeHop
    }
    val composerApproach = when (entryMode) {
        PetBubbleEntryMode.ClearGutter -> PetPoint(requestedGutterX, composerRail.bounds.top)
        PetBubbleEntryMode.EdgeHop -> PetPoint(
            if (useLeftGutter) composerRail.bounds.left else composerRail.bounds.right,
            composerRail.bounds.top,
        )
    }
    val gutter = when (entryMode) {
        PetBubbleEntryMode.ClearGutter -> PetPoint(requestedGutterX, bubbleRail.top)
        PetBubbleEntryMode.EdgeHop -> entry
    }
    val entryPathBlocked = when (entryMode) {
        PetBubbleEntryMode.ClearGutter ->
            pathIntersectsObstacle(composerApproach, gutter, expanded) ||
                pathIntersectsObstacle(gutter, entry, expanded)
        PetBubbleEntryMode.EdgeHop -> pathIntersectsObstacle(composerApproach, entry, expanded)
    }
    if (entryPathBlocked) return null

    val expandedBubble = bubble.bounds.expanded(
        footprint.horizontalRadius,
        footprint.verticalRadius,
    )
    if (
        entryMode == PetBubbleEntryMode.EdgeHop &&
        pathIntersectsObstacle(composerApproach, entry, listOf(expandedBubble))
    ) return null
    // The gutter is strictly exterior. The raised top rail may touch the
    // expanded boundary only when clearance is zero, but never enters it.
    if (entryMode == PetBubbleEntryMode.ClearGutter && expandedBubble.contains(gutter)) return null
    if (bubbleRail.top > expandedBubble.top) return null

    return PetBubbleExcursion(composerApproach, gutter, entry, opposite, entryMode)
}

/** Whether the pet's bottom edge is resting on this curated ledge. */
fun isPetSupportedByPerch(
    center: PetPoint,
    perch: PetMeasuredPerch,
    footprint: PetFootprint,
    tolerance: Float = 1f,
): Boolean {
    require(tolerance >= 0f && tolerance.isFinite()) { "Support tolerance must be finite and non-negative." }
    val supportedY = perch.bounds.top - footprint.height / 2f
    val minimumX = perch.bounds.left + footprint.width / 2f
    val maximumX = perch.bounds.right - footprint.width / 2f
    return maximumX >= minimumX &&
        center.x in (minimumX - tolerance)..(maximumX + tolerance) &&
        abs(center.y - supportedY) <= tolerance
}

/**
 * Split a measured perch into collision-free horizontal rails. Obstacles are
 * expanded by the pet footprint first, so a transient control trims only the
 * portion of a ledge it actually occupies instead of disabling the whole page.
 */
fun petPerchSegments(
    perch: PetMeasuredPerch,
    obstacles: Iterable<PetMeasuredObstacle>,
    footprint: PetFootprint,
    outer: PetSafeBounds,
    minimumWidth: Float = 1f,
    verticalClearance: Float = 0f,
): List<PetSafeBounds> {
    require(minimumWidth >= 0f && minimumWidth.isFinite()) {
        "Minimum perch width must be finite and non-negative."
    }
    val rail = petPerchRail(perch, footprint, outer, verticalClearance) ?: return emptyList()
    return trimHorizontalRail(rail, obstacles, footprint, minimumWidth)
}

/**
 * Prefer blank space beside the latest bubble; fall back to its raised top edge,
 * then to the outer composer corner. Every candidate uses center-coordinate
 * bounds for the complete scaled pet footprint.
 */
fun planSettledChatHabitat(
    bubble: PetMeasuredPerch,
    composerRails: List<PetRoamingRail>,
    obstacles: Iterable<PetMeasuredObstacle>,
    footprint: PetFootprint,
    outer: PetSafeBounds,
    useLeftPocket: Boolean,
    verticalClearance: Float = 0f,
): PetSettledChatHabitat? {
    require(verticalClearance >= 0f && verticalClearance.isFinite()) {
        "Habitat clearance must be finite and non-negative."
    }
    val obstacleList = obstacles.toList()
    val pocketY = (bubble.bounds.bottom - footprint.height / 2f)
        .coerceIn(outer.top, outer.bottom)
    val pocketLeft = if (useLeftPocket) {
        outer.left
    } else {
        bubble.bounds.right + footprint.horizontalRadius + verticalClearance
    }
    val pocketRight = if (useLeftPocket) {
        bubble.bounds.left - footprint.horizontalRadius - verticalClearance
    } else {
        outer.right
    }
    if (pocketRight >= pocketLeft) {
        val pocketRail = PetSafeBounds(pocketLeft, pocketY, pocketRight, pocketY)
        val pocketSegments = trimHorizontalRail(
            rail = pocketRail,
            obstacles = obstacleList,
            footprint = footprint,
            minimumWidth = 0f,
        )
        val pocket = pocketSegments.maxWithOrNull(
            compareBy<PetSafeBounds> { it.width }
                .thenBy { if (useLeftPocket) -it.left else it.right },
        )
        if (pocket != null) {
            val mode = if (pocket.width >= footprint.width * 0.75f) {
                PetSettledChatMode.SidePocketPace
            } else {
                PetSettledChatMode.SidePocketIdle
            }
            return PetSettledChatHabitat(
                mode = mode,
                rail = PetRoamingRail(
                    key = "chat-settled:${bubble.key}:side",
                    perchKey = "chat-settled:${bubble.key}",
                    bounds = pocket,
                ),
            )
        }
    }

    val hasSafeTopEntry = composerRails.any { composerRail ->
        planPetBubbleExcursion(
            bubble = bubble,
            composerRail = composerRail,
            footprint = footprint,
            outer = outer,
            uiObstacles = obstacleList.map { it.bounds },
            useLeftGutter = useLeftPocket,
            verticalClearance = verticalClearance,
            minimumWalkWidth = 0f,
        ) != null
    }
    val bubbleTop = petPerchSegments(
        perch = bubble,
        obstacles = obstacleList,
        footprint = footprint,
        outer = outer,
        minimumWidth = 0f,
        verticalClearance = verticalClearance,
    ).maxByOrNull { it.width }.takeIf { hasSafeTopEntry }
    if (bubbleTop != null) {
        return PetSettledChatHabitat(
            mode = PetSettledChatMode.BubbleTop,
            rail = PetRoamingRail(
                key = "chat-settled:${bubble.key}:top",
                perchKey = "chat-settled:${bubble.key}",
                bounds = bubbleTop,
            ),
        )
    }

    val composer = composerRails.minByOrNull { rail ->
        if (useLeftPocket) rail.bounds.left else -rail.bounds.right
    } ?: return null
    val cornerX = if (useLeftPocket) composer.bounds.left else composer.bounds.right
    val corner = PetSafeBounds(cornerX, composer.bounds.top, cornerX, composer.bounds.top)
    return PetSettledChatHabitat(
        mode = PetSettledChatMode.ComposerCorner,
        rail = PetRoamingRail(
            key = "chat-settled:${bubble.key}:composer",
            perchKey = "chat-settled:${bubble.key}",
            bounds = corner,
        ),
    )
}

private fun trimHorizontalRail(
    rail: PetSafeBounds,
    obstacles: Iterable<PetMeasuredObstacle>,
    footprint: PetFootprint,
    minimumWidth: Float,
): List<PetSafeBounds> {
    var intervals = listOf(rail.left to rail.right)
    obstacles.asSequence()
        .map { it.bounds.expanded(footprint.horizontalRadius, footprint.verticalRadius) }
        .filter { rail.top in it.top..it.bottom }
        .sortedWith(compareBy<PetObstacle> { it.left }.thenBy { it.right })
        .forEach { obstacle ->
            intervals = intervals.flatMap { (left, right) ->
                if (obstacle.right <= left || obstacle.left >= right) {
                    listOf(left to right)
                } else {
                    buildList {
                        val before = (obstacle.left - PET_ROUTE_EPSILON).coerceIn(left, right)
                        val after = (obstacle.right + PET_ROUTE_EPSILON).coerceIn(left, right)
                        if (obstacle.left > left && before - left >= minimumWidth) add(left to before)
                        if (obstacle.right < right && right - after >= minimumWidth) add(after to right)
                    }
                }
            }
        }
    return intervals
        .filter { (left, right) -> right - left >= minimumWidth }
        .map { (left, right) -> PetSafeBounds(left, rail.top, right, rail.top) }
}

/** Raw measured UI bounds expanded into forbidden pet-center coordinates. */
fun expandObstaclesForPet(
    obstacles: Iterable<PetObstacle>,
    footprint: PetFootprint,
): List<PetObstacle> = obstacles.map {
    it.expanded(footprint.horizontalRadius, footprint.verticalRadius)
}

/**
 * Preserve a measured perch body as a routing obstacle while allowing exact
 * contact with its validated top rail. Generic obstacles use inclusive bounds;
 * shifting only the raw top by one routing epsilon keeps the expanded body
 * below the rail instead of projecting a safe landing point away from it.
 */
fun petTopSupportedObstacle(perch: PetMeasuredPerch): PetObstacle =
    petTopSupportedObstacle(perch.bounds)

fun petTopSupportedObstacle(obstacle: PetObstacle): PetObstacle = obstacle.copy(
    top = minOf(obstacle.top + PET_ROUTE_EPSILON, obstacle.bottom),
)

data class PetRoute(val points: List<PetPoint>) {
    init {
        require(points.isNotEmpty()) { "A pet route needs at least one point." }
    }

    val start: PetPoint get() = points.first()
    val destination: PetPoint get() = points.last()
}

/** Exact collision-checked polyline used to enter a bubble-top rail. */
fun petBubbleEntryRoute(excursion: PetBubbleExcursion): PetRoute = PetRoute(
    when (excursion.entryMode) {
        PetBubbleEntryMode.ClearGutter -> listOf(
            excursion.composerApproach,
            excursion.gutter,
            excursion.entry,
        )
        PetBubbleEntryMode.EdgeHop -> listOf(excursion.composerApproach, excursion.entry)
    },
)

/** The same validated bubble edge path in reverse. */
fun petBubbleExitRoute(excursion: PetBubbleExcursion): PetRoute = PetRoute(
    petBubbleEntryRoute(excursion).points.asReversed(),
)

/**
 * Build a monotonic, collision-checked journey between vertical terrain levels.
 * A transfer longer than [maximumStepLength] is never accepted; intermediate
 * rails are used only when the destination cannot be reached safely in one hop.
 */
fun planPetRailJourney(
    startRail: PetRoamingRail,
    start: PetPoint,
    targetRail: PetRoamingRail,
    rails: Iterable<PetRoamingRail>,
    bounds: PetSafeBounds,
    uiObstacles: Iterable<PetObstacle>,
    footprint: PetFootprint,
    maximumStepLength: Float,
): List<PetRailJourneyStep>? {
    require(maximumStepLength > 0f && maximumStepLength.isFinite()) {
        "Maximum journey step length must be finite and positive."
    }
    if (startRail.key == targetRail.key) return emptyList()

    val candidates = (rails + targetRail)
        .filterNot { it.key == startRail.key }
        .distinctBy { it.key }
    val ascending = targetRail.bounds.top < start.y
    val targetY = targetRail.bounds.top
    val optionComparator = compareBy<PetRailJourneyStep> {
        if (ascending) it.rail.bounds.top else -it.rail.bounds.top
    }.thenBy { it.route.length }
        .thenBy { it.approach?.length ?: 0f }
        .thenBy { it.rail.key }
    val failedStates = mutableSetOf<Pair<String, Float>>()

    fun search(
        currentRail: PetRoamingRail,
        currentPoint: PetPoint,
        remaining: List<PetRoamingRail>,
    ): List<PetRailJourneyStep>? {
        if (currentRail.key == targetRail.key) return emptyList()
        val state = currentRail.key to currentPoint.x
        if (!failedStates.add(state)) return null
        val options = remaining.mapNotNull { candidate ->
            val candidateY = candidate.bounds.top
            val progresses = if (ascending) {
                candidateY < currentPoint.y - PET_ROUTE_EPSILON &&
                    candidateY >= targetY - PET_ROUTE_EPSILON
            } else {
                candidateY > currentPoint.y + PET_ROUTE_EPSILON &&
                    candidateY <= targetY + PET_ROUTE_EPSILON
            }
            if (!progresses) return@mapNotNull null
            petRailTransferXOptions(
                from = currentRail,
                to = candidate,
                preferredX = currentPoint.x,
            ).firstNotNullOfOrNull { (departureX, destinationX) ->
                val departure = PetPoint(departureX, currentPoint.y)
                val destination = PetPoint(destinationX, candidateY)
                val route = findExactOverlayRoute(
                    start = departure,
                    requestedDestination = destination,
                    bounds = bounds,
                    uiObstacles = uiObstacles,
                    footprint = footprint,
                ) ?: return@firstNotNullOfOrNull null
                if (route.start.distanceSquaredTo(departure) > 1f) {
                    return@firstNotNullOfOrNull null
                }
                if (
                    route.destination.distanceSquaredTo(destination) >
                    PET_ROUTE_EPSILON * PET_ROUTE_EPSILON
                ) return@firstNotNullOfOrNull null
                if (route.length > maximumStepLength + PET_ROUTE_EPSILON) {
                    return@firstNotNullOfOrNull null
                }
                val approach = if (departure.distanceSquaredTo(currentPoint) > 1f) {
                    PetRoute(listOf(currentPoint, departure))
                } else {
                    null
                }
                PetRailJourneyStep(candidate, route, approach)
            }
        }.sortedWith(optionComparator)

        options.forEach { next ->
            val tail = search(
                currentRail = next.rail,
                currentPoint = next.route.destination,
                remaining = remaining.filterNot { it.key == next.rail.key },
            ) ?: return@forEach
            return listOf(next) + tail
        }
        failedStates += state
        return null
    }

    return search(startRail, start, candidates)
}

/**
 * Pick matching launch/landing X coordinates for a rail transfer. The pet may
 * walk along its current safe rail before jumping, so the active planner uses
 * the same overlap/nearest-edge geometry shown by the debug route graph.
 */
private fun petRailTransferXOptions(
    from: PetRoamingRail,
    to: PetRoamingRail,
    preferredX: Float? = null,
): List<Pair<Float, Float>> {
    val overlapLeft = maxOf(from.bounds.left, to.bounds.left)
    val overlapRight = minOf(from.bounds.right, to.bounds.right)
    return when {
        overlapLeft <= overlapRight -> {
            buildList {
                val midpoint = (overlapLeft + overlapRight) / 2f
                add(midpoint to midpoint)
                preferredX?.coerceIn(overlapLeft, overlapRight)?.let { add(it to it) }
                add(overlapLeft to overlapLeft)
                add(overlapRight to overlapRight)
            }.distinct()
        }
        from.bounds.right < to.bounds.left -> listOf(from.bounds.right to to.bounds.left)
        else -> listOf(from.bounds.left to to.bounds.right)
    }
}

typealias PetBubbleExplorationPlan = PetRailExplorationPlan

/**
 * Select up to [maxExtraStops] visible rails after reaching [originRail].
 * Chat uses [PetRailExplorationMode.Ascending] to visit older messages;
 * curated app surfaces use [PetRailExplorationMode.AnyDirection] so a scroll
 * landing on an upper card can still tour downward. Each continuation is one
 * direct, collision-checked journey step no longer than [maximumStepLength].
 *
 * Rails are ordered by live screen geometry: origin first, then the nearest
 * reachable level allowed by [mode]. Multiple safe segments from one surface
 * count as one stop, with route length and key providing deterministic ties.
 */
fun planPetRailExploration(
    originRail: PetRoamingRail,
    start: PetPoint,
    candidateRails: Iterable<PetRoamingRail>,
    bounds: PetSafeBounds,
    uiObstacles: Iterable<PetObstacle>,
    footprint: PetFootprint,
    maximumStepLength: Float,
    maxExtraStops: Int,
    mode: PetRailExplorationMode = PetRailExplorationMode.Ascending,
): PetRailExplorationPlan {
    require(maxExtraStops in 0..PET_MAX_BUBBLE_EXPLORATION_STOPS) {
        "Rail exploration supports zero to three extra stops."
    }
    require(maximumStepLength > 0f && maximumStepLength.isFinite()) {
        "Maximum exploration step length must be finite and positive."
    }

    val orderedRails = mutableListOf(originRail)
    val continuation = mutableListOf<PetRailJourneyStep>()
    if (
        maxExtraStops == 0 ||
        start.x !in originRail.bounds.left..originRail.bounds.right ||
        abs(start.y - originRail.bounds.top) > PET_ROUTE_EPSILON
    ) {
        return PetRailExplorationPlan(orderedRails, continuation)
    }

    val remaining = candidateRails.asSequence()
        .filterNot { it.perchKey == originRail.perchKey }
        .filter { candidate ->
            mode == PetRailExplorationMode.AnyDirection ||
                candidate.bounds.top < originRail.bounds.top - PET_ROUTE_EPSILON
        }
        .distinctBy { it.key }
        .toMutableList()
    var currentRail = originRail
    var currentPoint = start

    while (continuation.size < maxExtraStops) {
        val next = remaining.asSequence()
            .filter { candidate ->
                mode == PetRailExplorationMode.AnyDirection ||
                    candidate.bounds.top < currentPoint.y - PET_ROUTE_EPSILON
            }
            .mapNotNull { candidate ->
                val step = planPetRailJourney(
                    startRail = currentRail,
                    start = currentPoint,
                    targetRail = candidate,
                    rails = emptyList(),
                    bounds = bounds,
                    uiObstacles = uiObstacles,
                    footprint = footprint,
                    maximumStepLength = maximumStepLength,
                )?.singleOrNull() ?: return@mapNotNull null
                step.takeIf {
                    it.route.destination.x in candidate.bounds.left..candidate.bounds.right &&
                        abs(it.route.destination.y - candidate.bounds.top) <= PET_ROUTE_EPSILON
                }
            }
            .sortedWith(
                if (mode == PetRailExplorationMode.Ascending) {
                    compareByDescending<PetRailJourneyStep> { it.rail.bounds.top }
                        .thenBy { it.route.length }
                        .thenBy { it.rail.key }
                } else {
                    compareBy<PetRailJourneyStep> {
                        abs(it.rail.bounds.top - currentPoint.y)
                    }
                        .thenBy { it.route.length }
                        .thenBy { it.rail.bounds.top }
                        .thenBy { it.rail.key }
                },
            )
            .firstOrNull()
            ?: break

        continuation += next
        orderedRails += next.rail
        currentRail = next.rail
        currentPoint = next.route.destination
        remaining.removeAll { it.perchKey == next.rail.perchKey }
    }

    return PetRailExplorationPlan(orderedRails, continuation)
}

/** Chat-specific naming wrapper over the generic ascending rail tour planner. */
fun planPetBubbleExploration(
    newestRail: PetRoamingRail,
    start: PetPoint,
    visibleMessageRails: Iterable<PetRoamingRail>,
    bounds: PetSafeBounds,
    uiObstacles: Iterable<PetObstacle>,
    footprint: PetFootprint,
    maximumStepLength: Float,
    maxExtraStops: Int,
): PetBubbleExplorationPlan = planPetRailExploration(
    originRail = newestRail,
    start = start,
    candidateRails = visibleMessageRails,
    bounds = bounds,
    uiObstacles = uiObstacles,
    footprint = footprint,
    maximumStepLength = maximumStepLength,
    maxExtraStops = maxExtraStops,
)

/**
 * Debug-only connectivity graph for the currently measured terrain. Every
 * returned edge is an exact-endpoint, collision-checked direct transfer within
 * [maximumRouteLength]. It describes possible movement, not planner selection.
 */
fun planPetDebugRouteGraph(
    rails: Iterable<PetRoamingRail>,
    bounds: PetSafeBounds,
    uiObstacles: Iterable<PetObstacle>,
    footprint: PetFootprint,
    maximumRouteLength: Float,
): List<PetRoute> {
    require(maximumRouteLength > 0f && maximumRouteLength.isFinite()) {
        "Maximum debug route length must be finite and positive."
    }
    val ordered = rails.distinctBy(PetRoamingRail::key)
        .sortedWith(compareBy<PetRoamingRail> { it.bounds.top }.thenBy { it.key })
    return buildList {
        for (firstIndex in ordered.indices) {
            for (secondIndex in (firstIndex + 1) until ordered.size) {
                val first = ordered[firstIndex]
                val second = ordered[secondIndex]
                if (abs(first.bounds.top - second.bounds.top) <= PET_ROUTE_EPSILON) {
                    continue
                }
                val route = petRailTransferXOptions(first, second).firstNotNullOfOrNull {
                        (firstX, secondX) ->
                    val start = PetPoint(firstX, first.bounds.top)
                    val destination = PetPoint(secondX, second.bounds.top)
                    val candidateRoute = findExactOverlayRoute(
                        start = start,
                        requestedDestination = destination,
                        bounds = bounds,
                        uiObstacles = uiObstacles,
                        footprint = footprint,
                    ) ?: return@firstNotNullOfOrNull null
                    val exactEndpointTolerance = PET_ROUTE_EPSILON * PET_ROUTE_EPSILON
                    if (candidateRoute.start.distanceSquaredTo(start) > exactEndpointTolerance) {
                        return@firstNotNullOfOrNull null
                    }
                    if (
                        candidateRoute.destination.distanceSquaredTo(destination) >
                        exactEndpointTolerance
                    ) return@firstNotNullOfOrNull null
                    candidateRoute.takeIf {
                        it.length <= maximumRouteLength + PET_ROUTE_EPSILON
                    }
                }
                if (route != null) add(route)
            }
        }
    }
}

/**
 * Route between sibling segments without entering the UI surface below them.
 * The generic visibility router remains the source of collision truth; this
 * wrapper clips its search space to the half-plane at or above both rails.
 */
fun findAbovePerchRoute(
    start: PetPoint,
    requestedDestination: PetPoint,
    bounds: PetSafeBounds,
    uiObstacles: Iterable<PetObstacle>,
    footprint: PetFootprint,
): PetRoute? {
    val perchTop = minOf(start.y, requestedDestination.y)
    if (perchTop < bounds.top) return null
    return findExactOverlayRoute(
        start = start,
        requestedDestination = requestedDestination,
        bounds = PetSafeBounds(bounds.left, bounds.top, bounds.right, perchTop),
        uiObstacles = uiObstacles,
        footprint = footprint,
    )
}

/**
 * Pick the nearest collision-free transfer with stable key ordering.
 *
 * Different measured perches retain Hermes Desktop's horizontal-overlap rule.
 * Sibling segments may cross their explicitly registered obstacle, but only by
 * a route constrained above the shared perch.
 */
fun choosePetRailTransfer(
    currentRail: PetRoamingRail,
    current: PetPoint,
    rails: Iterable<PetRoamingRail>,
    bounds: PetSafeBounds,
    uiObstacles: Iterable<PetObstacle>,
    footprint: PetFootprint,
    maximumRouteLength: Float = Float.POSITIVE_INFINITY,
): PetRailTransfer? {
    require(maximumRouteLength > 0f && !maximumRouteLength.isNaN()) {
        "Maximum transfer length must be positive."
    }
    return rails.asSequence()
    .filter { it.key != currentRail.key }
    .mapNotNull { candidate ->
        val samePerch = candidate.perchKey == currentRail.perchKey
        val destinationX = if (samePerch) {
            current.x.coerceIn(candidate.bounds.left, candidate.bounds.right)
        } else {
            val overlapLeft = maxOf(candidate.bounds.left, currentRail.bounds.left)
            val overlapRight = minOf(candidate.bounds.right, currentRail.bounds.right)
            if (overlapLeft > overlapRight) return@mapNotNull null
            current.x.coerceIn(overlapLeft, overlapRight)
        }
        val destination = PetPoint(destinationX, candidate.bounds.top)
        val route = if (samePerch) {
            findAbovePerchRoute(current, destination, bounds, uiObstacles, footprint)
        } else {
            findExactOverlayRoute(current, destination, bounds, uiObstacles, footprint)
        } ?: return@mapNotNull null
        if (route.length > maximumRouteLength + PET_ROUTE_EPSILON) return@mapNotNull null
        PetRailTransfer(candidate, destinationX, samePerch, route)
    }
    .sortedWith(
        compareBy<PetRailTransfer> { it.route.destination.distanceSquaredTo(current) }
            .thenBy { it.rail.key },
    )
    .firstOrNull()
}

/**
 * Clamp [requested] into [bounds], then move it to the nearest unblocked point.
 * Returns null when obstacles cover every candidate coordinate in the safe area.
 */
fun projectIntoSafeBounds(
    requested: PetPoint,
    bounds: PetSafeBounds,
    obstacles: List<PetObstacle>,
): PetPoint? {
    val clamped = bounds.clamp(requested)
    if (obstacles.none { it.contains(clamped) }) return clamped

    val epsilon = 0.001f
    val xCandidates = buildSet {
        add(clamped.x)
        add(bounds.left)
        add(bounds.right)
        obstacles.forEach { obstacle ->
            add((obstacle.left - epsilon).coerceIn(bounds.left, bounds.right))
            add((obstacle.right + epsilon).coerceIn(bounds.left, bounds.right))
        }
    }
    val yCandidates = buildSet {
        add(clamped.y)
        add(bounds.top)
        add(bounds.bottom)
        obstacles.forEach { obstacle ->
            add((obstacle.top - epsilon).coerceIn(bounds.top, bounds.bottom))
            add((obstacle.bottom + epsilon).coerceIn(bounds.top, bounds.bottom))
        }
    }

    return xCandidates.asSequence()
        .flatMap { x -> yCandidates.asSequence().map { y -> PetPoint(x, y) } }
        .filter(bounds::contains)
        .filter { point -> obstacles.none { it.contains(point) } }
        .minWithOrNull(
            compareBy<PetPoint> { it.distanceSquaredTo(clamped) }
                .thenBy { it.x }
                .thenBy { it.y },
        )
}

fun pathIntersectsObstacle(
    start: PetPoint,
    end: PetPoint,
    obstacles: List<PetObstacle>,
): Boolean = obstacles.any { it.intersectsSegment(start, end) }

/**
 * Select a reachable waypoint with a stable seed. Sorting first makes the
 * result independent of caller collection order.
 */
fun chooseDeterministicWaypoint(
    current: PetPoint,
    candidates: Iterable<PetPoint>,
    bounds: PetSafeBounds,
    obstacles: List<PetObstacle>,
    seed: Long,
): PetPoint? {
    val reachable = candidates.asSequence()
        .filter(bounds::contains)
        .filter { candidate -> candidate.distanceSquaredTo(current) > 0.0001f }
        .filter { candidate -> obstacles.none { it.contains(candidate) } }
        .filter { candidate -> !pathIntersectsObstacle(current, candidate, obstacles) }
        .distinct()
        .sortedWith(compareBy<PetPoint> { it.x }.thenBy { it.y })
        .toList()
    if (reachable.isEmpty()) return null
    return reachable[stableIndex(seed, reachable.size)]
}

/**
 * Find a shortest collision-free polyline through a full-screen overlay.
 * Registered UI bounds are expanded by the pet hit target, so callers register
 * the real control bounds rather than reserving an empty composable strip.
 *
 * If start/destination became invalid after an inset or obstacle update, each
 * is first projected to the nearest available center point. The returned route
 * therefore begins at the safe point the host should snap/animate from.
 */
fun findOverlayRoute(
    start: PetPoint,
    requestedDestination: PetPoint,
    bounds: PetSafeBounds,
    uiObstacles: Iterable<PetObstacle>,
    footprint: PetFootprint,
): PetRoute? {
    val obstacles = expandObstaclesForPet(uiObstacles, footprint)
    val safeStart = projectIntoSafeBounds(start, bounds, obstacles) ?: return null
    val safeDestination = projectIntoSafeBounds(requestedDestination, bounds, obstacles) ?: return null
    if (safeStart == safeDestination) return PetRoute(listOf(safeStart))
    if (!pathIntersectsObstacle(safeStart, safeDestination, obstacles)) {
        return PetRoute(listOf(safeStart, safeDestination))
    }

    val epsilon = 0.01f
    val vertices = buildList {
        add(safeStart)
        add(safeDestination)
        add(PetPoint(bounds.left, bounds.top))
        add(PetPoint(bounds.right, bounds.top))
        add(PetPoint(bounds.left, bounds.bottom))
        add(PetPoint(bounds.right, bounds.bottom))
        obstacles.forEach { obstacle ->
            add(PetPoint(obstacle.left - epsilon, obstacle.top - epsilon))
            add(PetPoint(obstacle.right + epsilon, obstacle.top - epsilon))
            add(PetPoint(obstacle.left - epsilon, obstacle.bottom + epsilon))
            add(PetPoint(obstacle.right + epsilon, obstacle.bottom + epsilon))
        }
    }.asSequence()
        .map(bounds::clamp)
        .filter(bounds::contains)
        .filter { point -> obstacles.none { it.contains(point) } }
        .distinct()
        .toList()

    val startIndex = vertices.indexOf(safeStart)
    val destinationIndex = vertices.indexOf(safeDestination)
    if (startIndex < 0 || destinationIndex < 0) return null

    val distances = FloatArray(vertices.size) { Float.POSITIVE_INFINITY }
    val previous = IntArray(vertices.size) { -1 }
    val visited = BooleanArray(vertices.size)
    distances[startIndex] = 0f

    repeat(vertices.size) {
        val currentIndex = vertices.indices
            .filterNot { visited[it] }
            .minByOrNull { distances[it] }
            ?.takeIf { distances[it].isFinite() }
            ?: return@repeat
        if (currentIndex == destinationIndex) return@repeat
        visited[currentIndex] = true
        val current = vertices[currentIndex]
        vertices.indices.forEach { nextIndex ->
            if (nextIndex == currentIndex || visited[nextIndex]) return@forEach
            val next = vertices[nextIndex]
            if (pathIntersectsObstacle(current, next, obstacles)) return@forEach
            val candidateDistance = distances[currentIndex] + sqrt(current.distanceSquaredTo(next))
            if (candidateDistance < distances[nextIndex]) {
                distances[nextIndex] = candidateDistance
                previous[nextIndex] = currentIndex
            }
        }
    }
    if (!distances[destinationIndex].isFinite()) return null

    val reversed = mutableListOf<PetPoint>()
    var cursor = destinationIndex
    while (cursor >= 0) {
        reversed += vertices[cursor]
        if (cursor == startIndex) break
        cursor = previous[cursor]
    }
    if (reversed.lastOrNull() != safeStart) return null
    return PetRoute(removeCollinearPoints(reversed.asReversed()))
}

/**
 * Collision-safe route for autonomous motion. Unlike [findOverlayRoute], this
 * rejects endpoint projection: a blocked live point or requested ledge means
 * the pet waits for a later terrain snapshot instead of moving to invented
 * geometry.
 */
fun findExactOverlayRoute(
    start: PetPoint,
    requestedDestination: PetPoint,
    bounds: PetSafeBounds,
    uiObstacles: Iterable<PetObstacle>,
    footprint: PetFootprint,
): PetRoute? {
    val route = findOverlayRoute(
        start = start,
        requestedDestination = requestedDestination,
        bounds = bounds,
        uiObstacles = uiObstacles,
        footprint = footprint,
    ) ?: return null
    val toleranceSquared = PET_ROUTE_EPSILON * PET_ROUTE_EPSILON
    if (route.start.distanceSquaredTo(start) > toleranceSquared) return null
    if (route.destination.distanceSquaredTo(requestedDestination) > toleranceSquared) return null
    return route
}

/**
 * Shortest bounded egress for a pet that layout has already placed inside an
 * expanded obstacle. The segment may cross only obstacles containing [start];
 * it never becomes autonomous route eligibility.
 */
fun findLocalPetEscapeRoute(
    start: PetPoint,
    bounds: PetSafeBounds,
    uiObstacles: Iterable<PetObstacle>,
    footprint: PetFootprint,
    maximumLength: Float,
): PetRoute? {
    require(maximumLength > 0f && maximumLength.isFinite()) {
        "Maximum escape length must be finite and positive."
    }
    val expanded = expandObstaclesForPet(uiObstacles, footprint)
    val containing = expanded.filter { it.contains(start) }
    if (bounds.contains(start) && containing.isEmpty()) return null
    val epsilon = PET_ROUTE_EPSILON
    val candidates = buildList {
        val clamped = bounds.clamp(start)
        if (clamped != start) add(clamped)
        containing.forEach { obstacle ->
            add(PetPoint(obstacle.left - epsilon, start.y))
            add(PetPoint(obstacle.right + epsilon, start.y))
            add(PetPoint(start.x, obstacle.top - epsilon))
            add(PetPoint(start.x, obstacle.bottom + epsilon))
        }
    }.asSequence()
        .map(bounds::clamp)
        .filter { it.distanceSquaredTo(start) > PET_ROUTE_EPSILON * PET_ROUTE_EPSILON }
        .filter { candidate -> expanded.none { it.contains(candidate) } }
        .filter { candidate ->
            expanded.asSequence()
                .filterNot { it in containing }
                .none { it.intersectsSegment(start, candidate) }
        }
        .filter { candidate -> sqrt(candidate.distanceSquaredTo(start)) <= maximumLength + epsilon }
        .distinct()
        .sortedWith(compareBy<PetPoint> { it.distanceSquaredTo(start) }.thenBy { it.x }.thenBy { it.y })
        .toList()
    return candidates.firstOrNull()?.let { destination -> PetRoute(listOf(start, destination)) }
}

/**
 * Find a reachable place for the pet to visit beside a measured message
 * bubble without turning that bubble into walkable terrain. Above-corner
 * anchors are preferred, followed by side anchors; within each tier the
 * shortest real overlay route wins.
 *
 * The bubble joins the obstacle set while routing, and every candidate lies
 * just beyond its footprint-expanded bounds. A candidate that the generic
 * router would need to project elsewhere is treated as blocked.
 */
fun findBubbleVisitRoute(
    targetBounds: PetObstacle,
    footprint: PetFootprint,
    bounds: PetSafeBounds,
    uiObstacles: Iterable<PetObstacle>,
    current: PetPoint,
): PetRoute? {
    val expandedTarget = targetBounds.expanded(
        footprint.horizontalRadius,
        footprint.verticalRadius,
    )
    val epsilon = PET_ROUTE_EPSILON
    val centerY = (targetBounds.top + targetBounds.bottom) / 2f
    val candidates = listOf(
        0 to PetPoint(targetBounds.left, expandedTarget.top - epsilon),
        0 to PetPoint(targetBounds.right, expandedTarget.top - epsilon),
        1 to PetPoint(expandedTarget.left - epsilon, centerY),
        1 to PetPoint(expandedTarget.right + epsilon, centerY),
    )
    val routingObstacles = uiObstacles.toList() + targetBounds

    return candidates.asSequence()
        .filter { (_, candidate) -> bounds.contains(candidate) }
        .filter { (_, candidate) -> !expandedTarget.contains(candidate) }
        .mapNotNull { (preference, candidate) ->
            val route = findExactOverlayRoute(
                start = current,
                requestedDestination = candidate,
                bounds = bounds,
                uiObstacles = routingObstacles,
                footprint = footprint,
            ) ?: return@mapNotNull null
            if (route.destination != candidate) return@mapNotNull null
            BubbleVisitCandidate(preference, route)
        }
        .sortedWith(
            compareBy<BubbleVisitCandidate> { it.preference }
                .thenBy { it.route.length }
                .thenBy { it.route.destination.x }
                .thenBy { it.route.destination.y },
        )
        .firstOrNull()
        ?.route
}

private data class BubbleVisitCandidate(
    val preference: Int,
    val route: PetRoute,
)

val PetRoute.length: Float
    get() = points.zipWithNext().sumOf { (start, end) ->
        sqrt(start.distanceSquaredTo(end)).toDouble()
    }.toFloat()

/** Long horizontal walks are allowed; every route with vertical travel is step-capped. */
fun petRouteFitsStepLimit(route: PetRoute, maximumStepLength: Float): Boolean {
    require(maximumStepLength > 0f && maximumStepLength.isFinite()) {
        "Maximum route step length must be finite and positive."
    }
    val containsVerticalSegment = route.points.zipWithNext().any { (start, end) ->
        abs(end.y - start.y) > PET_ROUTE_EPSILON
    }
    return !containsVerticalSegment || route.length <= maximumStepLength + PET_ROUTE_EPSILON
}

/** Deterministically choose among destinations that have a real safe route. */
fun chooseDeterministicOverlayRoute(
    current: PetPoint,
    candidates: Iterable<PetPoint>,
    bounds: PetSafeBounds,
    uiObstacles: Iterable<PetObstacle>,
    footprint: PetFootprint,
    seed: Long,
): PetRoute? {
    val routes = candidates.asSequence()
        .distinct()
        .mapNotNull { findExactOverlayRoute(current, it, bounds, uiObstacles, footprint) }
        .filter { it.destination.distanceSquaredTo(it.start) > 0.0001f }
        .sortedWith(compareBy<PetRoute> { it.destination.x }.thenBy { it.destination.y })
        .toList()
    if (routes.isEmpty()) return null
    return routes[stableIndex(seed, routes.size)]
}

private fun removeCollinearPoints(points: List<PetPoint>): List<PetPoint> {
    if (points.size < 3) return points
    val result = mutableListOf(points.first())
    for (index in 1 until points.lastIndex) {
        val previous = result.last()
        val current = points[index]
        val next = points[index + 1]
        val cross = (current.x - previous.x) * (next.y - current.y) -
            (current.y - previous.y) * (next.x - current.x)
        if (abs(cross) > 0.0001f) result += current
    }
    result += points.last()
    return result
}

private fun stableIndex(seed: Long, size: Int): Int {
    var value = seed + (-7046029254386353131L)
    value = (value xor (value ushr 30)) * (-4658895280553007687L)
    value = (value xor (value ushr 27)) * (-7723592293110705685L)
    value = value xor (value ushr 31)
    return ((value and Long.MAX_VALUE) % size).toInt()
}

private fun Float.normalizedFraction(): Float =
    if (isFinite()) coerceIn(0f, 1f) else 0f

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

private fun valuesAreFinite(vararg values: Float): Boolean = values.all { it.isFinite() }
