package com.hermesandroid.relay.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.data.ChatSession
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.ProfilePresentation
import com.hermesandroid.relay.ui.components.ProfileShelf
import com.hermesandroid.relay.ui.components.SessionDrawerContent
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.ConnectionViewModel
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnreadConversationsScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    @After fun cleanup() { store.clear() }
    @Test fun unreadConversationsAndProfileCounts() = capture("phone", 1f)
    @Test @Config(qualifiers = "w320dp-h700dp-xhdpi")
    fun compactWithLargerText() = capture("compact", 1.3f)

    private fun capture(name: String, fontScale: Float) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val connection = ConnectionViewModel(app).also { store.put("connection", it) }
        val researcher = Profile("research", "fixture", displayName = "Researcher")
        val writer = Profile("writer", "fixture", displayName = "Writer")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                HermesRelayTheme(themePreference = "dark") {
                    Column(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)) {
                        ProfileShelf(connection, listOf(researcher, writer), researcher, researcher,
                            ProfilePresentation(hidden = setOf(com.hermesandroid.relay.data.AgentDisplay.SERVER_DEFAULT_PROFILE_KEY)), "Researcher", unreadCounts = mapOf("research" to 2, "writer" to 1),
                            isProfileLocked = false, lockedProfileName = null, switchEnabled = true,
                            onSelect = {}, onOpenPassport = {}, onOpenSwitcher = {}, onInspect = {},
                            onLock = {}, onUnlock = {}, onHide = {})
                        SessionDrawerContent(
                            sessions = listOf(ChatSession("release", "Release review", null),
                                ChatSession("notes", "Research notes", null), ChatSession("current", "Current conversation", null)),
                            currentSessionId = "current", scopeTitle = "Researcher", activeProfileName = "research",
                            unreadSessionIds = setOf("release", "notes"), asSidebar = true,
                            onNewChat = {}, onSelectSession = {}, onDeleteSession = {}, onRenameSession = { _, _ -> })
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Unread conversations: 2", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Unread conversations: 1", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unread reply"))
            .fetchSemanticsNodes().also { org.junit.Assert.assertEquals(2, it.size) }
        val file = File("build/ui-evidence/unread-conversations-$name.png").apply { parentFile?.mkdirs() }
        compose.onRoot().captureRoboImage(file.absolutePath)
    }
}
