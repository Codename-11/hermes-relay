package com.axiomlabs.hermesquest.voice

import android.content.Context
import com.axiomlabs.hermesrelay.core.pairing.QuestSessionStore
import com.axiomlabs.hermesrelay.core.voice.RealtimeSessionResponse
import com.axiomlabs.hermesrelay.core.voice.RealtimeVoiceEvent
import com.axiomlabs.hermesrelay.core.voice.RealtimeVoiceListener
import com.axiomlabs.hermesrelay.core.voice.RealtimeVoiceSocket
import com.axiomlabs.hermesrelay.core.voice.RelayVoiceClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Base64
import kotlin.math.sqrt

enum class QuestVoicePhase {
    Idle,
    Connecting,
    Connected,
    Listening,
    Sending,
    Speaking,
    Error,
}

data class QuestVoiceLogEntry(
    val label: String,
    val detail: String = "",
)

data class QuestVoiceUiState(
    val phase: QuestVoicePhase = QuestVoicePhase.Idle,
    val provider: String? = null,
    val model: String? = null,
    val voice: String? = null,
    val sessionId: String? = null,
    val sampleRate: Int = 24_000,
    val inputLevel: Float = 0f,
    val outputLevel: Float = 0f,
    val audioChunks: Int = 0,
    val audioBytes: Int = 0,
    val eventLogPath: String? = null,
    val error: String? = null,
    val events: List<QuestVoiceLogEntry> = emptyList(),
)

class QuestRealtimeVoiceController(
    context: Context,
    private val store: QuestSessionStore,
    private val scope: CoroutineScope,
) {
    private val client = RelayVoiceClient(
        context = context.applicationContext,
        relayUrlProvider = { store.relayUrl },
        sessionTokenProvider = { store.sessionToken },
    )
    private val recorder = QuestRealtimePcmRecorder()
    private val player = QuestRealtimePcmPlayer()
    private var socket: RealtimeVoiceSocket? = null
    private var session: RealtimeSessionResponse? = null

    private val _uiState = MutableStateFlow(QuestVoiceUiState())
    val uiState: StateFlow<QuestVoiceUiState> = _uiState.asStateFlow()

    fun connect() {
        if (socket != null) return
        scope.launch {
            setPhase(QuestVoicePhase.Connecting)
            appendLog("config", "checking realtime voice")
            val config = client.getRealtimeConfig().getOrElse {
                fail(it.message ?: "Realtime config failed")
                return@launch
            }
            if (!config.enabled) {
                fail("Realtime voice disabled on relay")
                return@launch
            }

            val created = client.createRealtimeSession().getOrElse {
                fail(it.message ?: "Realtime session failed")
                return@launch
            }
            session = created
            _uiState.update {
                it.copy(
                    phase = QuestVoicePhase.Connecting,
                    provider = created.provider,
                    model = created.model,
                    voice = created.voice,
                    sessionId = created.sessionId,
                    sampleRate = created.sampleRate,
                    eventLogPath = created.eventLogPath,
                    error = null,
                )
            }
            appendLog("session", created.provider)

            val opened = client.openRealtimeSession(created, listener()).getOrElse {
                fail(it.message ?: "Realtime websocket failed")
                return@launch
            }
            socket = opened
        }
    }

    fun captureAndRequest(prompt: String) {
        val active = socket
        if (active == null) {
            fail("Connect realtime session first")
            return
        }
        scope.launch {
            setPhase(QuestVoicePhase.Listening)
            appendLog("mic", "capturing 1s PCM")
            val pcm = runCatching { recorder.capture() }.getOrElse {
                fail(it.message ?: "Mic capture failed")
                return@launch
            }
            val inputLevel = pcmRmsLevel(pcm)
            _uiState.update {
                it.copy(
                    phase = QuestVoicePhase.Sending,
                    inputLevel = inputLevel,
                    outputLevel = 0f,
                    error = null,
                )
            }
            active.sendInputAudio(pcm, QuestRealtimePcmRecorder.SAMPLE_RATE)
            active.requestResponse(prompt)
            appendLog("sent", "${pcm.size} bytes")
        }
    }

    fun disconnect() {
        socket?.close()
        socket = null
        session = null
        player.stop()
        _uiState.update {
            it.copy(
                phase = QuestVoicePhase.Idle,
                inputLevel = 0f,
                outputLevel = 0f,
                sessionId = null,
            )
        }
        appendLog("closed")
    }

    fun dispose() {
        disconnect()
    }

    private fun listener(): RealtimeVoiceListener = object : RealtimeVoiceListener {
        override fun onEvent(event: RealtimeVoiceEvent) {
            handleEvent(event)
        }

        override fun onFailure(error: Throwable) {
            fail(error.message ?: "Realtime websocket failed")
        }

        override fun onClosed(code: Int, reason: String) {
            socket = null
            player.stop()
            _uiState.update {
                it.copy(
                    phase = if (code == 1000) QuestVoicePhase.Idle else QuestVoicePhase.Error,
                    outputLevel = 0f,
                    sessionId = null,
                    error = if (code == 1000) null else "Closed: $code $reason",
                )
            }
            appendLog("closed", "$code $reason")
        }
    }

    private fun handleEvent(event: RealtimeVoiceEvent) {
        when (event.type) {
            "voice.session.ready" -> {
                _uiState.update {
                    it.copy(
                        phase = QuestVoicePhase.Connected,
                        provider = event.provider ?: it.provider,
                        model = event.model ?: it.model,
                        voice = event.voice ?: it.voice,
                        sessionId = event.sessionId ?: it.sessionId,
                        sampleRate = event.sampleRate ?: it.sampleRate,
                        eventLogPath = event.eventLogPath ?: it.eventLogPath,
                        error = null,
                    )
                }
                appendLog("ready", event.provider.orEmpty())
            }
            "voice.input_audio.received" -> {
                _uiState.update { it.copy(phase = QuestVoicePhase.Sending, error = null) }
                appendLog("relay", "${event.byteCount ?: 0} bytes")
            }
            "voice.response.started" -> {
                _uiState.update { it.copy(phase = QuestVoicePhase.Speaking, outputLevel = 0.08f) }
                appendLog("response", event.provider.orEmpty())
            }
            "voice.audio.delta" -> {
                val pcm = event.audioBase64?.let { Base64.getDecoder().decode(it) }
                if (pcm != null) {
                    val sampleRate = event.sampleRate ?: _uiState.value.sampleRate
                    player.write(pcm, sampleRate)
                }
                _uiState.update {
                    it.copy(
                        phase = QuestVoicePhase.Speaking,
                        outputLevel = event.rmsLevel ?: event.peakLevel ?: it.outputLevel,
                        audioChunks = it.audioChunks + 1,
                        audioBytes = it.audioBytes + (event.byteCount ?: 0),
                    )
                }
            }
            "voice.audio.done", "voice.response.done" -> {
                _uiState.update {
                    it.copy(
                        phase = QuestVoicePhase.Connected,
                        outputLevel = 0f,
                        eventLogPath = event.eventLogPath ?: it.eventLogPath,
                    )
                }
                appendLog(event.type.removePrefix("voice."), event.eventLogPath.orEmpty())
            }
            "voice.error", "parse.error" -> {
                fail(event.message ?: "Realtime voice error")
            }
            else -> appendLog(event.type.removePrefix("voice."))
        }
    }

    private fun setPhase(phase: QuestVoicePhase) {
        _uiState.update { it.copy(phase = phase, error = null) }
    }

    private fun fail(message: String) {
        socket = null
        player.stop()
        _uiState.update { it.copy(phase = QuestVoicePhase.Error, error = message, outputLevel = 0f) }
        appendLog("error", message)
    }

    private fun appendLog(label: String, detail: String = "") {
        _uiState.update {
            it.copy(events = (listOf(QuestVoiceLogEntry(label, detail)) + it.events).take(MAX_EVENTS))
        }
    }

    private fun pcmRmsLevel(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < pcm.size) {
            val low = pcm[index].toInt() and 0xff
            val high = pcm[index + 1].toInt()
            val sample = (high shl 8) or low
            sum += sample.toDouble() * sample.toDouble()
            count += 1
            index += 2
        }
        if (count == 0) return 0f
        return (sqrt(sum / count) / 32767.0).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val MAX_EVENTS = 8
    }
}
