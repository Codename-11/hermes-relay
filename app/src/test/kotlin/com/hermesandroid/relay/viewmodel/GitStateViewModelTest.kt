package com.hermesandroid.relay.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
        vm.configure(DashboardApiClient(server.url("/").toString()), ownerKey)
        return vm
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
}
