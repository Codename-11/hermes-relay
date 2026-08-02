package com.hermesandroid.relay.ui.components.avatar

import com.hermesandroid.relay.ui.components.SphereState

/** Stable preview actions backed by the same renderer inputs used by the overlay. */
enum class PetPreviewAction {
    Idle,
    WalkLeft,
    WalkRight,
    Jump,
    Fall,
    Held,
    Wave,
    Working,
    Review,
    Waiting,
    Error,
}

/** How honestly the installed pack can show a requested preview action. */
enum class PetPreviewSupport {
    Direct,
    Mirrored,
    Fallback,
    MirroredFallback,
}

/**
 * One interactive preview choice. [renderState] goes straight into [PetAvatar.Render],
 * while [sourceKey] and [support] describe the exact clip selection it will make.
 */
data class PetPreviewMapping(
    val action: PetPreviewAction,
    val renderState: AvatarRenderState,
    val sourceKey: String,
    val support: PetPreviewSupport,
)

private data class PetPreviewDefinition(
    val action: PetPreviewAction,
    val renderState: AvatarRenderState,
    val directSourceKeys: Set<String>,
)

private val PET_PREVIEW_DEFINITIONS = listOf(
    PetPreviewDefinition(
        PetPreviewAction.Idle,
        AvatarRenderState(SphereState.Idle),
        setOf("idle"),
    ),
    PetPreviewDefinition(
        PetPreviewAction.WalkLeft,
        AvatarRenderState(
            SphereState.Idle,
            petLocomotion = PetLocomotion.WalkLeft,
        ),
        setOf("walking-left", "walk-left", "running-left", "run-left"),
    ),
    PetPreviewDefinition(
        PetPreviewAction.WalkRight,
        AvatarRenderState(
            SphereState.Idle,
            petLocomotion = PetLocomotion.WalkRight,
        ),
        setOf("walking-right", "walk-right", "running-right", "run-right"),
    ),
    PetPreviewDefinition(
        PetPreviewAction.Jump,
        AvatarRenderState(SphereState.Idle, petLocomotion = PetLocomotion.Jump),
        setOf("jumping", "jump"),
    ),
    PetPreviewDefinition(
        PetPreviewAction.Fall,
        AvatarRenderState(SphereState.Idle, petLocomotion = PetLocomotion.Fall),
        setOf("falling", "fall"),
    ),
    PetPreviewDefinition(
        PetPreviewAction.Held,
        AvatarRenderState(SphereState.Idle, petLocomotion = PetLocomotion.Held),
        setOf("held", "hold"),
    ),
    PetPreviewDefinition(
        PetPreviewAction.Wave,
        AvatarRenderState(SphereState.Idle, petLocomotion = PetLocomotion.Wave),
        setOf("waving", "wave"),
    ),
    PetPreviewDefinition(
        PetPreviewAction.Working,
        AvatarRenderState(SphereState.Streaming, toolCallBurst = 1f),
        setOf("working", "run", "running"),
    ),
    PetPreviewDefinition(
        PetPreviewAction.Review,
        AvatarRenderState(SphereState.Thinking),
        setOf("thinking", "review"),
    ),
    PetPreviewDefinition(
        PetPreviewAction.Waiting,
        AvatarRenderState(SphereState.Listening),
        setOf("listening", "waiting"),
    ),
    PetPreviewDefinition(
        PetPreviewAction.Error,
        AvatarRenderState(SphereState.Error),
        setOf("error", "failed"),
    ),
)

private val PET_NATIVE_DIRECTIONAL_TRAVEL_KEYS = setOf(
    "walking-left",
    "walk-left",
    "running-left",
    "run-left",
    "walking-right",
    "walk-right",
    "running-right",
    "run-right",
)

/**
 * Resolve every preview through [PetAvatar.resolveBaseSelection]. Keeping this as
 * a pure projection means the browser cannot drift from overlay clip priority,
 * directional mirroring, or fallback behavior.
 */
fun PetAvatar.previewMappings(): List<PetPreviewMapping> = PET_PREVIEW_DEFINITIONS.map { definition ->
    val selection = resolveBaseSelection(definition.renderState)
    val sourceKey = selection.sourceKey ?: "idle"
    val sourceIsDirect = sourceKey in definition.directSourceKeys
    val sourceIsNativeDirectionalTravel =
        definition.action in setOf(PetPreviewAction.WalkLeft, PetPreviewAction.WalkRight) &&
            sourceKey in PET_NATIVE_DIRECTIONAL_TRAVEL_KEYS
    val support = when {
        selection.mirrorHorizontally && sourceIsNativeDirectionalTravel -> PetPreviewSupport.Mirrored
        selection.mirrorHorizontally -> PetPreviewSupport.MirroredFallback
        sourceIsDirect -> PetPreviewSupport.Direct
        else -> PetPreviewSupport.Fallback
    }
    PetPreviewMapping(
        action = definition.action,
        renderState = definition.renderState,
        sourceKey = sourceKey,
        support = support,
    )
}

/** Renderer twin for manual state inspection; transient greet/done clips are intentionally absent. */
fun PetAvatar.forCapabilityPreview(): PetAvatar = PetAvatar(
    id = id,
    label = label,
    description = description,
    reactivity = reactivity,
    activityClips = activityClips,
    activityClipSources = activityClipSources,
    workingClip = workingClip,
    workingClipSource = workingClipSource,
    legacyTravelClip = legacyTravelClip,
    legacyTravelClipSource = legacyTravelClipSource,
    locomotionClips = locomotionClips,
    locomotionClipSources = locomotionClipSources,
    oneShots = emptyMap(),
)
