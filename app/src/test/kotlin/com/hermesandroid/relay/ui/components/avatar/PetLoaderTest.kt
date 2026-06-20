package com.hermesandroid.relay.ui.components.avatar

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
    fun `declared tools and intensity reactivity are clamped out of the badge`() {
        val dir = tempDir()
        writePack(
            dir,
            "blob",
            """{ "id": "blob", "reactive": { "voice": true, "tools": true, "intensity": true }, "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }""",
            imageFiles = listOf("idle.png"),
        )

        val avatar = PetLoader.loadPets(dir).single()

        // The renderer only consumes voice today, so a manifest that declares
        // tools/intensity must NOT advertise them on the picker badge.
        assertTrue(avatar.reactivity.voice)
        assertFalse(avatar.reactivity.tools)
        assertFalse(avatar.reactivity.intensity)
        assertEquals("Voice", avatar.reactivity.summary())
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
}
