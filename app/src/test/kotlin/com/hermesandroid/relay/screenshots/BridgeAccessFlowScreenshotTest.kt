package com.hermesandroid.relay.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.bridge.BridgeCapability
import com.hermesandroid.relay.bridge.BridgeCapabilityPolicy
import com.hermesandroid.relay.ui.components.BridgeAccessPreset
import com.hermesandroid.relay.ui.components.BridgeAccessSetupSheet
import com.hermesandroid.relay.ui.components.BridgeAgentAccessCard
import com.hermesandroid.relay.ui.components.BridgeAndroidAccessSummaryCard
import com.hermesandroid.relay.ui.components.BridgeTimedAccessSheet
import com.hermesandroid.relay.ui.components.bridgeAndroidAccessSummary
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.BridgePermissionStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h900dp-432dpi")
class BridgeAccessFlowScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cockpitKeepsPolicyAndAndroidReadinessVisible() {
        val policy = BridgeCapabilityPolicy(
            permanentGrants = setOf(
                BridgeCapability.DEVICE_INFO,
                BridgeCapability.CONTACTS_READ,
                BridgeCapability.CLIPBOARD_READ,
            ),
            timedExpiriesMs = mapOf(
                BridgeCapability.SCREEN_INSPECTION to 1_800_100L,
                BridgeCapability.SCREEN_CONTROL to 1_800_100L,
            ),
        )
        compose.setContent {
            HermesRelayTheme(themePreference = "dark") {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BridgeAgentAccessCard(
                        policy = policy,
                        unattendedEnabled = false,
                        nowMs = 100L,
                        onSetUp = {},
                        onManage = {},
                        onAllowScreen = {},
                    )
                    BridgeAndroidAccessSummaryCard(
                        summary = bridgeAndroidAccessSummary(
                            policy,
                            BridgePermissionStatus(
                                accessibilityServiceEnabled = true,
                                contactsPermitted = false,
                            ),
                            nowMs = 100L,
                        ),
                        expanded = false,
                        onToggle = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("build/visual-qa/bridge-access-cockpit.png")
    }

    @Test
    fun setupPresetSheetRendersAtPhoneWidth() {
        compose.setContent {
            HermesRelayTheme(themePreference = "dark") {
                BridgeAccessSetupSheet(
                    selected = BridgeAccessPreset.READ_ONLY,
                    onSelected = {},
                    onDismiss = {},
                    onContinue = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("build/visual-qa/bridge-access-setup-sheet.png")
    }

    @Test
    fun timedAccessSheetShowsTruthfulPrerequisites() {
        compose.setContent {
            HermesRelayTheme(themePreference = "dark") {
                BridgeTimedAccessSheet(
                    inspectEnabled = true,
                    controlEnabled = true,
                    durationMinutes = 30,
                    unlimited = false,
                    accessibilityReady = true,
                    overlayReady = false,
                    currentlyActive = false,
                    onInspectChanged = {},
                    onControlChanged = {},
                    onDurationChanged = {},
                    onUnlimitedChanged = {},
                    onOpenAccessibility = {},
                    onOpenOverlay = {},
                    onDismiss = {},
                    onAllow = {},
                    onEndNow = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("build/visual-qa/bridge-timed-access-sheet.png")
    }

    @Test
    fun unlimitedAccessSheetExplainsDedicatedDeviceRisk() {
        compose.setContent {
            HermesRelayTheme(themePreference = "dark") {
                BridgeTimedAccessSheet(
                    inspectEnabled = true,
                    controlEnabled = true,
                    durationMinutes = 30,
                    unlimited = true,
                    accessibilityReady = true,
                    overlayReady = true,
                    currentlyActive = false,
                    onInspectChanged = {},
                    onControlChanged = {},
                    onDurationChanged = {},
                    onUnlimitedChanged = {},
                    onOpenAccessibility = {},
                    onOpenOverlay = {},
                    onDismiss = {},
                    onAllow = {},
                    onEndNow = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("build/visual-qa/bridge-unlimited-access-sheet.png")
    }
}
