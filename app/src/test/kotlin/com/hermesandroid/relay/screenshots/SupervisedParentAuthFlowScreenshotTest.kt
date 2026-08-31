package com.hermesandroid.relay.screenshots

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.SupervisedParentEnrollment
import com.hermesandroid.relay.ui.screens.CredentialChoiceScreen
import com.hermesandroid.relay.ui.screens.ParentAuthScreenSurface
import com.hermesandroid.relay.ui.screens.PasswordSetupScreen
import com.hermesandroid.relay.ui.screens.PinSetupScreen
import com.hermesandroid.relay.ui.screens.SupervisedParentRecoveryCodeContent
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h900dp-432dpi")
class SupervisedParentAuthFlowScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun credentialChoice() {
        render("build/visual-qa/supervised-parent-choice.png", 1 to 2) {
            CredentialChoiceScreen(
                title = "Choose parent access",
                subtitle = "Pick one way to unlock parent settings. You can change it later.",
                onSelected = {},
            )
        }
    }

    @Test
    fun pinSetup() {
        render("build/visual-qa/supervised-parent-pin.png", 2 to 2) {
            PinSetupScreen(busy = false, error = null, onComplete = {})
        }
    }

    @Test
    fun passwordSetup() {
        render("build/visual-qa/supervised-parent-password.png", 2 to 2) {
            PasswordSetupScreen(busy = false, error = null, onComplete = {})
        }
    }

    @Test
    fun recoveryPhrase() {
        render("build/visual-qa/supervised-parent-recovery.png", 3 to 3) {
            SupervisedParentRecoveryCodeContent(
                enrollment = SupervisedParentEnrollment(
                    "maple-river-lantern-copper-sparrow-moon",
                ),
            )
        }
    }

    private fun render(
        path: String,
        step: Pair<Int, Int>,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        compose.setContent {
            HermesRelayTheme(themePreference = "dark") {
                ParentAuthScreenSurface(step = step, onBack = {}, content = content)
            }
        }
        compose.onRoot().captureRoboImage(path)
    }
}
