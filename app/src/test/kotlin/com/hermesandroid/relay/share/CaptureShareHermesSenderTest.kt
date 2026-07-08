package com.hermesandroid.relay.share

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CaptureShareHermesSenderTest {
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
    fun createCapture_postsT08CaptureContract() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":"cap_123"}"""),
        )
        val sender = CaptureShareHermesSender(server.url("/").toString(), apiKey = "secret")

        val result = sender.createCapture(
            SharePayload(
                text = "Shared text",
                mimeType = "text/plain",
                sourceApp = "Chrome",
                timestamp = "2026-07-07T12:00:00Z",
            ),
        ).getOrThrow()
        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject

        assertEquals("cap_123", result.id)
        assertEquals("/api/captures", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertEquals("Shared text", body["payload"]!!.jsonObject["raw_text"]!!.jsonPrimitive.content)
        assertEquals("text/plain", body["payload"]!!.jsonObject["mime_type"]!!.jsonPrimitive.content)
        assertEquals("save", body["payload"]!!.jsonObject["requested_action"]!!.jsonPrimitive.content)
        assertEquals("save", body["proposed_routing"]!!.jsonObject["requested_action"]!!.jsonPrimitive.content)
        assertEquals("Chrome", body["provenance"]!!.jsonObject["source_app"]!!.jsonPrimitive.content)
    }

    @Test
    fun actOnCapture_postsCanonicalActionContract() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"status":"already-handled"}"""),
        )
        val sender = CaptureShareHermesSender(server.url("/").toString(), apiKey = "secret")

        val result = sender.actOnCapture("cap_123", ShareAction.OpenOnDesktop).getOrThrow()
        val request = server.takeRequest()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject

        assertTrue(result.isTerminalSuccess)
        assertEquals("/api/captures/cap_123/act", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertEquals("open_on_desktop", body["action"]!!.jsonPrimitive.content)
        assertEquals("unknown", body["domain_tag"]!!.jsonPrimitive.content)
        assertEquals("android-review-ui", body["renderer"]!!.jsonPrimitive.content)
        assertEquals("cap_123", body["renderer_id"]!!.jsonPrimitive.content)
    }
}
