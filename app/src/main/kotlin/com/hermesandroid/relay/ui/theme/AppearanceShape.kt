package com.hermesandroid.relay.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
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

fun appearanceShapes(shapeId: String?): Shapes = when (AppearanceShape.fromId(shapeId)) {
    AppearanceShape.SOFT -> Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(30.dp),
    )
    AppearanceShape.BALANCED -> Shapes(
        extraSmall = RoundedCornerShape(5.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(20.dp),
    )
    AppearanceShape.SHARP -> Shapes(
        extraSmall = RoundedCornerShape(2.dp),
        small = RoundedCornerShape(4.dp),
        medium = RoundedCornerShape(6.dp),
        large = RoundedCornerShape(8.dp),
        extraLarge = RoundedCornerShape(10.dp),
    )
}
