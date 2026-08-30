package com.hermesandroid.relay.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.HermesCard
import com.hermesandroid.relay.data.HermesCardInput
import com.hermesandroid.relay.ui.components.HermesCardBubble
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClarifyCardScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun compactPhoneLongOptions() = capture("01-compact-phone-long-options.png", 1f)

    @Test
    @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun compactPhoneLargeText() = capture("02-compact-phone-font-1_5.png", 1.5f)

    @Test
    @Config(qualifiers = "w720dp-h360dp-xhdpi")
    fun landscape() = capture("03-landscape-720x360.png", 1f)

    @Test
    @Config(qualifiers = "w330dp-h720dp-xhdpi")
    fun foldablePaneWidth() = capture("04-foldable-narrow-pane.png", 1.3f)

    private fun capture(fileName: String, fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                HermesRelayTheme(themePreference = "dark") {
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(12.dp),
                    ) {
                        item {
                            HermesCardBubble(
                                card = clarifyCard(),
                                cardKey = "clarify-render",
                                dispatches = emptyList(),
                                onActionTap = { _, _ -> },
                                onInputSubmit = { _, _ -> },
                            )
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Other (type your answer)…").assertExists()
        repeat(4) { compose.onNode(hasScrollAction()).performTouchInput { swipeUp() } }
        val evidenceDir = File("build/ui-evidence/clarify-card")
        evidenceDir.mkdirs()
        compose.onRoot().captureRoboImage(File(evidenceDir, fileName).path)
    }

    private fun clarifyCard() = HermesCard(
        type = HermesCard.BuiltInTypes.ASK_CLARIFY,
        title = "Hermes needs clarification",
        body = "Which deployment approach should I use for the migration?",
        input = HermesCardInput(
            kind = HermesCardInput.Kinds.CHOICE,
            choices = listOf(
                "Migrate everything immediately and accept a short maintenance window",
                "Keep both systems running while traffic moves in measured stages",
                "Pause until every downstream consumer has been verified",
                "Use a reversible canary rollout with automatic rollback thresholds",
            ),
            allowFreeText = true,
        ),
    )
}
