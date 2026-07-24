package com.hermesandroid.relay.network.upstream

import com.hermesandroid.relay.network.upstream.models.UsageInfo
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
        val interimMessages = mutableListOf<Pair<String, Boolean>>()
        val thinkingDeltas = mutableListOf<String>()
        val toolStarts = mutableListOf<Pair<String, String>>()
        val toolDones = mutableListOf<Pair<String, String?>>()
        val toolFails = mutableListOf<Pair<String, String?>>()
        val toolOutputRisks = mutableListOf<GatewayToolOutputRisk>()
        val toolGenerating = mutableListOf<String?>()
        val subagentEvents = mutableListOf<GatewaySubagentEvent>()
        val interactions = mutableListOf<GatewayAsk>()
        val interactionExpiries = mutableListOf<GatewayAskExpiry>()
        val interactionResolutions = mutableListOf<GatewayAskExpiry>()
        val statusUpdates = mutableListOf<Pair<String?, String>>()
        val statusClears = mutableListOf<String>()
        val sessionIds = mutableListOf<String>()
        var starts = 0
        var turnCompletes = 0
        var reconcileRequests = 0
        var completes = 0
        var usage: UsageInfo? = null
        var usageCalls = 0
        val errors = mutableListOf<String>()

        val callbacks = GatewayTurnCallbacks(
            onSessionId = { sessionIds += it },
            onStart = { starts++ },
            onTextDelta = { textDeltas += it },
            onInterimMessage = { text, alreadyStreamed -> interimMessages += text to alreadyStreamed },
            onThinkingDelta = { thinkingDeltas += it },
            onToolCallStart = { id, name -> toolStarts += id to name },
            onToolCallDone = { id, preview -> toolDones += id to preview },
            onToolCallFailed = { id, err -> toolFails += id to err },
            onToolOutputRisk = { toolOutputRisks += it },
            onTurnComplete = { turnCompletes++ },
            onReconcileRequired = { reconcileRequests++ },
            onComplete = { completes++ },
            onUsage = { usage = it; usageCalls++ },
            onError = { errors += it },
            onToolGenerating = { toolGenerating += it },
            onSubagentEvent = { subagentEvents += it },
            onInteractionRequest = { interactions += it },
            onInteractionExpired = { interactionExpiries += it },
            onInteractionResolved = { interactionResolutions += it },
            onStatusUpdate = { kind, text -> statusUpdates += kind to text },
            onStatusClear = { statusClears += it },
        )
    }

    private fun obj(jsonText: String): JsonObject =
        Json.parseToJsonElement(jsonText) as JsonObject

    private fun mapperWith(
        recorder: Recorder,
        dedupeAdjacentMessageStarts: Boolean = false,
    ) = GatewayEventMapper(recorder.callbacks, dedupeAdjacentMessageStarts)

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
    fun `canonical provider wait thinking is transient status not reasoning`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent(
            "thinking.delta",
            obj("""{"text":"⏳ waiting on gpt-5 — 30s with no output yet (provider may be slow)"}"""),
        )
        mapper.onEvent(
            "thinking.delta",
            obj("""{"text":"⚠ no output from provider for 60s — reconnecting..."}"""),
        )

        assertTrue(r.thinkingDeltas.isEmpty())
        assertEquals(2, r.statusUpdates.size)
        assertEquals(GatewayEventMapper.PROVIDER_WAIT_STATUS_KIND, r.statusUpdates.last().first)
        assertTrue(r.statusClears.isEmpty())

        mapper.onEvent("message.delta", obj("""{"text":"Back now"}"""))
        assertEquals(listOf(GatewayEventMapper.PROVIDER_WAIT_STATUS_KIND), r.statusClears)
    }

    @Test
    fun `legacy model thinking remains durable reasoning`() {
        val r = Recorder()
        mapperWith(r).onEvent("thinking.delta", obj("""{"text":"considering the tradeoffs"}"""))
        assertEquals(listOf("considering the tradeoffs"), r.thinkingDeltas)
        assertTrue(r.statusUpdates.isEmpty())
    }

    @Test
    fun `compaction status clears on resumed model tool and MoA activity only`() {
        listOf(
            "message.delta" to obj("""{"text":"resumed"}"""),
            "reasoning.delta" to obj("""{"text":"resumed"}"""),
            "thinking.delta" to obj("""{"text":"resumed"}"""),
            "tool.start" to obj("""{"tool_id":"t1","name":"terminal"}"""),
            "tool.progress" to obj("""{"tool_id":"t1","preview":"working"}"""),
            "moa.aggregating" to obj("""{"aggregator":"main"}"""),
        ).forEach { (type, payload) ->
            val r = Recorder()
            val mapper = mapperWith(r)
            mapper.onEvent(
                "status.update",
                obj("""{"kind":"compacting","text":"Compacting context…"}"""),
            )
            mapper.onEvent(type, payload)
            assertEquals(type, listOf(GatewayEventMapper.COMPACTION_STATUS_KIND), r.statusClears)
        }

        val unrelated = Recorder()
        val mapper = mapperWith(unrelated)
        mapper.onEvent("status.update", obj("""{"kind":"process","text":"Running terminal"}"""))
        mapper.onEvent("message.delta", obj("""{"text":"still running"}"""))
        assertTrue(unrelated.statusClears.isEmpty())
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
    fun `message complete backfills text when response was not previewed`() {
        val r = Recorder()
        val mapper = mapperWith(r)

        mapper.onEvent("message.complete", obj("""{"text":"Final answer","response_previewed":false}"""))

        assertEquals(listOf("Final answer"), r.textDeltas)
        assertEquals(1, r.completes)
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
    fun `message interim seals preview and previewed complete does not duplicate`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent(
            "message.interim",
            obj("""{"text":"candidate answer","already_streamed":false}"""),
        )
        mapper.onEvent(
            "message.complete",
            obj("""{"text":"candidate answer","response_previewed":true}"""),
        )

        assertEquals(listOf("candidate answer" to false), r.interimMessages)
        assertTrue(r.textDeltas.isEmpty())
        assertEquals(1, r.starts)
        assertEquals(1, r.completes)
    }

    @Test
    fun `message complete after interim backfills distinct final when not previewed`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent(
            "message.interim",
            obj("""{"text":"candidate answer","already_streamed":false}"""),
        )
        mapper.onEvent("message.complete", obj("""{"text":"final answer"}"""))

        assertEquals(listOf("candidate answer" to false), r.interimMessages)
        assertEquals(listOf("final answer"), r.textDeltas)
        assertEquals(1, r.completes)
    }

    @Test
    fun `already streamed interim seals without replaying text`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("message.delta", obj("""{"text":"candidate answer"}"""))
        mapper.onEvent(
            "message.interim",
            obj("""{"text":"candidate answer","already_streamed":true}"""),
        )

        assertEquals(listOf("candidate answer" to true), r.interimMessages)
        assertEquals(listOf("candidate answer"), r.textDeltas)
    }

    @Test
    fun `message complete suppresses exact intentional silence marker`() {
        val r = Recorder()
        val mapper = mapperWith(r)

        mapper.onEvent("message.complete", obj("""{"text":"[SILENT]"}"""))

        assertTrue(r.textDeltas.isEmpty())
        assertEquals(1, r.completes)
    }

    @Test
    fun `message delta suppresses exact intentional silence marker`() {
        val r = Recorder()
        val mapper = mapperWith(r)

        mapper.onEvent("message.delta", obj("""{"text":"NO_REPLY"}"""))
        mapper.onEvent("message.delta", obj("""{"text":"The profile said NO_REPLY for context."}"""))

        assertEquals(listOf("The profile said NO_REPLY for context."), r.textDeltas)
    }

    @Test
    fun `message interim already streamed seals without replaying text`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("message.delta", obj("""{"text":"candidate "}"""))
        mapper.onEvent(
            "message.interim",
            obj("""{"text":"candidate answer","already_streamed":true}"""),
        )
        mapper.onEvent(
            "message.complete",
            obj("""{"text":"candidate answer","response_previewed":true}"""),
        )

        assertEquals(listOf("candidate "), r.textDeltas)
        assertEquals(listOf("candidate answer" to true), r.interimMessages)
        assertEquals(1, r.completes)
    }

    @Test
    fun `message interim already streamed without text does not backfill previewed complete`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("message.delta", obj("""{"text":"candidate"}"""))
        mapper.onEvent("message.interim", obj("""{"already_streamed":true}"""))
        mapper.onEvent(
            "message.complete",
            obj("""{"text":"candidate","response_previewed":true}"""),
        )

        assertEquals(listOf("candidate"), r.textDeltas)
        assertEquals(listOf("" to true), r.interimMessages)
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
        assertEquals(2, r.starts)
    }

    @Test
    fun `adjacent duplicate message starts are one boundary`() {
        val r = Recorder()
        val mapper = mapperWith(r, dedupeAdjacentMessageStarts = true)

        mapper.onEvent("message.start", null)
        mapper.onEvent("message.start", null)

        assertEquals(1, r.starts)
        assertEquals(0, r.turnCompletes)
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

    // --- tool.generating (args still being written) ---

    @Test
    fun `tool generating fires callback and its id is adopted through to complete`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("tool.generating", obj("""{"name":"write_file"}"""))
        assertEquals(listOf<String?>("write_file"), r.toolGenerating)
        assertTrue(r.toolStarts.isEmpty())

        mapper.onEvent("tool.start", obj("""{"name":"write_file"}"""))
        mapper.onEvent("tool.complete", obj("""{"name":"write_file","summary":"wrote"}"""))
        val startId = r.toolStarts.single().first
        assertEquals(listOf(startId to "wrote"), r.toolDones)
    }

    @Test
    fun `generating pre-registrations are adopted FIFO per name`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("tool.generating", obj("""{"name":"read_file"}"""))
        mapper.onEvent("tool.generating", obj("""{"name":"read_file"}"""))
        mapper.onEvent("tool.start", obj("""{"name":"read_file"}"""))
        mapper.onEvent("tool.start", obj("""{"name":"read_file"}"""))
        assertEquals(2, r.toolStarts.size)
        assertEquals(2, r.toolStarts.map { it.first }.distinct().size)

        mapper.onEvent("tool.complete", obj("""{"name":"read_file","summary":"first"}"""))
        mapper.onEvent("tool.complete", obj("""{"name":"read_file","summary":"second"}"""))
        // Completes match the started ids in start order — FIFO held end to end.
        assertEquals(r.toolStarts.map { it.first }, r.toolDones.map { it.first })
        assertEquals(listOf("first", "second"), r.toolDones.map { it.second })
    }

    @Test
    fun `server tool id wins over a pending generating pre-registration`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("tool.generating", obj("""{"name":"web_search"}"""))
        mapper.onEvent("tool.start", obj("""{"tool_id":"t9","name":"web_search"}"""))
        assertEquals(listOf("t9" to "web_search"), r.toolStarts)
        // The pre-registration was consumed — a later id-less start mints fresh.
        mapper.onEvent("tool.start", obj("""{"name":"web_search"}"""))
        assertTrue(r.toolStarts[1].first != "t9")
    }

    @Test
    fun `tool generating without a name still fires the callback`() {
        val r = Recorder()
        mapperWith(r).onEvent("tool.generating", obj("""{}"""))
        assertEquals(listOf<String?>(null), r.toolGenerating)
    }

    // --- Subagent lifecycle ---

    @Test
    fun `subagent lifecycle maps phases and fields`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("subagent.start", obj("""{"goal":"research topic","task_index":1,"task_count":3}"""))
        mapper.onEvent("subagent.thinking", obj("""{"goal":"research topic","task_index":1,"task_count":3,"text":"hmm"}"""))
        mapper.onEvent(
            "subagent.tool",
            obj("""{"goal":"research topic","task_index":1,"task_count":3,"tool_name":"web_search","tool_preview":"searching docs","text":"searching docs"}"""),
        )
        mapper.onEvent("subagent.progress", obj("""{"goal":"research topic","task_index":1,"task_count":3,"text":"halfway"}"""))
        mapper.onEvent(
            "subagent.complete",
            obj("""{"goal":"research topic","task_index":1,"task_count":3,"status":"completed","summary":"found it","duration_seconds":12.5}"""),
        )

        assertEquals(
            listOf(
                GatewaySubagentEvent.Phase.START,
                GatewaySubagentEvent.Phase.THINKING,
                GatewaySubagentEvent.Phase.TOOL,
                GatewaySubagentEvent.Phase.PROGRESS,
                GatewaySubagentEvent.Phase.COMPLETE,
            ),
            r.subagentEvents.map { it.phase },
        )
        val start = r.subagentEvents[0]
        assertEquals(1, start.taskIndex)
        assertEquals(3, start.taskCount)
        assertEquals("research topic", start.goal)
        assertEquals("hmm", r.subagentEvents[1].preview)
        val tool = r.subagentEvents[2]
        assertEquals("web_search", tool.toolName)
        assertEquals("searching docs", tool.preview)
        assertEquals("halfway", r.subagentEvents[3].preview)
        val complete = r.subagentEvents[4]
        assertEquals("completed", complete.status)
        assertEquals("found it", complete.summary)
        assertEquals(12.5, complete.durationSeconds!!, 0.001)
        assertFalse(mapper.turnEnded)
    }

    @Test
    fun `subagent payload defaults when older emitters omit fields`() {
        val r = Recorder()
        mapperWith(r).onEvent("subagent.start", obj("""{}"""))
        val event = r.subagentEvents.single()
        assertEquals(0, event.taskIndex)
        assertEquals(1, event.taskCount)
        assertEquals("", event.goal)
        assertNull(event.status)
        assertNull(event.toolName)
        assertNull(event.durationSeconds)
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
    fun `gateway usage lifts context window fields`() {
        val usage = GatewayEventMapper.parseGatewayUsage(
            obj("""{"input":120,"output":45,"total":165,"context_used":41200,"context_max":48000,"context_percent":86}"""),
        )
        assertEquals(41200, usage?.contextUsed)
        assertEquals(48000, usage?.contextMax)
        assertEquals(86, usage?.contextPercent)
        assertEquals(120, usage?.resolvedInputTokens)
    }

    @Test
    fun `context fields stay null when the compressor block is absent`() {
        val usage = GatewayEventMapper.parseGatewayUsage(obj("""{"input":1,"output":1,"total":2}"""))
        assertNull(usage?.contextUsed)
        assertNull(usage?.contextMax)
        assertNull(usage?.contextPercent)
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

    // --- Interactive asks (structured GatewayAsk) ---

    @Test
    fun `clarify request maps to structured ask with request id and choices`() {
        val r = Recorder()
        mapperWith(r).onEvent(
            "clarify.request",
            obj("""{"request_id":"r1","question":"Which file?","choices":["a.txt","b.txt"]}"""),
        )
        val ask = r.interactions.single()
        assertEquals(GatewayAsk.Kind.CLARIFY, ask.kind)
        assertEquals("r1", ask.requestId)
        assertEquals("Which file?", ask.text)
        assertEquals(listOf("a.txt", "b.txt"), ask.choices)
        assertNull(ask.envVar)
        assertEquals(300, ask.timeoutSeconds)
    }

    @Test
    fun `clarify request without choices maps null choices`() {
        val r = Recorder()
        mapperWith(r).onEvent(
            "clarify.request",
            obj("""{"request_id":"r1","question":"Why?","choices":null}"""),
        )
        assertNull(r.interactions.single().choices)
    }

    @Test
    fun `approval request has no request id by contract`() {
        val r = Recorder()
        mapperWith(r).onEvent(
            "approval.request",
            obj("""{"command":"rm -rf build","description":"clean the build tree"}"""),
        )
        val ask = r.interactions.single()
        assertEquals(GatewayAsk.Kind.APPROVAL, ask.kind)
        assertNull(ask.requestId)
        assertEquals("rm -rf build — clean the build tree", ask.text)
        // Session-scoped — no countdown.
        assertEquals(0, ask.timeoutSeconds)
    }

    @Test
    fun `approval request preserves safe choices and smart deny context`() {
        val r = Recorder()
        mapperWith(r).onEvent(
            "approval.request",
            obj(
                """{"command":"deploy","choices":["once","session","always","deny","view","once"],"smart_denied":true}""",
            ),
        )
        val ask = r.interactions.single()
        assertEquals(listOf("once", "session", "always", "deny"), ask.choices)
        assertTrue(ask.smartDenied)
    }

    @Test
    fun `tool output risk maps deterministic non-low metadata only`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent(
            "tool.output_risk",
            obj(
                """{"tool_id":"t1","name":"browser","risk":"HIGH","findings":["prompt injection","prompt injection"," sensitive data "],"redacted":true}""",
            ),
        )
        mapper.onEvent(
            "tool.output_risk",
            obj("""{"tool_id":"t2","name":"read_file","risk":"low","findings":["safe"]}"""),
        )
        mapper.onEvent("tool.output_risk", obj("""{"name":"browser","risk":"critical"}"""))

        val risk = r.toolOutputRisks.single()
        assertEquals("t1", risk.toolCallId)
        assertEquals("browser", risk.toolName)
        assertEquals("high", risk.risk)
        assertEquals(listOf("prompt injection", "sensitive data"), risk.findings)
        assertTrue(risk.redacted)
    }

    @Test
    fun `approval request honors future explicit timeout metadata`() {
        val r = Recorder()
        mapperWith(r).onEvent(
            "approval.request",
            obj("""{"command":"ls","timeout_seconds":45}"""),
        )
        assertEquals(45, r.interactions.single().timeoutSeconds)
    }

    @Test
    fun `approval request ignores a stray request id`() {
        // Upstream approvals correlate per-session; even if some build sends
        // a request_id it must not be adopted (approval.respond has no slot for it).
        val r = Recorder()
        mapperWith(r).onEvent(
            "approval.request",
            obj("""{"command":"ls","request_id":"bogus"}"""),
        )
        assertNull(r.interactions.single().requestId)
    }

    @Test
    fun `sudo and secret requests carry request ids and timeouts`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("sudo.request", obj("""{"request_id":"r2"}"""))
        mapper.onEvent("secret.request", obj("""{"request_id":"r3","env_var":"API_KEY","prompt":"Enter API key"}"""))
        assertEquals(2, r.interactions.size)

        val sudo = r.interactions[0]
        assertEquals(GatewayAsk.Kind.SUDO, sudo.kind)
        assertEquals("r2", sudo.requestId)
        assertEquals(120, sudo.timeoutSeconds)

        val secret = r.interactions[1]
        assertEquals(GatewayAsk.Kind.SECRET, secret.kind)
        assertEquals("r3", secret.requestId)
        assertEquals("Enter API key", secret.text)
        assertEquals("API_KEY", secret.envVar)
        assertEquals(300, secret.timeoutSeconds)
    }

    @Test
    fun `interaction expiry events preserve correlation contract`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("clarify.expire", obj("""{"request_id":"r1"}"""))
        mapper.onEvent("sudo.expire", obj("""{"request_id":"r2"}"""))
        mapper.onEvent("secret.expire", obj("""{"request_id":"r3"}"""))
        mapper.onEvent("approval.expire", obj("""{"request_id":"ignored"}"""))

        assertEquals(
            listOf(
                GatewayAsk.Kind.CLARIFY,
                GatewayAsk.Kind.SUDO,
                GatewayAsk.Kind.SECRET,
                GatewayAsk.Kind.APPROVAL,
            ),
            r.interactionExpiries.map { it.kind },
        )
        assertEquals(listOf("r1", "r2", "r3", null), r.interactionExpiries.map { it.requestId })
    }

    @Test
    fun `replayed request is deduplicated and resumed turn resolves it`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        val request = obj("""{"request_id":"r1","question":"Choose","choices":["A","B"]}""")

        mapper.onEvent("clarify.request", request)
        mapper.onEvent("clarify.request", request)
        mapper.onEvent("message.delta", obj("""{"text":"Continuing"}"""))

        assertEquals(1, r.interactions.size)
        assertEquals(
            GatewayAskExpiry(GatewayAsk.Kind.CLARIFY, "r1"),
            r.interactionResolutions.single(),
        )
    }

    @Test
    fun `terminal read request is renderer plumbing not an actionable interaction`() {
        val r = Recorder()
        mapperWith(r).onEvent(
            "terminal.read.request",
            obj("""{"request_id":"terminal-1","start":0,"count":20}"""),
        )

        assertTrue(r.interactions.isEmpty())
    }

    // --- Forward compat ---

    @Test
    fun `unknown event types are silently ignored`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        mapper.onEvent("hologram.delta", obj("""{"text":"future"}"""))
        mapper.onEvent("session.info", obj("""{"model":"x"}"""))
        mapper.onEvent("status.update", obj("""{"kind":"busy"}"""))
        mapper.onEvent("tool.progress", obj("""{"name":"write_file","preview":"..."}"""))
        assertTrue(r.textDeltas.isEmpty())
        assertTrue(r.thinkingDeltas.isEmpty())
        assertTrue(r.errors.isEmpty())
        assertTrue(r.subagentEvents.isEmpty())
        assertTrue(r.toolGenerating.isEmpty())
        assertFalse(mapper.turnEnded)
    }

    @Test
    fun `null payloads do not crash`() {
        val r = Recorder()
        val mapper = mapperWith(r)
        listOf(
            "reasoning.delta", "thinking.delta", "message.delta", "message.interim", "message.start",
            "message.complete", "error", "clarify.request", "approval.request",
            "sudo.request", "secret.request", "reasoning.available",
            "clarify.expire", "sudo.expire", "secret.expire", "approval.expire",
            "tool.generating", "subagent.start", "subagent.thinking",
            "subagent.tool", "subagent.progress", "subagent.complete",
            "tool.output_risk", "moa.reference", "moa.aggregating",
        ).forEach { type ->
            // message.complete/error end the turn; use a fresh mapper for each
            mapperWith(Recorder()).onEvent(type, null)
        }
        // tool events with null payload on a shared mapper
        mapper.onEvent("tool.start", null)
        mapper.onEvent("tool.complete", null)
    }
}
