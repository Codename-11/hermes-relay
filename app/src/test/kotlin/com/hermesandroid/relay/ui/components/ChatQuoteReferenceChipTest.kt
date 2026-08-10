package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.ChatQuoteReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class ChatQuoteReferenceChipTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `chip colors use semantic readable roles in light and dark themes`() {
        listOf(
            lightColorScheme(),
            darkColorScheme(),
            lightColorScheme(primary = Color.Yellow, onPrimary = Color.Black),
            darkColorScheme(primary = Color.Magenta, onPrimary = Color.Black),
        ).forEach { scheme ->
            val colors = chatQuoteReferenceColors(scheme)

            assertEquals(scheme.surfaceContainerHigh, colors.background)
            assertEquals(scheme.onSurface, colors.author)
            assertEquals(scheme.onSurfaceVariant, colors.excerpt)
            assertEquals(scheme.primary, colors.accent)
        }
    }

    @Test
    fun `chip highlights author opens original and exposes accessible remove`() {
        var opens = 0
        var removes = 0
        compose.setContent {
            MaterialTheme {
                ChatQuoteReferenceChip(
                    reference = ChatQuoteReference("message-1", "Hermes", "Original answer"),
                    onOpenOriginal = { opens++ },
                    onRemove = { removes++ },
                )
            }
        }

        compose.onNodeWithText("@Hermes").assertIsDisplayed()
        compose.onNodeWithText("Original answer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open original message from Hermes. Original answer")
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithContentDescription("Remove quote")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        compose.runOnIdle {
            assertEquals(1, opens)
            assertEquals(1, removes)
        }
    }
}
