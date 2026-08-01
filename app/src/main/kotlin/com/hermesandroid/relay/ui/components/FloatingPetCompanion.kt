package com.hermesandroid.relay.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.components.avatar.AgentAvatar
import com.hermesandroid.relay.ui.components.avatar.AvatarRenderState

internal const val FLOATING_PET_COMPACT_HEIGHT_DP = 700

internal fun shouldCompactFloatingPet(imeVisible: Boolean, screenHeightDp: Int): Boolean =
    imeVisible || screenHeightDp < FLOATING_PET_COMPACT_HEIGHT_DP

internal fun floatingPetVisualSizeDp(compact: Boolean): Int = if (compact) 40 else 48

internal fun shouldPauseFloatingPet(
    alreadyPaused: Boolean,
    animationEnabled: Boolean,
    isScrolling: Boolean,
): Boolean = alreadyPaused || !animationEnabled || isScrolling

internal fun floatingPetAlpha(isScrolling: Boolean): Float = if (isScrolling) 0.6f else 1f

/**
 * A predictable, non-draggable companion perch immediately above the chat
 * composer. It intentionally renders only pet avatars supplied through
 * LocalFloatingPet; profile identity remains in the chat header/message group,
 * while the Sphere remains the optional ambient background.
 */
@Composable
fun FloatingPetCompanion(
    pet: AgentAvatar,
    state: AvatarRenderState,
    isScrolling: Boolean,
    compact: Boolean,
    animationEnabled: Boolean,
    onHide: () -> Unit,
    onOpenAppearance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember(pet.id) { mutableStateOf(false) }
    val visualSize = floatingPetVisualSizeDp(compact).dp
    val targetSize = if (compact) 48.dp else 56.dp
    val targetAlpha = floatingPetAlpha(isScrolling)
    val renderedAlpha = if (animationEnabled) {
        val animated by animateFloatAsState(
            targetValue = targetAlpha,
            animationSpec = tween(durationMillis = 140),
            label = "floating-pet-alpha",
        )
        animated
    } else {
        targetAlpha
    }
    val renderState = state.copy(
        paused = shouldPauseFloatingPet(
            alreadyPaused = state.paused,
            animationEnabled = animationEnabled,
            isScrolling = isScrolling,
        ),
    )
    val stateLabel = stringResource(state.state.floatingPetStateLabelRes())
    val companionDescription = stringResource(
        R.string.floating_pet_companion_description,
        pet.label,
        stateLabel,
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(targetSize)
            .padding(start = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(targetSize)
                .alpha(renderedAlpha)
                .clickable { menuExpanded = true }
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = companionDescription
                },
            contentAlignment = Alignment.Center,
        ) {
            // A tiny ground shadow gives the sprite a physical perch without
            // turning it into another card or message avatar.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-3).dp)
                    .size(width = visualSize * 0.62f, height = 5.dp)
                    .background(
                        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.20f),
                        shape = CircleShape,
                    ),
            )
            pet.Render(
                state = renderState,
                modifier = Modifier.size(visualSize),
            )

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "${pet.label} · $stateLabel",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.floating_pet_menu_appearance)) },
                    onClick = {
                        menuExpanded = false
                        onOpenAppearance()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.floating_pet_menu_hide)) },
                    onClick = {
                        menuExpanded = false
                        onHide()
                    },
                )
            }
        }
    }
}

private fun SphereState.floatingPetStateLabelRes(): Int = when (this) {
    SphereState.Idle -> R.string.floating_pet_state_idle
    SphereState.Thinking -> R.string.floating_pet_state_thinking
    SphereState.Streaming -> R.string.floating_pet_state_streaming
    SphereState.Listening -> R.string.floating_pet_state_listening
    SphereState.Speaking -> R.string.floating_pet_state_speaking
    SphereState.Error -> R.string.floating_pet_state_error
}
