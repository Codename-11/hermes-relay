package com.hermesandroid.relay.screenshots

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.ui.screens.DashboardManagementOverviewPreview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual regression coverage for the grouped, summary-first Hermes Management
 * overview. The preview seam renders the production composables with stable
 * server and section data; this test adds no alternate test-only UI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w420dp-h935dp-xhdpi")
class DashboardManagementOverviewScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun groupedManagementOverviewMatchesMobileInformationArchitecture() {
        compose.setContent { DashboardManagementOverviewPreview() }

        compose.onNodeWithText("Hermes management").assertExists()
        compose.onAllNodesWithText("Hermes Home")[0].assertExists()
        compose.onNodeWithText("Profiles").assertExists()
        compose.onNodeWithText("Skills + Tools").assertExists()
        compose.onRoot().captureRoboImage(
            "build/ui-regression/hermes-management-overview-dark.png",
        )

        compose.onNode(hasScrollAction())
            .performScrollToNode(hasText("Server details"))
        compose.onNodeWithText("Automations").assertExists()
        compose.onNodeWithText("Config").assertExists()
        compose.onNodeWithText("Server details").performClick()
        compose.onNode(hasText("https://hermes.local", substring = true)).assertExists()
    }
}
