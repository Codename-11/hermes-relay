package com.hermesandroid.relay.auth

import com.hermesandroid.relay.data.SupervisedCapabilities
import com.hermesandroid.relay.data.SupervisedModePolicy
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisedModeAuthPayloadTest {
    @Test fun `active policy reports only public capability ids`() {
        val payload = relaySupervisedModePayload(
            SupervisedModePolicy(
                enabled = true,
                pinnedProfileName = "willow",
                capabilities = SupervisedCapabilities(attachments = true, voice = true),
            ),
        )
        assertTrue(payload.getValue("active").jsonPrimitive.boolean)
        assertEquals("willow", payload.getValue("profile_label").jsonPrimitive.content)
        val capabilities = payload.getValue("capabilities").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("text_chat" in capabilities)
        assertTrue("attachments" in capabilities)
        assertTrue("voice" in capabilities)
        assertFalse(capabilities.any { it.contains("model") || it.contains("tool") })
    }

    @Test fun `inactive update explicitly clears Relay tag`() {
        val payload = relaySupervisedModePayload(SupervisedModePolicy())
        assertFalse(payload.getValue("active").jsonPrimitive.boolean)
        assertEquals(setOf("active"), payload.keys)
    }

    @Test fun `live update uses typed correlated system envelope`() {
        val envelope = relaySupervisedModeUpdateEnvelope(
            SupervisedModePolicy(
                enabled = true,
                pinnedProfileName = "willow",
                capabilities = SupervisedCapabilities(voice = true),
            ),
        )

        assertEquals("system", envelope.channel)
        assertEquals("supervised.update", envelope.type)
        assertTrue(envelope.id.isNotBlank())
        val mode = envelope.payload.getValue("supervised_mode")
            .jsonObject
        assertTrue(mode.getValue("active").jsonPrimitive.boolean)
        assertEquals("willow", mode.getValue("profile_label").jsonPrimitive.content)
    }
}
