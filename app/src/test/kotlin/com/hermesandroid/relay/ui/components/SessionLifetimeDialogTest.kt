package com.hermesandroid.relay.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.hermesandroid.relay.ui.theme.HermesRelayTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-xhdpi")
class SessionLifetimeDialogTest {
    @get:Rule val compose = createComposeRule()
    private val server = okhttp3.mockwebserver.MockWebServer()
    @org.junit.Before fun startServer() { server.start() }
    @org.junit.After fun stopServer() { server.shutdown() }
    private fun client() = com.hermesandroid.relay.network.relay.RelayHttpClient(
        okhttp3.OkHttpClient(), { "ws://${server.hostName}:${server.port}" }, { "current-session" },
    )

    @Test fun expiredOrShortSessionsOfferNoPolicyExpansion() {
        assertTrue(shorterSessionOptions(1000.0, 1001).isEmpty())
        assertTrue(shorterSessionOptions(1001.0 + 86400, 1001).isEmpty())
        assertEquals(listOf(86400L, 604800L), shorterSessionOptions(1001.0 + 28 * 86400, 1001).map { it.seconds })
        assertFalse(shorterSessionOptions(null, 1001).any { it.seconds == 0L })
    }

    @Test fun noShorterPresetDisablesSubmitAndPairAgainMakesNoPolicyRequest() {
        var renewed = false
        compose.setContent { HermesRelayTheme {
            SessionLifetimeDialog(System.currentTimeMillis() / 1000.0 + 3600,
                { error("No policy update is allowed without a shorter preset") }, {}, {}, { renewed = true })
        } }
        compose.onNodeWithText("No shorter preset remains.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Shorten").assertIsNotEnabled()
        compose.onNodeWithText("Pair again").performClick()
        compose.runOnIdle { assertTrue(renewed); assertEquals(0, server.requestCount) }
    }

    @Test fun forbiddenResponseKeepsDialogOpenAndOffersRepairWithoutAnotherPatch() {
        var calls = 0
        var renewed = false
        var succeeded = false
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(403).setBody("operator approval required to extend session"))
        val http = client()
        compose.setContent { HermesRelayTheme {
            SessionLifetimeDialog(System.currentTimeMillis() / 1000.0 + 28 * 86400,
                { calls++; http.extendSession("current", it) },
                { succeeded = true }, {}, { renewed = true })
        } }
        compose.onNodeWithText("30 days").assertDoesNotExist()
        compose.onNodeWithText("1 day").assertIsSelected()
        compose.onNodeWithText("Shorten").performClick()
        compose.waitUntil(10000) {
            compose.onAllNodesWithText("Could not shorten the session.", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("{\"ttl_seconds\":86400}", server.takeRequest().body.readUtf8())
        compose.onNodeWithText("Could not shorten the session.", substring = true).assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("build/ui-evidence/session-lifetime-forbidden.png")
        compose.onNodeWithText("Pair again").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, calls); assertTrue(renewed); assertFalse(succeeded) }
    }

    @Test fun successClosesOnlyAfterUpdateCompletes() {
        var open by mutableStateOf(true)
        var submitted = 0L
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
        val http = client()
        compose.setContent { HermesRelayTheme { if (open) {
            SessionLifetimeDialog(null, { submitted = it; http.extendSession("current", it) }, { open = false }, {}, {})
        } } }
        compose.onNodeWithText("7 days").performScrollTo().performClick()
        compose.onNodeWithText("Shorten").performClick()
        compose.waitUntil(10000) { !open }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertEquals(604800L, submitted) }
    }

    @Test fun pendingUpdateDisablesDuplicateSubmissionAndCancel() {
        val completion = kotlinx.coroutines.CompletableDeferred<Result<Unit>>()
        var calls = 0
        compose.setContent { HermesRelayTheme {
            SessionLifetimeDialog(null, { calls++; completion.await() }, {}, {}, {})
        } }
        compose.onNodeWithText("Shorten").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Shorten").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.onNodeWithText("Updating session…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, calls); completion.complete(Result.failure(java.io.IOException("HTTP 403"))) }
        compose.waitForIdle()
        compose.onNodeWithText("Shorten").assertIsEnabled()
        compose.onNodeWithText("Could not shorten the session.", substring = true).assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w780dp-h360dp-xhdpi")
    fun landscapeLargeTextPreservesActionsAndScrollsOptions() {
        compose.setContent { HermesRelayTheme(fontScale = 2f) {
            SessionTtlPickerDialog(2592000, true, "wss", {}, {})
        } }
        compose.onNodeWithText("30 days").performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithText("Never expire").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("Pair").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("build/ui-evidence/pairing-landscape-large-text.png")
    }
}
