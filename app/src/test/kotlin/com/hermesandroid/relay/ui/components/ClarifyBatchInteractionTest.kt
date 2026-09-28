package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.HermesCard
import com.hermesandroid.relay.data.HermesCardClarifyBatch
import com.hermesandroid.relay.data.HermesCardClarifyQuestion
import com.hermesandroid.relay.data.HermesCardInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xhdpi")
class ClarifyBatchInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nativeSkipLocksOnlyTheActiveQuestionWithAnEmptyAnswer() {
        val answers = mutableListOf<Pair<String, String>>()
        compose.setContent {
            MaterialTheme {
                HermesCardBubble(card().copy(clarifyBatch = HermesCardClarifyBatch(listOf(question), allowSkip = true)),
                    "batch", emptyList(), { _, _ -> }, { key, value -> answers += key to value })
            }
        }
        compose.onNodeWithText("Skip").performClick()
        compose.runOnIdle { assertEquals(listOf("qid-key" to ""), answers) }
    }

    @Test fun draftsAndSelectionSurviveSavedStateAndFocusFollowsQuestionOrder() {
        val restoration = StateRestorationTester(compose)
        lateinit var inputMode: InputModeManager
        restoration.setContent {
            inputMode = LocalInputModeManager.current
            MaterialTheme {
                HermesCardBubble(card().copy(clarifyBatch = HermesCardClarifyBatch(listOf(question))),
                    "batch", emptyList(), { _, _ -> }, { _, _ -> })
            }
        }
        compose.onNodeWithText("Stage").performClick()
        compose.onNodeWithContentDescription("Other (type your answer)…").performTextInput("Keep rollback")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Stage").assertIsSelected()
        compose.onNodeWithText("Keep rollback").assertExists()
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.onNodeWithText("Stage").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Stage").assertIsFocused()
        compose.onNodeWithText("Stage").performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithText("Production").assertIsFocused()
    }

    @Test fun multiSelectImeRetryKeepsChoicesAndDraftAndDisablesDuplicateActions() {
        var submitting by mutableStateOf(false)
        val answers = mutableListOf<Pair<String, String>>()
        compose.setContent {
            MaterialTheme {
                HermesCardBubble(
                    card = card().copy(clarifyBatch = HermesCardClarifyBatch(listOf(question.copy(submitting = submitting)))),
                    cardKey = "batch", dispatches = emptyList(), onActionTap = { _, _ -> },
                    onInputSubmit = { key, value -> answers += key to value; submitting = true },
                )
            }
        }
        compose.onNodeWithText("Submit").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Other (type your answer)…").apply {
            performTextInput("   ")
            performImeAction()
        }
        compose.runOnIdle { assertEquals(0, answers.size) }
        compose.onNodeWithText("Stage").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Other (type your answer)…").apply {
            performTextInput("custom")
            performImeAction()
        }
        compose.onNodeWithText("Stage").assertIsNotEnabled()
        compose.onNodeWithText("Submit").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf("qid-key" to "[\"Stage\",\"custom\"]"), answers)
            submitting = false
        }
        compose.onNodeWithText("Stage").assertIsSelected()
        compose.onNodeWithText("Submit").performClick()
        compose.runOnIdle { assertEquals(answers[0], answers[1]) }
    }

    @Test fun confirmedQuestionAdvancesAndExpiredBatchRetainsAnswersWithoutInputs() {
        var batch by mutableStateOf(HermesCardClarifyBatch(listOf(question,
            question.copy(key = "second", question = "Anything else?", input = HermesCardInput(HermesCardInput.Kinds.TEXT, allowFreeText = true)))))
        compose.setContent {
            MaterialTheme {
                HermesCardBubble(card().copy(clarifyBatch = batch), "batch", emptyList(), { _, _ -> }, { _, _ -> })
            }
        }
        compose.runOnIdle { batch = batch.copy(questions = batch.questions.map { if (it.key == "qid-key") it.copy(answer = "[\"Stage\"]") else it }) }
        compose.onNodeWithText("Question 2 of 2").assertExists()
        compose.onNodeWithText("Anything else?").assertExists()
        compose.onNodeWithText("Stage").assertDoesNotExist()
        compose.runOnIdle { batch = batch.copy(expiresAtMillis = 1L) }
        compose.onNodeWithText("This request has ended").assertExists()
        compose.onNodeWithText("Stage").assertExists()
        compose.onNodeWithContentDescription("Type an answer…").assertDoesNotExist()
    }

    private val question = HermesCardClarifyQuestion("qid-key", "Which environments?",
        HermesCardInput(HermesCardInput.Kinds.CHOICE, listOf("Stage", "Production"), multiSelect = true, allowFreeText = true))
    private fun card() = HermesCard(HermesCard.BuiltInTypes.ASK_CLARIFY, title = "Hermes needs clarification")
}
