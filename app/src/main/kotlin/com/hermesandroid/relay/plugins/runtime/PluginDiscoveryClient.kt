package com.hermesandroid.relay.plugins.runtime

import com.hermesandroid.relay.network.upstream.DashboardApiClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Discovers opt-in Android plugin surfaces through the vanilla upstream dashboard.
 *
 * The dashboard catalog is authoritative for plugin identity and active state. Only
 * entries advertising a backend API are probed; a missing or malformed mobile manifest
 * simply means that dashboard plugin has no Android surface.
 */
class PluginDiscoveryClient(
    private val dashboard: DashboardApiClient,
) {
    suspend fun discover(): Result<List<DiscoveredAndroidPlugin>> = runCatching {
        val catalog = dashboard.getJsonElement(CATALOG_PATH).getOrThrow() as? JsonArray
            ?: error("Dashboard plugin catalog must be a JSON array")

        catalog.mapNotNull(::parseCatalogEntry)
            .mapNotNull { entry ->
                val manifest = dashboard
                    .getJsonObject("/api/plugins/${entry.id}/mobile/manifest")
                    .getOrNull()
                    ?: return@mapNotNull null
                // The authenticated catalog supplies provenance. A backend document cannot
                // claim another plugin's identity and inherit this plugin's namespace/grants.
                if (manifest.string("id") != entry.id) return@mapNotNull null
                DiscoveredAndroidPlugin(catalog = entry, manifest = manifest)
            }
    }

    private fun parseCatalogEntry(element: kotlinx.serialization.json.JsonElement): AndroidPluginCatalogEntry? {
        val root = element as? JsonObject ?: return null
        val id = root.string("name") ?: return null
        val exposesApi = root.boolean("has_api") == true || root.string("api") != null
        if (!PluginIdentifiers.isValid(id) || !exposesApi) return null
        return AndroidPluginCatalogEntry(
            id = id,
            label = root.string("label") ?: id,
            description = root.string("description").orEmpty(),
            version = root.string("version").orEmpty(),
            icon = root.string("icon") ?: "Puzzle",
            source = root.string("source").orEmpty(),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private companion object {
        const val CATALOG_PATH = "/api/dashboard/plugins"
    }
}
