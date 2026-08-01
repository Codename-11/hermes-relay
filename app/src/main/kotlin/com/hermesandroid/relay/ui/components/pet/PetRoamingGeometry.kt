package com.hermesandroid.relay.ui.components.pet

import kotlin.math.abs
import kotlin.math.sqrt

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
