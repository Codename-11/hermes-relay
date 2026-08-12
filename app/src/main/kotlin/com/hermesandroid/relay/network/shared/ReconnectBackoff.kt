package com.hermesandroid.relay.network.shared

import kotlin.random.Random

/** Full-jitter retry delay in the inclusive range 0..[capMs]. */
internal fun fullJitterDelayMs(
    capMs: Long,
    unit: Double = Random.nextDouble(),
): Long {
    if (capMs <= 0L) return 0L
    val boundedUnit = unit.coerceIn(0.0, Math.nextDown(1.0))
    return (boundedUnit * (capMs + 1.0)).toLong().coerceAtMost(capMs)
}
