package com.hermesandroid.relay.ui.components.avatar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class PetCapabilityPreviewTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun `current Petdex rows report direct actions and honest fallbacks`() {
        val avatar = avatarWith(
            "idle",
            "running-right",
            "running-left",
            "waving",
            "jumping",
            "failed",
            "waiting",
            "running",
            "review",
        )

        val mappings = avatar.previewMappings().associateBy { it.action }

        assertMapping(mappings, PetPreviewAction.Idle, "idle", PetPreviewSupport.Direct)
        assertMapping(mappings, PetPreviewAction.WalkLeft, "running-left", PetPreviewSupport.Direct)
        assertMapping(mappings, PetPreviewAction.WalkRight, "running-right", PetPreviewSupport.Direct)
        assertMapping(mappings, PetPreviewAction.Jump, "jumping", PetPreviewSupport.Direct)
        assertMapping(mappings, PetPreviewAction.Fall, "jumping", PetPreviewSupport.Fallback)
        assertMapping(mappings, PetPreviewAction.Held, "idle", PetPreviewSupport.Fallback)
        assertMapping(mappings, PetPreviewAction.Wave, "waving", PetPreviewSupport.Direct)
        assertMapping(mappings, PetPreviewAction.Working, "running", PetPreviewSupport.Direct)
        assertMapping(mappings, PetPreviewAction.Review, "review", PetPreviewSupport.Direct)
        assertMapping(mappings, PetPreviewAction.Waiting, "waiting", PetPreviewSupport.Direct)
        assertMapping(mappings, PetPreviewAction.Error, "failed", PetPreviewSupport.Direct)
        assertTrue(avatar.forCapabilityPreview().oneShots.isEmpty())
    }

    @Test
    fun `one directional row previews the opposite direction by mirroring`() {
        val avatar = avatarWith("idle", "walking-right")

        val mappings = avatar.previewMappings().associateBy { it.action }

        assertMapping(mappings, PetPreviewAction.WalkRight, "walking-right", PetPreviewSupport.Direct)
        assertMapping(mappings, PetPreviewAction.WalkLeft, "walking-right", PetPreviewSupport.Mirrored)
    }

    @Test
    fun `legacy in-place running row is labeled as a travel fallback`() {
        val mappings = avatarWith("idle", "running").previewMappings().associateBy { it.action }

        assertMapping(mappings, PetPreviewAction.WalkLeft, "running", PetPreviewSupport.Fallback)
        assertMapping(
            mappings,
            PetPreviewAction.WalkRight,
            "running",
            PetPreviewSupport.MirroredFallback,
        )
    }

    @Test
    fun `minimal pet exposes every action without pretending fallback is native`() {
        val mappings = avatarWith("idle").previewMappings()

        assertEquals(PetPreviewAction.entries.size, mappings.size)
        assertEquals(PetPreviewSupport.Direct, mappings.single { it.action == PetPreviewAction.Idle }.support)
        mappings.filterNot { it.action == PetPreviewAction.Idle }.forEach { mapping ->
            assertEquals(PetPreviewSupport.Fallback, mapping.support)
            assertEquals("idle", mapping.sourceKey)
        }
    }

    private fun avatarWith(vararg stateKeys: String): PetAvatar {
        val dir = createTempDirectory("pet-capability-preview-test").toFile()
            .also(tempDirs::add)
        val states = stateKeys.associateWith { key ->
            val fileName = "$key.png"
            File(dir, fileName).createNewFile()
            PetClipSpec(frames = listOf(fileName))
        }
        return PetSpec(id = "preview", states = states).toAvatar(dir)
    }

    private fun assertMapping(
        mappings: Map<PetPreviewAction, PetPreviewMapping>,
        action: PetPreviewAction,
        sourceKey: String,
        support: PetPreviewSupport,
    ) {
        val mapping = mappings.getValue(action)
        assertEquals(sourceKey, mapping.sourceKey)
        assertEquals(support, mapping.support)
    }
}
