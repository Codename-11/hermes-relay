package com.hermesandroid.relay.ui.components.avatar

import com.hermesandroid.relay.ui.components.SphereState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class PetLoaderTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File =
        createTempDirectory("pet-loader-test").toFile().also { tempDirs.add(it) }

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    /**
     * Create `<parent>/<packName>/pet.json` with [manifest] and touch each name
     * in [imageFiles] as an empty placeholder inside the pack dir (the loader
     * only checks `isFile`, never decodes). Returns the pack directory.
     */
    private fun writePack(
        parent: File,
        packName: String,
        manifest: String,
        imageFiles: List<String> = emptyList(),
    ): File {
        val packDir = File(parent, packName)
        packDir.mkdirs()
        File(packDir, "pet.json").writeText(manifest)
        imageFiles.forEach { File(packDir, it).createNewFile() }
        return packDir
    }

    @Test
    fun `valid minimal pack loads one avatar`() {
        val dir = tempDir()
        writePack(
            dir,
            "blob",
            """{ "id": "blob", "label": "Blob", "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )

        val avatars = PetLoader.loadPets(dir)

        assertEquals(1, avatars.size)
        val avatar = avatars.single()
        assertEquals("blob", avatar.id)
        assertEquals("Blob", avatar.label)
        assertEquals(AvatarSource.USER, avatar.source)
    }

    @Test
    fun `blank id falls back to the pack directory name`() {
        val dir = tempDir()
        writePack(
            dir,
            "fox",
            """{ "id": "", "label": "Fox", "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )

        val avatars = PetLoader.loadPets(dir)

        assertEquals(1, avatars.size)
        assertEquals("fox", avatars.single().id)
    }

    @Test
    fun `blank label falls back to the id`() {
        val dir = tempDir()
        writePack(
            dir,
            "blob",
            """{ "id": "blob", "label": "", "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )

        val avatar = PetLoader.loadPets(dir).single()

        assertEquals("blob", avatar.label)
    }

    @Test
    fun `declared tools without a working clip is not advertised`() {
        val dir = tempDir()
        writePack(
            dir,
            "blob",
            """{ "id": "blob", "reactive": { "voice": true, "tools": true }, "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )

        val avatar = PetLoader.loadPets(dir).single()

        // `tools` reactivity is driven by shipping a `working` clip (this pack has
        // none), so a bare declaration must NOT advertise on the picker badge.
        assertTrue(avatar.reactivity.voice)
        assertFalse(avatar.reactivity.tools)
        assertEquals("Voice", avatar.reactivity.summary())
    }

    @Test
    fun `declared intensity reactivity is honored on the badge`() {
        val dir = tempDir()
        writePack(
            dir,
            "blob",
            """{ "id": "blob", "reactive": { "intensity": true }, "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )

        val avatar = PetLoader.loadPets(dir).single()

        // The renderer consumes intensity (faster playback), so a declared flag
        // is honored and shows as "Activity".
        assertTrue(avatar.reactivity.intensity)
        assertEquals("Voice · Activity", avatar.reactivity.summary())
    }

    @Test
    fun `a working clip enables tool reactivity on the badge`() {
        val dir = tempDir()
        writePack(
            dir,
            "blob",
            """{ "id": "blob", "states": { "idle": { "frames": ["idle.png"], "fps": 6 }, "working": { "frames": ["work.png"], "fps": 8 } } }""",
            imageFiles = listOf("idle.png", "work.png"),
        )

        val avatar = PetLoader.loadPets(dir).single()

        // Shipping a usable `working` clip IS the tool-reactivity capability — no
        // separate flag needed — so the badge now advertises Tools.
        assertTrue(avatar.reactivity.tools)
        assertEquals("Voice · Tools", avatar.reactivity.summary())
    }

    @Test
    fun `a working clip with missing files does not enable tool reactivity`() {
        val dir = tempDir()
        writePack(
            dir,
            "blob",
            """{ "id": "blob", "states": { "idle": { "frames": ["idle.png"], "fps": 6 }, "working": { "frames": ["missing.png"], "fps": 8 } } }""",
            imageFiles = listOf("idle.png"),
        )

        val avatar = PetLoader.loadPets(dir).single()

        // The working clip's file doesn't exist, so there's no real tool behavior
        // and the badge must not claim it.
        assertFalse(avatar.reactivity.tools)
    }

    @Test
    fun `one-shot reaction clips load without affecting the badge`() {
        val dir = tempDir()
        writePack(
            dir,
            "blob",
            """{ "id": "blob", "states": {
                 "idle": { "frames": ["idle.png"], "fps": 6 },
                 "greet": { "frames": ["wave_0.png", "wave_1.png"], "fps": 8 },
                 "done": { "frames": ["cheer_0.png", "cheer_1.png"], "fps": 8 }
            } }""",
            imageFiles = listOf("idle.png", "wave_0.png", "wave_1.png", "cheer_0.png", "cheer_1.png"),
        )

        val avatar = PetLoader.loadPets(dir).single()

        // One-shots are reactions, not a reactivity signal — they load fine but
        // don't light Tools/Activity on the badge.
        assertEquals("blob", avatar.id)
        assertFalse(avatar.reactivity.tools)
        assertFalse(avatar.reactivity.intensity)
        assertEquals("Voice", avatar.reactivity.summary())
    }

    @Test
    fun `single-frame clips can cover every state and reaction`() {
        val dir = tempDir()
        val files = listOf(
            "idle.png",
            "thinking.png",
            "working.png",
            "writing.png",
            "speaking.png",
            "listening.png",
            "error.png",
            "greet.png",
            "done.png",
        )
        writePack(
            dir,
            "static",
            """{ "id": "static", "reactive": { "intensity": true }, "states": {
                 "idle": { "frames": ["idle.png"], "fps": 1 },
                 "thinking": { "frames": ["thinking.png"], "fps": 1 },
                 "working": { "frames": ["working.png"], "fps": 1 },
                 "writing": { "frames": ["writing.png"], "fps": 1 },
                 "speaking": { "frames": ["speaking.png"], "fps": 1 },
                 "listening": { "frames": ["listening.png"], "fps": 1 },
                 "error": { "frames": ["error.png"], "fps": 1 },
                 "greet": { "frames": ["greet.png"], "fps": 1 },
                 "done": { "frames": ["done.png"], "fps": 1 }
            } }""",
            imageFiles = files,
        )

        val avatar = PetLoader.loadPets(dir).single()

        fun PetClip.fileName(): String = (this as FrameSequenceClip).files.single().name
        assertEquals("static", avatar.id)
        assertEquals("Voice · Tools · Activity", avatar.reactivity.summary())
        assertEquals("thinking.png", avatar.activityClips.getValue(SphereState.Thinking).fileName())
        assertEquals("writing.png", avatar.activityClips.getValue(SphereState.Streaming).fileName())
        assertEquals("listening.png", avatar.activityClips.getValue(SphereState.Listening).fileName())
        assertEquals("speaking.png", avatar.activityClips.getValue(SphereState.Speaking).fileName())
        assertEquals("error.png", avatar.activityClips.getValue(SphereState.Error).fileName())
        assertEquals("working.png", avatar.workingClip!!.fileName())
        assertEquals("greet.png", avatar.oneShots.getValue(PetOneShot.Greet).fileName())
        assertEquals("done.png", avatar.oneShots.getValue(PetOneShot.Done).fileName())
    }

    @Test
    fun `current Petdex taxonomy resolves activity reactions and directional locomotion`() {
        val dir = tempDir()
        val files = listOf(
            "idle.png",
            "right.png",
            "left.png",
            "wave.png",
            "jump.png",
            "failed.png",
            "waiting.png",
            "running.png",
            "review.png",
        )
        writePack(
            dir,
            "petdex",
            """{ "id": "petdex", "states": {
                 "idle": { "frames": ["idle.png"] },
                 "running-right": { "frames": ["right.png"] },
                 "running-left": { "frames": ["left.png"] },
                 "waving": { "frames": ["wave.png"] },
                 "jumping": { "frames": ["jump.png"] },
                 "failed": { "frames": ["failed.png"] },
                 "waiting": { "frames": ["waiting.png"] },
                 "running": { "frames": ["running.png"] },
                 "review": { "frames": ["review.png"] }
            } }""",
            imageFiles = files,
        )

        val avatar = PetLoader.loadPets(dir).single()

        fun PetClip.fileName(): String = (this as FrameSequenceClip).files.single().name
        assertEquals("idle.png", avatar.activityClips.getValue(SphereState.Idle).fileName())
        assertEquals("review.png", avatar.activityClips.getValue(SphereState.Thinking).fileName())
        assertEquals("running.png", avatar.activityClips.getValue(SphereState.Streaming).fileName())
        assertEquals("waiting.png", avatar.activityClips.getValue(SphereState.Listening).fileName())
        assertEquals("wave.png", avatar.activityClips.getValue(SphereState.Speaking).fileName())
        assertEquals("failed.png", avatar.activityClips.getValue(SphereState.Error).fileName())
        assertEquals("running.png", avatar.workingClip!!.fileName())
        assertEquals("wave.png", avatar.oneShots.getValue(PetOneShot.Greet).fileName())
        assertEquals("wave.png", avatar.oneShots.getValue(PetOneShot.Done).fileName())
        assertEquals("left.png", avatar.locomotionClips.getValue(PetLocomotion.WalkLeft).fileName())
        assertEquals("right.png", avatar.locomotionClips.getValue(PetLocomotion.WalkRight).fileName())
        assertEquals("left.png", avatar.locomotionClips.getValue(PetLocomotion.RunLeft).fileName())
        assertEquals("right.png", avatar.locomotionClips.getValue(PetLocomotion.RunRight).fileName())
        assertEquals("jump.png", avatar.locomotionClips.getValue(PetLocomotion.Jump).fileName())
        assertEquals("jump.png", avatar.locomotionClips.getValue(PetLocomotion.Fall).fileName())
        assertEquals("wave.png", avatar.locomotionClips.getValue(PetLocomotion.Wave).fileName())
        val travelLeft = avatar.resolveBaseSelection(
            AvatarRenderState(SphereState.Idle, petLocomotion = PetLocomotion.WalkLeft),
        )
        val travelRight = avatar.resolveBaseSelection(
            AvatarRenderState(SphereState.Idle, petLocomotion = PetLocomotion.WalkRight),
        )
        assertEquals("left.png", travelLeft.clip!!.fileName())
        assertFalse(travelLeft.mirrorHorizontally)
        assertEquals("right.png", travelRight.clip!!.fileName())
        assertFalse(travelRight.mirrorHorizontally)
        assertEquals(
            "jump.png",
            avatar.resolveBaseSelection(
                AvatarRenderState(SphereState.Idle, petLocomotion = PetLocomotion.Jump),
            ).clip!!.fileName(),
        )
        assertEquals(
            "jump.png",
            avatar.resolveBaseSelection(
                AvatarRenderState(SphereState.Idle, petLocomotion = PetLocomotion.Fall),
            ).clip!!.fileName(),
        )
        assertEquals(
            "idle.png",
            avatar.resolveBaseSelection(
                AvatarRenderState(SphereState.Idle, petLocomotion = PetLocomotion.Held),
            ).clip!!.fileName(),
        )
        assertEquals(
            "wave.png",
            avatar.resolveBaseSelection(
                AvatarRenderState(SphereState.Idle, petLocomotion = PetLocomotion.Wave),
            ).clip!!.fileName(),
        )
    }

    @Test
    fun `animation priority keeps manipulation and travel ahead of agent activity`() {
        val dir = tempDir()
        writePack(
            dir,
            "priority",
            """{ "id": "priority", "states": {
                 "idle": { "frames": ["idle.png"] },
                 "walking-left": { "frames": ["walk-left.png"] },
                 "running-right": { "frames": ["run-right.png"] },
                 "jumping": { "frames": ["jump.png"] },
                 "falling": { "frames": ["fall.png"] },
                 "held": { "frames": ["held.png"] },
                 "writing": { "frames": ["writing.png"] },
                 "error": { "frames": ["error.png"] }
            } }""",
            imageFiles = listOf(
                "idle.png",
                "walk-left.png",
                "run-right.png",
                "jump.png",
                "fall.png",
                "held.png",
                "writing.png",
                "error.png",
            ),
        )
        val avatar = PetLoader.loadPets(dir).single()

        fun PetClip?.fileName(): String = ((this as FrameSequenceClip).files.single().name)
        data class PriorityCase(
            val activity: SphereState,
            val locomotion: PetLocomotion,
            val expectedFile: String,
        )

        val cases = listOf(
            PriorityCase(SphereState.Streaming, PetLocomotion.Held, "held.png"),
            PriorityCase(SphereState.Error, PetLocomotion.Jump, "jump.png"),
            PriorityCase(SphereState.Streaming, PetLocomotion.Fall, "fall.png"),
            PriorityCase(SphereState.Error, PetLocomotion.WalkLeft, "walk-left.png"),
            PriorityCase(SphereState.Streaming, PetLocomotion.RunRight, "run-right.png"),
            PriorityCase(SphereState.Streaming, PetLocomotion.None, "writing.png"),
            PriorityCase(SphereState.Error, PetLocomotion.None, "error.png"),
            PriorityCase(SphereState.Listening, PetLocomotion.None, "idle.png"),
        )

        cases.forEach { case ->
            val selection = avatar.resolveBaseSelection(
                AvatarRenderState(case.activity, petLocomotion = case.locomotion),
            )
            assertEquals(
                "${case.locomotion} over ${case.activity}",
                case.expectedFile,
                selection.clip.fileName(),
            )
            assertFalse(selection.mirrorHorizontally)
        }
    }

    @Test
    fun `legacy run row supplies directional travel with desktop mirror convention`() {
        val dir = tempDir()
        writePack(
            dir,
            "legacy",
            """{ "id": "legacy", "states": {
                 "idle": { "frames": ["idle.png"] },
                 "run": { "frames": ["run.png"] }
            } }""",
            imageFiles = listOf("idle.png", "run.png"),
        )
        val avatar = PetLoader.loadPets(dir).single()

        fun PetClip?.fileName(): String = ((this as FrameSequenceClip).files.single().name)
        val left = avatar.resolveBaseSelection(
            AvatarRenderState(SphereState.Idle, petLocomotion = PetLocomotion.RunLeft),
        )
        val right = avatar.resolveBaseSelection(
            AvatarRenderState(SphereState.Idle, petLocomotion = PetLocomotion.RunRight),
        )

        assertEquals("run.png", left.clip.fileName())
        assertFalse(left.mirrorHorizontally)
        assertEquals("run.png", right.clip.fileName())
        assertTrue(right.mirrorHorizontally)
    }

    @Test
    fun `requested run locomotion prefers run clip over distinct walking clip`() {
        val dir = tempDir()
        writePack(
            dir,
            "paces",
            """{ "id": "paces", "states": {
                 "idle": { "frames": ["idle.png"] },
                 "walking-left": { "frames": ["walk-left.png"] },
                 "running-left": { "frames": ["run-left.png"] }
            } }""",
            imageFiles = listOf("idle.png", "walk-left.png", "run-left.png"),
        )
        val avatar = PetLoader.loadPets(dir).single()

        fun PetClip?.fileName(): String = ((this as FrameSequenceClip).files.single().name)
        assertEquals(
            "walk-left.png",
            avatar.resolveBaseSelection(
                AvatarRenderState(SphereState.Idle, petLocomotion = PetLocomotion.WalkLeft),
            ).clip.fileName(),
        )
        assertEquals(
            "run-left.png",
            avatar.resolveBaseSelection(
                AvatarRenderState(SphereState.Idle, petLocomotion = PetLocomotion.RunLeft),
            ).clip.fileName(),
        )
    }

    @Test
    fun `tool-only working pose never becomes roaming locomotion`() {
        val dir = tempDir()
        writePack(
            dir,
            "tool-only",
            """{ "id": "tool-only", "states": {
                 "idle": { "frames": ["idle.png"] },
                 "working": { "frames": ["tool.png"] }
            } }""",
            imageFiles = listOf("idle.png", "tool.png"),
        )
        val avatar = PetLoader.loadPets(dir).single()

        fun PetClip?.fileName(): String = ((this as FrameSequenceClip).files.single().name)
        val roaming = avatar.resolveBaseSelection(
            AvatarRenderState(SphereState.Idle, petLocomotion = PetLocomotion.RunRight),
        )
        assertEquals("idle.png", roaming.clip.fileName())
        assertFalse(roaming.mirrorHorizontally)
        assertEquals(
            "idle.png",
            avatar.resolveBaseSelection(
                AvatarRenderState(SphereState.Idle, petLocomotion = PetLocomotion.Jump),
            ).clip.fileName(),
        )
        assertEquals(
            "idle.png",
            avatar.resolveBaseSelection(
                AvatarRenderState(SphereState.Error, petLocomotion = PetLocomotion.RunRight),
            ).clip.fileName(),
        )
        assertEquals(
            "tool.png",
            avatar.resolveBaseSelection(
                AvatarRenderState(SphereState.Streaming, toolCallBurst = 1f),
            ).clip.fileName(),
        )
    }

    @Test
    fun `dedicated falling clip is preferred over jump fallback`() {
        val dir = tempDir()
        writePack(
            dir,
            "falling",
            """{ "id": "falling", "states": {
                 "idle": { "frames": ["idle.png"] },
                 "jumping": { "frames": ["jump.png"] },
                 "falling": { "frames": ["fall.png"] }
            } }""",
            imageFiles = listOf("idle.png", "jump.png", "fall.png"),
        )

        val avatar = PetLoader.loadPets(dir).single()

        assertEquals(
            "fall.png",
            (avatar.locomotionClips.getValue(PetLocomotion.Fall) as FrameSequenceClip).files.single().name,
        )
    }

    @Test
    fun `jump success alias remains available when a pack has no wave`() {
        val dir = tempDir()
        writePack(
            dir,
            "jump-only",
            """{ "id": "jump-only", "states": {
                 "idle": { "frames": ["idle.png"] },
                 "jumping": { "frames": ["jump.png"] }
            } }""",
            imageFiles = listOf("idle.png", "jump.png"),
        )

        val avatar = PetLoader.loadPets(dir).single()

        assertEquals(
            "jump.png",
            (avatar.oneShots.getValue(PetOneShot.Done) as FrameSequenceClip).files.single().name,
        )
    }

    @Test
    fun `single-frame one-shot reactions hold long enough to read`() {
        assertEquals(1800L, petOneShotReleaseDelayMs(frameCount = 1))
        assertEquals(4000L, petOneShotReleaseDelayMs(frameCount = 2))
    }

    @Test
    fun `unknown json keys are tolerated`() {
        val dir = tempDir()
        writePack(
            dir,
            "blob",
            """{ "id": "blob", "label": "Blob", "futureField": 42, "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )

        val avatars = PetLoader.loadPets(dir)

        assertEquals(1, avatars.size)
        assertEquals("blob", avatars.single().id)
    }

    @Test
    fun `schema version above supported is rejected`() {
        val dir = tempDir()
        writePack(
            dir,
            "blob",
            """{ "schemaVersion": 2, "id": "blob", "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )

        assertTrue(PetLoader.loadPets(dir).isEmpty())
    }

    @Test
    fun `schema version zero is rejected`() {
        val dir = tempDir()
        writePack(
            dir,
            "blob",
            """{ "schemaVersion": 0, "id": "blob", "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )

        assertTrue(PetLoader.loadPets(dir).isEmpty())
    }

    @Test
    fun `pack missing the idle clip is rejected`() {
        val dir = tempDir()
        writePack(
            dir,
            "blob",
            """{ "id": "blob", "states": { "thinking": { "frames": ["think.png"], "fps": 6 } } }""",
            imageFiles = listOf("think.png"),
        )

        assertTrue(PetLoader.loadPets(dir).isEmpty())
    }

    @Test
    fun `pack whose idle frames do not exist on disk is rejected`() {
        val dir = tempDir()
        writePack(
            dir,
            "blob",
            """{ "id": "blob", "states": { "idle": { "frames": ["missing.png"], "fps": 6 } } }""",
            imageFiles = emptyList(),
        )

        assertTrue(PetLoader.loadPets(dir).isEmpty())
    }

    @Test
    fun `path-traversal frames are rejected so the pack is skipped`() {
        val dir = tempDir()
        // A real file outside the pack — the guard must still refuse to reach it.
        File(dir, "escape.png").createNewFile()
        writePack(
            dir,
            "blob",
            """{ "id": "blob", "states": { "idle": { "frames": ["../escape.png"], "fps": 6 } } }""",
            imageFiles = emptyList(),
        )

        val avatars = PetLoader.loadPets(dir)

        assertTrue(avatars.none { it.id == "blob" })
        assertTrue(avatars.isEmpty())
    }

    @Test
    fun `pack with only an idle clip loads`() {
        val dir = tempDir()
        writePack(
            dir,
            "blob",
            """{ "id": "blob", "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )

        assertNotNull(PetLoader.loadPets(dir).single())
    }

    @Test
    fun `sprite sheet clip with a 4x4 16-frame grid loads`() {
        val dir = tempDir()
        writePack(
            dir,
            "grid",
            """{ "id": "grid", "states": { "idle": { "sheet": "idle.png", "frameWidth": 128, "frameHeight": 128, "frameCount": 16, "fps": 8 } } }""",
            imageFiles = listOf("idle.png"),
        )

        // The renderer slices any rectangular grid (cols×rows derived from sheet
        // size ÷ cell size), so a 16-frame 4×4 sheet is a first-class clip.
        assertEquals(1, PetLoader.loadPets(dir).size)
    }

    @Test
    fun `pack with idle and speaking clips loads`() {
        val dir = tempDir()
        writePack(
            dir,
            "blob",
            """{ "id": "blob", "states": { "idle": { "frames": ["idle.png"], "fps": 6 }, "speaking": { "frames": ["talk.png"], "fps": 12 } } }""",
            imageFiles = listOf("idle.png", "talk.png"),
        )

        assertEquals(1, PetLoader.loadPets(dir).size)
    }

    @Test
    fun `out-of-range fps values are clamped and still load`() {
        val dir = tempDir()
        writePack(
            dir,
            "fast",
            """{ "id": "fast", "states": { "idle": { "frames": ["idle.png"], "fps": 999 } } }""",
            imageFiles = listOf("idle.png"),
        )
        writePack(
            dir,
            "slow",
            """{ "id": "slow", "states": { "idle": { "frames": ["idle.png"], "fps": 0 } } }""",
            imageFiles = listOf("idle.png"),
        )

        val ids = PetLoader.loadPets(dir).map { it.id }

        assertEquals(listOf("fast", "slow"), ids)
    }

    @Test
    fun `a malformed pack does not break a valid one`() {
        val dir = tempDir()
        writePack(
            dir,
            "good",
            """{ "id": "good", "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )
        writePack(dir, "bad", """not json {""")

        val avatars = PetLoader.loadPets(dir)

        assertEquals(1, avatars.size)
        assertEquals("good", avatars.single().id)
    }

    @Test
    fun `a directory with no pet json is skipped`() {
        val dir = tempDir()
        File(dir, "empty-pack").mkdirs()

        assertTrue(PetLoader.loadPets(dir).isEmpty())
    }

    @Test
    fun `an empty directory yields no avatars`() {
        assertTrue(PetLoader.loadPets(tempDir()).isEmpty())
    }

    @Test
    fun `a non-existent directory yields no avatars`() {
        val parent = tempDir()
        assertTrue(PetLoader.loadPets(File(parent, "does-not-exist")).isEmpty())
    }

    @Test
    fun `avatars are sorted by pack directory name`() {
        val dir = tempDir()
        writePack(
            dir,
            "zebra",
            """{ "id": "zebra", "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )
        writePack(
            dir,
            "apple",
            """{ "id": "apple", "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )

        val ids = PetLoader.loadPets(dir).map { it.id }

        assertEquals(listOf("apple", "zebra"), ids)
    }

    @Test
    fun `deletePet removes the matching pack and leaves the rest`() {
        val dir = tempDir()
        writePack(
            dir,
            "lucy",
            """{ "id": "lucy", "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )
        writePack(
            dir,
            "blob",
            """{ "id": "blob", "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )

        assertTrue(PetLoader.deletePet(dir, "lucy"))

        assertEquals(listOf("blob"), PetLoader.loadPets(dir).map { it.id })
    }

    @Test
    fun `deletePet matches by manifest id even when the directory name differs`() {
        val dir = tempDir()
        // Pack directory "pack-a" but manifest id "lucy" — delete must resolve the
        // id, not assume dir == id.
        writePack(
            dir,
            "pack-a",
            """{ "id": "lucy", "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )

        assertTrue(PetLoader.deletePet(dir, "lucy"))
        assertTrue(PetLoader.loadPets(dir).isEmpty())
    }

    @Test
    fun `deletePet returns false when nothing matches`() {
        val dir = tempDir()
        writePack(
            dir,
            "blob",
            """{ "id": "blob", "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )

        assertFalse(PetLoader.deletePet(dir, "nope"))
        assertEquals(1, PetLoader.loadPets(dir).size)
    }
}
