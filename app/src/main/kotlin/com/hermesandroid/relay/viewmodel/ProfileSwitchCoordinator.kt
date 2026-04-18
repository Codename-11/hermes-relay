package com.hermesandroid.relay.viewmodel

import android.util.Log
import com.hermesandroid.relay.auth.AuthManager
import com.hermesandroid.relay.auth.AuthState
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.data.ProfileStore
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
 * between Hermes connection profiles. Extracted out of
 * [ConnectionViewModel] so the swap sequence is testable without having to
 * stand up an Android [android.app.Application] + DataStore + EncryptedPrefs
 * + MediaProjection + ... — every collaborator here is either an interface
 * or a factory, which MockK / plain fakes can substitute trivially.
 *
 * Sequence (must happen in order):
 *
 *  1. Guard — no-op if the requested id equals [ProfileStore.activeProfileId].
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
 *     an auth envelope with the old profile's session token against the
 *     new profile's relay.
 *  5. Emit a [profileSwitchEvents] event with the new profile id so
 *     [ChatViewModel] (and any other profile-scoped holder) can wipe local
 *     state — messages, session id, queued sends — before the rebuilt
 *     clients start serving the new profile.
 *  6. Look up the new [Profile] in [profileStore]. If the id is unknown
 *     we log + abort (no state mutation); a stale UI reference is safer
 *     than proceeding with a half-switched context.
 *  7. Rebuild [AuthManager] via [authManagerFactory] bound to the new
 *     profile id. The new AuthManager's init block re-registers itself on
 *     [multiplexer] as the "system" channel handler, which transparently
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
 * 11. Persist the new active-profile id via [ProfileStore.setActiveProfile]
 *     last so an early failure leaves the previous active profile in place.
 *
 * The whole sequence runs under [switchMutex] so rapid-fire switches from
 * the UI queue cleanly instead of interleaving partial teardowns.
 */
class ProfileSwitchCoordinator(
    private val profileStore: ProfileStore,
    private val connectionManager: ConnectionManager,
    private val scope: CoroutineScope,
    /**
     * Factory that constructs a brand-new [AuthManager] bound to a given
     * profile id. Called once per successful switch. The returned instance
     * must have already registered itself on [multiplexer] — AuthManager's
     * init block handles that via `multiplexer.registerHandler("system", this)`.
     */
    private val authManagerFactory: (profileId: String) -> AuthManager,
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
        private const val TAG = "ProfileSwitch"

        /**
         * Max time we wait for the new [AuthManager] to report a stable
         * [AuthState] before deciding whether to connect. Kept short —
         * the only slow path is Keystore init on the very first launch,
         * which well fits inside this budget.
         */
        // Switching to a brand-new (unpaired) profile is the common "Add
        // profile" path; AuthManager's init coroutine finishes without
        // transitioning away from Unpaired, so this timeout IS the happy
        // path there. Keep it short so the UI freeze on add-profile is
        // imperceptible. AuthManager.init reads EncryptedSharedPreferences
        // which takes < 50 ms on a warm device; 500 ms is generous even
        // with cold disk.
        const val AUTH_HYDRATE_TIMEOUT_MS: Long = 500L
    }

    private val switchMutex = Mutex()

    private var streamCancelCallback: (() -> Unit)? = null
    private var voiceStopCallback: (() -> Unit)? = null

    private val _profileSwitchEvents = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * One-shot events emitted after step 5 of [switchProfile]. The payload
     * is the id of the *new* profile being switched to. Subscribers
     * (notably [ChatViewModel]) use this to wipe per-profile local state.
     */
    val profileSwitchEvents: SharedFlow<String> = _profileSwitchEvents.asSharedFlow()

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
     * Run the full switch sequence described on the class KDoc. Returns a
     * [Job] so callers can `.join()` in tests; in production the VM fires
     * and forgets.
     */
    fun switchProfile(profileId: String): Job = scope.launch {
        switchMutex.withLock {
            if (profileStore.activeProfileId.value == profileId) {
                Log.i(TAG, "switchProfile: $profileId already active — no-op")
                return@withLock
            }

            // 2 + 3 — tear down anything that would keep talking to the old
            // profile's endpoints.
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
            // would leak the previous profile's chat history into the new
            // profile.
            _profileSwitchEvents.emit(profileId)

            // 6 — look up target. Guard against stale IDs from UI before
            // we start mutating shared state.
            val target = profileStore.profiles.value.firstOrNull { it.id == profileId }
            if (target == null) {
                Log.w(
                    TAG,
                    "switchProfile: unknown profileId=$profileId — aborting switch, " +
                        "active profile unchanged",
                )
                return@withLock
            }

            // 7 — swap the AuthManager. Factory-built instance re-registers
            // itself on the multiplexer in its own init block.
            val newAuth = authManagerFactory(profileId)
            installAuthManager(newAuth)

            // 8 — point the URL flows at the new profile's endpoints.
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
                    "switchProfile: auth hydrate timeout after ${AUTH_HYDRATE_TIMEOUT_MS}ms " +
                        "— relying on current hasPairContext snapshot",
                )
            }

            if (newAuth.hasPairContext) {
                runCatching { connectionManager.connect(target.relayUrl) }
                    .onFailure { Log.w(TAG, "connectionManager.connect failed: ${it.message}") }
            } else {
                Log.i(
                    TAG,
                    "switchProfile: new profile has no pair context — skipping WSS connect " +
                        "(user will pair from the Connection screen)",
                )
            }

            // 11 — persist last so an earlier failure leaves the previous
            // active pointer in place. setActiveProfile updates the
            // profileStore.activeProfileId StateFlow synchronously after
            // the DataStore write lands, so the UI reacts without waiting
            // for a recomposition pass.
            runCatching { profileStore.setActiveProfile(profileId) }
                .onFailure { Log.w(TAG, "profileStore.setActiveProfile failed: ${it.message}") }
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
