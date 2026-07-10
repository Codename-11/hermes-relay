package com.hermesandroid.relay.network.relay

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the relay-socket half of the #131 "Invalid URL host" crash class.
 *
 * [ConnectionManager.doConnectInternal] builds an OkHttp `Request` on a
 * background coroutine, so a malformed relay URL (from a corrupt or hand-edited
 * pairing payload) used to let `Request.Builder.url()` throw
 * `IllegalArgumentException`, which — uncaught on the IO dispatcher — crashed
 * the app (observed on Play as an `okhttp3.HttpUrl$Builder.parse` crash).
 * [buildRelayRequestOrNull] must return null for such URLs so the connect path
 * fails gracefully (Disconnected + diagnostic) instead of crashing.
 */
class ConnectionManagerUrlGuardTest {

    @Test
    fun `valid ws and wss urls build a request`() {
        assertNotNull(buildRelayRequestOrNull("wss://relay.example.com:8767"))
        assertNotNull(buildRelayRequestOrNull("ws://192.168.1.10:8767/path"))
        assertNotNull(buildRelayRequestOrNull("wss://host.tailnet.ts.net"))
    }

    @Test
    fun `malformed relay urls return null instead of throwing`() {
        // Each makes OkHttp's Request.Builder.url() throw IllegalArgumentException
        // ("Invalid URL host"): empty host, and a space inside the host.
        for (bad in listOf(
            "wss://",
            "wss://in valid host:8767",
            "wss://a b",
        )) {
            assertNull("expected null for malformed relay url '$bad'", buildRelayRequestOrNull(bad))
        }
    }
}
