package com.hermesandroid.relay.plugins.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermesandroid.relay.plugins.document.PluginDocumentState
import com.hermesandroid.relay.plugins.document.PluginElement
import com.hermesandroid.relay.plugins.document.PluginPage
import com.hermesandroid.relay.plugins.document.PluginText
import com.hermesandroid.relay.plugins.document.PluginValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PluginDocumentRendererTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun pageRendersBindingsAndEmitsControlledStateChanges() {
        var interaction: PluginInteraction? = null
        val page = PluginPage(
            id = "home",
            title = PluginText.Binding("title", "Fallback"),
            content = PluginElement.Group(
                id = "root",
                children = listOf(
                    PluginElement.Text(
                        id = "message",
                        text = PluginText.Binding("message"),
                    ),
                    PluginElement.Toggle(
                        id = "enabled-toggle",
                        label = PluginText.Literal("Enabled"),
                        binding = "enabled",
                    ),
                ),
            ),
        )
        val state = PluginDocumentState(
            mapOf(
                "title" to PluginValue.StringValue("Status plugin"),
                "message" to PluginValue.StringValue("Everything is healthy"),
                "enabled" to PluginValue.BooleanValue(false),
            ),
        )

        composeTestRule.setContent {
            MaterialTheme {
                PluginPageRenderer(page, state, { interaction = it })
            }
        }

        composeTestRule.onNodeWithText("Status plugin").assertIsDisplayed()
        composeTestRule.onNodeWithText("Everything is healthy").assertIsDisplayed()
        composeTestRule.onNode(isToggleable()).performClick()

        assertEquals(
            PluginInteraction.ValueChanged(
                elementId = "enabled-toggle",
                key = "enabled",
                value = PluginValue.BooleanValue(true),
            ),
            interaction,
        )
    }
}
