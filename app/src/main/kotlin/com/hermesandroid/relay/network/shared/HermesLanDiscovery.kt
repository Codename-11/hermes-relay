package com.hermesandroid.relay.network.shared

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class HermesLanDiscoveryResult(
    val host: String,
    val hostname: String? = null,
    val apiUrl: String,
    val dashboardUrl: String?,
    val apiReachable: Boolean,
    val dashboardReachable: Boolean,
) {
    val displayHost: String
        get() = hostname ?: host
}

/**
 * User-triggered local-network discovery for standard Hermes setup.
 *
 * This deliberately scans only the active RFC1918/link-local LAN around the
 * phone, never broad public or Tailscale ranges. Tailscale/public routes still
 * belong in the explicit advanced fields where the user controls the URL.
 */
object HermesLanDiscovery {
    private const val TAG = "HermesLanDiscovery"
    private const val MAX_HOSTS = 254
    private const val MAX_CONCURRENT_PROBES = 32
    private const val PROBE_TIMEOUT_MS = 650L
    private const val IPV4_MASK = 0xFFFF_FFFFL

    suspend fun scan(
        context: Context,
        apiPort: Int = 8642,
        dashboardPort: Int = 9119,
    ): List<HermesLanDiscoveryResult> = withContext(Dispatchers.IO) {
        val hosts = localLanHosts(context.applicationContext)
        if (hosts.isEmpty()) return@withContext emptyList()

        val client = OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
            .build()

        coroutineScope {
            val semaphore = Semaphore(MAX_CONCURRENT_PROBES)
            hosts.map { host ->
                async {
                    semaphore.withPermit {
                        probeHost(client, host, apiPort, dashboardPort)?.let { result ->
                            result.copy(hostname = resolveHostname(host))
                        }
                    }
                }
            }.awaitAll()
                .filterNotNull()
                .sortedWith(
                    compareByDescending<HermesLanDiscoveryResult> { it.dashboardReachable }
                        .thenByDescending { it.apiReachable }
                        .thenBy { it.host },
                )
        }
    }

    private fun probeHost(
        client: OkHttpClient,
        host: String,
        apiPort: Int,
        dashboardPort: Int,
    ): HermesLanDiscoveryResult? {
        val apiUrl = "http://$host:$apiPort"
        val dashboardUrl = "http://$host:$dashboardPort"
        val dashboardReachable = probe(
            client = client,
            url = "$dashboardUrl/api/status",
            expectedBody = ::looksLikeDashboardStatus,
        )
        val apiReachable = probe(
            client = client,
            url = "$apiUrl/health",
            expectedBody = ::looksLikeApiHealth,
        )
        if (!dashboardReachable && !apiReachable) return null
        return HermesLanDiscoveryResult(
            host = host,
            apiUrl = apiUrl,
            dashboardUrl = dashboardUrl.takeIf { dashboardReachable },
            apiReachable = apiReachable,
            dashboardReachable = dashboardReachable,
        )
    }

    private fun probe(
        client: OkHttpClient,
        url: String,
        expectedBody: (String, String) -> Boolean,
    ): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        val request = Request.Builder()
            .url(httpUrl)
            .get()
            .header("Accept", "application/json, text/plain, */*")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    return true
                }
                if (!response.isSuccessful) {
                    return false
                }
                val contentType = response.header("Content-Type").orEmpty()
                val body = response.body.string().take(2_048)
                expectedBody(body, contentType)
            }
        } catch (e: Exception) {
            Log.d(TAG, "probe failed url=$url type=${e.javaClass.simpleName}")
            false
        }
    }

    private suspend fun resolveHostname(address: String): String? =
        withTimeoutOrNull(350L) {
            runInterruptible(Dispatchers.IO) {
                normalizeResolvedHostname(
                    address = address,
                    resolved = InetAddress.getByName(address).canonicalHostName,
                )
            }
        }

    internal fun normalizeResolvedHostname(address: String, resolved: String?): String? {
        val normalized = resolved?.trim()?.trimEnd('.')?.takeIf { it.isNotBlank() } ?: return null
        if (normalized.equals(address.trim(), ignoreCase = true)) return null
        if (normalized.equals("localhost", ignoreCase = true)) return null
        if (normalized.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$"))) return null
        return normalized
    }

    private fun looksLikeDashboardStatus(body: String, contentType: String): Boolean {
        val lower = body.lowercase()
        return contentType.contains("json", ignoreCase = true) && (
            lower.contains("auth_required") ||
                lower.contains("auth_providers") ||
                lower.contains("authenticated") ||
                lower.contains("hermes")
            )
    }

    private fun looksLikeApiHealth(body: String, contentType: String): Boolean {
        if (contentType.contains("json", ignoreCase = true)) return true
        if (contentType.contains("text/plain", ignoreCase = true)) return true
        return body.isBlank() || body.trimStart().startsWith("{")
    }

    private fun localLanHosts(context: Context): List<String> {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return emptyList()
        val networks = buildList {
            connectivityManager.activeNetwork?.let(::add)
            connectivityManager.allNetworks.forEach { network ->
                if (!contains(network)) add(network)
            }
        }

        val hosts = linkedSetOf<String>()
        for (network in networks) {
            val linkProperties = connectivityManager.getLinkProperties(network) ?: continue
            for (linkAddress in linkProperties.linkAddresses) {
                addHostsForLink(linkAddress, hosts)
                if (hosts.size >= MAX_HOSTS) break
            }
            if (hosts.size >= MAX_HOSTS) break
        }
        return hosts.take(MAX_HOSTS)
    }

    private fun addHostsForLink(linkAddress: LinkAddress, hosts: MutableSet<String>) {
        val address = linkAddress.address as? Inet4Address ?: return
        if (address.isLoopbackAddress || address.isMulticastAddress) return

        val local = ipv4ToLong(address)
        if (!isScannableLanAddress(local)) return

        val scanPrefix = when (linkAddress.prefixLength) {
            in 24..30 -> linkAddress.prefixLength
            else -> 24
        }
        val mask = subnetMask(scanPrefix)
        val network = local and mask
        val broadcast = network or (mask.inv() and IPV4_MASK)
        val first = network + 1
        val last = broadcast - 1
        if (first > last) return

        for (candidate in first..last) {
            if (candidate == local) continue
            hosts.add(longToIpv4(candidate))
            if (hosts.size >= MAX_HOSTS) return
        }
    }

    private fun subnetMask(prefixLength: Int): Long {
        return (IPV4_MASK shl (32 - prefixLength)) and IPV4_MASK
    }

    private fun ipv4ToLong(address: Inet4Address): Long {
        return address.address.fold(0L) { acc, byte ->
            (acc shl 8) or (byte.toInt() and 0xFF).toLong()
        } and IPV4_MASK
    }

    private fun longToIpv4(value: Long): String {
        return listOf(
            (value shr 24) and 0xFF,
            (value shr 16) and 0xFF,
            (value shr 8) and 0xFF,
            value and 0xFF,
        ).joinToString(".") { it.toString() }
    }

    private fun isScannableLanAddress(value: Long): Boolean {
        val first = ((value shr 24) and 0xFF).toInt()
        val second = ((value shr 16) and 0xFF).toInt()
        return when {
            first == 10 -> true
            first == 172 && second in 16..31 -> true
            first == 192 && second == 168 -> true
            first == 169 && second == 254 -> true
            else -> false
        }
    }
}
