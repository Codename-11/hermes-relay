package com.hermesandroid.relay.ui.components.avatar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hermesandroid.relay.ui.components.SphereReactivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BackgroundAvatarSelectionTest {
    private val imported = object : AgentAvatar {
        override val id = "imported-background"
        override val label = "Imported background"
        override val description = "test"
        override val source = AvatarSource.USER
        override val reactivity = SphereReactivity()

        @Composable
        override fun Render(state: AvatarRenderState, modifier: Modifier) = Unit
    }
    private val floatingOnly = object : AgentAvatar by imported {
        override val id = "floating-only"
    }

    @Test
    fun `selected imported asset resolves independently`() {
        assertSame(
            imported,
            resolveBackgroundAvatar(imported.id, listOf(imported, floatingOnly)),
        )
    }

    @Test
    fun `missing asset falls back to sphere`() {
        assertEquals(SphereAvatar, resolveBackgroundAvatar("removed", listOf(imported)))
    }
}
