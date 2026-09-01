package com.hermesandroid.relay.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.data.GitRepositoryRoute
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitStateViewModelTest {
    private val ownerKey = "connection-a\u0000default\u0000dashboard"
    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var application: Application
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        application = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun viewModel(): GitStateViewModel {
        val vm = GitStateViewModel(application)
        vm.configure(DashboardApiClient(server.url("/").toString()), ownerKey, scanningEnabled = true)
        vm.loadRepos()
        return vm
    }

    @Test
    fun `disabled Relay discovery still loads standard session repository`() = runBlocking {
        enqueueJson("""{"branch":"main","changed":0,"staged":0,"unstaged":0,"untracked":0,"files":[]}""")
        val vm = GitStateViewModel(application)
        vm.configure(
            DashboardApiClient(server.url("/").toString()),
            ownerKey,
            scanningEnabled = false,
        )
        vm.setSessionWorkspace(repoRoot = "/workspace/repo", workingDirectory = null)

        vm.loadRepos()
        val standard = withTimeout(5_000) {
            vm.repos.filterIsInstance<GitStateUiState.Ready>().first()
        }
        assertEquals(GitRepositoryRoute.UPSTREAM, standard.repos.single().route)
        assertEquals("/api/git/status?path=%2Fworkspace%2Frepo", server.takeRequest().path)
        assertEquals(1, server.requestCount)

        enqueueJson("""{"branch":"main","changed":0,"staged":0,"unstaged":0,"untracked":0,"files":[]}""")
        enqueueJson("""{"repos":[]}""")
        vm.setScanningEnabled(true)
        withTimeout(5_000) { vm.repos.filterIsInstance<GitStateUiState.Ready>().first() }
        assertEquals("/api/git/status?path=%2Fworkspace%2Frepo", server.takeRequest().path)
        assertEquals("/api/plugins/hermes-relay/git/repos", server.takeRequest().path)
    }

    private fun enqueueJson(body: String) {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    @Test
    fun `loadRepos maps repo list and notice`() = runBlocking {
        enqueueJson(
            """{"repos":[{"id":"alpha","name":"alpha","root":"/p/alpha","current_branch":"main","dirty":false}],"notice":null}""",
        )
        val vm = viewModel()
        val state = withTimeout(5_000) {
            vm.repos.filterIsInstance<GitStateUiState.Ready>().first()
        }
        assertEquals(1, state.repos.size)
        assertEquals("alpha", state.repos.single().name)
        assertNotNull(vm.repos.value)
    }

    @Test
    fun `loadRepos surfaces server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"unknown repository"}"""))
        val vm = viewModel()
        val state = withTimeout(5_000) {
            vm.repos.filterIsInstance<GitStateUiState.Error>().first()
        }
        assertTrue(state.message.isNotBlank())
    }

    @Test
    fun `loadRepos maps missing plugin route to friendly unavailable state`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"detail":"No such API endpoint"}"""),
        )
        val vm = viewModel()
        val state = withTimeout(5_000) {
            vm.repos.filterIsInstance<GitStateUiState.Unavailable>().first()
        }
        assertEquals("Git isn't available on this Hermes host yet.", state.message)
    }

    @Test
    fun `selectRepo loads status and branches and preserves truncation flag`() = runBlocking {
        enqueueJson(
            """{"repos":[{"id":"alpha","name":"alpha","root":"/p/alpha","current_branch":"main","dirty":true}]}""",
        )
        // status
        enqueueJson(
            """{"counts":{"staged":1,"modified":2,"untracked":3},"staged":[{"path":"a.txt"}],"modified":[],"untracked":[],"truncated":true}""",
        )
        // branches
        enqueueJson(
            """{"branches":[{"name":"main","upstream":"origin/main","ahead":1,"behind":0,"is_current":true}]}""",
        )
        val vm = viewModel()
        withTimeout(5_000) { vm.repos.filterIsInstance<GitStateUiState.Ready>().first() }
        vm.selectRepo("alpha")
        val ready = withTimeout(5_000) {
            vm.detail.filterIsInstance<GitRepoDetailState.Ready>().first()
        }
        assertEquals(1, ready.status.counts.staged)
        assertEquals(2, ready.status.counts.modified)
        assertEquals(3, ready.status.counts.untracked)
        assertEquals(-1, ready.status.counts.changes)
        assertEquals(0, ready.status.counts.additions)
        assertEquals(0, ready.status.counts.deletions)
        assertNull(ready.status.staged.single().additions)
        assertTrue(ready.status.truncated)
        assertEquals("main", ready.branches.single().name)
        assertTrue(ready.branches.single().isCurrent)
    }

    @Test
    fun `loadDiff surfaces truncated diff`() = runBlocking {
        enqueueJson("""{"repos":[{"id":"alpha","name":"alpha","root":"/p/alpha"}]}""")
        enqueueJson("""{"counts":{"staged":0,"modified":1,"untracked":0},"staged":[],"modified":[{"path":"a.txt"}],"untracked":[],"truncated":false}""")
        enqueueJson("""{"branches":[]}""")
        enqueueJson("""{"path":"a.txt","kind":"unstaged","diff":"+change","truncated":true}""")

        val vm = viewModel()
        withTimeout(5_000) { vm.repos.filterIsInstance<GitStateUiState.Ready>().first() }
        vm.selectRepo("alpha")
        withTimeout(5_000) { vm.detail.filterIsInstance<GitRepoDetailState.Ready>().first() }
        vm.loadDiff("a.txt", "unstaged")
        val content = withTimeout(5_000) {
            vm.content.filterIsInstance<GitContentViewState.Diff>().first()
        }
        assertEquals("a.txt", content.diff.path)
        assertTrue(content.diff.truncated)
        assertTrue(content.diff.diff.contains("change"))
    }

    @Test
    fun `loadFile surfaces content and truncation`() = runBlocking {
        enqueueJson("""{"repos":[{"id":"alpha","name":"alpha","root":"/p/alpha"}]}""")
        enqueueJson("""{"counts":{"staged":0,"modified":0,"untracked":1},"staged":[],"modified":[],"untracked":[{"path":"new.txt"}],"truncated":false}""")
        enqueueJson("""{"branches":[]}""")
        enqueueJson("""{"path":"new.txt","content":"hello world","truncated":false}""")

        val vm = viewModel()
        withTimeout(5_000) { vm.repos.filterIsInstance<GitStateUiState.Ready>().first() }
        vm.selectRepo("alpha")
        withTimeout(5_000) { vm.detail.filterIsInstance<GitRepoDetailState.Ready>().first() }
        vm.loadFile("new.txt")
        val content = withTimeout(5_000) {
            vm.content.filterIsInstance<GitContentViewState.File>().first()
        }
        assertEquals("hello world", content.file.content)
        assertFalse(content.file.truncated)
    }

    @Test
    fun `selectRepo surfaces status error for unknown repo`() = runBlocking {
        enqueueJson("""{"repos":[{"id":"alpha","name":"alpha","root":"/p/alpha"}]}""")
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"unknown repository: bogus"}"""))
        enqueueJson("""{"branches":[]}""")
        val vm = viewModel()
        withTimeout(5_000) { vm.repos.filterIsInstance<GitStateUiState.Ready>().first() }
        vm.selectRepo("bogus")
        val error = withTimeout(5_000) {
            vm.detail.filterIsInstance<GitRepoDetailState.Error>().first()
        }
        assertTrue(error.message.contains("unknown repository"))
    }

    @Test
    fun `active session repository uses upstream Git without Relay`() = runBlocking {
        enqueueJson(
            """{"branch":"main","changed":2,"staged":0,"unstaged":1,"untracked":1,"added":7,"removed":2,"files":[]}""",
        )
        server.enqueue(
            MockResponse().setResponseCode(404).setBody("""{"detail":"No such API endpoint"}"""),
        )
        val vm = GitStateViewModel(application)
        vm.configure(DashboardApiClient(server.url("/").toString()), ownerKey, scanningEnabled = true)
        vm.setSessionWorkspace(repoRoot = "/workspace/repo", workingDirectory = "/workspace/repo/src")
        vm.loadRepos()

        val ready = withTimeout(5_000) {
            vm.repos.filterIsInstance<GitStateUiState.Ready>().first()
        }
        assertEquals(1, ready.repos.size)
        assertEquals(GitRepositoryRoute.UPSTREAM, ready.repos.single().route)
        assertEquals("/workspace/repo", ready.repos.single().root)
        assertEquals("/api/git/status?path=%2Fworkspace%2Frepo", server.takeRequest().path)
        assertEquals("/api/plugins/hermes-relay/git/repos", server.takeRequest().path)
    }

    @Test
    fun `standard session repository can be selected while Relay discovery is off`() = runBlocking {
        enqueueJson(
            """{"branch":"main","changed":1,"staged":0,"unstaged":1,"untracked":0,"added":2,"removed":0,"files":[{"path":"a.kt","unstaged":true}]}""",
        )
        enqueueJson(
            """{"branch":"main","changed":1,"staged":0,"unstaged":1,"untracked":0,"added":2,"removed":0,"files":[{"path":"a.kt","unstaged":true}]}""",
        )
        enqueueJson("""{"files":[{"path":"a.kt","added":2,"removed":0,"staged":false}]}""")
        enqueueJson("""{"branches":[{"name":"main","checkedOut":true}]}""")
        val vm = GitStateViewModel(application)
        vm.configure(DashboardApiClient(server.url("/").toString()), ownerKey, scanningEnabled = false)
        vm.setSessionWorkspace(repoRoot = "/workspace/repo", workingDirectory = null)
        vm.loadRepos()
        val repos = withTimeout(5_000) {
            vm.repos.filterIsInstance<GitStateUiState.Ready>().first()
        }

        vm.selectRepo(repos.repos.single().id)
        val detail = withTimeout(5_000) {
            vm.detail.filterIsInstance<GitRepoDetailState.Ready>().first()
        }
        assertEquals("a.kt", detail.status.modified.single().path)
        assertNotNull(vm.currentTarget())
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `upstream operational failure does not downgrade to Relay`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"git crashed"}"""))
        enqueueJson(
            """{"repos":[{"id":"alpha","name":"alpha","root":"/workspace/repo"}]}""",
        )
        val vm = GitStateViewModel(application)
        vm.configure(DashboardApiClient(server.url("/").toString()), ownerKey, scanningEnabled = true)
        vm.setSessionWorkspace(repoRoot = "/workspace/repo", workingDirectory = null)
        vm.loadRepos()

        val error = withTimeout(5_000) {
            vm.repos.filterIsInstance<GitStateUiState.Error>().first()
        }
        assertTrue(error.message.contains("HTTP 500"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `upstream session status maps official review and branch shapes`() = runBlocking {
        enqueueJson(
            """{"branch":"feature/x","changed":2,"staged":1,"unstaged":1,"untracked":0,"added":9,"removed":3,"files":[{"path":"a.kt","staged":true},{"path":"b.kt","unstaged":true}]}""",
        )
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"plugin absent"}"""))
        enqueueJson(
            """{"branch":"feature/x","changed":2,"staged":1,"unstaged":1,"untracked":0,"added":9,"removed":3,"files":[{"path":"a.kt","staged":true},{"path":"b.kt","unstaged":true}]}""",
        )
        enqueueJson(
            """{"files":[{"path":"a.kt","added":5,"removed":1,"staged":true},{"path":"b.kt","added":4,"removed":2,"staged":false}],"base":null}""",
        )
        enqueueJson(
            """{"branches":[{"name":"feature/x","checkedOut":true,"isDefault":false,"isRemote":false,"worktreePath":"/workspace/repo"}]}""",
        )
        val vm = GitStateViewModel(application)
        vm.configure(DashboardApiClient(server.url("/").toString()), ownerKey, scanningEnabled = true)
        vm.setSessionWorkspace(repoRoot = "/workspace/repo", workingDirectory = null)
        vm.loadRepos()
        val repos = withTimeout(5_000) {
            vm.repos.filterIsInstance<GitStateUiState.Ready>().first()
        }

        vm.selectRepo(repos.repos.single().id)
        val detail = withTimeout(5_000) {
            vm.detail.filterIsInstance<GitRepoDetailState.Ready>().first()
        }
        assertEquals(2, detail.status.counts.changes)
        assertEquals(9, detail.status.counts.additions)
        assertEquals(3, detail.status.counts.deletions)
        assertEquals("a.kt", detail.status.staged.single().path)
        assertEquals("b.kt", detail.status.modified.single().path)
        assertTrue(detail.branches.single().isCurrent)
    }

    @Test
    fun `missing upstream read route falls back to matching Relay repository`() = runBlocking {
        enqueueJson(
            """{"branch":"main","changed":1,"staged":0,"unstaged":1,"untracked":0,"files":[]}""",
        )
        enqueueJson(
            """{"repos":[{"id":"relay-alpha","name":"repo","root":"/workspace/repo","current_branch":"main","dirty":true}]}""",
        )
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"route unavailable"}"""))
        enqueueJson(
            """{"counts":{"staged":0,"modified":1,"untracked":0},"staged":[],"modified":[{"path":"a.kt"}],"untracked":[],"truncated":false}""",
        )
        enqueueJson(
            """{"branches":[{"name":"main","checkedOut":true,"isDefault":true,"isRemote":false,"worktreePath":"/workspace/repo"}]}""",
        )
        val vm = GitStateViewModel(application)
        vm.configure(DashboardApiClient(server.url("/").toString()), ownerKey, scanningEnabled = true)
        vm.setSessionWorkspace(repoRoot = "/workspace/repo", workingDirectory = null)
        vm.loadRepos()
        val repos = withTimeout(5_000) {
            vm.repos.filterIsInstance<GitStateUiState.Ready>().first()
        }

        vm.selectRepo(repos.repos.single().id)
        val detail = withTimeout(5_000) {
            vm.detail.filterIsInstance<GitRepoDetailState.Ready>().first()
        }
        assertEquals("a.kt", detail.status.modified.single().path)
        val paths = buildList {
            repeat(5) { add(server.takeRequest().path.orEmpty()) }
        }
        assertTrue(paths.contains("/api/plugins/hermes-relay/git/status?repo=relay-alpha"))
    }
}
