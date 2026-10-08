package com.hermesandroid.relay.ui

import android.net.Uri
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.notifications.ChatNotificationTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatNotificationNavigationTest {
    private val target = ChatNotificationTarget("server / a", "profile & a", "stored / a")
    private fun step(hydrated: Boolean = true, allowed: Boolean = true,
        connections: Set<String> = setOf(target.connectionId), active: String? = target.connectionId,
        settled: Boolean = true, selected: String? = target.profile,
        profiles: Set<String> = setOf(target.profile!!)) = chatNotificationNavigationStep(
        target, hydrated, allowed, connections, active, settled, selected, profiles,
    )

    @Test fun destinationRoundTripsReservedCharactersAndDefaultProfileIdentity() {
        val uri = Uri.parse("hermes-relay://app/" + target.route())
        assertEquals(target.connectionId, uri.getQueryParameter("connectionId"))
        assertEquals(target.profile, uri.getQueryParameter("profile"))
        assertEquals(target.sessionId, uri.getQueryParameter("sessionId"))
        assertNotEquals(target.copy(profile = null).key, target.copy(profile = "default").key)
        assertEquals(target, ChatNotificationTarget.from(AgentDisplay.profileContextKey(target.connectionId, target.profile), target.sessionId))
        assertNull(ChatNotificationTarget.from(null, "session"))
    }

    @Test fun coldStartAndConnectionSwitchCannotOpenAgainstPreviousProfile() {
        assertEquals(ChatNotificationNavigationStep.Wait, step(hydrated = false, active = "old"))
        assertEquals(ChatNotificationNavigationStep.SwitchConnection, step(active = "old"))
        assertEquals(ChatNotificationNavigationStep.Wait, step(settled = false, selected = "old"))
        assertEquals(ChatNotificationNavigationStep.SelectProfile, step(selected = "old"))
        assertEquals(ChatNotificationNavigationStep.Open, step())
    }

    @Test fun missingConnectionAndSupervisedTargetsNeverFallBack() {
        assertEquals(ChatNotificationNavigationStep.Reject, step(connections = emptySet()))
        assertEquals(ChatNotificationNavigationStep.Reject, step(allowed = false))
        assertEquals(ChatNotificationNavigationStep.Wait, step(profiles = emptySet()))
    }
}
