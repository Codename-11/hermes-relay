package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class ChatTextScaleTest {

    @get:Rule
    val compose = createComposeRule()

    // --- Pure scale math -------------------------------------------------

    @Test
    fun `clampChatTextScale keeps in-range values unchanged`() {
        assertEquals(1.0f, clampChatTextScale(1.0f), 0f)
        assertEquals(1.5f, clampChatTextScale(1.5f), 0f)
        assertEquals(0.85f, clampChatTextScale(0.85f), 0f)
        assertEquals(2.0f, clampChatTextScale(2.0f), 0f)
    }

    @Test
    fun `clampChatTextScale clamps below minimum and above maximum`() {
        assertEquals(0.85f, clampChatTextScale(0.5f), 0f)
        assertEquals(0.85f, clampChatTextScale(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(2.0f, clampChatTextScale(3.0f), 0f)
        assertEquals(2.0f, clampChatTextScale(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun `clampChatTextScale maps NaN to the default scale`() {
        assertEquals(1.0f, clampChatTextScale(Float.NaN), 0f)
    }

    @Test
    fun `state starts at one and clamps both bounds when scaling up and down`() {
        val state = ChatTextScaleState()
        assertEquals(1.0f, state.scale, 0f)

        // Upward: repeated zoom-in lands exactly on the upper bound.
        repeat(40) { state.applyFactor(1.25f) }
        assertEquals(2.0f, state.scale, 0f)

        // Downward: repeated zoom-out lands exactly on the lower bound.
        repeat(40) { state.applyFactor(0.8f) }
        assertEquals(0.85f, state.scale, 0f)

        // From the floor, further zoom-out stays clamped; from the ceiling,
        // zoom-in stays clamped as well.
        state.applyFactor(0.8f)
        assertEquals(0.85f, state.scale, 0f)
        state.setScale(2.0f)
        state.applyFactor(1.25f)
        assertEquals(2.0f, state.scale, 0f)
    }

    @Test
    fun `state ignores non-finite and zero factors`() {
        val state = ChatTextScaleState()
        state.applyFactor(Float.NaN)
        state.applyFactor(Float.POSITIVE_INFINITY)
        state.applyFactor(0f)
        assertEquals(1.0f, state.scale, 0f)
    }

    @Test
    fun `state setScale and reset clamp into the allowed range`() {
        val state = ChatTextScaleState()
        state.setScale(5f)
        assertEquals(2.0f, state.scale, 0f)
        state.setScale(-1f)
        assertEquals(0.85f, state.scale, 0f)
        state.reset()
        assertEquals(1.0f, state.scale, 0f)
    }

    // --- CompositionLocal plumbing ----------------------------------------

    @Test
    fun `default LocalChatTextScale is one when no provider is present`() {
        var observed = -1f
        compose.setContent { MaterialTheme { ScaledTestText("plain") { observed = it } } }
        compose.runOnIdle { assertEquals(1.0f, observed, 0f) }
    }

    @Test
    fun `ProvideChatTextScale exposes the current scale to descendants`() {
        val state = ChatTextScaleState()
        var observed = -1f
        compose.setContent {
            MaterialTheme {
                ProvideChatTextScale(state) { ScaledTestText("scaled") { observed = it } }
            }
        }
        compose.runOnIdle { assertEquals(1.0f, observed, 0f) }

        state.setScale(1.4f)
        compose.runOnIdle { assertEquals(1.4f, observed, 0f) }

        state.reset()
        compose.runOnIdle { assertEquals(1.0f, observed, 0f) }
    }

    // --- Pinch gesture ----------------------------------------------------

    @Test
    fun `two finger pinch updates the scale through the modifier`() {
        val state = ChatTextScaleState()
        setContentWithGesture(state)

        compose.onRoot().performTouchInput {
            down(0, Offset(50f, 100f))
            down(1, Offset(150f, 100f)) // two fingers, start distance 100
            moveBy(0, Offset(-25f, 0f))
            moveBy(1, Offset(25f, 0f)) // spread to 150 -> factor 1.5
            up(0)
            up(1)
        }

        compose.runOnIdle { assertEquals(1.5f, state.scale, 0.001f) }
    }

    @Test
    fun `two finger pinch in clamps toward the minimum bound`() {
        val state = ChatTextScaleState()
        setContentWithGesture(state)

        compose.onRoot().performTouchInput {
            down(0, Offset(50f, 100f))
            down(1, Offset(250f, 100f)) // start distance 200
            moveBy(0, Offset(30f, 0f))
            moveBy(1, Offset(-30f, 0f)) // pinch to 140 -> factor 0.7
            up(0)
            up(1)
        }

        compose.runOnIdle { assertEquals(0.85f, state.scale, 0.001f) }
    }

    @Test
    fun `single finger drag does not change the scale`() {
        val state = ChatTextScaleState()
        setContentWithGesture(state)

        compose.onRoot().performTouchInput {
            down(0, Offset(100f, 100f))
            moveBy(0, Offset(0f, -60f))
            up()
        }

        compose.runOnIdle { assertEquals(1.0f, state.scale, 0f) }
    }

    @Test
    fun `single finger tap does not change the scale`() {
        val state = ChatTextScaleState()
        setContentWithGesture(state)

        compose.onRoot().performTouchInput { click() }

        compose.runOnIdle { assertEquals(1.0f, state.scale, 0f) }
    }

    @Test
    fun `one finger drag with the gesture present leaves scale untouched`() {
        // Regression guard for "does not break ordinary one-finger scrolling":
        // with only one pointer down the modifier never engages, so a scrollable
        // sibling below it in the chain keeps receiving the drag.
        val state = ChatTextScaleState()
        setContentWithGesture(state)

        compose.onRoot().performTouchInput {
            down(0, Offset(100f, 200f))
            moveBy(0, Offset(0f, -40f))
            up()
        }

        compose.runOnIdle { assertEquals(1.0f, state.scale, 0f) }
    }

    // --- Helpers ----------------------------------------------------------

    private fun setContentWithGesture(state: ChatTextScaleState) {
        compose.setContent {
            MaterialTheme {
                ProvideChatTextScale(state) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Red)
                            .chatTextScaleGesture(state),
                    ) {
                        ScaledTestText("pinch target") {}
                    }
                }
            }
        }
    }

    /** Text that reports the [LocalChatTextScale] it observes; used to assert provider wiring. */
    @Composable
    private fun ScaledTestText(
        text: String,
        onScaleObserved: (Float) -> Unit,
    ) {
        val scale = LocalChatTextScale.current
        LaunchedEffect(scale) { onScaleObserved(scale) }
        Text(text = text, fontSize = (14f * scale).sp)
    }
}
