package com.hermesandroid.relay.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PluginsViewModelLifecycleTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var application: Application
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        application = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun `explicit opt out overrides a default enabled manifest after refresh and recreation`() = runBlocking {
        val pluginId = uniquePluginId()
        val connectionId = "connection-${UUID.randomUUID()}"
        val profileName = "research"
        val viewModel = PluginsViewModel(application)

        enqueueDiscovery(pluginId, defaultEnabled = true)
        configure(viewModel, connectionId, profileName)

        val defaulted = awaitPlugin(viewModel)
        assertTrue(defaulted.preferences.enabled)
        assertFalse(defaulted.preferences.configured)

        viewModel.setEnabled(pluginId, false)
        val optedOut = awaitPlugin(viewModel) {
            it.preferences.configured && !it.preferences.enabled
        }
        assertFalse(optedOut.preferences.enabled)
        assertTrue(optedOut.preferences.configured)

        enqueueDiscovery(pluginId, defaultEnabled = true)
        viewModel.refresh()
        val refreshed = awaitPlugin(viewModel) {
            it.preferences.configured && !it.preferences.enabled
        }
        assertFalse(refreshed.preferences.enabled)
        assertTrue(refreshed.preferences.configured)

        val recreated = PluginsViewModel(application)
        enqueueDiscovery(pluginId, defaultEnabled = true)
        configure(recreated, connectionId, profileName)
        val restored = awaitPlugin(recreated)
        assertFalse(restored.preferences.enabled)
        assertTrue(restored.preferences.configured)

        viewModel.configure(null, null, null, ::dashboardClient)
        recreated.configure(null, null, null, ::dashboardClient)
    }

    @Test
    fun `explicit opt out is isolated by profile and connection`() = runBlocking {
        val pluginId = uniquePluginId()
        val firstConnection = "connection-${UUID.randomUUID()}"
        val secondConnection = "connection-${UUID.randomUUID()}"
        val viewModel = PluginsViewModel(application)

        enqueueDiscovery(pluginId, defaultEnabled = true)
        configure(viewModel, firstConnection, "research")
        awaitPlugin(viewModel)
        viewModel.setEnabled(pluginId, false)
        awaitPlugin(viewModel) { it.preferences.configured && !it.preferences.enabled }

        enqueueDiscovery(pluginId, defaultEnabled = true)
        configure(viewModel, firstConnection, "writing")
        val otherProfile = awaitPlugin(viewModel)
        assertTrue(otherProfile.preferences.enabled)
        assertFalse(otherProfile.preferences.configured)

        enqueueDiscovery(pluginId, defaultEnabled = true)
        configure(viewModel, secondConnection, "research")
        val otherConnection = awaitPlugin(viewModel)
        assertTrue(otherConnection.preferences.enabled)
        assertFalse(otherConnection.preferences.configured)

        enqueueDiscovery(pluginId, defaultEnabled = true)
        configure(viewModel, firstConnection, "research")
        val originalScope = awaitPlugin(viewModel)
        assertFalse(originalScope.preferences.enabled)
        assertTrue(originalScope.preferences.configured)

        viewModel.configure(null, null, null, ::dashboardClient)
    }

    private fun configure(
        viewModel: PluginsViewModel,
        connectionId: String,
        profileName: String,
    ) {
        viewModel.configure(
            connectionId = connectionId,
            dashboardUrl = server.url("/").toString(),
            profileName = profileName,
            dashboardFactory = ::dashboardClient,
        )
    }

    private fun dashboardClient(url: String): DashboardApiClient = DashboardApiClient(url)

    private suspend fun awaitPlugin(
        viewModel: PluginsViewModel,
        predicate: (PluginHubItem) -> Boolean = { true },
    ): PluginHubItem = withTimeout(5_000) {
        viewModel.hubState
            .filterIsInstance<PluginsHubState.Ready>()
            .first { state -> state.plugins.singleOrNull()?.let(predicate) == true }
            .plugins
            .single()
    }

    private fun enqueueDiscovery(pluginId: String, defaultEnabled: Boolean) {
        server.enqueue(
            jsonResponse(
                """
                [{"name":"$pluginId","label":"Lifecycle plugin","has_api":true}]
                """.trimIndent(),
            ),
        )
        server.enqueue(
            jsonResponse(
                """
                {
                  "schema_version": 1,
                  "id": "$pluginId",
                  "display_name": "Lifecycle plugin",
                  "default_enabled": $defaultEnabled,
                  "contributions": []
                }
                """.trimIndent(),
            ),
        )
    }

    private fun uniquePluginId(): String = "lifecycle-${UUID.randomUUID()}"

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
