package com.hermesandroid.relay.viewmodel

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.data.relayDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppearanceAccentPersistenceTest {
    @Test
    fun `accent preference round trips through local DataStore`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val key = stringPreferencesKey("appearance_accent")
        app.relayDataStore.edit {
            it[key] = "#D84D91"
        }

        assertEquals("#D84D91", app.relayDataStore.data.first()[key])
    }

    @Test
    fun `shape preference round trips through local DataStore`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val key = stringPreferencesKey("appearance_shape")
        app.relayDataStore.edit { it[key] = "balanced" }

        assertEquals("balanced", app.relayDataStore.data.first()[key])
    }
}
