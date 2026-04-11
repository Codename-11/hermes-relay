package com.hermesandroid.relay.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Snapshot of the currently-paired session held by [AuthManager].
 *
 * Populated from the server's `auth.ok` payload after a successful pair
 * and persisted alongside the session token. Exposed to the UI via
 * [AuthManager.currentPairedSession] so screens can display expiry, grants,
 * and transport posture without reaching into [android.content.SharedPreferences].
 *
 * @property token the long-lived relay session token. Same value carried in
 *           the legacy `AuthState.Paired(token)`.
 * @property deviceName the human-readable device name the phone sent during
 *           pairing. Mirrored back here for UI display.
 * @property expiresAt epoch seconds at which the server says the session
 *           expires — or `null` when the user chose "never expire" at the
 *           TTL picker. `null` is a first-class value, not a missing field.
 * @property grants per-channel expiry map. Keys: `"chat"`, `"terminal"`,
 *           `"bridge"` (and any future channel the server adds). Values are
 *           epoch seconds or `null` for "never". Missing channels = server
 *           didn't grant that channel to this device.
 * @property transportHint the transport the server advises the phone to
 *           use, for UX labeling only. `"wss"` / `"ws"` / `null` when the
 *           server didn't provide a hint.
 * @property firstSeen epoch seconds of when this session record was created
 *           locally (from `System.currentTimeMillis() / 1000`).
 * @property hasHardwareStorage whether the [SessionTokenStore] that persists
 *           the token reports StrongBox-backed storage. Drives the 🛡 badge
 *           in the paired devices screen.
 */
data class PairedSession(
    val token: String,
    val deviceName: String,
    val expiresAt: Long?,
    val grants: Map<String, Long?>,
    val transportHint: String?,
    val firstSeen: Long,
    val hasHardwareStorage: Boolean,
) {
    /** True when the session has no expiry — the user picked "Never" at pair time. */
    val neverExpires: Boolean get() = expiresAt == null

    /**
     * True when the server-issued `expires_at` is in the past. Computed on
     * read (not stored) so the UI can render "Expired" bands the moment the
     * clock rolls over without needing an observer.
     */
    fun isExpired(nowSeconds: Long = System.currentTimeMillis() / 1000L): Boolean {
        val expiry = expiresAt ?: return false
        return expiry < nowSeconds
    }
}

/**
 * A single paired device as returned by the relay's `GET /sessions` endpoint.
 *
 * One phone = one entry. When the phone is looking at its own entry,
 * [isCurrent] is true. Used by [PairedDevicesScreen] to render revocable
 * device cards.
 *
 * Wire contract: matches the sibling Python agent's response shape. Extra
 * fields are tolerated via `ignoreUnknownKeys = true` so the server can grow
 * the shape without breaking existing phones.
 */
@Serializable
data class PairedDeviceInfo(
    @SerialName("token_prefix")
    val tokenPrefix: String,
    @SerialName("device_name")
    val deviceName: String = "",
    @SerialName("device_id")
    val deviceId: String = "",
    @SerialName("created_at")
    val createdAt: Double? = null,
    @SerialName("last_seen")
    val lastSeen: Double? = null,
    @SerialName("expires_at")
    val expiresAt: Double? = null,
    val grants: Map<String, Double?> = emptyMap(),
    @SerialName("transport_hint")
    val transportHint: String? = null,
    @SerialName("is_current")
    val isCurrent: Boolean = false,
)
