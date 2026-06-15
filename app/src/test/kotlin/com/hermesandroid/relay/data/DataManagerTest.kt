package com.hermesandroid.relay.data

import com.hermesandroid.relay.auth.ConnectionAuthSecrets
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

    // --- Sensitive full-backup marker ---

    @Test
    fun backup_marksSensitiveDataByDefault() {
        val backup = DataManager.AppBackup()

        assertTrue(backup.containsSensitiveData)
    }

    @Test
    fun backup_connectionSecrets_roundTrip() {
        val backup = DataManager.AppBackup(
            connections = listOf(
                Connection(
                    id = "id-a",
                    label = "local",
                    apiServerUrl = "http://localhost:8642",
                    relayUrl = "ws://localhost:8767",
                    tokenStoreKey = "hermes_auth_id-a",
                ),
            ),
            activeConnectionId = "id-a",
            connectionSecrets = listOf(
                DataManager.ConnectionSecretBackup(
                    connectionId = "id-a",
                    tokenStoreKey = "hermes_auth_id-a",
                    auth = ConnectionAuthSecrets(
                        sessionToken = "relay-session-token",
                        refreshToken = "refresh-token",
                        deviceId = "device-id",
                        apiKey = "api-key",
                        pairedSessionMetaJson = """{"transport_hint":"wss"}""",
                    ),
                    dashboardCookies = listOf(
                        DataManager.DashboardCookieBackup(
                            name = "hermes_session",
                            value = "cookie-value",
                            expiresAt = Long.MAX_VALUE,
                            domain = "localhost",
                            path = "/",
                            secure = false,
                            httpOnly = true,
                            hostOnly = true,
                            persistent = false,
                        ),
                    ),
                ),
            ),
        )

        val jsonStr = json.encodeToString(backup)
        val restored = json.decodeFromString<DataManager.AppBackup>(jsonStr)

        assertTrue(jsonStr.contains("relay-session-token"))
        assertTrue(jsonStr.contains("api-key"))
        assertEquals("id-a", restored.activeConnectionId)
        assertEquals("relay-session-token", restored.connectionSecrets[0].auth.sessionToken)
        assertEquals("api-key", restored.connectionSecrets[0].auth.apiKey)
        assertEquals("cookie-value", restored.connectionSecrets[0].dashboardCookies[0].value)
    }

    // --- Serialization round-trip ---

    @Test
    fun backup_roundTrip_preservesAllFields() {
        val connections = listOf(
            Connection(
                id = "id-a",
                label = "local",
                apiServerUrl = "http://localhost:8642",
                relayUrl = "wss://localhost:8767",
                tokenStoreKey = "hermes_auth_id-a",
            ),
            Connection(
                id = "id-b",
                label = "remote",
                apiServerUrl = "http://10.0.0.5:8642",
                relayUrl = "wss://10.0.0.5:8767",
                tokenStoreKey = "hermes_auth_id-b",
            ),
        )
        val original = DataManager.AppBackup(
            version = 4,
            serverUrl = "http://old-server:8642",
            apiServerUrl = "http://localhost:8642",
            relayUrl = "wss://localhost:8767",
            theme = "dark",
            onboardingCompleted = true,
            connections = connections,
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
        assertEquals(original.connections, restored.connections)
        assertEquals(original.exportedAt, restored.exportedAt)
    }

    // --- Restore from valid JSON ---

    @Test
    fun importSettings_validJson_returnsBackup() {
        val jsonStr = """
            {
                "version": 4,
                "apiServerUrl": "http://myserver:8642",
                "relayUrl": "wss://myserver:8767",
                "theme": "light",
                "onboardingCompleted": true,
                "connections": [
                    {
                        "id": "id-a",
                        "label": "local",
                        "apiServerUrl": "http://myserver:8642",
                        "relayUrl": "wss://myserver:8767",
                        "tokenStoreKey": "hermes_auth_id-a"
                    }
                ],
                "exportedAt": 1700000000000
            }
        """.trimIndent()

        val result = json.decodeFromString<DataManager.AppBackup>(jsonStr)

        assertNotNull(result)
        assertEquals(4, result.version)
        assertEquals("http://myserver:8642", result.apiServerUrl)
        assertEquals("wss://myserver:8767", result.relayUrl)
        assertEquals("light", result.theme)
        assertTrue(result.onboardingCompleted)
        assertEquals(1, result.connections.size)
        assertEquals("id-a", result.connections[0].id)
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
        assertEquals(5, result.version)
        assertNull(result.serverUrl)
        assertNull(result.apiServerUrl)
        assertNull(result.relayUrl)
        assertEquals("auto", result.theme)
        assertFalse(result.onboardingCompleted)
        assertTrue(result.connections.isEmpty())
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
    fun backup_defaultVersion_isFive() {
        val backup = DataManager.AppBackup()
        assertEquals(5, backup.version)
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
    fun backup_defaultConnections_isEmpty() {
        val backup = DataManager.AppBackup()
        assertTrue(backup.connections.isEmpty())
    }

    @Test
    fun backup_nullableFields_defaultToNull() {
        val backup = DataManager.AppBackup()
        assertNull(backup.serverUrl)
        assertNull(backup.apiServerUrl)
        assertNull(backup.relayUrl)
    }

    // --- Connections list serialization ---

    @Test
    fun backup_connectionsList_roundTrip() {
        val connections = listOf(
            Connection(
                id = "id-a",
                label = "local",
                apiServerUrl = "http://localhost:8642",
                relayUrl = "wss://localhost:8767",
                tokenStoreKey = "hermes_auth_id-a",
            ),
            Connection(
                id = "id-b",
                label = "remote",
                apiServerUrl = "http://10.0.0.5:8642",
                relayUrl = "wss://10.0.0.5:8767",
                tokenStoreKey = "hermes_auth_id-b",
                routeCandidates = listOf(
                    EndpointCandidate(
                        role = "lan",
                        priority = 0,
                        api = ApiEndpoint(host = "10.0.0.5", port = 8642, tls = false),
                        relay = RelayEndpoint(url = "ws://10.0.0.5:8767", transportHint = "ws"),
                    ),
                    EndpointCandidate(
                        role = "tailscale",
                        priority = 1,
                        api = ApiEndpoint(host = "hermes.ts.net", port = 8642, tls = true),
                        relay = RelayEndpoint(url = "wss://hermes.ts.net:8767", transportHint = "wss"),
                    ),
                ),
                preferredRouteRole = "tailscale",
                pairedAt = 1_700_000_000L,
                transportHint = "wss",
                expiresAt = 1_700_100_000L,
            ),
        )
        val backup = DataManager.AppBackup(connections = connections)

        val jsonStr = json.encodeToString(backup)
        val restored = json.decodeFromString<DataManager.AppBackup>(jsonStr)

        assertEquals(connections, restored.connections)
        assertEquals("tailscale", restored.connections[1].preferredRouteRole)
        assertEquals("hermes.ts.net", restored.connections[1].routeCandidates[1].api.host)
    }

    @Test
    fun backup_emptyConnectionsList_roundTrip() {
        val backup = DataManager.AppBackup(connections = emptyList())

        val jsonStr = json.encodeToString(backup)
        val restored = json.decodeFromString<DataManager.AppBackup>(jsonStr)

        assertTrue(restored.connections.isEmpty())
    }

    @Test
    fun importSettings_v2_dropsLegacyStringProfiles() {
        // v2 stored profiles as List<String>, which is structurally
        // incompatible with v4's List<Connection>. DataManager.importSettings()
        // pre-processes v1/v2 blobs to drop the old field rather than throw.
        val v2Json = """
            {
                "version": 2,
                "apiServerUrl": "http://myserver:8642",
                "relayUrl": "wss://myserver:8767",
                "theme": "light",
                "onboardingCompleted": true,
                "profiles": ["default", "coder"],
                "exportedAt": 1700000000000
            }
        """.trimIndent()

        val result = com.hermesandroid.relay.data.DataManagerTestHelper
            .importWithDataManager(v2Json)
        assertNotNull(result)
        assertEquals(2, result!!.version)
        assertTrue(
            "v2 legacy profiles list should be dropped on import",
            result.connections.isEmpty(),
        )
    }

    @Test
    fun importSettings_v3_remapsProfilesFieldToConnections() {
        // v3 stored connections under the old `profiles` field name
        // (same wire shape, just the pre-rename name). Import must
        // transparently remap so existing v3 backups still load.
        val v3Json = """
            {
                "version": 3,
                "apiServerUrl": "http://myserver:8642",
                "relayUrl": "wss://myserver:8767",
                "theme": "dark",
                "onboardingCompleted": true,
                "profiles": [
                    {
                        "id": "id-a",
                        "label": "local",
                        "apiServerUrl": "http://myserver:8642",
                        "relayUrl": "wss://myserver:8767",
                        "tokenStoreKey": "hermes_auth_id-a"
                    }
                ],
                "exportedAt": 1700000000000
            }
        """.trimIndent()

        val result = com.hermesandroid.relay.data.DataManagerTestHelper
            .importWithDataManager(v3Json)
        assertNotNull(result)
        assertEquals(3, result!!.version)
        assertEquals(1, result.connections.size)
        assertEquals("id-a", result.connections[0].id)
        assertEquals("local", result.connections[0].label)
    }
}

/**
 * Narrow helper so the v2/v3-compat tests can exercise the real
 * [DataManager.importSettings] logic (which pre-processes the payload to
 * drop legacy incompatible fields and remap v3 `profiles` → v4
 * `connections`). DataManager itself takes a Context so this helper just
 * copies the pure JSON-handling part — if that logic ever moves to a pure
 * static helper, swap this out.
 */
internal object DataManagerTestHelper {
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun importWithDataManager(jsonString: String): DataManager.AppBackup? {
        return try {
            val element = json.parseToJsonElement(jsonString)
            val obj = element as? kotlinx.serialization.json.JsonObject ?: return null
            val version = obj["version"]?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
            } ?: 4
            val normalized = when {
                version < 3 -> kotlinx.serialization.json.JsonObject(obj - "profiles")
                version == 3 -> {
                    val profilesField = obj["profiles"]
                    val withoutProfiles = obj - "profiles"
                    if (profilesField != null) {
                        kotlinx.serialization.json.JsonObject(
                            withoutProfiles + ("connections" to profilesField),
                        )
                    } else {
                        kotlinx.serialization.json.JsonObject(withoutProfiles)
                    }
                }
                else -> obj
            }
            json.decodeFromJsonElement(DataManager.AppBackup.serializer(), normalized)
        } catch (_: Exception) {
            null
        }
    }
}
