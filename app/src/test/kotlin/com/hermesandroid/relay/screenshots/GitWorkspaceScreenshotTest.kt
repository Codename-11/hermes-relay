package com.hermesandroid.relay.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.ui.components.ChatGitContextButton
import com.hermesandroid.relay.ui.components.ChatGitWorkspaceRail
import com.hermesandroid.relay.ui.components.ChatGitWorkspaceSummary
import com.hermesandroid.relay.ui.screens.GitStateScreen
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import com.hermesandroid.relay.viewmodel.GitStateViewModel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h800dp-432dpi")
class GitWorkspaceScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun detailWorkspaceMatchesApprovedInformationHierarchy() {
        enqueue("""{"repos":[{"id":"hermes-relay","name":"hermes-relay","root":"/srv/projects/hermes-relay","current_branch":"main","dirty":true}]}""")
        enqueue(
            """{"counts":{"staged":1,"modified":1,"untracked":1,"changes":3,"additions":24,"deletions":7},"staged":[{"path":"app/src/main/kotlin/RelayApp.kt","additions":12,"deletions":3}],"modified":[{"path":"plugin/git_state.py","additions":8,"deletions":4}],"untracked":[{"path":"docs/git-workspace.md","additions":null,"deletions":null}],"truncated":false}""",
        )
        enqueue("""{"branches":[{"name":"main","upstream":"origin/main","ahead":1,"behind":0,"is_current":true},{"name":"dev","upstream":"origin/dev","ahead":0,"behind":0,"is_current":false}]}""")
        enqueue(
            """{"path":"plugin/git_state.py","kind":"unstaged","diff":"@@ repository containment @@\n+ def is_within_repo(path):\n-    return false\n+    return path.startswith(repo_root)","truncated":false}""",
        )
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = GitStateViewModel(app)
        val owner = "visual-owner"
        viewModel.configure(DashboardApiClient(server.url("/").toString()), owner)
        viewModel.setWriteGrant(owner, true)

        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                GitStateScreen(viewModel = viewModel, onBack = {})
            }
        }

        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("3 changes").assertExists() }.isSuccess
        }
        compose.onNodeWithContentDescription("Select plugin/git_state.py").performClick()
        compose.onNodeWithText("git_state.py").performClick()
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("@@ repository containment @@", substring = true).assertExists() }.isSuccess
        }
        compose.onRoot().captureRoboImage("build/store-shots/15_git_workspace.png")
    }

    @Test
    fun chatRailMatchesApprovedCompactTreatment() {
        compose.setContent {
            HermesRelayTheme(appThemeId = "hermes-relay", themePreference = "dark") {
                Box(
                    Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background),
                ) {
                    ChatGitContextButton(
                        onClick = {},
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                    )
                    Column(
                        Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        ChatGitWorkspaceRail(
                            summary = ChatGitWorkspaceSummary("main", 3, 24, 7),
                            onClick = {},
                        )
                        Box(
                            Modifier.fillMaxWidth().background(
                                androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
                                androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                            ).padding(vertical = 28.dp),
                        )
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/ui-evidence/chat-git-workspace-rail.png")
    }

    private fun enqueue(body: String) {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }
}
