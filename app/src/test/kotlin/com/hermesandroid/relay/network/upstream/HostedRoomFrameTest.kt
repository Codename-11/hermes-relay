package com.hermesandroid.relay.network.upstream

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class HostedRoomFrameTest {
    @Test fun oversizedHostedFrameIsRejectedWithoutClosingOwningGateway() = runBlocking {
        val harness = GatewayClientHarness()
        harness.hostedRoomHandler = { _, _ -> JsonObject(emptyMap()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = GatewayChatClient(initialDashboardClient = DashboardApiClient(harness.server.url("/").toString().trimEnd('/'), OkHttpClient()),
            fixedSessionProfile = "default", okHttpClient = OkHttpClient(), callbackDispatcher = { it() }, scope = scope, rpcTimeoutMs = 500)
        try {
            client.hostedRoomRpc("groups.capabilities").getOrThrow()
            val tickets = harness.ticketMints.get()
            val oversized = client.hostedRoomRpc("groups.attachment.put", buildJsonObject { put("content_base64", "A".repeat(16 * 1024 * 1024)) })
            assertTrue(oversized.exceptionOrNull()?.message.orEmpty().contains("queue limit"))
            client.hostedRoomRpc("groups.capabilities").getOrThrow()
            assertEquals("Oversized frame must not force reconnection", tickets, harness.ticketMints.get())
            assertTrue(harness.rpcLog.none { it.first == "groups.attachment.put" })
        } finally { client.shutdown(); scope.cancel(); harness.shutdown() }
    }
}
