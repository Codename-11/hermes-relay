package com.hermesandroid.relay.network

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSecurityConfigTest {
    private val document by lazy {
        val config = sequenceOf(
            File("src/main/res/xml/network_security_config.xml"),
            File("app/src/main/res/xml/network_security_config.xml"),
        ).firstOrNull(File::isFile)
            ?: error("network_security_config.xml not found")

        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(config)
    }

    @Test
    fun releaseConfig_trustsOnlySystemAndUserCredentialStores() {
        val certificates = document.getElementsByTagName("certificates")
        val sources = (0 until certificates.length).map { index ->
            certificates.item(index).attributes.getNamedItem("src").nodeValue
        }

        assertEquals(listOf("system", "user"), sources)
        assertTrue(
            (0 until certificates.length).all { index ->
                certificates.item(index).attributes.getNamedItem("overridePins").nodeValue == "false"
            },
        )
    }

    @Test
    fun releaseConfig_hasNoTrustOrHostnameBypass() {
        assertEquals(0, document.getElementsByTagName("debug-overrides").length)
        assertFalse(document.documentElement.textContent.contains("trust-all", ignoreCase = true))
        assertFalse(document.documentElement.textContent.contains("ignore tls", ignoreCase = true))
    }
}
