package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.data.*
import java.io.File
import java.net.ServerSocket
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

/** Runs the repository's declarative Python wire fixture against the production Kotlin client. */
class HostedRoomPythonFixtureTest {
    @Test fun declarativeResponseLossReplaysOneCanonicalSend() = runBlocking {
        val root = File("../test-fixtures/vanilla-gateway").canonicalFile
        check(root.isDirectory) { "Repository fixture is required" }
        val port = ServerSocket(0).use { it.localPort }
        val output = File("build/ui-evidence/hosted-python-fixture.log").apply { parentFile?.mkdirs() }
        val builder = ProcessBuilder("python", "-m", "vanilla_gateway.cli", "hosted_room_response_loss", "--host", "127.0.0.1", "--port", port.toString())
            .redirectErrorStream(true).redirectOutput(output)
        builder.environment()["PYTHONPATH"] = root.path
        val process = builder.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = GatewayChatClient(initialDashboardClient = DashboardApiClient("http://127.0.0.1:$port", OkHttpClient()),
            fixedSessionProfile = "default", okHttpClient = OkHttpClient(), callbackDispatcher = { it() }, scope = scope, rpcTimeoutMs = 500)
        try {
            withTimeout(10_000) {
                while (true) {
                    check(process.isAlive) { "Python fixture exited: ${output.readText()}" }
                    if (runCatching { java.net.Socket("127.0.0.1", port).use {} }.isSuccess) break
                    delay(100)
                }
            }
            val caps = client.hostedRoomRpc("groups.capabilities").getOrThrow()
            assertTrue(HostedRoomCapabilities.parse(caps).writable)
            val params = buildJsonObject { put("room_id", "fixture-room"); put("event_id", "fixture-first-send")
                put("payload", buildJsonObject { put("text", "@reviewer Review this draft.") }) }
            assertTrue(client.hostedRoomRpc("groups.send", params).isFailure)
            val replay = client.hostedRoomRpc("groups.send", params).getOrThrow()
            assertTrue((replay["event"] as JsonObject).roomBool("idempotent"))
            val page = client.hostedRoomRpc("groups.log", buildJsonObject { put("room_id", "fixture-room"); put("since_seq", 0) }).getOrThrow()
            assertEquals(1, page.roomObjects("events").size)
            assertEquals(hostedUserEventId("fixture-first-send"), page.roomObjects("events").single().roomString("event_id"))
        } finally {
            client.shutdown(); scope.cancel(); process.destroy()
            withContext(Dispatchers.IO) { if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly() }
        }
    }
}
