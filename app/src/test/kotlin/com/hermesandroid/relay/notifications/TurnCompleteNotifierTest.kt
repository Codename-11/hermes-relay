package com.hermesandroid.relay.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import com.hermesandroid.relay.MainActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TurnCompleteNotifierTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val target = ChatNotificationTarget("connection / a", "work & play", "session / 1")

    @Before fun setUp() {
        manager.cancelAll()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun post(owner: ChatNotificationTarget = target, turn: String = "turn-1") =
        TurnCompleteNotifier.notifyTurnComplete(app, owner, turn, "Test agent", "Synthetic reply")

    @Test fun everyConnectionProfileAndSessionKeepsItsOwnIntentAndSlot() {
        val owners = listOf(target, target.copy(connectionId = "other"),
            target.copy(profile = null), target.copy(profile = "default"), target.copy(sessionId = "other"))
        owners.forEach { post(it) }
        assertEquals(owners.size, manager.activeNotifications.size)
        owners.forEach { owner ->
            val n = manager.activeNotifications.single { it.tag == TurnCompleteNotifier.notificationTag(owner) }
            val intent = shadowOf(n.notification.contentIntent).savedIntent
            assertEquals(owner.route(), intent.getStringExtra(MainActivity.EXTRA_NAV_ROUTE))
            assertEquals(owner.key, intent.data.toString())
        }
        assertEquals(owners.size, manager.activeNotifications.map { it.notification.contentIntent }.distinct().size)
    }

    @Test fun replayDoesNotReplaceSameTurnButNewTurnUpdatesOnlyItsConversation() {
        post()
        val first = manager.activeNotifications.single().notification
        post()
        assertSame(first, manager.activeNotifications.single().notification)
        post(target.copy(sessionId = "sibling"))
        post(turn = "turn-2")
        assertEquals(2, manager.activeNotifications.size)
        assertNotSame(first, manager.activeNotifications.single { it.tag == TurnCompleteNotifier.notificationTag(target) }.notification)
    }

    @Test fun openingOneConversationPreservesSiblingReplyAndAskAlerts() {
        val sibling = target.copy(profile = "other")
        post()
        post(sibling)
        val ask = com.hermesandroid.relay.network.upstream.GatewayAsk(
            kind = com.hermesandroid.relay.network.upstream.GatewayAsk.Kind.CLARIFY,
            requestId = "ask", text = "Synthetic question", timeoutSeconds = 0,
        )
        for (owner in listOf(target, sibling)) {
            InteractionRequestNotifier.notify(app, owner.sessionId, ask, owner.profile, true, false, owner.connectionId)
        }
        TurnCompleteNotifier.cancel(app, target)
        InteractionRequestNotifier.cancelConversation(app, target)
        assertEquals(2, manager.activeNotifications.size)
        assertTrue(manager.activeNotifications.all {
            it.notification.extras.getString(ChatNotificationTarget.EXTRA_OWNER) == sibling.key
        })
    }

    @Test fun permissionDenialDoesNotPostAndPublicCopyOmitsReply() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        post()
        assertTrue(manager.activeNotifications.isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        post()
        val n = manager.activeNotifications.single().notification
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertFalse(n.publicVersion.extras.toString().contains("Synthetic reply"))
    }

    @Test fun clearingDisabledAlertsPreservesOtherChannels() {
        post()
        manager.createNotificationChannel(android.app.NotificationChannel("other", "Other", 3))
        manager.notify(55, Notification.Builder(app, "other").setSmallIcon(com.hermesandroid.relay.R.mipmap.ic_launcher).build())
        TurnCompleteNotifier.cancelAll(app)
        assertEquals(55, manager.activeNotifications.single().id)
    }
}
