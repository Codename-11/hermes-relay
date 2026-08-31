package com.hermesandroid.relay.network.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun prioritizeHostSweep_checksCommonLowAndHighAddressesEarly() {
        val hosts = (1..254).map { "172.16.24.$it" }

        val prioritized = HermesLanDiscovery.prioritizeHostSweep(hosts)

        assertEquals(
            listOf(
                "172.16.24.1",
                "172.16.24.254",
                "172.16.24.2",
                "172.16.24.253",
            ),
            prioritized.take(4),
        )
        assertTrue(prioritized.indexOf("172.16.24.250") < 10)
        assertEquals(hosts.toSet(), prioritized.toSet())
    }
}
