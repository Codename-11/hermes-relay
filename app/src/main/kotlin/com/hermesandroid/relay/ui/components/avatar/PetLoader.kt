package com.hermesandroid.relay.ui.components.avatar

import android.content.Context
import android.util.Log
import com.hermesandroid.relay.ui.components.SphereReactivity
import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.ui.components.UserContentDir
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** The pet schema versions this build understands. */
const val PET_SPEC_SCHEMA_VERSION = 1

/**
 * User-authored pet manifest — the on-disk `pet.json` format for a side-loaded
 * bitmap companion. Pure data: filenames + numbers, no code. Parsed,
 * validated, and converted to a [PetAvatar] by [toAvatar]; see
 * `docs/pet-spec.md` for the authoring reference.
 *
 * Clips are keyed by name in [states]; the loader accepts both the original
 * Android names and Hermes/Petdex's current atlas taxonomy. Agent activity
 * (`review`, `running`) and horizontal locomotion (`running-left` /
 * `running-right`) remain separate. A minimal pack still needs only `idle`.
 *
 * Example:
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "id": "blob",
 *   "label": "Blob",
 *   "description": "A friendly blob",
 *   "reactive": { "voice": true },
 *   "states": {
 *     "idle":     { "frames": ["idle_0.png", "idle_1.png"], "fps": 6 },
 *     "thinking": { "frames": ["think_0.png", "think_1.png"], "fps": 8 },
 *     "speaking": { "sheet": "talk.png", "frameWidth": 64, "frameHeight": 64, "frameCount": 4, "fps": 12 }
 *   }
 * }
 * ```
 */
@Serializable
data class PetSpec(
    val schemaVersion: Int = 1,
    val id: String = "",
    val label: String = "",
    val description: String = "",
    /** Optional origin metadata for gallery-installed packs. Never fetched or executed. */
    val source: String = "",
    val sourceUrl: String = "",
    val creator: String = "",
    val reactive: PetReactiveSpec = PetReactiveSpec(),
    /** Clip definitions keyed by an Android activity name or Hermes/Petdex row name. */
    val states: Map<String, PetClipSpec> = emptyMap(),
    /** Fallback clip for any state with no usable clip in [states]. */
    val defaults: PetClipSpec? = null,
)

/**
 * Which live signals the pet honors. Drives the picker capability badge. The
 * default pet renderer only acts on [voice] (an amplitude bounce); [tools] and
 * [intensity] are declared for the badge but reserved for richer renderers.
 */
@Serializable
data class PetReactiveSpec(
    val voice: Boolean = true,
    val tools: Boolean = false,
    val intensity: Boolean = false,
)

/**
 * One animation clip. Provide EITHER [frames] (one or more image files; one is
 * a still clip) OR a sprite [sheet] sliced into a [frameWidth]×[frameHeight]
 * grid of [frameCount] cells. [fps] is clamped to a safe range at load time.
 */
@Serializable
data class PetClipSpec(
    val frames: List<String> = emptyList(),
    val sheet: String = "",
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val frameCount: Int = 0,
    /** Zero-based cell offset into [sheet], read row-major. */
    val startFrame: Int = 0,
    val fps: Float = 8f,
)

// Android activity names and upstream Hermes/Petdex row names are both accepted.
// `running` is the in-place agent-work row; directional rows are resolved by
// LOCOMOTION_CLIP_CHAIN and never substituted for agent activity.
private val STATE_CLIP_CHAIN: Map<SphereState, List<String>> = mapOf(
    SphereState.Idle to listOf("idle"),
    SphereState.Thinking to listOf("thinking", "review", "idle"),
    SphereState.Streaming to listOf(
        "writing",
        "streaming",
        "working",
        "run",
        "running",
        "review",
        "thinking",
        "idle",
    ),
    SphereState.Listening to listOf("listening", "waiting", "idle"),
    SphereState.Speaking to listOf("speaking", "talking", "wave", "waving", "writing", "idle"),
    SphereState.Error to listOf("error", "failed", "review", "thinking", "idle"),
)

private val LOCOMOTION_CLIP_CHAIN: Map<PetLocomotion, List<String>> = mapOf(
    PetLocomotion.WalkLeft to listOf("walking-left", "walk-left", "running-left", "run-left"),
    PetLocomotion.WalkRight to listOf("walking-right", "walk-right", "running-right", "run-right"),
    PetLocomotion.RunLeft to listOf("running-left", "run-left", "walking-left", "walk-left"),
    PetLocomotion.RunRight to listOf("running-right", "run-right", "walking-right", "walk-right"),
)

/**
 * Validate a parsed [PetSpec] and resolve it (against the pack directory [dir])
 * into a renderable [PetAvatar]. Throws [IllegalArgumentException] with a
 * human-readable reason for any pack that can't be honored (unsupported schema,
 * blank id, or no usable `idle` clip whose files actually exist).
 */
fun PetSpec.toAvatar(dir: File): PetAvatar {
    require(schemaVersion in 1..PET_SPEC_SCHEMA_VERSION) {
        "unsupported schemaVersion $schemaVersion (this build supports up to $PET_SPEC_SCHEMA_VERSION)"
    }
    require(id.isNotBlank()) { "missing id" }
    val resolvedLabel = label.ifBlank { id }

    val idleClip = resolveStateClip(SphereState.Idle, dir)
    requireNotNull(idleClip) {
        "no usable 'idle' clip — need a frames list or a sprite sheet whose files exist in the pack"
    }

    val clips = SphereState.entries.associateWith { state ->
        resolveStateClip(state, dir) ?: idleClip
    }

    // The upstream in-place `run`/`running` row means agent work. It must not be
    // confused with the dedicated left/right rows used for screen locomotion.
    val workingClip = resolveAliasClip(listOf("working", "run", "running"), dir)
    // Only the canonical/legacy in-place run row may substitute for directional
    // travel. A tool-specific `working` pose is never locomotion.
    val legacyTravelClip = resolveAliasClip(listOf("run", "running"), dir)

    val locomotionClips = LOCOMOTION_CLIP_CHAIN.mapNotNull { (motion, keys) ->
        resolveAliasClip(keys, dir)?.let { motion to it }
    }.toMap()

    // Hermes maps a clean completion/greeting to wave; jump is the stronger
    // success/celebration fallback. Explicit Android names retain priority.
    val oneShots = buildMap {
        resolveAliasClip(listOf("greet", "wake", "wave", "waving"), dir)
            ?.let { put(PetOneShot.Greet, it) }
        resolveAliasClip(
            listOf("done", "wave", "waving", "success", "celebrate", "jump", "jumping"),
            dir,
        )?.let { put(PetOneShot.Done, it) }
    }

    return PetAvatar(
        id = id,
        label = resolvedLabel,
        description = description,
        // Clamp declared reactivity to what the renderer actually honors today so
        // the picker badge can't over-promise (declared AND supported). Flip a
        // flag in [PET_RENDERER_CAPABILITIES] to let a declared signal through.
        // `tools` reactivity is the `working` clip: a pet reacts to tool use iff
        // it ships one, so the badge is driven by the clip, not the declared flag.
        reactivity = SphereReactivity(
            voice = reactive.voice && PET_RENDERER_CAPABILITIES.voice,
            tools = (workingClip != null) && PET_RENDERER_CAPABILITIES.tools,
            intensity = reactive.intensity && PET_RENDERER_CAPABILITIES.intensity,
            gaze = false,
        ),
        activityClips = clips,
        workingClip = workingClip,
        legacyTravelClip = legacyTravelClip,
        locomotionClips = locomotionClips,
        oneShots = oneShots,
    )
}

/** First usable clip among [keys] (friendly aliases), or null. No fallback. */
private fun PetSpec.resolveAliasClip(keys: List<String>, dir: File): PetClip? {
    for (key in keys) states[key]?.toClip(dir)?.let { return it }
    return null
}

private fun PetSpec.resolveStateClip(state: SphereState, dir: File): PetClip? {
    for (key in STATE_CLIP_CHAIN[state] ?: listOf("idle")) {
        val clip = states[key]?.toClip(dir)
        if (clip != null) return clip
    }
    return defaults?.toClip(dir)
}

private fun PetClipSpec.toClip(dir: File): PetClip? {
    val fpsSafe = fps.coerceIn(1f, 60f)
    if (frames.isNotEmpty()) {
        val files = frames.mapNotNull { safeChild(dir, it) }.filter { it.isFile }
        if (files.isEmpty()) return null
        return FrameSequenceClip(files, fpsSafe)
    }
    if (sheet.isNotBlank() && frameWidth > 0 && frameHeight > 0 && frameCount > 0 && startFrame >= 0) {
        val sheetFile = safeChild(dir, sheet) ?: return null
        if (!sheetFile.isFile) return null
        return SpriteSheetClip(sheetFile, frameWidth, frameHeight, frameCount, fpsSafe, startFrame)
    }
    return null
}

/**
 * Resolve [name] as a child of [dir], rejecting any path that escapes the pack
 * directory (e.g. `../../secret`). Pets are user-dropped data, but a manifest
 * must never be able to reference files outside its own pack.
 */
private fun safeChild(dir: File, name: String): File? {
    if (name.isBlank()) return null
    val child = File(dir, name)
    val base = dir.canonicalPath + File.separator
    return if (child.canonicalPath.startsWith(base)) child else null
}

/**
 * Discovers user-authored pet packs under the app-private `pets/` directory.
 * Data-only: manifests are parsed and validated, never executed; bitmaps are
 * decoded later by [PetAvatar]. Invalid/absent packs are skipped (with a log
 * line) so one bad pack can't break the picker — an empty result means
 * "sphere only".
 *
 * The directory is app-scoped external storage (no runtime permission). Users
 * place packs there via ADB (`adb push pet-dir/ $(...)/files/pets/`) or a future
 * in-app import. Resolved via [UserContentDir]. See `docs/pet-spec.md`.
 */
object PetLoader {
    private const val TAG = "PetLoader"
    private const val DIR = "pets"
    private const val PETDEX_TRANSACTION_PREFIX = ".petdex-"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** The directory users drop `<id>/pet.json` pet packs into. Created if absent. */
    fun userDir(context: Context): File = UserContentDir.resolve(context, DIR)

    /** Convenience overload resolving the pet directory from [context]. */
    fun loadPets(context: Context): List<PetAvatar> = loadPets(userDir(context))

    /**
     * Parse every `<pack>/pet.json` under [dir] into a [PetAvatar], skipping any
     * pack that fails to parse or validate. Returns avatars sorted by pack
     * directory name for a stable picker order. Pure (no Android Context), so the
     * discovery/skip-invalid behavior is unit-testable against a temp directory.
     */
    fun loadPets(dir: File): List<PetAvatar> {
        val packs = dir.listFiles { file ->
            file.isDirectory && !file.name.startsWith(PETDEX_TRANSACTION_PREFIX)
        } ?: return emptyList()
        return packs.sortedBy { it.name }.mapNotNull { packDir ->
            val manifest = File(packDir, "pet.json")
            if (!manifest.isFile) return@mapNotNull null
            try {
                val spec = json.decodeFromString(PetSpec.serializer(), manifest.readText())
                val resolved = if (spec.id.isBlank()) spec.copy(id = packDir.name) else spec
                resolved.toAvatar(packDir)
            } catch (t: Throwable) {
                Log.w(TAG, "Skipping pet ${packDir.name}: ${t.message}")
                null
            }
        }
    }

    /**
     * Remove the user pet pack whose resolved id matches [avatarId]. The id is the
     * manifest `id` (or the pack directory name when blank), mirroring [loadPets];
     * the pack directory may not equal the id, so we resolve rather than assume.
     * Returns true iff a matching pack was found and deleted.
     */
    fun deletePet(context: Context, avatarId: String): Boolean = deletePet(userDir(context), avatarId)

    /** Pure overload (no Android Context) for tests against a temp directory. */
    fun deletePet(dir: File, avatarId: String): Boolean {
        val packs = dir.listFiles { file ->
            file.isDirectory && !file.name.startsWith(PETDEX_TRANSACTION_PREFIX)
        } ?: return false
        for (packDir in packs) {
            val manifest = File(packDir, "pet.json")
            if (!manifest.isFile) continue
            val resolvedId = try {
                json.decodeFromString(PetSpec.serializer(), manifest.readText()).id.ifBlank { packDir.name }
            } catch (t: Throwable) {
                packDir.name
            }
            if (resolvedId == avatarId) return packDir.deleteRecursively()
        }
        return false
    }
}
