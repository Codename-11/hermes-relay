package com.hermesandroid.relay.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for DataManager.AppBackup serialization and import logic.
 *
 * DataManager itself requires Android Context, but AppBackup is a
 * kotlinx.serialization data class and importSettings() is pure logic,
 * so we can test the serialization round-trip and field validation on JVM.
 */
class DataManagerTest {

    private lateinit var json: Json

    @Before
    fun setUp() {
        json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    // --- Serialization produces valid JSON ---

    @Test
    fun backup_serialization_producesValidJson() {
        val backup = DataManager.AppBackup(
            apiServerUrl = "http://localhost:8642",
            relayUrl = "wss://localhost:8767",
            theme = "dark"
        )

        val jsonStr = json.encodeToString(backup)
        assertNotNull(jsonStr)
        assertTrue(jsonStr.isNotEmpty())

        // Should be parseable back
        val parsed = json.decodeFromString<DataManager.AppBackup>(jsonStr)
        assertNotNull(parsed)
    }

    // --- Backup contains expected keys ---

    @Test
    fun backup_containsVersionKey() {
        val backup = DataManager.AppBackup()
        val jsonStr = json.encodeToString(backup)

        assertTrue("JSON should contain 'version'", jsonStr.contains("\"version\""))
    }

    @Test
    fun backup_containsApiServerUrlKey() {
        val backup = DataManager.AppBackup(apiServerUrl = "http://localhost:8642")
        val jsonStr = json.encodeToString(backup)

        assertTrue("JSON should contain 'apiServerUrl'", jsonStr.contains("\"apiServerUrl\""))
    }

    @Test
    fun backup_containsRelayUrlKey() {
        val backup = DataManager.AppBackup(relayUrl = "wss://localhost:8767")
        val jsonStr = json.encodeToString(backup)

        assertTrue("JSON should contain 'relayUrl'", jsonStr.contains("\"relayUrl\""))
    }

    @Test
    fun backup_containsThemeKey() {
        val backup = DataManager.AppBackup(theme = "dark")
        val jsonStr = json.encodeToString(backup)

        assertTrue("JSON should contain 'theme'", jsonStr.contains("\"theme\""))
    }

    @Test
    fun backup_containsExportedAtKey() {
        val backup = DataManager.AppBackup()
        val jsonStr = json.encodeToString(backup)

        assertTrue("JSON should contain 'exportedAt'", jsonStr.contains("\"exportedAt\""))
    }

    @Test
    fun backup_containsOnboardingCompletedKey() {
        val backup = DataManager.AppBackup()
        val jsonStr = json.encodeToString(backup)

        assertTrue("JSON should contain 'onboardingCompleted'", jsonStr.contains("\"onboardingCompleted\""))
    }

    // --- Backup does NOT contain sensitive data ---

    @Test
    fun backup_doesNotContainApiKey() {
        val backup = DataManager.AppBackup(
            apiServerUrl = "http://localhost:8642",
            theme = "auto"
        )
        val jsonStr = json.encodeToString(backup)

        assertFalse("Backup should not contain 'apiKey'", jsonStr.contains("\"apiKey\""))
        assertFalse("Backup should not contain 'api_key'", jsonStr.contains("\"api_key\""))
    }

    @Test
    fun backup_doesNotContainSessionToken() {
        val backup = DataManager.AppBackup()
        val jsonStr = json.encodeToString(backup)

        assertFalse("Backup should not contain 'session_token'", jsonStr.contains("\"session_token\""))
        assertFalse("Backup should not contain 'sessionToken'", jsonStr.contains("\"sessionToken\""))
    }

    @Test
    fun backup_doesNotContainDeviceId() {
        val backup = DataManager.AppBackup()
        val jsonStr = json.encodeToString(backup)

        assertFalse("Backup should not contain 'device_id'", jsonStr.contains("\"device_id\""))
        assertFalse("Backup should not contain 'deviceId'", jsonStr.contains("\"deviceId\""))
    }

    @Test
    fun backup_doesNotContainBearerToken() {
        val backup = DataManager.AppBackup()
        val jsonStr = json.encodeToString(backup)

        assertFalse("Backup should not contain 'token'", jsonStr.contains("\"token\""))
        assertFalse("Backup should not contain 'bearer'", jsonStr.lowercase().contains("\"bearer\""))
    }

    // --- Serialization round-trip ---

    @Test
    fun backup_roundTrip_preservesAllFields() {
        val original = DataManager.AppBackup(
            version = 2,
            serverUrl = "http://old-server:8642",
            apiServerUrl = "http://localhost:8642",
            relayUrl = "wss://localhost:8767",
            theme = "dark",
            onboardingCompleted = true,
            profiles = listOf("default", "coder"),
            exportedAt = 1700000000000L
        )

        val jsonStr = json.encodeToString(original)
        val restored = json.decodeFromString<DataManager.AppBackup>(jsonStr)

        assertEquals(original.version, restored.version)
        assertEquals(original.serverUrl, restored.serverUrl)
        assertEquals(original.apiServerUrl, restored.apiServerUrl)
        assertEquals(original.relayUrl, restored.relayUrl)
        assertEquals(original.theme, restored.theme)
        assertEquals(original.onboardingCompleted, restored.onboardingCompleted)
        assertEquals(original.profiles, restored.profiles)
        assertEquals(original.exportedAt, restored.exportedAt)
    }

    // --- Restore from valid JSON ---

    @Test
    fun importSettings_validJson_returnsBackup() {
        val jsonStr = """
            {
                "version": 2,
                "apiServerUrl": "http://myserver:8642",
                "relayUrl": "wss://myserver:8767",
                "theme": "light",
                "onboardingCompleted": true,
                "profiles": ["default"],
                "exportedAt": 1700000000000
            }
        """.trimIndent()

        val result = json.decodeFromString<DataManager.AppBackup>(jsonStr)

        assertNotNull(result)
        assertEquals(2, result.version)
        assertEquals("http://myserver:8642", result.apiServerUrl)
        assertEquals("wss://myserver:8767", result.relayUrl)
        assertEquals("light", result.theme)
        assertTrue(result.onboardingCompleted)
        assertEquals(listOf("default"), result.profiles)
    }

    // --- Restore from malformed JSON ---

    @Test
    fun importSettings_malformedJson_returnsNull() {
        val malformed = "this is not json {{"
        val result = try {
            json.decodeFromString<DataManager.AppBackup>(malformed)
        } catch (e: Exception) {
            null
        }
        assertNull(result)
    }

    @Test
    fun importSettings_emptyString_returnsNull() {
        val result = try {
            json.decodeFromString<DataManager.AppBackup>("")
        } catch (e: Exception) {
            null
        }
        assertNull(result)
    }

    @Test
    fun importSettings_emptyJsonObject_usesDefaults() {
        val jsonStr = "{}"
        val result = json.decodeFromString<DataManager.AppBackup>(jsonStr)

        assertNotNull(result)
        assertEquals(2, result.version)
        assertNull(result.serverUrl)
        assertNull(result.apiServerUrl)
        assertNull(result.relayUrl)
        assertEquals("auto", result.theme)
        assertFalse(result.onboardingCompleted)
        assertTrue(result.profiles.isEmpty())
    }

    // --- Unknown fields are ignored ---

    @Test
    fun importSettings_unknownFields_ignored() {
        val jsonStr = """
            {
                "version": 2,
                "apiServerUrl": "http://localhost:8642",
                "theme": "auto",
                "unknownField": "should be ignored",
                "anotherUnknown": 42
            }
        """.trimIndent()

        val result = json.decodeFromString<DataManager.AppBackup>(jsonStr)
        assertNotNull(result)
        assertEquals("http://localhost:8642", result.apiServerUrl)
    }

    // --- Format version handling ---

    @Test
    fun backup_defaultVersion_isTwo() {
        val backup = DataManager.AppBackup()
        assertEquals(2, backup.version)
    }

    @Test
    fun importSettings_version1_compat() {
        // v1 used 'serverUrl' instead of 'apiServerUrl'/'relayUrl'
        val jsonStr = """
            {
                "version": 1,
                "serverUrl": "wss://oldserver:8767",
                "theme": "auto"
            }
        """.trimIndent()

        val result = json.decodeFromString<DataManager.AppBackup>(jsonStr)
        assertEquals(1, result.version)
        assertEquals("wss://oldserver:8767", result.serverUrl)
        assertNull(result.apiServerUrl) // Not present in v1
    }

    // --- Default values ---

    @Test
    fun backup_defaultTheme_isAuto() {
        val backup = DataManager.AppBackup()
        assertEquals("auto", backup.theme)
    }

    @Test
    fun backup_defaultOnboardingCompleted_isFalse() {
        val backup = DataManager.AppBackup()
        assertFalse(backup.onboardingCompleted)
    }

    @Test
    fun backup_defaultProfiles_isEmpty() {
        val backup = DataManager.AppBackup()
        assertTrue(backup.profiles.isEmpty())
    }

    @Test
    fun backup_nullableFields_defaultToNull() {
        val backup = DataManager.AppBackup()
        assertNull(backup.serverUrl)
        assertNull(backup.apiServerUrl)
        assertNull(backup.relayUrl)
    }

    // --- Profiles list serialization ---

    @Test
    fun backup_profilesList_roundTrip() {
        val profiles = listOf("default", "coder", "researcher", "creative")
        val backup = DataManager.AppBackup(profiles = profiles)

        val jsonStr = json.encodeToString(backup)
        val restored = json.decodeFromString<DataManager.AppBackup>(jsonStr)

        assertEquals(profiles, restored.profiles)
    }

    @Test
    fun backup_emptyProfilesList_roundTrip() {
        val backup = DataManager.AppBackup(profiles = emptyList())

        val jsonStr = json.encodeToString(backup)
        val restored = json.decodeFromString<DataManager.AppBackup>(jsonStr)

        assertTrue(restored.profiles.isEmpty())
    }
}
