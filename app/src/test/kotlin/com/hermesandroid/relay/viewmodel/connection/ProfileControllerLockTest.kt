package com.hermesandroid.relay.viewmodel.connection

import android.content.Context
import com.hermesandroid.relay.auth.AuthManager
import com.hermesandroid.relay.data.AgentDisplay
import com.hermesandroid.relay.data.Profile
import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.DashboardProfileScope
import com.hermesandroid.relay.network.upstream.GatewayAvailability
import com.hermesandroid.relay.network.upstream.models.SessionItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Profile-**lock** behavior of [ProfileController].
 *
 * The controller's five persistence stores ([ProfileSelectionStore],
 * [ProfileSessionStore], [ProfileDisplayAliasStore], [ProfileLockStore],
 * [ProfileIconStore]) are built from a [Context], so this runs under
 * Robolectric (same seam as `ConnectionManagerRouteTest`) with
 * [RuntimeEnvironment.getApplication]. All other collaborators are injected as
 * lambdas/flows and are either no-ops or capturing stubs.
 *
 * Timing note: [ProfileController.lockedProfileName] is a `stateIn(...Eagerly)`
 * projection of the `ProfileLockStore` DataStore flow, so it lags a write by an
 * async hop. The controller's own [scope] therefore uses a REAL dispatcher
 * (Dispatchers.IO) — a StandardTestDispatcher would never let the DataStore
 * actor or the stateIn collectors run — and the tests `await { ... }` the lock /
 * selection StateFlows rather than reading them synchronously after a write.
 *
 * Scope of coverage: the lock semantics described on [ProfileController]:
 *  - [ProfileController.lockProfile] persists the lock token + force-selects.
 *  - [ProfileController.lockProfile] (null) locks to Server default.
 *  - [ProfileController.selectProfile] is a no-op for a non-locked target while
 *    locked, but allowed for the locked target.
 *  - [ProfileController.resolvePendingProfileFrom] under a lock resolves to the
 *    locked target when present and HOLDS (selection null) when it is absent,
 *    then recovers on a later list arrival.
 *  - [ProfileController.unlockProfile] clears the lock and re-enables selection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileControllerLockTest {

    private val connectionId = "conn-lock-test"

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var authManager: AuthManager
    private lateinit var authManagerFlow: MutableStateFlow<AuthManager>
    private lateinit var activeConnectionId: MutableStateFlow<String?>
    private lateinit var controller: ProfileController
    private lateinit var dashboardClient: DashboardApiClient
    private var dashboardUrl: String? = null

    private val lastSessionIds = mutableListOf<String?>()

    private val mizu = Profile(name = "mizu", model = "model-a")
    private val coder = Profile(name = "coder", model = "model-b")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Relay-advertised profile list — empty; tests drive the list explicitly
        // through resolvePendingProfileFrom(list).
        authManager = mockk(relaxed = true)
        every { authManager.agentProfiles } returns MutableStateFlow<List<Profile>>(emptyList()).asStateFlow()
        authManagerFlow = MutableStateFlow(authManager)

        activeConnectionId = MutableStateFlow<String?>(connectionId)
        dashboardClient = mockk(relaxed = true)
        dashboardUrl = null

        controller = ProfileController(
            context = context,
            scope = scope,
            authManagerFlow = authManagerFlow,
            activeConnectionId = activeConnectionId,
            activeDashboardUrlProvider = { dashboardUrl },
            dashboardClientFactory = { _, _ -> dashboardClient },
            // Non-"auto" so activeSessionTransport() resolves deterministically
            // (no gateway probe gating) — keeps refreshLastSessionForProfile from
            // bailing early on Unknown.
            streamingEndpointProvider = { "completions" },
            gatewayAvailabilityProvider = { GatewayAvailability.Ready },
            setLastSessionId = { lastSessionIds += it },
            legacyDefaultSessionId = { null },
            rebuildChatApiClient = { },
        )

        // Guarantee a clean lock slot — the underlying "profile_selections"
        // DataStore is name-scoped and could carry residual state across runs in
        // the same JVM.
        runBlocking { controller.profileLockStore.clear(connectionId) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        // Start each run from clean lock state — the DataStore file is shared by
        // the app-internal store name across tests in the same JVM.
        runBlocking { controller.profileLockStore.clear(connectionId) }
    }

    // --- await helpers ------------------------------------------------------

    private fun <T> awaitFlow(flow: StateFlow<T>, predicate: (T) -> Boolean): T =
        runBlocking {
            withTimeout(5_000) { flow.first { predicate(it) } }
        }

    private fun awaitLocked(token: String?) =
        awaitFlow(controller.lockedProfileName) { it == token }

    private fun awaitSelected(name: String?) =
        awaitFlow(controller.selectedProfile) { it?.name == name }

    // --- lockProfile(profile) -----------------------------------------------

    @Test
    fun lockProfile_persistsTokenAndForceSelects() {
        runBlocking { controller.lockProfile(mizu) }

        // Lock token == the profile name; selection forced to the locked target.
        assertEquals("mizu", awaitLocked("mizu"))
        assertEquals(mizu, awaitSelected("mizu"))
        assertTrue("should report locked", awaitFlow(controller.isProfileLocked) { it })
    }

    @Test
    fun lockProfileNull_locksToServerDefault() {
        runBlocking { controller.lockProfile(null) }

        // Server default is stored as the sentinel (NOT null = unlocked) and the
        // selection resolves to null (the server-default context).
        assertEquals(
            AgentDisplay.SERVER_DEFAULT_PROFILE_KEY,
            awaitLocked(AgentDisplay.SERVER_DEFAULT_PROFILE_KEY),
        )
        assertNull(awaitSelected(null))
        assertTrue(awaitFlow(controller.isProfileLocked) { it })
    }

    @Test
    fun serverDefault_resolvesStickyActiveProfileForSessionReads() {
        dashboardUrl = "https://dashboard.example"
        coEvery { dashboardClient.getActiveProfileScope() } returns Result.success(
            DashboardProfileScope(active = "victor", current = "default"),
        )
        coEvery { dashboardClient.listProfiles() } returns Result.success(emptyList())
        coEvery { dashboardClient.listSessions(profile = "victor", limit = 200) } returns
            Result.success(emptyList<SessionItem>())
        coEvery { dashboardClient.deleteSession("session-1", profile = "victor") } returns
            Result.success(buildJsonObject { })
        coEvery { dashboardClient.renameSession("session-1", "Renamed", profile = "victor") } returns
            Result.success(buildJsonObject { })
        controller.setPendingConnectionId(connectionId)
        controller.setPendingName(null)

        controller.refreshDashboardProfiles()

        assertEquals("victor", awaitFlow(controller.effectiveSessionProfileName) { it == "victor" })
        assertEquals("default", controller.serverDefaultProfileScope.value?.current)
        assertTrue(awaitFlow(controller.selectionSettled) { it })
        runBlocking { controller.listProfileScopedSessions()?.getOrThrow() }
        assertTrue(runBlocking { controller.deleteProfileScopedSession("session-1") })
        assertTrue(runBlocking { controller.renameProfileScopedSession("session-1", "Renamed") })
        coVerify(exactly = 1) { dashboardClient.listSessions(profile = "victor", limit = 200) }
        coVerify(exactly = 1) { dashboardClient.deleteSession("session-1", profile = "victor") }
        coVerify(exactly = 1) {
            dashboardClient.renameSession("session-1", "Renamed", profile = "victor")
        }
    }

    // --- selectProfile gating while locked ----------------------------------

    @Test
    fun selectProfile_otherTarget_isNoOpWhileLocked() {
        runBlocking { controller.lockProfile(mizu) }
        awaitLocked("mizu")
        awaitSelected("mizu")

        // Attempt to switch to a DIFFERENT profile — must be refused.
        controller.selectProfile(coder)

        // Selection stays on the locked target.
        assertEquals(mizu, controller.selectedProfile.value)
    }

    @Test
    fun selectProfile_lockedTarget_isAllowedWhileLocked() {
        runBlocking { controller.lockProfile(mizu) }
        awaitLocked("mizu")
        awaitSelected("mizu")

        // Re-selecting the locked target is permitted (no-op against state, but
        // must not be refused outright).
        controller.selectProfile(mizu)
        assertEquals(mizu, controller.selectedProfile.value)
    }

    // --- resolvePendingProfileFrom under a lock -----------------------------

    @Test
    fun resolvePending_locked_resolvesToLockedTargetWhenPresent() {
        runBlocking { controller.lockProfile(mizu) }
        awaitLocked("mizu")
        awaitSelected("mizu")

        // The locked profile object refreshes from the advertised list.
        val refreshedMizu = mizu.copy(model = "model-a-v2")
        val changed = controller.resolvePendingProfileFrom(listOf(refreshedMizu, coder))

        assertTrue("resolution should report a change (model differs)", changed)
        assertEquals(refreshedMizu, controller.selectedProfile.value)
    }

    @Test
    fun resolvePending_locked_holdsWhenLockedProfileAbsent_thenRecovers() {
        runBlocking { controller.lockProfile(mizu) }
        awaitLocked("mizu")
        awaitSelected("mizu")

        // Locked profile NOT in the advertised list → HOLD: selection cleared to
        // null, return true (changed from the previously-selected mizu).
        val held = controller.resolvePendingProfileFrom(listOf(coder))
        assertTrue("HOLD must report a change away from the locked target", held)
        assertNull("selection must hold on null while the locked profile is gone", controller.selectedProfile.value)

        // A later list arrival that DOES contain the locked profile recovers it —
        // proves the pending lock name was retained during the HOLD.
        val recovered = controller.resolvePendingProfileFrom(listOf(mizu, coder))
        assertTrue("recovery should report a change back to the locked target", recovered)
        assertEquals(mizu, controller.selectedProfile.value)
    }

    @Test
    fun resolvePending_lockedToServerDefault_resolvesToNull() {
        runBlocking { controller.lockProfile(null) }
        awaitLocked(AgentDisplay.SERVER_DEFAULT_PROFILE_KEY)
        awaitSelected(null)

        // The sentinel resolves to the null (server-default) selection regardless
        // of the advertised list.
        val changed = controller.resolvePendingProfileFrom(listOf(mizu, coder))
        // Selection was already null, so no change is reported; the contract we
        // care about is that it stays null and never coerces to a list entry.
        assertFalse("server-default selection was already null — no change", changed)
        assertNull(controller.selectedProfile.value)
    }

    // --- unlockProfile re-enables free selection ----------------------------

    @Test
    fun unlockProfile_clearsLock_andReenablesSelectProfile() {
        runBlocking { controller.lockProfile(mizu) }
        awaitLocked("mizu")
        awaitSelected("mizu")

        runBlocking { controller.unlockProfile() }
        // Lock cleared (back to unlocked / null) and isProfileLocked flips false.
        assertNull(awaitLocked(null))
        assertFalse(awaitFlow(controller.isProfileLocked) { !it })

        // selectProfile to a different target is now honored.
        controller.selectProfile(coder)
        assertEquals(coder, controller.selectedProfile.value)
    }
}
