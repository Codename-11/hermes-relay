package com.hermesandroid.relay.network.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayUrlDeriverTest {
    @Test
    fun derivesPlainLanRelayUrlFromApiUrl() {
        assertEquals(
            "ws://172.16.24.250:8767",
            RelayUrlDeriver.deriveFromApiUrl("http://172.16.24.250:8642"),
        )
    }

    @Test
    fun derivesTlsRelayUrlFromApiUrl() {
        assertEquals(
            "wss://docker-server.tailnet.ts.net:8767",
            RelayUrlDeriver.deriveFromApiUrl("https://docker-server.tailnet.ts.net:8642"),
        )
    }

    @Test
    fun ignoresApiPathAndTrailingSlash() {
        assertEquals(
            "wss://hermes.example.test:8767",
            RelayUrlDeriver.deriveFromApiUrl("https://hermes.example.test:8642/api/"),
        )
    }

    @Test
    fun rejectsNonHttpApiUrl() {
        assertNull(RelayUrlDeriver.deriveFromApiUrl("ws://host:8767"))
    }

    @Test
    fun treatsBlankDefaultAndDerivedRelayUrlsAsAutoManaged() {
        assertTrue(RelayUrlDeriver.isAutoManagedRelayUrl("", "http://10.0.0.5:8642"))
        assertTrue(
            RelayUrlDeriver.isAutoManagedRelayUrl(
                "wss://localhost:8767",
                "http://10.0.0.5:8642",
            ),
        )
        assertTrue(
            RelayUrlDeriver.isAutoManagedRelayUrl(
                "ws://10.0.0.5:8767",
                "http://10.0.0.5:8642",
            ),
        )
    }

    @Test
    fun preservesManualRelayOverride() {
        assertFalse(
            RelayUrlDeriver.isAutoManagedRelayUrl(
                "wss://relay.example.test:443",
                "https://api.example.test:8642",
            ),
        )
    }
}
