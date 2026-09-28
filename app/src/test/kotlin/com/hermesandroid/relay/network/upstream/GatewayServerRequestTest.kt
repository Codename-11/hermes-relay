package com.hermesandroid.relay.network.upstream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class GatewayServerRequestTest {
    private fun frame(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test fun idsRetainTheirWireTypeAndRejectInvalidKinds() {
        val string = GatewayServerRequest.parse(frame("""{"id":"1","method":"sudo","params":{"session_id":"s"}}"""))!!
        val number = GatewayServerRequest.parse(frame("""{"id":1,"method":"sudo","params":{"session_id":"s"}}"""))!!
        assertNotEquals(string.key, number.key)
        assertEquals(JsonPrimitive("1"), string.id)
        assertEquals(JsonPrimitive(1), number.id)
        for (id in listOf("null", "true", "[]", "{}")) {
            assertNull(GatewayServerRequest.id(frame("""{"id":$id}""")))
        }
    }

    @Test fun nativeApprovalIgnoresQueueIdAndKeepsSafetyRestrictions() {
        val request = GatewayServerRequest.parse(frame("""{
            "id":"srq-1","method":"approval","params":{"session_id":"s","request_id":"queue-9",
            "choices":["once","session","always","deny"],"allow_session":false,"allow_permanent":false}}
        """))!!
        val ask = GatewayEventMapper.interactionRequest(request.eventType, request.payload)!!
        assertTrue(ask.serverRequest)
        assertEquals(request.key, ask.requestId)
        assertEquals(listOf("once", "deny"), ask.choices)
    }

    @Test fun nativeSecretsDoNotInventAClientDeadline() {
        for (method in listOf("sudo", "secret")) {
            val request = GatewayServerRequest.parse(frame("""{"id":"srq-$method","method":"$method","params":{"session_id":"s"}}"""))!!
            assertEquals(0, GatewayEventMapper.interactionRequest(request.eventType, request.payload)!!.timeoutSeconds)
        }
    }
}
