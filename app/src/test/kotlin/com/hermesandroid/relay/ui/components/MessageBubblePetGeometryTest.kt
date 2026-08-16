package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.ui.components.pet.LocalPetSafeAreaRegistry
import com.hermesandroid.relay.ui.components.pet.PetSafeAreaRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [27], qualifiers = "w360dp-h720dp-xhdpi")
class MessageBubblePetGeometryTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun assistantIdentityRemainsAnObstacleWhenItsMessageMoves() {
        val registry = PetSafeAreaRegistry()
        val offset = mutableStateOf(0.dp)
        val message = ChatMessage(
            id = "assistant-with-identity",
            role = MessageRole.ASSISTANT,
            content = "A response with a safe top rail.",
            timestamp = 1_700_000_000_000L,
            agentName = "Hermes",
        )
        val identityKey = "$CHAT_PET_IDENTITY_OBSTACLE_PREFIX${message.uiKey}"
        val perchKey = "$CHAT_PET_ASSISTANT_MESSAGE_PERCH_PREFIX${message.uiKey}"

        compose.setContent {
            CompositionLocalProvider(LocalPetSafeAreaRegistry provides registry) {
                MaterialTheme {
                    MessageBubble(
                        message = message,
                        modifier = Modifier.offset(y = offset.value),
                        isFirstInGroup = true,
                        petPerchKey = perchKey,
                    )
                }
            }
        }

        compose.waitForIdle()
        val initial = registry.snapshot("chat")
        val initialIdentity = initial.obstacles.single { it.key == identityKey }.bounds
        val initialPerch = initial.perches.single { it.key == perchKey }.bounds
        assertTrue(initialIdentity.bottom <= initialPerch.top)

        compose.runOnIdle { offset.value = (-24).dp }
        compose.waitForIdle()

        val moved = registry.snapshot("chat")
        val movedIdentity = moved.obstacles.single { it.key == identityKey }.bounds
        val movedPerch = moved.perches.single { it.key == perchKey }.bounds
        assertTrue(movedIdentity.top < initialIdentity.top)
        assertTrue(movedPerch.top < initialPerch.top)
        assertEquals(
            initialPerch.top - initialIdentity.top,
            movedPerch.top - movedIdentity.top,
            0.01f,
        )
        assertTrue(movedIdentity.bottom <= movedPerch.top)
    }
}
