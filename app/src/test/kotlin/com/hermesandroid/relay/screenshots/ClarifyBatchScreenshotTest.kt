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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.HermesCard
import com.hermesandroid.relay.data.HermesCardClarifyBatch
import com.hermesandroid.relay.data.HermesCardClarifyQuestion
import com.hermesandroid.relay.data.HermesCardInput
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.ui.components.MessageBubble
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ClarifyBatchScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun compactDark() = capture("compact-dark")

    @Test @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun compactLightLargeText() = capture("compact-light-font-1_5", theme = "light", scale = 1.5f)

    @Test @Config(qualifiers = "w720dp-h360dp-xhdpi")
    fun landscape() = capture("landscape", width = 540)

    @Test @Config(qualifiers = "w330dp-h720dp-xhdpi")
    fun narrowFoldable() = capture("foldable-pane", scale = 1.5f)

    @Test @Config(qualifiers = "w360dp-h720dp-xhdpi")
    fun partialProgress() = capture("partial", answered = 1)

    @Test @Config(qualifiers = "w360dp-h720dp-xhdpi")
    fun sending() = capture("sending", answered = 1, submitting = true)

    @Test @Config(qualifiers = "w360dp-h720dp-xhdpi")
    fun completed() = capture("completed", answered = 2)

    @Test @Config(qualifiers = "w320dp-h568dp-xhdpi")
    fun expiredPartial() = capture("expired-partial", answered = 1, expired = true)

    private fun capture(
        name: String, theme: String = "dark", scale: Float = 1f, width: Int = 300,
        answered: Int = 0, submitting: Boolean = false, expired: Boolean = false,
    ) {
        val options = listOf(
            "Keep both systems running while traffic moves in measured stages (Recommended)",
            "Migrate everything immediately and accept a short maintenance window",
            "Pause until every downstream consumer has been verified",
            "Use a reversible canary rollout with automatic rollback thresholds",
        )
        val questions = listOf(
            HermesCardClarifyQuestion("q0", "Which deployment approach should I use for the migration, given the existing clients and the rollback requirements?",
                HermesCardInput(HermesCardInput.Kinds.CHOICE, options, allowFreeText = true),
                answer = options[0].takeIf { answered > 0 }),
            HermesCardClarifyQuestion("q1", "Which environments should receive this change?",
                HermesCardInput(HermesCardInput.Kinds.CHOICE, listOf("Staging", "Production"), multiSelect = true, allowFreeText = true),
                answer = "[\"Staging\",\"Production\"]".takeIf { answered > 1 }, submitting = submitting),
        )
        val card = HermesCard(type = HermesCard.BuiltInTypes.ASK_CLARIFY, title = "Hermes needs clarification",
            id = "batch", clarifyBatch = HermesCardClarifyBatch(questions, allowSkip = true, expiresAtMillis = if (expired) 1L else null))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                HermesRelayTheme(themePreference = theme) {
                    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(10.dp)) {
                        item {
                            MessageBubble(
                                message = ChatMessage(id = "batch-message", role = MessageRole.ASSISTANT, content = "",
                                    timestamp = 0L, cards = listOf(card), clientOnly = true),
                                maxBubbleWidth = width.dp, showTimestamps = false, animationEnabled = false,
                            )
                        }
                    }
                }
            }
        }
        val directory = File("build/ui-evidence/clarify-batch").apply { mkdirs() }
        compose.onRoot().captureRoboImage(File(directory, "$name-top.png").path)
        repeat(4) { compose.onNode(hasScrollAction()).performTouchInput { swipeUp() } }
        compose.onRoot().captureRoboImage(File(directory, "$name-bottom.png").path)
    }
}
