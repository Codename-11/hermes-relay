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
    val paused: Boolean = false,
)

/**
 * Swappable agent avatar — the visual embodiment of the agent across chat, the
 * clean text-flow mode, and the voice overlay.
 *
 * This is the seam that lets the rendering implementation change without
 * touching any surface: every surface composes `LocalAgentAvatar.current.Render(
 * AvatarRenderState(...), modifier)` and is blind to whether the avatar is the
 * ASCII [SphereAvatar] or a future sprite/Lottie "pet".
 *
 * Two-level model: **which avatar** (this interface) → for the sphere avatar,
 * **which skin** (the unchanged [com.hermesandroid.relay.ui.components.SphereSkin]
 * system, consumed internally by [SphereAvatar]).
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
 * The active agent avatar. Defaults to [SphereAvatar] so previews and any
 * composable outside the app root render the classic orb; `RelayApp` provides
 * the user's resolved choice beside the sphere-skin locals.
 */
val LocalAgentAvatar = staticCompositionLocalOf<AgentAvatar> { SphereAvatar }

/**
 * Every selectable avatar (built-ins + any loaded user "pets"). Provided at the
 * app root for the Appearance picker. C2 ships only [SphereAvatar]; C3 appends
 * user pets.
 */
val LocalAvailableAvatars = staticCompositionLocalOf<List<AgentAvatar>> { listOf(SphereAvatar) }
