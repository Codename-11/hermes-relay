package com.hermesandroid.relay.network.relay

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okio.ByteString
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RelayVoiceClientRoutingTest {

    private lateinit var lanServer: MockWebServer
    private lateinit var tailscaleServer: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var context: Context

    @Before
    fun setUp() {
        lanServer = MockWebServer().apply {
            dispatcher = voiceRouteDispatcher()
            start()
        }
        tailscaleServer = MockWebServer().apply {
            dispatcher = voiceRouteDispatcher()
            start()
        }
        httpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        context = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        runCatching { lanServer.shutdown() }
        runCatching { tailscaleServer.shutdown() }
    }

    @Test
    fun realtimeAgentAndVoiceOutputFollowSameEffectiveRelayUrlProvider() = runTest {
        var activeRelayUrl = relayUrl(lanServer)
        val client = RelayVoiceClient(
            context = context,
            okHttpClient = httpClient,
            relayUrlProvider = { activeRelayUrl },
            sessionTokenProvider = { "session-token" },
        )

        val stableResult = client.runVoiceOutput("Stable voice route test") {}
        assertTrue(stableResult.exceptionOrNull()?.message, stableResult.isSuccess)

        activeRelayUrl = relayUrl(tailscaleServer)
        val realtimeResult = client.runRealtimeAgent(
            prompt = "Realtime voice route test",
            inputPcm = ByteArray(0),
        ) { _, _ -> }
        assertTrue(realtimeResult.exceptionOrNull()?.message, realtimeResult.isSuccess)

        assertEquals(
            listOf("/voice/output/session", "/voice/output/session-test"),
            requestPaths(lanServer, count = 2),
        )
        assertEquals(
            listOf("/voice/realtime-agent/session", "/voice/realtime-agent/session-test"),
            requestPaths(tailscaleServer, count = 2),
        )
    }

    @Test
    fun realtimeAgentSessionResponseParsesResumeMetadata() {
        val response = Json.decodeFromString(
            RealtimeSessionResponse.serializer(),
            """
                {
                  "success": true,
                  "session_id": "realtime-agent-test",
                  "websocket_path": "/voice/realtime-agent/session-test",
                  "resume_token": "resume-token-1",
                  "resume_supported": true,
                  "resume_ttl_ms": 30000,
                  "provider": "xai_realtime",
                  "model": "grok-voice-latest",
                  "voice": "leo",
                  "sample_rate": 24000
                }
            """.trimIndent(),
        )

        assertEquals("resume-token-1", response.resumeToken)
        assertTrue(response.resumeSupported)
        assertEquals(30000L, response.resumeTtlMs)
    }

    @Test
    fun realtimeAgentSessionSendsModelAndVoiceOverrides() = runTest {
        val client = RelayVoiceClient(
            context = context,
            okHttpClient = httpClient,
            relayUrlProvider = { relayUrl(lanServer) },
            sessionTokenProvider = { "session-token" },
        )

        val result = client.runRealtimeAgent(
            prompt = "Use my selected realtime voice",
            inputPcm = ByteArray(0),
            model = "grok-voice-think-fast-1.0",
            voice = "leo",
        ) { _, _ -> }

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        val request = lanServer.takeRequest(2, TimeUnit.SECONDS)
            ?: error("missing realtime session request")
        val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("grok-voice-think-fast-1.0", payload["model"]?.jsonPrimitive?.content)
        assertEquals("leo", payload["voice"]?.jsonPrimitive?.content)
    }

    @Test
    fun voiceOutputSessionResponseParsesResumeMetadata() {
        val response = Json.decodeFromString(
            VoiceOutputSessionResponse.serializer(),
            """
                {
                  "success": true,
                  "session_id": "voice-output-test",
                  "websocket_path": "/voice/output/session-test",
                  "resume_token": "voice-resume-token-1",
                  "resume_supported": true,
                  "resume_ttl_ms": 30000,
                  "provider": "stub",
                  "model": "local-tone",
                  "voice": "sine",
                  "sample_rate": 24000
                }
            """.trimIndent(),
        )

        assertEquals("voice-resume-token-1", response.resumeToken)
        assertTrue(response.resumeSupported)
        assertEquals(30000L, response.resumeTtlMs)
    }

    @Test
    fun voiceOutputResumeOpensWebsocketWithCurrentRelayUrl() = runTest {
        var activeRelayUrl = relayUrl(lanServer)
        val routeProbeRequests = AtomicInteger(0)
        val openedUrls = Collections.synchronizedList(mutableListOf<String>())
        val sentMessages = Collections.synchronizedList(mutableListOf<String>())
        lanServer.dispatcher = sessionOnlyDispatcher(
            path = "/voice/output/session",
            body = """
                {
                  "success": true,
                  "session_id": "voice-output-resume-test",
                  "websocket_path": "/voice/output/session-test",
                  "resume_token": "voice-resume-token-1",
                  "resume_supported": true,
                  "resume_ttl_ms": 30000,
                  "provider": "stub",
                  "model": "local-tone",
                  "voice": "sine",
                  "sample_rate": 24000
                }
            """.trimIndent(),
        )
        val client = RelayVoiceClient(
            context = context,
            okHttpClient = httpClient,
            relayUrlProvider = { activeRelayUrl },
            sessionTokenProvider = { "session-token" },
            routeProbeRequester = { routeProbeRequests.incrementAndGet() },
            webSocketFactory = { request, listener ->
                val index = openedUrls.size
                lateinit var socket: ScriptedWebSocket
                socket = ScriptedWebSocket(request, listener) { message ->
                    sentMessages.add(message)
                    if (index == 1 && message.contains("session.resume")) {
                        listener.onMessage(
                            socket,
                            """{"type":"voice.response.done","provider":"stub","model":"local-tone","voice":"sine"}""",
                        )
                    }
                }
                openedUrls.add(request.url.toString())
                listener.onOpen(socket, mockk(relaxed = true))
                if (index == 0) {
                    activeRelayUrl = relayUrl(tailscaleServer)
                    listener.onFailure(socket, IOException("network changed"), null)
                }
                socket
            },
        )

        val result = client.runVoiceOutput("Stable resume route test") {}

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertEquals(
            listOf(
                "${requestUrl(lanServer)}/voice/output/session-test",
                "${requestUrl(tailscaleServer)}/voice/output/session-test",
            ),
            openedUrls,
        )
        assertTrue(sentMessages.any { it.contains("session.resume") && it.contains("voice-resume-token-1") })
        assertEquals(1, routeProbeRequests.get())
    }

    @Test
    fun voiceOutputRouteChangeProactivelyResumesBeforeSocketFailure() = runBlocking {
        var activeRelayUrl = relayUrl(lanServer)
        val routeSwitch = CompletableDeferred<String>()
        val firstSocketOpened = CountDownLatch(1)
        val routeWatcherStarted = CountDownLatch(1)
        val openedUrls = Collections.synchronizedList(mutableListOf<String>())
        val sentMessages = Collections.synchronizedList(mutableListOf<String>())
        lanServer.dispatcher = sessionOnlyDispatcher(
            path = "/voice/output/session",
            body = """
                {
                  "success": true,
                  "session_id": "voice-output-route-watch-test",
                  "websocket_path": "/voice/output/session-test",
                  "resume_token": "voice-route-token-1",
                  "resume_supported": true,
                  "resume_ttl_ms": 30000,
                  "provider": "stub",
                  "model": "local-tone",
                  "voice": "sine",
                  "sample_rate": 24000
                }
            """.trimIndent(),
        )
        val client = RelayVoiceClient(
            context = context,
            okHttpClient = httpClient,
            relayUrlProvider = { activeRelayUrl },
            relayRouteChangesProvider = {
                flow {
                    emit(activeRelayUrl)
                    routeWatcherStarted.countDown()
                    emit(routeSwitch.await())
                }
            },
            sessionTokenProvider = { "session-token" },
            webSocketFactory = { request, listener ->
                val index = openedUrls.size
                lateinit var socket: ScriptedWebSocket
                socket = ScriptedWebSocket(request, listener) { message ->
                    sentMessages.add(message)
                    if (index == 1 && message.contains("session.resume")) {
                        listener.onMessage(
                            socket,
                            """{"type":"voice.response.done","provider":"stub","model":"local-tone","voice":"sine"}""",
                        )
                    }
                }
                openedUrls.add(request.url.toString())
                listener.onOpen(socket, mockk(relaxed = true))
                if (index == 0) {
                    firstSocketOpened.countDown()
                }
                socket
            },
        )

        val deferred = async(Dispatchers.IO) {
            client.runVoiceOutput("Stable proactive resume route test") {}
        }
        assertTrue(firstSocketOpened.await(2, TimeUnit.SECONDS))
        assertTrue(routeWatcherStarted.await(2, TimeUnit.SECONDS))
        val nextRelayUrl = relayUrl(tailscaleServer)
        routeSwitch.complete(nextRelayUrl)

        val result = withTimeout(5_000) { deferred.await() }
        activeRelayUrl = nextRelayUrl

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertEquals(
            listOf(
                "${requestUrl(lanServer)}/voice/output/session-test",
                "${requestUrl(tailscaleServer)}/voice/output/session-test",
            ),
            openedUrls,
        )
        assertTrue(sentMessages.any { it.contains("session.resume") && it.contains("voice-route-token-1") })
    }

    @Test
    fun realtimeAgentResumeOpensWebsocketWithCurrentRelayUrl() = runTest {
        var activeRelayUrl = relayUrl(lanServer)
        val routeProbeRequests = AtomicInteger(0)
        val openedUrls = Collections.synchronizedList(mutableListOf<String>())
        val sentMessages = Collections.synchronizedList(mutableListOf<String>())
        lanServer.dispatcher = sessionOnlyDispatcher(
            path = "/voice/realtime-agent/session",
            body = """
                {
                  "success": true,
                  "session_id": "realtime-agent-resume-test",
                  "websocket_path": "/voice/realtime-agent/session-test",
                  "resume_token": "realtime-resume-token-1",
                  "resume_supported": true,
                  "resume_ttl_ms": 30000,
                  "provider": "xai_realtime",
                  "model": "grok-voice-latest",
                  "voice": "leo",
                  "sample_rate": 24000
                }
            """.trimIndent(),
        )
        val client = RelayVoiceClient(
            context = context,
            okHttpClient = httpClient,
            relayUrlProvider = { activeRelayUrl },
            sessionTokenProvider = { "session-token" },
            routeProbeRequester = { routeProbeRequests.incrementAndGet() },
            webSocketFactory = { request, listener ->
                val index = openedUrls.size
                lateinit var socket: ScriptedWebSocket
                socket = ScriptedWebSocket(request, listener) { message ->
                    sentMessages.add(message)
                    if (index == 1 && message.contains("session.resume")) {
                        listener.onMessage(
                            socket,
                            """{"type":"voice.response.done","provider":"xai_realtime","model":"grok-voice-latest","voice":"leo"}""",
                        )
                    }
                }
                openedUrls.add(request.url.toString())
                listener.onOpen(socket, mockk(relaxed = true))
                if (index == 0) {
                    activeRelayUrl = relayUrl(tailscaleServer)
                    listener.onFailure(socket, IOException("network changed"), null)
                }
                socket
            },
        )

        val result = client.runRealtimeAgent(
            prompt = "Realtime resume route test",
            inputPcm = ByteArray(0),
        ) { _, _ -> }

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertEquals(
            listOf(
                "${requestUrl(lanServer)}/voice/realtime-agent/session-test",
                "${requestUrl(tailscaleServer)}/voice/realtime-agent/session-test",
            ),
            openedUrls,
        )
        assertTrue(sentMessages.any { it.contains("session.resume") && it.contains("realtime-resume-token-1") })
        assertEquals(1, routeProbeRequests.get())
    }

    @Test
    fun realtimeAgentRouteChangeProactivelyResumesBeforeSocketFailure() = runBlocking {
        var activeRelayUrl = relayUrl(lanServer)
        val routeSwitch = CompletableDeferred<String>()
        val firstSocketOpened = CountDownLatch(1)
        val routeWatcherStarted = CountDownLatch(1)
        val openedUrls = Collections.synchronizedList(mutableListOf<String>())
        val sentMessages = Collections.synchronizedList(mutableListOf<String>())
        lanServer.dispatcher = sessionOnlyDispatcher(
            path = "/voice/realtime-agent/session",
            body = """
                {
                  "success": true,
                  "session_id": "realtime-agent-route-watch-test",
                  "websocket_path": "/voice/realtime-agent/session-test",
                  "resume_token": "realtime-route-token-1",
                  "resume_supported": true,
                  "resume_ttl_ms": 30000,
                  "provider": "xai_realtime",
                  "model": "grok-voice-latest",
                  "voice": "leo",
                  "sample_rate": 24000
                }
            """.trimIndent(),
        )
        val client = RelayVoiceClient(
            context = context,
            okHttpClient = httpClient,
            relayUrlProvider = { activeRelayUrl },
            relayRouteChangesProvider = {
                flow {
                    emit(activeRelayUrl)
                    routeWatcherStarted.countDown()
                    emit(routeSwitch.await())
                }
            },
            sessionTokenProvider = { "session-token" },
            webSocketFactory = { request, listener ->
                val index = openedUrls.size
                lateinit var socket: ScriptedWebSocket
                socket = ScriptedWebSocket(request, listener) { message ->
                    sentMessages.add(message)
                    if (index == 1 && message.contains("session.resume")) {
                        listener.onMessage(
                            socket,
                            """{"type":"voice.response.done","provider":"xai_realtime","model":"grok-voice-latest","voice":"leo"}""",
                        )
                    }
                }
                openedUrls.add(request.url.toString())
                listener.onOpen(socket, mockk(relaxed = true))
                if (index == 0) {
                    firstSocketOpened.countDown()
                }
                socket
            },
        )

        val deferred = async(Dispatchers.IO) {
            client.runRealtimeAgent(
                prompt = "Realtime proactive resume route test",
                inputPcm = ByteArray(0),
            ) { _, _ -> }
        }
        assertTrue(firstSocketOpened.await(2, TimeUnit.SECONDS))
        assertTrue(routeWatcherStarted.await(2, TimeUnit.SECONDS))
        val nextRelayUrl = relayUrl(tailscaleServer)
        routeSwitch.complete(nextRelayUrl)

        val result = withTimeout(5_000) { deferred.await() }
        activeRelayUrl = nextRelayUrl

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertEquals(
            listOf(
                "${requestUrl(lanServer)}/voice/realtime-agent/session-test",
                "${requestUrl(tailscaleServer)}/voice/realtime-agent/session-test",
            ),
            openedUrls,
        )
        assertTrue(sentMessages.any { it.contains("session.resume") && it.contains("realtime-route-token-1") })
    }

    @Test
    fun realtimeAgentRetriesResumeOnRouteChangeAfterStaleRouteResumeFails() = runBlocking {
        var activeRelayUrl = relayUrl(lanServer)
        val routeSwitch = CompletableDeferred<String>()
        val routeWatcherStarted = CountDownLatch(1)
        val staleResumeFailed = CountDownLatch(1)
        val openedUrls = Collections.synchronizedList(mutableListOf<String>())
        val sentMessages = Collections.synchronizedList(mutableListOf<String>())
        lanServer.dispatcher = sessionOnlyDispatcher(
            path = "/voice/realtime-agent/session",
            body = """
                {
                  "success": true,
                  "session_id": "realtime-agent-stale-route-test",
                  "websocket_path": "/voice/realtime-agent/session-test",
                  "resume_token": "realtime-stale-route-token-1",
                  "resume_supported": true,
                  "resume_ttl_ms": 30000,
                  "provider": "xai_realtime",
                  "model": "grok-voice-latest",
                  "voice": "leo",
                  "sample_rate": 24000
                }
            """.trimIndent(),
        )
        val client = RelayVoiceClient(
            context = context,
            okHttpClient = httpClient,
            relayUrlProvider = { activeRelayUrl },
            relayRouteChangesProvider = {
                flow {
                    emit(activeRelayUrl)
                    routeWatcherStarted.countDown()
                    emit(routeSwitch.await())
                }
            },
            sessionTokenProvider = { "session-token" },
            webSocketFactory = { request, listener ->
                val index = openedUrls.size
                lateinit var socket: ScriptedWebSocket
                socket = ScriptedWebSocket(request, listener) { message ->
                    sentMessages.add(message)
                    if (index == 1 && message.contains("session.resume")) {
                        listener.onFailure(socket, IOException("stale LAN route"), null)
                        staleResumeFailed.countDown()
                    }
                    if (index == 2 && message.contains("session.resume")) {
                        listener.onMessage(
                            socket,
                            """{"type":"voice.session.resumed","event_id":7,"session_id":"realtime-agent-stale-route-test"}""",
                        )
                        listener.onMessage(
                            socket,
                            """{"type":"voice.response.done","provider":"xai_realtime","model":"grok-voice-latest","voice":"leo"}""",
                        )
                    }
                }
                openedUrls.add(request.url.toString())
                listener.onOpen(socket, mockk(relaxed = true))
                if (index == 0) {
                    listener.onFailure(socket, IOException("wifi lost"), null)
                }
                socket
            },
        )

        val deferred = async(Dispatchers.IO) {
            client.runRealtimeAgent(
                prompt = "Realtime stale route retry test",
                inputPcm = ByteArray(0),
            ) { _, _ -> }
        }
        assertTrue(staleResumeFailed.await(2, TimeUnit.SECONDS))
        assertTrue(routeWatcherStarted.await(2, TimeUnit.SECONDS))
        val nextRelayUrl = relayUrl(tailscaleServer)
        routeSwitch.complete(nextRelayUrl)

        val result = withTimeout(5_000) { deferred.await() }
        activeRelayUrl = nextRelayUrl

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertEquals(
            listOf(
                "${requestUrl(lanServer)}/voice/realtime-agent/session-test",
                "${requestUrl(lanServer)}/voice/realtime-agent/session-test",
                "${requestUrl(tailscaleServer)}/voice/realtime-agent/session-test",
            ),
            openedUrls,
        )
        assertTrue(sentMessages.count { it.contains("session.resume") && it.contains("realtime-stale-route-token-1") } >= 2)
    }

    @Test
    fun persistentRealtimeTurnWaitsForResumedSocketAfterBackgroundRouteLoss() = runBlocking {
        val openedUrls = Collections.synchronizedList(mutableListOf<String>())
        val sentBySocket = Collections.synchronizedList(mutableListOf<MutableList<String>>())
        val firstOpened = CountDownLatch(1)
        val secondOpened = CountDownLatch(1)
        val thirdOpened = CountDownLatch(1)
        val resumedTurnCommitted = CountDownLatch(1)
        val turnCompleted = CompletableDeferred<Unit>()
        val abandonedDelivery = CompletableDeferred<Result<Unit>>()
        val turnDelivered = CompletableDeferred<Result<Unit>>()
        val turns = Channel<RealtimeTurnInput>(Channel.UNLIMITED)
        lateinit var firstSocket: ScriptedWebSocket
        lateinit var firstListener: WebSocketListener
        lateinit var secondSocket: ScriptedWebSocket
        lateinit var secondListener: WebSocketListener
        lateinit var thirdSocket: ScriptedWebSocket
        lateinit var thirdListener: WebSocketListener
        lanServer.dispatcher = sessionOnlyDispatcher(
            path = "/voice/realtime-agent/session",
            body = """
                {
                  "success": true,
                  "session_id": "realtime-agent-background-resume-test",
                  "websocket_path": "/voice/realtime-agent/session-test",
                  "resume_token": "realtime-background-token-1",
                  "resume_supported": true,
                  "resume_ttl_ms": 300000,
                  "provider": "xai_realtime",
                  "model": "grok-voice-latest",
                  "voice": "leo",
                  "sample_rate": 24000
                }
            """.trimIndent(),
        )
        val client = RelayVoiceClient(
            context = context,
            okHttpClient = httpClient,
            relayUrlProvider = { relayUrl(lanServer) },
            sessionTokenProvider = { "session-token" },
            webSocketFactory = { request, listener ->
                val index = openedUrls.size
                val messages = Collections.synchronizedList(mutableListOf<String>())
                sentBySocket.add(messages)
                lateinit var socket: ScriptedWebSocket
                socket = ScriptedWebSocket(request, listener) { message ->
                    messages.add(message)
                    if (index == 2 && message.contains("input_audio.commit")) {
                        resumedTurnCommitted.countDown()
                    }
                }
                openedUrls.add(request.url.toString())
                when (index) {
                    0 -> {
                        firstSocket = socket
                        firstListener = listener
                        listener.onOpen(socket, mockk(relaxed = true))
                        firstOpened.countDown()
                    }
                    1 -> {
                        secondSocket = socket
                        secondListener = listener
                        listener.onOpen(socket, mockk(relaxed = true))
                        secondOpened.countDown()
                    }
                    else -> {
                        thirdSocket = socket
                        thirdListener = listener
                        thirdOpened.countDown()
                    }
                }
                socket
            },
        )

        val sessionJob = async(Dispatchers.IO) {
            client.runRealtimeAgent(
                prompt = "",
                inputPcm = ByteArray(0),
                turnInputs = turns,
                prewarm = true,
                onTurnComplete = { turnCompleted.complete(Unit) },
            ) { _, _ -> }
        }

        try {
            assertTrue(firstOpened.await(2, TimeUnit.SECONDS))
            firstListener.onFailure(firstSocket, IOException("background route lost"), null)
            assertTrue(secondOpened.await(2, TimeUnit.SECONDS))
            secondListener.onFailure(secondSocket, IOException("route unavailable while backgrounded"), null)

            turns.send(
                RealtimeTurnInput(
                    inputPcm = ByteArray(6_400) { 7 },
                    deliveryResult = abandonedDelivery,
                )
            )

            assertTrue(
                "A foreground turn must trigger an immediate resume instead of using the failed socket",
                thirdOpened.await(2, TimeUnit.SECONDS),
            )
            assertTrue("Turn delivery must wait for replacement onOpen", !abandonedDelivery.isCompleted)
            abandonedDelivery.cancel()
            turns.send(
                RealtimeTurnInput(
                    inputPcm = ByteArray(6_400) { 9 },
                    deliveryResult = turnDelivered,
                )
            )
            thirdListener.onOpen(thirdSocket, mockk(relaxed = true))
            assertTrue(
                "The queued turn must be committed on the replacement socket",
                resumedTurnCommitted.await(2, TimeUnit.SECONDS),
            )
            assertTrue(withTimeout(2_000) { turnDelivered.await() }.isSuccess)
            thirdListener.onMessage(
                thirdSocket,
                """{"type":"voice.response.done","provider":"xai_realtime","model":"grok-voice-latest","voice":"leo"}""",
            )
            withTimeout(2_000) { turnCompleted.await() }

            assertTrue(sentBySocket[2].any { it.contains("session.resume") })
            assertEquals(1, sentBySocket[2].count { it.contains("input_audio.append") })
            assertTrue(sentBySocket[2].any { it.contains("input_audio.commit") })
            assertTrue(sentBySocket[1].none { it.contains("input_audio.append") })
        } finally {
            turns.close()
            withTimeout(2_000) { sessionJob.await() }
        }
    }

    @Test
    fun persistentRealtimeTurnCoalescesWhileResumeHandshakeIsPending() = runBlocking {
        val sentBySocket = Collections.synchronizedList(mutableListOf<MutableList<String>>())
        val firstOpened = CountDownLatch(1)
        val pendingResumeCreated = CountDownLatch(1)
        val unexpectedOverlap = CountDownLatch(1)
        val turns = Channel<RealtimeTurnInput>(Channel.UNLIMITED)
        val delivered = CompletableDeferred<Result<Unit>>()
        val turnCompleted = CompletableDeferred<Unit>()
        lateinit var firstSocket: ScriptedWebSocket
        lateinit var firstListener: WebSocketListener
        lateinit var pendingSocket: ScriptedWebSocket
        lateinit var pendingListener: WebSocketListener
        lanServer.dispatcher = sessionOnlyDispatcher(
            path = "/voice/realtime-agent/session",
            body = """
                {
                  "success": true,
                  "session_id": "realtime-agent-pending-resume-test",
                  "websocket_path": "/voice/realtime-agent/session-test",
                  "resume_token": "realtime-pending-resume-token",
                  "resume_supported": true,
                  "resume_ttl_ms": 300000,
                  "provider": "xai_realtime",
                  "model": "grok-voice-latest",
                  "voice": "leo",
                  "sample_rate": 24000
                }
            """.trimIndent(),
        )
        val client = RelayVoiceClient(
            context = context,
            okHttpClient = httpClient,
            relayUrlProvider = { relayUrl(lanServer) },
            sessionTokenProvider = { "session-token" },
            webSocketFactory = { request, listener ->
                val index = sentBySocket.size
                val messages = Collections.synchronizedList(mutableListOf<String>())
                sentBySocket.add(messages)
                val socket = ScriptedWebSocket(request, listener) { messages.add(it) }
                when (index) {
                    0 -> {
                        firstSocket = socket
                        firstListener = listener
                        listener.onOpen(socket, mockk(relaxed = true))
                        firstOpened.countDown()
                    }
                    1 -> {
                        pendingSocket = socket
                        pendingListener = listener
                        pendingResumeCreated.countDown()
                    }
                    else -> unexpectedOverlap.countDown()
                }
                socket
            },
        )
        val sessionJob = async(Dispatchers.IO) {
            client.runRealtimeAgent(
                prompt = "",
                inputPcm = ByteArray(0),
                turnInputs = turns,
                prewarm = true,
                onTurnComplete = { turnCompleted.complete(Unit) },
            ) { _, _ -> }
        }

        try {
            assertTrue(firstOpened.await(2, TimeUnit.SECONDS))
            firstListener.onFailure(firstSocket, IOException("route lost"), null)
            assertTrue(pendingResumeCreated.await(2, TimeUnit.SECONDS))
            turns.send(
                RealtimeTurnInput(
                    inputPcm = ByteArray(6_400) { 8 },
                    deliveryResult = delivered,
                )
            )

            delay(250)
            assertTrue("A waiting turn must not open a competing resume", unexpectedOverlap.count == 1L)
            assertEquals(2, sentBySocket.size)
            assertTrue(!delivered.isCompleted)

            pendingListener.onOpen(pendingSocket, mockk(relaxed = true))
            assertTrue(withTimeout(2_000) { delivered.await() }.isSuccess)
            assertEquals(1, sentBySocket[1].count { it.contains("session.resume") })
            assertEquals(1, sentBySocket[1].count { it.contains("input_audio.append") })
            assertEquals(1, sentBySocket[1].count { it.contains("input_audio.commit") })
            pendingListener.onMessage(
                pendingSocket,
                """{"type":"voice.response.done","provider":"xai_realtime","model":"grok-voice-latest","voice":"leo"}""",
            )
            withTimeout(2_000) { turnCompleted.await() }
        } finally {
            turns.close()
            withTimeout(2_000) { sessionJob.await() }
        }
    }

    @Test
    fun persistentRealtimeTurnRetriesWhenCoalescedResumeLaterFails() = runBlocking {
        val sentBySocket = Collections.synchronizedList(mutableListOf<MutableList<String>>())
        val firstOpened = CountDownLatch(1)
        val pendingResumeCreated = CountDownLatch(1)
        val replacementResumeCreated = CountDownLatch(1)
        val turns = Channel<RealtimeTurnInput>()
        val delivered = CompletableDeferred<Result<Unit>>()
        val turnCompleted = CompletableDeferred<Unit>()
        lateinit var firstSocket: ScriptedWebSocket
        lateinit var firstListener: WebSocketListener
        lateinit var pendingSocket: ScriptedWebSocket
        lateinit var pendingListener: WebSocketListener
        lateinit var replacementSocket: ScriptedWebSocket
        lateinit var replacementListener: WebSocketListener
        lanServer.dispatcher = sessionOnlyDispatcher(
            path = "/voice/realtime-agent/session",
            body = """
                {
                  "success": true,
                  "session_id": "realtime-agent-coalesced-failure-test",
                  "websocket_path": "/voice/realtime-agent/session-test",
                  "resume_token": "realtime-coalesced-failure-token",
                  "resume_supported": true,
                  "resume_ttl_ms": 300000,
                  "provider": "xai_realtime",
                  "model": "grok-voice-latest",
                  "voice": "leo",
                  "sample_rate": 24000
                }
            """.trimIndent(),
        )
        val client = RelayVoiceClient(
            context = context,
            okHttpClient = httpClient,
            relayUrlProvider = { relayUrl(lanServer) },
            sessionTokenProvider = { "session-token" },
            webSocketFactory = { request, listener ->
                val index = sentBySocket.size
                val messages = Collections.synchronizedList(mutableListOf<String>())
                sentBySocket.add(messages)
                val socket = ScriptedWebSocket(request, listener) { messages.add(it) }
                when (index) {
                    0 -> {
                        firstSocket = socket
                        firstListener = listener
                        listener.onOpen(socket, mockk(relaxed = true))
                        firstOpened.countDown()
                    }
                    1 -> {
                        pendingSocket = socket
                        pendingListener = listener
                        pendingResumeCreated.countDown()
                    }
                    else -> {
                        replacementSocket = socket
                        replacementListener = listener
                        replacementResumeCreated.countDown()
                    }
                }
                socket
            },
        )
        val sessionJob = async(Dispatchers.IO) {
            client.runRealtimeAgent(
                prompt = "",
                inputPcm = ByteArray(0),
                turnInputs = turns,
                prewarm = true,
                onTurnComplete = { turnCompleted.complete(Unit) },
            ) { _, _ -> }
        }

        try {
            assertTrue(firstOpened.await(2, TimeUnit.SECONDS))
            firstListener.onFailure(firstSocket, IOException("route lost"), null)
            assertTrue(pendingResumeCreated.await(2, TimeUnit.SECONDS))
            turns.send(
                RealtimeTurnInput(
                    inputPcm = ByteArray(6_400) { 6 },
                    deliveryResult = delivered,
                )
            )
            delay(100)
            assertEquals(2, sentBySocket.size)

            pendingListener.onFailure(pendingSocket, IOException("pending route failed"), null)
            assertTrue(
                "The waiting turn must retry as soon as the handshake gate clears",
                replacementResumeCreated.await(2, TimeUnit.SECONDS),
            )
            replacementListener.onOpen(replacementSocket, mockk(relaxed = true))
            assertTrue(withTimeout(2_000) { delivered.await() }.isSuccess)
            assertEquals(1, sentBySocket[2].count { it.contains("session.resume") })
            assertEquals(1, sentBySocket[2].count { it.contains("input_audio.append") })
            assertEquals(1, sentBySocket[2].count { it.contains("input_audio.commit") })
            replacementListener.onMessage(
                replacementSocket,
                """{"type":"voice.response.done","provider":"xai_realtime","model":"grok-voice-latest","voice":"leo"}""",
            )
            withTimeout(2_000) { turnCompleted.await() }
        } finally {
            turns.close()
            withTimeout(2_000) { sessionJob.await() }
        }
    }

    @Test
    fun persistentRealtimeTurnReportsRejectedSocketSend() = runBlocking {
        val opened = CountDownLatch(1)
        val turns = Channel<RealtimeTurnInput>(Channel.UNLIMITED)
        val delivery = CompletableDeferred<Result<Unit>>()
        lanServer.dispatcher = sessionOnlyDispatcher(
            path = "/voice/realtime-agent/session",
            body = """
                {
                  "success": true,
                  "session_id": "realtime-agent-rejected-send-test",
                  "websocket_path": "/voice/realtime-agent/session-test",
                  "resume_token": "realtime-rejected-send-token",
                  "resume_supported": true,
                  "resume_ttl_ms": 300000,
                  "provider": "xai_realtime",
                  "model": "grok-voice-latest",
                  "voice": "leo",
                  "sample_rate": 24000
                }
            """.trimIndent(),
        )
        val client = RelayVoiceClient(
            context = context,
            okHttpClient = httpClient,
            relayUrlProvider = { relayUrl(lanServer) },
            sessionTokenProvider = { "session-token" },
            webSocketFactory = { request, listener ->
                lateinit var socket: ScriptedWebSocket
                socket = ScriptedWebSocket(
                    request = request,
                    listener = listener,
                    onText = { },
                    sendAccepted = { message -> !message.contains("input_audio.append") },
                )
                listener.onOpen(socket, mockk(relaxed = true))
                opened.countDown()
                socket
            },
        )
        val sessionJob = async(Dispatchers.IO) {
            client.runRealtimeAgent(
                prompt = "",
                inputPcm = ByteArray(0),
                turnInputs = turns,
                prewarm = true,
            ) { _, _ -> }
        }

        try {
            assertTrue(opened.await(2, TimeUnit.SECONDS))
            turns.send(
                RealtimeTurnInput(
                    inputPcm = ByteArray(6_400) { 3 },
                    deliveryResult = delivery,
                )
            )

            val deliveryResult = withTimeout(2_000) { delivery.await() }
            val sessionResult = withTimeout(2_000) { sessionJob.await() }
            assertTrue(deliveryResult.isFailure)
            assertTrue(sessionResult.isFailure)
            assertTrue(
                deliveryResult.exceptionOrNull()?.message?.contains("rejected the recorded turn") == true,
            )
        } finally {
            turns.close()
        }
    }

    @Test
    fun persistentRealtimeTurnReplaysUnacknowledgedFollowUpAfterRouteLoss() = runBlocking {
        val firstOpened = CountDownLatch(1)
        val resumedOpened = CountDownLatch(1)
        val firstTurnCommitted = CountDownLatch(1)
        val sentBySocket = Collections.synchronizedList(mutableListOf<MutableList<String>>())
        val turns = Channel<RealtimeTurnInput>(Channel.UNLIMITED)
        val delivered = CompletableDeferred<Result<Unit>>()
        val turnCompleted = CompletableDeferred<Unit>()
        lateinit var firstSocket: ScriptedWebSocket
        lateinit var firstListener: WebSocketListener
        lateinit var resumedSocket: ScriptedWebSocket
        lateinit var resumedListener: WebSocketListener
        lanServer.dispatcher = sessionOnlyDispatcher(
            path = "/voice/realtime-agent/session",
            body = """
                {
                  "success": true,
                  "session_id": "realtime-agent-follow-up-replay-test",
                  "websocket_path": "/voice/realtime-agent/session-test",
                  "resume_token": "realtime-follow-up-replay-token",
                  "resume_supported": true,
                  "resume_ttl_ms": 300000,
                  "provider": "xai_realtime",
                  "model": "grok-voice-latest",
                  "voice": "leo",
                  "sample_rate": 24000
                }
            """.trimIndent(),
        )
        val client = RelayVoiceClient(
            context = context,
            okHttpClient = httpClient,
            relayUrlProvider = { relayUrl(lanServer) },
            sessionTokenProvider = { "session-token" },
            webSocketFactory = { request, listener ->
                val index = sentBySocket.size
                val messages = Collections.synchronizedList(mutableListOf<String>())
                sentBySocket.add(messages)
                lateinit var socket: ScriptedWebSocket
                socket = ScriptedWebSocket(request, listener) { message ->
                    messages.add(message)
                    if (index == 0 && message.contains("input_audio.commit")) {
                        firstTurnCommitted.countDown()
                    }
                }
                if (index == 0) {
                    firstSocket = socket
                    firstListener = listener
                } else {
                    resumedSocket = socket
                    resumedListener = listener
                }
                listener.onOpen(socket, mockk(relaxed = true))
                if (index == 0) firstOpened.countDown() else resumedOpened.countDown()
                socket
            },
        )
        val sessionJob = async(Dispatchers.IO) {
            client.runRealtimeAgent(
                prompt = "",
                inputPcm = ByteArray(0),
                turnInputs = turns,
                prewarm = true,
                onTurnComplete = { turnCompleted.complete(Unit) },
            ) { _, _ -> }
        }

        try {
            assertTrue(firstOpened.await(2, TimeUnit.SECONDS))
            turns.send(
                RealtimeTurnInput(
                    inputPcm = ByteArray(6_400) { 5 },
                    deliveryResult = delivered,
                )
            )
            assertTrue(firstTurnCommitted.await(2, TimeUnit.SECONDS))
            assertTrue(withTimeout(2_000) { delivered.await() }.isSuccess)

            firstListener.onFailure(firstSocket, IOException("route lost after commit enqueue"), null)

            assertTrue(resumedOpened.await(2, TimeUnit.SECONDS))
            assertTrue(sentBySocket[1].any { it.contains("session.resume") })
            assertTrue(sentBySocket[1].any { it.contains("input_audio.append") })
            assertTrue(sentBySocket[1].any { it.contains("input_audio.commit") })
            resumedListener.onMessage(
                resumedSocket,
                """{"type":"voice.response.done","provider":"xai_realtime","model":"grok-voice-latest","voice":"leo"}""",
            )
            withTimeout(2_000) { turnCompleted.await() }
        } finally {
            turns.close()
            withTimeout(2_000) { sessionJob.await() }
        }
    }

    @Test
    fun persistentRealtimeTurnBuffersWholeUtteranceBeforeResumeReplay() = runBlocking {
        val firstOpened = CountDownLatch(1)
        val resumedOpened = CountDownLatch(1)
        val resumedTurnCommitted = CountDownLatch(1)
        val routeLost = AtomicBoolean(false)
        val sentBySocket = Collections.synchronizedList(mutableListOf<MutableList<String>>())
        val turns = Channel<RealtimeTurnInput>(Channel.UNLIMITED)
        val delivered = CompletableDeferred<Result<Unit>>()
        val turnCompleted = CompletableDeferred<Unit>()
        lateinit var resumedSocket: ScriptedWebSocket
        lateinit var resumedListener: WebSocketListener
        lanServer.dispatcher = sessionOnlyDispatcher(
            path = "/voice/realtime-agent/session",
            body = """
                {
                  "success": true,
                  "session_id": "realtime-agent-atomic-input-replay-test",
                  "websocket_path": "/voice/realtime-agent/session-test",
                  "resume_token": "realtime-atomic-input-token",
                  "resume_supported": true,
                  "resume_ttl_ms": 300000,
                  "provider": "xai_realtime",
                  "model": "grok-voice-latest",
                  "voice": "leo",
                  "sample_rate": 24000
                }
            """.trimIndent(),
        )
        val client = RelayVoiceClient(
            context = context,
            okHttpClient = httpClient,
            relayUrlProvider = { relayUrl(lanServer) },
            sessionTokenProvider = { "session-token" },
            webSocketFactory = { request, listener ->
                val index = sentBySocket.size
                val messages = Collections.synchronizedList(mutableListOf<String>())
                sentBySocket.add(messages)
                lateinit var socket: ScriptedWebSocket
                socket = ScriptedWebSocket(request, listener) { message ->
                    messages.add(message)
                    if (index == 0 &&
                        message.contains("input_audio.append") &&
                        routeLost.compareAndSet(false, true)
                    ) {
                        // Reconnect exactly while the first chunk send is
                        // unwinding. Resume must still see both buffered chunks.
                        listener.onFailure(socket, IOException("route changed during input send"), null)
                    }
                    if (index == 1 && message.contains("input_audio.commit")) {
                        resumedTurnCommitted.countDown()
                    }
                }
                if (index == 1) {
                    resumedSocket = socket
                    resumedListener = listener
                }
                listener.onOpen(socket, mockk(relaxed = true))
                if (index == 0) firstOpened.countDown() else resumedOpened.countDown()
                socket
            },
        )
        val sessionJob = async(Dispatchers.IO) {
            client.runRealtimeAgent(
                prompt = "",
                inputPcm = ByteArray(0),
                turnInputs = turns,
                prewarm = true,
                onTurnComplete = { turnCompleted.complete(Unit) },
            ) { _, _ -> }
        }

        try {
            assertTrue(firstOpened.await(2, TimeUnit.SECONDS))
            turns.send(
                RealtimeTurnInput(
                    inputPcm = ByteArray(12_800) { 5 },
                    deliveryResult = delivered,
                )
            )

            assertTrue(resumedOpened.await(2, TimeUnit.SECONDS))
            assertTrue(resumedTurnCommitted.await(2, TimeUnit.SECONDS))
            assertTrue(withTimeout(2_000) { delivered.await() }.isSuccess)
            assertEquals(2, sentBySocket[1].count { it.contains("input_audio.append") })
            resumedListener.onMessage(
                resumedSocket,
                """{"type":"voice.response.done","provider":"xai_realtime","model":"grok-voice-latest","voice":"leo"}""",
            )
            withTimeout(2_000) { turnCompleted.await() }
        } finally {
            turns.close()
            withTimeout(2_000) { sessionJob.await() }
        }
    }

    @Test
    fun realtimeAgentResumeCursorIgnoresReplayControlEventsDuringRouteChurn() = runBlocking {
        var activeRelayUrl = relayUrl(lanServer)
        val routeSwitch = CompletableDeferred<String>()
        val routeWatcherStarted = CountDownLatch(1)
        val firstReplayInterrupted = CountDownLatch(1)
        val openedUrls = Collections.synchronizedList(mutableListOf<String>())
        val sentMessages = Collections.synchronizedList(mutableListOf<String>())
        lanServer.dispatcher = sessionOnlyDispatcher(
            path = "/voice/realtime-agent/session",
            body = """
                {
                  "success": true,
                  "session_id": "realtime-agent-replay-cursor-test",
                  "websocket_path": "/voice/realtime-agent/session-test",
                  "resume_token": "realtime-replay-cursor-token-1",
                  "resume_supported": true,
                  "resume_ttl_ms": 30000,
                  "provider": "xai_realtime",
                  "model": "grok-voice-latest",
                  "voice": "leo",
                  "sample_rate": 24000
                }
            """.trimIndent(),
        )
        val client = RelayVoiceClient(
            context = context,
            okHttpClient = httpClient,
            relayUrlProvider = { activeRelayUrl },
            relayRouteChangesProvider = {
                flow {
                    emit(activeRelayUrl)
                    routeWatcherStarted.countDown()
                    emit(routeSwitch.await())
                }
            },
            sessionTokenProvider = { "session-token" },
            webSocketFactory = { request, listener ->
                val index = openedUrls.size
                lateinit var socket: ScriptedWebSocket
                socket = ScriptedWebSocket(request, listener) { message ->
                    sentMessages.add(message)
                    if (index == 0 && message.contains("response.create")) {
                        listener.onMessage(
                            socket,
                            """{"type":"voice.response.started","event_id":4}""",
                        )
                        listener.onFailure(socket, IOException("wifi lost"), null)
                    }
                    if (index == 1 && message.contains("session.resume")) {
                        listener.onMessage(
                            socket,
                            """{"type":"voice.session.resumed","event_id":19,"session_id":"realtime-agent-replay-cursor-test"}""",
                        )
                        listener.onMessage(
                            socket,
                            """{"type":"voice.replay.started","event_id":20,"session_id":"realtime-agent-replay-cursor-test"}""",
                        )
                        listener.onMessage(
                            socket,
                            """{"type":"voice.response.started","event_id":5,"replayed":true}""",
                        )
                        listener.onMessage(
                            socket,
                            """{"type":"voice.output_audio.delta","event_id":11,"audio_event_id":4,"byte_count":3200,"sample_rate":24000,"audio_base64":"AA==","replayed":true}""",
                        )
                        listener.onFailure(socket, IOException("handoff interrupted"), null)
                        firstReplayInterrupted.countDown()
                    }
                    if (index == 2 && message.contains("session.resume")) {
                        listener.onMessage(
                            socket,
                            """{"type":"voice.response.done","event_id":17,"provider":"xai_realtime","model":"grok-voice-latest","voice":"leo","replayed":true}""",
                        )
                    }
                }
                openedUrls.add(request.url.toString())
                listener.onOpen(socket, mockk(relaxed = true))
                socket
            },
        )

        val deferred = async(Dispatchers.IO) {
            client.runRealtimeAgent(
                prompt = "Realtime replay cursor test",
                inputPcm = ByteArray(0),
            ) { _, _ -> }
        }
        assertTrue(firstReplayInterrupted.await(2, TimeUnit.SECONDS))
        assertTrue(routeWatcherStarted.await(2, TimeUnit.SECONDS))
        val nextRelayUrl = relayUrl(tailscaleServer)
        routeSwitch.complete(nextRelayUrl)

        val result = withTimeout(5_000) { deferred.await() }
        activeRelayUrl = nextRelayUrl
        val resumeMessages = sentMessages.filter { it.contains("session.resume") }

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertEquals(
            listOf(
                "${requestUrl(lanServer)}/voice/realtime-agent/session-test",
                "${requestUrl(lanServer)}/voice/realtime-agent/session-test",
                "${requestUrl(tailscaleServer)}/voice/realtime-agent/session-test",
            ),
            openedUrls,
        )
        assertTrue(resumeMessages.any { it.contains(""""last_event_id":4""") })
        assertTrue(resumeMessages.any { it.contains(""""last_event_id":11""") })
        assertTrue(resumeMessages.none { it.contains(""""last_event_id":19""") || it.contains(""""last_event_id":20""") })
    }

    @Test
    fun realtimeAgentOnlyReportsPlayedAudioAfterPlaybackDrain() = runTest {
        val sentMessages = Collections.synchronizedList(mutableListOf<String>())
        lanServer.dispatcher = sessionOnlyDispatcher(
            path = "/voice/realtime-agent/session",
            body = """
                {
                  "success": true,
                  "session_id": "realtime-agent-playback-drain-test",
                  "websocket_path": "/voice/realtime-agent/session-test",
                  "resume_token": "realtime-playback-token-1",
                  "resume_supported": true,
                  "resume_ttl_ms": 30000,
                  "provider": "xai_realtime",
                  "model": "grok-voice-latest",
                  "voice": "leo",
                  "sample_rate": 24000
                }
            """.trimIndent(),
        )
        val client = RelayVoiceClient(
            context = context,
            okHttpClient = httpClient,
            relayUrlProvider = { relayUrl(lanServer) },
            sessionTokenProvider = { "session-token" },
            webSocketFactory = { request, listener ->
                lateinit var socket: ScriptedWebSocket
                socket = ScriptedWebSocket(request, listener) { message ->
                    sentMessages.add(message)
                    if (message.contains("session.start")) {
                        listener.onMessage(
                            socket,
                            """{"type":"voice.output_audio.delta","event_id":8,"audio_event_id":4,"byte_count":3200,"sample_rate":24000,"audio_base64":"AA=="}""",
                        )
                        listener.onMessage(
                            socket,
                            """{"type":"voice.playback_drain.requested","event_id":9,"call_id":"call-1","reason":"pre_hermes_ack","timeout_ms":2500}""",
                        )
                    }
                    if (message.contains("playback.drained")) {
                        listener.onMessage(
                            socket,
                            """{"type":"voice.response.done","event_id":10,"provider":"xai_realtime","model":"grok-voice-latest","voice":"leo"}""",
                        )
                    }
                }
                listener.onOpen(socket, mockk(relaxed = true))
                socket
            },
        )
        var lastAudioEventId = 0L

        val result = client.runRealtimeAgent(
            prompt = "",
            inputPcm = ByteArray(0),
        ) { event, control ->
            if (event.isAudioDelta) {
                lastAudioEventId = event.audioEventId ?: 0L
            }
            if (event.type == "voice.playback_drain.requested") {
                control.sendPlaybackDrained(event.toolCallId, lastAudioEventId)
            }
        }

        val audioAck = sentMessages.filter {
            it.contains("client.ack") && it.contains(""""audio_event_id":4""")
        }
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertTrue(audioAck.isNotEmpty())
        assertTrue(audioAck.none { it.contains("played_audio_event_id") })
        assertTrue(
            sentMessages.any {
                it.contains("playback.drained") && it.contains(""""played_audio_event_id":4""")
            },
        )
    }

    private fun relayUrl(server: MockWebServer): String =
        "ws://${server.hostName}:${server.port}"

    private fun requestUrl(server: MockWebServer): String =
        "http://${server.hostName}:${server.port}"

    private fun requestPaths(server: MockWebServer, count: Int): List<String> =
        (0 until count).map {
            val request = server.takeRequest(2, TimeUnit.SECONDS)
            request?.path ?: error("missing request $it")
        }

    private fun voiceRouteDispatcher(): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            return when (request.path) {
                "/voice/output/session" -> jsonResponse(
                    """
                        {
                          "success": true,
                          "session_id": "voice-output-test",
                          "websocket_path": "/voice/output/session-test",
                          "provider": "stub",
                          "model": "local-tone",
                          "voice": "sine",
                          "sample_rate": 24000
                        }
                    """.trimIndent(),
                )
                "/voice/realtime-agent/session" -> jsonResponse(
                    """
                        {
                          "success": true,
                          "session_id": "realtime-agent-test",
                          "websocket_path": "/voice/realtime-agent/session-test",
                          "provider": "xai_realtime",
                          "model": "grok-voice-latest",
                          "voice": "leo",
                          "sample_rate": 24000
                        }
                    """.trimIndent(),
                )
                "/voice/output/session-test",
                "/voice/realtime-agent/session-test" -> websocketDoneResponse()
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun sessionOnlyDispatcher(path: String, body: String): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.path) {
                    path -> jsonResponse(body)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

    private fun websocketDoneResponse(): MockResponse =
        MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"voice.response.done"}""")
                    webSocket.close(1000, "done")
                }
            },
        )

    private class ScriptedWebSocket(
        private val request: Request,
        private val listener: WebSocketListener,
        private val sendAccepted: (String) -> Boolean = { true },
        private val onText: (String) -> Unit,
    ) : WebSocket {
        override fun request(): Request = request

        override fun queueSize(): Long = 0L

        override fun send(text: String): Boolean {
            onText(text)
            return sendAccepted(text)
        }

        override fun send(bytes: ByteString): Boolean = true

        override fun close(code: Int, reason: String?): Boolean {
            listener.onClosed(this, code, reason.orEmpty())
            return true
        }

        override fun cancel() {
            listener.onFailure(this, IOException("cancelled"), null)
        }
    }
}
