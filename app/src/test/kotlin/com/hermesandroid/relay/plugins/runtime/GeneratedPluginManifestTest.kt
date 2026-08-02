package com.hermesandroid.relay.plugins.runtime

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedPluginManifestTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun relayGeneratedManifest_decodesApprovalAndRefreshMetadata() {
        val manifest = json.decodeFromString<AndroidPluginManifest>(
            """
            {
              "schema_version": 1,
              "id": "hermes-relay",
              "display_name": "Relay Plugins",
              "requested_capabilities": [
                {"id":"plugin.api.write","reason":"Keep reviewed pages","required":false}
              ],
              "updates": {"poll_seconds": 5},
              "contributions": [
                {
                  "id": "daily-brief",
                  "surface": "page",
                  "title": "Draft: Daily Brief",
                  "status": "draft",
                  "lifecycle": "session",
                  "revision": 3,
                  "digest": "sha256:abc123",
                  "document": {"method":"GET","path":"mobile/pages/daily-brief"}
                }
              ]
            }
            """.trimIndent(),
        )

        val contribution = manifest.contributions.single()
        assertEquals("draft", contribution.status)
        assertEquals("session", contribution.lifecycle)
        assertEquals(3, contribution.revision)
        assertEquals("sha256:abc123", contribution.digest)
        assertEquals(5, manifest.updates?.pollSeconds)
        assertTrue(manifest.requestedCapabilities.any { it.id == PLUGIN_API_WRITE_CAPABILITY })
    }
}
