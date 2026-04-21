package com.hermesandroid.relay.viewmodel

import android.util.Log
import com.hermesandroid.relay.auth.AuthManager
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.ConnectionStore
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.ConnectionManager
import com.hermesandroid.relay.network.HermesApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orchestrates the "heavy context swap" that happens when the user switches
 * between Hermes connections. Extracted out of
 * [ConnectionViewModel] so the swap sequence is testable without having to
 * stand up an Android [android.app.Application] + DataStore + EncryptedPrefs
 * + MediaProjection + ... — every collaborator here is either an interface
 * or a factory, which MockK / plain fakes can substitute trivially.
 *
 * Sequence (must happen in order):
 *
 *  1. Guard — no-op if the requested id equals [ConnectionStore.activeConnectionId].
 *  2. Invoke the stream-cancel callback registered via
 *     [registerStreamCancelCallback] so any in-flight SSE chat stream held
 *     by [ChatViewModel] is torn down before its API client disappears.
 *  3. Invoke the voice-stop callback registered via
 *     [registerVoiceStopCallback] so any active voice turn (recording or
 *     TTS playback) is aborted. We deliberately avoid a direct
 *     `VoiceViewModel` dependency here — the coordinator lives in the
 *     connection layer and voice is an optional feature wired on top.
 *  4. Call [ConnectionManager.disconnect] — flips `shouldReconnect = false`
 *     and closes the WSS, preventing the auto-reconnect loop from firing
 *     an auth envelope with the old connection's session token against the
 *     new connection's relay.
 *  5. Emit a [connectionSwitchEvents] event with the new connection id so
 *     [ChatViewModel] (and any other connection-scoped holder) can wipe
 *     local state — messages, session id, queued sends — before the
 *     rebuilt clients start serving the new connection.
 *  6. Look up the new [Connection] in [connectionStore]. If the id is
 *     unknown we log + abort (no state mutation); a stale UI reference is
 *     safer than proceeding with a half-switched context.
 *  7. Rebuild [AuthManager] via [authManagerFactory] bound to the new
 *     connection id. The new AuthManager's init block re-registers itself
 *     on [multiplexer] as the "system" channel handler, which transparently
 *     overwrites the previous one (see [ChannelMultiplexer.registerHandler]).
 *  8. Update URL flows via [setApiServerUrl] and [setRelayUrl] so
 *     [HermesApiClient] and [RelayHttpClient] pick up the new endpoints.
 *     [persistUrls] writes both values to the app DataStore in one pass.
 *  9. Rebuild the API client via [rebuildApiClient] so subsequent chat
 *     calls hit the new API server with the new API key.
 * 10. Await the new AuthManager's first non-[AuthState.Unpaired] /
 *     non-[AuthState.Loading] emission (or a short timeout) before deciding
 *     whether to kick the WSS handshake. Connecting too early fires an
 *     auth envelope with no pair context, which tick the relay's rate
 *     limiter — exactly the trap [AuthManager.hasPairContext] was
 *     introduced to avoid.
 * 11. Persist the new active-connection id via
 *     [ConnectionStore.setActiveConnection] last so an early failure leaves
 *     the previous active connection in place.
 *
 * The whole sequence runs under [switchMutex] so rapid-fire switches from
 * the UI queue cleanly instead of interleaving partial teardowns.
 */
class ConnectionSwitchCoordinator(
    private val connectionStore: ConnectionStore,
    private val connectionManager: ConnectionManager,
    private val scope: CoroutineScope,
    /**
     * Factory that constructs a brand-new [AuthManager] bound to a given
     * connection id. Called once per successful switch. The returned
     * instance must have already registered itself on [multiplexer] —
     * AuthManager's init block handles that via
     * `multiplexer.registerHandler("system", this)`.
     */
    private val authManagerFactory: (connectionId: String) -> AuthManager,
    /**
     * Installs a freshly-built [AuthManager] on the holder so the
     * reconnect gate and any other `authManager.*` call sites see it.
     */
    private val installAuthManager: (AuthManager) -> Unit,
    /**
     * Writes [ConnectionViewModel.apiServerUrl]'s backing flow.
     */
    private val setApiServerUrl: (String) -> Unit,
    /**
     * Writes [ConnectionViewModel.relayUrl]'s backing flow.
     */
    private val setRelayUrl: (String) -> Unit,
    /**
     * Persists both URLs to the app DataStore. Suspend because the edit is
     * async.
     */
    private val persistUrls: suspend (apiServerUrl: String, relayUrl: String) -> Unit,
    /**
     * Rebuilds the [HermesApiClient] against the newly-updated URL + API
     * key. Suspends on Tink crypto init the first time through.
     */
    private val rebuildApiClient: suspend () -> Unit,
) {

    companion object {
        private const val TAG = "ConnectionSwitch"

        /**
         * Max time we wait for the new [AuthManager] to report a stable
         * [AuthState] before deciding whether to connect. Kept short —
         * the only slow path is Keystore init on the very first launch,
         * which well fits inside this budget.
         */
        // Switching to a brand-new (unpaired) connection is the common "Add
        // connection" path; AuthManager's init coroutine finishes without
        // transitioning away from Unpaired, so this timeout IS the happy
        // path there. Keep it short so the UI freeze on add-connection is
        // imperceptible. AuthManager.init reads EncryptedSharedPreferences
        // which takes < 50 ms on a warm device; 500 ms is generous even
        // with cold disk.
        const val AUTH_HYDRATE_TIMEOUT_MS: Long = 500L
    }

    private val switchMutex = Mutex()

    private var streamCancelCallback: (() -> Unit)? = null
    private var voiceStopCallback: (() -> Unit)? = null

    private val _connectionSwitchEvents = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * One-shot events emitted after step 5 of [switchConnection]. The payload
     * is the id of the *new* connection being switched to. Subscribers
     * (notably [ChatViewModel]) use this to wipe per-connection local state.
     */
    val connectionSwitchEvents: SharedFlow<String> = _connectionSwitchEvents.asSharedFlow()

    /**
     * Register a callback that cancels any in-flight HTTP/SSE stream
     * currently held by [ChatViewModel]. Wired from [ui.RelayApp] at
     * composition time.
     */
    fun registerStreamCancelCallback(callback: () -> Unit) {
        streamCancelCallback = callback
    }

    /**
     * Register a callback that stops any active voice turn (recording or
     * TTS playback). Wired from [ui.RelayApp] when a VoiceViewModel is
     * available on the current flavor.
     */
    fun registerVoiceStopCallback(callback: () -> Unit) {
        voiceStopCallback = callback
    }

    /**
     * Tear down the transport half of an active connection WITHOUT
     * rebuilding against a successor — the "removing the last remaining
     * connection" case in [ConnectionViewModel.removeConnection].
     *
     * Runs steps 2-5 of [switchConnection] (cancel stream, stop voice,
     * disconnect WSS, emit switch event) then returns. The emitted event
     * carries an empty payload so subscribers (ChatViewModel, profile
     * resolver) treat it as "no new target" and wipe their per-connection
     * state the same way they would on a regular switch.
     *
     * The caller is responsible for:
     *  - Clearing the app-wide URL flows so the API client probe loop
     *    stops firing against the removed URL.
     *  - Nulling out the API client itself via `rebuildApiClient()` with
     *    blank URLs, which drives the Unreachable/Unknown states on the
     *    status badges.
     *
     * We intentionally do NOT rebuild [AuthManager] here — there's no
     * successor id to bind it to. The previous AuthManager instance stays
     * in memory; its EncryptedSharedPreferences file was just deleted by
     * [ConnectionStore.removeConnection], but that's a storage-side
     * concern (a future write would just re-create the file). Any
     * subsequent `Add connection` flow rebuilds the AuthManager against
     * the new connection's id anyway.
     */
    fun teardownActive(): Job = scope.launch {
        switchMutex.withLock {
            runCatching { streamCancelCallback?.invoke() }
                .onFailure { Log.w(TAG, "streamCancelCallback failed: ${it.message}") }
            runCatching { voiceStopCallback?.invoke() }
                .onFailure { Log.w(TAG, "voiceStopCallback failed: ${it.message}") }
            runCatching { connectionManager.disconnect() }
                .onFailure { Log.w(TAG, "connectionManager.disconnect failed: ${it.message}") }
            // Empty payload signals "no successor". Subscribers that look
            // up per-connection state (profile selection, session
            // history) treat the empty id as a miss and fall back to
            // their null/empty branches, matching the on-switch behavior.
            _connectionSwitchEvents.emit("")
        }
    }

    /**
     * Run the full switch sequence described on the class KDoc. Returns a
     * [Job] so callers can `.join()` in tests; in production the VM fires
     * and forgets.
     */
    fun switchConnection(connectionId: String): Job = scope.launch {
        switchMutex.withLock {
            if (connectionStore.activeConnectionId.value == connectionId) {
                Log.i(TAG, "switchConnection: $connectionId already active — no-op")
                return@withLock
            }

            // 2 + 3 — tear down anything that would keep talking to the old
            // connection's endpoints.
            runCatching { streamCancelCallback?.invoke() }
                .onFailure { Log.w(TAG, "streamCancelCallback failed: ${it.message}") }
            runCatching { voiceStopCallback?.invoke() }
                .onFailure { Log.w(TAG, "voiceStopCallback failed: ${it.message}") }

            // 4 — kill the WSS and its reconnect loop.
            runCatching { connectionManager.disconnect() }
                .onFailure { Log.w(TAG, "connectionManager.disconnect failed: ${it.message}") }

            // 5 — let subscribers wipe local state before we swap backing
            // clients underneath them. Use emit (suspending) rather than
            // tryEmit so a full buffer blocks this coroutine instead of
            // silently dropping the event — losing the switch notification
            // would leak the previous connection's chat history into the new
            // connection.
            _connectionSwitchEvents.emit(connectionId)

            // 6 — look up target. Guard against stale IDs from UI before
            // we start mutating shared state.
            val target = connectionStore.connections.value.firstOrNull { it.id == connectionId }
            if (target == null) {
                Log.w(
                    TAG,
                    "switchConnection: unknown connectionId=$connectionId — aborting switch, " +
                        "active connection unchanged",
                )
                return@withLock
            }

            // 7 — swap the AuthManager. Factory-built instance re-registers
            // itself on the multiplexer in its own init block.
            val newAuth = authManagerFactory(connectionId)
            installAuthManager(newAuth)

            // 8 — point the URL flows at the new connection's endpoints.
            setApiServerUrl(target.apiServerUrl)
            setRelayUrl(target.relayUrl)
            runCatching { persistUrls(target.apiServerUrl, target.relayUrl) }
                .onFailure { Log.w(TAG, "persistUrls failed: ${it.message}") }

            // 9 — rebuild the API client against the new URL + key.
            runCatching { rebuildApiClient() }
                .onFailure { Log.w(TAG, "rebuildApiClient failed: ${it.message}") }

            // 10 — wait briefly for the new AuthManager to hydrate its
            // stored token (if any) so hasPairContext returns the right
            // answer. The authState flow seeds to Unpaired and flips to
            // Paired asynchronously; without this await we'd almost
            // always skip the WSS connect on switch.
            val hydrated = withTimeoutOrNull(AUTH_HYDRATE_TIMEOUT_MS) {
                waitForStableAuth(newAuth.authState)
            }
            if (hydrated == null) {
                Log.i(
                    TAG,
                    "switchConnection: auth hydrate timeout after ${AUTH_HYDRATE_TIMEOUT_MS}ms " +
                        "— relying on current hasPairContext snapshot",
                )
            }

            if (newAuth.hasPairContext) {
                runCatching { connectionManager.connect(target.relayUrl) }
                    .onFailure { Log.w(TAG, "connectionManager.connect failed: ${it.message}") }
            } else {
                Log.i(
                    TAG,
                    "switchConnection: new connection has no pair context — skipping WSS connect " +
                        "(user will pair from the Connection screen)",
                )
            }

            // 11 — persist last so an earlier failure leaves the previous
            // active pointer in place. setActiveConnection updates the
            // connectionStore.activeConnectionId StateFlow synchronously
            // after the DataStore write lands, so the UI reacts without
            // waiting for a recomposition pass.
            runCatching { connectionStore.setActiveConnection(connectionId) }
                .onFailure { Log.w(TAG, "connectionStore.setActiveConnection failed: ${it.message}") }
        }
    }

    /**
     * Suspend until [authState] emits a terminal state — [AuthState.Paired]
     * (stored token hydrated) or [AuthState.Failed] (keystore unreadable /
     * corrupt). Returns the first such emission.
     *
     * AuthManager's init coroutine transitions Unpaired → Paired once the
     * token store is read. If no token exists we stay on Unpaired forever
     * — the caller's [withTimeoutOrNull] wrap is the exit for that case.
     * Accepting Failed as terminal too means a broken keystore unblocks
     * the switch immediately instead of waiting out the full timeout.
     */
    private suspend fun waitForStableAuth(authState: StateFlow<AuthState>): AuthState {
        return authState.first { it is AuthState.Paired || it is AuthState.Failed }
    }

}
