package com.hermesandroid.relay.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ChatViewModelCommandCatalogTest {

    @Test
    fun parseCommandsCatalog_filtersUpdateAndStrictCliOnlyCommands() {
        val catalog = buildJsonObject {
            put(
                "pairs",
                JsonArray(
                    listOf(
                        commandPair("status", "Show status"),
                        commandPair("fast", "Toggle fast mode"),
                        commandPair("update", "Update Hermes Agent"),
                        commandPair(
                            "clear",
                            "Clear terminal",
                            buildJsonObject { put("cli_only", true) },
                        ),
                        commandPair(
                            "verbose",
                            "Cycle tool progress",
                            buildJsonObject {
                                put("cli_only", true)
                                put("gateway_config_gate", "display.tool_progress_command")
                            },
                        ),
                    ),
                ),
            )
        }

        val commands = parseCommandsCatalog(catalog).map { it.command }

        assertEquals(listOf("/status", "/fast", "/verbose"), commands)
        assertTrue(commands.contains("/fast"))
        assertFalse(commands.contains("/update"))
        assertTrue(commands.contains("/verbose"))
    }

    @Test
    fun mobileBlockedSlashNotice_explainsUpdate() {
        val notice = mobileBlockedSlashNotice("update")

        assertEquals(
            "/update is only available from messaging platforms. Run `hermes update` from the terminal.",
            notice,
        )
    }

    private fun commandPair(
        name: String,
        description: String,
        metadata: JsonObject? = null,
    ): JsonArray = JsonArray(
        listOfNotNull(
            JsonPrimitive(name),
            JsonPrimitive(description),
            metadata,
        ),
    )
}
