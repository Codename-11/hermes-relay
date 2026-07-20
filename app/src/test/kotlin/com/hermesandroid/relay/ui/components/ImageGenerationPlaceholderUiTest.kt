package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class ImageGenerationPlaceholderUiTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `placeholder announces rendering image status`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                ImageGenerationPlaceholder(Modifier.padding(16.dp))
            }
        }

        compose.mainClock.advanceTimeBy(2_400)
        compose.onNodeWithContentDescription("Rendering image").assertExists()
        compose.onRoot().captureRoboImage("build/verification-shots/image-generation-placeholder.png")
    }
}
