package com.hermesandroid.relay.ui.components.pet

import kotlin.math.abs

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

private fun valuesAreFinite(vararg values: Float): Boolean = values.all(Float::isFinite)
