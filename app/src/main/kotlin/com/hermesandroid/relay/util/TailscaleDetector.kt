package com.hermesandroid.relay.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI

/**
 * Informational detector for whether the device appears to be on a Tailscale
 * network.
 *
 * **Bailey's explicit call:** the result is *informational only*. It does NOT
 * change any default TTL, does NOT auto-flip insecure mode, does NOT gate
 * anything. It only powers a "Tailscale detected" chip in the Connection
 * section and a small helper line in the TTL picker dialog.
 *
 * Detection uses two cheap signals:
 *
 *  1. **Interface scan.** Walks [NetworkInterface.getNetworkInterfaces] for
 *     any interface literally named `tailscale0` or any interface holding an
 *     address in the Tailscale CGNAT range (100.64.0.0/10). This catches
 *     both stock Tailscale on devices that expose the tun interface and
 *     MagicDNS-routed traffic where the tun is named differently on some
 *     OEMs.
 *
 *  2. **Relay URL hostname.** Parses the configured relay URL host and
 *     checks whether it contains `.ts.net` (the Tailscale MagicDNS suffix)
 *     or resolves (best-effort, quick) to a 100.* address.
 *
 * [refresh] is called once at construction and again on network-change
 * broadcasts via [ConnectivityManager.NetworkCallback]. The detector exposes
 * [isTailscaleDetected] as a [StateFlow] the UI can collect.
 *
 * @param relayUrlProvider returns the currently-configured relay URL (or null
 *        when no relay is set) — the detector re-reads this on every refresh.
 */
class TailscaleDetector(
    private val context: Context,
    private val scope: CoroutineScope,
    private val relayUrlProvider: () -> String?,
) {

    companion object {
        private const val TAG = "TailscaleDetector"
        private const val TAILSCALE_IFACE_NAME = "tailscale0"
        private const val TAILSCALE_MAGICDNS_SUFFIX = ".ts.net"
    }

    private val _isTailscaleDetected = MutableStateFlow(false)
    val isTailscaleDetected: StateFlow<Boolean> = _isTailscaleDetected.asStateFlow()

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { refresh() }
        override fun onLost(network: Network) { refresh() }
        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: android.net.NetworkCapabilities
        ) {
            refresh()
        }
    }

    init {
        try {
            cm?.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "registerDefaultNetworkCallback failed: ${e.message}")
        }
        refresh()
    }

    /**
     * Trigger a re-scan. Called internally on network change and externally
     * when the user changes the relay URL (so the second signal can update
     * without waiting for a connectivity callback).
     */
    fun refresh() {
        scope.launch {
            val detected = withContext(Dispatchers.IO) { computeDetection() }
            _isTailscaleDetected.value = detected
        }
    }

    fun shutdown() {
        try {
            cm?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
            // unregister can throw on devices that never received register
        }
    }

    // --- detection ---------------------------------------------------------

    private fun computeDetection(): Boolean {
        if (scanInterfaces()) return true
        if (matchRelayHost()) return true
        return false
    }

    /**
     * Walks local interfaces. Named `tailscale0` wins outright. Otherwise
     * any IPv4 address in `100.64.0.0/10` also wins.
     */
    private fun scanInterfaces(): Boolean {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return false
            for (iface in ifaces) {
                val name = iface.name ?: continue
                if (name.equals(TAILSCALE_IFACE_NAME, ignoreCase = true)) return true
                val addresses = iface.inetAddresses ?: continue
                for (addr in addresses) {
                    if (addr is Inet4Address && isCgnatAddress(addr)) return true
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "scanInterfaces failed: ${e.message}")
            false
        }
    }

    /**
     * Checks the relay URL. If the host ends in `.ts.net` we're certain;
     * otherwise we do a best-effort [InetAddress.getByName] lookup and check
     * whether the result falls in the CGNAT range.
     */
    private fun matchRelayHost(): Boolean {
        val rawUrl = relayUrlProvider()?.trim().orEmpty()
        if (rawUrl.isEmpty()) return false
        val host = try {
            URI(rawUrl).host
        } catch (_: Exception) {
            null
        } ?: return false

        val lower = host.lowercase()
        if (lower.endsWith(TAILSCALE_MAGICDNS_SUFFIX)) return true
        if (isCgnatLiteral(lower)) return true

        // Best-effort DNS resolve. Guard it behind a try-catch — a flaky
        // network shouldn't flip the detector state.
        return try {
            val resolved = InetAddress.getAllByName(host)
            resolved.any { it is Inet4Address && isCgnatAddress(it) }
        } catch (_: Exception) {
            false
        }
    }

    private fun isCgnatAddress(addr: Inet4Address): Boolean {
        val bytes = addr.address
        if (bytes.size != 4) return false
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        // 100.64.0.0/10 — first octet 100, second octet 64..127
        return a == 100 && b in 64..127
    }

    private fun isCgnatLiteral(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127
    }
}
