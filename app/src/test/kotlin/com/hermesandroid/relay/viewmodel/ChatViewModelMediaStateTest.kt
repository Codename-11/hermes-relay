package com.hermesandroid.relay.viewmodel

import android.net.Uri
import android.os.Looper
import com.hermesandroid.relay.data.AttachmentState
import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.MediaSettingsRepository
import com.hermesandroid.relay.network.relay.RelayHttpClient
import com.hermesandroid.relay.network.upstream.ChatHandler
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.models.MessageItem
import com.hermesandroid.relay.util.MediaCacheWriter
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelMediaStateTest {

    private lateinit var server: MockWebServer
    private lateinit var dashboardServer: MockWebServer
    private lateinit var handler: ChatHandler
    private lateinit var viewModel: ChatViewModel
    private lateinit var cache: MediaCacheWriter

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        dashboardServer = MockWebServer().apply { start() }
        val context = RuntimeEnvironment.getApplication()
        val relay = RelayHttpClient(
            okHttpClient = OkHttpClient(),
            relayUrlProvider = {
                server.url("/").toString()
                    .replaceFirst("http://", "ws://")
                    .trimEnd('/')
            },
            sessionTokenProvider = { "paired-session" },
            pairedTokenSnapshot = { "paired-session" },
        )
        cache = mockk()
        coEvery { cache.cache(any(), any(), any()) } returns
            Uri.parse("content://com.axiomlabs.hermesrelay.fileprovider/hermes-media/photo.jpg")
        handler = ChatHandler()
        viewModel = ChatViewModel().also {
            it.initialize(apiClient = null, chatHandler = handler)
            it.initializeMedia(
                context = context,
                relayHttpClient = relay,
                mediaSettingsRepo = MediaSettingsRepository(context),
                mediaCacheWriter = cache,
            )
            it.cellularNetworkOverride = true
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        dashboardServer.shutdown()
    }

    @Test
    fun persistedUserImageCellularGateAndManualFetchReachActionableStates() {
        handler.loadMessageHistory(
            listOf(
                MessageItem(
                    id = "user-image-1",
                    role = "user",
                    content = JsonPrimitive("@image:/tmp/photo.jpg"),
                )
            )
        )

        val deferred = awaitMessage {
            it.attachments.singleOrNull()?.errorMessage == "Tap to download"
        }
        assertEquals(AttachmentState.FAILED, deferred.attachments.single().state)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/jpeg")
                .setHeader("Content-Disposition", "inline; filename=\"photo.jpg\"")
                .setBody("image-bytes")
        )
        viewModel.cellularNetworkOverride = false
        viewModel.manualFetchAttachment("user-image-1", 0)

        val loaded = awaitMessage {
            it.attachments.singleOrNull()?.state == AttachmentState.LOADED
        }.attachments.single()
        assertEquals("image/jpeg", loaded.contentType)
        assertEquals("photo.jpg", loaded.fileName)
        assertEquals("content://com.axiomlabs.hermesrelay.fileprovider/hermes-media/photo.jpg", loaded.cachedUri)
    }

    @Test
    fun assistantWindowsPathCellularRetryUsesByPathEndpoint() {
        val windowsPath =
            "C:\\Users\\Example\\AppData\\Local\\Temp\\Sovereign Intelligence copy.md"
        handler.loadMessageHistory(
            listOf(
                MessageItem(
                    id = "assistant-file-1",
                    role = "assistant",
                    content = JsonPrimitive("MEDIA:$windowsPath"),
                )
            )
        )

        val deferred = awaitMessage {
            it.attachments.singleOrNull()?.errorMessage == "Tap to download"
        }
        assertEquals(AttachmentState.FAILED, deferred.attachments.single().state)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/markdown")
                .setHeader(
                    "Content-Disposition",
                    "inline; filename=\"Sovereign Intelligence copy.md\"",
                )
                .setBody("# copy")
        )
        viewModel.cellularNetworkOverride = false
        viewModel.manualFetchAttachment("assistant-file-1", 0)

        val loaded = awaitMessage {
            it.attachments.singleOrNull()?.state == AttachmentState.LOADED
        }.attachments.single()
        assertEquals("text/markdown", loaded.contentType)
        assertEquals("Sovereign Intelligence copy.md", loaded.fileName)

        val request = server.takeRequest()
        assertEquals("/media/by-path", request.requestUrl?.encodedPath)
        assertEquals(
            "path=C%3A%5CUsers%5CExample%5CAppData%5CLocal%5CTemp%5C" +
                "Sovereign%20Intelligence%20copy.md",
            request.requestUrl?.encodedQuery,
        )
        assertEquals(windowsPath, request.requestUrl?.queryParameter("path"))
    }

    @Test
    fun assistantBarePathPrefersAuthenticatedUpstreamDashboardDownload() {
        val path = "/tmp/test-voice-message.mp3"
        dashboardServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "audio/mpeg")
                .setHeader("Content-Disposition", "attachment; filename=\"test-voice-message.mp3\"")
                .setBody("voice-bytes"),
        )
        viewModel.cellularNetworkOverride = false
        viewModel.initializeMedia(
            context = RuntimeEnvironment.getApplication(),
            relayHttpClient = RelayHttpClient(
                okHttpClient = OkHttpClient(),
                relayUrlProvider = { server.url("/").toString().replaceFirst("http://", "ws://").trimEnd('/') },
                sessionTokenProvider = { "paired-session" },
                pairedTokenSnapshot = { "paired-session" },
            ),
            mediaSettingsRepo = MediaSettingsRepository(RuntimeEnvironment.getApplication()),
            mediaCacheWriter = cache,
            dashboardMediaClientProvider = {
                DashboardApiClient(baseUrl = dashboardServer.url("/").toString())
            },
        )

        handler.loadMessageHistory(
            listOf(
                MessageItem(
                    id = "assistant-audio-upstream",
                    role = "assistant",
                    content = JsonPrimitive("Voice reply\nMEDIA:$path"),
                ),
            ),
        )

        val loaded = awaitMessage {
            it.attachments.singleOrNull()?.state == AttachmentState.LOADED
        }.attachments.single()
        assertEquals("audio/mpeg", loaded.contentType)
        assertEquals("test-voice-message.mp3", loaded.fileName)
        val request = dashboardServer.takeRequest()
        assertEquals("/api/files/download", request.requestUrl?.encodedPath)
        assertEquals(path, request.requestUrl?.queryParameter("path"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun assistantBarePathWithoutUpstreamOrRelaySettlesAsNeutralHostFile() {
        viewModel.cellularNetworkOverride = false
        viewModel.initializeMedia(
            context = RuntimeEnvironment.getApplication(),
            relayHttpClient = RelayHttpClient(
                okHttpClient = OkHttpClient(),
                relayUrlProvider = { null },
                sessionTokenProvider = { null },
            ),
            mediaSettingsRepo = MediaSettingsRepository(RuntimeEnvironment.getApplication()),
            mediaCacheWriter = cache,
        )

        handler.loadMessageHistory(
            listOf(
                MessageItem(
                    id = "assistant-file-unavailable",
                    role = "assistant",
                    content = JsonPrimitive("MEDIA:/tmp/result.zip"),
                ),
            ),
        )

        val unavailable = awaitMessage {
            it.attachments.singleOrNull()?.errorMessage == ChatViewModel.MEDIA_HOST_ONLY
        }.attachments.single()
        assertEquals(AttachmentState.FAILED, unavailable.state)
        assertEquals("result.zip", unavailable.fileName)
    }

    @Test
    fun assistantBarePathFallsBackToRelayWhenUpstreamRouteIsUnavailable() {
        val path = "/tmp/legacy-host-image.png"
        dashboardServer.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setHeader("Content-Disposition", "inline; filename=\"legacy-host-image.png\"")
                .setBody("image-bytes"),
        )
        viewModel.cellularNetworkOverride = false
        viewModel.initializeMedia(
            context = RuntimeEnvironment.getApplication(),
            relayHttpClient = RelayHttpClient(
                okHttpClient = OkHttpClient(),
                relayUrlProvider = { server.url("/").toString().replaceFirst("http://", "ws://").trimEnd('/') },
                sessionTokenProvider = { "paired-session" },
                pairedTokenSnapshot = { "paired-session" },
            ),
            mediaSettingsRepo = MediaSettingsRepository(RuntimeEnvironment.getApplication()),
            mediaCacheWriter = cache,
            dashboardMediaClientProvider = {
                DashboardApiClient(baseUrl = dashboardServer.url("/").toString())
            },
        )

        handler.loadMessageHistory(
            listOf(
                MessageItem(
                    id = "assistant-image-fallback",
                    role = "assistant",
                    content = JsonPrimitive("MEDIA:$path"),
                ),
            ),
        )

        val loaded = awaitMessage {
            it.attachments.singleOrNull()?.state == AttachmentState.LOADED
        }.attachments.single()
        assertEquals("image/png", loaded.contentType)
        assertEquals("/api/files/download", dashboardServer.takeRequest().requestUrl?.encodedPath)
        assertEquals("/media/by-path", server.takeRequest().requestUrl?.encodedPath)
    }

    @Test
    fun assistantBarePathDoesNotBypassUpstreamSensitiveFileDenial() {
        val path = "/home/user/.ssh/id_ed25519"
        dashboardServer.enqueue(MockResponse().setResponseCode(403).setBody("sensitive file"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody("must-not-be-fetched"),
        )
        viewModel.cellularNetworkOverride = false
        viewModel.initializeMedia(
            context = RuntimeEnvironment.getApplication(),
            relayHttpClient = RelayHttpClient(
                okHttpClient = OkHttpClient(),
                relayUrlProvider = { server.url("/").toString().replaceFirst("http://", "ws://").trimEnd('/') },
                sessionTokenProvider = { "paired-session" },
                pairedTokenSnapshot = { "paired-session" },
            ),
            mediaSettingsRepo = MediaSettingsRepository(RuntimeEnvironment.getApplication()),
            mediaCacheWriter = cache,
            dashboardMediaClientProvider = {
                DashboardApiClient(baseUrl = dashboardServer.url("/").toString())
            },
        )

        handler.loadMessageHistory(
            listOf(
                MessageItem(
                    id = "assistant-sensitive-denied",
                    role = "assistant",
                    content = JsonPrimitive("MEDIA:$path"),
                ),
            ),
        )

        val denied = awaitMessage {
            it.attachments.singleOrNull()?.let { attachment ->
                attachment.state == AttachmentState.FAILED && attachment.errorMessage != null
            } == true
        }.attachments.single()
        assertEquals(AttachmentState.FAILED, denied.state)
        assertEquals(1, dashboardServer.requestCount)
        assertEquals(0, server.requestCount)
    }

    private fun awaitMessage(predicate: (ChatMessage) -> Boolean): ChatMessage {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            handler.messages.value.singleOrNull()?.let { if (predicate(it)) return it }
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for media attachment state: ${handler.messages.value}")
    }
}
