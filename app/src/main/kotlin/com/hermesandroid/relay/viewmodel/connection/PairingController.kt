package com.hermesandroid.relay.viewmodel.connection

import android.content.Context
import com.hermesandroid.relay.auth.PairedDeviceInfo
import com.hermesandroid.relay.data.PairingPreferences
import com.hermesandroid.relay.network.relay.RelayHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the **paired-devices list** (`GET /sessions`) and the **insecure-ack**
 * DataStore flags — the self-contained pairing-management surface of
 * [com.hermesandroid.relay.viewmodel.ConnectionViewModel].
 *
 * Extracted as part of the ConnectionViewModel decomposition (ADR 34 follow-up).
 * This is a pure mechanical lift: the field/method bodies are identical to the
 * originals; the ViewModel keeps its public getters/functions and delegates
 * here. Behavior — including the optimistic local removal in [revokeDevice] and
 * the full-grants-rebuild in [revokeChannelGrant] — is preserved verbatim.
 *
 * The broader pairing *orchestration* (`applyPairingPayload`, which spans the
 * upstream/relay/connection-store wiring) deliberately stays in the ViewModel:
 * it is glue across every other collaborator, not a cohesive unit that moves
 * cleanly behind this seam.
 *
 * Dependencies are injected by reference/scope so this collaborator is testable
 * without standing up an Android `Application` + DataStore + EncryptedPrefs —
 * mirroring the [com.hermesandroid.relay.viewmodel.ConnectionSwitchCoordinator]
 * precedent.
 */
class PairingController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val relayHttpClient: RelayHttpClient,
) {

    // --- Paired devices list (GET /sessions) -------------------------------
    //
    // Loaded on-demand from PairedDevicesScreen. State is held here so the
    // screen can navigate away and come back without re-fetching every time.

    private val _pairedDevices = MutableStateFlow<List<PairedDeviceInfo>>(emptyList())
    val pairedDevices: StateFlow<List<PairedDeviceInfo>> = _pairedDevices.asStateFlow()

    private val _pairedDevicesLoading = MutableStateFlow(false)
    val pairedDevicesLoading: StateFlow<Boolean> = _pairedDevicesLoading.asStateFlow()

    private val _pairedDevicesError = MutableStateFlow<String?>(null)
    val pairedDevicesError: StateFlow<String?> = _pairedDevicesError.asStateFlow()

    /**
     * Fetch the list of paired devices from the relay. Idempotent — safe to
     * call repeatedly (e.g. on screen entry and on pull-to-refresh). Errors
     * surface through [pairedDevicesError]; a partial failure (404 = endpoint
     * not implemented) yields an empty list rather than an error.
     */
    fun loadPairedDevices() {
        scope.launch {
            _pairedDevicesLoading.value = true
            _pairedDevicesError.value = null
            val result = relayHttpClient.listSessions()
            result.fold(
                onSuccess = { list -> _pairedDevices.value = list },
                onFailure = { e ->
                    _pairedDevicesError.value = e.message ?: "Unknown error"
                }
            )
            _pairedDevicesLoading.value = false
        }
    }

    /**
     * Revoke a paired device by its token prefix. Returns `true` on success
     * (and on 404, which we treat as "already gone"). Refreshes the list
     * automatically on success.
     */
    suspend fun revokeDevice(tokenPrefix: String): Boolean {
        val result = relayHttpClient.revokeSession(tokenPrefix)
        return if (result.isSuccess) {
            // Optimistic local removal so the UI updates immediately while
            // the refresh round-trips.
            _pairedDevices.value = _pairedDevices.value.filterNot { it.tokenPrefix == tokenPrefix }
            loadPairedDevices()
            true
        } else {
            _pairedDevicesError.value = result.exceptionOrNull()?.message
            false
        }
    }

    /**
     * Extend (or update) a paired device's session TTL.
     *
     * Backs the "Extend" button on the Paired Devices card. Passes through
     * to [RelayHttpClient.extendSession] and refreshes the list on success
     * so the UI shows the new expiry immediately. Errors surface via
     * [pairedDevicesError] the same way revoke errors do.
     *
     * Grants are intentionally NOT exposed on this path — the MVP UX is
     * "pick a new session duration", and server-side re-clamping ensures
     * existing grants stay inside the (possibly new) session lifetime.
     * Callers that want to edit grants can call [RelayHttpClient.extendSession]
     * directly.
     *
     * @param ttlSeconds new session lifetime in seconds. `0` means "never
     *   expire". Must be non-negative.
     * @return `true` on success, `false` otherwise (error message in
     *   [pairedDevicesError]).
     */
    suspend fun extendDevice(tokenPrefix: String, ttlSeconds: Long): Boolean {
        val result = relayHttpClient.extendSession(tokenPrefix, ttlSeconds = ttlSeconds)
        return if (result.isSuccess) {
            loadPairedDevices()
            true
        } else {
            _pairedDevicesError.value = result.exceptionOrNull()?.message
            false
        }
    }

    // === PER-CHANNEL-REVOKE: revoke a single channel grant on a device ===
    /**
     * Revoke a single per-channel grant on a paired device without touching
     * the rest of the session.
     *
     * The relay's `PATCH /sessions/{prefix}` accepts a `grants` map of
     * `channel → seconds-from-now`. The server-side `_materialize_grants`
     * helper rebuilds the entire grants table from whatever we send (plus
     * the relay's defaults for channels we omit), so we must reconstruct
     * the FULL current grants map and only swap the target channel.
     *
     * Quirks of the relay-side encoding we have to honor:
     *  * `0` means "never expire" (NOT "expired").
     *  * Omitted channels default to `_default_grants`, which would extend
     *    a previously-shorter grant. So we re-send every existing channel
     *    explicitly, converted from absolute-epoch back to seconds-from-now.
     *  * To express "revoked", we send `1` second — the round trip alone
     *    pushes us past the expiry, and `_materialize_grants` clamps the
     *    candidate to the session lifetime so a stale `1` is harmless.
     *
     * @param tokenPrefix the device's token prefix from `PairedDeviceInfo`.
     * @param channel the channel name to revoke (`"chat"`, `"terminal"`,
     *   `"bridge"`, `"tui"`, `"voice:config"`, `"voice:stt"`,
     *   `"voice:tts"`, …).
     * @return `true` on success.
     */
    suspend fun revokeChannelGrant(
        tokenPrefix: String,
        channel: String,
    ): Boolean {
        // Look up the device locally so we can rebuild the full grants
        // map from its current state. If the device list is stale we
        // bail rather than guessing.
        val device = _pairedDevices.value
            .firstOrNull { it.tokenPrefix == tokenPrefix }
            ?: return run {
                _pairedDevicesError.value = "Device not in cache — refresh and retry"
                false
            }

        val nowSec = System.currentTimeMillis() / 1000.0
        val rebuilt: MutableMap<String, Long> = mutableMapOf()
        for ((existingChannel, expiryEpoch) in device.grants) {
            if (existingChannel == channel) {
                // Target channel — encode as expired (1s from now → past
                // by the time the relay processes the PATCH).
                rebuilt[existingChannel] = 1L
            } else if (expiryEpoch == null) {
                // Existing "never expire" grant — preserve via 0.
                rebuilt[existingChannel] = 0L
            } else {
                // Existing capped grant — convert absolute epoch to
                // seconds-from-now, clamped to >= 1 so the relay
                // accepts it as a valid non-negative integer.
                val secsFromNow = (expiryEpoch - nowSec).toLong().coerceAtLeast(1L)
                rebuilt[existingChannel] = secsFromNow
            }
        }
        if (rebuilt.isEmpty()) {
            _pairedDevicesError.value = "Device has no grants to revoke"
            return false
        }
        if (channel !in rebuilt) {
            // The channel wasn't in the device's grant table — nothing
            // to do. Treat as success so the UI updates cleanly.
            return true
        }

        val result = relayHttpClient.extendSession(
            tokenPrefix = tokenPrefix,
            ttlSeconds = null,
            grants = rebuilt,
        )
        return if (result.isSuccess) {
            loadPairedDevices()
            true
        } else {
            _pairedDevicesError.value = result.exceptionOrNull()?.message
            false
        }
    }
    // === END PER-CHANNEL-REVOKE ===

    // --- Insecure-ack helpers ---------------------------------------------

    /**
     * DataStore-backed flow of whether the user has acknowledged the
     * [com.hermesandroid.relay.ui.components.InsecureConnectionAckDialog].
     */
    val insecureAckSeen: StateFlow<Boolean> =
        PairingPreferences.insecureAckSeen(context)
            .stateIn(scope, SharingStarted.Eagerly, false)

    val insecureReason: StateFlow<String> =
        PairingPreferences.insecureReason(context)
            .stateIn(scope, SharingStarted.Eagerly, "")

    fun setInsecureAckComplete(reason: String) {
        scope.launch {
            PairingPreferences.setInsecureAckSeen(context, true)
            PairingPreferences.setInsecureReason(context, reason)
        }
    }
}
