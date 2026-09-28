package com.hermesandroid.relay.network.shared

import com.hermesandroid.relay.data.ProxyEndpoint
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginProxyTransportTest {
    private val pin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    @Test
    fun `system trust never bypasses the paired leaf pin`() {
        val key = mockk<PublicKey>()
        every { key.encoded } returns byteArrayOf(1, 2, 3)
        val leaf = mockk<X509Certificate>(relaxed = true)
        every { leaf.publicKey } returns key
        val matchingPin = "sha256/" + Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(key.encoded),
        )
        for (systemAccepted in listOf(true, false)) {
            val system = mockk<X509TrustManager>(relaxed = true)
            if (!systemAccepted) {
                every { system.checkServerTrusted(any(), any()) } throws CertificateException("untrusted")
            }
            PinnedOrSystemTrustManager(system, matchingPin).checkServerTrusted(arrayOf(leaf), "RSA")
            assertThrows(CertificateException::class.java) {
                PinnedOrSystemTrustManager(system, pin).checkServerTrusted(arrayOf(leaf), "RSA")
            }
            assertThrows(CertificateException::class.java) {
                PinnedOrSystemTrustManager(system, matchingPin).checkServerTrusted(emptyArray(), "RSA")
            }
        }
        val rejectingSystem = mockk<X509TrustManager>()
        every { rejectingSystem.checkServerTrusted(any(), any()) } throws CertificateException("untrusted")
        every { leaf.checkValidity() } throws CertificateException("expired")
        assertThrows(CertificateException::class.java) {
            PinnedOrSystemTrustManager(rejectingSystem, matchingPin).checkServerTrusted(arrayOf(leaf), "RSA")
        }
    }

    @Test
    fun `authority guard rejects HTTP and WebSocket before credentials or network`() {
        var credentialsRead = false
        val client = buildPluginProxyClient(
            OkHttpClient.Builder(),
            ProxyEndpoint("https://paired.invalid:9443", pinSha256 = pin).toPluginProxyRoutesOrNull()!!,
            sessionTokenProvider = { credentialsRead = true; "session-token" },
        )
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        for (url in listOf("http://paired.invalid:9443/relay", "https://other.invalid:9443/relay", "https://paired.invalid:9444/relay")) {
            val failure = assertThrows(java.io.IOException::class.java) {
                client.newCall(Request.Builder().url(url).build()).execute().close()
            }
            assertTrue(failure.message.orEmpty().contains("paired authority"))
        }
        val failed = CountDownLatch(1)
        var socketFailure: Throwable? = null
        client.newWebSocket(Request.Builder().url("wss://paired.invalid:9444/relay/ws").build(),
            object : WebSocketListener() {
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socketFailure = t
                    failed.countDown()
                }
            },
        )
        assertTrue(failed.await(3, TimeUnit.SECONDS))
        assertTrue(socketFailure?.message.orEmpty().contains("paired authority"))
        assertFalse(credentialsRead)
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `derives all proxy surfaces from one authority`() {
        val routes = ProxyEndpoint(
            "https://relay.example:9443",
            pinSha256 = pin,
            surfaces = listOf("relay", "api", "dashboard"),
        )
            .toPluginProxyRoutesOrNull()!!
        assertEquals("relay.example:9443", routes.authority)
        assertEquals("https://relay.example:9443/relay", routes.relayHttpUrl)
        assertEquals("wss://relay.example:9443/relay/ws", routes.relayWebSocketUrl)
        assertEquals("https://relay.example:9443/api", routes.apiBaseUrl)
        assertEquals("https://relay.example:9443/dashboard", routes.dashboardBaseUrl)
    }

    @Test
    fun `rejects incomplete or unsafe proxy advertisements`() {
        val invalid = listOf(
            ProxyEndpoint("http://relay.example:9443", pinSha256 = pin),
            ProxyEndpoint("https://relay.example:9443", pinSha256 = null),
            ProxyEndpoint("https://relay.example:9443", pinSha256 = "sha256/not-base64"),
            ProxyEndpoint("https://user@relay.example:9443", pinSha256 = pin),
            ProxyEndpoint("https://relay.example:9443?route=x", pinSha256 = pin),
            ProxyEndpoint("https://relay.example:9443/a/../b", pinSha256 = pin),
            ProxyEndpoint("https://relay.example:9443/a/%2e%2e/b", pinSha256 = pin),
            ProxyEndpoint("https://relay.example:9443/a%2fb", pinSha256 = pin),
            ProxyEndpoint("https://relay.example:9443/secure", pinSha256 = pin),
        )
        assertTrue(invalid.all { it.toPluginProxyRoutesOrNull() == null })
    }

    @Test
    fun `proxy pin remains scoped to advertised host and port`() {
        val routes = ProxyEndpoint("https://relay.example:9443", pinSha256 = pin)
            .toPluginProxyRoutesOrNull()!!
        assertEquals("relay.example:9443", routes.authority)
        assertNull(ProxyEndpoint("https://relay.example", pinSha256 = "sha256/")
            .toPluginProxyRoutesOrNull())
    }
}
