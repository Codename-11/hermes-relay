package com.hermesandroid.relay.petdex

import com.hermesandroid.relay.ui.components.avatar.PetLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

@RunWith(RobolectricTestRunner::class)
class PetdexInstallerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `current v1 v2 and legacy atlas layouts map state rows to cell offsets`() {
        val current = PetdexAtlasLayout.fromDimensions(1536, 1872)
        val currentV2 = PetdexAtlasLayout.fromDimensions(1536, 2288)
        val legacy = PetdexAtlasLayout.fromDimensions(1728, 1664)
        assertNotNull(current)
        assertNotNull(currentV2)
        assertNotNull(legacy)

        val currentSpec = current!!.toPetSpec(PET, META, "spritesheet.webp")
        assertEquals(0, currentSpec.states.getValue("idle").startFrame)
        assertEquals(1 * 8, currentSpec.states.getValue("running-right").startFrame)
        assertEquals(2 * 8, currentSpec.states.getValue("running-left").startFrame)
        assertEquals(3 * 8, currentSpec.states.getValue("waving").startFrame)
        assertEquals(4 * 8, currentSpec.states.getValue("jumping").startFrame)
        assertEquals(5 * 8, currentSpec.states.getValue("failed").startFrame)
        assertEquals(6 * 8, currentSpec.states.getValue("waiting").startFrame)
        assertEquals(7 * 8, currentSpec.states.getValue("running").startFrame)
        assertEquals(8 * 8, currentSpec.states.getValue("review").startFrame)

        val currentV2Spec = currentV2!!.toPetSpec(PET, META, "spritesheet.webp")
        assertEquals(currentSpec.states, currentV2Spec.states)
        assertEquals(9, currentV2Spec.states.size)
        assertFalse(currentV2Spec.states.containsKey("look-row-9"))
        assertFalse(currentV2Spec.states.containsKey("look-row-10"))

        val legacySpec = legacy!!.toPetSpec(PET, META, "spritesheet.webp")
        assertEquals(0, legacySpec.states.getValue("idle").startFrame)
        assertEquals(1 * 9, legacySpec.states.getValue("wave").startFrame)
        assertEquals(2 * 9, legacySpec.states.getValue("run").startFrame)
        assertEquals(3 * 9, legacySpec.states.getValue("failed").startFrame)
        assertEquals(4 * 9, legacySpec.states.getValue("review").startFrame)
        assertEquals(5 * 9, legacySpec.states.getValue("jump").startFrame)
    }

    @Test
    fun `unsupported atlas dimensions are rejected`() {
        assertEquals(null, PetdexAtlasLayout.fromDimensions(1536, 1664))
        assertEquals(null, PetdexAtlasLayout.fromDimensions(1536, 2080))
        assertEquals(null, PetdexAtlasLayout.fromDimensions(20_000, 20_000))
    }

    @Test
    fun `failed install preserves existing pet and cleans staging`() = runBlocking {
        val petsDir = temp.newFolder("pets")
        val existing = java.io.File(petsDir, PET.installedAvatarId).apply { mkdirs() }
        java.io.File(existing, "marker").writeText("keep")
        val fetcher = PetdexFetcher { url, _, _ ->
            if (url.endsWith("pet.json")) METADATA.encodeToByteArray() else byteArrayOf(1, 2, 3)
        }

        val result = PetdexInstaller(fetcher).installTo(petsDir, PET)

        assertTrue(result is PetdexInstallResult.Failure)
        assertEquals("keep", java.io.File(existing, "marker").readText())
        assertFalse(petsDir.listFiles().orEmpty().any { it.name.startsWith(".petdex-") })
    }

    @Test
    fun `successful install reloads offline through PetLoader`() = runBlocking {
        val petsDir = temp.newFolder("installed-pets")
        val sprite = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(1536, 1872, BufferedImage.TYPE_INT_ARGB), "png", output)
            output.toByteArray()
        }
        val fetcher = PetdexFetcher { url, _, _ ->
            if (url.endsWith("pet.json")) METADATA.encodeToByteArray() else sprite
        }

        val result = PetdexInstaller(fetcher).installTo(petsDir, PET)

        assertTrue(result is PetdexInstallResult.Success)
        val installed = PetLoader.loadPets(petsDir)
        assertEquals(listOf(PET.installedAvatarId), installed.map { it.id })
        assertTrue(java.io.File(petsDir, "${PET.installedAvatarId}/pet.json").readText().contains("\"source\": \"petdex\""))
        assertFalse(petsDir.listFiles().orEmpty().any { it.name.startsWith(".petdex-") })
    }

    @Test
    fun `loader ignores stale Petdex transaction directories`() = runBlocking {
        val petsDir = temp.newFolder("transaction-pets")
        val sprite = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(1536, 1872, BufferedImage.TYPE_INT_ARGB), "png", output)
            output.toByteArray()
        }
        val fetcher = PetdexFetcher { url, _, _ ->
            if (url.endsWith("pet.json")) METADATA.encodeToByteArray() else sprite
        }
        assertTrue(PetdexInstaller(fetcher).installTo(petsDir, PET) is PetdexInstallResult.Success)
        val target = java.io.File(petsDir, PET.installedAvatarId)
        target.copyRecursively(java.io.File(petsDir, ".petdex-backup-stale"))

        assertEquals(listOf(PET.installedAvatarId), PetLoader.loadPets(petsDir).map { it.id })
    }

    @Test
    fun `installer preserves cancellation`() {
        val installer = PetdexInstaller(PetdexFetcher { _, _, _ ->
            throw CancellationException("cancelled")
        })

        org.junit.Assert.assertThrows(CancellationException::class.java) {
            runBlocking { installer.installTo(temp.newFolder("cancelled-install"), PET) }
        }
    }

    private companion object {
        const val METADATA = """{"id":"boba","displayName":"Boba","description":"Friendly"}"""
        val META = PetdexMetadata(id = "boba", displayName = "Boba", description = "Friendly")
        val PET = PetdexPet(
            slug = "boba",
            displayName = "Boba",
            kind = "creature",
            submittedBy = "Maker",
            spritesheetUrl = "https://assets.petdex.dev/sprite.webp",
            petJsonUrl = "https://assets.petdex.dev/pet.json",
        )
    }
}
