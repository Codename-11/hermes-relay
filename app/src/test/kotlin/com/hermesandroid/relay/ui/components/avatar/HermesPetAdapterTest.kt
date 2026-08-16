package com.hermesandroid.relay.ui.components.avatar

import com.hermesandroid.relay.ui.components.SphereState
import com.hermesandroid.relay.viewmodel.connection.ProfileController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HermesPetAdapterTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `upstream rows keep geometry and map activity aliases`() {
        val sheet = temp.newFile("pet.png").apply { writeBytes(byteArrayOf(1)) }
        val presentation = ProfileController.HermesPetPresentation(
            connectionId = "connection",
            profileName = "work",
            slug = "boba",
            displayName = "Boba",
            spritesheetPath = sheet.absolutePath,
            spritesheetRevision = "1:1",
            frameWidth = 192,
            frameHeight = 208,
            framesPerState = 8,
            framesByState = mapOf("idle" to 6, "run" to 7, "review" to 4),
            framesByRow = mapOf("idle" to 5, "running" to 8),
            loopMs = 1_000,
            scale = 1f,
            stateRows = listOf("idle", "running", "review"),
        )

        val avatar = presentation.toAvatar()

        assertNotNull(avatar)
        assertEquals("hermes:boba", avatar?.id)
        assertEquals(5, avatar?.activityClips?.get(SphereState.Idle)?.frameCount)
        assertEquals(8, avatar?.activityClips?.get(SphereState.Streaming)?.frameCount)
        assertEquals("running", avatar?.activityClipSources?.get(SphereState.Streaming))
        assertEquals("review", avatar?.activityClipSources?.get(SphereState.Thinking))
    }
}
