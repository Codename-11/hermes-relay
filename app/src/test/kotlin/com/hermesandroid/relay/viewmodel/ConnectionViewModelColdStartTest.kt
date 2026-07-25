package com.hermesandroid.relay.viewmodel

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.data.relayDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ConnectionViewModelColdStartTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        runBlocking {
            application.relayDataStore.edit { it.clear() }
        }
    }

    @Test
    fun `API fallback stays absent before persisted connection hydration`() {
        val viewModel = ConnectionViewModel(application)

        assertEquals("", viewModel.apiServerUrl.value)
        assertEquals("", viewModel.effectiveApiServerUrl.value)
        assertNull(viewModel.apiClient.value)
    }
}
