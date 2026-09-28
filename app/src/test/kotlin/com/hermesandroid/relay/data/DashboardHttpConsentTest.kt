package com.hermesandroid.relay.data

import com.hermesandroid.relay.network.upstream.isNativeDashboardTransportEligible
import com.hermesandroid.relay.network.upstream.trustedDashboardBearerAuthOrNull
import com.hermesandroid.relay.network.upstream.dashboardClientWithHttpConsent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import com.hermesandroid.relay.viewmodel.publicDashboardAddressRequiresHttps
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DashboardHttpConsentTest {
    private val address = "http://11.0.0.1:9119"
    private val consent = setOf(address)

    @Test fun consentDoesNotFollowRedirectsToAnotherOrigin() {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                first.enqueue(MockResponse().setResponseCode(307).setHeader("Location", second.url("/capture")))
                val base = first.url("/").toString().trimEnd('/')
                val client = dashboardClientWithHttpConsent(OkHttpClient(), base, setOf(base))
                try {
                    client.newCall(Request.Builder().url(first.url("/api/auth/me"))
                        .header("Authorization", "Bearer synthetic-fixture").build()).execute().use {
                        assertEquals(307, it.code)
                    }
                    assertEquals(0, second.requestCount)
                } finally {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    @Test fun publicHttpRequiresExplicitExactOriginConsent() {
        assertTrue(publicDashboardAddressRequiresHttps(null, address))
        assertFalse(publicDashboardAddressRequiresHttps(null, address, consent))
        assertTrue(publicDashboardAddressRequiresHttps("lan", "http://11.0.0.2:9119", consent))
        assertTrue(publicDashboardAddressRequiresHttps(null, "http://11.0.0.1:9120", consent))
        assertNull(dashboardHttpOrigin("http://user:password@11.0.0.1:9119"))
        assertNull(dashboardHttpOrigin("http://11.0.0.1:9119?token=untrusted"))
        assertFalse(dashboardHttpConsentRequired("https://11.0.0.1:9119"))
        assertFalse(dashboardHttpConsentRequired("http://192.168.1.5:9119"))
    }

    @Test fun persistedConsentBelongsToOneConnectionAndDoesNotChangeSecurityClassification() {
        val connection = Connection("one", "Gateway", "", "", "one-key",
            dashboardUrl = address, dashboardHttpConsentOrigins = consent)
        val restored = Json.decodeFromString<Connection>(Json.encodeToString(connection)).withDashboardDefaults()
        assertEquals(consent, restored.dashboardHttpConsentOrigins)
        assertTrue(dashboardHttpConsentMatches(address, restored.dashboardHttpConsentOrigins))
        assertFalse(dashboardHttpConsentMatches(address, Connection("two", "Other", "", "", "two-key").dashboardHttpConsentOrigins))
        assertEquals("public", Connection.inferRouteRole(address))
        assertFalse(isTlsUrl(address))
    }

    @Test fun changingAnOriginRequiresFreshConsentAndRetiresTheOldException() {
        val next = "http://11.0.0.2:9119"
        assertEquals(emptySet<String>(), updatedDashboardHttpConsents(consent, address, next, null))
        assertEquals(setOf(next), updatedDashboardHttpConsents(consent, address, next, next))
        assertEquals(emptySet<String>(), updatedDashboardHttpConsents(consent, address, "https://11.0.0.1:9119", null))
        assertEquals(consent, updatedDashboardHttpConsents(consent, address, "$address/prefix", null))
    }

    @Test fun nativeAuthenticationUsesTheSameConsentWithoutTrustingOtherBases() {
        assertFalse(isNativeDashboardTransportEligible(address))
        assertTrue(isNativeDashboardTransportEligible(address, consent))
        assertFalse(isNativeDashboardTransportEligible("http://11.0.0.2:9119", consent))
        assertNull(normalizeCredentialFreeAuthenticatedDashboardOrigin(address))
        assertEquals(address, normalizeCredentialFreeAuthenticatedDashboardOrigin(address, consent))
        assertNotNull(trustedDashboardBearerAuthOrNull(address, address, consent) { mockk(relaxed = true) })
        assertNull(trustedDashboardBearerAuthOrNull("http://11.0.0.2:9119", address, consent) { error("Must not load credentials") })
        assertNull(trustedDashboardBearerAuthOrNull("$address/other", "$address/hermes", consent) { error("Must not load credentials") })
    }
}
