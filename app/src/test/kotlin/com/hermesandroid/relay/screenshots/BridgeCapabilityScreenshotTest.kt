package com.hermesandroid.relay.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.bridge.BridgeCapability
import com.hermesandroid.relay.bridge.BridgeCapabilityPolicy
import com.hermesandroid.relay.ui.screens.CapabilityGrantCards
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h1000dp-432dpi")
class BridgeCapabilityScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun groupedCapabilityControlsRenderAtPhoneWidth() {
        compose.setContent {
            HermesRelayTheme(themePreference = "dark") {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    CapabilityGrantCards(
                        policy = BridgeCapabilityPolicy(
                            permanentGrants = setOf(
                                BridgeCapability.DEVICE_INFO,
                                BridgeCapability.CONTACTS_READ,
                                BridgeCapability.CLIPBOARD_READ,
                            ),
                            timedExpiriesMs = mapOf(
                                BridgeCapability.SCREEN_INSPECTION to Long.MAX_VALUE,
                            ),
                        ),
                        timerMinutes = 30,
                        enabled = true,
                        onPermanentChanged = { _, _ -> },
                        onTimedChanged = { _, _ -> },
                    )
                }
            }
        }

        compose.onRoot().captureRoboImage("build/visual-qa/bridge-capabilities.png")
    }
}
