package com.hermesandroid.relay.viewmodel

import android.app.Application
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.auth.AuthManager
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.TerminalTabNameStore
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.ConnectionState
import com.hermesandroid.relay.network.models.Envelope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Terminal channel ViewModel — multi-tab edition.
 *
 * Owns up to [MAX_TABS] concurrent PTY session slots and bridges envelopes
 * between the WebSocket relay (via [ChannelMultiplexer]) and per-tab
 * [com.hermesandroid.relay.ui.components.TerminalWebView] instances.
 *
 * Each tab maps to a stable wire-side `session_name` of the form
 * `hermes-<deviceId>-tab<N>` so the server (which wraps every PTY in tmux)
 * can re-attach the same long-lived shell across phone reconnects without
 * the phone needing to remember anything beyond the tab index.
 *
 * Lifecycle per tab:
 *   1. [openNewTab] (or the auto-created tab1 in [initialize]) reserves a slot.
 *   2. The tab's WebView boots, fits the container, and calls [onTerminalReady]
 *      with the initial cols/rows. The ViewModel sends `terminal.attach`.
 *   3. Relay responds with `terminal.attached` → that tab's [TabState] flips
 *      to attached.
 *   4. Output arrives as `terminal.output` envelopes, gets routed to the
 *      matching tab by `session_name`, and is emitted on [outputFlow] as a
 *      [TabOutput] tagged with the destination tab id. Each WebView filters
 *      the flow to its own tab id.
 *   5. Typing in xterm.js → WebView JS bridge → [sendInput] (with tab id) →
 *      `terminal.input`.
 *
 * The auth gate from the single-tab implementation is preserved exactly:
 * we never send a `terminal.*` envelope unless `connectionState == Connected
 * && authState is AuthState.Paired`. Pending attaches per tab are held in
 * [pendingReady] and replayed when the gate opens.
 */
class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TerminalViewModel"
        const val MAX_TABS = 4
    }

    /** Keys the extra-keys toolbar can emit. */
    enum class SpecialKey {
        ESC, TAB, ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
        HOME, END, PAGE_UP, PAGE_DOWN
    }

    /**
     * Per-tab terminal state. One of these exists for every active tab.
     *
     * @property tabId stable user-facing tab number (1..MAX_TABS). Reused
     *           when a tab is closed and a new one is opened — see
     *           [openNewTab] for the slot-allocation rules.
     * @property sessionName the wire-side session_name we send to the relay
     *           for this tab — `hermes-<deviceId>-tab<tabId>`. Stable across
     *           reconnects so the server's tmux-backed handler re-attaches
     *           the same shell.
     */
    /**
     * Per-tab terminal state.
     *
     * @property userStarted flips true the first time the user taps "Start
     *           session" on this tab (or calls [startSession] from anywhere).
     *           While false, the tab exists in the UI but no `terminal.attach`
     *           has been sent — the tmux-backed shell isn't created. This is
     *           the opt-in gate that replaces the previous auto-attach
     *           behaviour: we no longer spawn persistent shells on the server
     *           just because the user opened the Terminal tab.
     *
     *           Once true, the tab participates in the auth-gate replay: if
     *           the wire drops and re-rises, we re-attach automatically.
     */
    data class TabState(
        val tabId: Int,
        val sessionName: String,
        val userStarted: Boolean = false,
        val attached: Boolean = false,
        val attaching: Boolean = false,
        val cols: Int = 80,
        val rows: Int = 24,
        val pid: Int? = null,
        val shell: String? = null,
        val tmuxAvailable: Boolean = false,
        val error: String? = null,
        val ctrlActive: Boolean = false,
        val altActive: Boolean = false,
        /**
         * Friendly name for the tab, set by the user. Cosmetic-only —
         * never crosses the wire. Persisted via [TerminalTabNameStore]
         * keyed on [sessionName] so the name sticks across app restart
         * and reattach. Null when the user hasn't named this session.
         */
        val displayName: String? = null,
    )

    /**
     * One chunk of base64-encoded output destined for a specific tab. Emitted
     * on [outputFlow] so per-tab WebViews can filter on `tabId`.
     */
    data class TabOutput(val tabId: Int, val b64: String)

    private val _tabs = MutableStateFlow<List<TabState>>(emptyList())
    val tabs: StateFlow<List<TabState>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow(1)
    val activeTabId: StateFlow<Int> = _activeTabId.asStateFlow()

    /**
     * Convenience derived flow for the currently-active tab. Null when no
     * tab exists yet (briefly during cold start before [initialize]).
     */
    val activeTab: StateFlow<TabState?> = combine(_tabs, _activeTabId) { list, id ->
        list.firstOrNull { it.tabId == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Single multiplexed output stream tagged by tab id. Each
     * [com.hermesandroid.relay.ui.components.TerminalWebView] subscribes and
     * filters on its own tab id — no per-tab flow ceremony, no risk of a
     * SharedFlow getting orphaned when its tab closes.
     */
    private val _outputFlow = MutableSharedFlow<TabOutput>(
        replay = 0,
        extraBufferCapacity = 256
    )
    val outputFlow: SharedFlow<TabOutput> = _outputFlow.asSharedFlow()

    private var multiplexer: ChannelMultiplexer? = null
    private var connectionState: StateFlow<ConnectionState>? = null
    private var authState: StateFlow<AuthState>? = null
    private var authManager: AuthManager? = null
    private var tabNameStore: TerminalTabNameStore? = null

    /**
     * Cached device id used to construct stable per-tab session names. Loaded
     * lazily inside [initialize] before the first tab is created so the
     * initial tab1 lines up with the deviceId-derived name immediately.
     */
    private var cachedDeviceId: String = "unknown"

    /**
     * Pending attach requests keyed by tab id. If a WebView signals ready
     * before the relay is ready for channel messages, we hold the request
     * and replay it when the auth+connection gate opens.
     */
    private val pendingReady: MutableMap<Int, Pair<Int, Int>> = mutableMapOf()

    /**
     * One-shot init. Wires the multiplexer, watches the auth+connection gate,
     * and creates the initial tab1. Subsequent calls are no-ops so it's safe
     * to invoke from `LaunchedEffect(Unit)`.
     */
    fun initialize(
        multiplexer: ChannelMultiplexer,
        connectionState: StateFlow<ConnectionState>,
        authState: StateFlow<AuthState>,
        authManager: AuthManager? = null,
        tabNameStore: TerminalTabNameStore? = null,
    ) {
        if (this.multiplexer != null) return
        this.multiplexer = multiplexer
        this.connectionState = connectionState
        this.authState = authState
        this.authManager = authManager
        this.tabNameStore = tabNameStore

        // Rebind persisted display names whenever the store changes. We
        // collect for the life of the ViewModel so external edits (e.g.
        // future batch-rename flows) propagate without special cases.
        if (tabNameStore != null) {
            viewModelScope.launch {
                tabNameStore.namesFlow.collect { namesByWire ->
                    _tabs.update { list ->
                        list.map { tab ->
                            val name = namesByWire[tab.sessionName]
                            if (tab.displayName == name) tab else tab.copy(displayName = name)
                        }
                    }
                }
            }
        }

        multiplexer.registerHandler("terminal") { envelope ->
            handleEnvelope(envelope)
        }

        // Resolve the device id off the main dispatcher and seed tab1.
        // We seed tab1 immediately with a placeholder name so the UI has
        // something to render, then patch the session_name once the device
        // id resolves. The placeholder name is never sent on the wire because
        // sendAttach is gated on the auth combine below — it can't fire until
        // the device id has been baked into the tab.
        viewModelScope.launch {
            cachedDeviceId = try {
                authManager?.getOrCreateDeviceId() ?: "unknown"
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve device id, using fallback", e)
                "unknown"
            }
            if (_tabs.value.isEmpty()) {
                _tabs.value = listOf(makeTab(1))
                _activeTabId.value = 1
            } else {
                // Patch every existing tab so the placeholder is replaced.
                _tabs.update { list ->
                    list.map { it.copy(sessionName = sessionNameFor(it.tabId)) }
                }
            }
        }

        // Auth-gated attach pump.
        //
        // The relay expects the first message on a freshly-opened WebSocket
        // to be a `system/auth` envelope. If we send `terminal.attach` before
        // [AuthManager] has completed its auth round-trip, the relay closes
        // the connection with "expected system/auth, got terminal/terminal.attach"
        // and the phone's AuthState flips to Failed — which the UI renders
        // as "pair cleared, re-pair required."
        //
        // Before 2026-04-11 we watched `connectionState == Connected` only,
        // which fires the instant the WS opens, well before `authenticate()`
        // has finished its suspend I/O to read the device ID. Result: a race
        // that `sendAttach` won on every reconnect.
        //
        // Now we `combine` connection + auth and only fire attach when BOTH
        // are healthy (Connected + Paired). On drop we flip every tab back
        // to detached state. On rise we replay every pending attach.
        viewModelScope.launch {
            connectionState.combine(authState) { conn, auth -> conn to auth }
                .collect { (conn, auth) ->
                    val ready = conn == ConnectionState.Connected && auth is AuthState.Paired
                    if (!ready) {
                        // Reflect drop on every tab so the UI doesn't claim
                        // attached state while the wire is dead. The actual
                        // server-side tmux session is still alive — we'll
                        // re-attach next time the gate opens.
                        _tabs.update { list ->
                            list.map { tab ->
                                if (tab.attached || tab.attaching) {
                                    // Re-queue this tab to re-attach when the
                                    // gate re-opens. We use the cached cols/rows
                                    // so the server gets a sensible initial PTY
                                    // size right away — the WebView's layout
                                    // listener will resize again on first paint.
                                    pendingReady[tab.tabId] = tab.cols to tab.rows
                                    tab.copy(
                                        attached = false,
                                        attaching = false,
                                        pid = null,
                                    )
                                } else tab
                            }
                        }
                    } else {
                        // Replay every queued attach. Iterate a snapshot so we
                        // can mutate the map under the loop.
                        val queued = pendingReady.toMap()
                        for ((tabId, dims) in queued) {
                            sendAttach(tabId, dims.first, dims.second)
                            pendingReady.remove(tabId)
                        }
                    }
                }
        }
    }

    // ── Tab management ───────────────────────────────────────────────────

    private fun sessionNameFor(tabId: Int): String =
        "hermes-${cachedDeviceId}-tab${tabId}"

    private fun makeTab(tabId: Int): TabState =
        TabState(tabId = tabId, sessionName = sessionNameFor(tabId))

    /**
     * Allocate a new tab. Returns the new tab id, or `null` when the tab
     * cap is hit. New tabs become active immediately so the UI animates the
     * tab strip and the WebView for the new tab gets constructed on the
     * next composition.
     */
    fun openNewTab(): Int? {
        val current = _tabs.value
        if (current.size >= MAX_TABS) return null
        // Find the lowest unused tab id in [1..MAX_TABS]. We re-use slots
        // when an earlier tab was closed so the user always sees `1 2 3`
        // instead of `1 3 4` after closing tab 2.
        val used = current.map { it.tabId }.toSet()
        val newId = (1..MAX_TABS).first { it !in used }
        val newTab = makeTab(newId)
        _tabs.update { (it + newTab).sortedBy { t -> t.tabId } }
        _activeTabId.value = newId
        Log.i(TAG, "openNewTab: id=$newId session=${newTab.sessionName}")
        return newId
    }

    /**
     * Close a tab, preserving the underlying tmux session on the server.
     *
     * Sends `terminal.detach` so the relay releases the PTY but leaves the
     * tmux-hosted shell alive — the next [startSession] with the same
     * [TabState.sessionName] re-enters the same running shell. This is the
     * "soft" close: the user is done with the tab on *this* device but might
     * come back later (same device, another device).
     *
     * For destructive cleanup — kill the tmux session, stop any running
     * commands, free the shell — use [killTab] instead.
     *
     * Closing the last tab is a no-op — there's always at least one tab
     * slot visible in the UI.
     */
    fun closeTab(tabId: Int) {
        val current = _tabs.value
        if (current.size <= 1) return
        val tab = current.firstOrNull { it.tabId == tabId } ?: return
        if (tab.attached) {
            sendEnvelope("terminal.detach", buildJsonObject {
                put("session_name", tab.sessionName)
            })
        }
        pendingReady.remove(tabId)
        val remaining = current.filter { it.tabId != tabId }
        _tabs.value = remaining
        if (_activeTabId.value == tabId) {
            _activeTabId.value = remaining.first().tabId
        }
        Log.i(TAG, "closeTab (detach): id=$tabId remaining=${remaining.map { it.tabId }}")
    }

    /**
     * Destructively close a tab — sends `terminal.kill` so the relay tears
     * down the tmux session and the background shell dies with it. Use this
     * when the user wants a clean slate, not a detach-for-later.
     *
     * If the tab was never started ([TabState.userStarted] == false), this
     * degrades to removing the tab from the UI without touching the wire —
     * there's nothing to kill.
     */
    fun killTab(tabId: Int) {
        val current = _tabs.value
        val tab = current.firstOrNull { it.tabId == tabId } ?: return
        if (tab.userStarted) {
            sendEnvelope("terminal.kill", buildJsonObject {
                put("session_name", tab.sessionName)
            })
        }
        // Kill destroys the underlying session — its friendly name should
        // go with it. Detach (closeTab) deliberately leaves the name in
        // place so re-opening the same slot restores it.
        tabNameStore?.let { store ->
            viewModelScope.launch { store.clearName(tab.sessionName) }
        }
        pendingReady.remove(tabId)
        val remaining = current.filter { it.tabId != tabId }
        if (remaining.isEmpty()) {
            // Never drop to zero tabs — replace with a fresh, un-started slot
            // at the same id so the UI always has something to render.
            _tabs.value = listOf(makeTab(tab.tabId))
            _activeTabId.value = tab.tabId
            Log.i(TAG, "killTab: id=$tabId (last tab, reseeded)")
            return
        }
        _tabs.value = remaining
        if (_activeTabId.value == tabId) {
            _activeTabId.value = remaining.first().tabId
        }
        Log.i(TAG, "killTab: id=$tabId remaining=${remaining.map { it.tabId }}")
    }

    fun selectTab(tabId: Int) {
        if (_tabs.value.any { it.tabId == tabId }) {
            _activeTabId.value = tabId
        }
    }

    /**
     * Set a friendly name for [tabId]. Passing null or a blank string
     * clears the name (tab falls back to displaying its tab number).
     *
     * Persistence is fire-and-forget on viewModelScope — the store
     * update is cheap and we surface the change to the UI immediately
     * via the flow collector wired in [initialize], so we don't need
     * to await the write.
     */
    fun setTabName(tabId: Int, name: String?) {
        val tab = tabById(tabId) ?: return
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        updateTab(tabId) { it.copy(displayName = trimmed) }
        val store = tabNameStore ?: return
        viewModelScope.launch {
            store.setName(tab.sessionName, trimmed)
        }
    }

    private inline fun updateTab(tabId: Int, transform: (TabState) -> TabState) {
        _tabs.update { list ->
            list.map { if (it.tabId == tabId) transform(it) else it }
        }
    }

    private fun tabById(tabId: Int): TabState? =
        _tabs.value.firstOrNull { it.tabId == tabId }

    // ── Called by TerminalWebView's JS bridge ────────────────────────────

    /**
     * WebView booted and knows its container size in cells. We record the
     * dimensions but do NOT attach yet — the user must explicitly tap
     * "Start session" (which calls [startSession]) before we spawn a shell
     * on the relay. This is the opt-in gate: fresh tabs no longer create
     * persistent tmux sessions just by existing.
     *
     * If the tab was previously started in this process (e.g. the user
     * tapped Start, then the WS dropped and the WebView re-laid out), we
     * replay the attach immediately through [pendingReady] + the auth gate.
     */
    fun onTerminalReady(tabId: Int, cols: Int, rows: Int) {
        Log.i(TAG, "onTerminalReady tab=$tabId cols=$cols rows=$rows")
        updateTab(tabId) { it.copy(cols = cols, rows = rows) }
        val tab = tabById(tabId) ?: return
        if (!tab.userStarted) return
        if (isReadyForChannelMessages()) {
            sendAttach(tabId, cols, rows)
        } else {
            pendingReady[tabId] = cols to rows
        }
    }

    /**
     * User-initiated attach for [tabId]. Flips [TabState.userStarted] to true
     * and kicks off the attach path. Subsequent reconnects re-attach
     * automatically via the auth-gate replay.
     */
    fun startSession(tabId: Int) {
        val tab = tabById(tabId) ?: return
        if (tab.userStarted && (tab.attached || tab.attaching)) return
        Log.i(TAG, "startSession tab=$tabId session=${tab.sessionName}")
        updateTab(tabId) { it.copy(userStarted = true, error = null) }
        if (isReadyForChannelMessages()) {
            sendAttach(tabId, tab.cols, tab.rows)
        } else {
            pendingReady[tabId] = tab.cols to tab.rows
        }
    }

    private fun isReadyForChannelMessages(): Boolean =
        connectionState?.value == ConnectionState.Connected &&
            authState?.value is AuthState.Paired

    /** Raw input from xterm.js (soft keyboard, hardware keys, paste). */
    fun sendInput(tabId: Int, data: String) {
        if (data.isEmpty()) return
        val tab = tabById(tabId) ?: return
        Log.d(TAG, "sendInput tab=$tabId: ${data.length} bytes attached=${tab.attached}")
        var payload = data

        // Apply sticky CTRL: map a single a-z/A-Z keypress to its control byte.
        if (tab.ctrlActive && payload.length == 1) {
            val c = payload[0]
            val translated = when (c) {
                in 'a'..'z' -> (c.code - 'a'.code + 1).toChar().toString()
                in 'A'..'Z' -> (c.code - 'A'.code + 1).toChar().toString()
                ' ' -> "\u0000"      // Ctrl+Space → NUL
                '[' -> "\u001b"      // Ctrl+[ → ESC
                '\\' -> "\u001c"     // Ctrl+\
                ']' -> "\u001d"      // Ctrl+]
                else -> payload
            }
            payload = translated
            updateTab(tabId) { it.copy(ctrlActive = false) }
        }

        // Apply sticky ALT: prefix ESC (standard meta convention).
        if (tab.altActive && payload.isNotEmpty()) {
            payload = "\u001b$payload"
            updateTab(tabId) { it.copy(altActive = false) }
        }

        sendEnvelope("terminal.input", buildJsonObject {
            put("session_name", tab.sessionName)
            put("data", payload)
        })
    }

    /** Extra-keys toolbar key tap. */
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
        // Special keys bypass sticky CTRL/ALT translation and are sent raw.
        sendEnvelope("terminal.input", buildJsonObject {
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

    /** Container resize from the WebView — forwards to the server. */
    fun resize(tabId: Int, cols: Int, rows: Int) {
        val tab = tabById(tabId) ?: return
        if (tab.cols == cols && tab.rows == rows) return
        Log.i(TAG, "resize tab=$tabId: cols=$cols rows=$rows (was ${tab.cols}x${tab.rows})")
        updateTab(tabId) { it.copy(cols = cols, rows = rows) }
        if (tab.attached) {
            sendEnvelope("terminal.resize", buildJsonObject {
                put("session_name", tab.sessionName)
                put("cols", cols)
                put("rows", rows)
            })
        }
    }

    /**
     * Force a fresh attach for [tabId]. Used by the Reattach button on the
     * info sheet — same path as the auth-gate replay so we never bypass the
     * gate accidentally.
     */
    fun reattach(tabId: Int) {
        val tab = tabById(tabId) ?: return
        pendingReady[tabId] = tab.cols to tab.rows
        if (isReadyForChannelMessages()) {
            sendAttach(tabId, tab.cols, tab.rows)
            pendingReady.remove(tabId)
        }
    }

    /** Server-side detach without removing the tab — currently unused but kept for parity with the old API. */
    fun detach(tabId: Int) {
        val tab = tabById(tabId) ?: return
        if (!tab.attached) return
        sendEnvelope("terminal.detach", buildJsonObject {
            put("session_name", tab.sessionName)
        })
        updateTab(tabId) {
            it.copy(attached = false, attaching = false, pid = null)
        }
    }

    // ── Envelope handling ────────────────────────────────────────────────

    private fun sendAttach(tabId: Int, cols: Int, rows: Int) {
        val tab = tabById(tabId) ?: return
        updateTab(tabId) { it.copy(attaching = true, error = null) }
        sendEnvelope("terminal.attach", buildJsonObject {
            put("session_name", tab.sessionName)
            put("cols", cols)
            put("rows", rows)
        })
    }

    /**
     * Route a terminal envelope to the right tab by `session_name`. Falls
     * back to the active tab when the envelope omits `session_name` (some
     * server responses might) so we don't drop output on the floor.
     */
    private fun handleEnvelope(envelope: Envelope) {
        val payload = envelope.payload
        val sessionName = payload["session_name"]?.asStringOrNull()
        val targetTab: TabState? = sessionName?.let { name ->
            _tabs.value.firstOrNull { it.sessionName == name }
        } ?: tabById(_activeTabId.value)

        when (envelope.type) {
            "terminal.attached" -> {
                val tab = targetTab ?: return
                updateTab(tab.tabId) {
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
                Log.i(TAG, "Attached tab=${tab.tabId} session=${tab.sessionName} pid=${payload["pid"]?.asIntOrNull()}")
            }

            "terminal.output" -> {
                val tab = targetTab ?: return
                val data = payload["data"]?.asStringOrNull() ?: return
                Log.d(TAG, "terminal.output tab=${tab.tabId}: ${data.length} bytes")
                // Re-encode as base64 so the WebView's window.writeTerminal can
                // decode it back to bytes without any JS-string escaping worries.
                val b64 = Base64.encodeToString(
                    data.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                viewModelScope.launch {
                    _outputFlow.emit(TabOutput(tab.tabId, b64))
                }
            }

            "terminal.detached" -> {
                val tab = targetTab ?: return
                val reason = payload["reason"]?.asStringOrNull() ?: "detached"
                Log.i(TAG, "Detached tab=${tab.tabId}: $reason")
                // Both "client detach" and "client kill" are user-initiated
                // shutdowns — not errors. The kill case is especially
                // load-bearing here because killing the last tab reseeds a
                // fresh tab at the same tabId (and therefore the same wire
                // session_name), so the server's "client kill" detached
                // envelope arrives AFTER the reseed and would otherwise
                // stamp an error onto the brand-new tab the user is about
                // to start.
                val userInitiated = reason == "client detach" || reason == "client kill"
                updateTab(tab.tabId) {
                    it.copy(
                        attached = false,
                        attaching = false,
                        pid = null,
                        error = if (userInitiated) null else reason,
                    )
                }
            }

            "terminal.error" -> {
                val message = payload["message"]?.asStringOrNull() ?: "Unknown error"
                // Only bind the error to a tab when the envelope explicitly
                // addresses one via session_name. Server-level errors with no
                // session scope (e.g. "Unknown terminal message type" from an
                // old relay binary that doesn't know a new envelope type) used
                // to fall through to the active tab and poison whichever tab
                // the user happened to be looking at — producing error overlays
                // on tabs they never interacted with. We log those instead.
                val tab = sessionName?.let { name ->
                    _tabs.value.firstOrNull { it.sessionName == name }
                }
                if (tab != null) {
                    Log.w(TAG, "Terminal error tab=${tab.tabId}: $message")
                    updateTab(tab.tabId) { it.copy(attaching = false, error = message) }
                } else {
                    Log.w(TAG, "Terminal error (no session scope): $message")
                }
            }

            "terminal.sessions" -> {
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
