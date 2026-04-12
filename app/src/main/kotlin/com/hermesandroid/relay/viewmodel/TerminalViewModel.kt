package com.hermesandroid.relay.viewmodel

import android.app.Application
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.ConnectionState
import com.hermesandroid.relay.network.models.Envelope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Terminal channel ViewModel.
 *
 * Owns PTY session state and bridges envelopes between the WebSocket relay
 * (via [ChannelMultiplexer]) and the [com.hermesandroid.relay.ui.components.TerminalWebView]
 * that hosts xterm.js.
 *
 * Lifecycle:
 *   1. [initialize] wires the multiplexer + connection state flow.
 *   2. The WebView boots, fits the container, and calls [onTerminalReady] with
 *      the initial cols/rows. The ViewModel sends `terminal.attach`.
 *   3. Relay responds with `terminal.attached` → [state] flips to connected.
 *   4. Output arrives as `terminal.output` envelopes → [outputFlow] emits
 *      base64-encoded bytes that the WebView pipes into `window.writeTerminal`.
 *   5. Typing in xterm.js → WebView JS bridge → [sendInput] → `terminal.input`.
 */
class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TerminalViewModel"
    }

    /** Keys the extra-keys toolbar can emit. */
    enum class SpecialKey {
        ESC, TAB, ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
        HOME, END, PAGE_UP, PAGE_DOWN
    }

    data class TerminalState(
        val attached: Boolean = false,
        val attaching: Boolean = false,
        val sessionName: String? = null,
        val pid: Int? = null,
        val shell: String? = null,
        val cols: Int = 80,
        val rows: Int = 24,
        val tmuxAvailable: Boolean = false,
        val ctrlActive: Boolean = false,
        val altActive: Boolean = false,
        val error: String? = null
    )

    private val _state = MutableStateFlow(TerminalState())
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    /**
     * Base64-encoded output chunks destined for the WebView. We use SharedFlow
     * (not StateFlow) because each chunk must be delivered exactly once — a
     * StateFlow would conflate consecutive chunks and drop terminal output.
     */
    private val _outputFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 256
    )
    val outputFlow: SharedFlow<String> = _outputFlow.asSharedFlow()

    private var multiplexer: ChannelMultiplexer? = null
    private var connectionState: StateFlow<ConnectionState>? = null

    /** Pending attach request — if the WebView signals ready before the relay connects, we hold and retry. */
    private var pendingReady: Pair<Int, Int>? = null

    fun initialize(
        multiplexer: ChannelMultiplexer,
        connectionState: StateFlow<ConnectionState>
    ) {
        if (this.multiplexer != null) return
        this.multiplexer = multiplexer
        this.connectionState = connectionState

        multiplexer.registerHandler("terminal") { envelope ->
            handleEnvelope(envelope)
        }

        // If the connection drops, reset attached state so the UI shows disconnected.
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state != ConnectionState.Connected && _state.value.attached) {
                    _state.update {
                        it.copy(
                            attached = false,
                            attaching = false,
                            sessionName = null,
                            pid = null
                        )
                    }
                }
                // If we came back online and have a pending attach, fire it now.
                if (state == ConnectionState.Connected) {
                    pendingReady?.let { (c, r) ->
                        sendAttach(c, r)
                        pendingReady = null
                    }
                }
            }
        }
    }

    // ── Called by TerminalWebView's JS bridge ────────────────────────────

    /** WebView booted and knows its container size in cells. Time to attach. */
    fun onTerminalReady(cols: Int, rows: Int) {
        _state.update { it.copy(cols = cols, rows = rows) }
        if (connectionState?.value == ConnectionState.Connected) {
            sendAttach(cols, rows)
        } else {
            pendingReady = cols to rows
        }
    }

    /** Raw input from xterm.js (soft keyboard, hardware keys, paste). */
    fun sendInput(data: String) {
        if (data.isEmpty()) return
        Log.d(TAG, "sendInput: ${data.length} bytes attached=${_state.value.attached} session=${_state.value.sessionName}")
        var payload = data

        // Apply sticky CTRL: map a single a-z/A-Z keypress to its control byte.
        if (_state.value.ctrlActive && payload.length == 1) {
            val c = payload[0]
            val translated = when (c) {
                in 'a'..'z' -> (c.code - 'a'.code + 1).toChar().toString()
                in 'A'..'Z' -> (c.code - 'A'.code + 1).toChar().toString()
                // Common punctuation Ctrl sequences
                ' ' -> "\u0000"      // Ctrl+Space → NUL
                '[' -> "\u001b"      // Ctrl+[ → ESC
                '\\' -> "\u001c"     // Ctrl+\
                ']' -> "\u001d"      // Ctrl+]
                else -> payload
            }
            payload = translated
            _state.update { it.copy(ctrlActive = false) }
        }

        // Apply sticky ALT: prefix ESC (standard meta convention).
        if (_state.value.altActive && payload.isNotEmpty()) {
            payload = "\u001b$payload"
            _state.update { it.copy(altActive = false) }
        }

        sendEnvelope("terminal.input", buildJsonObject {
            _state.value.sessionName?.let { put("session_name", it) }
            put("data", payload)
        })
    }

    /** Extra-keys toolbar key tap. */
    fun sendKey(key: SpecialKey) {
        val sequence = when (key) {
            SpecialKey.ESC -> "\u001b"
            SpecialKey.TAB -> "\t"
            SpecialKey.ARROW_UP -> "\u001b[A"
            SpecialKey.ARROW_DOWN -> "\u001b[B"
            SpecialKey.ARROW_RIGHT -> "\u001b[C"
            SpecialKey.ARROW_LEFT -> "\u001b[D"
            SpecialKey.HOME -> "\u001b[H"
            SpecialKey.END -> "\u001b[F"
            SpecialKey.PAGE_UP -> "\u001b[5~"
            SpecialKey.PAGE_DOWN -> "\u001b[6~"
        }
        // Special keys bypass sticky CTRL/ALT translation and are sent raw.
        sendEnvelope("terminal.input", buildJsonObject {
            _state.value.sessionName?.let { put("session_name", it) }
            put("data", sequence)
        })
    }

    fun toggleCtrl() {
        _state.update { it.copy(ctrlActive = !it.ctrlActive) }
    }

    fun toggleAlt() {
        _state.update { it.copy(altActive = !it.altActive) }
    }

    /** Container resize from the WebView — forwards to the server. */
    fun resize(cols: Int, rows: Int) {
        val current = _state.value
        if (current.cols == cols && current.rows == rows) return
        _state.update { it.copy(cols = cols, rows = rows) }
        if (current.attached) {
            sendEnvelope("terminal.resize", buildJsonObject {
                current.sessionName?.let { put("session_name", it) }
                put("cols", cols)
                put("rows", rows)
            })
        }
    }

    fun detach() {
        val session = _state.value.sessionName ?: return
        sendEnvelope("terminal.detach", buildJsonObject {
            put("session_name", session)
        })
        _state.update { it.copy(attached = false, attaching = false, sessionName = null, pid = null) }
    }

    fun reattach() {
        pendingReady = _state.value.cols to _state.value.rows
        if (connectionState?.value == ConnectionState.Connected) {
            sendAttach(_state.value.cols, _state.value.rows)
            pendingReady = null
        }
    }

    // ── Envelope handling ────────────────────────────────────────────────

    private fun sendAttach(cols: Int, rows: Int) {
        _state.update { it.copy(attaching = true, error = null) }
        sendEnvelope("terminal.attach", buildJsonObject {
            put("cols", cols)
            put("rows", rows)
        })
    }

    private fun handleEnvelope(envelope: Envelope) {
        val payload = envelope.payload
        when (envelope.type) {
            "terminal.attached" -> {
                _state.update {
                    it.copy(
                        attached = true,
                        attaching = false,
                        sessionName = payload["session_name"]?.asStringOrNull(),
                        pid = payload["pid"]?.asIntOrNull(),
                        shell = payload["shell"]?.asStringOrNull(),
                        cols = payload["cols"]?.asIntOrNull() ?: it.cols,
                        rows = payload["rows"]?.asIntOrNull() ?: it.rows,
                        tmuxAvailable = payload["tmux_available"]?.asBoolOrNull() ?: false,
                        error = null
                    )
                }
                Log.i(TAG, "Attached: ${_state.value.sessionName} pid=${_state.value.pid}")
            }

            "terminal.output" -> {
                val data = payload["data"]?.asStringOrNull() ?: return
                Log.d(TAG, "terminal.output: ${data.length} bytes")
                // Re-encode as base64 so the WebView's window.writeTerminal can
                // decode it back to bytes without any JS-string escaping worries.
                val b64 = Base64.encodeToString(
                    data.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                viewModelScope.launch {
                    _outputFlow.emit(b64)
                }
            }

            "terminal.detached" -> {
                val reason = payload["reason"]?.asStringOrNull() ?: "detached"
                Log.i(TAG, "Detached: $reason")
                _state.update {
                    it.copy(
                        attached = false,
                        attaching = false,
                        sessionName = null,
                        pid = null,
                        error = if (reason == "client detach") null else reason
                    )
                }
            }

            "terminal.error" -> {
                val message = payload["message"]?.asStringOrNull() ?: "Unknown error"
                Log.w(TAG, "Terminal error: $message")
                _state.update { it.copy(attaching = false, error = message) }
            }

            "terminal.sessions" -> {
                // Session listing — unused in MVP, logged for debugging.
                Log.d(TAG, "Sessions: $payload")
            }

            else -> Log.d(TAG, "Unhandled terminal envelope type: ${envelope.type}")
        }
    }

    private fun sendEnvelope(type: String, payload: JsonObject) {
        val mux = multiplexer ?: return
        mux.send(
            Envelope(
                channel = "terminal",
                type = type,
                payload = payload
            )
        )
    }
}

// ── JsonElement helpers (kept local to avoid polluting the global namespace) ──

private fun kotlinx.serialization.json.JsonElement.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private fun kotlinx.serialization.json.JsonElement.asIntOrNull(): Int? =
    (this as? JsonPrimitive)?.intOrNull

private fun kotlinx.serialization.json.JsonElement.asBoolOrNull(): Boolean? =
    (this as? JsonPrimitive)?.booleanOrNull
