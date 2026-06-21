package com.hermesandroid.relay.ui.components

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

/**
 * User-authored sphere spec — the on-disk JSON format for side-loaded sphere
 * skins. Pure data: no code, no expressions. A spec is parsed, validated, and
 * converted to a [SphereSkin] by [toSkin]; see `docs/sphere-spec.md` for the
 * authoring reference.
 *
 * The format is deliberately forgiving — any state you omit falls back to
 * [defaults], then to the built-in Classic look, so a minimal spec is valid.
 *
 * Example:
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "id": "my-orb",
 *   "label": "My Orb",
 *   "description": "A custom sphere",
 *   "reactive": { "voice": true, "tools": true, "intensity": true, "gaze": false },
 *   "defaults": { "color1": "#7FE9DE", "color2": "#8C5CFF" },
 *   "states": {
 *     "error": { "color1": "#FF6B78", "color2": "#F2B14B" }
 *   }
 * }
 * ```
 */
@Serializable
data class SphereSpec(
    val schemaVersion: Int = 1,
    val id: String = "",
    val label: String = "",
    val description: String = "",
    val reactive: ReactiveSpec = ReactiveSpec(),
    /** Keyed by state name (idle/thinking/streaming/listening/speaking/error). */
    val states: Map<String, StateSpec> = emptyMap(),
    /** Fallback colors/params for any state not listed in [states]. */
    val defaults: StateSpec? = null,
)

@Serializable
data class ReactiveSpec(
    val voice: Boolean = true,
    val tools: Boolean = true,
    val intensity: Boolean = true,
    val gaze: Boolean = false,
)

@Serializable
data class StateSpec(
    /** Hex `#RRGGBB` or `#AARRGGBB` (alpha ignored — sphere alpha is computed). */
    val color1: String = "",
    val color2: String = "",
    val params: ParamSpec? = null,
)

/** All optional; unset fields inherit the built-in per-state defaults. */
@Serializable
data class ParamSpec(
    val breatheSpeed: Float? = null,
    val breatheAmp: Float? = null,
    val lightSpeedX: Float? = null,
    val lightSpeedY: Float? = null,
    val lightInfluence: Float? = null,
    val coreTightness: Float? = null,
    val turbulenceAmp: Float? = null,
    val rippleScale: Float? = null,
    val heartbeatSpeed: Float? = null,
    val radialFlowSpeed: Float? = null,
)

/** The schema versions this build understands. */
const val SPHERE_SPEC_SCHEMA_VERSION = 1

/**
 * Convert a parsed [SphereSpec] into a [SphereSkin]. Throws
 * [IllegalArgumentException] with a human-readable reason for any spec that
 * can't be honored (unsupported schema, blank id/label, no usable colors).
 */
fun SphereSpec.toSkin(): SphereSkin {
    require(schemaVersion in 1..SPHERE_SPEC_SCHEMA_VERSION) {
        "unsupported schemaVersion $schemaVersion (this build supports up to $SPHERE_SPEC_SCHEMA_VERSION)"
    }
    require(id.isNotBlank()) { "missing id" }
    val resolvedLabel = label.ifBlank { id }

    val stateColors = mutableMapOf<SphereState, SphereColors>()
    val stateParams = mutableMapOf<SphereState, SphereParams>()

    for (state in SphereState.entries) {
        val spec = states[state.specName] ?: defaults
        if (spec != null) {
            val c1 = parseHexColorOrNull(spec.color1)
            val c2 = parseHexColorOrNull(spec.color2)
            if (c1 != null && c2 != null) {
                stateColors[state] = poles(c1, c2)
            }
            spec.params?.let { stateParams[state] = it.applyOver(paramsFor(state)) }
        }
    }

    require(stateColors.isNotEmpty() || defaults == null) {
        "no valid hex colors found in any state"
    }

    return SphereSkin(
        id = id,
        label = resolvedLabel,
        description = description,
        source = SphereSkinSource.USER,
        reactivity = SphereReactivity(
            voice = reactive.voice,
            tools = reactive.tools,
            intensity = reactive.intensity,
            gaze = reactive.gaze,
        ),
        adaptive = false,
        stateColors = stateColors,
        stateParams = stateParams,
    )
}

/** State name as it appears in JSON. */
private val SphereState.specName: String
    get() = name.lowercase()

/** Merge a sparse [ParamSpec] over the base params, clamping to safe ranges. */
private fun ParamSpec.applyOver(base: SphereParams): SphereParams = SphereParams(
    breatheSpeed = (breatheSpeed ?: base.breatheSpeed).coerceIn(0f, 5f),
    breatheAmp = (breatheAmp ?: base.breatheAmp).coerceIn(0f, 0.5f),
    lightSpeedX = (lightSpeedX ?: base.lightSpeedX).coerceIn(0f, 3f),
    lightSpeedY = (lightSpeedY ?: base.lightSpeedY).coerceIn(0f, 3f),
    lightInfluence = (lightInfluence ?: base.lightInfluence).coerceIn(0f, 1f),
    coreTightness = (coreTightness ?: base.coreTightness).coerceIn(0f, 1f),
    turbulenceAmp = (turbulenceAmp ?: base.turbulenceAmp).coerceIn(0f, 1f),
    rippleScale = (rippleScale ?: base.rippleScale).coerceIn(0f, 5f),
    heartbeatSpeed = (heartbeatSpeed ?: base.heartbeatSpeed).coerceIn(0f, 12f),
    radialFlowSpeed = (radialFlowSpeed ?: base.radialFlowSpeed).coerceIn(0f, 3f),
)

/** Parse `#RRGGBB`, `#AARRGGBB`, or the same without `#`. Returns null if invalid. */
internal fun parseHexColorOrNull(raw: String): Color? {
    val hex = raw.trim().removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    val value = hex.toLongOrNull(16) ?: return null
    return when (hex.length) {
        6 -> Color(0xFF000000L or value)
        else -> Color(value)
    }
}
