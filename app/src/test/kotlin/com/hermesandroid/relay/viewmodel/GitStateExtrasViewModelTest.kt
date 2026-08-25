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
class GitStateExtrasViewModelTest {
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

    private fun viewModel(grant: Boolean = true): GitStateViewModel {
        val vm = GitStateViewModel(application)
        vm.configure(DashboardApiClient(server.url("/").toString()), ownerKey)
        vm.setWriteGrant(ownerKey, grant)
        return vm
    }

    private fun enqueueJson(body: String) {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(body),
        )
    }

    private fun selectAlpha(vm: GitStateViewModel) {
        enqueueJson("""{"repos":[{"id":"alpha","name":"alpha","root":"/p/alpha","current_branch":"main","dirty":true}]}""")
        enqueueJson("""{"counts":{"staged":1,"modified":0,"untracked":0},"staged":[{"path":"a.txt"}],"modified":[],"untracked":[],"truncated":false}""")
        enqueueJson("""{"branches":[{"name":"main","upstream":"origin/main","ahead":0,"behind":0,"is_current":true}]}""")
        runBlocking { withTimeout(5_000) { vm.repos.filterIsInstance<GitStateUiState.Ready>().first() } }
        vm.selectRepo("alpha")
        runBlocking { withTimeout(5_000) { vm.detail.filterIsInstance<GitRepoDetailState.Ready>().first() } }
        repeat(3) { server.takeRequest() }
    }

    // ── AI commit message (magic-wand) ─────────────────────────────────────

    @Test
    fun `generate commit message pre-fills the suggestion via selected paths`() = runBlocking {
        val vm = viewModel()
        selectAlpha(vm)
        enqueueJson("""{"message":"feat: add feature","notice":""}""")
        vm.generateCommitMessage(listOf("a.txt"))
        val state = withTimeout(5_000) {
            vm.messageGeneration.filterIsInstance<GitMessageGenerationState.Ready>().first()
        }
        assertEquals("feat: add feature", state.message)
        assertEquals("", state.notice)
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("/git/commit_message_selected"))
        assertTrue(req.body.readUtf8().contains("a.txt"))
    }

    @Test
    fun `generate message without grant is refused before any POST`() = runBlocking {
        val vm = viewModel(grant = false)
        selectAlpha(vm)
        vm.generateCommitMessage(null)
        val state = withTimeout(5_000) {
            vm.messageGeneration.filterIsInstance<GitMessageGenerationState.Ready>().first()
        }
        assertTrue(state.notice.contains("plugin.api.write"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `empty staged diff surfaces notice without error`() = runBlocking {
        val vm = viewModel()
        selectAlpha(vm)
        enqueueJson("""{"message":"","notice":"nothing staged"}""")
        vm.generateCommitMessage(listOf("a.txt"))
        val state = withTimeout(5_000) {
            vm.messageGeneration.filterIsInstance<GitMessageGenerationState.Ready>().first()
        }
        assertEquals("", state.message)
        assertEquals("nothing staged", state.notice)
    }

    // ── Push-after-commit toggle ───────────────────────────────────────────

    @Test
    fun `push after commit defaults off and toggles`() {
        val vm = viewModel()
        assertTrue(!vm.isPushAfterCommitEnabled())
        vm.setPushAfterCommit(true)
        assertTrue(vm.isPushAfterCommitEnabled())
        vm.setPushAfterCommit(false)
        assertTrue(!vm.isPushAfterCommitEnabled())
    }

    // ── Stash-checkout ─────────────────────────────────────────────────────

    @Test
    fun `stash checkout surfaces the stash notice on success`() = runBlocking {
        val vm = viewModel()
        selectAlpha(vm)
        enqueueJson(
            """{"head":"abc","stashed":true,"stash_message":"git-state: feature","status":{"counts":{"staged":0,"modified":0,"untracked":0},"staged":[],"modified":[],"untracked":[],"truncated":false},"branches":[]}""",
        )
        // refreshDetail fires two reads (status + branches).
        enqueueJson("""{"counts":{"staged":0,"modified":0,"untracked":0},"staged":[],"modified":[],"untracked":[],"truncated":false}""")
        enqueueJson("""{"branches":[]}""")
        vm.stashCheckout("feature")
        withTimeout(5_000) { vm.mutation.filterIsInstance<GitMutationState.Success>().first() }
        val notice = withTimeout(5_000) { vm.stashNotice.first { it != null } }!!
        assertTrue(notice.contains("git-state: feature"))
        assertTrue(notice.contains("git stash pop"))
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("/git/stash_checkout"))
        assertTrue(req.body.readUtf8().contains("feature"))
    }

    @Test
    fun `stash checkout without grant is refused before any POST`() = runBlocking {
        val vm = viewModel(grant = false)
        selectAlpha(vm)
        vm.stashCheckout("feature")
        val state = withTimeout(5_000) {
            vm.mutation.filterIsInstance<GitMutationState.Error>().first()
        }
        assertTrue(state.message.contains("plugin.api.write"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `clean stash checkout yields no stash notice`() = runBlocking {
        val vm = viewModel()
        selectAlpha(vm)
        enqueueJson(
            """{"head":"abc","stashed":false,"stash_message":"","status":{"counts":{"staged":0,"modified":0,"untracked":0},"staged":[],"modified":[],"untracked":[],"truncated":false},"branches":[]}""",
        )
        enqueueJson("""{"counts":{"staged":0,"modified":0,"untracked":0},"staged":[],"modified":[],"untracked":[],"truncated":false}""")
        enqueueJson("""{"branches":[]}""")
        vm.stashCheckout("feature")
        withTimeout(5_000) { vm.mutation.filterIsInstance<GitMutationState.Success>().first() }
        // No stash notice for a clean checkout.
        assertEquals(null, vm.stashNotice.value)
    }
}
