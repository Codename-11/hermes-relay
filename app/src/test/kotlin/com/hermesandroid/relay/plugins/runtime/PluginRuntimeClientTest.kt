package com.hermesandroid.relay.plugins.runtime

import com.hermesandroid.relay.network.upstream.DashboardApiClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PluginRuntimeClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun discovery_probesOnlyValidApiPlugins_andAnchorsManifestIdentity() = runTest {
        server.enqueue(
            jsonResponse(
                """
                [
                  {"name":"weather","label":"Weather","description":"Forecasts","version":"1.2.0","icon":"Cloud","source":"user","has_api":true},
                  {"name":"visual-only","has_api":false},
                  {"name":"../unsafe","has_api":true},
                  {"name":"impostor","has_api":true}
                ]
                """.trimIndent(),
            ),
        )
        server.enqueue(jsonResponse("""{"schemaVersion":1,"id":"weather","contributions":[]}"""))
        server.enqueue(jsonResponse("""{"schemaVersion":1,"id":"some-other-plugin","contributions":[]}"""))

        val dashboard = DashboardApiClient(server.url("/").toString())
        val discovered = PluginDiscoveryClient(dashboard).discover().getOrThrow()

        assertEquals(1, discovered.size)
        assertEquals("weather", discovered.single().catalog.id)
        assertEquals("Weather", discovered.single().catalog.label)
        assertEquals("/api/dashboard/plugins", server.takeRequest().path)
        assertEquals("/api/plugins/weather/mobile/manifest", server.takeRequest().path)
        assertEquals("/api/plugins/impostor/mobile/manifest", server.takeRequest().path)
        assertEquals(0, server.requestCount - 3)
    }

    @Test
    fun discovery_treatsMissingMobileManifestAsNoAndroidSurface() = runTest {
        server.enqueue(jsonResponse("""[{"name":"desktop-only","has_api":true}]"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Not found"}"""))

        val discovered = PluginDiscoveryClient(
            DashboardApiClient(server.url("/").toString()),
        ).discover().getOrThrow()

        assertTrue(discovered.isEmpty())
    }

    @Test
    fun scopedClient_confinesAllRequestsToPluginNamespace() = runTest {
        repeat(5) { server.enqueue(jsonResponse("""{"ok":true}""")) }
        val client = ScopedPluginApiClient(
            pluginId = "weather",
            dashboard = DashboardApiClient(server.url("/").toString()),
        )
        val payload = buildJsonObject { put("enabled", true) }

        client.get("forecast/hourly", mapOf("city" to "New York")).getOrThrow()
        client.post("settings", payload).getOrThrow()
        client.put("settings", payload).getOrThrow()
        client.patch("settings/theme", payload).getOrThrow()
        client.delete("settings/cache").getOrThrow()

        assertEquals(
            listOf(
                "GET /api/plugins/weather/forecast/hourly?city=New%20York",
                "POST /api/plugins/weather/settings",
                "PUT /api/plugins/weather/settings",
                "PATCH /api/plugins/weather/settings/theme",
                "DELETE /api/plugins/weather/settings/cache",
            ),
            List(5) {
                val request = server.takeRequest()
                "${request.method} ${request.path}"
            },
        )
    }

    @Test
    fun scopedClient_rejectsTraversalAbsoluteAndEncodedPathsWithoutNetworkCall() = runTest {
        val client = ScopedPluginApiClient(
            pluginId = "weather",
            dashboard = DashboardApiClient(server.url("/").toString()),
        )

        listOf("../config", "/api/status", "safe/../../config", "https://evil.test/x", "safe%2f..%2fconfig", "safe\\config")
            .forEach { path -> assertTrue(path, client.get(path).isFailure) }

        assertEquals(0, server.requestCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun scopedClient_rejectsUnsafePluginIdentity() {
        ScopedPluginApiClient(
            pluginId = "../other",
            dashboard = DashboardApiClient(server.url("/").toString()),
        )
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
