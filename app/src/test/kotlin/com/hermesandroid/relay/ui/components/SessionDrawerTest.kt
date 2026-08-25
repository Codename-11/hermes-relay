package com.hermesandroid.relay.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hermesandroid.relay.data.ChatSession
import com.hermesandroid.relay.data.SessionActivityState
import com.hermesandroid.relay.ui.theme.ProfileAccentSwatches
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h720dp-xhdpi")
class SessionDrawerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `unpinned action uses a distinct lighter outlined star`() {
        assertNotEquals(sessionPinIcon(pinned = true), sessionPinIcon(pinned = false))
        assertTrue(sessionPinIcon(pinned = true).name.contains("Star"))
        assertTrue(sessionPinIcon(pinned = false).name.contains("StarBorder"))
        assertEquals(0.45f, UNPINNED_STAR_ALPHA, 0.0f)
    }

    @Test
    fun `archive filter resets when connection cannot restore archived sessions`() {
        assertEquals(
            SessionDrawerFilter.All,
            resolveSessionDrawerFilter(
                filter = SessionDrawerFilter.Archive,
                showThreads = true,
                archiveSupported = false,
            ),
        )
    }

    @Test
    fun `session work labels use a safe repo name branch and pull request number`() {
        val session = ChatSession(
            sessionId = "coding-1",
            title = "Ship it",
            model = null,
            gitRepoRoot = "C:\\worktrees\\hermes-relay\\",
            gitBranch = "feature/android-session-context",
            pullRequestNumber = 134,
            pullRequestUrl = "https://github.com/example/hermes-relay/pull/134",
            pullRequestState = "open",
        )

        assertEquals(
            listOf("hermes-relay", "feature/android-session-context", "PR #134 · Open"),
            sessionWorkLabels(session),
        )
        assertEquals(
            listOf(
                SessionWorkBadgeKind.PROJECT,
                SessionWorkBadgeKind.BRANCH,
                SessionWorkBadgeKind.PULL_REQUEST,
            ),
            sessionWorkBadges(session).map(SessionWorkBadge::kind),
        )
    }

    @Test
    fun `session rows identify project and branch badges`() {
        compose.setContent {
            MaterialTheme {
                SessionDrawerContent(
                    sessions = listOf(
                        ChatSession(
                            sessionId = "coding-1",
                            title = "Ship it",
                            model = null,
                            gitRepoRoot = "/work/hermes-relay",
                            gitBranch = "feature/chat-polish",
                        ),
                    ),
                    currentSessionId = null,
                    onNewChat = {},
                    onSelectSession = {},
                    onDeleteSession = {},
                    onRenameSession = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("hermes-relay").assertIsDisplayed()
        compose.onNodeWithContentDescription("Project: hermes-relay").assertIsDisplayed()
        compose.onNodeWithContentDescription("Branch: feature/chat-polish").assertIsDisplayed()
    }

    @Test
    fun `session work labels stay empty on older hosts`() {
        assertTrue(
            sessionWorkLabels(
                ChatSession(sessionId = "legacy", title = null, model = null),
            ).isEmpty(),
        )
    }

    @Test
    fun `reordered leading session remains visible after drawer refresh`() {
        var sessions by mutableStateOf(
            List(15) { index ->
                ChatSession(
                    sessionId = "session-$index",
                    title = "Session $index",
                    model = null,
                    lastActivityAt = 100L - index,
                )
            },
        )

        compose.setContent {
            MaterialTheme {
                SessionDrawerContent(
                    sessions = sessions,
                    currentSessionId = null,
                    isOpen = true,
                    onNewChat = {},
                    onSelectSession = {},
                    onDeleteSession = {},
                    onRenameSession = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag(SESSION_DRAWER_LIST_TAG)
            .performScrollToNode(hasText("Session 14"))
        compose.onNodeWithText("Session 14").assertIsDisplayed()

        compose.runOnIdle {
            sessions = sessions.map { session ->
                if (session.sessionId == "session-10") {
                    session.copy(title = "Now latest", lastActivityAt = 1_000L)
                } else {
                    session
                }
            }
        }

        compose.onNodeWithText("Now latest").assertIsDisplayed()
    }

    @Test
    fun `all profiles toggle renders duplicate ids together in the primary list`() {
        var pinned: Triple<String, String, Boolean>? = null
        compose.setContent {
            MaterialTheme {
                SessionDrawerContent(
                    sessions = listOf(ChatSession("same", "Current", null)),
                    currentSessionId = null,
                    activeProfileName = "alpha",
                    allProfilesSupported = true,
                    allProfileSessions = listOf(
                        ProfileSessionRow("alpha", ChatSession("same", "Alpha session", null)),
                        ProfileSessionRow("beta", ChatSession("same", "Beta session", null)),
                    ),
                    onRefreshAllProfiles = {},
                    onSelectProfileSession = { _, _ -> },
                    onSetProfileSessionPinned = { profile, sessionId, value ->
                        pinned = Triple(profile, sessionId, value)
                    },
                    onNewChat = {},
                    onSelectSession = {},
                    onDeleteSession = {},
                    onRenameSession = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("All Profiles").performClick()

        compose.onNodeWithText("Alpha session").assertIsDisplayed()
        compose.onNodeWithText("Beta session").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Session actions")[0]
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithText("Pin session").performClick()
        compose.runOnIdle { assertEquals(Triple("alpha", "same", true), pinned) }
    }

    @Test
    fun `opening an owned session keeps all profiles browsing selected`() {
        var scopeTitle by mutableStateOf("Mizu Sessions")
        compose.setContent {
            MaterialTheme {
                SessionDrawerContent(
                    sessions = emptyList(),
                    currentSessionId = null,
                    scopeTitle = scopeTitle,
                    activeProfileName = "mizu",
                    allProfilesSupported = true,
                    allProfileSessions = listOf(
                        ProfileSessionRow("mizu", ChatSession("m", "Mizu chat", null)),
                        ProfileSessionRow("x-bot", ChatSession("x", "X Bot chat", null)),
                    ),
                    onRefreshAllProfiles = {},
                    onSelectProfileSession = { _, _ -> scopeTitle = "X Bot Sessions" },
                    onNewChat = {},
                    onSelectSession = {},
                    onDeleteSession = {},
                    onRenameSession = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("All Profiles").performClick()
        compose.onNodeWithText("X Bot chat").performClick()

        compose.onNodeWithText("Mizu chat").assertIsDisplayed()
        compose.onNodeWithText("X Bot Sessions").assertDoesNotExist()
        compose.onNodeWithText("2 profiles · 2 sessions").assertIsDisplayed()
    }

    @Test
    fun `all profiles scope survives activity state restoration`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                SessionDrawerContent(
                    sessions = listOf(ChatSession("m", "Mizu chat", null)),
                    currentSessionId = null,
                    activeProfileName = "mizu",
                    allProfilesSupported = true,
                    allProfileSessions = listOf(
                        ProfileSessionRow("mizu", ChatSession("m", "Mizu chat", null)),
                        ProfileSessionRow("x-bot", ChatSession("x", "X Bot chat", null)),
                    ),
                    onRefreshAllProfiles = {},
                    onSelectProfileSession = { _, _ -> },
                    onNewChat = {},
                    onSelectSession = {},
                    onDeleteSession = {},
                    onRenameSession = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("All Profiles").performClick()
        compose.onNodeWithText("X Bot chat").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("X Bot chat").assertIsDisplayed()
        compose.onNodeWithText("2 profiles · 2 sessions").assertIsDisplayed()
    }

    @Test
    fun `profile lock hides all profiles and exits its saved browser scope`() {
        var allProfilesSupported by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                SessionDrawerContent(
                    sessions = listOf(ChatSession("m", "Mizu chat", null)),
                    currentSessionId = null,
                    scopeTitle = "Mizu Sessions",
                    activeProfileName = "mizu",
                    allProfilesSupported = allProfilesSupported,
                    allProfileSessions = listOf(
                        ProfileSessionRow("mizu", ChatSession("m", "Mizu chat", null)),
                        ProfileSessionRow("x-bot", ChatSession("x", "X Bot chat", null)),
                    ),
                    onRefreshAllProfiles = {},
                    onSelectProfileSession = { _, _ -> },
                    onNewChat = {},
                    onSelectSession = {},
                    onDeleteSession = {},
                    onRenameSession = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("All Profiles").performClick()
        compose.onNodeWithText("X Bot chat").assertIsDisplayed()

        compose.runOnIdle { allProfilesSupported = false }

        compose.onNodeWithText("All Profiles").assertDoesNotExist()
        compose.onNodeWithText("X Bot chat").assertDoesNotExist()
        compose.onNodeWithText("Mizu chat").assertIsDisplayed()
    }

    @Test
    fun `new chat from all profiles requests an explicit default draft`() {
        var scopedNewChats = 0
        var defaultNewChats = 0
        compose.setContent {
            MaterialTheme {
                SessionDrawerContent(
                    sessions = emptyList(),
                    currentSessionId = null,
                    allProfilesSupported = true,
                    allProfileSessions = listOf(
                        ProfileSessionRow("default", ChatSession("d", "Default chat", null)),
                    ),
                    onRefreshAllProfiles = {},
                    onSelectProfileSession = { _, _ -> },
                    onNewChat = { scopedNewChats++ },
                    onNewDefaultChat = { defaultNewChats++ },
                    onSelectSession = {},
                    onDeleteSession = {},
                    onRenameSession = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("All Profiles").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("New Chat").performClick()

        compose.runOnIdle {
            assertEquals(0, scopedNewChats)
            assertEquals(1, defaultNewChats)
        }
    }

    @Test
    fun `customize sessions exposes desktop backed view variants`() {
        compose.setContent {
            MaterialTheme {
                SessionDrawerContent(
                    sessions = listOf(ChatSession("one", "One", null)),
                    currentSessionId = null,
                    onNewChat = {},
                    onSelectSession = {},
                    onDeleteSession = {},
                    onRenameSession = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Customize sessions").performClick()

        compose.onNodeWithText("Group by").assertIsDisplayed()
        compose.onNodeWithText("Order by").assertIsDisplayed()
        compose.onNodeWithText("Show details").assertIsDisplayed()
        compose.onNodeWithText("Filters").assertIsDisplayed()
    }

    @Test
    fun `drawer renders every authoritative activity phase distinctly`() {
        val states = SessionActivityState.entries
        compose.setContent {
            MaterialTheme {
                SessionDrawerContent(
                    sessions = states.mapIndexed { index, _ ->
                        ChatSession("session-$index", "Session $index", null)
                    },
                    currentSessionId = null,
                    activeProfileName = "default",
                    activityStates = states.mapIndexed { index, state ->
                        "default:session-$index" to state
                    }.toMap(),
                    animationEnabled = false,
                    onNewChat = {},
                    onSelectSession = {},
                    onDeleteSession = {},
                    onRenameSession = { _, _ -> },
                )
            }
        }

        listOf(
            "Starting",
            "Working",
            "Needs input",
            "Background work",
            "Checking",
            "Unavailable",
        ).forEach { label ->
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `rest recency alone renders no working badge`() {
        compose.setContent {
            MaterialTheme {
                SessionDrawerContent(
                    sessions = listOf(
                        ChatSession(
                            "recent",
                            "Recently updated",
                            null,
                            recentlyActive = true,
                        ),
                    ),
                    currentSessionId = null,
                    onNewChat = {},
                    onSelectSession = {},
                    onDeleteSession = {},
                    onRenameSession = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Working").assertDoesNotExist()
    }

    @Test
    fun `all profiles customization can override a profile identity color`() {
        var changed: Pair<String, String?>? = null
        compose.setContent {
            MaterialTheme {
                SessionDrawerContent(
                    sessions = emptyList(),
                    currentSessionId = null,
                    allProfilesSupported = true,
                    allProfileSessions = listOf(
                        ProfileSessionRow("alpha", ChatSession("a", "Alpha session", null)),
                        ProfileSessionRow("beta", ChatSession("b", "Beta session", null)),
                    ),
                    onProfileColorChange = { profile, color -> changed = profile to color },
                    onRefreshAllProfiles = {},
                    onSelectProfileSession = { _, _ -> },
                    onNewChat = {},
                    onSelectSession = {},
                    onDeleteSession = {},
                    onRenameSession = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("All Profiles").performClick()
        compose.onNodeWithText("Customize sessions").performClick()
        compose.onNodeWithText("Profile colors").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Set alpha profile color to ${ProfileAccentSwatches.first()}",
        ).performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals("alpha" to ProfileAccentSwatches.first(), changed)
        }
    }
}
