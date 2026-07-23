package com.hermesandroid.relay.network.upstream

import android.content.Context
import com.hermesandroid.relay.network.shared.VoiceSpeechStreamCallbacks
import com.hermesandroid.relay.network.shared.VoiceSpeechStreamStatus
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class StandardHermesVoiceClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun dashboardAudioTimeoutBoundsMatchUpstreamDesktopFloorAndRelayCap() {
        assertEquals(180L, standardHermesDashboardAudioTimeoutSeconds(90L))
        assertEquals(180L, standardHermesDashboardAudioTimeoutSeconds(180L))
        assertEquals(420L, standardHermesDashboardAudioTimeoutSeconds(420L))
        assertEquals(600L, standardHermesDashboardAudioTimeoutSeconds(900L))
    }

    @Test
    fun dashboardAudioClientRaisesOnlyDashboardAudioTimeouts() {
        val baseClient = DashboardApiClient.defaultClient()
        val audioClient = standardHermesDashboardAudioClient(baseClient)
        val audioTimeoutMillis = TimeUnit.SECONDS.toMillis(180L).toInt()

        assertEquals(audioTimeoutMillis, audioClient.callTimeoutMillis)
        assertEquals(audioTimeoutMillis, audioClient.readTimeoutMillis)
        assertEquals(audioTimeoutMillis, audioClient.writeTimeoutMillis)
        assertEquals(baseClient.connectTimeoutMillis, audioClient.connectTimeoutMillis)

        assertEquals(TimeUnit.SECONDS.toMillis(45L).toInt(), baseClient.readTimeoutMillis)
        assertEquals(TimeUnit.SECONDS.toMillis(30L).toInt(), baseClient.writeTimeoutMillis)
    }

    @Test
    fun speechStreamUsesFreshTicketBuffersUntilOpenAndCarriesOddPcmByte() = runTest {
        server.enqueue(ticketResponse())
        lateinit var listener: WebSocketListener
        lateinit var websocketRequest: Request
        lateinit var socket: RecordingWebSocket
        val pcm = mutableListOf<ByteArray>()
        val formats = mutableListOf<Pair<Int, Int>>()
        val client = testClient { request, capturedListener ->
            websocketRequest = request
            listener = capturedListener
            RecordingWebSocket(request).also { socket = it }
        }

        val stream = client.openSpeechStream(
            VoiceSpeechStreamCallbacks(
                onStart = { rate, channels -> formats += rate to channels },
                onPcm = { bytes, _ -> pcm += bytes },
            ),
        ).getOrThrow()!!
        stream.append("Hello ")
        stream.append("world.")
        assertTrue(socket.sentText.isEmpty())

        listener.onOpen(socket, switchingProtocols(websocketRequest))
        assertEquals(listOf("{\"text\":\"Hello \"}", "{\"text\":\"world.\"}"), socket.sentText)
        listener.onMessage(socket, "{\"type\":\"start\",\"sample_rate\":24000,\"channels\":1}")
        listener.onMessage(socket, byteArrayOf(1, 2, 3).toByteString())
        listener.onMessage(socket, byteArrayOf(4, 5, 6).toByteString())
        stream.finish()
        listener.onMessage(socket, "{\"type\":\"end\"}")

        val outcome = stream.awaitOutcome()
        assertEquals(VoiceSpeechStreamStatus.Completed, outcome.status)
        assertTrue(outcome.audioStarted)
        assertEquals(listOf(24_000 to 1), formats)
        assertEquals(listOf(1, 2), pcm[0].map { it.toInt() })
        assertEquals(listOf(3, 4, 5, 6), pcm[1].map { it.toInt() })
        assertTrue(socket.sentText.last().contains("\"done\":true"))
        val ticketRequest = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/api/auth/ws-ticket", ticketRequest.path)
        assertEquals("/api/audio/speak-stream", websocketRequest.url.encodedPath)
        assertEquals("ticket-1", websocketRequest.url.queryParameter("ticket"))
    }

    @Test
    fun speechStreamFallbackBeforeAudioRequestsLegacyPlayback() = runTest {
        server.enqueue(ticketResponse())
        lateinit var listener: WebSocketListener
        lateinit var socket: RecordingWebSocket
        val client = testClient { request, capturedListener ->
            listener = capturedListener
            RecordingWebSocket(request).also { socket = it }
        }

        val stream = client.openSpeechStream(
            VoiceSpeechStreamCallbacks(onPcm = { _, _ -> }),
        ).getOrThrow()!!
        listener.onOpen(socket, switchingProtocols(socket.request()))
        listener.onMessage(socket, "{\"type\":\"fallback\"}")

        val outcome = stream.awaitOutcome()
        assertEquals(VoiceSpeechStreamStatus.Fallback, outcome.status)
        assertFalse(outcome.audioStarted)
    }

    @Test
    fun speechStreamFailureAfterAudioNeverRequestsReplay() = runTest {
        server.enqueue(ticketResponse())
        lateinit var listener: WebSocketListener
        lateinit var socket: RecordingWebSocket
        val client = testClient { request, capturedListener ->
            listener = capturedListener
            RecordingWebSocket(request).also { socket = it }
        }

        val stream = client.openSpeechStream(
            VoiceSpeechStreamCallbacks(onPcm = { _, _ -> }),
        ).getOrThrow()!!
        listener.onOpen(socket, switchingProtocols(socket.request()))
        listener.onMessage(socket, byteArrayOf(1, 2).toByteString())
        listener.onFailure(socket, IOException("route lost"), null)

        val outcome = stream.awaitOutcome()
        assertEquals(VoiceSpeechStreamStatus.Failed, outcome.status)
        assertTrue(outcome.audioStarted)
    }

    private fun testClient(
        socketFactory: (Request, WebSocketListener) -> WebSocket,
    ): StandardHermesVoiceClient = StandardHermesVoiceClient(
        context = mockk<Context>(relaxed = true),
        okHttpClient = DashboardApiClient.defaultClient(),
        dashboardUrlProvider = { server.url("/").toString() },
        webSocketFactory = socketFactory,
    )

    private fun ticketResponse(): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("{\"ticket\":\"ticket-1\",\"ttl_seconds\":30}")

    private fun switchingProtocols(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(101)
        .message("Switching Protocols")
        .build()

    private class RecordingWebSocket(private val ownRequest: Request) : WebSocket {
        val sentText = mutableListOf<String>()
        var cancelled = false

        override fun request(): Request = ownRequest
        override fun queueSize(): Long = 0L
        override fun send(text: String): Boolean = sentText.add(text)
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {
            cancelled = true
        }
    }
}
