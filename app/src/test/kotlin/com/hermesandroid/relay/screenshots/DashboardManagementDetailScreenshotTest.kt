package com.hermesandroid.relay.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.ui.screens.DashboardAutomationsDetailPreview
import com.hermesandroid.relay.ui.screens.DashboardIntegrationsDetailPreview
import com.hermesandroid.relay.ui.screens.DashboardManagementHubPreview
import com.hermesandroid.relay.ui.screens.DashboardServerConfigurationDetailPreview
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xxhdpi")
class DashboardManagementDetailScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun managementHub_rendersConnectionStatusAndAllDestinations() {
        compose.setContent { DashboardManagementHubPreview() }

        compose.onRoot().captureRoboImage(
            "build/ui-regression/dashboard-management-hub-dark.png",
        )
        assertTextExists("Hermes Home")
        assertTextExists("Dashboard ready", substring = true)
        assertTextCanScrollIntoView("Connections")
        assertTextCanScrollIntoView("Profiles")
        assertTextCanScrollIntoView("Skills + Tools")
        assertTextCanScrollIntoView("Automations")
        assertTextCanScrollIntoView("MCP Servers")
        assertTextCanScrollIntoView("Models")
        assertTextCanScrollIntoView("Config")
        assertTextCanScrollIntoView("Server details")
    }

    @Test
    fun integrationsMcp_rendersProfileScopedOperationalDetail() {
        compose.setContent { DashboardIntegrationsDetailPreview() }

        assertTextExists("Skills + Tools")
        assertTextExists("Hermes Home")
        assertTextExists("Profile: default")
        assertTextExists("GitHub")
        assertTextExists("Filesystem")
        compose.onRoot().captureRoboImage(
            "build/ui-regression/dashboard-integrations-mcp-dark.png",
        )
    }

    @Test
    fun automations_rendersFiltersScopeAndSchedules() {
        compose.setContent { DashboardAutomationsDetailPreview() }

        compose.onRoot().captureRoboImage(
            "build/ui-regression/dashboard-automations-dark.png",
        )
        assertTextExists("Automations")
        assertTextExists("Hermes Home")
        assertTextExists("Profile: default")
        assertTextExists("Morning brief")
        assertTextCanScrollIntoView("Weekly cleanup")
        assertTextCanScrollIntoView("Paused")
    }

    @Test
    fun serverConfig_rendersGlobalReadOnlyDetail() {
        compose.setContent { DashboardServerConfigurationDetailPreview() }

        compose.onRoot().captureRoboImage(
            "build/ui-regression/dashboard-server-config-dark.png",
        )
        assertTextExists("Server configuration")
        assertTextExists("Hermes Home")
        assertTextExists("Profile: default", substring = true)
        assertTextExists("Configuration is schema-driven and read-only here. No automatic writes are made.")
        assertTextCanScrollIntoView("Model routing")
        assertTextCanScrollIntoView("Agent defaults")
    }

    private fun assertTextExists(text: String, substring: Boolean = false) {
        assertTrue(
            "Expected semantics text: $text",
            compose.onAllNodesWithText(
                text,
                substring = substring,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private fun assertTextCanScrollIntoView(text: String) {
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText(text))
        assertTextExists(text)
    }
}
