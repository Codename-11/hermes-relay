package com.hermesandroid.relay.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitStateWriteViewModelTest {
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

    private fun viewModel(grant: Boolean = true): GitStateViewModel {
        val vm = GitStateViewModel(application)
        vm.configure(DashboardApiClient(server.url("/").toString()))
        vm.setWriteGrant(grant)
        return vm
    }

    private fun enqueueJson(body: String) {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    /** Loads the repo list + selects ``alpha`` so a mutation has a target. */
    private fun selectAlpha(vm: GitStateViewModel) {
        enqueueJson("""{"repos":[{"id":"alpha","name":"alpha","root":"/p/alpha","current_branch":"main","dirty":true}]}""")
        enqueueJson("""{"counts":{"staged":1,"modified":0,"untracked":0},"staged":[{"path":"a.txt"}],"modified":[],"untracked":[],"truncated":false}""")
        enqueueJson("""{"branches":[{"name":"main","upstream":"origin/main","ahead":0,"behind":0,"is_current":true}]}""")
        runBlocking { withTimeout(5_000) { vm.repos.filterIsInstance<GitStateUiState.Ready>().first() } }
        vm.selectRepo("alpha")
        runBlocking { withTimeout(5_000) { vm.detail.filterIsInstance<GitRepoDetailState.Ready>().first() } }
        // Drain the three read requests (repos/status/branches) so the next
        // takeRequest() returns the mutation POST we actually assert on.
        repeat(3) { server.takeRequest() }
    }

    private fun enqueuePostSuccess(head: String) {
        enqueueJson("""{"head":"$head","status":{"counts":{"staged":0,"modified":0,"untracked":0},"staged":[],"modified":[],"untracked":[],"truncated":false}}""")
        // refreshDetail fires two more requests (status + branches).
        enqueueJson("""{"counts":{"staged":0,"modified":0,"untracked":0},"staged":[],"modified":[],"untracked":[],"truncated":false}""")
        enqueueJson("""{"branches":[]}""")
    }

    // ── Grant gating (security first) ──────────────────────────────────────

    @Test
    fun `stage without write grant is refused before any POST`() = runBlocking {
        val vm = viewModel(grant = false)
        selectAlpha(vm)
        vm.stage(listOf("a.txt"))
        val state = withTimeout(5_000) {
            vm.mutation.filterIsInstance<GitMutationState.Error>().first()
        }
        assertTrue(state.message.contains("plugin.api.write"))
        // No write POST was sent (only the 3 read requests for load/select).
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `discard without write grant is refused`() = runBlocking {
        val vm = viewModel(grant = false)
        selectAlpha(vm)
        vm.discard(listOf("a.txt"), GitConfirmationStrings.DISCARD)
        val state = withTimeout(5_000) {
            vm.mutation.filterIsInstance<GitMutationState.Error>().first()
        }
        assertTrue(state.message.contains("plugin.api.write"))
        assertEquals(3, server.requestCount)
    }

    // ── Happy paths ────────────────────────────────────────────────────────

    @Test
    fun `stage sends POST and surfaces success + fresh status`() = runBlocking {
        val vm = viewModel()
        selectAlpha(vm)
        enqueuePostSuccess("abc123")
        vm.stage(listOf("a.txt"))
        val state = withTimeout(5_000) {
            vm.mutation.filterIsInstance<GitMutationState.Success>().first()
        }
        assertEquals("stage", state.label)
        assertEquals("abc123", state.head)
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("/git/stage"))
        assertTrue(req.body.readUtf8().contains("a.txt"))
    }

    @Test
    fun `commit sends message and returns head`() = runBlocking {
        val vm = viewModel()
        selectAlpha(vm)
        enqueuePostSuccess("deadbeef")
        vm.commit("add feature")
        val state = withTimeout(5_000) {
            vm.mutation.filterIsInstance<GitMutationState.Success>().first()
        }
        assertEquals("commit", state.label)
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("/git/commit"))
        assertTrue(req.body.readUtf8().contains("add feature"))
    }

    @Test
    fun `discard echoes the fixed confirmation token`() = runBlocking {
        val vm = viewModel()
        selectAlpha(vm)
        enqueuePostSuccess("abc")
        vm.discard(listOf("a.txt"), GitConfirmationStrings.DISCARD)
        withTimeout(5_000) { vm.mutation.filterIsInstance<GitMutationState.Success>().first() }
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("/git/discard"))
        assertTrue(req.body.readUtf8().contains(GitConfirmationStrings.DISCARD))
    }

    @Test
    fun `push echoes the confirmation token`() = runBlocking {
        val vm = viewModel()
        selectAlpha(vm)
        enqueuePostSuccess("abc")
        vm.push(GitConfirmationStrings.PUSH)
        withTimeout(5_000) { vm.mutation.filterIsInstance<GitMutationState.Success>().first() }
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("/git/push"))
        assertTrue(req.body.readUtf8().contains(GitConfirmationStrings.PUSH))
    }

    @Test
    fun `fetch and pull send their endpoints`() = runBlocking {
        val vm = viewModel()
        selectAlpha(vm)
        enqueuePostSuccess("abc")
        vm.fetch()
        withTimeout(5_000) { vm.mutation.filterIsInstance<GitMutationState.Success>().first() }
        assertTrue(server.takeRequest().path!!.contains("/git/fetch"))
        // Drain the two refreshDetail reads (status + branches) so the next
        // takeRequest() sees only the pull POST.
        repeat(2) { server.takeRequest() }

        enqueuePostSuccess("xyz")
        vm.pull("origin", "main")
        withTimeout(5_000) { vm.mutation.filterIsInstance<GitMutationState.Success>().first() }
        assertTrue(server.takeRequest().path!!.contains("/git/pull"))
    }

    // ── Error branches ─────────────────────────────────────────────────────

    @Test
    fun `commit surfaces server error as readable message`() = runBlocking {
        val vm = viewModel()
        selectAlpha(vm)
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"detail":"commit message must not be empty"}"""),
        )
        vm.commit("   ")
        val state = withTimeout(5_000) {
            vm.mutation.filterIsInstance<GitMutationState.Error>().first()
        }
        assertTrue(state.message.contains("must not be empty"))
    }

    @Test
    fun `discard wrong confirmation surfaces server 403`() = runBlocking {
        val vm = viewModel()
        selectAlpha(vm)
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"detail":"confirmation did not match"}"""),
        )
        vm.discard(listOf("a.txt"), "wrong")
        val state = withTimeout(5_000) {
            vm.mutation.filterIsInstance<GitMutationState.Error>().first()
        }
        assertTrue(state.message.contains("confirmation") || state.message.contains("403"))
    }

    // ── Confirmation gating helpers ────────────────────────────────────────

    @Test
    fun `requiresConfirmation and confirmationFor match destructive ops`() {
        val vm = viewModel()
        assertTrue(vm.requiresConfirmation("discard"))
        assertTrue(vm.requiresConfirmation("push"))
        assertTrue(vm.requiresConfirmation("dirty-checkout"))
        assertEquals(GitConfirmationStrings.DISCARD, vm.confirmationFor("discard"))
        assertEquals(GitConfirmationStrings.PUSH, vm.confirmationFor("push"))
        assertEquals(GitConfirmationStrings.DIRTY_CHECKOUT, vm.confirmationFor("dirty-checkout"))
        assertEquals(null, vm.confirmationFor("commit"))
    }
}
