package com.hermesandroid.relay.network.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HermesLanDiscoveryTest {

    @Test
    fun normalizeResolvedHostname_acceptsARealHostname() {
        assertEquals(
            "hermes-box.local",
            HermesLanDiscovery.normalizeResolvedHostname(
                address = "192.168.1.25",
                resolved = "hermes-box.local.",
            ),
        )
    }

    @Test
    fun normalizeResolvedHostname_rejectsNumericAndUnresolvedResults() {
        assertNull(HermesLanDiscovery.normalizeResolvedHostname("192.168.1.25", "192.168.1.25"))
        assertNull(HermesLanDiscovery.normalizeResolvedHostname("192.168.1.25", ""))
        assertNull(HermesLanDiscovery.normalizeResolvedHostname("192.168.1.25", "localhost"))
    }
}
