package com.hermesandroid.relay.auth

import android.content.Context
import com.hermesandroid.relay.data.PairingPreferences
import com.hermesandroid.relay.network.ChannelMultiplexer
import com.hermesandroid.relay.network.models.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

sealed class AuthState {
    data object Unpaired : AuthState()
    data object Pairing : AuthState()
    data class Paired(val token: String) : AuthState()
    data class Failed(val reason: String) : AuthState()
}

/**
 * Orchestrates pairing + session token lifecycle for the relay channel.
 *
 * **Security overhaul (2026-04-11):**
 *
 *  - Token storage is abstracted behind [SessionTokenStore]. On first run
 *    we try [KeystoreTokenStore] (StrongBox-preferred) and fall back to
 *    [LegacyEncryptedPrefsTokenStore] when Keystore init fails. Existing
 *    tokens are migrated one-shot from the legacy prefs file on first
 *    launch so users don't need to re-pair after the upgrade.
 *
 *  - TTL + grants + transport_hint are now parsed from the server's
 *    `auth.ok` payload and exposed via [currentPairedSession] so the UI can
 *    display expiry badges and per-channel grant chips.
 *
 *  - The next [authenticate] call sends a user-selected `ttl_seconds` from
 *    the [SessionTtlPickerDialog]. See [setPendingTtlSeconds].
 *
 *  - TOFU cert pinning lives in [CertPinStore]; re-pair flows call
 *    [CertPinStore.removePinFor] to reset trust for the target host.
 */
class AuthManager(
    private val context: Context,
    private val multiplexer: ChannelMultiplexer,
    private val scope: CoroutineScope
) : ChannelMultiplexer.ChannelHandler {

    companion object {
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_API_KEY = "api_server_key"
        private const val KEY_PAIRED_META = "paired_session_meta_json"
        private const val PAIRING_CODE_LENGTH = 6
        private val PAIRING_CODE_CHARS = ('A'..'Z') + ('0'..'9')
    }

    private val json = Json { ignoreUnknownKeys = true }

    // --- Token store (new, hardware-backed preferred) -----------------------

    private var _store: SessionTokenStore? = null
    private val storeMutex = Mutex()

    /**
     * Lazily construct the best available token store. First tries
     * [KeystoreTokenStore] — if that fails on broken OEM keystores we fall
     * back to [LegacyEncryptedPrefsTokenStore]. The chosen store is cached
     * for the lifetime of this manager.
     *
     * After picking a store we run [migrateFromLegacyIfNeeded] once so any
     * existing session token lands in the new location without forcing the
     * user to re-pair.
     */
    private suspend fun store(): SessionTokenStore {
        _store?.let { return it }
        return storeMutex.withLock {
            _store?.let { return it }
            withContext(Dispatchers.IO) {
                val picked: SessionTokenStore =
                    KeystoreTokenStore.tryCreate(context) ?: LegacyEncryptedPrefsTokenStore(context)
                migrateFromLegacyIfNeeded(picked)
                _store = picked
                picked
            }
        }
    }

    /**
     * One-shot migration: copy session token + device ID + API key from the
     * legacy [EncryptedSharedPreferences] file into the new store, then
     * clear the legacy file. No-op when [picked] is itself the legacy store
     * (nothing to migrate from — they're the same file).
     */
    private fun migrateFromLegacyIfNeeded(picked: SessionTokenStore) {
        if (picked is LegacyEncryptedPrefsTokenStore) return
        val legacy = try {
            LegacyEncryptedPrefsTokenStore(context)
        } catch (_: Exception) {
            return
        }

        val keysToMigrate = listOf(KEY_SESSION_TOKEN, KEY_DEVICE_ID, KEY_API_KEY, KEY_PAIRED_META)
        var migrated = false
        for (k in keysToMigrate) {
            val existing = legacy.getString(k) ?: continue
            if (!picked.contains(k)) {
                picked.putString(k, existing)
                migrated = true
            }
        }
        if (migrated) {
            // Wipe the legacy file so we don't keep cleartext-equivalent
            // backup copies of the session token lying around.
            legacy.clearAll()
        }
    }

    /** Cert pin store — shared across all relay connections. */
    val certPinStore: CertPinStore = CertPinStore(context)

    /** True when the resolved token store reports StrongBox-backed storage. */
    val hasHardwareBackedStorage: Boolean
        get() = _store?.hasHardwareBackedStorage ?: false

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unpaired)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _pairingCode = MutableStateFlow(generatePairingCode())
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

    /**
     * Snapshot of the current paired session metadata — token, expiry,
     * grants, transport hint. `null` when unpaired. Exposed to the UI via
     * [com.hermesandroid.relay.viewmodel.ConnectionViewModel.currentPairedSession].
     */
    private val _currentPairedSession = MutableStateFlow<PairedSession?>(null)
    val currentPairedSession: StateFlow<PairedSession?> = _currentPairedSession.asStateFlow()

    /**
     * When non-null, the next [authenticate] call sends this code instead
     * of [_pairingCode]. Populated via [applyServerIssuedCode] when the
     * user scans a QR that carries a server-issued relay pairing code.
     *
     * Cleared automatically after a successful `auth.ok` so we fall back
     * to the locally-generated code (which stays as the trust anchor for
     * the phone → server direction used by the Phase 3 bridge channel).
     */
    private var serverIssuedCode: String? = null

    /**
     * Read-only view of whether this AuthManager currently has something to
     * say in an auth envelope — either a pending server-issued pair code
     * from a fresh QR scan or a stored session token (via [authState]).
     *
     * Used by [com.hermesandroid.relay.viewmodel.ConnectionViewModel.connectRelay]
     * to gate WSS connect attempts. Without a pair context, firing the WSS
     * handshake just sends a doomed auth envelope, which makes the relay
     * tick its rate limiter 5 times in 60 seconds and then block the IP
     * for 5 minutes — exactly the scenario that bit us on the first cycle
     * of the inbound media test.
     *
     * Returns true if **any** of:
     *  1. `authState` is [AuthState.Paired] (we have a session token)
     *  2. `authState` is [AuthState.Pairing] (mid-handshake)
     *  3. [serverIssuedCode] is non-null (fresh QR scan just landed, about
     *     to authenticate)
     *
     * Does NOT consider the locally-generated [_pairingCode] as a valid
     * context — that code is for the Phase 3 phone→host direction and is
     * NOT registered on the relay side, so sending it yields guaranteed
     * auth failures.
     */
    val hasPairContext: Boolean
        get() = _authState.value is AuthState.Paired ||
            _authState.value is AuthState.Pairing ||
            serverIssuedCode != null

    /**
     * TTL the user picked at the [SessionTtlPickerDialog]. Included in the
     * next auth envelope's payload as `ttl_seconds`. `null` means "use
     * server default" (equivalent to the pre-overhaul behavior — the
     * server's own policy kicks in). `0` means "never expire".
     */
    private var pendingTtlSeconds: Long? = null

    /**
     * Optional per-channel grant preferences from the QR. If set, included
     * in the auth envelope so the server can issue shorter-lived tokens for
     * specific channels. Null means "use the server's default for each
     * channel".
     */
    private var pendingGrants: Map<String, Long>? = null

    private val _profiles = MutableStateFlow<List<String>>(emptyList())
    val profiles: StateFlow<List<String>> = _profiles.asStateFlow()

    /**
     * Whether an API key is currently stored. Updated reactively by
     * [setApiKey] / [clearApiKey] so composables can show "API key: already
     * set — leave blank to keep" without suspending.
     */
    private val _apiKeyPresent = MutableStateFlow(false)
    val apiKeyPresent: StateFlow<Boolean> = _apiKeyPresent.asStateFlow()

    init {
        // Register as system channel handler for auth messages
        multiplexer.registerHandler("system", this)

        // Check for existing session token off main thread
        scope.launch {
            val s = store()
            val existingToken = s.getString(KEY_SESSION_TOKEN)
            if (existingToken != null) {
                _authState.value = AuthState.Paired(existingToken)
                _currentPairedSession.value = loadStoredMetadata(existingToken)
            }
            _apiKeyPresent.value = !s.getString(KEY_API_KEY).isNullOrBlank()
        }
    }

    /**
     * Re-hydrate a [PairedSession] snapshot from the JSON blob we persisted
     * alongside the session token. Returns a minimal fallback when metadata
     * is missing (old installs without the blob).
     */
    private suspend fun loadStoredMetadata(token: String): PairedSession {
        val s = store()
        val rawMeta = s.getString(KEY_PAIRED_META)
        val now = System.currentTimeMillis() / 1000L
        val defaults = PairedSession(
            token = token,
            deviceName = android.os.Build.MODEL,
            expiresAt = null,
            grants = emptyMap(),
            transportHint = null,
            firstSeen = now,
            hasHardwareStorage = s.hasHardwareBackedStorage,
        )
        if (rawMeta.isNullOrBlank()) return defaults

        return try {
            val obj = json.decodeFromString<JsonObject>(rawMeta)
            val expiresAt = obj["expires_at"]?.jsonPrimitive?.longOrNull
                ?: obj["expires_at"]?.jsonPrimitive?.doubleOrNull?.toLong()
            val grants = obj["grants"]?.jsonObject?.mapValues { (_, v) ->
                (v as? JsonPrimitive)?.longOrNull
                    ?: (v as? JsonPrimitive)?.doubleOrNull?.toLong()
            } ?: emptyMap()
            val transportHint = obj["transport_hint"]?.jsonPrimitive?.contentOrNull
            val firstSeen = obj["first_seen"]?.jsonPrimitive?.longOrNull ?: now
            val deviceName = obj["device_name"]?.jsonPrimitive?.contentOrNull
                ?: android.os.Build.MODEL

            PairedSession(
                token = token,
                deviceName = deviceName,
                expiresAt = expiresAt,
                grants = grants,
                transportHint = transportHint,
                firstSeen = firstSeen,
                hasHardwareStorage = s.hasHardwareBackedStorage,
            )
        } catch (_: Exception) {
            defaults
        }
    }

    private suspend fun persistPairedSession(session: PairedSession) {
        val s = store()
        val meta = buildJsonObject {
            put("device_name", session.deviceName)
            session.expiresAt?.let { put("expires_at", it) }
            put("transport_hint", session.transportHint ?: "")
            put("first_seen", session.firstSeen)
            val grantsObj = buildJsonObject {
                for ((k, v) in session.grants) {
                    if (v != null) put(k, v)
                }
            }
            put("grants", grantsObj)
        }
        s.putString(KEY_PAIRED_META, json.encodeToString(JsonObject.serializer(), meta))
    }

    private suspend fun getDeviceId(): String {
        val s = store()
        val existing = s.getString(KEY_DEVICE_ID)
        if (existing != null) return existing
        val newId = java.util.UUID.randomUUID().toString()
        s.putString(KEY_DEVICE_ID, newId)
        return newId
    }

    /**
     * Public accessor for the stable device ID. Used by [TerminalViewModel]
     * to compose deterministic per-tab `session_name` values
     * (`hermes-<deviceId>-tabN`) so the relay's tmux-backed terminal channel
     * can re-attach the same shell across reconnects.
     */
    suspend fun getOrCreateDeviceId(): String = getDeviceId()

    /**
     * Set the TTL the user picked at [SessionTtlPickerDialog]. `0` → never,
     * `null` → defer to server default. Persisted across [authenticate]
     * calls and mirrored into [PairingPreferences] for the next pair's
     * preselection default.
     */
    fun setPendingTtlSeconds(ttlSeconds: Long?) {
        pendingTtlSeconds = ttlSeconds
        if (ttlSeconds != null) {
            scope.launch {
                PairingPreferences.setPairTtlSeconds(context, ttlSeconds)
            }
        }
    }

    fun setPendingGrants(grants: Map<String, Long>?) {
        pendingGrants = grants
    }

    /**
     * Send auth envelope when connection is established.
     *
     * Pairing-code precedence when unpaired:
     *   1. [serverIssuedCode] if set — comes from a scanned QR. This is the
     *      canonical path: operator runs `hermes pair` on the host, which
     *      pre-registers the code with the running relay, then renders it
     *      into the QR. The phone scans once and consumes it here.
     *   2. [_pairingCode] (locally-generated). Fallback for manual setups
     *      and Phase 3's bridge direction (phone-issues-code, host-approves).
     *
     * Pending TTL + grants are serialized into the auth payload when set,
     * letting the [SessionTtlPickerDialog] selection flow through to the
     * server.
     */
    fun authenticate(ttlSeconds: Long? = null) {
        if (ttlSeconds != null) pendingTtlSeconds = ttlSeconds
        scope.launch {
            val currentState = _authState.value
            val deviceId = getDeviceId()
            val payload = when (currentState) {
                is AuthState.Paired -> {
                    buildJsonObject {
                        put("session_token", currentState.token)
                        put("device_id", deviceId)
                        put("device_name", android.os.Build.MODEL)
                    }
                }
                else -> {
                    _authState.value = AuthState.Pairing
                    val codeToSend = serverIssuedCode ?: _pairingCode.value
                    buildJsonObject {
                        put("pairing_code", codeToSend)
                        put("device_id", deviceId)
                        put("device_name", android.os.Build.MODEL)
                        pendingTtlSeconds?.let { put("ttl_seconds", it) }
                        pendingGrants?.let { grants ->
                            val obj = buildJsonObject {
                                for ((k, v) in grants) put(k, v)
                            }
                            put("grants", obj)
                        }
                    }
                }
            }

            multiplexer.send(
                Envelope(
                    channel = "system",
                    type = "auth",
                    payload = payload
                )
            )
        }
    }

    /**
     * Store a server-issued pairing code (from a scanned QR) for use on the
     * next [authenticate] call. Overrides the locally-generated code; cleared
     * automatically on successful `auth.ok`.
     *
     * Also mirrors the code into [_pairingCode] so any UI that displays the
     * code shows the one actually being used — avoids a confusing "QR said
     * ABCD12 but the app shows XYZ123" gap during the pairing moment.
     */
    fun applyServerIssuedCode(code: String) {
        val normalized = code.trim().uppercase()
        if (normalized.isEmpty()) return
        serverIssuedCode = normalized
        _pairingCode.value = normalized
    }

    /**
     * Apply a server-issued code AND wipe any existing session token in one
     * atomic step. Used by the manual-code entry dialog and by the QR flow
     * whenever the user re-pairs.
     *
     * Also wipes the TOFU cert pin for the target relay host (if provided)
     * so the next wss handshake re-TOFUs against whatever cert is currently
     * being presented. Without this step, a legit cert rotation looks
     * identical to a MITM attack.
     */
    fun applyServerIssuedCodeAndReset(code: String, relayUrl: String? = null) {
        val normalized = code.trim().uppercase()
        if (normalized.isEmpty()) return
        serverIssuedCode = normalized
        _pairingCode.value = normalized
        _authState.value = AuthState.Unpaired
        _currentPairedSession.value = null
        scope.launch {
            val s = store()
            s.remove(KEY_SESSION_TOKEN)
            s.remove(KEY_PAIRED_META)
            if (relayUrl != null) {
                certPinStore.removePinFor(relayUrl)
            }
        }
    }

    override fun onMessage(envelope: Envelope) {
        when (envelope.type) {
            "auth.ok" -> handleAuthOk(envelope)
            "auth.fail" -> handleAuthFail(envelope)
        }
    }

    fun regeneratePairingCode() {
        _pairingCode.value = generatePairingCode()
    }

    fun clearSession() {
        scope.launch {
            val s = store()
            s.remove(KEY_SESSION_TOKEN)
            s.remove(KEY_PAIRED_META)
            _authState.value = AuthState.Unpaired
            _currentPairedSession.value = null
            _pairingCode.value = generatePairingCode()
        }
    }

    // --- API Key storage (for direct Hermes API Server auth) ---

    suspend fun getApiKey(): String? = store().getString(KEY_API_KEY)

    suspend fun setApiKey(key: String) {
        val trimmed = key.trim()
        val s = store()
        if (trimmed.isBlank()) {
            s.remove(KEY_API_KEY)
            _apiKeyPresent.value = false
        } else {
            s.putString(KEY_API_KEY, trimmed)
            _apiKeyPresent.value = true
        }
    }

    suspend fun clearApiKey() {
        store().remove(KEY_API_KEY)
        _apiKeyPresent.value = false
    }

    val isPaired: Boolean
        get() = _authState.value is AuthState.Paired

    private fun handleAuthOk(envelope: Envelope) {
        scope.launch {
            try {
                val payload = envelope.payload
                val token = payload["session_token"]?.jsonPrimitive?.contentOrNull

                if (token != null) {
                    val s = store()
                    s.putString(KEY_SESSION_TOKEN, token)
                    _authState.value = AuthState.Paired(token)
                    // Server-issued code is one-shot — drop it once the
                    // upgrade to a long-lived session token has landed.
                    serverIssuedCode = null

                    // --- Parse new security fields -------------------------
                    //
                    // expires_at: epoch seconds or null (never expires).
                    // Accepts both integer and float representations —
                    // Python servers often return time.time() which is a
                    // float. We go through jsonPrimitive so `null` literal
                    // in the payload surfaces as a null Long here.
                    val expiresAtElem = payload["expires_at"]
                    val expiresAt: Long? = expiresAtElem?.jsonPrimitive?.let { prim ->
                        prim.longOrNull ?: prim.doubleOrNull?.toLong()
                    }

                    // grants: map of channel name → epoch seconds | null
                    val grantsObj = payload["grants"] as? JsonObject
                    val grantsMap: Map<String, Long?> = grantsObj
                        ?.mapValues { (_, v) ->
                            val prim = v as? JsonPrimitive
                            prim?.longOrNull ?: prim?.doubleOrNull?.toLong()
                        }
                        ?: emptyMap()

                    val transportHint = payload["transport_hint"]
                        ?.jsonPrimitive?.contentOrNull

                    val paired = PairedSession(
                        token = token,
                        deviceName = android.os.Build.MODEL,
                        expiresAt = expiresAt,
                        grants = grantsMap,
                        transportHint = transportHint,
                        firstSeen = System.currentTimeMillis() / 1000L,
                        hasHardwareStorage = s.hasHardwareBackedStorage,
                    )
                    _currentPairedSession.value = paired
                    persistPairedSession(paired)

                    // Pending TTL/grants are consumed — the server has
                    // either honored or overridden them and the next
                    // auth round-trip should not resend stale values.
                    pendingTtlSeconds = null
                    pendingGrants = null
                }

                val profilesArray = payload["profiles"]?.jsonArray
                if (profilesArray != null) {
                    _profiles.value = profilesArray.map { it.jsonPrimitive.content }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleAuthFail(envelope: Envelope) {
        try {
            val reason = envelope.payload["reason"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
            _authState.value = AuthState.Failed(reason)
        } catch (e: Exception) {
            _authState.value = AuthState.Failed("Authentication failed")
        }
    }

    private fun generatePairingCode(): String {
        return (1..PAIRING_CODE_LENGTH)
            .map { PAIRING_CODE_CHARS.random() }
            .joinToString("")
    }
}
