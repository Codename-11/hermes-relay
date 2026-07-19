package com.hermesandroid.relay.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Local device-review helper. Never runs in or ships with the application APK. */
@RunWith(AndroidJUnit4::class)
class ConnectionReviewSeedTest {

    @Test
    fun seedOfflineSecondaryConnection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ConnectionStore(context)
        store.isHydrated.first { it }
        if (store.connections.value.none { it.id == REVIEW_ID }) {
            store.addConnection(
                Connection(
                    id = REVIEW_ID,
                    label = "Lab NAS",
                    apiServerUrl = "",
                    relayUrl = "",
                    tokenStoreKey = Connection.buildTokenStoreKey(REVIEW_ID),
                    dashboardUrl = "http://192.0.2.10:9119",
                    lastUsedAt = System.currentTimeMillis() - 2L * 24L * 60L * 60L * 1_000L,
                ),
            )
        }
    }

    @Test
    fun removeOfflineSecondaryConnection() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ConnectionStore(context)
        store.isHydrated.first { it }
        if (store.connections.value.any { it.id == REVIEW_ID }) {
            store.removeConnection(REVIEW_ID)
        }
    }

    private companion object {
        const val REVIEW_ID = "00000000-0000-4000-8000-000000000220"
    }
}
