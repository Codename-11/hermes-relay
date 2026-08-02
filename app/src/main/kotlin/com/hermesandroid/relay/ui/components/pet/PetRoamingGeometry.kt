package com.hermesandroid.relay.ui.components.pet

import kotlin.math.abs
import kotlin.math.sqrt

private const val PET_ROUTE_EPSILON = 0.001f

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
)

/**
 * One collision-checked chat response excursion. A roomy response uses
 * [gutter]; a phone-width response uses a ballistic [PetBubbleEntryMode.EdgeHop]
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
    val left = (perch.bounds.left + footprint.horizontalRadius).coerceIn(outer.left, outer.right)
    val right = (perch.bounds.right - footprint.horizontalRadius).coerceIn(outer.left, outer.right)
    if (right < left) return null
    val centerY = (perch.bounds.top - footprint.height / 2f - verticalClearance)
        .coerceIn(outer.top, outer.bottom)
    return PetSafeBounds(left, centerY, right, centerY)
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
    val centerY = (perch.bounds.top - footprint.verticalRadius - verticalClearance)
        .coerceIn(outer.top, outer.bottom)
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

data class PetRoute(val points: List<PetPoint>) {
    init {
        require(points.isNotEmpty()) { "A pet route needs at least one point." }
    }

    val start: PetPoint get() = points.first()
    val destination: PetPoint get() = points.last()
}

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
    var currentPoint = start
    var currentRail = startRail
    val remaining = candidates.toMutableList()
    val journey = mutableListOf<PetRailJourneyStep>()

    repeat(candidates.size + 1) {
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
            val destination = PetPoint(
                x = currentPoint.x.coerceIn(candidate.bounds.left, candidate.bounds.right),
                y = candidateY,
            )
            val route = findOverlayRoute(
                start = currentPoint,
                requestedDestination = destination,
                bounds = bounds,
                uiObstacles = uiObstacles,
                footprint = footprint,
            ) ?: return@mapNotNull null
            if (route.start.distanceSquaredTo(currentPoint) > 1f) return@mapNotNull null
            if (route.length > maximumStepLength + PET_ROUTE_EPSILON) return@mapNotNull null
            PetRailJourneyStep(candidate, route)
        }
        val next = options.minWithOrNull(
            compareBy<PetRailJourneyStep> {
                if (ascending) it.rail.bounds.top else -it.rail.bounds.top
            }.thenBy { it.route.length }
                .thenBy { it.rail.key },
        ) ?: return null
        journey += next
        currentPoint = next.route.destination
        currentRail = next.rail
        remaining.removeAll { it.key == currentRail.key }
        if (currentRail.key == targetRail.key) return journey
    }
    return null
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
    return findOverlayRoute(
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
            findOverlayRoute(current, destination, bounds, uiObstacles, footprint)
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
            val route = findOverlayRoute(
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
        .mapNotNull { findOverlayRoute(current, it, bounds, uiObstacles, footprint) }
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
