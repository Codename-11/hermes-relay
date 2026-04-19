package com.hermesandroid.relay.auth

import com.hermesandroid.relay.data.Profile
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // --- Pass 3: system_message field (Worker R1 contract) --------------

    @Test
    fun parsesSystemMessageWhenPresent() {
        // Happy path — profile has a SOUL.md on the server, so the relay
        // populates system_message with its contents.
        val profilesArray = buildJsonArray {
            add(buildJsonObject {
                put("name", "mizu")
                put("model", "claude-opus-4-6")
                put("description", "Axiom-Labs Public Ops")
                put(
                    "system_message",
                    "You are Mizu, the public-facing agent at Axiom-Labs.",
                )
            })
        }

        val parsed = AuthManager.parseAgentProfiles(profilesArray)

        assertEquals(1, parsed.size)
        assertEquals(
            "You are Mizu, the public-facing agent at Axiom-Labs.",
            parsed[0].systemMessage,
        )
    }

    @Test
    fun treatsExplicitNullSystemMessageAsNull() {
        // Per the R1 contract: system_message may be JSON null when the
        // profile has no SOUL.md on disk. Must deserialize to Kotlin null
        // so ChatViewModel's `systemMessage?.isNotBlank() == true` falls
        // through to the personality fallback.
        val profilesArray = buildJsonArray {
            add(buildJsonObject {
                put("name", "bare")
                put("model", "claude-opus-4-6")
                put("description", "")
                put("system_message", JsonNull)
            })
        }

        val parsed = AuthManager.parseAgentProfiles(profilesArray)

        assertEquals(1, parsed.size)
        assertNull(parsed[0].systemMessage)
    }

    @Test
    fun defaultsSystemMessageToNullWhenKeyMissing() {
        // Older relay versions (pre-R1) won't send the key at all.
        // Parser must default to null — not crash, not empty string.
        val profilesArray = buildJsonArray {
            add(buildJsonObject {
                put("name", "legacy")
                put("model", "claude-opus-4-6")
                put("description", "Pre-R1 server")
            })
        }

        val parsed = AuthManager.parseAgentProfiles(profilesArray)

        assertEquals(1, parsed.size)
        assertNull(parsed[0].systemMessage)
    }

    // --- v0.7.0 runtime metadata: gateway_running / has_soul / skill_count -

    @Test
    fun parsesRuntimeMetadataWhenPresent() {
        // Happy path — a fully-populated v0.7.0 server response.
        val profilesArray = buildJsonArray {
            add(buildJsonObject {
                put("name", "mizu")
                put("model", "anthropic/claude-opus-4")
                put("description", "Axiom-Labs Public Ops")
                put("system_message", "You are Mizu.")
                put("gateway_running", true)
                put("has_soul", true)
                put("skill_count", 12)
            })
        }

        val parsed = AuthManager.parseAgentProfiles(profilesArray)

        assertEquals(1, parsed.size)
        assertTrue(parsed[0].gatewayRunning)
        assertTrue(parsed[0].hasSoul)
        assertEquals(12, parsed[0].skillCount)
    }

    @Test
    fun defaultsRuntimeMetadataWhenKeysMissing() {
        // Older (pre-v0.7.0) relays won't send any of the three runtime
        // metadata keys. Parser must default gateway_running=false,
        // has_soul=false, skill_count=0 — backward-compat contract.
        val profilesArray = buildJsonArray {
            add(buildJsonObject {
                put("name", "legacy")
                put("model", "claude-opus-4-6")
                put("description", "Pre-v0.7 server")
            })
        }

        val parsed = AuthManager.parseAgentProfiles(profilesArray)

        assertEquals(1, parsed.size)
        assertFalse(parsed[0].gatewayRunning)
        assertFalse(parsed[0].hasSoul)
        assertEquals(0, parsed[0].skillCount)
    }

    @Test
    fun fallsBackToDefaultsOnMalformedRuntimeMetadata() {
        // Survivable garbage — booleans expressed as strings, skill_count
        // as a string. Must NOT throw; must fall through to safe defaults
        // so one misbehaving field can't poison the whole profile.
        val profilesArray = buildJsonArray {
            add(buildJsonObject {
                put("name", "malformed")
                put("model", "m1")
                put("gateway_running", JsonPrimitive("yes-please"))
                put("has_soul", JsonPrimitive("nope"))
                put("skill_count", JsonPrimitive("twelve"))
            })
        }

        val parsed = AuthManager.parseAgentProfiles(profilesArray)

        assertEquals(1, parsed.size)
        // String "yes-please" is not a JSON-native bool, booleanOrNull → null → false.
        assertFalse(parsed[0].gatewayRunning)
        assertFalse(parsed[0].hasSoul)
        // "twelve" doesn't parse as Int, intOrNull → null → 0.
        assertEquals(0, parsed[0].skillCount)
        // Rest of the profile still usable.
        assertEquals("malformed", parsed[0].name)
        assertEquals("m1", parsed[0].model)
    }

    @Test
    fun fallsBackToDefaultsOnNullRuntimeMetadata() {
        // Explicit JSON null for any of the three fields — a server bug
        // but not a fatal one. Parser must treat it as absent.
        val profilesArray = buildJsonArray {
            add(buildJsonObject {
                put("name", "nulls")
                put("model", "m1")
                put("gateway_running", JsonNull)
                put("has_soul", JsonNull)
                put("skill_count", JsonNull)
            })
        }

        val parsed = AuthManager.parseAgentProfiles(profilesArray)

        assertEquals(1, parsed.size)
        assertFalse(parsed[0].gatewayRunning)
        assertFalse(parsed[0].hasSoul)
        assertEquals(0, parsed[0].skillCount)
    }
}
