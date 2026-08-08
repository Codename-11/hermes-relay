package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.HermesCard
import com.hermesandroid.relay.data.HermesCardAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class HermesApprovalCardInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `vertical swipes and nested scrolling never choose a decision`() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        HermesCardBubble(
                            card = approvalCard(),
                            cardKey = CARD_KEY,
                            dispatches = emptyList(),
                            onActionTap = { _, action -> actions += action.value },
                            onInputSubmit = { _, value -> actions += value },
                        )
                    }
                    item { Spacer(Modifier.height(600.dp)) }
                }
            }
        }

        compose.onNodeWithText("Approve").performTouchInput { swipeUp() }
        compose.onNodeWithText("Deny").performTouchInput { swipeDown() }
        compose.onNodeWithText("A deliberately long command that must be read").performTouchInput {
            swipeUp()
        }

        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }
    }

    @Test
    fun `removal restoration and recomposition are passive`() {
        var visible by mutableStateOf(true)
        var revision by mutableStateOf(0)
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                if (visible) {
                    HermesCardBubble(
                        card = approvalCard().copy(footer = "revision $revision"),
                        cardKey = CARD_KEY,
                        dispatches = emptyList(),
                        onActionTap = { _, action -> actions += action.value },
                        onInputSubmit = { _, value -> actions += value },
                    )
                }
            }
        }

        compose.runOnIdle { revision += 1 }
        compose.runOnIdle { visible = false }
        compose.runOnIdle { visible = true }

        compose.onNodeWithText("Approve").assertExists()
        compose.onNodeWithText("Deny").assertExists()
        compose.runOnIdle { assertEquals(emptyList<String>(), actions) }
    }

    @Test
    fun `only explicit labeled taps choose approve or deny`() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                HermesCardBubble(
                    card = approvalCard(),
                    cardKey = CARD_KEY,
                    dispatches = emptyList(),
                    onActionTap = { _, action -> actions += action.value },
                    onInputSubmit = { _, value -> actions += value },
                )
            }
        }

        compose.onNodeWithText("Approve").performTouchInput { click() }
        compose.onNodeWithText("Deny").performTouchInput { click() }

        compose.runOnIdle { assertEquals(listOf("once", "deny"), actions) }
    }

    private fun approvalCard() = HermesCard(
        type = HermesCard.BuiltInTypes.ASK_APPROVAL,
        title = "Approval requested",
        fields = listOf(
            com.hermesandroid.relay.data.HermesCardField(
                label = "Command",
                value = "A deliberately long command that must be read",
            ),
        ),
        actions = listOf(
            HermesCardAction(
                label = "Approve",
                value = "once",
                style = HermesCardAction.Styles.PRIMARY,
                mode = HermesCardAction.Modes.SUBMIT_ASK,
            ),
            HermesCardAction(
                label = "Deny",
                value = "deny",
                style = HermesCardAction.Styles.DANGER,
                mode = HermesCardAction.Modes.SUBMIT_ASK,
            ),
        ),
        id = CARD_KEY,
    )

    private companion object {
        const val CARD_KEY = "approval-session-a"
    }
}
