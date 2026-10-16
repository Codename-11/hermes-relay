package com.hermesandroid.relay.ui.components

import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionTtlPickerInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val labels = listOf("1 day", "7 days", "30 days", "90 days", "1 year", "Never expire")

    @Test fun android16ExposesAllRowsAndAccessibilityCanSelectThirtyDays() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var submitted = -1L
        compose.setContent { HermesRelayTheme { SessionTtlPickerDialog(2592000, true, "wss", { submitted = it }, {}) } }
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertCountEquals(6)
        var rows = emptyMap<String, AccessibilityNodeInfo>()
        var radioCount = 0
        fun refreshTree() {
            val found = mutableMapOf<String, AccessibilityNodeInfo>()
            radioCount = 0
            fun visit(node: AccessibilityNodeInfo) {
                if (node.className == "android.widget.RadioButton") radioCount++
                val ownLabel = node.text?.toString()
                if (ownLabel in labels && (node.isSelected || node.isChecked || node.isClickable)) {
                    found[ownLabel!!] = node
                }
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { child ->
                        if (child.text?.toString() in labels && (node.isSelected || node.isChecked || node.isClickable)) {
                            found[child.text.toString()] = node
                        }
                        visit(child)
                    }
                }
            }
            automation.rootInActiveWindow?.let { visit(it) }
            rows = found
        }
        compose.onNodeWithText("30 days").performScrollTo().assertIsSelected()
        compose.waitUntil(10000) { refreshTree(); rows["30 days"] != null && radioCount > 0 }
        // Selected radios intentionally omit a redundant platform click action.
        assertTrue(rows.getValue("30 days").isSelected || rows.getValue("30 days").isChecked)
        println("PAIRING_ACCESSIBILITY: visibleRadios=$radioCount; rows=${rows.mapValues { "selected=${it.value.isSelected || it.value.isChecked}, clickable=${it.value.isClickable}" }}")
        compose.onNodeWithText("7 days").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("30 days").performScrollTo()
        compose.waitUntil(10000) { refreshTree(); rows["30 days"]?.isClickable == true }
        assertTrue(rows.getValue("30 days").performAction(AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitForIdle()
        compose.onNodeWithText("30 days").assertIsSelected()
        val accessibleSelections = mutableSetOf<String>()
        for (label in labels) {
            compose.onNodeWithText(label).performScrollTo().performTouchInput { click() }.assertIsSelected()
            // The platform omits offscreen nodes; verify each choice in its viewport.
            compose.waitUntil(10000) {
                refreshTree()
                rows[label]?.let { it.isSelected || it.isChecked } == true && radioCount > 0
            }
            accessibleSelections += label
        }
        assertEquals(labels.toSet(), accessibleSelections)
        println("PAIRING_ACCESSIBILITY_SELECTED: $accessibleSelections")
        compose.onNodeWithText("30 days").performScrollTo().performClick()
        compose.onNodeWithText("Pair").performClick()
        compose.runOnIdle { assertEquals(2592000L, submitted) }
    }
}
