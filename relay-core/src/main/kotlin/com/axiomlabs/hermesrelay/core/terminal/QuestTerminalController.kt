package com.axiomlabs.hermesrelay.core.terminal

import android.os.Build
import android.util.Base64
import com.axiomlabs.hermesrelay.core.pairing.PairingPayloadParser
import com.axiomlabs.hermesrelay.core.pairing.QuestSessionStore
import com.axiomlabs.hermesrelay.core.transport.ConnectionState
import com.axiomlabs.hermesrelay.core.transport.RelayConnection
import com.axiomlabs.hermesrelay.core.wire.ChannelMultiplexer
import com.axiomlabs.hermesrelay.core.wire.Envelope
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

sealed class QuestAuthState {
    data object Unpaired : QuestAuthState()
    data object Pairing : QuestAuthState()
    data class Paired(val token: String) : QuestAuthState()
    data class Failed(val reason: String) : QuestAuthState()
}

enum class SpecialKey {
    ESC,
    TAB,
    ARROW_UP,
    ARROW_DOWN,
    ARROW_LEFT,
    ARROW_RIGHT,
    HOME,
    END,
    PAGE_UP,
    PAGE_DOWN,
}

class QuestTerminalController(
    private val store: QuestSessionStore,
    private val scope: CoroutineScope,
) {
    companion object {
        const val MAX_TABS = 4
    }

    data class TabState(
        val tabId: Int,
        val sessionName: String,
        val userStarted: Boolean = false,
        val attached: Boolean = false,
        val attaching: Boolean = false,
        val cols: Int = 100,
        val rows: Int = 32,
        val pid: Int? = null,
        val shell: String? = null,
        val tmuxAvailable: Boolean = false,
        val error: String? = null,
        val ctrlActive: Boolean = false,
        val altActive: Boolean = false,
    )

    data class TabOutput(val tabId: Int, val b64: String)

    private val multiplexer = ChannelMultiplexer()
    private val connection = RelayConnection(multiplexer) { hasPairContext() }

    private var pendingPairingCode: String? = null
    private val pendingReady = mutableMapOf<Int, Pair<Int, Int>>()

    private val _authState = MutableStateFlow<QuestAuthState>(
        store.sessionToken?.let { QuestAuthState.Paired(it) } ?: QuestAuthState.Unpaired
    )
    val authState: StateFlow<QuestAuthState> = _authState.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = connection.state

    private val _tabs = MutableStateFlow(listOf(makeTab(1)))
    val tabs: StateFlow<List<TabState>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow(1)
    val activeTabId: StateFlow<Int> = _activeTabId.asStateFlow()

    private val _outputFlow = MutableSharedFlow<TabOutput>(extraBufferCapacity = 256)
    val outputFlow: SharedFlow<TabOutput> = _outputFlow.asSharedFlow()

    private val _eventMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val eventMessages: SharedFlow<String> = _eventMessages.asSharedFlow()

    init {
        multiplexer.registerHandler("system") { handleSystem(it) }
        multiplexer.registerHandler("terminal") { handleTerminal(it) }
        multiplexer.setOnConnectedCallback { authenticate() }
    }

    fun connectStored() {
        val url = store.relayUrl ?: return
        connection.connect(url)
    }

    fun pairManually(relayUrl: String, code: String) {
        val normalized = code.trim().uppercase()
        if (relayUrl.isBlank() || normalized.isBlank()) {
            emit("Relay URL and code are required")
            return
        }
        store.relayUrl = relayUrl.trim()
        store.clearSessionToken()
        pendingPairingCode = normalized
        _authState.value = QuestAuthState.Unpaired
        connection.connect(relayUrl.trim())
    }

    fun pairFromQrPayload(payload: String) {
        val parsed = PairingPayloadParser.parse(payload)
        parsed.onSuccess { pairManually(it.relayUrl, it.pairingCode) }
            .onFailure { emit(it.message ?: "Could not read QR payload") }
    }

    fun disconnect() {
        connection.disconnect()
    }

    fun openNewTab(): Int? {
        val current = _tabs.value
        if (current.size >= MAX_TABS) return null
        val used = current.map { it.tabId }.toSet()
        val nextId = (1..MAX_TABS).first { it !in used }
        _tabs.update { (it + makeTab(nextId)).sortedBy { tab -> tab.tabId } }
        _activeTabId.value = nextId
        return nextId
    }

    fun selectTab(tabId: Int) {
        if (_tabs.value.any { it.tabId == tabId }) _activeTabId.value = tabId
    }

    fun closeTab(tabId: Int) {
        val current = _tabs.value
        if (current.size <= 1) return
        val tab = current.firstOrNull { it.tabId == tabId } ?: return
        if (tab.attached || tab.attaching) detachTab(tabId)
        val remaining = current.filter { it.tabId != tabId }
        _tabs.value = remaining
        if (_activeTabId.value == tabId) _activeTabId.value = remaining.first().tabId
    }

    fun detachTab(tabId: Int) {
        val tab = tabById(tabId) ?: return
        if (!tab.attached && !tab.attaching) return
        sendTerminal("terminal.detach", buildJsonObject {
            put("session_name", tab.sessionName)
        })
        updateTab(tabId) { it.copy(attached = false, attaching = false, pid = null) }
    }

    fun killTab(tabId: Int) {
        val tab = tabById(tabId) ?: return
        if (tab.userStarted) {
            sendTerminal("terminal.kill", buildJsonObject {
                put("session_name", tab.sessionName)
            })
        }
        val remaining = _tabs.value.filter { it.tabId != tabId }
        _tabs.value = remaining.ifEmpty { listOf(makeTab(tabId)) }
        _activeTabId.value = _tabs.value.first().tabId
    }

    fun startSession(tabId: Int) {
        val tab = tabById(tabId) ?: return
        if (tab.userStarted && (tab.attached || tab.attaching)) return
        updateTab(tabId) { it.copy(userStarted = true, error = null) }
        if (isReadyForTerminal()) sendAttach(tabId, tab.cols, tab.rows) else pendingReady[tabId] = tab.cols to tab.rows
    }

    fun onTerminalReady(tabId: Int, cols: Int, rows: Int) {
        updateTab(tabId) { it.copy(cols = cols, rows = rows) }
        val tab = tabById(tabId) ?: return
        if (!tab.userStarted) return
        if (isReadyForTerminal()) sendAttach(tabId, cols, rows) else pendingReady[tabId] = cols to rows
    }

    fun resize(tabId: Int, cols: Int, rows: Int) {
        val tab = tabById(tabId) ?: return
        if (tab.cols == cols && tab.rows == rows) return
        updateTab(tabId) { it.copy(cols = cols, rows = rows) }
        if (tab.attached) {
            sendTerminal("terminal.resize", buildJsonObject {
                put("session_name", tab.sessionName)
                put("cols", cols)
                put("rows", rows)
            })
        }
    }

    fun reattach(tabId: Int) {
        val tab = tabById(tabId) ?: return
        pendingReady[tabId] = tab.cols to tab.rows
        if (isReadyForTerminal()) {
            sendAttach(tabId, tab.cols, tab.rows)
            pendingReady.remove(tabId)
        }
    }

    fun sendInput(tabId: Int, data: String) {
        if (data.isEmpty()) return
        val tab = tabById(tabId) ?: return
        var payload = data
        if (tab.ctrlActive && payload.length == 1) {
            val c = payload[0]
            payload = when (c) {
                in 'a'..'z' -> (c.code - 'a'.code + 1).toChar().toString()
                in 'A'..'Z' -> (c.code - 'A'.code + 1).toChar().toString()
                ' ' -> "\u0000"
                '[' -> "\u001b"
                '\\' -> "\u001c"
                ']' -> "\u001d"
                else -> payload
            }
            updateTab(tabId) { it.copy(ctrlActive = false) }
        }
        if (tab.altActive && payload.isNotEmpty()) {
            payload = "\u001b$payload"
            updateTab(tabId) { it.copy(altActive = false) }
        }
        sendTerminal("terminal.input", buildJsonObject {
            put("session_name", tab.sessionName)
            put("data", payload)
        })
    }

    fun sendKey(tabId: Int, key: SpecialKey) {
        val tab = tabById(tabId) ?: return
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
        sendTerminal("terminal.input", buildJsonObject {
            put("session_name", tab.sessionName)
            put("data", sequence)
        })
    }

    fun toggleCtrl(tabId: Int) {
        updateTab(tabId) { it.copy(ctrlActive = !it.ctrlActive) }
    }

    fun toggleAlt(tabId: Int) {
        updateTab(tabId) { it.copy(altActive = !it.altActive) }
    }

    private fun authenticate() {
        val token = store.sessionToken
        val code = pendingPairingCode
        if (token == null && code == null) {
            _authState.value = QuestAuthState.Unpaired
            emit("Pair with the relay before connecting")
            return
        }
        _authState.value = if (token == null) QuestAuthState.Pairing else QuestAuthState.Paired(token)
        val deviceName = "Meta Quest ${Build.MODEL}".trim()
        val payload = buildJsonObject {
            if (token != null) put("session_token", token) else put("pairing_code", code!!)
            put("device_id", store.deviceId)
            put("device_name", deviceName)
            put("client_surface", "quest")
            put("device_form_factor", "xr")
        }
        multiplexer.send(Envelope(channel = "system", type = "auth", payload = payload))
    }

    private fun handleSystem(envelope: Envelope) {
        when (envelope.type) {
            "auth.ok" -> {
                val token = envelope.payload["session_token"]?.asStringOrNull()
                if (token != null) {
                    store.sessionToken = token
                    pendingPairingCode = null
                    _authState.value = QuestAuthState.Paired(token)
                    emit("Paired with relay")
                    replayPendingAttaches()
                }
            }
            "auth.fail" -> {
                val reason = envelope.payload["reason"]?.asStringOrNull() ?: "Authentication failed"
                store.clearSessionToken()
                _authState.value = QuestAuthState.Failed(reason)
                emit(reason)
            }
        }
    }

    private fun handleTerminal(envelope: Envelope) {
        val payload = envelope.payload
        val sessionName = payload["session_name"]?.asStringOrNull()
        val target = sessionName?.let { name -> _tabs.value.firstOrNull { it.sessionName == name } }
            ?: tabById(_activeTabId.value)
            ?: return

        when (envelope.type) {
            "terminal.attached" -> updateTab(target.tabId) {
                it.copy(
                    attached = true,
                    attaching = false,
                    pid = payload["pid"]?.asIntOrNull(),
                    shell = payload["shell"]?.asStringOrNull(),
                    cols = payload["cols"]?.asIntOrNull() ?: it.cols,
                    rows = payload["rows"]?.asIntOrNull() ?: it.rows,
                    tmuxAvailable = payload["tmux_available"]?.asBoolOrNull() ?: false,
                    error = null,
                )
            }
            "terminal.output" -> {
                val data = payload["data"]?.asStringOrNull() ?: return
                val b64 = Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                scope.launch { _outputFlow.emit(TabOutput(target.tabId, b64)) }
            }
            "terminal.detached" -> updateTab(target.tabId) {
                val reason = payload["reason"]?.asStringOrNull()
                it.copy(
                    attached = false,
                    attaching = false,
                    pid = null,
                    error = if (reason == "client detach" || reason == "client kill") null else reason,
                )
            }
            "terminal.error" -> {
                val message = payload["message"]?.asStringOrNull() ?: "Terminal error"
                updateTab(target.tabId) { it.copy(attaching = false, error = message) }
            }
        }
    }

    private fun replayPendingAttaches() {
        val queued = pendingReady.toMap()
        for ((tabId, dims) in queued) {
            sendAttach(tabId, dims.first, dims.second)
            pendingReady.remove(tabId)
        }
    }

    private fun sendAttach(tabId: Int, cols: Int, rows: Int) {
        val tab = tabById(tabId) ?: return
        updateTab(tabId) { it.copy(attaching = true, error = null) }
        sendTerminal("terminal.attach", buildJsonObject {
            put("session_name", tab.sessionName)
            put("cols", cols)
            put("rows", rows)
        })
    }

    private fun sendTerminal(type: String, payload: JsonObject) {
        multiplexer.send(Envelope(channel = "terminal", type = type, payload = payload))
    }

    private fun hasPairContext(): Boolean =
        store.sessionToken != null || pendingPairingCode != null || _authState.value is QuestAuthState.Paired

    private fun isReadyForTerminal(): Boolean =
        connection.state.value == ConnectionState.Connected && _authState.value is QuestAuthState.Paired

    private fun sessionNameFor(tabId: Int): String = "quest-${store.deviceId}-tab$tabId"

    private fun makeTab(tabId: Int): TabState = TabState(tabId = tabId, sessionName = sessionNameFor(tabId))

    private fun tabById(tabId: Int): TabState? = _tabs.value.firstOrNull { it.tabId == tabId }

    private inline fun updateTab(tabId: Int, transform: (TabState) -> TabState) {
        _tabs.update { list -> list.map { if (it.tabId == tabId) transform(it) else it } }
    }

    private fun emit(message: String) {
        _eventMessages.tryEmit(message)
    }
}

private fun kotlinx.serialization.json.JsonElement.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private fun kotlinx.serialization.json.JsonElement.asIntOrNull(): Int? =
    (this as? JsonPrimitive)?.intOrNull

private fun kotlinx.serialization.json.JsonElement.asBoolOrNull(): Boolean? =
    (this as? JsonPrimitive)?.booleanOrNull
