package com.hermesandroid.relay.ui.theme

import androidx.compose.ui.graphics.Color
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val PROFILE_SATURATION = 0.68f
private const val PROFILE_LIGHTNESS = 0.58f

/** The same evenly-spaced 12-hue picker model used by Hermes Desktop. */
val ProfileAccentSwatches: List<String> = (0 until 12).map { index ->
    hslColor(index * 30f, PROFILE_SATURATION, PROFILE_LIGHTNESS).toRgbHex()
}

/** Desktop-compatible deterministic profile identity color; default remains neutral. */
fun resolveProfileAccent(name: String?, overrides: Map<String, String>): Color? {
    val key = name?.trim().orEmpty()
    if (key.isBlank() || key.equals("default", ignoreCase = true)) return null
    return accentColor(overrides[key]) ?: deterministicProfileAccent(key)
}

internal fun deterministicProfileAccent(name: String): Color {
    var hash = 0u
    name.forEach { character -> hash = hash * 31u + character.code.toUInt() }
    return hslColor((hash % 360u).toFloat(), PROFILE_SATURATION, PROFILE_LIGHTNESS)
}

private fun hslColor(hue: Float, saturation: Float, lightness: Float): Color {
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val section = (hue / 60f) % 6f
    val x = chroma * (1f - abs(section % 2f - 1f))
    val (red, green, blue) = when {
        section < 1f -> Triple(chroma, x, 0f)
        section < 2f -> Triple(x, chroma, 0f)
        section < 3f -> Triple(0f, chroma, x)
        section < 4f -> Triple(0f, x, chroma)
        section < 5f -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    val match = lightness - chroma / 2f
    return Color(red + match, green + match, blue + match)
}

private fun Color.toRgbHex(): String = String.format(
    Locale.ROOT,
    "#%02X%02X%02X",
    (red * 255f).roundToInt(),
    (green * 255f).roundToInt(),
    (blue * 255f).roundToInt(),
)
