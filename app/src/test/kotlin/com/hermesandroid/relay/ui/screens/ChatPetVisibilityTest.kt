package com.hermesandroid.relay.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPetVisibilityTest {

    @Test
    fun petRemainsVisibleOnUnobstructedChat() {
        assertFalse(shouldHideChatPet())
    }

    @Test
    fun everyModalChatSurfaceSuppressesPet() {
        assertTrue(shouldHideChatPet(voiceMode = true))
        assertTrue(shouldHideChatPet(drawerOpenOrMoving = true))
        assertTrue(shouldHideChatPet(commandPaletteVisible = true))
        assertTrue(shouldHideChatPet(modelSheetVisible = true))
        assertTrue(shouldHideChatPet(effortSheetVisible = true))
        assertTrue(shouldHideChatPet(contextSheetVisible = true))
        assertTrue(shouldHideChatPet(backgroundProcessesVisible = true))
        assertTrue(shouldHideChatPet(agentInfoVisible = true))
    }
}
