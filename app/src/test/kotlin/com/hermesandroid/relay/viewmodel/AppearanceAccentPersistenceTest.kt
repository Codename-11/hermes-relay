package com.hermesandroid.relay.viewmodel

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.data.AppearancePreferences
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
        app.relayDataStore.edit {
            it[AppearancePreferences.accentKey] = "#D84D91"
        }

        assertEquals("#D84D91", app.relayDataStore.data.first()[AppearancePreferences.accentKey])
    }

    @Test
    fun `shape preference round trips through local DataStore`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.relayDataStore.edit { it[AppearancePreferences.shapeKey] = "balanced" }

        assertEquals("balanced", AppearancePreferences.shape(app).first())
    }

    @Test
    fun `complete appearance state restores external compose windows`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.relayDataStore.edit {
            it[AppearancePreferences.themeKey] = "light"
            it[AppearancePreferences.appThemeKey] = "hermes-relay"
            it[AppearancePreferences.accentKey] = "#d84d91"
            it[AppearancePreferences.shapeKey] = "sharp"
            it[AppearancePreferences.appFontKey] = "nunito"
            it[AppearancePreferences.fontScaleKey] = 1.3f
        }

        val restored = AppearancePreferences.state(app).first()
        assertEquals("light", restored.themePreference)
        assertEquals("hermes-relay", restored.appThemeId)
        assertEquals("#D84D91", restored.accentHex)
        assertEquals("sharp", restored.shapeId)
        assertEquals("nunito", restored.appFontId)
        assertEquals(1.3f, restored.fontScale)
    }
}
