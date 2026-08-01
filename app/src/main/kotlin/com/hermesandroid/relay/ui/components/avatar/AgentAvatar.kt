package com.hermesandroid.relay.ui.components.avatar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.hermesandroid.relay.ui.components.SphereReactivity
import com.hermesandroid.relay.ui.components.SphereState

/**
 * Provenance of an [AgentAvatar] — mirrors the skin-level
 * [com.hermesandroid.relay.ui.components.SphereSkinSource] intent one level up.
 * Built-ins ship with the app; user avatars (the "pets" added in C3) load from
 * a user-authored on-disk spec.
 */
enum class AvatarSource { BUILT_IN, USER }

/**
 * Pet-only horizontal travel. This is deliberately separate from
 * [SphereState]: `run`/`running` describes agent work, while these values
 * describe the floating companion moving across the screen.
 */
enum class PetLocomotion {
    None,
    WalkLeft,
    WalkRight,
    RunLeft,
    RunRight,
    Jump,
    Fall,
    Held,
}

/**
 * Per-frame reactive input bundle handed to [AgentAvatar.Render].
 *
 * Deliberately REUSES the sphere's existing vocabulary — the [SphereState] enum
 * plus the same `intensity` / `toolCallBurst` / `voiceAmplitude` / `voiceMode`
 * signals every former `MorphingSphere(...)` call site already supplied — so the
 * call-site swaps are a mechanical 1:1 mapping with no behavior change.
 *
 * @property state agent visual state (idle/thinking/streaming/listening/speaking/error).
 * @property intensity generic activity ramp (turbulence/ripple/flow).
 * @property toolCallBurst tool-call pulse spike, slow decay.
 * @property voiceAmplitude live mic/output amplitude (0..1) in voice mode.
 * @property voiceMode whether a voice session is active (expands/animates the avatar).
 * @property petLocomotion optional pet-only manipulation/travel pose. Pet
 *   rendering resolves animation priority as Held, Jump/Fall, Walk/Run, agent
 *   activity [state], then Idle. Direct manipulation and travel therefore stay
 *   visible even when agent activity changes concurrently.
 * @property paused render a single still frame instead of animating — the
 *   avatar-agnostic reduced-motion signal. The sphere honors it by pinning its
 *   time/color phase; a sprite "pet" (C3) honors it by freezing its clip. This
 *   is what preserves C1's reduced-motion / animations-off static sphere through
 *   the seam.
 */
@Immutable
data class AvatarRenderState(
    val state: SphereState,
    val intensity: Float = 0f,
    val toolCallBurst: Float = 0f,
    val voiceAmplitude: Float = 0f,
    val voiceMode: Boolean = false,
    val petLocomotion: PetLocomotion = PetLocomotion.None,
    val paused: Boolean = false,
)

/**
 * Shared reactive-rendering contract for the ambient Sphere and floating pets.
 * Profile identity images use a separate UI path; implementing this interface
 * does not make a visual the message sender's identity.
 *
 * Implementations are expected to be cheap, stable singletons (or `@Immutable`
 * data) so they sit safely in a [staticCompositionLocalOf].
 */
interface AgentAvatar {
    /** Stable identifier persisted in the avatar selection pref (C3). */
    val id: String

    /** Short display name for the picker chip. */
    val label: String

    /** One-line description for the picker. */
    val description: String

    /** Where this avatar came from (built-in vs user-loaded pet). */
    val source: AvatarSource

    /**
     * Which reactive inputs this avatar honors — drives the picker capability
     * badge via [SphereReactivity.summary]. Reuses the sphere's existing
     * capability contract rather than inventing a parallel one.
     */
    val reactivity: SphereReactivity

    /**
     * Render the avatar for one frame's worth of [state]. The [modifier] is the
     * sizing/placement the call site provides (e.g. `fillMaxSize()`, an alpha,
     * an aspect ratio) and must be applied to the avatar's root node.
     */
    @Composable
    fun Render(state: AvatarRenderState, modifier: Modifier)
}

/**
 * Legacy ambient-visualization seam. The app now provides [SphereAvatar] here;
 * floating companions are published through [LocalFloatingPet].
 */
val LocalAgentAvatar = staticCompositionLocalOf<AgentAvatar> { SphereAvatar }

/**
 * Compatibility list retained during the Sphere/pet split. New companion
 * pickers should consume [LocalAvailablePets].
 */
val LocalAvailableAvatars = staticCompositionLocalOf<List<AgentAvatar>> { listOf(SphereAvatar) }

/**
 * The optional floating pet companion. Unlike [LocalAgentAvatar], this does not
 * replace the ambient sphere or the active profile's identity image. `null`
 * means the user has not selected a companion.
 */
val LocalFloatingPet = staticCompositionLocalOf<AgentAvatar?> { null }

/** User-installed pets available to the companion picker (sphere excluded). */
val LocalAvailablePets = staticCompositionLocalOf<List<AgentAvatar>> { emptyList() }

/** Ambient sphere/background visibility, independent of the motion setting. */
val LocalBackgroundVisualizationEnabled = staticCompositionLocalOf { true }

/**
 * Global pet playback-speed multiplier (1.0 = the clip's authored fps), set in
 * Appearance and provided at the app root. [PetAvatar] scales its frame rate by
 * this so users can tune a pet that feels too fast/slow without re-authoring;
 * the sphere avatar ignores it.
 */
val LocalPetPlaybackSpeed = staticCompositionLocalOf { 1f }

/**
 * Whether [PetAvatar] re-centers each frame on its own opaque content at decode
 * time, cancelling the positional drift common in AI-generated sprite sheets (a
 * character that floats/jumps cell-to-cell). Global Appearance toggle, default
 * on; the sphere ignores it.
 */
val LocalPetStabilize = staticCompositionLocalOf { true }
