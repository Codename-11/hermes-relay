package com.hermesandroid.relay.ui

import com.hermesandroid.relay.data.SupervisedCapabilities
import com.hermesandroid.relay.data.SupervisedModePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisedNavigationPolicyTest {
    @Test fun `locked surface permits only approved destinations`() {
        assertTrue(isSupervisedRouteAllowed("chat?sessionId=owned", false))
        assertTrue(isSupervisedRouteAllowed("settings", false))
        // The full Appearance destination includes profile/avatar/pet controls.
        // The supervised Settings root owns its own allowlisted theme controls.
        assertFalse(isSupervisedRouteAllowed("settings/appearance", false))
        assertFalse(isSupervisedRouteAllowed("settings/about", false))
        assertFalse(isSupervisedRouteAllowed("manage", false))
        assertFalse(isSupervisedRouteAllowed("settings/developer", false))
        assertFalse(isSupervisedRouteAllowed("settings/supervised", false))
        assertFalse(isSupervisedRouteAllowed(null, false))
    }

    @Test fun `parent unlock permits full navigation`() {
        assertTrue(isSupervisedRouteAllowed("manage", true))
    }

    @Test fun `navigation waits for connection store before trusting null active id`() {
        assertFalse(isRelayNavigationHydrated(false, null, false))
        assertTrue(isRelayNavigationHydrated(true, null, false))
        assertFalse(isRelayNavigationHydrated(true, "home", false))
        assertTrue(isRelayNavigationHydrated(true, "home", true))
    }

    @Test fun `parent access relocks as soon as chat becomes current`() {
        assertTrue(shouldRelockParentAccess(true, true, "chat?sessionId=ignored"))
        assertFalse(shouldRelockParentAccess(true, true, "settings/supervised"))
        assertFalse(shouldRelockParentAccess(false, true, "chat"))
        assertFalse(shouldRelockParentAccess(true, false, "chat"))
    }

    @Test fun `supervised route session requires history pinned profile and trusted ownership proof`() {
        val policy = SupervisedModePolicy(
            enabled = true,
            pinnedProfileName = "willow",
            capabilities = SupervisedCapabilities(conversationHistory = true),
        )

        assertFalse(mayRestoreSupervisedSessionRoute(policy, "session-1", "willow", false))
        assertFalse(mayRestoreSupervisedSessionRoute(policy, "session-1", "parent", true))
        assertTrue(mayRestoreSupervisedSessionRoute(policy, "session-1", "WILLOW", true))
        assertFalse(
            mayRestoreSupervisedSessionRoute(
                policy.copy(capabilities = policy.capabilities.copy(conversationHistory = false)),
                "session-1",
                "willow",
                true,
            ),
        )
    }

    @Test fun `supervised external route discards session profile and proactive targets`() {
        val policy = SupervisedModePolicy(
            enabled = true,
            pinnedProfileName = "willow",
            capabilities = SupervisedCapabilities(conversationHistory = true),
        )
        val external = SupervisedChatRouteArgs(
            sessionId = "parent-session",
            profile = "willow",
            proactiveChatId = "phone",
        )

        assertTrue(
            sanitizeSupervisedChatRouteArgs(policy, external, false) ==
                SupervisedChatRouteArgs(),
        )
        assertTrue(
            sanitizeSupervisedChatRouteArgs(policy, external, true) ==
                external.copy(proactiveChatId = null),
        )
        assertTrue(
            sanitizeSupervisedChatRouteArgs(SupervisedModePolicy(), external, false) == external,
        )
    }

    @Test fun `first enable requires configured policy secure screen and successful device credential`() {
        val configured = SupervisedModePolicy(pinnedProfileName = "willow")

        assertFalse(
            mayEnableSupervisedMode(
                configured,
                deviceSecure = false,
                deviceCredentialConfirmed = true,
            ),
        )
        assertFalse(
            mayEnableSupervisedMode(
                configured,
                deviceSecure = true,
                deviceCredentialConfirmed = false,
            ),
        )
        assertFalse(mayEnableSupervisedMode(SupervisedModePolicy(), true, true))
        assertTrue(mayEnableSupervisedMode(configured, true, true))
        assertFalse(mayEnableSupervisedMode(configured.copy(enabled = true), true, true))
    }
}
