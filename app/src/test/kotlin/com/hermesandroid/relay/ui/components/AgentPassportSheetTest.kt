package com.hermesandroid.relay.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.network.upstream.GatewayApprovalMode
import com.hermesandroid.relay.network.upstream.GatewayApprovalModeCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xxhdpi")
class AgentPassportSheetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun safetyControls_haveLargeTargets_selectedSemantics_andPlainLanguageMeaning() {
        var selectedMode: GatewayApprovalMode? = null
        var darkTheme by mutableStateOf(false)

        compose.setContent {
            MaterialTheme(
                colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
            ) {
                AgentPassportSafetyCard(
                    approvalMode = GatewayApprovalMode.Smart,
                    approvalCapability = GatewayApprovalModeCapability.Supported,
                    approvalWritable = true,
                    yoloEnabled = false,
                    fastEnabled = false,
                    controlsAvailable = true,
                    gatewayReady = true,
                    isStreaming = false,
                    onApprovalMode = { selectedMode = it },
                    onYolo = {},
                    onFast = {},
                )
            }
        }

        compose.onNodeWithTag("$PASSPORT_APPROVAL_CONTROL_TAG-0")
            .assertHeightIsAtLeast(48.dp)
            .assertIsNotSelected()
        compose.onNodeWithTag("$PASSPORT_APPROVAL_CONTROL_TAG-1")
            .assertHeightIsAtLeast(48.dp)
            .assertIsSelected()
        compose.onNodeWithText("Ask only when Hermes detects elevated risk.")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Smart. Ask only when Hermes detects elevated risk.",
        ).assertIsSelected()
        compose.onNodeWithTag("$PASSPORT_CHAT_OVERRIDE_CONTROL_TAG-0")
            .assertHeightIsAtLeast(48.dp)
            .assertIsSelected()
        compose.onNodeWithTag("$PASSPORT_FAST_CONTROL_TAG-1")
            .assertHeightIsAtLeast(48.dp)
            .assertIsSelected()
        compose.onNodeWithText("Use the standard processing tier.")
            .assertIsDisplayed()

        compose.onNodeWithTag("$PASSPORT_APPROVAL_CONTROL_TAG-0").performClick()
        compose.runOnIdle { assertEquals(GatewayApprovalMode.Manual, selectedMode) }

        compose.runOnIdle { darkTheme = true }
        compose.onNodeWithTag("$PASSPORT_APPROVAL_CONTROL_TAG-1")
            .assertIsSelected()
        compose.onNodeWithText("Ask only when Hermes detects elevated risk.")
            .assertIsDisplayed()
    }

    @Test
    fun explicitCloseControl_dismissesSheet() {
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                AgentPassportSheetHost(
                    scrollState = rememberScrollState(),
                    onDismiss = { dismissals += 1 },
                ) {
                    Spacer(Modifier.height(800.dp))
                }
            }
        }

        compose.onNodeWithTag(AGENT_PASSPORT_CLOSE_TAG)
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun downwardGesture_scrollsNestedContentBeforeDismissingSheet() {
        var dismissals = 0
        lateinit var nestedScrollState: ScrollState
        compose.setContent {
            MaterialTheme {
                nestedScrollState = rememberScrollState()
                AgentPassportSheetHost(
                    scrollState = nestedScrollState,
                    onDismiss = { dismissals += 1 },
                ) {
                    repeat(30) { index ->
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .testTag("passportItem-$index"),
                        )
                    }
                }
            }
        }

        compose.runOnIdle {
            nestedScrollState.dispatchRawDelta(nestedScrollState.maxValue.toFloat())
            assertTrue(nestedScrollState.value > 0)
        }
        compose.onNodeWithTag(AGENT_PASSPORT_SCROLL_TAG)
            .performTouchInput { swipeDown() }

        compose.runOnIdle {
            assertEquals(0, dismissals)
            assertTrue(nestedScrollState.value > 0)
        }
        compose.onNodeWithTag(AGENT_PASSPORT_SHEET_TAG).assertIsDisplayed()
    }

    @Test
    fun downwardGestureAtTop_dismissesSheet() {
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                AgentPassportSheetHost(
                    scrollState = rememberScrollState(),
                    onDismiss = { dismissals += 1 },
                ) {
                    Spacer(Modifier.height(900.dp))
                }
            }
        }

        compose.onNodeWithTag(AGENT_PASSPORT_SCROLL_TAG)
            .performTouchInput { swipeDown() }

        compose.waitUntil(timeoutMillis = 5_000) { dismissals == 1 }
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp-xxhdpi")
    fun compactHeightAndLargeFont_keepContentScrollableAndCloseVisible() {
        lateinit var compactScrollState: ScrollState
        compose.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(currentDensity.density, fontScale = 2f),
            ) {
                MaterialTheme {
                    compactScrollState = rememberScrollState()
                    AgentPassportSheetHost(
                        scrollState = compactScrollState,
                        onDismiss = {},
                    ) {
                        repeat(12) { index ->
                            Spacer(
                                Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .testTag("largeFontItem-$index"),
                            )
                        }
                    }
                }
            }
        }

        compose.onNodeWithTag(AGENT_PASSPORT_CLOSE_TAG).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(compactScrollState.maxValue > 0)
            compactScrollState.dispatchRawDelta(compactScrollState.maxValue.toFloat())
        }
        compose.onNodeWithTag("largeFontItem-11").assertIsDisplayed()
        compose.onNodeWithTag(AGENT_PASSPORT_CLOSE_TAG).assertIsDisplayed()
    }
}
