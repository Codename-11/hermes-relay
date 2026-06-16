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

        assertEquals(listOf("/status", "/verbose"), commands)
        assertFalse(commands.contains("/update"))
        assertTrue(commands.contains("/verbose"))
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
