package com.hermesandroid.relay.network.relay

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RelayHttpClientModelCapabilitiesTest {
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
    fun postsBoundedProfileScopedPairsAndParsesExactOverlay() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                  "schema_version":1,
                  "contract_version":"relay-reasoning-v1",
                  "capabilities":[{
                    "provider":"opencode",
                    "model":"deepseek-v3",
                    "reasoning":true,
                    "reasoning_efforts":["low","max"],
                    "reasoning_efforts_exact":true,
                    "source":"relay-provider-policy"
                  }]
                }""".trimIndent(),
            ),
        )
        val client = client(token = "paired-token")
        val rows = (0..130).map {
            RelayHttpClient.ModelCapabilityRequestRow("provider-$it", "model-$it")
        }

        val response = client.fetchModelCapabilities(rows, profile = "research").getOrThrow()
        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject

        assertEquals("/relay/model-capabilities", request.path)
        assertEquals("Bearer paired-token", request.getHeader("Authorization"))
        assertEquals("research", body.getValue("profile").jsonPrimitive.content)
        assertEquals(
            RelayHttpClient.MAX_MODEL_CAPABILITY_ROWS,
            body.getValue("models").jsonArray.size,
        )
        assertEquals(listOf("low", "max"), response?.capabilities?.single()?.reasoningEfforts)
        assertEquals(true, response?.capabilities?.single()?.reasoningEffortsExact)
    }

    @Test
    fun olderRelay404IsOptional() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val response = client(token = "paired-token").fetchModelCapabilities(
            listOf(RelayHttpClient.ModelCapabilityRequestRow("openai", "gpt-5.5")),
        ).getOrThrow()

        assertNull(response)
    }

    @Test
    fun vanillaUnpairedPathDoesNotCallRelay() = runTest {
        val response = client(token = null).fetchModelCapabilities(
            listOf(RelayHttpClient.ModelCapabilityRequestRow("openai", "gpt-5.5")),
        ).getOrThrow()

        assertNull(response)
        assertEquals(0, server.requestCount)
    }

    private fun client(token: String?) = RelayHttpClient(
        okHttpClient = OkHttpClient(),
        relayUrlProvider = { server.url("/").toString() },
        sessionTokenProvider = { token },
    )
}
