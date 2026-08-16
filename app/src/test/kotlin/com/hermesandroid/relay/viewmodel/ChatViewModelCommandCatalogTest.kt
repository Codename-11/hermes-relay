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

    @Test
    fun parseCommandsCatalog_ranksOnlySkillSlotsAndBoundsMetadata() {
        val catalog = buildJsonObject {
            put(
                "pairs",
                JsonArray(
                    listOf(
                        commandPair("status", "Show status"),
                        commandPair("research", "Research"),
                        commandPair("help", "Show help"),
                        commandPair("work", "Worktree"),
                    ),
                ),
            )
            put("skills", buildJsonObject {
                put("/research", buildJsonObject {
                    put("usage", 60)
                    put("origin", "bundled")
                })
                put("/work", buildJsonObject {
                    put("usage", 172)
                    put("origin", "local")
                })
            })
        }

        val commands = parseCommandsCatalog(catalog)

        assertEquals(listOf("/status", "/work", "/help", "/research"), commands.map { it.command })
        assertEquals(172, commands[1].usageRank)
        assertEquals("local", commands[1].origin)
        assertEquals(listOf("/status", "/help"), commands.filter { it.usageRank == 0 }.map { it.command })
    }

    @Test
    fun deferredConfigResult_acceptsCurrentBooleanShapes() {
        assertTrue(isDeferredConfigResult(buildJsonObject { put("deferred", true) }))
        assertTrue(isDeferredConfigResult(buildJsonObject { put("deferred", "1") }))
        assertFalse(isDeferredConfigResult(buildJsonObject { put("deferred", false) }))
        assertFalse(isDeferredConfigResult(buildJsonObject { }))
    }

    @Test
    fun parseDashboardPersonalityConfig_readsWrappedAndDirectConfig() {
        val config = buildJsonObject {
            put("model", "gpt-test")
            put("agent", buildJsonObject {
                put("personalities", buildJsonObject {
                    put("concise", "Be concise")
                    put("coach", buildJsonObject {
                        put("description", "A supportive coach")
                        put("system_prompt", "Coach the user")
                        put("tone", "supportive")
                        put("style", "ask questions")
                    })
                })
            })
            put("display", buildJsonObject { put("personality", "concise") })
        }

        listOf(config, buildJsonObject { put("config", config) }).forEach { root ->
            val parsed = parseDashboardPersonalityConfig(root)
            assertEquals(listOf("concise", "coach"), parsed.names)
            assertEquals("Be concise", parsed.prompts["concise"])
            assertEquals(
                "Coach the user\nTone: supportive\nStyle: ask questions",
                parsed.prompts["coach"],
            )
            assertEquals("concise", parsed.defaultName)
            assertEquals("gpt-test", parsed.modelName)
        }
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
