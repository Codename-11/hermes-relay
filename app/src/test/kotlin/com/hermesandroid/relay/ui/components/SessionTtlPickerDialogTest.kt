package com.hermesandroid.relay.ui.components

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xhdpi")
class SessionTtlPickerDialogTest {
    @get:Rule val compose = createComposeRule()
    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Test fun everyDurationHasOneSelectableRadioIncludingThirtyDays() {
        var submitted = -1L
        compose.setContent { HermesRelayTheme { SessionTtlPickerDialog(2592000, true, "wss", { submitted = it }, {}) } }
        compose.onAllNodes(radio).assertCountEquals(6)
        compose.onNodeWithText("30 days").assertIsSelected().assertHasClickAction().performClick()
        compose.onNode(isDialog()).captureRoboImage("build/ui-evidence/pairing-ttl.png")
        compose.onNodeWithText("Pair").performClick()
        compose.runOnIdle { assertEquals(2592000, submitted) }
    }

    @Test fun oneDayInitialSelectionIsPreserved() {
        compose.setContent { HermesRelayTheme { SessionTtlPickerDialog(86400, false, "wss", {}, {}) } }
        compose.onNodeWithText("1 day").assertIsSelected()
    }
}
