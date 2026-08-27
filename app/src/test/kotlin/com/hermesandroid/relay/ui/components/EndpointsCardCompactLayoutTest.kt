package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h720dp-xxhdpi")
class EndpointsCardCompactLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `long route title keeps active state on a separate visible row`() {
        val title = "A very long operator-defined reverse proxy route name"
        val route = EndpointCandidate(
            role = "reverse-proxy-west-with-a-long-internal-name",
            displayName = title,
            dashboard = DashboardEndpoint("https://hermes.example.com"),
            api = ApiEndpoint("hermes.example.com", 8642, tls = true),
        )

        compose.setContent {
            MaterialTheme {
                EndpointsCard(
                    endpoints = listOf(route),
                    activeEndpoint = route,
                    preferredRole = null,
                    manualOverrideRole = null,
                    onUseNow = {},
                    onCancelUseNow = {},
                    onPreferEndpoint = {},
                    onClearPreferred = {},
                    onProbeNow = {},
                    onViewPin = { null },
                )
            }
        }

        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText("Active").assertExists()
        val titleBounds = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
        val activeBounds = compose.onNodeWithText("Active").fetchSemanticsNode().boundsInRoot
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("status should be placed below the bounded title row", activeBounds.top >= titleBounds.bottom)
        assertTrue("status must remain inside the compact viewport", activeBounds.right <= rootBounds.right)
    }
}
