package com.hermesandroid.relay.network.relay

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.Assert.*

class RelayHttpClientSessionPolicyTest {
    @Test fun secureLinkSessionRequestsKeepRelayPrefixAndBearer() = runTest {
        MockWebServer().use { server ->
            server.start()
            val client = RelayHttpClient(
                OkHttpClient(),
                { "ws://${server.hostName}:${server.port}/relay/ws" },
                { "current-session" },
            )
            server.enqueue(MockResponse().setBody("{\"sessions\":[]}"))
            assertTrue(client.listSessions().isSuccess)
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            assertTrue(client.extendSession("current", 60).isSuccess)
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            assertTrue(client.revokeSession("current").isSuccess)
            for ((method, path) in listOf(
                "GET" to "/relay/sessions",
                "PATCH" to "/relay/sessions/current",
                "DELETE" to "/relay/sessions/current",
            )) {
                val request = server.takeRequest()
                assertEquals(method, request.method)
                assertEquals(path, request.path)
                assertEquals("Bearer current-session", request.getHeader("Authorization"))
                assertNull(request.getHeader("X-Hermes-Relay-Session"))
            }
        }
    }

    @Test fun successfulReductionSendsOnlyTtlAndForbiddenRenewalIsActionable() = runTest {
        MockWebServer().use { server ->
            server.start()
            val client = RelayHttpClient(OkHttpClient(), { "ws://${server.hostName}:${server.port}" }, { "current-session" })
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
            assertTrue(client.extendSession("current", 86400).isSuccess)
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/sessions/current", request.path)
            assertEquals("Bearer current-session", request.getHeader("Authorization"))
            assertEquals("{\"ttl_seconds\":86400}", request.body.readUtf8())
            server.enqueue(MockResponse().setResponseCode(403).setBody("operator approval required to extend session"))
            val failed = client.extendSession("current", 90 * 86400)
            assertTrue(failed.isFailure)
            assertTrue(failed.exceptionOrNull()!!.message!!.contains("fresh operator-approved code"))
        }
    }
}
