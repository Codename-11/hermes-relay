package com.hermesandroid.relay.viewmodel

import android.os.Handler
import android.os.Looper
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Attachment
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.ChatTurnAskCheckpoint
import com.hermesandroid.relay.data.ChatTurnAssistantCheckpoint
import com.hermesandroid.relay.data.ChatTurnCheckpoint
import com.hermesandroid.relay.data.ChatTurnCheckpointStore
import com.hermesandroid.relay.data.ChatTurnToolCheckpoint
import com.hermesandroid.relay.data.ChatTurnUserCheckpoint
import com.hermesandroid.relay.data.HermesCardDispatch
import com.hermesandroid.relay.data.MessageRole
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.ProactiveInboxEntry
import com.hermesandroid.relay.data.SessionTransport
import com.hermesandroid.relay.data.SessionActivityState
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.network.relay.ProactiveMessage
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.GatewayChatClient
import com.hermesandroid.relay.network.upstream.GatewayClientHarness
import com.hermesandroid.relay.network.upstream.GatewayConnectionState
import com.hermesandroid.relay.network.upstream.HermesApiClient
import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.network.upstream.models.SessionItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelGatewayInboundTurnTest {

    private class MemoryCheckpointStore(
        var checkpoint: ChatTurnCheckpoint? = null,
    ) : ChatTurnCheckpointStore {
        override suspend fun read(): ChatTurnCheckpoint? = checkpoint
        override suspend fun write(checkpoint: ChatTurnCheckpoint) {
            this.checkpoint = checkpoint
        }
        override suspend fun clear() {
            checkpoint = null
        }
    }

    private lateinit var gatewayHarness: GatewayClientHarness
    private lateinit var apiServer: MockWebServer
    private lateinit var gatewayScope: CoroutineScope
    private lateinit var gatewayClient: GatewayChatClient
    private lateinit var serverWs: WebSocket
    private lateinit var handler: ChatHandler
    private lateinit var viewModel: ChatViewModel
    @Volatile
    private var persistedHistory: List<MessageItem> = emptyList()
    @Volatile
    private var holdCompletionsStream = false
    private val apiCompletionsRequestCount = AtomicInteger(0)
    private val apiMessageRequestCount = AtomicInteger(0)

    @Before
    fun setUp() {
        gatewayHarness = GatewayClientHarness()
        apiServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/v1/chat/completions") {
                        apiCompletionsRequestCount.incrementAndGet()
                    }
                    if (request.path?.contains("/messages") == true) {
                        apiMessageRequestCount.incrementAndGet()
                    }
                    return if (holdCompletionsStream && request.path == "/v1/chat/completions") {
                        MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    } else {
                        MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        gatewayClient = GatewayChatClient(
            initialDashboardClient = DashboardApiClient(
                baseUrl = gatewayHarness.server.url("/").toString().trimEnd('/'),
                okHttpClient = OkHttpClient(),
            ),
            okHttpClient = OkHttpClient(),
            // Match production ordering: Gateway callbacks are posted from the
            // OkHttp WebSocket thread onto Android's main looper.
            callbackDispatcher = { block ->
                Handler(Looper.getMainLooper()).post(block)
            },
            scope = gatewayScope,
        )
        handler = ChatHandler().also { it.setSessionId(STORED_SESSION_ID) }
        persistedHistory = emptyList()
        holdCompletionsStream = false
        apiCompletionsRequestCount.set(0)
        apiMessageRequestCount.set(0)
        viewModel = ChatViewModel().also {
            it.initialize(
                HermesApiClient(apiServer.url("/").toString(), "test-key"),
                handler,
            )
            it.streamingEndpoint = "gateway"
            it.setProfileMessageLoader {
                Result.success(persistedHistory)
            }
            it.updateGatewayClient(gatewayClient)
        }
        assertTrue(runBlocking { gatewayClient.prewarmAwait(STORED_SESSION_ID) })
        serverWs = gatewayHarness.awaitServerSocket()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun offlineGatewaySendPublishesRetryableFailureAndKeepsPrompt() {
        DiagnosticsLog.clear()
        viewModel.updateGatewayClient(null)
        viewModel.initialize(null, handler)
        viewModel.streamingEndpoint = "gateway"

        viewModel.sendMessage("Retry this after reconnect")

        val failure = viewModel.chatFailure.value
        assertEquals(STORED_SESSION_ID, failure?.sessionId)
        assertEquals(ChatFailureRoute.GATEWAY, failure?.route)
        assertTrue(failure?.recoverable == true)
        assertTrue(failure?.rawError.orEmpty().contains("no API fallback"))
        assertEquals("Retry this after reconnect", handler.lastSentMessage.value)
        assertTrue(handler.messages.value.isEmpty())
        val diagnostic = DiagnosticsLog.recent(setOf(DiagnosticCategory.Session), 1).single()
        assertEquals("gateway", diagnostic.endpointRole)
        assertEquals("chat response", diagnostic.operation)
    }

    @Test
    fun explicitProfileHistoryFailureSurfacesAndNeverFallsBackAcrossProfiles() {
        DiagnosticsLog.clear()
        apiMessageRequestCount.set(0)
        val owner = Profile(name = "owner", model = "model-a", description = "Owner")
        viewModel.setSelectedProfileProvider { owner }
        viewModel.setSessionProfileNameProvider { owner.name }
        viewModel.setProfileMessageLoaderWithMode { profileName, sessionId, _ ->
            assertEquals(owner.name, profileName)
            assertEquals("owner-session", sessionId)
            Result.failure(IllegalStateException("profile history unavailable"))
        }

        assertTrue(
            viewModel.openProfileSession(
                profileName = owner.name,
                profile = owner,
                contextKey = AgentDisplay.profileContextKey("connection-a", owner.name),
                sessionId = "owner-session",
            ),
        )

        awaitCondition { viewModel.chatFailure.value?.turnId == "history-owner-session" }
        val failure = viewModel.chatFailure.value
        assertEquals("owner-session", failure?.sessionId)
        assertEquals(ChatFailureRoute.GATEWAY, failure?.route)
        assertFalse(failure?.recoverable ?: true)
        assertTrue(failure?.rawError.orEmpty().contains("profile history unavailable"))
        assertEquals(0, apiMessageRequestCount.get())
        val diagnostic = DiagnosticsLog.recent(setOf(DiagnosticCategory.Session), 1).single()
        assertEquals("Hermes chat history failed", diagnostic.title)
        assertEquals("load chat history", diagnostic.operation)
        assertEquals("gateway", diagnostic.endpointRole)
    }

    @Test
    fun missingRequiredProfileHistoryLoaderFailsClosedWithoutApiRead() {
        DiagnosticsLog.clear()
        apiMessageRequestCount.set(0)
        val owner = Profile(name = "owner", model = "model-a", description = "Owner")
        viewModel.setSelectedProfileProvider { owner }
        viewModel.setSessionProfileNameProvider { owner.name }
        viewModel.clearProfileMessageLoader()

        assertTrue(
            viewModel.openProfileSession(
                profileName = owner.name,
                profile = owner,
                contextKey = AgentDisplay.profileContextKey("connection-a", owner.name),
                sessionId = "missing-loader-session",
            ),
        )

        awaitCondition { viewModel.chatFailure.value?.sessionId == "missing-loader-session" }
        assertFalse(viewModel.chatFailure.value?.recoverable ?: true)
        assertTrue(
            viewModel.chatFailure.value?.rawError.orEmpty()
                .contains("Profile-scoped conversation history is unavailable"),
        )
        assertEquals(0, apiMessageRequestCount.get())
    }

    @Test
    fun ordinarySessionSwitchFailureSettlesLoadingAndSurfacesError() {
        DiagnosticsLog.clear()
        viewModel.setProfileMessageLoaderWithMode { _, sessionId, _ ->
            Result.failure(IllegalStateException("history failed for $sessionId"))
        }

        viewModel.switchSession("failed-switch-session")

        awaitCondition {
            !viewModel.isLoadingHistory.value &&
                viewModel.chatFailure.value?.sessionId == "failed-switch-session"
        }
        val failure = viewModel.chatFailure.value
        assertFalse(failure?.recoverable ?: true)
        assertTrue(failure?.rawError.orEmpty().contains("failed-switch-session"))
        assertEquals("failed-switch-session", handler.currentSessionId.value)
    }

    @Test
    fun supersededHistoryFailureCannotClearOrErrorNewerSession() {
        DiagnosticsLog.clear()
        val oldLoadStarted = CompletableDeferred<Unit>()
        val releaseOldLoad = CompletableDeferred<Unit>()
        viewModel.setProfileMessageLoaderWithMode { _, sessionId, _ ->
            when (sessionId) {
                "old-session" -> {
                    oldLoadStarted.complete(Unit)
                    releaseOldLoad.await()
                    Result.failure(IllegalStateException("stale history failure"))
                }
                "new-session" -> Result.success(
                    listOf(
                        MessageItem(
                            id = "new-answer",
                            sessionId = sessionId,
                            role = "assistant",
                            content = JsonPrimitive("New session transcript"),
                        ),
                    ),
                )
                else -> Result.success(emptyList())
            }
        }

        viewModel.switchSession("old-session")
        awaitCondition { oldLoadStarted.isCompleted }
        viewModel.switchSession("new-session")
        awaitCondition {
            !viewModel.isLoadingHistory.value &&
                handler.messages.value.any { it.content == "New session transcript" }
        }

        releaseOldLoad.complete(Unit)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)

        assertEquals("new-session", handler.currentSessionId.value)
        assertTrue(handler.messages.value.any { it.content == "New session transcript" })
        assertNull(viewModel.chatFailure.value)
        assertTrue(
            DiagnosticsLog.recent(setOf(DiagnosticCategory.Session))
                .none { it.detail.orEmpty().contains("stale history failure") },
        )
    }

    @Test
    fun activeListWorkingThenDisappearanceSettlesDespiteRestRecency() {
        bindActivityTestDirectory()
        handler.updateSessions(
            listOf(SessionItem(id = STORED_SESSION_ID, title = "Recent", isActive = true)),
        )
        gatewayHarness.activeSessionListPayload = activeSessionPayload("working")

        viewModel.setChatVisible(true)
        gatewayHarness.awaitRpc("session.active_list")
        awaitCondition {
            viewModel.backgroundSessionActivityStates.value["default:$STORED_SESSION_ID"] ==
                SessionActivityState.Working
        }

        gatewayHarness.activeSessionListPayload = buildJsonObject {
            put("sessions", buildJsonArray { })
        }
        viewModel.requestSessionActivityRefresh()
        gatewayHarness.awaitRpcCount("session.active_list", 2)
        awaitCondition {
            "default:$STORED_SESSION_ID" !in viewModel.backgroundSessionActivityStates.value
        }
    }

    @Test
    fun activeListWaitingProjectsNeedsInput() {
        bindActivityTestDirectory()
        gatewayHarness.activeSessionListPayload = activeSessionPayload("waiting")

        viewModel.setChatVisible(true)
        gatewayHarness.awaitRpc("session.active_list")

        awaitCondition {
            viewModel.backgroundSessionActivityStates.value["default:$STORED_SESSION_ID"] ==
                SessionActivityState.NeedsInput
        }
    }

    @Test
    fun unsupportedActiveListLeavesRowsNeutralAcrossDirectoryRefresh() {
        bindActivityTestDirectory()
        handler.updateSessions(
            listOf(SessionItem(id = STORED_SESSION_ID, title = "Recent", isActive = true)),
        )
        gatewayHarness.methodNotFound += "session.active_list"

        viewModel.setChatVisible(true)
        gatewayHarness.awaitRpc("session.active_list")

        awaitCondition {
            "default:$STORED_SESSION_ID" !in viewModel.backgroundSessionActivityStates.value
        }

        viewModel.updateSessionActivityDirectory(
            rows = listOf("default" to STORED_SESSION_ID),
        )

        assertFalse("default:$STORED_SESSION_ID" in viewModel.backgroundSessionActivityStates.value)
    }

    @Test
    fun rejectedAdmissionCannotLeaveStartingStatusStale() {
        bindActivityTestDirectory()
        gatewayClient.clearSession()
        gatewayHarness.rpcErrors["session.resume"] = 4090 to "stored session is unavailable"

        viewModel.sendMessage("This admission should fail")
        gatewayHarness.awaitRpc("session.resume")

        awaitCondition { !handler.isStreaming.value }
        assertTrue(
            viewModel.backgroundSessionActivityStates.value["default:$STORED_SESSION_ID"] !=
                SessionActivityState.Starting,
        )
    }

    @After
    fun tearDown() {
        DiagnosticsLog.clear()
        viewModel.updateGatewayClient(null)
        gatewayClient.shutdown()
        gatewayScope.cancel()
        gatewayHarness.shutdown()
        apiServer.shutdown()
    }

    @Test
    fun gatewaySessionBindingUsesResolvedServerDefaultProfile() {
        viewModel.setSelectedProfileProvider { null }
        viewModel.setSessionProfileNameProvider { "victor" }

        assertEquals("victor", gatewayClient.sessionProfileProvider())
    }

    @Test
    fun allProfilesOpenScopesHistoryResumeAndSendToSelectedOwner() {
        val global = Profile(name = "mizu", model = "grok-4.5", description = "Mizu")
        val owner = Profile(name = "x-bot", model = "grok-4.3", description = "X Bot")
        var loadedProfile: String? = null
        var persistedSession = "unchanged"
        viewModel.setSelectedProfileProvider { global }
        viewModel.setSessionProfileNameProvider { global.name }
        viewModel.onSessionChanged = { persistedSession = it ?: "cleared" }
        viewModel.setProfileMessageLoaderWithMode { profileName, _, _ ->
            loadedProfile = profileName
            Result.success(emptyList())
        }

        viewModel.openProfileSession(
            profileName = owner.name,
            profile = owner,
            contextKey = AgentDisplay.profileContextKey("connection-a", owner.name),
            sessionId = "x-bot-session",
        )

        awaitCondition { loadedProfile == owner.name }
        assertEquals(owner.name, viewModel.conversationBinding.value.profileName)
        assertEquals(owner.name, gatewayClient.sessionProfileProvider())
        assertEquals("X-bot", handler.activeAgentName)
        assertEquals("x-bot-session", persistedSession)

        viewModel.switchProfileContext(
            AgentDisplay.profileContextKey("connection-a", global.name),
            sessionId = null,
        )
        assertFalse(viewModel.conversationBinding.value.hasExplicitOwner)
        assertEquals(global.name, gatewayClient.sessionProfileProvider())

        viewModel.openProfileSession(
            profileName = owner.name,
            profile = owner,
            contextKey = AgentDisplay.profileContextKey("connection-a", owner.name),
            sessionId = "x-bot-session",
        )
        assertEquals(owner.name, viewModel.conversationBinding.value.profileName)

        viewModel.createNewChat()
        assertTrue(viewModel.conversationBinding.value.hasExplicitOwner)
        assertEquals(owner.name, viewModel.conversationBinding.value.profileName)
        assertNull(viewModel.conversationBinding.value.sessionId)
        assertEquals(owner.name, gatewayClient.sessionProfileProvider())

        viewModel.reconcileProfileContext(
            AgentDisplay.profileContextKey("connection-a", owner.name),
            sessionId = "x-bot-session",
        )
        assertNull(viewModel.conversationBinding.value.sessionId)
        assertNull(handler.currentSessionId.value)
    }

    @Test
    fun allProfilesSwitchBetweenDifferentOwnersReplacesVisibleIdentity() {
        val beta = Profile(name = "beta", model = "model-b", description = "Beta")
        val alpha = Profile(name = "alpha", model = "model-a", description = "Alpha")
        var selected = beta
        viewModel.setSelectedProfileProvider { selected }
        viewModel.setSessionProfileNameProvider { selected.name }
        viewModel.setProfileSelectionHandler { profile ->
            selected = requireNotNull(profile)
            true
        }

        viewModel.openProfileSession(
            profileName = alpha.name,
            profile = alpha,
            contextKey = AgentDisplay.profileContextKey("connection-a", alpha.name),
            sessionId = "alpha-session",
        )
        assertEquals(alpha, selected)
        assertEquals(alpha.name, viewModel.conversationBinding.value.profileName)
        assertEquals("Alpha", handler.activeAgentName)

        viewModel.openProfileSession(
            profileName = beta.name,
            profile = beta,
            contextKey = AgentDisplay.profileContextKey("connection-a", beta.name),
            sessionId = "beta-session",
        )

        assertEquals(beta, selected)
        assertEquals(beta.name, viewModel.conversationBinding.value.profileName)
        assertEquals(beta.name, gatewayClient.sessionProfileProvider())
        assertEquals("Beta", handler.activeAgentName)
        assertEquals("beta-session", handler.currentSessionId.value)
    }

    @Test
    fun profileLockRejectsCrossProfileOpenBeforeSelectionOrChatStateChanges() {
        val beta = Profile(name = "beta", model = "model-b", description = "Beta")
        val alpha = Profile(name = "alpha", model = "model-a", description = "Alpha")
        var selectionCalls = 0
        viewModel.setSelectedProfileProvider { beta }
        viewModel.setSessionProfileNameProvider { beta.name }
        viewModel.setLockedProfileNameProvider { beta.name }
        viewModel.setProfileSelectionHandler {
            selectionCalls += 1
            true
        }

        val opened = viewModel.openProfileSession(
            profileName = alpha.name,
            profile = alpha,
            contextKey = AgentDisplay.profileContextKey("connection-a", alpha.name),
            sessionId = "alpha-session",
        )

        assertFalse(opened)
        assertEquals(0, selectionCalls)
        assertFalse(viewModel.conversationBinding.value.isBound)
        assertEquals(STORED_SESSION_ID, handler.currentSessionId.value)
    }

    @Test
    fun lifecycleReconciliationKeepsExplicitOwnerAndScopesDrawerRefreshToIt() {
        val global = Profile(name = "mizu", model = "grok-4.5", description = "Mizu")
        val owner = Profile(name = "x-bot", model = "grok-4.3", description = "X Bot")
        var listedProfile: String? = null
        var persistedSession = "unchanged"
        viewModel.setSelectedProfileProvider { global }
        viewModel.setSessionProfileNameProvider { global.name }
        viewModel.onSessionChanged = { persistedSession = it ?: "cleared" }
        viewModel.setProfileSessionLister { profileName ->
            listedProfile = profileName
            Result.success(emptyList())
        }

        viewModel.openProfileSession(
            profileName = owner.name,
            profile = owner,
            contextKey = AgentDisplay.profileContextKey("connection-a", owner.name),
            sessionId = "x-bot-session",
        )
        viewModel.reconcileProfileContext(
            contextKey = AgentDisplay.profileContextKey("connection-a", global.name),
            sessionId = STORED_SESSION_ID,
        )
        viewModel.refreshSessions()

        awaitCondition { listedProfile == owner.name }
        assertEquals(owner.name, viewModel.conversationBinding.value.profileName)
        assertEquals("x-bot-session", handler.currentSessionId.value)
        assertEquals(owner.name, gatewayClient.sessionProfileProvider())

        viewModel.switchSession("x-bot-sibling")
        assertEquals(owner.name, viewModel.conversationBinding.value.profileName)
        assertEquals("x-bot-sibling", persistedSession)
    }

    @Test
    fun lifecycleReconciliationDuringHydrationCannotMoveOrEraseExplicitSession() {
        val global = Profile(name = "mizu", model = "grok-4.5", description = "Mizu")
        val owner = Profile(name = "x-bot", model = "grok-4.3", description = "X Bot")
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        var loadedProfile: String? = null
        viewModel.setSelectedProfileProvider { global }
        viewModel.setSessionProfileNameProvider { global.name }
        viewModel.setProfileMessageLoaderWithMode { profileName, sessionId, _ ->
            loadedProfile = profileName
            loadStarted.complete(Unit)
            releaseLoad.await()
            Result.success(
                listOf(
                    MessageItem(
                        id = "owned-answer",
                        sessionId = sessionId,
                        role = "assistant",
                        content = JsonPrimitive("Owned transcript"),
                    ),
                ),
            )
        }

        viewModel.openProfileSession(
            profileName = owner.name,
            profile = owner,
            contextKey = AgentDisplay.profileContextKey("connection-a", owner.name),
            sessionId = "x-bot-session",
        )
        awaitCondition { loadStarted.isCompleted }

        viewModel.reconcileProfileContext(
            contextKey = AgentDisplay.profileContextKey("connection-a", global.name),
            sessionId = STORED_SESSION_ID,
        )
        releaseLoad.complete(Unit)

        awaitCondition { handler.messages.value.any { it.content == "Owned transcript" } }
        assertEquals(owner.name, loadedProfile)
        assertEquals(owner.name, viewModel.conversationBinding.value.profileName)
        assertEquals("x-bot-session", handler.currentSessionId.value)
    }

    @Test
    fun explicitOwnerScopesSessionMetadataWritesAfterLifecycleReconciliation() {
        val global = Profile(name = "mizu", model = "grok-4.5", description = "Mizu")
        val owner = Profile(name = "x-bot", model = "grok-4.3", description = "X Bot")
        val writtenProfiles = java.util.Collections.synchronizedList(mutableListOf<String?>())
        viewModel.setSelectedProfileProvider { global }
        viewModel.setSessionProfileNameProvider { global.name }
        viewModel.profileSessionRenamer = { profileName, _, _, _ ->
            writtenProfiles += profileName
            true
        }
        viewModel.profileSessionPinner = { profileName, _, _, _ ->
            writtenProfiles += profileName
            true
        }
        viewModel.profileSessionArchiver = { profileName, _, _, _ ->
            writtenProfiles += profileName
            true
        }
        viewModel.profileSessionDeleter = { profileName, _, _ ->
            writtenProfiles += profileName
            true
        }
        handler.addSession(
            com.hermesandroid.relay.data.ChatSession(
                sessionId = "x-bot-session",
                title = "Owned session",
                model = null,
            ),
        )

        viewModel.openProfileSession(
            profileName = owner.name,
            profile = owner,
            contextKey = AgentDisplay.profileContextKey("connection-a", owner.name),
            sessionId = "x-bot-session",
        )
        viewModel.reconcileProfileContext(
            contextKey = AgentDisplay.profileContextKey("connection-a", global.name),
            sessionId = STORED_SESSION_ID,
        )
        viewModel.renameSession("x-bot-session", "Renamed")
        viewModel.setSessionPinned("x-bot-session", true)
        viewModel.setSessionArchived("x-bot-session", true)
        viewModel.deleteSession("x-bot-session")

        awaitCondition { writtenProfiles.size == 4 }
        assertEquals(listOf(owner.name, owner.name, owner.name, owner.name), writtenProfiles)
    }

    @Test
    fun explicitDefaultDraftUsesLiteralDefaultProfile() {
        val global = Profile(name = "victor", model = "grok-4.5", description = "Victor")
        val rootDefault = Profile(name = "default", model = "gpt-5.5", description = "Hermes")
        var persistedSession = "unchanged"
        viewModel.setSelectedProfileProvider { global }
        viewModel.setSessionProfileNameProvider { global.name }
        viewModel.onSessionChanged = { persistedSession = it ?: "cleared" }

        viewModel.createProfileChat(
            profileName = "default",
            profile = rootDefault,
            contextKey = AgentDisplay.profileContextKey("connection-a", "default"),
        )

        assertEquals("default", viewModel.conversationBinding.value.profileName)
        assertEquals("default", gatewayClient.sessionProfileProvider())
        assertEquals(null, handler.currentSessionId.value)
        assertEquals("Hermes", handler.activeAgentName)
        assertEquals("cleared", persistedSession)
    }

    @Test
    fun freshDraftTransferKeepsNullableServerDefaultAndRejectsOldSessionRestore() {
        val named = Profile(name = "x-bot", model = "grok-4.3", description = "X Bot")
        var selected: Profile? = named
        var persistedDraft: Pair<String?, SessionTransport>? = null
        viewModel.setSelectedProfileProvider { selected }
        viewModel.setSessionProfileNameProvider { selected?.name }
        viewModel.setProfileSelectionHandler { profile ->
            selected = profile
            true
        }
        viewModel.onFreshDraftSelected = { profileName, transport ->
            persistedDraft = profileName to transport
        }

        assertTrue(
            viewModel.createProfileChat(
                profileName = null,
                profile = null,
                contextKey = AgentDisplay.profileContextKey("connection-a", null),
            ),
        )

        assertNull(selected)
        assertTrue(viewModel.conversationBinding.value.hasExplicitOwner)
        assertNull(viewModel.conversationBinding.value.profileName)
        assertNull(viewModel.conversationBinding.value.sessionId)
        assertEquals(null to SessionTransport.GATEWAY, persistedDraft)
        assertNull(gatewayClient.sessionProfileProvider())

        viewModel.reconcileProfileContext(
            AgentDisplay.profileContextKey("connection-a", null),
            sessionId = "old-default-session",
        )
        assertNull(viewModel.conversationBinding.value.sessionId)
        assertNull(handler.currentSessionId.value)
    }

    @Test
    fun freshDraftTransferToNamedProfileCreatesInsteadOfResumingItsOldSession() {
        val alpha = Profile(name = "alpha", model = "model-a", description = "Alpha")
        val beta = Profile(name = "beta", model = "model-b", description = "Beta")
        var selected: Profile? = alpha
        var persistedDraft: Pair<String?, SessionTransport>? = null
        viewModel.setSelectedProfileProvider { selected }
        viewModel.setSessionProfileNameProvider { selected?.name }
        viewModel.setProfileSelectionHandler { profile ->
            selected = profile
            true
        }
        viewModel.onFreshDraftSelected = { profileName, transport ->
            persistedDraft = profileName to transport
        }

        viewModel.openProfileSession(
            profileName = alpha.name,
            profile = alpha,
            contextKey = AgentDisplay.profileContextKey("connection-a", alpha.name),
            sessionId = "alpha-session",
        )
        viewModel.createNewChat()
        assertTrue(
            viewModel.selectProfileFromHeader(
                profileName = beta.name,
                profile = beta,
                contextKey = AgentDisplay.profileContextKey("connection-a", beta.name),
            ),
        )

        assertEquals(beta, selected)
        assertEquals(beta.name to SessionTransport.GATEWAY, persistedDraft)
        viewModel.reconcileProfileContext(
            AgentDisplay.profileContextKey("connection-a", beta.name),
            sessionId = "beta-old-session",
        )
        assertNull(handler.currentSessionId.value)

        gatewayHarness.createdSessionProfileName = beta.name
        val resumeCountBeforeFreshSend = gatewayHarness.rpcLog.count {
            it.first == "session.resume"
        }
        viewModel.sendMessage("Fresh beta turn")
        val create = gatewayHarness.awaitRpc("session.create")
        assertEquals(beta.name, (create["profile"] as JsonPrimitive).content)
        assertEquals(
            resumeCountBeforeFreshSend,
            gatewayHarness.rpcLog.count { it.first == "session.resume" },
        )
    }

    @Test
    fun headerProfileSwitchExitsProvisionalThreadBeforeFreshProfileSend() {
        val alpha = Profile(name = "alpha", model = "model-a", description = "Alpha")
        val beta = Profile(name = "beta", model = "model-b", description = "Beta")
        var selected: Profile? = alpha
        val proactiveChatIds = mutableListOf<String?>()
        viewModel.setSelectedProfileProvider { selected }
        viewModel.setSessionProfileNameProvider { selected?.name }
        viewModel.setProfileSelectionHandler { profile ->
            selected = profile
            true
        }
        viewModel.onProactiveReply = { _, chatId, _, _ -> proactiveChatIds += chatId }

        viewModel.openProactiveThread(
            chatId = "old-phone-chat",
            entries = listOf(
                ProactiveInboxEntry(
                    id = "inbox-1",
                    title = "Old phone thread",
                    text = "Continue here",
                    receivedAt = 1L,
                    chatId = "old-phone-chat",
                    connectionId = "connection-a",
                ),
            ),
        )
        assertNull(handler.currentSessionId.value)

        assertTrue(
            viewModel.selectProfileFromHeader(
                profileName = beta.name,
                profile = beta,
                contextKey = AgentDisplay.profileContextKey("connection-a", beta.name),
            ),
        )
        viewModel.sendMessage("Fresh beta turn")

        val create = gatewayHarness.awaitRpc("session.create")
        assertEquals(beta.name, (create["profile"] as JsonPrimitive).content)
        assertTrue(proactiveChatIds.isEmpty())
        assertEquals(beta.name, viewModel.conversationBinding.value.profileName)
    }

    @Test
    fun headerProfileSwitchExitsPromotedPhoneSessionWithoutReusingItsChatId() {
        val alpha = Profile(name = "alpha", model = "model-a", description = "Alpha")
        val beta = Profile(name = "beta", model = "model-b", description = "Beta")
        var selected: Profile? = alpha
        val proactiveChatIds = mutableListOf<String?>()
        viewModel.setSelectedProfileProvider { selected }
        viewModel.setSessionProfileNameProvider { selected?.name }
        viewModel.setProfileSelectionHandler { profile ->
            selected = profile
            true
        }
        viewModel.onProactiveReply = { _, chatId, _, _ -> proactiveChatIds += chatId }
        handler.addSession(
            com.hermesandroid.relay.data.ChatSession(
                sessionId = "promoted-phone-session",
                title = "Promoted thread",
                model = null,
                source = "phone",
            ),
        )
        handler.setSessionId("promoted-phone-session")

        assertTrue(
            viewModel.selectProfileFromHeader(
                profileName = beta.name,
                profile = beta,
                contextKey = AgentDisplay.profileContextKey("connection-a", beta.name),
            ),
        )
        assertNull(handler.currentSessionId.value)
        viewModel.sendMessage("Fresh beta after Thread")

        val create = gatewayHarness.awaitRpc("session.create")
        assertEquals(beta.name, (create["profile"] as JsonPrimitive).content)
        assertTrue(proactiveChatIds.isEmpty())
    }

    @Test
    fun newChatAndConnectionSwitchRetireProvisionalThreadRouting() {
        val entry = ProactiveInboxEntry(
            id = "inbox-1",
            title = "Old phone thread",
            text = "Continue here",
            receivedAt = 1L,
            chatId = "old-phone-chat",
            connectionId = "connection-a",
        )
        val inbound = ProactiveMessage(
            messageId = "late-1",
            chatId = "old-phone-chat",
            text = "Late old-thread message",
            title = "Old phone thread",
            surfacing = "thread",
            sentAt = 2L,
        )

        viewModel.openProactiveThread("old-phone-chat", listOf(entry))
        viewModel.createNewChat()
        assertFalse(viewModel.injectThreadMessage(inbound))

        val switches = MutableSharedFlow<String>(extraBufferCapacity = 1)
        viewModel.observeConnectionSwitches(switches)
        viewModel.openProactiveThread("old-phone-chat", listOf(entry))
        switches.tryEmit("connection-b")
        awaitCondition { handler.messages.value.isEmpty() }
        assertFalse(viewModel.injectThreadMessage(inbound))
    }

    @Test
    fun staleThreadPromotionCannotReplaceTransferredProfileDraft() {
        val beta = Profile(name = "beta", model = "model-b", description = "Beta")
        var selected: Profile? = Profile(name = "alpha", model = "model-a")
        viewModel.setSelectedProfileProvider { selected }
        viewModel.setSessionProfileNameProvider { selected?.name }
        viewModel.setProfileSelectionHandler { profile ->
            selected = profile
            true
        }
        viewModel.onProactiveReply = { _, _, _, _ -> }
        viewModel.openProactiveThread(
            "old-phone-chat",
            listOf(
                ProactiveInboxEntry(
                    id = "inbox-1",
                    title = "Old phone thread",
                    text = "Continue here",
                    receivedAt = 1L,
                    chatId = "old-phone-chat",
                    connectionId = "connection-a",
                ),
            ),
        )
        viewModel.sendMessage("Promote the old Thread")

        assertTrue(
            viewModel.selectProfileFromHeader(
                profileName = beta.name,
                profile = beta,
                contextKey = AgentDisplay.profileContextKey("connection-a", beta.name),
            ),
        )
        handler.addSession(
            com.hermesandroid.relay.data.ChatSession(
                sessionId = "late-promoted-thread",
                title = "Late promoted thread",
                model = null,
                source = "phone",
            ),
        )
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        Thread.sleep(100)

        assertNull(handler.currentSessionId.value)
        assertEquals(beta.name, viewModel.conversationBinding.value.profileName)
    }

    @Test
    fun gatewaySessionCreateProviderPreservesExplicitFastFalse() {
        serverWs.send(
            gatewayHarness.eventFrame(
                "session.info",
                buildJsonObject { put("fast", false) },
                "live-resumed",
            ),
        )
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.fastEnabled.value != false) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() >= deadline) error("fast=false did not reconcile")
            Thread.sleep(20)
        }

        assertEquals(false, gatewayClient.sessionModelProvider()?.fast)
    }

    @Test
    fun dashboardOnlyConnectionCanSendWithoutApiClient() {
        viewModel.updateGatewayClient(null)
        gatewayClient.clearSession()
        handler = ChatHandler()
        viewModel = ChatViewModel().also {
            it.initialize(null, handler)
            it.streamingEndpoint = "gateway"
            it.setProfileMessageLoader { Result.success(persistedHistory) }
            it.updateGatewayClient(gatewayClient)
        }

        viewModel.sendMessage("Dashboard-only gateway turn")

        gatewayHarness.awaitRpc("prompt.submit")
        assertTrue(handler.messages.value.any { it.content == "Dashboard-only gateway turn" })
        assertTrue(gatewayClient.hasActiveTurn())
    }

    @Test
    fun gatewayRichCardActionStaysOnGatewayInsteadOfDrainingThroughSessionsApi() {
        viewModel.sseFallbackEndpoint = "sessions"
        val cardMessageId = "card-message"
        handler.addPlaceholderMessage(
            ChatMessage(
                id = cardMessageId,
                role = MessageRole.ASSISTANT,
                content = "",
                timestamp = System.currentTimeMillis(),
                isStreaming = true,
            ),
        )
        handler.onTextDelta(
            cardMessageId,
            """
            CARD:{"type":"approval_request","id":"test-card","actions":[{"label":"Approve","value":"approve","mode":"send_text"}]}
            """.trimIndent(),
        )
        handler.onTurnComplete(cardMessageId)
        val card = handler.messages.value.single { it.id == cardMessageId }.cards.single()
        val apiRequestsBeforeAction = apiCompletionsRequestCount.get()

        viewModel.dispatchCardAction(
            messageId = cardMessageId,
            cardKey = card.id!!,
            action = card.actions.single(),
        )

        val submit = gatewayHarness.awaitRpc("prompt.submit")
        assertEquals("approve", (submit["text"] as JsonPrimitive).content)
        assertEquals(apiRequestsBeforeAction, apiCompletionsRequestCount.get())
    }

    @Test
    fun dashboardOnlyPersonalityCatalogLoadsAndSurvivesRefreshFailure() {
        viewModel.updateApiClient(null)
        viewModel.streamingEndpoint = "completions"
        viewModel.selectPersonality("coach")
        val config = buildJsonObject {
            put("config", buildJsonObject {
                put("agent", buildJsonObject {
                    put("personalities", buildJsonObject {
                        put("concise", "Be concise")
                        put("coach", "Coach the user")
                    })
                })
                put("display", buildJsonObject { put("personality", "concise") })
            })
        }
        viewModel.setDashboardConfigLoader { Result.success(config) }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("concise", "coach"), viewModel.personalityNames.value)
        assertEquals("concise", viewModel.defaultPersonality.value)
        assertEquals("coach", viewModel.selectedPersonality.value)

        viewModel.setDashboardConfigLoader { Result.failure(IllegalStateException("offline")) }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("concise", "coach"), viewModel.personalityNames.value)
        assertEquals("concise", viewModel.defaultPersonality.value)
        assertEquals("coach", viewModel.selectedPersonality.value)
    }

    @Test
    fun dashboardOnlyProfileContextLoadsPersistedHistoryWithoutApiClient() {
        viewModel.updateApiClient(null)
        persistedHistory = persistedAnswerHistory(answer = "Dashboard history")
        handler.setSessionId(null)

        viewModel.switchProfileContext("connection-dashboard/profile-default", STORED_SESSION_ID)

        awaitCondition { handler.messages.value.any { it.content == "Dashboard history" } }
    }

    @Test
    fun profileContextSwitchClearsSessionScopedModelAndPersonality() {
        viewModel.selectModel("provider/old-model", "provider")
        viewModel.selectPersonality("coach")
        assertEquals("provider/old-model", viewModel.selectedModelOverride.value)
        assertEquals("coach", viewModel.selectedPersonality.value)

        viewModel.switchProfileContext("connection-dashboard/profile-new", null)

        assertEquals(null, viewModel.selectedModelOverride.value)
        assertEquals(null, viewModel.selectedProviderOverride.value)
        assertEquals("default", viewModel.selectedPersonality.value)
        assertEquals(null, viewModel.selectedReasoningEffort.value)
        assertEquals(null, viewModel.yoloEnabled.value)
        assertEquals(null, viewModel.fastEnabled.value)
        assertEquals(null, viewModel.approvalMode.value)
    }

    @Test
    fun freshChatRiskyModelCreatesDefaultSessionBeforeConfirmationPreflight() {
        handler.setSessionId(null)
        gatewayClient.clearSession()
        gatewayHarness.modelConfirmationMessage = "This tier may train on your data."
        gatewayHarness.modelsRequiringConfirmation += "risky-model --provider risky-provider"

        viewModel.selectModel("risky-model", "risky-provider")

        val create = gatewayHarness.awaitRpc("session.create")
        awaitCondition { gatewayHarness.rpcLog.count { it.first == "config.set" } >= 1 }
        val configSet = gatewayHarness.awaitRpcCount("config.set", 1).single()
        awaitCondition { viewModel.modelSelectionConfirmation.value != null }

        assertFalse(create.containsKey("model"))
        assertFalse(create.containsKey("provider"))
        assertEquals("model", (configSet["key"] as JsonPrimitive).content)
        assertEquals("live-1", (configSet["session_id"] as JsonPrimitive).content)
        assertEquals("20260612_120000_abc123", handler.currentSessionId.value)
        assertNull(viewModel.selectedModelOverride.value)
        assertEquals(
            "This tier may train on your data.",
            viewModel.modelSelectionConfirmation.value?.message,
        )
    }

    @Test
    fun freshNamedProfileModelPickRejectsDifferentConfirmedOwnerBeforeConfigSet() {
        handler.setSessionId(null)
        gatewayClient.clearSession()
        viewModel.setSelectedProfileProvider {
            Profile(name = "mizu", model = "safe-default", description = "Mizu")
        }
        viewModel.setSessionProfileNameProvider { "mizu" }
        gatewayHarness.createdSessionProfileName = "victor"

        viewModel.selectModel("risky-model", "risky-provider")

        gatewayHarness.awaitRpc("session.create")
        awaitCondition { viewModel.selectedModelOverride.value == null }

        assertNull(handler.currentSessionId.value)
        assertFalse(gatewayHarness.rpcLog.any { it.first == "config.set" })
    }

    @Test
    fun freshNamedProfileModelPickRejectsAbsentConfirmedOwnerBeforeConfigSet() {
        handler.setSessionId(null)
        gatewayClient.clearSession()
        viewModel.setSelectedProfileProvider {
            Profile(name = "mizu", model = "safe-default", description = "Mizu")
        }
        viewModel.setSessionProfileNameProvider { "mizu" }
        gatewayHarness.omitSessionProfileMetadata = true

        viewModel.selectModel("risky-model", "risky-provider")

        gatewayHarness.awaitRpc("session.create")
        awaitCondition { viewModel.selectedModelOverride.value == null }

        assertNull(handler.currentSessionId.value)
        assertFalse(gatewayHarness.rpcLog.any { it.first == "config.set" })
    }

    @Test
    fun serverDefaultModelUsesSameConfirmationAwareTransition() {
        viewModel.selectModel("safe-model", "safe-provider")
        gatewayHarness.awaitRpcCount("config.set", 1)
        awaitCondition { viewModel.selectedModelOverride.value == "safe-model" }
        viewModel.setSelectedProfileProvider {
            Profile(name = "default", model = "risky-default", description = "Hermes")
        }
        gatewayHarness.modelConfirmationMessage = "This default has high usage cost."
        gatewayHarness.modelsRequiringConfirmation += "risky-default"

        viewModel.selectModel(null)

        awaitCondition { gatewayHarness.rpcLog.count { it.first == "config.set" } >= 2 }
        val configSet = gatewayHarness.awaitRpcCount("config.set", 2).last()
        awaitCondition { viewModel.modelSelectionConfirmation.value != null }

        assertEquals("risky-default", (configSet["value"] as JsonPrimitive).content)
        assertEquals("live-resumed", (configSet["session_id"] as JsonPrimitive).content)
        assertEquals("safe-model", viewModel.selectedModelOverride.value)
        assertNull(viewModel.modelSelectionConfirmation.value?.modelOverride)
        assertEquals(
            "This default has high usage cost.",
            viewModel.modelSelectionConfirmation.value?.message,
        )
    }

    @Test
    fun connectionCatalogResetPreventsDashboardOnlyLeakage() {
        viewModel.updateApiClient(null)
        val config = buildJsonObject {
            put("agent", buildJsonObject {
                put("personalities", buildJsonObject { put("private-a", "A") })
            })
        }
        viewModel.setDashboardConfigLoader { Result.success(config) }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("private-a"), viewModel.personalityNames.value)

        viewModel.resetConnectionCatalogs()

        assertTrue(viewModel.personalityNames.value.isEmpty())
        assertTrue(viewModel.availableSkills.value.isEmpty())
        assertTrue(viewModel.availableModels.value.isEmpty())
        assertTrue(viewModel.serverCommands.value.isEmpty())
    }

    @Test
    fun unsolicitedGatewayCompletionAppearsAsOneAssistantTurnAndSettles() {
        // Upstream's process-completion poller currently emits this adjacent
        // duplicate pair; it must still create exactly one placeholder.
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )

        awaitCondition {
            handler.messages.value.singleOrNull()?.content == BACKGROUND_ANSWER
        }
        assertTrue(handler.isStreaming.value)
        assertEquals(1, handler.messages.value.size)
        assertEquals(MessageRole.ASSISTANT, handler.messages.value.single().role)

        persistedHistory = persistedAnswerHistory()
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )

        awaitCondition { !handler.isStreaming.value }
        shadowOf(Looper.getMainLooper()).idle()
        awaitCondition {
            handler.messages.value.singleOrNull()?.content == BACKGROUND_ANSWER
        }
        assertFalse(handler.messages.value.single().isStreaming)
        assertFalse(gatewayHarness.rpcLog.any { it.first == "prompt.submit" })
    }

    @Test
    fun queuedMainDispatchAdmitsBackgroundStartAfterLocalCompletion() {
        viewModel.sendMessage("Local gateway turn")
        gatewayHarness.awaitRpc("prompt.submit")
        awaitCondition { gatewayClient.hasActiveTurn() }

        // Queue the local completion and the server-initiated start back to
        // back. The inbound admission must run behind the local completion on
        // main, rather than reading stale activeStream state on the socket.
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Local answer") },
                "live-resumed",
            ),
        )
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )

        awaitCondition {
            handler.messages.value.any {
                it.id.startsWith("gateway-inbound-") && it.content == BACKGROUND_ANSWER
            }
        }
        awaitCondition { !handler.isStreaming.value }
    }

    @Test
    fun switchingBetweenTwoRunningChatsDetachesAndReattachesWithoutInterruptingEither() {
        val secondSession = "stored-session-b"
        val contextKey = AgentDisplay.profileContextKey("connection-a", null)
        gatewayHarness.resumeLiveSessionIds[secondSession] = "live-b"
        viewModel.switchProfileContext(contextKey, STORED_SESSION_ID)

        viewModel.sendMessage("Run task A")
        gatewayHarness.awaitRpc("prompt.submit")
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "Partial A") },
                "live-resumed",
            ),
        )
        awaitCondition { handler.messages.value.any { it.content == "Partial A" } }

        viewModel.switchSession(secondSession)
        gatewayHarness.awaitRpcCount("session.resume", 2)
        awaitCondition { handler.currentSessionId.value == secondSession && !handler.isStreaming.value }
        assertTrue(gatewayHarness.rpcLog.none { it.first == "session.interrupt" })

        viewModel.sendMessage("Run task B")
        gatewayHarness.awaitRpcCount("prompt.submit", 2)
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "Partial B") },
                "live-b",
            ),
        )
        awaitCondition { handler.messages.value.any { it.content == "Partial B" } }

        gatewayHarness.recoveryRunning = true
        gatewayHarness.recoveryAssistant = "Partial A"
        viewModel.switchSession(STORED_SESSION_ID)
        val firstActivation = gatewayHarness.awaitRpcCount("session.activate", 1).last()
        assertEquals(JsonPrimitive("live-resumed"), firstActivation["session_id"])
        awaitCondition {
            handler.currentSessionId.value == STORED_SESSION_ID &&
                handler.isStreaming.value &&
                handler.messages.value.any { it.content == "Partial A" }
        }
        assertTrue(gatewayHarness.rpcLog.none { it.first == "session.interrupt" })

        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Task A complete") },
                "live-resumed",
            ),
        )
        awaitCondition { !handler.isStreaming.value }

        gatewayHarness.recoveryAssistant = "Partial B"
        viewModel.switchSession(secondSession)
        val secondActivation = gatewayHarness.awaitRpcCount("session.activate", 2).last()
        assertEquals(JsonPrimitive("live-b"), secondActivation["session_id"])
        awaitCondition {
            handler.currentSessionId.value == secondSession &&
                handler.isStreaming.value &&
                handler.messages.value.any { it.content == "Partial B" }
        }

        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Task B complete") },
                "live-b",
            ),
        )
        awaitCondition { !handler.isStreaming.value && !gatewayClient.hasActiveTurn() }
        assertTrue(gatewayHarness.rpcLog.none { it.first == "session.interrupt" })
    }

    @Test
    fun stopOnUnsolicitedTurnInterruptsTheGatewaySession() {
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "Still composing") },
                "live-resumed",
            ),
        )
        awaitCondition { handler.isStreaming.value }

        viewModel.cancelStream()

        gatewayHarness.awaitRpc("session.interrupt")
        assertFalse(handler.isStreaming.value)

        // Upstream can emit the interrupted turn's terminal event after the
        // interrupt RPC. It is a drain marker, not a new background answer.
        persistedHistory = persistedAnswerHistory("Canceled answer", "canceled-server-answer")
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Canceled answer") },
                "live-resumed",
            ),
        )
        Thread.sleep(150)
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        assertFalse(handler.messages.value.any { it.content == "Canceled answer" })
        assertTrue(handler.messages.value.any { "Stopped" in it.badges })
    }

    @Test
    fun stopClearsStaleBusyStateAfterTerminalBubbleAlreadySettled() {
        // Exercise Stop's route-independent fallback without the Gateway
        // orphan-state observer settling this synthetic state first.
        viewModel.streamingEndpoint = "sessions"
        handler.onTextDelta("stale-answer", "Finished answer")
        handler.onTurnComplete("stale-answer")
        assertFalse(handler.messages.value.single().isStreaming)
        assertTrue(handler.isStreaming.value)

        viewModel.cancelStream()

        assertFalse(handler.isStreaming.value)
        assertNull(handler.turnStatus.value)
    }

    @Test
    fun completedGatewayBubbleAutomaticallySettlesComposerWithoutInput() {
        handler.onTextDelta("completed-answer", "Finished answer")
        handler.onTurnComplete("completed-answer")

        awaitCondition { !handler.isStreaming.value }

        assertFalse(handler.messages.value.single().isStreaming)
        assertFalse(gatewayClient.hasActiveTurnForSession(STORED_SESSION_ID))
        assertTrue(gatewayHarness.rpcLog.none { it.first == "prompt.submit" })
        assertTrue(gatewayHarness.rpcLog.none { it.first == "session.interrupt" })
    }

    @Test
    fun completedBubbleKeepsComposerBusyWhileGatewaySessionStillOwnsTheRun() {
        viewModel.sendMessage("Continue through another assistant turn")
        gatewayHarness.awaitRpc("prompt.submit")
        awaitCondition { gatewayClient.hasActiveTurnForSession(STORED_SESSION_ID) }
        val assistantId = handler.messages.value.single { it.role == MessageRole.ASSISTANT }.id

        handler.onTextDelta(assistantId, "First assistant message")
        handler.onTurnComplete(assistantId)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(handler.isStreaming.value)
        assertTrue(gatewayClient.hasActiveTurnForSession(STORED_SESSION_ID))

        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Final assistant message") },
                "live-resumed",
            ),
        )
        awaitCondition { !handler.isStreaming.value }
    }

    @Test
    fun newChatClearsStaleBusyStateWhenNoLiveGatewayTurnRemains() {
        handler.onTextDelta("stale-answer", "Finished answer")
        assertTrue(handler.isStreaming.value)
        assertFalse(gatewayClient.hasActiveTurn())

        viewModel.createNewChat()

        assertFalse(handler.isStreaming.value)
        assertTrue(handler.messages.value.isEmpty())
        assertNull(handler.currentSessionId.value)
    }

    @Test
    fun reopenedChatRestoresRichStateAndReattachesLiveGatewayTurn() {
        val now = System.currentTimeMillis()
        val checkpointStore = MemoryCheckpointStore(
            ChatTurnCheckpoint(
                contextKey = PROFILE_CONTEXT,
                sessionId = STORED_SESSION_ID,
                liveSessionId = "live-resumed",
                transport = "gateway",
                user = ChatTurnUserCheckpoint("pending-user", "Research this", now - 2_000L),
                assistant = ChatTurnAssistantCheckpoint(
                    id = "pending-assistant",
                    content = "Partial",
                    timestamp = now - 1_900L,
                    thinkingContent = "Inspecting sources",
                    isThinkingStreaming = true,
                    toolCalls = listOf(
                        ChatTurnToolCheckpoint(
                            id = "tool-1",
                            name = "terminal",
                            isComplete = false,
                            startedAt = now - 1_500L,
                        ),
                    ),
                ),
                turnStatus = "Running terminal",
                priorUserMessageCount = 0,
                baselineAssistantCount = 0,
                pendingAsk = ChatTurnAskCheckpoint(
                    kind = "APPROVAL",
                    text = "Allow the command?",
                    choices = listOf("once", "session", "always", "deny"),
                    smartDenied = true,
                    timeoutSeconds = 0,
                    messageId = "ask-approval-1",
                    cardKey = "approval-1",
                    receivedAt = now - 1_000L,
                ),
                startedAt = now - 1_900L,
                updatedAt = now,
            ),
        )
        gatewayHarness.recoveryRunning = true
        gatewayHarness.recoveryAssistant = "Partial answer from upstream"
        viewModel.setChatTurnCheckpointStore(checkpointStore)
        // Cold process start: ConnectionViewModel's fresh handler has not yet
        // adopted the persisted lastSessionId when the profile context binds.
        handler.setSessionId(null)
        viewModel.switchProfileContext(PROFILE_CONTEXT, STORED_SESSION_ID)

        viewModel.prewarmGateway()

        gatewayHarness.awaitRpc("session.activate")
        awaitCondition {
            handler.messages.value.any {
                it.id == "pending-assistant" && it.content == "Partial answer from upstream"
            } &&
                handler.isStreaming.value
        }
        val restored = handler.messages.value.single { it.id == "pending-assistant" }
        assertEquals("Partial answer from upstream", restored.content)
        assertEquals("Inspecting sources", restored.thinkingContent)
        assertEquals("terminal", restored.toolCalls.single().name)
        assertFalse(restored.toolCalls.single().isComplete)
        assertEquals("Running terminal", handler.turnStatus.value)
        assertEquals("approval-1", viewModel.pendingAsk.value?.cardKey)
        assertEquals(PROFILE_CONTEXT, viewModel.pendingAsk.value?.contextKey)
        assertEquals(STORED_SESSION_ID, viewModel.pendingAsk.value?.sessionId)
        val restoredApproval = handler.messages.value
            .single { it.id == "ask-approval-1" }
            .cards
            .single()
        assertEquals(listOf("once", "deny"), restoredApproval.actions.map { it.value })
        assertTrue(restoredApproval.title?.contains("Smart DENY") == true)
        assertTrue(restoredApproval.body?.contains("override it once") == true)
        assertTrue(
            handler.messages.value.indexOfFirst { it.id == "pending-assistant" } <
                handler.messages.value.indexOfFirst { it.id == "ask-approval-1" },
        )

        serverWs.send(
            gatewayHarness.eventFrame(
                "tool.complete",
                buildJsonObject {
                    put("tool_id", "tool-1")
                    put("name", "terminal")
                    put("summary", "done")
                },
                "live-resumed",
            ),
        )
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", " and finished") },
                "live-resumed",
            ),
        )
        persistedHistory = listOf(
            MessageItem(id = "server-user", role = "user", content = JsonPrimitive("Research this")),
            MessageItem(
                id = "server-assistant",
                role = "assistant",
                content = JsonPrimitive("Partial answer from upstream and finished"),
            ),
        )

        assertTrue(handler.isStreaming.value)
        assertEquals("approval-1", checkpointStore.checkpoint?.pendingAsk?.cardKey)
        assertTrue(gatewayHarness.rpcLog.none { it.first == "approval.respond" })
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Partial answer from upstream and finished") },
                "live-resumed",
            ),
        )

        shadowOf(Looper.getMainLooper()).idle()
        awaitCondition {
            handler.messages.value.any {
                it.role == MessageRole.ASSISTANT &&
                    it.content == "Partial answer from upstream and finished"
            }
        }
        awaitCondition { !handler.isStreaming.value }
        // Neither replayed activity nor generic completion proves consent.
        // The client-only card remains actionable until an explicit response
        // or authoritative expiry event owns the transition.
        assertEquals("approval-1", viewModel.pendingAsk.value?.cardKey)
        assertTrue(
            handler.messages.value
                .single { it.id == "ask-approval-1" }
                .cardDispatches
                .isEmpty(),
        )

        val pending = requireNotNull(viewModel.pendingAsk.value)
        viewModel.answerAsk(pending.messageId, pending.cardKey, "once")
        gatewayHarness.awaitRpc("approval.respond")
        awaitCondition { !handler.isStreaming.value }
        awaitCondition { checkpointStore.checkpoint == null }
        assertEquals(
            "once",
            handler.messages.value.single { it.id == "ask-approval-1" }
                .cardDispatches.single().actionValue,
        )
    }

    @Test
    fun explicitApprovalActionAloneEmitsResponseAndCollapsesCard() {
        viewModel.sendMessage("Run the guarded command")
        gatewayHarness.awaitRpc("prompt.submit")
        serverWs.send(
            gatewayHarness.eventFrame(
                "approval.request",
                buildJsonObject { put("command", "guarded command") },
                "live-resumed",
            ),
        )
        awaitCondition { viewModel.pendingAsk.value != null }
        val pending = requireNotNull(viewModel.pendingAsk.value)

        viewModel.answerAsk(pending.messageId, pending.cardKey, "once")

        val response = gatewayHarness.awaitRpc("approval.respond")
        assertEquals(JsonPrimitive("live-resumed"), response["session_id"])
        assertEquals(JsonPrimitive("once"), response["choice"])
        awaitCondition { viewModel.pendingAsk.value == null }
        val cardMessage = handler.messages.value.single { it.id == pending.messageId }
        assertEquals("once", cardMessage.cardDispatches.single().actionValue)
    }

    @Test
    fun protectedInstructionApprovalCardOffersOneOperationScopeOnly() {
        viewModel.sendMessage("Edit the disposable instruction fixture")
        gatewayHarness.awaitRpc("prompt.submit")
        serverWs.send(
            gatewayHarness.eventFrame(
                "approval.request",
                buildJsonObject {
                    put("command", "<write to AGENTS.md>")
                    put("allow_session", false)
                    put("allow_permanent", false)
                    put("choices", buildJsonArray {
                        add(JsonPrimitive("once"))
                        add(JsonPrimitive("session"))
                        add(JsonPrimitive("always"))
                        add(JsonPrimitive("deny"))
                    })
                },
                "live-resumed",
            ),
        )

        awaitCondition { viewModel.pendingAsk.value != null }
        val pending = requireNotNull(viewModel.pendingAsk.value)
        val card = handler.messages.value.single { it.id == pending.messageId }.cards.single()
        assertEquals(listOf("once", "deny"), card.actions.map { it.value })
    }

    @Test
    fun multiSelectClarifyPreservesCardSemanticsAndExactWireAnswer() {
        viewModel.sendMessage("Ask which environments")
        gatewayHarness.awaitRpc("prompt.submit")
        serverWs.send(
            gatewayHarness.eventFrame(
                "clarify.request",
                buildJsonObject {
                    put("request_id", "clarify-1")
                    put("question", "Which environments?")
                    put("multi_select", true)
                    put("choices", buildJsonArray {
                        add(JsonPrimitive("dev"))
                        add(JsonPrimitive("stage"))
                        add(JsonPrimitive("prod"))
                    })
                },
                "live-resumed",
            ),
        )
        awaitCondition { viewModel.pendingAsk.value != null }
        val pending = requireNotNull(viewModel.pendingAsk.value)
        val card = handler.messages.value.single { it.id == pending.messageId }.cards.single()
        assertTrue(card.input?.multiSelect == true)
        assertEquals(listOf("dev", "stage", "prod"), card.input?.choices)
        assertEquals(null, card.input?.expiresAtMillis)

        viewModel.answerAsk(pending.messageId, pending.cardKey, "[\"prod\",\"dev\"]")

        val response = gatewayHarness.awaitRpc("clarify.respond")
        assertEquals(JsonPrimitive("clarify-1"), response["request_id"])
        assertEquals(JsonPrimitive("[\"prod\",\"dev\"]"), response["answer"])
        awaitCondition { viewModel.pendingAsk.value == null }
    }

    @Test
    fun authoritativeClarifyExpiryCollapsesCardAndRejectsLateAction() {
        viewModel.sendMessage("Ask a question")
        gatewayHarness.awaitRpc("prompt.submit")
        serverWs.send(
            gatewayHarness.eventFrame(
                "clarify.request",
                buildJsonObject {
                    put("request_id", "clarify-expired")
                    put("question", "Still there?")
                    put("choices", buildJsonArray { add(JsonPrimitive("yes")) })
                },
                "live-resumed",
            ),
        )
        awaitCondition { viewModel.pendingAsk.value != null }
        val pending = requireNotNull(viewModel.pendingAsk.value)

        serverWs.send(
            gatewayHarness.eventFrame(
                "clarify.expire",
                buildJsonObject { put("request_id", "clarify-expired") },
                "live-resumed",
            ),
        )

        awaitCondition { viewModel.pendingAsk.value == null }
        val cardMessage = handler.messages.value.single { it.id == pending.messageId }
        assertEquals(
            HermesCardDispatch.EXPIRED_STAMP,
            cardMessage.cardDispatches.single().actionValue,
        )
        viewModel.answerAsk(pending.messageId, pending.cardKey, "yes")
        Thread.sleep(100)
        assertTrue(gatewayHarness.rpcLog.none { it.first == "clarify.respond" })
    }

    @Test
    fun explicitDenialActionEmitsResponseAndCollapsesCard() {
        viewModel.sendMessage("Run the guarded command")
        gatewayHarness.awaitRpc("prompt.submit")
        serverWs.send(
            gatewayHarness.eventFrame(
                "approval.request",
                buildJsonObject { put("command", "guarded command") },
                "live-resumed",
            ),
        )
        awaitCondition { viewModel.pendingAsk.value != null }
        val pending = requireNotNull(viewModel.pendingAsk.value)

        viewModel.answerAsk(pending.messageId, pending.cardKey, "deny")

        val response = gatewayHarness.awaitRpc("approval.respond")
        assertEquals(JsonPrimitive("live-resumed"), response["session_id"])
        assertEquals(JsonPrimitive("deny"), response["choice"])
        awaitCondition { viewModel.pendingAsk.value == null }
        val cardMessage = handler.messages.value.single { it.id == pending.messageId }
        assertEquals("deny", cardMessage.cardDispatches.single().actionValue)
    }

    @Test
    fun queuedOnlyRecoverySettlesPriorCheckpointBeforeStreamingQueuedTurn() {
        val now = System.currentTimeMillis()
        val checkpointStore = MemoryCheckpointStore(
            ChatTurnCheckpoint(
                contextKey = PROFILE_CONTEXT,
                sessionId = STORED_SESSION_ID,
                liveSessionId = "live-resumed",
                transport = "gateway",
                user = ChatTurnUserCheckpoint("prior-user", "First prompt", now - 3_000L),
                assistant = ChatTurnAssistantCheckpoint(
                    id = "prior-assistant",
                    content = "Old partial",
                    timestamp = now - 2_900L,
                ),
                priorUserMessageCount = 0,
                baselineAssistantCount = 0,
                startedAt = now - 2_900L,
                updatedAt = now,
            ),
        )
        persistedHistory = listOf(
            MessageItem(
                id = "server-prior-user",
                sessionId = STORED_SESSION_ID,
                role = "user",
                content = JsonPrimitive("First prompt"),
            ),
            MessageItem(
                id = "server-prior-assistant",
                sessionId = STORED_SESSION_ID,
                role = "assistant",
                content = JsonPrimitive("Completed first answer"),
            ),
        )
        gatewayHarness.recoveryRunning = false
        gatewayHarness.recoveryQueuedUser = "Second queued prompt"
        viewModel.setChatTurnCheckpointStore(checkpointStore)
        handler.setSessionId(null)
        viewModel.switchProfileContext(PROFILE_CONTEXT, STORED_SESSION_ID)

        viewModel.prewarmGateway()

        gatewayHarness.awaitRpc("session.activate")
        awaitCondition {
            handler.messages.value.any {
                it.role == MessageRole.USER && it.content == "Second queued prompt"
            } && handler.messages.value.any {
                it.id.startsWith("gateway-inbound-") && it.isStreaming
            }
        }
        val beforeEvents = handler.messages.value
        assertTrue(beforeEvents.any {
            it.role == MessageRole.ASSISTANT &&
                it.content == "Completed first answer" &&
                !it.isStreaming
        })
        assertFalse(beforeEvents.any { it.id == "prior-assistant" && it.isStreaming })

        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "Second answer") },
                "live-resumed",
            ),
        )
        persistedHistory = persistedHistory + listOf(
            MessageItem(
                id = "server-queued-user",
                sessionId = STORED_SESSION_ID,
                role = "user",
                content = JsonPrimitive("Second queued prompt"),
            ),
            MessageItem(
                id = "server-queued-assistant",
                sessionId = STORED_SESSION_ID,
                role = "assistant",
                content = JsonPrimitive("Second answer"),
            ),
        )
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Second answer") },
                "live-resumed",
            ),
        )

        awaitCondition { !handler.isStreaming.value }
        awaitCondition {
            handler.messages.value.count {
                it.role == MessageRole.ASSISTANT && it.content == "Second answer"
            } == 1
        }
        assertTrue(handler.messages.value.any { it.content == "Completed first answer" })
        assertFalse(handler.messages.value.any {
            it.id == "prior-assistant" && it.content.contains("Second answer")
        })
    }

    @Test
    fun runningAndQueuedRecoveryCreatesANewPromptAndCheckpointAfterCurrentCompletion() {
        val now = System.currentTimeMillis()
        val checkpointStore = MemoryCheckpointStore(
            ChatTurnCheckpoint(
                contextKey = PROFILE_CONTEXT,
                sessionId = STORED_SESSION_ID,
                liveSessionId = "live-resumed",
                transport = "gateway",
                user = ChatTurnUserCheckpoint("prior-user", "First prompt", now - 3_000L),
                assistant = ChatTurnAssistantCheckpoint(
                    id = "prior-assistant",
                    content = "Current partial",
                    timestamp = now - 2_900L,
                ),
                priorUserMessageCount = 0,
                baselineAssistantCount = 0,
                startedAt = now - 2_900L,
                updatedAt = now,
            ),
        )
        gatewayHarness.recoveryRunning = true
        gatewayHarness.recoveryAssistant = "Current partial"
        gatewayHarness.recoveryQueuedUser = "Second queued prompt"
        viewModel.setChatTurnCheckpointStore(checkpointStore)
        handler.setSessionId(null)
        viewModel.switchProfileContext(PROFILE_CONTEXT, STORED_SESSION_ID)

        viewModel.prewarmGateway()

        gatewayHarness.awaitRpc("session.activate")
        awaitCondition {
            handler.messages.value.any { it.id == "prior-assistant" && it.isStreaming }
        }
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Current partial completed") },
                "live-resumed",
            ),
        )

        awaitCondition {
            handler.messages.value.any {
                it.role == MessageRole.USER && it.content == "Second queued prompt"
            } && handler.messages.value.any {
                it.id.startsWith("gateway-inbound-") && it.isStreaming
            }
        }
        awaitCondition { checkpointStore.checkpoint?.user?.content == "Second queued prompt" }
        assertTrue(
            "prior turn was not settled before queued handoff: ${handler.messages.value}",
            handler.messages.value.any {
                it.id == "prior-assistant" &&
                    it.content.endsWith("Current partial completed") &&
                    !it.isStreaming
            },
        )

        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "Second answer") },
                "live-resumed",
            ),
        )
        persistedHistory = listOf(
            MessageItem(
                id = "server-prior-user",
                sessionId = STORED_SESSION_ID,
                role = "user",
                content = JsonPrimitive("First prompt"),
            ),
            MessageItem(
                id = "server-prior-assistant",
                sessionId = STORED_SESSION_ID,
                role = "assistant",
                content = JsonPrimitive("Current partial completed"),
            ),
            MessageItem(
                id = "server-queued-user",
                sessionId = STORED_SESSION_ID,
                role = "user",
                content = JsonPrimitive("Second queued prompt"),
            ),
            MessageItem(
                id = "server-queued-assistant",
                sessionId = STORED_SESSION_ID,
                role = "assistant",
                content = JsonPrimitive("Second answer"),
            ),
        )
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Second answer") },
                "live-resumed",
            ),
        )

        awaitCondition { !handler.isStreaming.value }
        awaitCondition { checkpointStore.checkpoint == null }
        assertEquals(
            1,
            handler.messages.value.count {
                it.role == MessageRole.USER && it.content == "Second queued prompt"
            },
        )
        assertEquals(
            1,
            handler.messages.value.count {
                it.role == MessageRole.ASSISTANT && it.content == "Second answer"
            },
        )
    }

    @Test
    fun lateCanceledCompletionDrainsBeforeImmediateNextTurn() {
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "Old partial") },
                "live-resumed",
            ),
        )
        awaitCondition { handler.isStreaming.value }

        viewModel.cancelStream()
        gatewayHarness.awaitRpc("session.interrupt")
        viewModel.sendMessage("Start the next turn")

        // Let the bounded next-submit wait elapse first. The started turn's
        // tombstone must still drain its eventual terminal event rather than
        // routing it into the new active mapper.
        Thread.sleep(2_100)
        awaitCondition {
            gatewayHarness.rpcLog.any { (method, params) ->
                method == "prompt.submit" && params["text"] == JsonPrimitive("Start the next turn")
            }
        }
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Canceled answer") },
                "live-resumed",
            ),
        )

        persistedHistory = persistedAnswerHistory("New answer", "new-server-answer")
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "New answer") },
                "live-resumed",
            ),
        )
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "New answer") },
                "live-resumed",
            ),
        )

        awaitCondition { handler.messages.value.any { it.content == "New answer" } }
        awaitCondition { !handler.isStreaming.value }
        assertFalse(handler.messages.value.any { it.content == "Canceled answer" })
    }

    @Test
    fun queuedMessageDrainsAfterUnsolicitedTurnCompletes() {
        viewModel.switchProfileContext(PROFILE_CONTEXT, STORED_SESSION_ID)
        gatewayHarness.redirectStatus = "rejected"
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "Finishing background work") },
                "live-resumed",
            ),
        )
        awaitCondition { handler.isStreaming.value }

        viewModel.sendMessage("Run this next")
        gatewayHarness.awaitRpc("session.redirect")
        awaitCondition { viewModel.queuedMessages.value == listOf("Run this next") }

        persistedHistory = persistedAnswerHistory()
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Finishing background work") },
                "live-resumed",
            ),
        )

        awaitCondition {
            gatewayHarness.rpcLog.any { (method, params) ->
                method == "prompt.submit" &&
                    params["text"] == JsonPrimitive("Run this next") &&
                    params["queued"] == JsonPrimitive(true)
            }
        }
        assertTrue(viewModel.queuedMessages.value.isEmpty())
    }

    @Test
    fun multipleQueuedMessagesDrainAsAnOwnedRunChain() {
        viewModel.switchProfileContext(
            AgentDisplay.profileContextKey("connection-a", null),
            STORED_SESSION_ID,
        )
        gatewayHarness.redirectStatus = "rejected"
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        awaitCondition { handler.isStreaming.value }

        viewModel.sendMessage("Queued first")
        gatewayHarness.awaitRpc("session.redirect")
        viewModel.sendMessage("Queued second")
        gatewayHarness.awaitRpcCount("session.redirect", 2)
        awaitCondition {
            viewModel.queuedMessages.value == listOf("Queued first", "Queued second")
        }

        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Original complete") },
                "live-resumed",
            ),
        )
        awaitCondition {
            gatewayHarness.rpcLog.count { it.first == "prompt.submit" } == 1
        }
        assertEquals(listOf("Queued second"), viewModel.queuedMessages.value)

        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "First queued complete") },
                "live-resumed",
            ),
        )
        awaitCondition {
            gatewayHarness.rpcLog.filter { it.first == "prompt.submit" }
                .map { it.second["text"] } ==
                listOf(JsonPrimitive("Queued first"), JsonPrimitive("Queued second"))
        }
        assertTrue(viewModel.queuedMessages.value.isEmpty())
    }

    @Test
    fun queuedMessageStaysWithOriginWhileAnotherRunningSessionCompletes() {
        val secondSession = "stored-session-b"
        val contextKey = AgentDisplay.profileContextKey("connection-a", null)
        gatewayHarness.resumeLiveSessionIds[secondSession] = "live-b"
        gatewayHarness.redirectStatus = "rejected"
        viewModel.switchProfileContext(contextKey, STORED_SESSION_ID)

        viewModel.sendMessage("Run task A")
        gatewayHarness.awaitRpc("prompt.submit")
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "Partial A") },
                "live-resumed",
            ),
        )
        awaitCondition { handler.isStreaming.value }

        viewModel.sendMessage("Follow up A")
        gatewayHarness.awaitRpc("session.redirect")
        awaitCondition { viewModel.queuedMessages.value == listOf("Follow up A") }

        viewModel.switchSession(secondSession)
        gatewayHarness.awaitRpcCount("session.resume", 2)
        awaitCondition { handler.currentSessionId.value == secondSession && !handler.isStreaming.value }
        assertTrue("session B must not show A's queue", viewModel.queuedMessages.value.isEmpty())

        viewModel.sendMessage("Run task B")
        gatewayHarness.awaitRpcCount("prompt.submit", 2)
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Task B complete") },
                "live-b",
            ),
        )
        awaitCondition { !handler.isStreaming.value }
        assertEquals(
            0,
            gatewayHarness.rpcLog.count { (method, params) ->
                method == "prompt.submit" && params["text"] == JsonPrimitive("Follow up A")
            },
        )

        // A finishes late while B remains visible. Its queue becomes eligible,
        // but must not be dispatched through B's mutable visible route.
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Task A complete") },
                "live-resumed",
            ),
        )
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        assertTrue(viewModel.queuedMessages.value.isEmpty())
        assertEquals(
            0,
            gatewayHarness.rpcLog.count { (method, params) ->
                method == "prompt.submit" && params["text"] == JsonPrimitive("Follow up A")
            },
        )

        viewModel.switchSession(STORED_SESSION_ID)
        awaitCondition {
            gatewayHarness.rpcLog.any { (method, params) ->
                method == "prompt.submit" &&
                    params["text"] == JsonPrimitive("Follow up A") &&
                    params["queued"] == JsonPrimitive(true)
            }
        }
        assertTrue(viewModel.queuedMessages.value.isEmpty())
    }

    @Test
    fun queuedMessageVisibilityFollowsItsOriginProfile() {
        val defaultContext = AgentDisplay.profileContextKey("connection-a", null)
        val otherContext = AgentDisplay.profileContextKey("connection-a", "profile-b")
        gatewayHarness.redirectStatus = "rejected"
        viewModel.switchProfileContext(defaultContext, STORED_SESSION_ID)

        viewModel.sendMessage("Run in default profile")
        gatewayHarness.awaitRpc("prompt.submit")
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "Default profile partial") },
                "live-resumed",
            ),
        )
        awaitCondition { handler.isStreaming.value }
        viewModel.sendMessage("Default profile follow-up")
        gatewayHarness.awaitRpc("session.redirect")
        awaitCondition { viewModel.queuedMessages.value.size == 1 }

        viewModel.switchProfileContext(otherContext, STORED_SESSION_ID)
        awaitCondition { viewModel.queuedMessages.value.isEmpty() }

        gatewayHarness.recoveryRunning = true
        gatewayHarness.recoveryAssistant = "Default profile partial"
        viewModel.switchProfileContext(defaultContext, STORED_SESSION_ID)
        gatewayHarness.awaitRpc("session.activate")
        awaitCondition {
            handler.isStreaming.value &&
                viewModel.queuedMessages.value == listOf("Default profile follow-up")
        }
    }

    @Test
    fun connectionChangeCancelsOwnedQueueAndLateCompletionCannotResendIt() {
        val switches = MutableSharedFlow<String>(extraBufferCapacity = 1)
        gatewayHarness.redirectStatus = "rejected"
        viewModel.switchProfileContext(
            AgentDisplay.profileContextKey("connection-a", null),
            STORED_SESSION_ID,
        )
        viewModel.observeConnectionSwitches(switches)

        viewModel.sendMessage("Run before connection change")
        gatewayHarness.awaitRpc("prompt.submit")
        viewModel.sendMessage("Must stay on the old connection")
        awaitCondition { viewModel.queuedMessages.value.size == 1 }

        switches.tryEmit("connection-b")
        awaitCondition { viewModel.queuedMessages.value.isEmpty() }

        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Late old-connection completion") },
                "live-resumed",
            ),
        )
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        assertFalse(
            gatewayHarness.rpcLog.any { (method, params) ->
                method == "prompt.submit" &&
                    params["text"] == JsonPrimitive("Must stay on the old connection")
            },
        )
    }

    @Test
    fun deletingDestinationCancelsItsQueueAndEditRestoresOnlyThatItem() {
        gatewayHarness.redirectStatus = "rejected"
        viewModel.switchProfileContext(
            AgentDisplay.profileContextKey("connection-a", null),
            STORED_SESSION_ID,
        )
        handler.addSession(
            com.hermesandroid.relay.data.ChatSession(
                sessionId = STORED_SESSION_ID,
                title = "Owned session",
                model = null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        viewModel.profileSessionDeleter = { _, _, _ -> true }

        viewModel.sendMessage("Run before delete")
        gatewayHarness.awaitRpc("prompt.submit")
        viewModel.sendMessage("Edit this queued message")
        awaitCondition { viewModel.queuedMessages.value == listOf("Edit this queued message") }
        assertEquals("Edit this queued message", viewModel.takeQueuedForEdit(0))
        assertTrue(viewModel.queuedMessages.value.isEmpty())

        viewModel.sendMessage("Cancel when destination is deleted")
        awaitCondition { viewModel.queuedMessages.value == listOf("Cancel when destination is deleted") }
        viewModel.deleteSession(STORED_SESSION_ID)
        assertTrue(viewModel.queuedMessages.value.isEmpty())

        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Late deleted-session completion") },
                "live-resumed",
            ),
        )
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        assertFalse(
            gatewayHarness.rpcLog.any { (method, params) ->
                method == "prompt.submit" &&
                    params["text"] == JsonPrimitive("Cancel when destination is deleted")
            },
        )
    }

    @Test
    fun retryQueuedForActiveRunIsCancelledWithThatRun() {
        gatewayHarness.redirectStatus = "rejected"
        viewModel.switchProfileContext(
            AgentDisplay.profileContextKey("connection-a", null),
            STORED_SESSION_ID,
        )

        viewModel.sendMessage("Retry this turn")
        gatewayHarness.awaitRpc("prompt.submit")
        viewModel.retryLastMessage()
        gatewayHarness.awaitRpc("session.redirect")
        awaitCondition { viewModel.queuedMessages.value == listOf("Retry this turn") }

        viewModel.cancelStream()
        gatewayHarness.awaitRpc("session.interrupt")
        assertTrue(viewModel.queuedMessages.value.isEmpty())

        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Late completion after cancel") },
                "live-resumed",
            ),
        )
        Thread.sleep(100)
        shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
        assertEquals(
            1,
            gatewayHarness.rpcLog.count { (method, params) ->
                method == "prompt.submit" && params["text"] == JsonPrimitive("Retry this turn")
            },
        )
    }

    @Test
    fun coldForegroundPrewarmReloadsACompletionMissedWhileDisconnected() {
        persistedHistory = persistedAnswerHistory()
        serverWs.close(1012, "test disconnect")
        awaitCondition { gatewayClient.connectionState.value == GatewayConnectionState.Idle }

        viewModel.prewarmGateway()
        gatewayHarness.awaitServerSocket()
        gatewayHarness.awaitRpcCount("session.resume", 2)

        awaitCondition {
            handler.messages.value.singleOrNull()?.content == BACKGROUND_ANSWER
        }
        assertFalse(handler.isStreaming.value)
    }

    @Test
    fun visibleChatReattachesWhenSocketClosesAfterForegroundPrewarmRace() {
        persistedHistory = persistedAnswerHistory()
        viewModel.setChatVisible(true)

        // Foreground arrives while OkHttp still reports the old socket ready,
        // so the one-shot prewarm is an intentional no-op. The delayed close
        // callback must itself trigger an exact-session reattach.
        viewModel.prewarmGateway()
        serverWs.close(1012, "late background close")

        awaitCondition { gatewayHarness.ticketMints.get() >= 2 }
        serverWs = gatewayHarness.awaitServerSocket()
        gatewayHarness.awaitRpcCount("session.resume", 2)
        awaitCondition {
            handler.messages.value.singleOrNull()?.content == BACKGROUND_ANSWER
        }
        assertFalse(handler.isStreaming.value)
    }

    @Test
    fun foregroundMultiTurnRecoversWhenReconnectReportsSettledWithoutMessageComplete() {
        viewModel.setChatVisible(true)
        viewModel.sendMessage("Run a long foreground task")
        gatewayHarness.awaitRpc("prompt.submit")

        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "tool.start",
                buildJsonObject {
                    put("tool_id", "tool-foreground")
                    put("name", "terminal")
                },
                "live-resumed",
            ),
        )
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "Partial foreground answer") },
                "live-resumed",
            ),
        )
        awaitCondition { handler.isStreaming.value }

        // The authoritative turn is already persisted when the replacement
        // socket activates. No message.complete is replayed to that socket.
        persistedHistory = persistedAnswerHistory()
        gatewayHarness.recoveryRunning = false
        serverWs.close(1011, "foreground network gap")
        gatewayHarness.awaitServerSocket()
        gatewayHarness.awaitRpc("session.activate")

        awaitCondition { !handler.isStreaming.value }
        awaitCondition {
            handler.messages.value.singleOrNull()?.id == "persisted-background-answer"
        }
        assertEquals(BACKGROUND_ANSWER, handler.messages.value.single().content)
        assertEquals(0, apiCompletionsRequestCount.get())
    }

    @Test
    fun gatewayVoiceTurnDoesNotRequireApiFallback() {
        val result = viewModel.sendVoiceMessage(
            "local voice turn",
            "Respond for spoken playback",
        )
        val params = gatewayHarness.awaitRpc("prompt.submit")

        assertTrue(result is VoiceMessageSubmissionResult.Submitted)
        assertEquals(JsonPrimitive("local voice turn"), params["text"])
        assertEquals(0, apiCompletionsRequestCount.get())
    }

    @Test
    fun gatewayVoiceTurn_uploadsUntrustedTextAndScreenshotBeforePromptSubmit() {
        val accepted = AtomicInteger(0)
        gatewayHarness.fileAttachPayload = buildJsonObject {
            put("attached", true)
            put("ref_text", "@file:current-screen-context.txt")
        }
        val framed = """
            [UNTRUSTED SCREEN CONTENT]
            Visible screen text:
            Vehicle settings
            Attached current-screen image: untrusted user-provided screen content.
            [/UNTRUSTED SCREEN CONTENT]
        """.trimIndent()
        val contextBytes = framed.toByteArray()
        val contextAttachment = Attachment(
            contentType = "text/plain",
            content = Base64.getEncoder().encodeToString(contextBytes),
            fileName = "current-screen-context.txt",
            fileSize = contextBytes.size.toLong(),
        )
        val screenshot = Attachment(
            contentType = "image/jpeg",
            content = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
            fileName = "current-screen.jpg",
            fileSize = 3,
        )

        val result = viewModel.sendVoiceMessage(
            text = "What is on screen?",
            interfaceContextPrompt = framed,
            attachments = listOf(screenshot),
            gatewayAttachments = listOf(contextAttachment),
            hasScreenContext = true,
            onTransportAccepted = { accepted.incrementAndGet() },
        )
        val submit = gatewayHarness.awaitRpc("prompt.submit")
        val fileAttach = gatewayHarness.awaitRpc("file.attach")
        gatewayHarness.awaitRpc("image.attach_bytes")

        val methods = gatewayHarness.rpcLog.map { it.first }
        assertTrue(methods.indexOf("file.attach") < methods.indexOf("prompt.submit"))
        assertTrue(methods.indexOf("image.attach_bytes") < methods.indexOf("prompt.submit"))
        val dataUrl = (fileAttach["data_url"] as JsonPrimitive).content
        val uploadedText = String(Base64.getDecoder().decode(dataUrl.substringAfter(',')))
        assertEquals(framed, uploadedText)
        assertEquals(
            "@file:current-screen-context.txt\n\nWhat is on screen?",
            (submit["text"] as JsonPrimitive).content,
        )
        assertTrue(result is VoiceMessageSubmissionResult.Submitted)
        awaitCondition { accepted.get() == 1 }
        assertEquals(1, accepted.get())
        assertEquals(
            listOf(screenshot),
            handler.messages.value.last { it.role == MessageRole.USER }.attachments,
        )
        assertEquals(listOf(screenshot), viewModel.messages.value.last { it.role == MessageRole.USER }.attachments)
    }

    @Test
    fun voiceTurnExplicitAttachment_doesNotConsumeComposerDraftAttachment() {
        val draft = Attachment("text/plain", "ZHJhZnQ=", "draft.txt")
        val screen = Attachment("image/jpeg", "c2NyZWVu", "current-screen.jpg")
        viewModel.addAttachment(draft)

        val submitted = viewModel.sendVoiceMessage(
            "What is on screen?",
            "Untrusted screen context",
            listOf(screen),
        )

        assertTrue(submitted is VoiceMessageSubmissionResult.Submitted)
        assertEquals(listOf(draft), viewModel.pendingAttachments.value)
        assertEquals(listOf(screen), handler.messages.value.last { it.role == MessageRole.USER }.attachments)
    }

    @Test
    fun gatewayVoiceAttachmentPreflightFailure_doesNotAcceptContext() {
        gatewayHarness.methodNotFound.add("image.attach_bytes")
        gatewayHarness.methodNotFound.add("image.attach.bytes")
        val accepted = AtomicInteger(0)
        val failed = AtomicInteger(0)

        val result = viewModel.sendVoiceMessage(
            text = "Inspect this screen",
            interfaceContextPrompt = "[UNTRUSTED SCREEN CONTENT]",
            attachments = listOf(
                Attachment(
                    contentType = "image/jpeg",
                    content = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)),
                    fileName = "current-screen.jpg",
                )
            ),
            hasScreenContext = true,
            onTransportAccepted = { accepted.incrementAndGet() },
            onTransportFailed = { failed.incrementAndGet() },
        )

        assertTrue(result is VoiceMessageSubmissionResult.Submitted)
        awaitCondition { failed.get() == 1 }
        assertEquals(0, accepted.get())
        assertEquals(1, failed.get())
        assertTrue(gatewayHarness.rpcLog.none { it.first == "prompt.submit" })
    }

    @Test
    fun voiceTurnDuringActiveTurn_isRejectedAndRetainsComposerDraft() {
        val draft = Attachment("text/plain", "ZHJhZnQ=", "draft.txt")
        viewModel.sendMessage("First turn")
        gatewayHarness.awaitRpc("prompt.submit")
        viewModel.addAttachment(draft)

        val submitted = viewModel.sendVoiceMessage(
            "Second voice turn",
            "Untrusted screen context",
            listOf(Attachment("image/jpeg", "c2NyZWVu")),
        )

        assertTrue(submitted is VoiceMessageSubmissionResult.Rejected)
        assertEquals(listOf(draft), viewModel.pendingAttachments.value)
        assertEquals(1, handler.messages.value.count { it.role == MessageRole.USER })
    }

    @Test
    fun phoneThreadVoiceContext_isRejectedBeforeLocalTurnCreation() {
        assertNotNull(
            voiceTurnTransportRejection(
                pendingPhoneThread = true,
                activeSessionSource = null,
                hasIsolatedContext = true,
            )
        )
        assertNotNull(
            voiceTurnTransportRejection(
                pendingPhoneThread = false,
                activeSessionSource = "phone",
                hasIsolatedContext = true,
            )
        )
        assertNull(
            voiceTurnTransportRejection(
                pendingPhoneThread = true,
                activeSessionSource = null,
                hasIsolatedContext = false,
            )
        )

        handler.addSession(
            com.hermesandroid.relay.data.ChatSession(
                sessionId = "phone-thread",
                title = "Phone thread",
                model = null,
                source = "phone",
            )
        )
        handler.setSessionId("phone-thread")
        val beforeUsers = handler.messages.value.count { it.role == MessageRole.USER }

        val result = viewModel.sendVoiceMessage(
            text = "Use this screen",
            interfaceContextPrompt = "[UNTRUSTED SCREEN CONTENT]",
            gatewayAttachments = listOf(Attachment("text/plain", "Y29udGV4dA==")),
            hasScreenContext = true,
        )

        assertTrue(result is VoiceMessageSubmissionResult.Rejected)
        assertEquals(beforeUsers, handler.messages.value.count { it.role == MessageRole.USER })
        assertTrue(gatewayHarness.rpcLog.none { it.first == "prompt.submit" })

        val proactiveCalls = AtomicInteger(0)
        viewModel.onProactiveReply = { _, _, _, _ -> proactiveCalls.incrementAndGet() }
        val ordinaryVoice = viewModel.sendVoiceMessage(
            text = "Ordinary context-free voice",
            interfaceContextPrompt = "Respond for spoken playback",
            hasScreenContext = false,
        )

        assertTrue(ordinaryVoice is VoiceMessageSubmissionResult.Submitted)
        assertEquals(1, proactiveCalls.get())
    }

    @Test
    fun acceptedInboundTurnSettlesAfterGatewayDowngrade() {
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )
        awaitCondition { handler.isStreaming.value }

        viewModel.streamingEndpoint = "sessions"
        viewModel.updateGatewayClient(null)
        persistedHistory = persistedAnswerHistory()
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )

        awaitCondition { !handler.isStreaming.value }
        awaitCondition {
            handler.messages.value.singleOrNull()?.id == "persisted-background-answer"
        }
        assertEquals(BACKGROUND_ANSWER, handler.messages.value.single().content)
    }

    @Test
    fun lateOldCompletionDoesNotClearNewGatewayTurnSteering() {
        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", "Old inbound") },
                "live-resumed",
            ),
        )
        awaitCondition { handler.isStreaming.value }

        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", "Old inbound") },
                "live-resumed",
            ),
        )
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (gatewayClient.hasActiveTurn() && System.nanoTime() < deadline) {
            // Deliberately do not idle main: keep the old completion callback
            // queued while the socket-side mapper reaches its terminal state.
            Thread.sleep(20)
        }
        assertFalse("old gateway turn never ended", gatewayClient.hasActiveTurn())

        viewModel.cancelStream()
        viewModel.sendMessage("New gateway turn")
        awaitCondition { gatewayClient.hasActiveTurn() }

        // Pump the queued old callback. It no longer owns activeStream and must
        // not clear the new turn's steering affordance.
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.steerableTurn.value)
    }

    @Test
    fun reconnectAfterMissedStartRecoversOnExactSessionCompletion() {
        serverWs.close(1012, "missed start")
        awaitCondition { gatewayClient.connectionState.value == GatewayConnectionState.Idle }
        viewModel.prewarmGateway()
        serverWs = gatewayHarness.awaitServerSocket()
        gatewayHarness.awaitRpcCount("session.resume", 2)

        // Reconnected midway through the synthetic turn: no message.start is
        // replayed, so the delta is intentionally ignored and completion drives
        // authoritative history recovery.
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )
        persistedHistory = persistedAnswerHistory()
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )

        awaitCondition { handler.messages.value.any { it.content == BACKGROUND_ANSWER } }
        assertFalse(handler.isStreaming.value)
    }

    @Test
    fun staleHistoryReadCannotEraseATurnCompletedDuringTheFetch() {
        val loadCount = AtomicInteger(0)
        val firstLoadStarted = CompletableDeferred<Unit>()
        val releaseFirstLoad = CompletableDeferred<Unit>()
        viewModel.setProfileMessageLoader {
            when (loadCount.incrementAndGet()) {
                1 -> {
                    firstLoadStarted.complete(Unit)
                    releaseFirstLoad.await()
                    Result.success(persistedAnswerHistory())
                }
                else -> Result.success(
                    persistedAnswerHistory() +
                        MessageItem(
                            id = "newer-local-answer",
                            sessionId = STORED_SESSION_ID,
                            role = "assistant",
                            content = JsonPrimitive("Newer answer"),
                        ),
                )
            }
        }

        serverWs.send(gatewayHarness.eventFrame("message.start", null, "live-resumed"))
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.delta",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )
        serverWs.send(
            gatewayHarness.eventFrame(
                "message.complete",
                buildJsonObject { put("text", BACKGROUND_ANSWER) },
                "live-resumed",
            ),
        )
        awaitCondition { firstLoadStarted.isCompleted }

        handler.addPlaceholderMessage(
            ChatMessage(
                id = "newer-local-answer",
                role = MessageRole.ASSISTANT,
                content = "Newer answer",
                timestamp = System.currentTimeMillis(),
                isStreaming = true,
            ),
        )
        handler.onStreamComplete("newer-local-answer")
        releaseFirstLoad.complete(Unit)

        awaitCondition {
            handler.messages.value.any { it.content == "Newer answer" } && loadCount.get() >= 2
        }
        assertTrue(handler.messages.value.any { it.content == BACKGROUND_ANSWER })
    }

    private fun bindActivityTestDirectory() {
        viewModel.switchProfileContext(
            AgentDisplay.profileContextKey("connection-a", "default"),
            STORED_SESSION_ID,
        )
        viewModel.updateSessionActivityDirectory(
            rows = listOf("default" to STORED_SESSION_ID),
        )
    }

    private fun activeSessionPayload(status: String) = buildJsonObject {
        put("sessions", buildJsonArray {
            add(buildJsonObject {
                put("id", "live-resumed")
                put("session_key", STORED_SESSION_ID)
                put("status", status)
                put("last_active", 1.0)
            })
        })
    }

    private fun persistedAnswerHistory(
        answer: String = BACKGROUND_ANSWER,
        id: String = "persisted-background-answer",
    ): List<MessageItem> = listOf(
        MessageItem(
            id = id,
            sessionId = STORED_SESSION_ID,
            role = "assistant",
            content = JsonPrimitive(answer),
        ),
    )

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            // Advance Robolectric's paused main clock so coroutine delay-based
            // reconciliation retries can resume as they do on-device.
            shadowOf(Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("condition not met; messages=${handler.messages.value}", condition())
    }

    companion object {
        private const val STORED_SESSION_ID = "stored-session"
        private const val PROFILE_CONTEXT = "connection-a/profile-default"
        private const val BACKGROUND_ANSWER = "Background task finished."
    }
}
