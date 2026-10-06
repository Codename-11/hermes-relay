package com.hermesandroid.relay.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring

/**
 * Material 3 Expressive spring tokens on the stable Material library.
 *
 * material3 1.4.0 keeps `MotionScheme` internal; it is public only in the
 * 1.5.0 alphas, which also pull Compose 1.13 alphas. Until that ships stable,
 * Clean surfaces use these springs directly: bouncier spatial springs for
 * position and size, critically damped effects springs for color and alpha
 * so fades never overshoot.
 */
object ExpressiveMotion {
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)

    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)

    fun <T> defaultEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)

    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)
}
