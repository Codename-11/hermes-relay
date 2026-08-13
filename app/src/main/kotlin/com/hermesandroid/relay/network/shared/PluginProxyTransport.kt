package com.hermesandroid.relay.network.shared

import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.ProxyEndpoint
import com.hermesandroid.relay.data.isValidPinnedProxy
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Runtime endpoints exposed beneath one plugin-owned pinned-TLS origin. */
data class PluginProxyRoutes(
    val authority: String,
    val host: String,
    val port: Int,
    val relayHttpUrl: String,
    val relayWebSocketUrl: String,
    val pinSha256: String,
)

/**
 * Resolve and validate the pairing-advertised proxy contract. Invalid or
 * incomplete advertisements are never treated as secure routes.
 */
fun ProxyEndpoint.toPluginProxyRoutesOrNull(): PluginProxyRoutes? {
    if (!isValidPinnedProxy()) return null
    val base = url.trim().trimEnd('/')
    val uri = runCatching { URI(base) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    if (!uri.rawUserInfo.isNullOrBlank() || uri.rawQuery != null || uri.rawFragment != null) return null
    val rawPath = uri.rawPath.orEmpty()
    if (rawPath.isNotEmpty() && rawPath != "/") return null
    val port = if (uri.port > 0) uri.port else 443
    val pin = pinSha256!!.trim()
    val authority = "$host:$port"
    val wsBase = "wss://${formatHost(host)}${if (port == 443) "" else ":$port"}$rawPath"
        .trimEnd('/')
    return PluginProxyRoutes(
        authority = authority,
        host = host,
        port = port,
        relayHttpUrl = "$base/relay",
        relayWebSocketUrl = "$wsBase/relay/ws",
        pinSha256 = pin,
    )
}

fun EndpointCandidate.pluginProxyRoutesOrNull(): PluginProxyRoutes? =
    proxy?.toPluginProxyRoutesOrNull()

private fun formatHost(host: String): String = if (':' in host) "[$host]" else host

/**
 * Build a client that trusts the system normally, plus exactly the
 * pairing-advertised SPKI for this proxy. The authority guard keeps a pin
 * scoped to host *and port*; OkHttp's CertificatePinner alone is host-only.
 */
fun buildPluginProxyClient(
    baseBuilder: OkHttpClient.Builder,
    routes: PluginProxyRoutes,
    sessionTokenProvider: () -> String?,
): OkHttpClient {
    val expectedHost = routes.host
    val expectedPort = routes.port
    val systemTrust = systemTrustManager()
    val pinnedTrust = PinnedOrSystemTrustManager(systemTrust, routes.pinSha256)
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(pinnedTrust), SecureRandom())
    }

    return baseBuilder
        .sslSocketFactory(sslContext.socketFactory, pinnedTrust)
        .certificatePinner(
            CertificatePinner.Builder().add(expectedHost, routes.pinSha256).build(),
        )
        .addNetworkInterceptor(Interceptor { chain ->
            val requestUrl = chain.request().url
            if (!requestUrl.host.equals(expectedHost, ignoreCase = true) ||
                requestUrl.port != expectedPort
            ) {
                throw java.io.IOException("Pinned proxy redirect left its paired authority")
            }
            val token = sessionTokenProvider()?.takeIf { it.isNotBlank() }
            val request = if (token != null) {
                chain.request().newBuilder()
                    .header("X-Hermes-Relay-Session", token)
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        })
        .build()
}

private fun systemTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().single()
}

private class PinnedOrSystemTrustManager(
    private val system: X509TrustManager,
    private val expectedPin: String,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificates = chain?.takeIf { it.isNotEmpty() }
            ?: throw CertificateException("Proxy supplied no certificate chain")
        val systemAccepted = runCatching { system.checkServerTrusted(chain, authType) }.isSuccess
        if (systemAccepted) return

        val leaf = certificates.first()
        leaf.checkValidity()
        val actual = "sha256/" + java.util.Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded),
        )
        if (!MessageDigest.isEqual(actual.toByteArray(), expectedPin.toByteArray())) {
            throw CertificateException("Plugin proxy certificate does not match the paired pin")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}
