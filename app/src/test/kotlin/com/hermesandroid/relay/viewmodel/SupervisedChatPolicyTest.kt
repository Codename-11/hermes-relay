package com.hermesandroid.relay.viewmodel

import com.hermesandroid.relay.data.SupervisedModePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupervisedChatPolicyTest {
    @Test
    fun `normal mode preserves slash commands`() {
        assertNull(supervisedMessageBlockReason(SupervisedModePolicy(), " /model"))
    }

    @Test
    fun `enabled policy fails closed without pinned profile`() {
        assertEquals(
            "Supervised mode is unavailable until the parent selects a profile.",
            supervisedMessageBlockReason(SupervisedModePolicy(enabled = true), "hello"),
        )
    }

    @Test
    fun `active policy blocks slash commands after unicode whitespace`() {
        val policy = SupervisedModePolicy(enabled = true, pinnedProfileName = "willow")
        assertEquals(
            "Slash commands are unavailable in supervised mode.",
            supervisedMessageBlockReason(policy, "\u2003\t /model hidden"),
        )
        assertNull(supervisedMessageBlockReason(policy, "please explain /model"))
    }
}
