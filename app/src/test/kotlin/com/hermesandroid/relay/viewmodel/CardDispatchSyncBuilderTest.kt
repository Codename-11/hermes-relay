package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.ChatMessage
import com.hermesandroid.relay.data.HermesCard
import com.hermesandroid.relay.data.HermesCardAction
import com.hermesandroid.relay.data.HermesCardDispatch
import com.hermesandroid.relay.data.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function JVM tests for [CardDispatchSyncBuilder]. Mirrors
 * [com.hermesandroid.relay.voice.VoiceIntentSyncBuilderTest] in shape and
 * assertion style so the two synthesis paths stay recognizably parallel.
 */
class CardDispatchSyncBuilderTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val seqIds = generateSequence(1) { it + 1 }.iterator()
    private fun nextId(): String = "test-${seqIds.next()}"

    @Test
    fun buildSyntheticMessages_emptyHistory_returnsEmpty() {
        val out = CardDispatchSyncBuilder.buildSyntheticMessages(emptyList())
        assertTrue(out.isEmpty())
    }

    @Test
    fun buildSyntheticMessages_noDispatches_returnsEmpty() {
        val history = listOf(
            assistantWithCards(
                id = "m1",
                cards = listOf(
                    HermesCard(
                        type = HermesCard.BuiltInTypes.APPROVAL_REQUEST,
                        title = "Run shell command?",
                        actions = listOf(
                            HermesCardAction(label = "Allow", value = "/approve"),
                        ),
                    ),
                ),
            ),
        )
        val out = CardDispatchSyncBuilder.buildSyntheticMessages(history)
        assertTrue(out.isEmpty())
    }

    @Test
    fun buildSyntheticMessages_singleDispatch_emitsAssistantPlusToolPair() {
        val card = HermesCard(
            type = HermesCard.BuiltInTypes.APPROVAL_REQUEST,
            title = "Run shell command?",
            id = "shell-001",
            actions = listOf(
                HermesCardAction(
                    label = "Allow",
                    value = "/approve",
                    style = HermesCardAction.Styles.PRIMARY,
                    mode = HermesCardAction.Modes.SLASH_COMMAND,
                ),
                HermesCardAction(
                    label = "Deny",
                    value = "/deny",
                    style = HermesCardAction.Styles.DANGER,
                ),
            ),
        )
        val dispatch = HermesCardDispatch(
            cardKey = "shell-001",
            actionValue = "/approve",
            timestamp = 1713614400000L,
        )
        val history = listOf(
            assistantWithCards(
                id = "m1",
                cards = listOf(card),
                dispatches = listOf(dispatch),
            ),
        )

        val out = CardDispatchSyncBuilder.buildSyntheticMessages(history) { nextId() }

        assertEquals(2, out.size)

        val assistantMsg = out[0].jsonObject
        assertEquals("assistant", assistantMsg["role"]?.jsonPrimitive?.content)
        assertEquals("", assistantMsg["content"]?.jsonPrimitive?.content)

        val call = assistantMsg["tool_calls"]!!.jsonArray[0].jsonObject
        val callId = call["id"]?.jsonPrimitive?.content
        assertNotNull(callId)
        assertTrue(callId!!.startsWith("call_carddispatch_"))

        val function = call["function"]!!.jsonObject
        assertEquals("hermes_card_action", function["name"]?.jsonPrimitive?.content)

        // arguments is a JSON-encoded STRING per OpenAI spec
        val argsStr = function["arguments"]?.jsonPrimitive?.content!!
        val args = json.parseToJsonElement(argsStr).jsonObject
        assertEquals("shell-001", args["card_key"]?.jsonPrimitive?.content)
        assertEquals("/approve", args["action_value"]?.jsonPrimitive?.content)
        assertEquals("approval_request", args["card_type"]?.jsonPrimitive?.content)
        assertEquals("Run shell command?", args["card_title"]?.jsonPrimitive?.content)
        assertEquals("Allow", args["action_label"]?.jsonPrimitive?.content)
        assertEquals("slash_command", args["action_mode"]?.jsonPrimitive?.content)
        assertEquals("primary", args["action_style"]?.jsonPrimitive?.content)

        val toolMsg = out[1].jsonObject
        assertEquals("tool", toolMsg["role"]?.jsonPrimitive?.content)
        assertEquals(callId, toolMsg["tool_call_id"]?.jsonPrimitive?.content)

        val toolContentStr = toolMsg["content"]?.jsonPrimitive?.content!!
        val toolContent = json.parseToJsonElement(toolContentStr).jsonObject
        assertEquals(true, toolContent["ok"]?.jsonPrimitive?.boolean)
        assertEquals(1713614400000L, toolContent["dispatched_at"]?.jsonPrimitive?.long)
    }

    @Test
    fun buildSyntheticMessages_skipsAlreadySyncedDispatches() {
        val card = HermesCard(
            type = HermesCard.BuiltInTypes.APPROVAL_REQUEST,
            id = "k1",
            actions = listOf(
                HermesCardAction(label = "Allow", value = "/approve"),
                HermesCardAction(label = "Deny", value = "/deny"),
            ),
        )
        val history = listOf(
            assistantWithCards(
                id = "m1",
                cards = listOf(card),
                dispatches = listOf(
                    HermesCardDispatch(
                        cardKey = "k1",
                        actionValue = "/approve",
                        timestamp = 1L,
                        syncedToServer = true, // already synced — must be skipped
                    ),
                    HermesCardDispatch(
                        cardKey = "k1",
                        actionValue = "/deny",
                        timestamp = 2L,
                    ),
                ),
            ),
        )
        val out = CardDispatchSyncBuilder.buildSyntheticMessages(history) { nextId() }
        assertEquals("only the unsynced dispatch emits a pair", 2, out.size)

        val args = out[0].jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
        assertTrue(args.contains("\"action_value\":\"/deny\""))
    }

    @Test
    fun buildSyntheticMessages_unknownCardKey_stillEmitsBareEnvelope() {
        // Dispatch survives even when the matching card got trimmed from
        // the rolling MAX_MESSAGES buffer. The envelope falls back to
        // just card_key + action_value — enough for an audit record.
        val history = listOf(
            assistantWithCards(
                id = "m1",
                cards = emptyList(),
                dispatches = listOf(
                    HermesCardDispatch(
                        cardKey = "idx:2",
                        actionValue = "/approve",
                        timestamp = 42L,
                    ),
                ),
            ),
        )
        val out = CardDispatchSyncBuilder.buildSyntheticMessages(history) { nextId() }
        assertEquals(2, out.size)

        val argsStr = out[0].jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
        val args = json.parseToJsonElement(argsStr).jsonObject
        assertEquals("idx:2", args["card_key"]?.jsonPrimitive?.content)
        assertEquals("/approve", args["action_value"]?.jsonPrimitive?.content)
        assertFalse("no card → no card_type", args.containsKey("card_type"))
        assertFalse("no card → no action_label", args.containsKey("action_label"))
    }

    @Test
    fun buildSyntheticMessages_fallbackCardKey_matchesIdxForm() {
        // Card without an explicit id — key formula is "idx:$index".
        // Builder must resolve dispatches keyed that way.
        val card = HermesCard(
            type = HermesCard.BuiltInTypes.LINK_PREVIEW,
            title = "Docs",
            actions = listOf(
                HermesCardAction(
                    label = "Open",
                    value = "https://example.com",
                    mode = HermesCardAction.Modes.OPEN_URL,
                ),
            ),
        )
        val history = listOf(
            assistantWithCards(
                id = "m1",
                cards = listOf(card),
                dispatches = listOf(
                    HermesCardDispatch(
                        cardKey = "idx:0",
                        actionValue = "https://example.com",
                        timestamp = 100L,
                    ),
                ),
            ),
        )
        val out = CardDispatchSyncBuilder.buildSyntheticMessages(history) { nextId() }
        assertEquals(2, out.size)

        val argsStr = out[0].jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
        val args = json.parseToJsonElement(argsStr).jsonObject
        assertEquals("link_preview", args["card_type"]?.jsonPrimitive?.content)
        assertEquals("Open", args["action_label"]?.jsonPrimitive?.content)
        assertEquals("open_url", args["action_mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun hasUnsynced_trueOnlyWhenAtLeastOneUnsynced() {
        val allSynced = listOf(
            assistantWithCards(
                id = "m1",
                cards = emptyList(),
                dispatches = listOf(
                    HermesCardDispatch("k", "v", 0L, syncedToServer = true),
                ),
            ),
        )
        assertFalse(CardDispatchSyncBuilder.hasUnsynced(allSynced))

        val oneUnsynced = listOf(
            assistantWithCards(
                id = "m2",
                cards = emptyList(),
                dispatches = listOf(
                    HermesCardDispatch("k", "v1", 0L, syncedToServer = true),
                    HermesCardDispatch("k", "v2", 0L, syncedToServer = false),
                ),
            ),
        )
        assertTrue(CardDispatchSyncBuilder.hasUnsynced(oneUnsynced))

        assertFalse(CardDispatchSyncBuilder.hasUnsynced(emptyList()))
    }

    // === helpers ===

    private fun assistantWithCards(
        id: String,
        cards: List<HermesCard> = emptyList(),
        dispatches: List<HermesCardDispatch> = emptyList(),
    ): ChatMessage = ChatMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        content = "",
        timestamp = System.currentTimeMillis(),
        cards = cards,
        cardDispatches = dispatches,
    )
}
