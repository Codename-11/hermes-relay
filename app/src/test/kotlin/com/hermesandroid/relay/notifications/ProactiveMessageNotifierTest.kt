package com.hermesandroid.relay.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class ProactiveMessageNotifierTest {
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
    fun `notification identity is stable per wire message id`() {
        assertEquals(
            ProactiveMessageNotifier.notificationIdFor("message-1", "reminders"),
            ProactiveMessageNotifier.notificationIdFor("message-1", "updates"),
        )
        assertNotEquals(
            ProactiveMessageNotifier.notificationIdFor("message-1", "reminders"),
            ProactiveMessageNotifier.notificationIdFor("message-2", "reminders"),
        )
    }

    @Test
    fun `blank message ids keep independent thread slots`() {
        assertNotEquals(
            ProactiveMessageNotifier.notificationIdFor(null, "reminders"),
            ProactiveMessageNotifier.notificationIdFor(null, "updates"),
        )
    }

    @Test
    fun `cancel removes only the persisted notification slot`() {
        ProactiveMessageNotifier.notify(context, "Hermes", "first", "message-1", "reminders")
        ProactiveMessageNotifier.notify(context, "Hermes", "second", "message-2", "updates")

        ProactiveMessageNotifier.cancel(
            context,
            ProactiveMessageNotifier.notificationIdFor("message-1", "reminders"),
        )

        assertEquals(
            listOf(ProactiveMessageNotifier.notificationIdFor("message-2", "updates")),
            manager.activeNotifications.map { it.id },
        )
    }
}
