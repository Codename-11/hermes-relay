package com.hermesandroid.relay.auth

import android.content.Context
import android.util.Base64
import android.util.Log
import com.hermesandroid.relay.data.PairingPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.CertificatePinner
import java.net.URI
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.X509Certificate

/**
 * Trust-on-first-use (TOFU) certificate pinning store.
 *
 * Records the SHA-256 fingerprint of the peer certificate the first time we
 * successfully connect over wss to a given host:port. Subsequent connects
 * build an OkHttp [CertificatePinner] with the stored pin. If the cert
 * changes, OkHttp will refuse the connection and our reconnect loop will
 * surface it as a loud error.
 *
 * Scope and limitations:
 *  - Only applies to `wss://` — `ws://` is unencrypted, pinning is moot.
 *  - Pin format is OkHttp's `sha256/<base64>` — matches what
 *    [CertificatePinner.pin] expects.
 *  - Pins are keyed by `host:port` (lowercase). If the user re-pairs via QR
 *    we wipe the pin for that host via [removePinFor] so the next connect
 *    re-TOFUs against the new cert.
 *  - Wildcards and SAN matching are NOT supported — only exact host matches.
 *    Relay operators running a single host per port is the common case.
 *
 * Storage lives in DataStore via [PairingPreferences.setTofuPin].
 */
class CertPinStore(private val context: Context) {

    companion object {
        private const val TAG = "CertPinStore"

        /**
         * Compute the OkHttp-compatible `sha256/<base64>` pin string for an
         * [X509Certificate]. Mirrors [CertificatePinner.pin] internally.
         */
        fun fingerprint(cert: X509Certificate): String {
            val spki = cert.publicKey.encoded
            val digest = MessageDigest.getInstance("SHA-256").digest(spki)
            val b64 = Base64.encodeToString(digest, Base64.NO_WRAP)
            return "sha256/$b64"
        }

        /** Extract `host:port` from a ws/wss URL. Returns null on malformed input. */
        fun hostPortFromUrl(wsUrl: String): String? {
            return try {
                val uri = URI(wsUrl.trim())
                val host = uri.host ?: return null
                val port = when {
                    uri.port > 0 -> uri.port
                    uri.scheme.equals("wss", ignoreCase = true) -> 443
                    uri.scheme.equals("https", ignoreCase = true) -> 443
                    else -> 80
                }
                "${host.lowercase()}:$port"
            } catch (_: Exception) {
                null
            }
        }

        /** True when the URL is secure (wss/https) and pinning is meaningful. */
        fun isPinnableUrl(url: String): Boolean {
            val trimmed = url.trim().lowercase()
            return trimmed.startsWith("wss://") || trimmed.startsWith("https://")
        }
    }

    /**
     * Snapshot of all stored pins. Blocks on the DataStore Flow — caller
     * should be off the main thread. Used by [buildPinnerSnapshot] which is
     * called from OkHttp builder setup.
     */
    private fun getPinsBlocking(): Map<String, String> =
        runBlocking { PairingPreferences.getTofuPins(context) }

    /**
     * Build an OkHttp [CertificatePinner] from the current pin store. Empty
     * pin store → returns [CertificatePinner.DEFAULT], which allows any cert
     * (so first-time TOFU still works — we record on first successful connect).
     */
    fun buildPinnerSnapshot(): CertificatePinner {
        val pins = getPinsBlocking()
        if (pins.isEmpty()) return CertificatePinner.DEFAULT
        val builder = CertificatePinner.Builder()
        for ((hostPort, pin) in pins) {
            val host = hostPort.substringBefore(':')
            builder.add(host, pin)
        }
        return builder.build()
    }

    /**
     * Record a pin for a host. Called from the WebSocket listener's `onOpen`
     * when we have a successful connection and can read the peer certs from
     * the response handshake. Idempotent — if the pin already matches, we
     * simply overwrite with the same value.
     *
     * If there's already a pin for this host and the new fingerprint differs,
     * this logs a loud warning but still overwrites. The intended safe path
     * for cert rotation is a re-pair through the QR flow, which calls
     * [removePinFor] before the next connect.
     */
    suspend fun recordPinIfAbsent(url: String, peerCerts: List<Certificate>) {
        if (!isPinnableUrl(url)) return
        val hostPort = hostPortFromUrl(url) ?: return
        val leaf = peerCerts.firstOrNull() as? X509Certificate ?: return

        val newPin = fingerprint(leaf)
        val existing = PairingPreferences.getTofuPins(context)[hostPort]

        if (existing == null) {
            Log.i(TAG, "TOFU: recording new pin for $hostPort -> $newPin")
            PairingPreferences.setTofuPin(context, hostPort, newPin)
        } else if (existing != newPin) {
            // If a mismatched pin survives into this code path, something
            // bypassed the OkHttp CertificatePinner check. Log it loudly so
            // it shows up in bug reports. Keep the existing pin — do NOT
            // silently overwrite.
            Log.e(
                TAG,
                "TOFU: pin MISMATCH for $hostPort (stored=$existing, peer=$newPin). " +
                    "Keeping stored pin. Re-pair to accept the new certificate."
            )
        }
    }

    /**
     * Wipe the stored pin for a host. Called when the user explicitly re-pairs
     * from a QR — the new pairing is an implicit trust-reset.
     */
    suspend fun removePinFor(url: String) {
        val hostPort = hostPortFromUrl(url) ?: return
        Log.i(TAG, "TOFU: removing pin for $hostPort (re-pair)")
        PairingPreferences.removeTofuPin(context, hostPort)
    }

    /**
     * Snapshot the list of currently pinned hosts. Suspending version for UI
     * use — backs [PairedDevicesScreen]'s security badge hints.
     */
    suspend fun listPinnedHosts(): List<String> =
        PairingPreferences.getTofuPins(context).keys.toList()
}
