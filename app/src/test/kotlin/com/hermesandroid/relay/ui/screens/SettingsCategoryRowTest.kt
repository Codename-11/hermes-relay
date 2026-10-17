package com.hermesandroid.relay.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xhdpi")
class SettingsCategoryRowTest {
    @get:Rule val compose = createComposeRule()

    private fun state(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    @Test fun expandableRowAnnouncesItsState() {
        compose.setContent {
            HermesRelayTheme(appThemeId = "clean", themePreference = "light", layoutStyleId = "clean") {
                var expanded by remember { mutableStateOf(false) }
                SettingsCategoryRow(
                    icon = Icons.Filled.GraphicEq,
                    title = "Relay tools",
                    subtitle = "Needs Relay",
                    trailingIcon = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    stateLabel = if (expanded) "Expanded" else "Collapsed",
                    onClick = { expanded = !expanded },
                    isDarkTheme = false,
                    petPerchKey = null,
                )
            }
        }
        val row = compose.onNodeWithText("Relay tools", useUnmergedTree = false)
        row.assert(state("Collapsed")).performClick()
        row.assert(state("Expanded"))
    }
}
