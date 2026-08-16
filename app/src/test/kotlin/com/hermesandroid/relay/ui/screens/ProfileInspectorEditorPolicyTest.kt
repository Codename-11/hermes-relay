package com.hermesandroid.relay.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileInspectorEditorPolicyTest {
    @Test
    fun `config save requires complete model identity and an idle request`() {
        assertFalse(profileConfigSaveEnabled("", "gpt-5.6", saving = false))
        assertFalse(profileConfigSaveEnabled("openai", "", saving = false))
        assertFalse(profileConfigSaveEnabled("openai", "gpt-5.6", saving = true))
        assertTrue(profileConfigSaveEnabled("openai", "gpt-5.6", saving = false))
    }

    @Test
    fun `gateway save action appears for either kind of retained draft`() {
        assertFalse(gatewayDraftSaveVisible(emptyMap(), emptyMap()))
        assertTrue(gatewayDraftSaveVisible(mapOf("weather" to false), emptyMap()))
        assertTrue(gatewayDraftSaveVisible(emptyMap(), mapOf("terminal" to false)))
    }
}
