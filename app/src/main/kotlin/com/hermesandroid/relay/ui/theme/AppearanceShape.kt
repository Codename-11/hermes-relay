package com.hermesandroid.relay.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class AppearanceShape(val id: String) {
    SOFT("soft"),
    BALANCED("balanced"),
    SHARP("sharp");

    companion object {
        val DEFAULT = SOFT

        fun fromId(id: String?): AppearanceShape = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * One shape scale for both Material components and Hermes-owned surfaces.
 *
 * [rounded] accepts the radius that shipped in the Soft design and scales it
 * for Balanced or Sharp. This retains component hierarchy without maintaining
 * a second set of unrelated per-component radii.
 */
@Immutable
data class AppearanceShapeScale(
    val mode: AppearanceShape,
    val extraSmall: Dp,
    val small: Dp,
    val medium: Dp,
    val large: Dp,
    val extraLarge: Dp,
) {
    fun radius(softRadius: Dp): Dp = when (mode) {
        AppearanceShape.SOFT -> softRadius
        AppearanceShape.BALANCED -> softRadius * (2f / 3f)
        AppearanceShape.SHARP -> softRadius / 3f
    }

    /**
     * The composer is a persistent interaction control, not a content card.
     * Keep its silhouette stable while still acknowledging the selected mode.
     */
    fun composerRadius(): Dp = when (mode) {
        AppearanceShape.SOFT -> 20.dp
        AppearanceShape.BALANCED -> 18.dp
        AppearanceShape.SHARP -> 16.dp
    }

    fun rounded(softRadius: Dp): Shape = RoundedCornerShape(radius(softRadius))

    fun rounded(
        topStart: Dp,
        topEnd: Dp,
        bottomEnd: Dp,
        bottomStart: Dp,
    ): Shape = RoundedCornerShape(
        topStart = radius(topStart),
        topEnd = radius(topEnd),
        bottomEnd = radius(bottomEnd),
        bottomStart = radius(bottomStart),
    )

    fun asMaterialShapes(): Shapes = Shapes(
        extraSmall = RoundedCornerShape(extraSmall),
        small = RoundedCornerShape(small),
        medium = RoundedCornerShape(medium),
        large = RoundedCornerShape(large),
        extraLarge = RoundedCornerShape(extraLarge),
    )
}

fun appearanceShapeScale(shapeId: String?): AppearanceShapeScale = when (val mode = AppearanceShape.fromId(shapeId)) {
    AppearanceShape.SOFT -> AppearanceShapeScale(mode, 8.dp, 12.dp, 18.dp, 24.dp, 30.dp)
    AppearanceShape.BALANCED -> AppearanceShapeScale(mode, 5.dp, 8.dp, 12.dp, 16.dp, 20.dp)
    AppearanceShape.SHARP -> AppearanceShapeScale(mode, 2.dp, 4.dp, 6.dp, 8.dp, 10.dp)
}

fun appearanceShapes(shapeId: String?): Shapes = appearanceShapeScale(shapeId).asMaterialShapes()

val LocalAppearanceShapeScale = staticCompositionLocalOf {
    appearanceShapeScale(AppearanceShape.DEFAULT.id)
}

@Composable
@ReadOnlyComposable
fun appearanceRoundedCornerShape(softRadius: Dp): Shape =
    LocalAppearanceShapeScale.current.rounded(softRadius)

@Composable
@ReadOnlyComposable
fun appearanceComposerShape(): Shape =
    RoundedCornerShape(LocalAppearanceShapeScale.current.composerRadius())

@Composable
@ReadOnlyComposable
fun appearanceTopRoundedCornerShape(softRadius: Dp): Shape {
    val radius = LocalAppearanceShapeScale.current.radius(softRadius)
    return RoundedCornerShape(topStart = radius, topEnd = radius)
}
