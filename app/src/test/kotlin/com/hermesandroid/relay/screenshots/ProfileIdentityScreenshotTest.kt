package com.hermesandroid.relay.screenshots

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.ProfilePresentation
import com.hermesandroid.relay.ui.components.ProfileSwitcherSheet
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class ProfileIdentityScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun profileSwitcher() = render("profile-switcher")

    @Test fun enlargedText() = render("profile-switcher-large-text", fontScale = 1.5f)

    @Test fun longName() = render("profile-switcher-long-name", fontScale = 1.5f,
        displayName = "Research and planning assistant for the whole team")

    @Test
    @Config(qualifiers = "w720dp-h360dp-xhdpi")
    fun landscape() = render("profile-switcher-landscape")

    @Test
    @Config(qualifiers = "w840dp-h720dp-xhdpi")
    fun expanded() = render("profile-switcher-expanded")

    private fun render(file: String, fontScale: Float = 1f, displayName: String = "Guide") {
        RuntimeEnvironment.setFontScale(fontScale)
        val agent = Profile(name = "guide", model = "example-model", displayName = displayName)
        val vm = mockk<ConnectionViewModel>(relaxed = true)
        every { vm.profileIconFlow(any()) } returns MutableStateFlow(null)
        every { vm.serverDefaultDisplayProfile } returns MutableStateFlow(agent)
        val selected = mutableStateOf<Profile?>(null)
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    ProfileSwitcherSheet(vm, listOf(Profile("default", "root-model"), agent), selected.value,
                        selected.value ?: agent, ProfilePresentation(), false, true, { selected.value = it }, {}, {})
                }
            }
        }
        compose.onAllNodesWithText(displayName, substring = false).assertCountEquals(1)
        compose.onNodeWithText("Follow server default").assertIsDisplayed().assertIsOn()
            .assertHeightIsAtLeast(48.dp)
        val output = File("build/ui-evidence/$file.png")
        output.parentFile?.mkdirs()
        compose.onRoot().captureRoboImage(output.absolutePath)
        compose.onNodeWithText("Follow server default").performClick()
        compose.runOnIdle { assertEquals("guide", selected.value?.name) }
        compose.onNodeWithText("Follow server default").assertIsOff()
        compose.onNodeWithText("Follow server default").performClick()
        compose.runOnIdle { assertEquals(null, selected.value) }
        // Scrollable even in landscape or at enlarged text sizes.
        compose.onNodeWithText("default", substring = false).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("default", selected.value?.name) }
        compose.onNodeWithText("default", substring = false).assertIsSelected()
    }
}
