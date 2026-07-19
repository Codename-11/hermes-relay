package com.hermesandroid.relay.network.shared

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileApiUrlResolverTest {

    @Test
    fun resolveForConnection_rewritesLoopbackProfileHostToBaseHost() {
        assertEquals(
            "http://192.168.1.100:8647",
            ProfileApiUrlResolver.resolveForConnection(
                profileApiUrl = "http://127.0.0.1:8647",
                baseApiUrl = "http://192.168.1.100:8642",
            ),
        )
    }

    @Test
    fun resolveForConnection_rewritesZeroBindHostToBaseHost() {
        assertEquals(
            "https://hermes-host.tailnet.ts.net:8646",
            ProfileApiUrlResolver.resolveForConnection(
                profileApiUrl = "http://0.0.0.0:8646/",
                baseApiUrl = "https://hermes-host.tailnet.ts.net:8642/",
            ),
        )
    }

    @Test
    fun resolveForConnection_keepsRemoteProfileHost() {
        assertEquals(
            "http://192.168.1.50:8647",
            ProfileApiUrlResolver.resolveForConnection(
                profileApiUrl = "http://192.168.1.50:8647",
                baseApiUrl = "http://192.168.1.100:8642",
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
                baseApiUrl = "http://192.168.1.100:8642",
            ),
        )
    }

    @Test
    fun resolveChatBase_buildsMultiplexProfilePath() {
        assertEquals(
            "https://hermes.example.test:8642/p/research_qa",
            ProfileApiUrlResolver.resolveChatBase(
                profileApiUrl = null,
                baseApiUrl = "https://hermes.example.test:8642/",
                selectedProfileName = "research_qa",
                gatewayMode = "multiplex",
                servedProfiles = listOf("default", "research_qa"),
            ),
        )
    }

    @Test
    fun resolveChatBase_keepsRootForDefaultProfile() {
        assertEquals(
            "https://hermes.example.test:8642",
            ProfileApiUrlResolver.resolveChatBase(
                profileApiUrl = null,
                baseApiUrl = "https://hermes.example.test:8642/",
                selectedProfileName = "default",
                gatewayMode = "multiplex",
                servedProfiles = listOf("default", "coder"),
            ),
        )
    }

    @Test
    fun resolveChatBase_keepsRootWithoutMultiplexTopology() {
        assertEquals(
            "https://hermes.example.test:8642",
            ProfileApiUrlResolver.resolveChatBase(
                profileApiUrl = null,
                baseApiUrl = "https://hermes.example.test:8642",
                selectedProfileName = "coder",
                gatewayMode = null,
                servedProfiles = emptyList(),
            ),
        )
        assertEquals(
            "https://hermes.example.test:8642",
            ProfileApiUrlResolver.resolveChatBase(
                profileApiUrl = null,
                baseApiUrl = "https://hermes.example.test:8642",
                selectedProfileName = "coder",
                gatewayMode = "single",
                servedProfiles = listOf("default", "coder"),
            ),
        )
    }

    @Test
    fun resolveChatBase_prefersDedicatedProfileApi() {
        assertEquals(
            "https://hermes.example.test:8647",
            ProfileApiUrlResolver.resolveChatBase(
                profileApiUrl = "http://127.0.0.1:8647",
                baseApiUrl = "https://hermes.example.test:8642",
                selectedProfileName = "coder",
                gatewayMode = "multiplex",
                servedProfiles = listOf("default", "coder"),
            ),
        )
    }

    @Test
    fun resolveChatBase_switchesProfilesWithoutRetainingPriorPrefix() {
        val base = "https://hermes.example.test:8642"
        val coder = ProfileApiUrlResolver.resolveChatBase(
            profileApiUrl = null,
            baseApiUrl = base,
            selectedProfileName = "coder",
            gatewayMode = "multiplex",
            servedProfiles = listOf("default", "coder", "research"),
        )
        val research = ProfileApiUrlResolver.resolveChatBase(
            profileApiUrl = null,
            baseApiUrl = base,
            selectedProfileName = "research",
            gatewayMode = "multiplex",
            servedProfiles = listOf("default", "coder", "research"),
        )

        assertEquals("https://hermes.example.test:8642/p/coder", coder)
        assertEquals("https://hermes.example.test:8642/p/research", research)
        assertNotEquals(coder, research)
    }

    @Test
    fun multiplexBase_keepsPrefixForEveryDirectApiRouteFamily() {
        val base = ProfileApiUrlResolver.resolveChatBase(
            profileApiUrl = null,
            baseApiUrl = "https://hermes.example.test:8642",
            selectedProfileName = "coder",
            gatewayMode = "multiplex",
            servedProfiles = listOf("default", "coder"),
        )
        val paths = listOf(
            "/v1/models",
            "/api/sessions",
            "/api/sessions/session-1/messages",
            "/api/sessions/session-1/chat/stream",
            "/v1/chat/completions",
            "/v1/runs",
            "/v1/runs/run-1/events",
            "/v1/runs/run-1/stop",
        )

        val encodedPaths = paths.map { path ->
            "$base$path".toHttpUrlOrNull()?.encodedPath
        }

        assertEquals(paths.map { "/p/coder$it" }, encodedPaths)
    }
}
