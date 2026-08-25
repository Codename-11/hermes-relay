package com.hermesandroid.relay.ui.components.avatar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.hermesandroid.relay.ui.components.MorphingSphere
import com.hermesandroid.relay.ui.components.SphereReactivity
import com.hermesandroid.relay.util.AppForegroundTracker

/**
 * Default ambient visualization — the ASCII [MorphingSphere].
 *
 * Its [Render] is the existing sphere call verbatim: it forwards the reactive
 * bundle and lets [MorphingSphere] pull colors/params from
 * [com.hermesandroid.relay.ui.components.LocalSphereSkin] internally (the
 * `skin` parameter defaults to `LocalSphereSkin.current`). So the entire skin
 * system remains independently selectable in Appearance.
 *
 * A singleton because it holds no per-instance state; it sits in
 * [LocalAgentAvatar]. Floating pets use their own companion seam.
 */
object SphereAvatar : AgentAvatar {
    override val id: String = "sphere"
    override val label: String = "Sphere"
    override val description: String = "The classic ASCII orb"
    override val source: AvatarSource = AvatarSource.BUILT_IN

    /**
     * The sphere renderer honors voice, tool-burst, and activity intensity (its
     * skins may narrow this further per-skin); it does not consume gaze. This is
     * the avatar-level capability shown on the picker chip — the same superset
     * the built-in skins declare.
     */
    override val reactivity: SphereReactivity =
        SphereReactivity(voice = true, tools = true, intensity = true, gaze = false)

    @Composable
    override fun Render(state: AvatarRenderState, modifier: Modifier) {
        val appForeground by AppForegroundTracker.isForeground.collectAsState()
        MorphingSphere(
            modifier = modifier,
            state = state.state,
            intensity = state.intensity,
            toolCallBurst = state.toolCallBurst,
            voiceAmplitude = state.voiceAmplitude,
            voiceMode = state.voiceMode,
            // skin is left to its default (LocalSphereSkin.current).
            // paused → pin to a still frame, exactly as the pre-seam clean-mode
            // call did with fixedTime/fixedColorPhase = 0f.
            fixedTime = if (state.paused) 0f else null,
            fixedColorPhase = if (state.paused) 0f else null,
            motionVisible = appForeground,
        )
    }
}
