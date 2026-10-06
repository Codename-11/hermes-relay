package com.hermesandroid.relay.network.upstream

import android.content.Context
import android.util.Log
import com.hermesandroid.relay.network.shared.GptLiveCallbacks
import com.hermesandroid.relay.network.shared.GptLiveHistoryMessage
import com.hermesandroid.relay.network.shared.GptLiveSession
import com.hermesandroid.relay.network.shared.GptLiveStatus
import com.hermesandroid.relay.network.shared.GptLiveTranscriptFragment
import com.hermesandroid.relay.network.shared.GptLiveVoiceClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android transport for Hermes' upstream GPT-Live voice mode.
 *
 * The OpenAI credential never leaves the Hermes host. Android creates an
 * audio-only WebRTC offer, sends only that SDP + bounded chat history to
 * `/api/audio/voice-live/session`, applies the returned SDP answer, then uses
 * the `oai-events` data channel for delegation / transcript / commentary.
 */
class StandardGptLiveVoiceClient(
    private val context: Context,
    private val dashboardHttpClientProvider: (String) -> OkHttpClient,
    private val dashboardUrlProvider: () -> String?,
    private val profileProvider: () -> String? = { null },
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    },
) : GptLiveVoiceClient {

    override suspend fun status(): Result<GptLiveStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val base = dashboardBaseUrl()
                ?: throw IOException("Hermes dashboard URL not configured")
            val url = standardHermesAudioUrl(
                base,
                "/api/audio/voice-live/status",
                activeProfile(),
            ) ?: throw IOException("Hermes dashboard URL is not a valid address: $base")
            val request = Request.Builder().url(url).get().build()
            val root = executeJson(request, "GPT-Live status", base)
            GptLiveStatus(
                mode = root.string("mode") ?: "chained",
                available = root.boolean("available") ?: false,
                reason = root.string("reason"),
                model = root.string("model") ?: "gpt-live-1",
                voice = root.string("voice") ?: "marin",
            )
        }
    }

    override suspend fun startSession(
        history: List<GptLiveHistoryMessage>,
        callbacks: GptLiveCallbacks,
    ): Result<GptLiveSession> = runCatching {
        val base = dashboardBaseUrl()
            ?: throw IOException("Hermes dashboard URL not configured")
        val transport = AndroidGptLiveSession(
            context = context,
            callbacks = callbacks,
            createSession = { sdp -> createSession(base, history, sdp) },
            json = json,
        )
        transport.start()
        transport
    }

    private suspend fun createSession(
        baseUrl: String,
        history: List<GptLiveHistoryMessage>,
        sdp: String,
    ): LiveSessionAnswer = withContext(Dispatchers.IO) {
        val url = standardHermesAudioUrl(
            baseUrl,
            "/api/audio/voice-live/session",
            activeProfile(),
        ) ?: throw IOException("Hermes dashboard URL is not a valid address: $baseUrl")

        val historyJson = buildJsonArray {
            history.takeLast(24).forEach { message ->
                val text = message.text.replace(Regex("\\s+"), " ").trim().take(1_200)
                if (text.isBlank()) return@forEach
                add(buildJsonObject {
                    put("type", "message")
                    put("role", message.role)
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put(
                                "type",
                                if (message.role == "assistant") "output_text" else "input_text",
                            )
                            put("text", text)
                        })
                    })
                })
            }
        }
        val payload = buildJsonObject {
            put("sdp", sdp)
            put("history", historyJson)
        }
        val request = Request.Builder()
            .url(url)
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json")
            .build()
        val root = executeJson(request, "GPT-Live session creation", baseUrl)
        val sessionId = (root["session"] as? JsonObject)?.string("id")
        val transport = root["transport"] as? JsonObject
            ?: throw IOException("GPT-Live session response missing transport")
        val answer = transport.string("sdp")
            ?: throw IOException("GPT-Live session response missing SDP answer")
        LiveSessionAnswer(sessionId, answer)
    }

    private fun executeJson(request: Request, operation: String, baseUrl: String): JsonObject {
        val client = standardHermesDashboardAudioClient(dashboardHttpClientProvider(baseUrl), 180L)
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IOException(
                    when (response.code) {
                        401, 403 -> "$operation needs dashboard sign-in"
                        404 -> "$operation is unavailable on this Hermes build"
                        502, 503 -> "$operation is unavailable: ${body.take(500)}"
                        else -> "$operation failed (HTTP ${response.code}): ${body.take(500)}"
                    },
                )
            }
            val root = json.decodeFromString<JsonObject>(body)
            if (root.boolean("ok") == false) {
                throw IOException(root.string("detail") ?: root.string("error") ?: "$operation failed")
            }
            return root
        }
    }

    private fun dashboardBaseUrl(): String? =
        dashboardUrlProvider()?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank)

    private fun activeProfile(): String? =
        profileProvider()?.trim()?.takeIf(String::isNotBlank)

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}

private data class LiveSessionAnswer(val sessionId: String?, val sdp: String)

private class AndroidGptLiveSession(
    context: Context,
    private val callbacks: GptLiveCallbacks,
    private val createSession: suspend (String) -> LiveSessionAnswer,
    private val json: Json,
) : GptLiveSession {
    private val appContext = context.applicationContext
    private val finished = AtomicBoolean(false)
    private val eventCounter = AtomicInteger(0)
    private val iceGatheringComplete = CompletableDeferred<Unit>()
    private val transcript = mutableListOf<GptLiveTranscriptFragment>()
    private var audioDeviceModule = JavaAudioDeviceModule.builder(appContext).createAudioDeviceModule()
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var microphoneTrack: AudioTrack? = null
    private var events: DataChannel? = null
    private var started = false

    @Volatile
    override var sessionId: String? = null
        private set

    override val connected: Boolean
        get() = started && events?.state() == DataChannel.State.OPEN

    suspend fun start() {
        ensureWebRtcInitialized(appContext)
        val localFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
        factory = localFactory

        val localPeer = localFactory.createPeerConnection(
            PeerConnection.RTCConfiguration(emptyList()),
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                    if (state == PeerConnection.IceGatheringState.COMPLETE) {
                        iceGatheringComplete.complete(Unit)
                    }
                }
                override fun onIceCandidate(candidate: IceCandidate) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
                override fun onAddStream(stream: MediaStream) = Unit
                override fun onRemoveStream(stream: MediaStream) = Unit
                override fun onDataChannel(dataChannel: DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                    (receiver.track() as? AudioTrack)?.setEnabled(true)
                }
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    if (newState == PeerConnection.PeerConnectionState.FAILED ||
                        newState == PeerConnection.PeerConnectionState.DISCONNECTED
                    ) {
                        finish("connection_lost", null)
                    }
                }
            },
        ) ?: throw IOException("Could not create GPT-Live WebRTC peer connection")
        peer = localPeer

        audioSource = localFactory.createAudioSource(MediaConstraints())
        microphoneTrack = localFactory.createAudioTrack("gpt-live-mic", audioSource).also { track ->
            track.setEnabled(true)
            localPeer.addTrack(track, listOf("gpt-live-audio"))
        }

        val channel = localPeer.createDataChannel("oai-events", DataChannel.Init())
            ?: throw IOException("Could not create GPT-Live data channel")
        events = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.CLOSED && !finished.get()) {
                    finish("connection_lost", null)
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                handleEvent(String(bytes, StandardCharsets.UTF_8))
            }
        })

        val offer = createOffer(localPeer)
        setLocalDescription(localPeer, offer)
        withTimeoutOrNull(10_000L) { iceGatheringComplete.await() }
        val sdp = localPeer.localDescription?.description
            ?: throw IOException("GPT-Live WebRTC offer has no SDP")
        val answer = createSession(sdp)
        sessionId = answer.sessionId
        setRemoteDescription(
            localPeer,
            SessionDescription(SessionDescription.Type.ANSWER, answer.sdp),
        )
    }

    override fun speak(delegationId: String?, content: String) {
        chunkCommentary(content).forEach { chunk ->
            send(buildJsonObject {
                put("type", "session.commentary.append")
                put("event_id", nextEventId("say"))
                delegationId?.let { put("delegation_id", it) }
                put("content", chunk)
            })
        }
    }

    override fun think(delegationId: String?, content: String) {
        val clean = content.replace(Regex("\\s+"), " ").trim().take(APPEND_CHAR_LIMIT)
        if (clean.isBlank()) return
        send(buildJsonObject {
            put("type", "session.thinking.append")
            put("event_id", nextEventId("think"))
            delegationId?.let { put("delegation_id", it) }
            put("content", clean)
        })
    }

    override fun instruct(content: String) {
        val clean = content.trim().take(APPEND_CHAR_LIMIT)
        if (clean.isBlank()) return
        send(buildJsonObject {
            put("type", "session.instructions.append")
            put("event_id", nextEventId("instr"))
            put("content", clean)
        })
    }

    override fun setMuted(muted: Boolean) {
        microphoneTrack?.setEnabled(!muted)
        send(buildJsonObject {
            put("type", if (muted) "session.input_audio.mute" else "session.input_audio.unmute")
            put("event_id", nextEventId(if (muted) "mute" else "unmute"))
        })
    }

    override fun close() {
        if (finished.get()) return
        if (!send(buildJsonObject { put("type", "session.close") })) {
            finish("close_requested", null)
        }
    }

    private fun send(event: JsonObject): Boolean {
        val channel = events ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val bytes = json.encodeToString(JsonObject.serializer(), event).toByteArray(StandardCharsets.UTF_8)
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    private fun handleEvent(raw: String) {
        val event = runCatching { json.decodeFromString<JsonObject>(raw) }.getOrNull() ?: return
        when (event.string("type")) {
            "session.started" -> {
                started = true
                sessionId = (event["session"] as? JsonObject)?.string("id") ?: sessionId
            }
            "session.input_transcript.delta", "session.output_transcript.delta" -> {
                val type = event.string("type") ?: return
                val fragment = GptLiveTranscriptFragment(
                    speaker = if (type == "session.input_transcript.delta") {
                        GptLiveTranscriptFragment.Speaker.User
                    } else {
                        GptLiveTranscriptFragment.Speaker.Assistant
                    },
                    text = event.string("delta").orEmpty(),
                    startMs = event.long("start_ms") ?: 0L,
                    endMs = event.long("end_ms") ?: 0L,
                )
                transcript += fragment
                if (transcript.size > 2_000) transcript.subList(0, transcript.size - 1_500).clear()
                callbacks.onTranscript(fragment)
            }
            "session.delegation.created" -> {
                val id = (event["delegation"] as? JsonObject)?.string("id") ?: return
                callbacks.onDelegation(id, contextWindow())
            }
            "error" -> {
                val error = event["error"] as? JsonObject
                if (error?.string("code") == "context_injection_incomplete") return
                callbacks.onError(error?.string("message") ?: "GPT-Live error", false)
            }
            "session.closed" -> finish(
                event.string("reason") ?: "closed",
                (event["usage"] as? JsonObject)?.double("seconds"),
            )
        }
    }

    private fun contextWindow(): List<GptLiveTranscriptFragment> {
        val last = transcript.lastOrNull() ?: return emptyList()
        val floor = last.endMs - CONTEXT_WINDOW_MS
        return transcript.filter { it.endMs >= floor }.takeLast(CONTEXT_MAX_FRAGMENTS)
    }

    private fun finish(reason: String, usageSeconds: Double?) {
        if (!finished.compareAndSet(false, true)) return
        started = false
        runCatching { events?.unregisterObserver() }
        runCatching { events?.close() }
        events = null
        runCatching { microphoneTrack?.setEnabled(false) }
        runCatching { microphoneTrack?.dispose() }
        microphoneTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        runCatching { peer?.close() }
        runCatching { peer?.dispose() }
        peer = null
        runCatching { factory?.dispose() }
        factory = null
        runCatching { audioDeviceModule.release() }
        callbacks.onClosed(reason, usageSeconds)
    }

    private fun nextEventId(prefix: String) = "${prefix}_${eventCounter.incrementAndGet()}"

    private companion object {
        const val TAG = "AndroidGptLive"
        const val APPEND_CHAR_LIMIT = 1_400
        const val CONTEXT_WINDOW_MS = 5 * 60_000L
        const val CONTEXT_MAX_FRAGMENTS = 80

        val webRtcInitialized = AtomicBoolean(false)

        fun ensureWebRtcInitialized(context: Context) {
            if (webRtcInitialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions(),
                )
                Log.i(TAG, "WebRTC initialized for GPT-Live")
            }
        }
    }
}

private suspend fun createOffer(peer: PeerConnection): SessionDescription {
    val deferred = CompletableDeferred<SessionDescription>()
    peer.createOffer(object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {
            deferred.complete(description)
        }
        override fun onCreateFailure(error: String) {
            deferred.completeExceptionally(IOException(error))
        }
        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String) = Unit
    }, MediaConstraints())
    return deferred.await()
}

private suspend fun setLocalDescription(peer: PeerConnection, description: SessionDescription) {
    val deferred = CompletableDeferred<Unit>()
    peer.setLocalDescription(object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetSuccess() {
            deferred.complete(Unit)
        }
        override fun onSetFailure(error: String) {
            deferred.completeExceptionally(IOException(error))
        }
    }, description)
    deferred.await()
}

private suspend fun setRemoteDescription(peer: PeerConnection, description: SessionDescription) {
    val deferred = CompletableDeferred<Unit>()
    peer.setRemoteDescription(object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetSuccess() {
            deferred.complete(Unit)
        }
        override fun onSetFailure(error: String) {
            deferred.completeExceptionally(IOException(error))
        }
    }, description)
    deferred.await()
}

private fun chunkCommentary(text: String): List<String> {
    val clean = text.replace(Regex("\\s+"), " ").trim()
    if (clean.isBlank()) return emptyList()
    if (clean.length <= 1_400) return listOf(clean)
    val out = mutableListOf<String>()
    var current = ""
    clean.split(Regex("(?<=[.!?])\\s+")).forEach { sentence ->
        if (sentence.length > 1_400) {
            if (current.isNotBlank()) out += current.also { current = "" }
            sentence.chunked(1_400).forEach(out::add)
        } else {
            val candidate = if (current.isBlank()) sentence else "$current $sentence"
            if (candidate.length > 1_400) {
                out += current
                current = sentence
            } else {
                current = candidate
            }
        }
    }
    if (current.isNotBlank()) out += current
    return out
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

private fun JsonObject.boolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull

private fun JsonObject.double(name: String): Double? =
    (this[name] as? JsonPrimitive)?.doubleOrNull
