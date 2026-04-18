package com.hermesandroid.relay.auth

import com.hermesandroid.relay.data.Profile
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [AuthManager.parseAgentProfiles] — the Pass 2 replacement
 * for the broken `_sessionLabels` parser. [AuthManager] itself can't be
 * instantiated here (Android Context, EncryptedSharedPrefs), so we target
 * the pure parser the companion exposes.
 *
 * Mirrors the setup style of `ConnectionStoreTest` (plain JUnit, no runBlocking
 * needed since this is pure synchronous parsing).
 */
class AuthManagerProfilesParseTest {

    @Test
    fun parsesWellFormedEntries() {
        val profilesArray = buildJsonArray {
            add(buildJsonObject {
                put("name", "coder")
                put("model", "claude-opus-4-6")
                put("description", "Pair programmer")
            })
            add(buildJsonObject {
                put("name", "researcher")
                put("model", "gpt-4o")
                put("description", "Deep-dive analyst")
            })
        }

        val parsed = AuthManager.parseAgentProfiles(profilesArray)

        assertEquals(2, parsed.size)
        assertEquals(
            Profile(
                name = "coder",
                model = "claude-opus-4-6",
                description = "Pair programmer",
            ),
            parsed[0],
        )
        assertEquals(
            Profile(
                name = "researcher",
                model = "gpt-4o",
                description = "Deep-dive analyst",
            ),
            parsed[1],
        )
    }

    @Test
    fun dropsEntriesWithoutAName() {
        // A profile without a name is unusable in the picker (no label to
        // render, no way to reference it), so we drop such entries
        // defensively rather than surfacing a garbage chip.
        val profilesArray = buildJsonArray {
            add(buildJsonObject {
                put("name", "good")
                put("model", "m1")
                put("description", "valid")
            })
            add(buildJsonObject {
                // intentionally missing "name"
                put("model", "m2")
                put("description", "broken")
            })
            add(buildJsonObject {
                put("name", "also-good")
                put("model", "m3")
            })
        }

        val parsed = AuthManager.parseAgentProfiles(profilesArray)

        assertEquals(2, parsed.size)
        assertEquals(listOf("good", "also-good"), parsed.map { it.name })
    }

    @Test
    fun defaultsMissingOptionalFields() {
        val profilesArray = buildJsonArray {
            add(buildJsonObject {
                put("name", "sparse")
                // model and description absent
            })
        }

        val parsed = AuthManager.parseAgentProfiles(profilesArray)

        assertEquals(1, parsed.size)
        assertEquals("sparse", parsed[0].name)
        assertEquals("unknown", parsed[0].model)
        assertEquals("", parsed[0].description)
    }

    @Test
    fun tolerantOfNonObjectEntries() {
        // Pathological but survivable — a malformed server shouldn't crash
        // the pairing handshake. Non-object entries are dropped.
        val profilesArray = buildJsonArray {
            add(buildJsonObject {
                put("name", "good")
                put("model", "m1")
            })
            // raw string mixed in
            add(kotlinx.serialization.json.JsonPrimitive("garbage"))
        }

        val parsed = AuthManager.parseAgentProfiles(profilesArray)

        assertEquals(1, parsed.size)
        assertEquals("good", parsed[0].name)
    }

    @Test
    fun emptyArrayProducesEmptyList() {
        val parsed = AuthManager.parseAgentProfiles(buildJsonArray { })
        assertTrue(parsed.isEmpty())
    }
}
