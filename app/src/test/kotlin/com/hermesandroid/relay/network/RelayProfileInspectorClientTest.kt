package com.hermesandroid.relay.network

import com.hermesandroid.relay.data.ProfileConfigResponse
import com.hermesandroid.relay.data.ProfileMemoryResponse
import com.hermesandroid.relay.data.ProfileSkillsResponse
import com.hermesandroid.relay.data.ProfileSoulResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLEncoder

/**
 * Unit tests for [RelayProfileInspectorClient] and the data models backing
 * it. OkHttp call execution requires the Android framework (Handler/Looper),
 * so the client's happy-path network behavior is exercised on-device in
 * integration testing. These JVM-local tests cover:
 *
 *  - Wire-contract round-trip for all four response models (happy path).
 *  - Optional-field defaults (`truncated`, `readonly`) when the relay
 *    omits them — matches the Python worker's intended behavior.
 *  - Tolerance for unknown keys (forward-compat if the relay adds fields).
 *  - Parse failures surface as `SerializationException` — the client wraps
 *    these as `Result.failure(IOException)` so callers can render the
 *    message directly.
 *  - URL-encoding of profile names with spaces / non-ASCII characters.
 *
 * MockWebServer isn't in `testImplementation` deps (see
 * `app/build.gradle.kts`), and the spec forbids adding a dependency for
 * this slice, so we don't stand up a real HTTP server here. The on-device
 * relay smoke tests cover end-to-end behavior.
 */
class RelayProfileInspectorClientTest {

    // The same JSON configuration the client itself uses — we duplicate
    // the flags (`ignoreUnknownKeys`, `isLenient`, `coerceInputValues`,
    // `explicitNulls`) here so the test exercises the same parser config.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    // ---------------------------------------------------------------
    // ProfileConfigResponse
    // ---------------------------------------------------------------

    @Test
    fun profileConfig_parsesHappyPath() {
        val body = """
            {
                "profile": "axiom",
                "path": "/home/bailey/.hermes/profiles/axiom/config.yaml",
                "config": {"model": {"default": "claude-sonnet-4-5"}, "tools": {"enabled": true}},
                "readonly": true
            }
        """.trimIndent()

        val parsed = json.decodeFromString<ProfileConfigResponse>(body)
        assertEquals("axiom", parsed.profile)
        assertEquals("/home/bailey/.hermes/profiles/axiom/config.yaml", parsed.path)
        assertTrue(parsed.readonly)

        val modelBlock = parsed.config["model"]
        assertNotNull(modelBlock)
        val toolsBlock = parsed.config["tools"]
        assertNotNull(toolsBlock)
    }

    @Test
    fun profileConfig_readonly_defaultsToFalseWhenOmitted() {
        // Python worker may omit `readonly` — it should default to false.
        val body = """
            {
                "profile": "axiom",
                "path": "/tmp/config.yaml",
                "config": {"model": "claude-sonnet-4-5"}
            }
        """.trimIndent()

        val parsed = json.decodeFromString<ProfileConfigResponse>(body)
        assertFalse(parsed.readonly)
    }

    @Test
    fun profileConfig_tolerates_unknownKeys() {
        val body = """
            {
                "profile": "axiom",
                "path": "/tmp/config.yaml",
                "config": {},
                "readonly": false,
                "future_field": "ignored"
            }
        """.trimIndent()
        // Shouldn't throw.
        val parsed = json.decodeFromString<ProfileConfigResponse>(body)
        assertEquals("axiom", parsed.profile)
    }

    // ---------------------------------------------------------------
    // ProfileSkillsResponse
    // ---------------------------------------------------------------

    @Test
    fun profileSkills_parsesHappyPath() {
        val body = """
            {
                "profile": "axiom",
                "skills": [
                    {"name": "clean_gone", "category": "devops", "description": "Clean stale branches", "path": "/tmp/skills/clean_gone", "enabled": true},
                    {"name": "bug_triage", "category": "support", "description": "Sort bugs", "path": "/tmp/skills/bug_triage", "enabled": false}
                ],
                "total": 2
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileSkillsResponse>(body)
        assertEquals("axiom", parsed.profile)
        assertEquals(2, parsed.total)
        assertEquals(2, parsed.skills.size)
        assertEquals("clean_gone", parsed.skills[0].name)
        assertEquals("devops", parsed.skills[0].category)
        assertTrue(parsed.skills[0].enabled)
        assertFalse(parsed.skills[1].enabled)
    }

    @Test
    fun profileSkills_enabled_defaultsToTrueWhenOmitted() {
        val body = """
            {
                "profile": "axiom",
                "skills": [
                    {"name": "foo", "category": "bar", "description": "baz", "path": "/tmp/foo"}
                ],
                "total": 1
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileSkillsResponse>(body)
        assertEquals(1, parsed.skills.size)
        assertTrue("enabled should default to true", parsed.skills[0].enabled)
    }

    @Test
    fun profileSkills_emptyList_ok() {
        val body = """{"profile": "axiom", "skills": [], "total": 0}"""
        val parsed = json.decodeFromString<ProfileSkillsResponse>(body)
        assertEquals(0, parsed.total)
        assertTrue(parsed.skills.isEmpty())
    }

    // ---------------------------------------------------------------
    // ProfileSoulResponse
    // ---------------------------------------------------------------

    @Test
    fun profileSoul_parsesHappyPath() {
        val body = """
            {
                "profile": "axiom",
                "path": "/home/bailey/.hermes/profiles/axiom/SOUL.md",
                "content": "You are Axiom.\nA careful agent.",
                "exists": true,
                "size_bytes": 32,
                "truncated": false
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileSoulResponse>(body)
        assertEquals("axiom", parsed.profile)
        assertTrue(parsed.exists)
        assertEquals(32L, parsed.sizeBytes)
        assertFalse(parsed.truncated)
        assertTrue(parsed.content.contains("Axiom"))
    }

    @Test
    fun profileSoul_truncated_defaultsToFalseWhenOmitted() {
        // Python worker may omit `truncated` when it's false — this is
        // explicitly allowed by the wire contract in the task spec.
        val body = """
            {
                "profile": "axiom",
                "path": "/tmp/SOUL.md",
                "content": "body",
                "exists": true,
                "size_bytes": 4
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileSoulResponse>(body)
        assertFalse(parsed.truncated)
    }

    @Test
    fun profileSoul_notExistsCase() {
        val body = """
            {
                "profile": "empty",
                "path": "/tmp/empty/SOUL.md",
                "content": "",
                "exists": false,
                "size_bytes": 0
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileSoulResponse>(body)
        assertFalse(parsed.exists)
        assertEquals(0L, parsed.sizeBytes)
        assertTrue(parsed.content.isEmpty())
    }

    // ---------------------------------------------------------------
    // ProfileMemoryResponse
    // ---------------------------------------------------------------

    @Test
    fun profileMemory_parsesHappyPath() {
        val body = """
            {
                "profile": "axiom",
                "memories_dir": "/home/bailey/.hermes/profiles/axiom/memories",
                "entries": [
                    {"name": "android", "filename": "android.md", "path": "/tmp/memories/android.md", "content": "# Notes", "size_bytes": 7, "truncated": false},
                    {"name": "work", "filename": "work.md", "path": "/tmp/memories/work.md", "content": "longer content...", "size_bytes": 17}
                ],
                "total": 2
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileMemoryResponse>(body)
        assertEquals("axiom", parsed.profile)
        assertEquals(2, parsed.total)
        assertEquals(2, parsed.entries.size)
        assertEquals("/home/bailey/.hermes/profiles/axiom/memories", parsed.memoriesDir)
        assertEquals("android", parsed.entries[0].name)
        assertEquals("android.md", parsed.entries[0].filename)
        assertEquals(7L, parsed.entries[0].sizeBytes)
        assertFalse(parsed.entries[0].truncated)
        // Second entry — no truncated field — should default false.
        assertFalse(parsed.entries[1].truncated)
        assertEquals(17L, parsed.entries[1].sizeBytes)
    }

    @Test
    fun profileMemory_emptyEntries_ok() {
        val body = """
            {
                "profile": "axiom",
                "memories_dir": "/tmp/memories",
                "entries": [],
                "total": 0
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileMemoryResponse>(body)
        assertTrue(parsed.entries.isEmpty())
        assertEquals(0, parsed.total)
    }

    // ---------------------------------------------------------------
    // Parse error handling
    // ---------------------------------------------------------------

    @Test
    fun malformedJson_throwsSerializationException() {
        val body = """{"profile": "axiom", "skills": "not-an-array", "total": 0}"""
        try {
            json.decodeFromString<ProfileSkillsResponse>(body)
            fail("Expected SerializationException for malformed JSON")
        } catch (e: SerializationException) {
            // Good — the client wraps this into Result.failure(IOException).
            assertNotNull(e.message)
        }
    }

    @Test
    fun missingRequiredField_throwsSerializationException() {
        // `profile` is a required non-default field — omitting it must fail.
        val body = """{"path": "/tmp/x", "config": {}}"""
        try {
            json.decodeFromString<ProfileConfigResponse>(body)
            fail("Expected SerializationException for missing required field")
        } catch (e: SerializationException) {
            assertNotNull(e.message)
        }
    }

    // ---------------------------------------------------------------
    // URL encoding — the client routes profile names through
    // URLEncoder.encode(..., "UTF-8") and swaps `+` back to `%20`.
    // ---------------------------------------------------------------

    @Test
    fun urlEncoding_handlesSpacesAsPercent20() {
        // Reproduces the transform the client applies before splicing the
        // profile name into the path. Can't call the private helper so
        // we test the behavior of the same pair of calls.
        val encoded = URLEncoder.encode("my profile", "UTF-8").replace("+", "%20")
        assertEquals("my%20profile", encoded)
    }

    @Test
    fun urlEncoding_handlesNonAscii() {
        val encoded = URLEncoder.encode("café", "UTF-8").replace("+", "%20")
        // `é` is 0xC3 0xA9 in UTF-8 → `%C3%A9`.
        assertTrue(encoded.contains("%C3%A9"))
    }

    @Test
    fun urlEncoding_preservesAsciiIdentifier() {
        val encoded = URLEncoder.encode("axiom", "UTF-8").replace("+", "%20")
        assertEquals("axiom", encoded)
    }

    // ---------------------------------------------------------------
    // Config JsonObject shape — we preserve arbitrary nested structure
    // so the UI can render whatever the server emits without a schema
    // drift breaking deserialization.
    // ---------------------------------------------------------------

    @Test
    fun profileConfig_preservesNestedConfigShape() {
        val body = """
            {
                "profile": "axiom",
                "path": "/tmp/config.yaml",
                "config": {
                    "nested": {
                        "deeply": {"value": 42, "label": "answer"},
                        "list": [1, 2, 3]
                    },
                    "top_level": "string"
                }
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileConfigResponse>(body)
        assertNotNull(parsed.config["nested"])
        assertEquals("string", (parsed.config["top_level"] as? JsonPrimitive)?.content)
    }

    @Test
    fun profileConfig_emptyConfigMap_ok() {
        val body = """{"profile": "axiom", "path": "/tmp", "config": {}}"""
        val parsed = json.decodeFromString<ProfileConfigResponse>(body)
        assertTrue(parsed.config.isEmpty())
    }

    @Test
    fun profileSoul_truncatedTrue_surfacesFlag() {
        // When truncated=true the UI shows a banner — make sure the
        // flag round-trips faithfully.
        val body = """
            {
                "profile": "axiom",
                "path": "/tmp/SOUL.md",
                "content": "head...",
                "exists": true,
                "size_bytes": 1048576,
                "truncated": true
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileSoulResponse>(body)
        assertTrue(parsed.truncated)
        assertEquals(1048576L, parsed.sizeBytes)
    }

    @Test
    fun profileMemory_entryTruncatedTrue_surfacesFlag() {
        val body = """
            {
                "profile": "axiom",
                "memories_dir": "/tmp/memories",
                "entries": [
                    {"name": "big", "filename": "big.md", "path": "/tmp/big.md", "content": "head", "size_bytes": 999999, "truncated": true}
                ],
                "total": 1
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileMemoryResponse>(body)
        assertTrue(parsed.entries[0].truncated)
    }

    @Test
    fun profileConfig_nullConfigValue_decodesAsMissing() {
        // `coerceInputValues = true` means null for a non-nullable primitive
        // field coerces to its default. JsonObject is a real object so this
        // just verifies empty map semantics.
        val body = """{"profile": "axiom", "path": "/tmp", "config": {"k": null}}"""
        val parsed = json.decodeFromString<ProfileConfigResponse>(body)
        // null value shows up as JsonNull inside the map — it's not dropped.
        assertTrue(parsed.config.containsKey("k"))
    }

    @Test
    fun profileSkills_emptyDescription_ok() {
        val body = """
            {
                "profile": "axiom",
                "skills": [
                    {"name": "noop", "category": "misc", "description": "", "path": "/tmp/noop"}
                ],
                "total": 1
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileSkillsResponse>(body)
        assertEquals("", parsed.skills[0].description)
    }

    @Test
    fun relayProfileInspectorClient_constructor_rejectsNothing() {
        // Smoke test: the client constructs without side effects. Network
        // calls aren't made until a suspend fetch* is invoked on a
        // Dispatchers.IO dispatcher (skipped here — see test-class kdoc).
        val client = RelayProfileInspectorClient(
            okHttpClient = okhttp3.OkHttpClient(),
            relayUrlProvider = { null },
            sessionTokenProvider = { null },
        )
        assertNotNull(client)
    }

    @Test
    fun profileConfig_fromJsonObjectElement_stillParses() {
        // Dumb regression: ensure the lenient JSON config handles top-level
        // whitespace without bailing.
        val body = """   {"profile":"axiom","path":"/tmp","config":{}}   """
        val parsed = json.decodeFromString<ProfileConfigResponse>(body)
        assertEquals("axiom", parsed.profile)
    }

    @Test
    fun profileSoul_serializesBackToJson_roundTrip() {
        // Sanity — serialization is also used when we ever persist
        // fetched snapshots. No current consumer, but cheap insurance.
        val original = ProfileSoulResponse(
            profile = "axiom",
            path = "/tmp/SOUL.md",
            content = "hi",
            exists = true,
            sizeBytes = 2,
            truncated = false,
        )
        val encoded = json.encodeToString(ProfileSoulResponse.serializer(), original)
        val decoded = json.decodeFromString<ProfileSoulResponse>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun profileMemoryEntry_snakeCaseSizeBytes_mapsCorrectly() {
        val body = """
            {
                "name": "x", "filename": "x.md", "path": "/tmp/x",
                "content": "", "size_bytes": 42
            }
        """.trimIndent()
        val parsed = json.decodeFromString<com.hermesandroid.relay.data.ProfileMemoryEntry>(body)
        assertEquals(42L, parsed.sizeBytes)
    }

    @Test
    fun profileConfig_missingConfigField_fails() {
        // `config` is required (JsonObject has no default). Missing it
        // must fail so we surface a parse error to the user rather than
        // rendering an empty inspector silently.
        val body = """{"profile": "axiom", "path": "/tmp"}"""
        try {
            json.decodeFromString<ProfileConfigResponse>(body)
            fail("Expected failure when config field is missing")
        } catch (e: SerializationException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun profileSkills_largeTotal_parsesAsInt() {
        val body = """{"profile": "axiom", "skills": [], "total": 99999}"""
        val parsed = json.decodeFromString<ProfileSkillsResponse>(body)
        assertEquals(99999, parsed.total)
    }

    @Test
    fun unknownFieldAtTopLevel_ignored() {
        // Forward-compat — if the Python worker ships a new top-level
        // field, the client must not choke.
        val body = """
            {
                "profile": "axiom",
                "path": "/tmp",
                "config": {},
                "readonly": true,
                "served_by": "plugin-bootstrap-v2",
                "cache_status": "miss"
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileConfigResponse>(body)
        assertEquals("axiom", parsed.profile)
        assertTrue(parsed.readonly)
    }

    @Test
    fun profileMemoryResponse_lowercase_memoriesDirFieldWorks() {
        // The wire key is `memories_dir` (snake_case), mapped via
        // @SerialName. Camel-case `memoriesDir` in the JSON should NOT work
        // (strict matching).
        val body = """
            {
                "profile": "axiom",
                "memoriesDir": "/tmp/memories",
                "entries": [],
                "total": 0
            }
        """.trimIndent()
        try {
            json.decodeFromString<ProfileMemoryResponse>(body)
            fail("Expected failure when wire field uses camelCase instead of snake_case")
        } catch (e: SerializationException) {
            // Good — `memoriesDir` is unknown (we only accept `memories_dir`).
            assertNotNull(e)
        }
    }

    @Test
    fun profileSoul_negativeSizeBytes_parsesAsLong() {
        // Not expected in practice but we shouldn't crash if the server
        // emits one (e.g. an errno bubble-up).
        val body = """
            {
                "profile": "x", "path": "/tmp", "content": "",
                "exists": false, "size_bytes": -1
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileSoulResponse>(body)
        assertEquals(-1L, parsed.sizeBytes)
    }

    @Test
    fun profileSoul_missingSizeBytes_fails() {
        // size_bytes is required (no default) — must fail when missing.
        val body = """
            {
                "profile": "x", "path": "/tmp", "content": "",
                "exists": true
            }
        """.trimIndent()
        try {
            json.decodeFromString<ProfileSoulResponse>(body)
            fail("Expected failure when size_bytes is missing")
        } catch (e: SerializationException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun profileSkills_category_preservedAsIs() {
        // Categories aren't normalized — relay emits whatever the skill's
        // `category:` frontmatter says.
        val body = """
            {
                "profile": "axiom",
                "skills": [
                    {"name": "a", "category": "DevOps", "description": "", "path": "/tmp/a"},
                    {"name": "b", "category": "devops", "description": "", "path": "/tmp/b"}
                ],
                "total": 2
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileSkillsResponse>(body)
        assertEquals("DevOps", parsed.skills[0].category)
        assertEquals("devops", parsed.skills[1].category)
    }

    @Test
    fun profileSkills_duplicateNames_preserved() {
        // Relay doesn't dedupe — two profiles could expose similarly-named
        // skills (shouldn't really happen within a single profile but we
        // don't add extra logic).
        val body = """
            {
                "profile": "axiom",
                "skills": [
                    {"name": "same", "category": "a", "description": "", "path": "/tmp/1"},
                    {"name": "same", "category": "b", "description": "", "path": "/tmp/2"}
                ],
                "total": 2
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileSkillsResponse>(body)
        assertEquals(2, parsed.skills.size)
        assertEquals("same", parsed.skills[0].name)
        assertEquals("same", parsed.skills[1].name)
    }

    @Test
    fun encodedProfileName_plusInSource_becomesPercent2B() {
        // Another URL-encoding edge case: a literal '+' in the profile
        // name should round-trip to '%2B' (not be left alone as '+').
        val raw = "a+b"
        val encoded = URLEncoder.encode(raw, "UTF-8").replace("+", "%20")
        assertEquals("a%2Bb", encoded)
    }

    @Test
    fun profileMemory_manyEntries_parses() {
        // Stress: ensure list parsing scales to reasonable counts.
        val entries = (1..50).joinToString(",") { i ->
            """{"name":"m$i","filename":"m$i.md","path":"/tmp/m$i","content":"","size_bytes":$i}"""
        }
        val body = """
            {"profile":"axiom","memories_dir":"/tmp","entries":[$entries],"total":50}
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileMemoryResponse>(body)
        assertEquals(50, parsed.entries.size)
        assertEquals(50, parsed.total)
    }

    @Test
    fun profileConfig_path_acceptsUnicodePath() {
        // Windows paths are fine on Android (they'd just be strings anyway),
        // but Unicode home directory names are a real case for non-English
        // users.
        val body = """
            {
                "profile": "axiom",
                "path": "/home/bailey/café/.hermes/profiles/axiom/config.yaml",
                "config": {}
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileConfigResponse>(body)
        assertTrue(parsed.path.contains("café"))
    }

    @Test
    fun profileSoul_content_preservesNewlines() {
        val body = """
            {
                "profile": "axiom",
                "path": "/tmp/SOUL.md",
                "content": "line1\nline2\nline3",
                "exists": true,
                "size_bytes": 17
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileSoulResponse>(body)
        assertEquals(3, parsed.content.split('\n').size)
    }

    @Test
    fun profileSoul_exists_false_content_notRequiredNonEmpty() {
        // Even when exists=false, `content` is still structurally required
        // (not null). Empty string is the canonical value.
        val body = """
            {
                "profile": "absent",
                "path": "/tmp/SOUL.md",
                "content": "",
                "exists": false,
                "size_bytes": 0
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileSoulResponse>(body)
        assertEquals("", parsed.content)
    }

    @Test
    fun profileSoul_missingContent_fails() {
        val body = """
            {"profile":"a","path":"/tmp","exists":false,"size_bytes":0}
        """.trimIndent()
        try {
            json.decodeFromString<ProfileSoulResponse>(body)
            fail("Expected failure when content is missing")
        } catch (e: SerializationException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun profileMemory_entry_emptyContent_ok() {
        // Empty memory file is valid (user hasn't written yet).
        val body = """
            {
                "profile": "axiom",
                "memories_dir": "/tmp",
                "entries": [{"name":"x","filename":"x.md","path":"/tmp/x","content":"","size_bytes":0}],
                "total": 1
            }
        """.trimIndent()
        val parsed = json.decodeFromString<ProfileMemoryResponse>(body)
        assertEquals("", parsed.entries[0].content)
        assertEquals(0L, parsed.entries[0].sizeBytes)
    }
}
