package com.hermesandroid.relay.notifications

import android.content.Intent
import com.hermesandroid.relay.MainActivity
import com.hermesandroid.relay.util.NavRouteRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatNotificationIntentTest {
    @Test fun acceptedNotificationIntentIsConsumedOnceAcrossActivityRecreation() = runBlocking {
        val target = ChatNotificationTarget("connection", "profile", "session")
        val intent = Intent().putExtra(MainActivity.EXTRA_NAV_ROUTE, target.route())
        val consume = MainActivity::class.java.getDeclaredMethod("consumeNavRouteIntent", Intent::class.java)
            .apply { isAccessible = true }
        val first = Robolectric.buildActivity(MainActivity::class.java).get()
        consume.invoke(first, intent)
        assertFalse(intent.hasExtra(MainActivity.EXTRA_NAV_ROUTE))
        val recreated = Robolectric.buildActivity(MainActivity::class.java).get()
        consume.invoke(recreated, intent)
        assertEquals(target.route(), withTimeout(1_000) { NavRouteRequest.requests.first() })
        assertNull(withTimeoutOrNull(50) { NavRouteRequest.requests.first() })
    }
}
