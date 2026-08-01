package com.hermesandroid.relay.petdex

import android.content.Context
import android.graphics.BitmapFactory
import com.hermesandroid.relay.ui.components.avatar.PetClipSpec
import com.hermesandroid.relay.ui.components.avatar.PetLoader
import com.hermesandroid.relay.ui.components.avatar.PetReactiveSpec
import com.hermesandroid.relay.ui.components.avatar.PetSpec
import com.hermesandroid.relay.ui.components.avatar.toAvatar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class PetdexInstaller internal constructor(
    private val fetcher: PetdexFetcher,
) {
    constructor() : this(SecurePetdexFetcher())

    suspend fun install(context: Context, pet: PetdexPet): PetdexInstallResult =
        installTo(PetLoader.userDir(context), pet)

    internal suspend fun installTo(petsDir: File, pet: PetdexPet): PetdexInstallResult = installMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                validateCatalogPet(pet)
                val metadataBytes = fetcher.fetch(pet.petJsonUrl, MAX_METADATA_BYTES, PetdexRemoteKind.Asset)
                val remoteMetadata = parseMetadata(metadataBytes)
                val spriteBytes = fetcher.fetch(pet.spritesheetUrl, MAX_SPRITESHEET_BYTES, PetdexRemoteKind.Asset)
                val image = inspectSpritesheet(spriteBytes)
                val layout = PetdexAtlasLayout.fromDimensions(image.width, image.height)
                    ?: throw PetdexException("That Petdex pet uses an unsupported spritesheet layout.")
                val extension = image.extension
                val spec = layout.toPetSpec(pet, remoteMetadata, "spritesheet.$extension")
                installAtomically(petsDir, pet.installedAvatarId, spec, spriteBytes, extension)
                PetdexInstallResult.Success(spec.id, spec.label)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                PetdexInstallResult.Failure(error.message ?: "Couldn't install that Petdex pet.")
            }
        }
    }

    private fun validateCatalogPet(pet: PetdexPet) {
        if (!PETDEX_SLUG.matches(pet.slug)) throw PetdexException("Invalid Petdex pet id.")
        if (!PetdexUrlPolicy.isTrusted(pet.petJsonUrl, PetdexRemoteKind.Asset) ||
            !PetdexUrlPolicy.isTrusted(pet.spritesheetUrl, PetdexRemoteKind.Asset)
        ) {
            throw PetdexException("Untrusted Petdex asset URL.")
        }
    }

    private fun parseMetadata(bytes: ByteArray): PetdexMetadata {
        val metadata = try {
            json.decodeFromString(PetdexMetadata.serializer(), bytes.decodeToString())
        } catch (t: Throwable) {
            throw PetdexException("Petdex metadata isn't valid JSON.", t)
        }
        if (metadata.id.length > MAX_METADATA_ID_LENGTH ||
            metadata.displayName.length > MAX_METADATA_NAME_LENGTH ||
            metadata.description.length > MAX_METADATA_DESCRIPTION_LENGTH
        ) {
            throw PetdexException("Petdex metadata fields are too large.")
        }
        return metadata
    }

    private fun inspectSpritesheet(bytes: ByteArray): InspectedImage {
        val extension = when {
            bytes.hasPrefix(PNG_MAGIC) -> "png"
            bytes.hasWebpMagic() -> "webp"
            else -> throw PetdexException("Petdex spritesheet isn't PNG or WebP.")
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw PetdexException("Petdex spritesheet couldn't be decoded.")
        }
        val pixels = options.outWidth.toLong() * options.outHeight.toLong()
        if (pixels > MAX_SPRITESHEET_PIXELS) throw PetdexException("Petdex spritesheet is too large.")
        return InspectedImage(options.outWidth, options.outHeight, extension)
    }

    private fun installAtomically(
        petsDir: File,
        avatarId: String,
        spec: PetSpec,
        spriteBytes: ByteArray,
        extension: String,
    ) {
        petsDir.mkdirs()
        val staging = File(petsDir, ".petdex-install-${UUID.randomUUID()}")
        val backup = File(petsDir, ".petdex-backup-${UUID.randomUUID()}")
        val target = File(petsDir, avatarId)
        try {
            check(staging.mkdir()) { "Couldn't prepare Petdex install." }
            File(staging, "spritesheet.$extension").writeBytes(spriteBytes)
            File(staging, "pet.json").writeText(json.encodeToString(spec))
            spec.toAvatar(staging) // Validate the exact installed representation before swapping it in.

            if (target.exists() && !target.renameTo(backup)) {
                throw PetdexException("Couldn't replace the existing Petdex pet.")
            }
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                throw PetdexException("Couldn't finish the Petdex install.")
            }
            backup.deleteRecursively()
        } finally {
            staging.deleteRecursively()
            if (backup.exists() && !target.exists()) backup.renameTo(target)
            if (backup.exists() && target.exists()) backup.deleteRecursively()
        }
    }

    private data class InspectedImage(val width: Int, val height: Int, val extension: String)

    private companion object {
        val PETDEX_SLUG = Regex("[a-z0-9][a-z0-9-]{0,127}")
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        const val MAX_METADATA_BYTES = 256L * 1024
        const val MAX_METADATA_ID_LENGTH = 128
        const val MAX_METADATA_NAME_LENGTH = 256
        const val MAX_METADATA_DESCRIPTION_LENGTH = 4_096
        const val MAX_SPRITESHEET_BYTES = 32L * 1024 * 1024
        const val MAX_SPRITESHEET_PIXELS = 16L * 1024 * 1024
        val installMutex = Mutex()
        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    }
}

@Serializable
internal data class PetdexMetadata(
    val id: String = "",
    val displayName: String = "",
    val description: String = "",
)

internal data class PetdexAtlasLayout(
    val columns: Int,
    val rows: Map<String, Int>,
) {
    fun toPetSpec(pet: PetdexPet, metadata: PetdexMetadata, sheetName: String): PetSpec {
        fun clip(rowName: String): PetClipSpec? = rows[rowName]?.let { row ->
            PetClipSpec(
                sheet = sheetName,
                frameWidth = FRAME_WIDTH,
                frameHeight = FRAME_HEIGHT,
                frameCount = minOf(FRAMES_PER_STATE, columns),
                startFrame = row * columns,
                fps = PETDEX_FPS,
            )
        }
        val idle = requireNotNull(clip("idle"))
        val run = clip("run") ?: idle
        val wave = clip("wave") ?: idle
        val states = buildMap {
            put("idle", idle)
            put("thinking", clip("review") ?: idle)
            put("working", run)
            put("writing", run)
            put("listening", clip("waiting") ?: idle)
            put("speaking", wave)
            put("error", clip("failed") ?: idle)
            put("greet", wave)
            put("done", wave)
        }
        return PetSpec(
            schemaVersion = 1,
            id = pet.installedAvatarId,
            label = pet.displayName.ifBlank { metadata.displayName.ifBlank { pet.slug } },
            description = metadata.description,
            source = "petdex",
            sourceUrl = pet.sourceUrl,
            creator = pet.submittedBy,
            reactive = PetReactiveSpec(voice = true, tools = true, intensity = false),
            states = states,
        )
    }

    companion object {
        const val FRAME_WIDTH = 192
        const val FRAME_HEIGHT = 208
        const val FRAMES_PER_STATE = 6
        const val PETDEX_FPS = FRAMES_PER_STATE * 1000f / 1100f

        private val CURRENT_ROWS = mapOf(
            "idle" to 0,
            "wave" to 3,
            "jump" to 4,
            "failed" to 5,
            "waiting" to 6,
            "run" to 7,
            "review" to 8,
        )
        private val LEGACY_ROWS = mapOf(
            "idle" to 0,
            "wave" to 1,
            "run" to 2,
            "failed" to 3,
            "review" to 4,
            "jump" to 5,
        )

        fun fromDimensions(width: Int, height: Int): PetdexAtlasLayout? = when {
            width == 8 * FRAME_WIDTH && height == 9 * FRAME_HEIGHT -> PetdexAtlasLayout(8, CURRENT_ROWS)
            width == 9 * FRAME_WIDTH && height == 8 * FRAME_HEIGHT -> PetdexAtlasLayout(9, LEGACY_ROWS)
            else -> null
        }
    }
}

private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private fun ByteArray.hasWebpMagic(): Boolean =
    size >= 12 &&
        copyOfRange(0, 4).contentEquals("RIFF".encodeToByteArray()) &&
        copyOfRange(8, 12).contentEquals("WEBP".encodeToByteArray())
