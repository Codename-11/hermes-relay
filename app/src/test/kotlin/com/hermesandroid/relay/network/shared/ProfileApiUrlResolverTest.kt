package com.hermesandroid.relay.network.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileApiUrlResolverTest {

    @Test
    fun resolveForConnection_rewritesLoopbackProfileHostToBaseHost() {
        assertEquals(
            "http://172.16.24.250:8647",
            ProfileApiUrlResolver.resolveForConnection(
                profileApiUrl = "http://127.0.0.1:8647",
                baseApiUrl = "http://172.16.24.250:8642",
            ),
        )
    }

    @Test
    fun resolveForConnection_rewritesZeroBindHostToBaseHost() {
        assertEquals(
            "https://docker-server.tailnet.ts.net:8646",
            ProfileApiUrlResolver.resolveForConnection(
                profileApiUrl = "http://0.0.0.0:8646/",
                baseApiUrl = "https://docker-server.tailnet.ts.net:8642/",
            ),
        )
    }

    @Test
    fun resolveForConnection_keepsRemoteProfileHost() {
        assertEquals(
            "http://192.168.1.50:8647",
            ProfileApiUrlResolver.resolveForConnection(
                profileApiUrl = "http://192.168.1.50:8647",
                baseApiUrl = "http://172.16.24.250:8642",
            ),
        )
    }

    @Test
    fun resolveForConnection_keepsLoopbackWhenBaseIsAlsoLoopback() {
        assertEquals(
            "http://127.0.0.1:8647",
            ProfileApiUrlResolver.resolveForConnection(
                profileApiUrl = "http://127.0.0.1:8647",
                baseApiUrl = "http://localhost:8642",
            ),
        )
    }

    @Test
    fun resolveForConnection_handlesBlankProfileUrl() {
        assertNull(
            ProfileApiUrlResolver.resolveForConnection(
                profileApiUrl = " ",
                baseApiUrl = "http://172.16.24.250:8642",
            ),
        )
    }
}
