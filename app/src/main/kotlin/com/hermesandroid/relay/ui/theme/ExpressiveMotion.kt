package com.hermesandroid.relay.ui.theme

import androidx.compose.material3.MotionScheme

/** Official Expressive motion for custom Clean transitions as well as Material components. */
object ExpressiveMotion {
    private val scheme = MotionScheme.expressive()

    fun <T> defaultSpatial() = scheme.defaultSpatialSpec<T>()
    fun <T> fastSpatial() = scheme.fastSpatialSpec<T>()
    fun <T> defaultEffects() = scheme.defaultEffectsSpec<T>()
    fun <T> fastEffects() = scheme.fastEffectsSpec<T>()
}
