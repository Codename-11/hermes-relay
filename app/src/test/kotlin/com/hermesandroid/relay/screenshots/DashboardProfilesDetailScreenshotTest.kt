package com.hermesandroid.relay.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.ui.screens.DashboardProfilesDetailPreview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class DashboardProfilesDetailScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectedProfileUsesFocusedInspectorAndSafeServerActions() {
        compose.setContent { DashboardProfilesDetailPreview() }

        compose.onNodeWithText("Profiles").assertExists()
        compose.onNodeWithText("Hermes Home").assertExists()
        compose.onNodeWithText("New profile").assertExists()
        compose.onNodeWithText("SOUL").assertExists()
        compose.onRoot().captureRoboImage(
            "build/ui-regression/hermes-management-profiles-detail-dark.png",
        )

        compose.onNodeWithText("Work").performClick()
        compose.onNodeWithTag("profiles-management-detail")
            .performScrollToIndex(6)
        compose.onNodeWithText("Set active").assertExists()
        compose.onNodeWithText("Delete profile").assertExists()
        compose.onRoot().captureRoboImage(
            "build/ui-regression/hermes-management-profiles-detail-work-dark.png",
        )
    }
}
