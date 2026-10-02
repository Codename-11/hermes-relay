package com.hermesandroid.relay.viewmodel

import android.Manifest
import android.app.Application
import android.os.Looper
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.data.relayDataStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionViewModelChatAlertsTest {
    private lateinit var app: Application
    private val store = ViewModelStore()
    private val key = booleanPreferencesKey("notify_turn_complete")

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        runBlocking { app.relayDataStore.edit { it.remove(key) } }
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After fun tearDown() {
        store.clear()
        runBlocking { app.relayDataStore.edit { it.remove(key) } }
    }

    @Test fun permissionGrantedAfterStartupRefreshesAbsentPreferenceOnResume() {
        val vm = ConnectionViewModel(app).also { store.put("connection", it) }
        assertFalse(vm.notifyTurnComplete.value)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        vm.revalidateOnResume(0)
        await { vm.notifyTurnComplete.value }
        assertTrue(vm.notifyTurnComplete.value)
    }

    @Test fun explicitOffSurvivesPermissionGrantAndResume() {
        runBlocking { app.relayDataStore.edit { it[key] = false } }
        val vm = ConnectionViewModel(app).also { store.put("connection", it) }
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        vm.revalidateOnResume(0)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(vm.notifyTurnComplete.value)
    }

    @Test fun revokedPermissionRefreshesAbsentPreferenceOnResume() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val vm = ConnectionViewModel(app).also { store.put("connection", it) }
        assertTrue(vm.notifyTurnComplete.value)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        vm.revalidateOnResume(0)
        await { !vm.notifyTurnComplete.value }
        assertFalse(vm.notifyTurnComplete.value)
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!predicate() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Notification setting did not refresh", predicate())
    }
}
