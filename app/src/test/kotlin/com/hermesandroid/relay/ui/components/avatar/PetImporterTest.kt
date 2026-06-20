package com.hermesandroid.relay.ui.components.avatar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class PetImporterTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File =
        createTempDirectory("pet-import-test").toFile().also { tempDirs.add(it) }

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    /** Build an in-memory zip from name → bytes. */
    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun petsDir(): File = File(tempDir(), "pets").apply { mkdirs() }
    private fun workDir(): File = File(tempDir(), "work").apply { mkdirs() }

    private val minimalManifest =
        """{ "id": "blob", "label": "Blob", "states": { "idle": { "frames": ["idle.png"], "fps": 6 } } }"""

    @Test
    fun `valid pack at archive root imports and loads back`() {
        val zip = zipOf(
            mapOf(
                "pet.json" to minimalManifest.toByteArray(),
                "idle.png" to byteArrayOf(1, 2, 3),
            )
        )
        val pets = petsDir()

        val result = PetImporter.importStream(ByteArrayInputStream(zip), pets, workDir())

        assertTrue(result is PetImportResult.Success)
        result as PetImportResult.Success
        assertEquals("blob", result.id)
        assertEquals("Blob", result.label)
        assertTrue(File(pets, "blob/pet.json").isFile)
        assertTrue(File(pets, "blob/idle.png").isFile)
        // The freshly-imported pack is discoverable by the loader.
        assertEquals(1, PetLoader.loadPets(pets).size)
    }

    @Test
    fun `pack nested under a folder imports under its manifest id`() {
        val zip = zipOf(
            mapOf(
                "lucy/pet.json" to minimalManifest.toByteArray(),
                "lucy/idle.png" to byteArrayOf(1),
            )
        )
        val pets = petsDir()

        val result = PetImporter.importStream(ByteArrayInputStream(zip), pets, workDir())

        assertTrue(result is PetImportResult.Success)
        // Installed under the manifest id ("blob"), not the archive folder name.
        assertTrue(File(pets, "blob/pet.json").isFile)
    }

    @Test
    fun `archive without a pet json fails`() {
        val zip = zipOf(mapOf("idle.png" to byteArrayOf(1)))

        val result = PetImporter.importStream(ByteArrayInputStream(zip), petsDir(), workDir())

        assertTrue(result is PetImportResult.Failure)
    }

    @Test
    fun `pack missing the idle clip fails`() {
        val zip = zipOf(
            mapOf(
                "pet.json" to """{ "id": "x", "states": { "thinking": { "frames": ["t.png"], "fps": 6 } } }""".toByteArray(),
                "t.png" to byteArrayOf(1),
            )
        )

        val result = PetImporter.importStream(ByteArrayInputStream(zip), petsDir(), workDir())

        assertTrue(result is PetImportResult.Failure)
    }

    @Test
    fun `zip-slip entry is refused and writes nothing outside the staging dir`() {
        val pets = petsDir()
        val work = workDir()
        val zip = zipOf(
            mapOf(
                "pet.json" to minimalManifest.toByteArray(),
                "idle.png" to byteArrayOf(1),
                "../escape.png" to byteArrayOf(9, 9, 9),
            )
        )

        val result = PetImporter.importStream(ByteArrayInputStream(zip), pets, work)

        assertTrue(result is PetImportResult.Failure)
        // The escaping entry resolves to work/escape.png — it must never land.
        assertFalse(File(work, "escape.png").exists())
        assertFalse(File(pets, "escape.png").exists())
    }
}
