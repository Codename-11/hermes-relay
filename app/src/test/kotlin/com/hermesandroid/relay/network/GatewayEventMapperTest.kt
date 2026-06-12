package com.hermesandroid.relay.network

import com.hermesandroid.relay.network.models.UsageInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping-table tests for [GatewayEventMapper] — one fixture per row of the
 * event→callback contract, plus the backfill, synthetic-id, and
 * forward-compat (unknown event type) behaviours.
 */
class GatewayEventMapperTest {

    private class Recorder {
        val textDeltas = mutableListOf<String>()
        val thinkingDeltas = mutableListOf<String>()
        val toolStarts = mutableListOf<Pair<String, String>>()
        val toolDones = mutableListOf<Pair<String, String?>>()
        val toolFails = mutableListOf<Pair<String, String?>>()
        val interactions = mutableListOf<Pair<String, String>>()
        val sessionIds = mutableListOf<String>()
        var turnCompletes = 0
        var completes = 0
        var usage: UsageInfo? = null
        var usageCalls = 0
        val errors = mutableListOf<String>()

        val callbacks = GatewayTurnCallbacks(
            onSessionId = { sessionIds += it },
            onTextDelta = { textDeltas += it },
            onThinkingDelta = { thinkingDeltas += it },
            onToolCallStart = { id, name -> toolStarts += id to name },
            onToolCallDone = { id, preview -> toolDones += id to preview },
            onToolCallFailed = { id, err -> toolFails += id to err },
            onTurnComplete = { turnCompletes++ },
            onComplete = { completes++ },
            onUsage = { usage = it; usageCalls++ },
            onError = { errors += it },
            onInteractionRequest = { kind, detail -> interactions += kind to detail },
        )
    }

    private fun obj(jsonText: String): JsonObject =
        Json.parseToJsonElement(jsonText) as JsonObject

    private fun mapperWith(recorder: Recorder) = GatewayEventMapper(recorder.callbacks)

    // --- The feature: live thinking ---

    @Test
    fun `reasoning delta streams to onThinkingDelta`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("reasoning.delta", obj("""{"text":"pondering"}"""))
        mapper.onEvent("thinking.delta", obj("""{"text":" more"}"""))
        assertEquals(listOf("pondering", " more"), r.thinkingDeltas)
        assertFalse(mapper.turnEnded)
    }

    @Test
    fun `reasoning available backfills only when nothing streamed`() {
        val streamed = Recorder()
        val m1 = mapperWith(streamed)
        m1.onEvent("reasoning.delta", obj("""{"text":"live"}"""))
        m1.onEvent("reasoning.available", obj("""{"text":"post-hoc"}"""))
        assertEquals(listOf("live"), streamed.thinkingDeltas)

        val quiet = Recorder()
        val m2 = mapperWith(quiet)
        m2.onEvent("reasoning.available", obj("""{"text":"post-hoc"}"""))
        assertEquals(listOf("post-hoc"), quiet.thinkingDeltas)
    }

    // --- Text + turn lifecycle ---

    @Test
    fun `message delta streams text and complete ends turn`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("message.start", null)
        mapper.onEvent("message.delta", obj("""{"text":"Hel"}"""))
        mapper.onEvent("message.delta", obj("""{"text":"lo"}"""))
        mapper.onEvent("message.complete", obj("""{"text":"Hello","status":"complete"}"""))
        assertEquals(listOf("Hel", "lo"), r.textDeltas)
        assertEquals(1, r.completes)
        assertTrue(mapper.turnEnded)
        // Text already streamed — complete must NOT re-append it.
        assertEquals(2, r.textDeltas.size)
    }

    @Test
    fun `message complete backfills text and reasoning when nothing streamed`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent(
            "message.complete",
            obj("""{"text":"Full answer","reasoning":"the hidden why","status":"complete"}"""),
        )
        assertEquals(listOf("Full answer"), r.textDeltas)
        assertEquals(listOf("the hidden why"), r.thinkingDeltas)
        assertEquals(1, r.completes)
    }

    @Test
    fun `second message start signals turn boundary`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("message.start", null)
        assertEquals(0, r.turnCompletes)
        mapper.onEvent("message.start", null)
        assertEquals(1, r.turnCompletes)
    }

    @Test
    fun `events after turn end are ignored`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("message.complete", obj("""{"text":"done"}"""))
        mapper.onEvent("message.delta", obj("""{"text":"straggler"}"""))
        mapper.onEvent("error", obj("""{"message":"late"}"""))
        assertEquals(listOf("done"), r.textDeltas)
        assertEquals(1, r.completes)
        assertTrue(r.errors.isEmpty())
    }

    @Test
    fun `error event surfaces message and ends turn`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("error", obj("""{"message":"model exploded"}"""))
        assertEquals(listOf("model exploded"), r.errors)
        assertTrue(mapper.turnEnded)
        assertEquals(0, r.completes)
    }

    // --- Tools ---

    @Test
    fun `tool start and complete route by tool_id`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("tool.start", obj("""{"tool_id":"t1","name":"execute_code"}"""))
        mapper.onEvent("tool.complete", obj("""{"tool_id":"t1","name":"execute_code","summary":"ran fine"}"""))
        assertEquals(listOf("t1" to "execute_code"), r.toolStarts)
        assertEquals(listOf("t1" to "ran fine"), r.toolDones)
    }

    @Test
    fun `tool complete with error routes to failed`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("tool.start", obj("""{"tool_id":"t2","name":"web_search"}"""))
        mapper.onEvent("tool.complete", obj("""{"tool_id":"t2","name":"web_search","error":"timeout"}"""))
        assertEquals(listOf("t2" to "timeout"), r.toolFails)
        assertTrue(r.toolDones.isEmpty())
    }

    @Test
    fun `missing tool ids get synthesized and matched FIFO by name`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("tool.start", obj("""{"name":"read_file"}"""))
        mapper.onEvent("tool.start", obj("""{"name":"read_file"}"""))
        mapper.onEvent("tool.complete", obj("""{"name":"read_file","summary":"first"}"""))
        mapper.onEvent("tool.complete", obj("""{"name":"read_file","summary":"second"}"""))
        assertEquals(2, r.toolStarts.size)
        val (firstId, secondId) = r.toolStarts.map { it.first }
        assertEquals(listOf(firstId to "first", secondId to "second"), r.toolDones)
    }

    @Test
    fun `tool complete with no id and no open start is dropped`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("tool.complete", obj("""{"name":"mystery"}"""))
        assertTrue(r.toolDones.isEmpty())
        assertTrue(r.toolFails.isEmpty())
    }

    // --- Usage translation (tui_gateway key names, not SSE names) ---

    @Test
    fun `gateway usage keys translate to UsageInfo`() {
        val usage = GatewayEventMapper.parseGatewayUsage(
            obj("""{"input":120,"output":45,"total":165,"model":"hermes-4"}"""),
        )
        assertEquals(120, usage?.resolvedInputTokens)
        assertEquals(45, usage?.resolvedOutputTokens)
        assertEquals(165, usage?.resolvedTotalTokens)
    }

    @Test
    fun `gateway usage falls back to prompt and completion keys`() {
        val usage = GatewayEventMapper.parseGatewayUsage(
            obj("""{"prompt":10,"completion":5}"""),
        )
        assertEquals(10, usage?.resolvedInputTokens)
        assertEquals(5, usage?.resolvedOutputTokens)
    }

    @Test
    fun `empty or missing usage maps to null`() {
        assertNull(GatewayEventMapper.parseGatewayUsage(null))
        assertNull(GatewayEventMapper.parseGatewayUsage(obj("""{"model":"x"}""")))
    }

    @Test
    fun `message complete forwards usage`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent(
            "message.complete",
            obj("""{"text":"hi","usage":{"input":7,"output":3,"total":10}}"""),
        )
        assertEquals(7, r.usage?.resolvedInputTokens)
        assertEquals(3, r.usage?.resolvedOutputTokens)
    }

    // --- Interactive asks (display-only MVP) ---

    @Test
    fun `clarify request surfaces question with choices`() {
        val r = Recorder()
        mapperWith(r).onEvent(
            "clarify.request",
            obj("""{"request_id":"r1","question":"Which file?","choices":["a.txt","b.txt"]}"""),
        )
        assertEquals(listOf("clarification" to "Which file? (a.txt / b.txt)"), r.interactions)
    }

    @Test
    fun `approval request surfaces command and description`() {
        val r = Recorder()
        mapperWith(r).onEvent(
            "approval.request",
            obj("""{"command":"rm -rf build","description":"clean the build tree"}"""),
        )
        assertEquals(listOf("approval" to "rm -rf build — clean the build tree"), r.interactions)
    }

    @Test
    fun `sudo and secret requests surface as interactions`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("sudo.request", obj("""{"request_id":"r2"}"""))
        mapper.onEvent("secret.request", obj("""{"request_id":"r3","env_var":"API_KEY","prompt":"Enter API key"}"""))
        assertEquals(2, r.interactions.size)
        assertEquals("sudo access", r.interactions[0].first)
        assertEquals("a secret" to "Enter API key", r.interactions[1])
    }

    // --- Forward compat ---

    @Test
    fun `unknown event types are silently ignored`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("hologram.delta", obj("""{"text":"future"}"""))
        mapper.onEvent("session.info", obj("""{"model":"x"}"""))
        mapper.onEvent("status.update", obj("""{"kind":"busy"}"""))
        mapper.onEvent("subagent.start", obj("""{"goal":"g","task_index":0}"""))
        mapper.onEvent("tool.generating", obj("""{"name":"write_file"}"""))
        mapper.onEvent("tool.progress", obj("""{"name":"write_file","preview":"..."}"""))
        assertTrue(r.textDeltas.isEmpty())
        assertTrue(r.thinkingDeltas.isEmpty())
        assertTrue(r.errors.isEmpty())
        assertFalse(mapper.turnEnded)
    }

    @Test
    fun `null payloads do not crash`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        listOf(
            "reasoning.delta", "thinking.delta", "message.delta", "message.start",
            "message.complete", "error", "clarify.request", "approval.request",
            "sudo.request", "secret.request", "reasoning.available",
        ).forEach { type ->
            // message.complete/error end the turn; use a fresh mapper for each
            mapperWith(Recorder()).onEvent(type, null)
        }
        // tool events with null payload on a shared mapper
        mapper.onEvent("tool.start", null)
        mapper.onEvent("tool.complete", null)
    }
}
