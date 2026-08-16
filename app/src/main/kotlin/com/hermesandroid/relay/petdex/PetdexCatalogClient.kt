package com.hermesandroid.relay.petdex

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class PetdexCatalogClient internal constructor(
    private val fetcher: PetdexFetcher,
    private val nowMs: () -> Long,
) {
    constructor() : this(SecurePetdexFetcher(), System::currentTimeMillis)

    @Volatile
    private var cached: CachedCatalog? = null

    suspend fun fetchCatalog(forceRefresh: Boolean = false): List<PetdexPet> {
        val now = nowMs()
        cached?.takeIf { !forceRefresh && now - it.loadedAtMs < CACHE_TTL_MS }?.let { return it.pets }

        val pets = runCatching {
            val bytes = fetcher.fetch(V2_URL, MAX_V2_BYTES, PetdexRemoteKind.Catalog)
            PetdexCatalogParser.parseV2(bytes.decodeToString())
        }.getOrElse { v2Error ->
            if (v2Error is CancellationException) throw v2Error
            runCatching {
                val bytes = fetcher.fetch(V1_URL, MAX_V1_BYTES, PetdexRemoteKind.Catalog)
                PetdexCatalogParser.parseV1(bytes.decodeToString())
            }.getOrElse { v1Error ->
                if (v1Error is CancellationException) throw v1Error
                throw PetdexException("Couldn't load the Petdex catalog.", v1Error).also {
                    it.addSuppressed(v2Error)
                }
            }
        }
        cached = CachedCatalog(nowMs(), pets)
        return pets
    }

    fun clearCache() {
        cached = null
    }

    private data class CachedCatalog(val loadedAtMs: Long, val pets: List<PetdexPet>)

    private companion object {
        const val V2_URL = "https://petdex.dev/api/manifest/v2"
        const val V1_URL = "https://petdex.dev/api/manifest"
        const val CACHE_TTL_MS = 5 * 60 * 1000L
        const val MAX_V2_BYTES = 4L * 1024 * 1024
        const val MAX_V1_BYTES = 8L * 1024 * 1024
    }
}

internal object PetdexCatalogParser {
    private const val MAX_CATALOG_ITEMS = 10_000
    private const val MAX_MANIFEST_FIELDS = 32
    private const val MAX_FIELD_NAME_LENGTH = 64
    private const val MAX_ROW_FIELDS = 32
    private const val MAX_DISPLAY_NAME_LENGTH = 256
    private const val MAX_KIND_LENGTH = 64
    private const val MAX_CREATOR_LENGTH = 256
    private const val MAX_ASSET_URL_LENGTH = 2_048
    private val slugPattern = Regex("[a-z0-9][a-z0-9-]{0,127}")
    private val json = Json { ignoreUnknownKeys = true }

    fun parseV2(raw: String): List<PetdexPet> {
        val manifest = json.decodeFromString(V2Manifest.serializer(), raw)
        if (manifest.v != 2) throw PetdexException("Unsupported Petdex catalog version.")
        if (manifest.pets.size > MAX_CATALOG_ITEMS) throw PetdexException("Petdex catalog has too many entries.")
        if (manifest.fields.isEmpty() || manifest.fields.size > MAX_MANIFEST_FIELDS ||
            manifest.fields.any { it.isBlank() || it.length > MAX_FIELD_NAME_LENGTH }
        ) {
            throw PetdexException("Petdex catalog has invalid fields.")
        }
        val fieldIndex = manifest.fields.withIndex().associate { it.value to it.index }
        if (!fieldIndex.keys.containsAll(REQUIRED_V2_FIELDS)) {
            throw PetdexException("Petdex catalog is missing required fields.")
        }
        val base = manifest.assetBase.toHttpUrlOrNull()
            ?.takeIf { PetdexUrlPolicy.isTrusted(it.toString(), PetdexRemoteKind.Asset) }
            ?: throw PetdexException("Petdex catalog has an untrusted asset base.")
        return manifest.pets.mapNotNull row@{ row ->
            if (row.size > MAX_ROW_FIELDS || row.size > manifest.fields.size) return@row null
            fun value(name: String): String = fieldIndex[name]
                ?.let(row::getOrNull)
                ?.jsonPrimitive
                ?.contentOrNull
                .orEmpty()
            buildPet(
                slug = value("slug"),
                displayName = value("displayName"),
                kind = value("kind"),
                submittedBy = value("submittedBy"),
                spritesheetUrl = resolveAsset(base.toString(), value("spritesheet")),
                petJsonUrl = resolveAsset(base.toString(), value("petJson")),
                zipUrl = value("zip").takeIf(String::isNotBlank)?.let { resolveAsset(base.toString(), it) },
            )
        }
    }

    fun parseV1(raw: String): List<PetdexPet> {
        val manifest = json.decodeFromString(V1Manifest.serializer(), raw)
        if (manifest.pets.size > MAX_CATALOG_ITEMS) throw PetdexException("Petdex catalog has too many entries.")
        return manifest.pets.mapNotNull { entry ->
            buildPet(
                slug = entry.slug,
                displayName = entry.displayName,
                kind = entry.kind,
                submittedBy = entry.submittedBy.orEmpty(),
                spritesheetUrl = entry.spritesheetUrl,
                petJsonUrl = entry.petJsonUrl,
                zipUrl = entry.zipUrl?.takeIf(String::isNotBlank),
            )
        }
    }

    private fun buildPet(
        slug: String,
        displayName: String,
        kind: String,
        submittedBy: String,
        spritesheetUrl: String,
        petJsonUrl: String,
        zipUrl: String?,
    ): PetdexPet? {
        if (!slugPattern.matches(slug)) return null
        if (displayName.length > MAX_DISPLAY_NAME_LENGTH || kind.length > MAX_KIND_LENGTH ||
            submittedBy.length > MAX_CREATOR_LENGTH || spritesheetUrl.length > MAX_ASSET_URL_LENGTH ||
            petJsonUrl.length > MAX_ASSET_URL_LENGTH || (zipUrl?.length ?: 0) > MAX_ASSET_URL_LENGTH
        ) {
            return null
        }
        if (!PetdexUrlPolicy.isTrusted(spritesheetUrl, PetdexRemoteKind.Asset)) return null
        if (!PetdexUrlPolicy.isTrusted(petJsonUrl, PetdexRemoteKind.Asset)) return null
        if (zipUrl != null && !PetdexUrlPolicy.isTrusted(zipUrl, PetdexRemoteKind.Asset)) return null
        return PetdexPet(
            slug = slug,
            displayName = displayName.ifBlank { slug },
            kind = kind.ifBlank { "pet" },
            submittedBy = submittedBy,
            spritesheetUrl = spritesheetUrl,
            petJsonUrl = petJsonUrl,
            zipUrl = zipUrl,
        )
    }

    private fun resolveAsset(base: String, reference: String): String {
        if (reference.isBlank()) return ""
        val absolute = reference.toHttpUrlOrNull()
        if (absolute != null) return absolute.toString()
        return base.toHttpUrlOrNull()?.resolve(reference)?.toString().orEmpty()
    }

    private val REQUIRED_V2_FIELDS = setOf("slug", "displayName", "spritesheet", "petJson")
}

@Serializable
private data class V2Manifest(
    val v: Int,
    val assetBase: String,
    val fields: List<String>,
    val pets: List<List<JsonElement>>,
)

@Serializable
private data class V1Manifest(val pets: List<V1Pet> = emptyList())

@Serializable
private data class V1Pet(
    val slug: String = "",
    val displayName: String = "",
    val kind: String = "",
    val submittedBy: String? = null,
    val spritesheetUrl: String = "",
    val petJsonUrl: String = "",
    val zipUrl: String? = null,
)
