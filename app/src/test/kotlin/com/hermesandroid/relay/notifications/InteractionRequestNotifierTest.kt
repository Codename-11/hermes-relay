package com.hermesandroid.relay.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.hermesandroid.relay.MainActivity
import com.hermesandroid.relay.network.upstream.GatewayAsk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class InteractionRequestNotifierTest {

    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @After
    fun tearDown() {
        manager.cancelAll()
    }

    @Test
    fun backgroundAndSettingAndPermissionGatesAreExplicit() {
        assertTrue(InteractionRequestNotifier.shouldPost(true, false, true))
        assertFalse(InteractionRequestNotifier.shouldPost(false, false, true))
        assertFalse(InteractionRequestNotifier.shouldPost(true, true, true))
        assertFalse(InteractionRequestNotifier.shouldPost(true, false, false))
    }

    @Test
    fun requestReplayReplacesOneNotificationAndDeepLinksToExactSession() {
        val ask = approval(command = "rm -rf /private/path")

        assertTrue(post(ask, SESSION_ID, PROFILE))
        assertTrue(post(ask, SESSION_ID, PROFILE))

        val active = manager.activeNotifications.single()
        assertEquals(InteractionRequestNotifier.notificationTag(SESSION_ID, ask, PROFILE), active.tag)
        val tapIntent = shadowOf(active.notification.contentIntent).savedIntent
        assertEquals(
            InteractionRequestNotifier.chatRoute(SESSION_ID, PROFILE),
            tapIntent.getStringExtra(MainActivity.EXTRA_NAV_ROUTE),
        )
        assertEquals(
            InteractionRequestNotifier.requestKey(SESSION_ID, ask, PROFILE),
            tapIntent.data?.lastPathSegment,
        )
    }

    @Test
    fun copyAndPublicVersionNeverExposePromptCommandOrSecretMetadata() {
        val secret = GatewayAsk(
            kind = GatewayAsk.Kind.SECRET,
            requestId = "secret-1",
            text = "Paste the production payment token",
            envVar = "PAYMENT_TOKEN",
            timeoutSeconds = 300,
        )

        assertTrue(post(secret, SESSION_ID))

        val notification = manager.activeNotifications.single().notification
        val privateText = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        val privateTitle = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val publicText = notification.publicVersion.extras
            .getCharSequence(Notification.EXTRA_TEXT)
            .toString()
        val visibleCopy = "$privateTitle $privateText $publicText"
        assertFalse(visibleCopy.contains(secret.text))
        assertFalse(visibleCopy.contains(secret.envVar!!))
        assertTrue(visibleCopy.contains("Open Hermes"))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
    }

    @Test
    fun resolvedOrExpiredRequestCancelsOnlyItsStableSlot() {
        val first = approval(command = "first")
        val second = GatewayAsk(
            kind = GatewayAsk.Kind.CLARIFY,
            requestId = "clarify-2",
            text = "second",
            timeoutSeconds = 300,
        )
        assertTrue(post(first, SESSION_ID))
        assertTrue(post(second, SESSION_ID))
        assertEquals(2, manager.activeNotifications.size)

        InteractionRequestNotifier.cancel(context, SESSION_ID, first)

        val remaining = manager.activeNotifications.single()
        assertEquals(InteractionRequestNotifier.notificationTag(SESSION_ID, second), remaining.tag)
    }

    @Test
    fun appRelaunchCleanupRemovesOnlyInteractionChannelNotifications() {
        val ask = approval(command = "private")
        assertTrue(post(ask, SESSION_ID))
        val unrelated = Notification.Builder(context, "other-channel")
            .setSmallIcon(com.hermesandroid.relay.R.mipmap.ic_launcher)
            .setContentTitle("Unrelated")
            .build()
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                "other-channel",
                "Other",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        manager.notify(99, unrelated)

        InteractionRequestNotifier.cancelAll(context)

        assertEquals(1, manager.activeNotifications.size)
        assertEquals(99, manager.activeNotifications.single().id)
    }

    @Test
    fun missingAndroidPermissionSkipsPosting() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
        )

        assertFalse(post(approval(command = "hidden"), SESSION_ID))
        assertTrue(manager.activeNotifications.isEmpty())
    }

    private fun post(ask: GatewayAsk, sessionId: String, profile: String? = null): Boolean =
        InteractionRequestNotifier.notify(
            context = context,
            sessionId = sessionId,
            ask = ask,
            profile = profile,
            alertsEnabled = true,
            appForeground = false,
        )

    private fun approval(command: String) = GatewayAsk(
        kind = GatewayAsk.Kind.APPROVAL,
        requestId = null,
        text = command,
        timeoutSeconds = 0,
    )

    companion object {
        private const val SESSION_ID = "session with spaces/and/slashes"
        private const val PROFILE = "work profile"
    }
}
