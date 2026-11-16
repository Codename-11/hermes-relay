package com.hermesandroid.relay.util

import com.hermesandroid.relay.data.ApiEndpoint
import com.hermesandroid.relay.data.Connection
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewChatLaunchRequestTest {
    private val neo = Profile(name = "neo", model = "m")
    private val quant = Profile(name = "quant", model = "m")
    private val default = Profile(name = "default", model = "m", isDefault = true)
    private val roster = listOf(default, neo, quant)

    private val home = NewChatConnectionCandidate(
        id = "home",
        urls = listOf("https://hermes.example.com", "wss://hermes.example.com/relay"),
    )
    private val lab = NewChatConnectionCandidate(
        id = "lab",
        urls = listOf("http://100.64.0.7:8642", "ws://100.64.0.7:8767"),
    )

    private fun request(gateway: String? = null, profile: String? = null, malformed: Boolean = false) =
        NewChatLaunchRequest(id = 1, gatewayUrl = gateway, profile = profile, malformed = malformed)

    private fun step(
        request: NewChatLaunchRequest,
        hydrated: Boolean = true,
        supervised: Boolean = false,
        connections: List<NewChatConnectionCandidate> = listOf(home, lab),
        active: String? = "home",
        settled: Boolean = true,
        roster: List<Profile> = this.roster,
        expired: Boolean = false,
        allowed: (String?) -> Boolean = { true },
    ) = newChatLaunchStep(
        request, hydrated, supervised, connections, active, settled, roster, expired, allowed,
    )

    // --- Intent parsing -------------------------------------------------------------

    @Test fun otherActionsAreIgnored() {
        assertNull(parseNewChatLaunch("android.intent.action.SEND", "https://x", "neo"))
        assertNull(parseNewChatLaunch(null, null, null))
    }

    @Test fun extrasAreOptionalAndTrimmed() {
        val bare = parseNewChatLaunch(NewChatLaunch.ACTION_NEW_CHAT, null, null)!!
        assertEquals(ParsedNewChatLaunch(null, null, false), bare)
        val both = parseNewChatLaunch(NewChatLaunch.ACTION_NEW_CHAT, " https://hermes.example.com ", " neo ")!!
        assertEquals(ParsedNewChatLaunch("https://hermes.example.com", "neo", false), both)
    }

    @Test fun presentButUnusableExtrasFailClosedInsteadOfMeaningCurrentAgent() {
        assertTrue(parseNewChatLaunch(NewChatLaunch.ACTION_NEW_CHAT, null, "")!!.malformed)
        assertTrue(parseNewChatLaunch(NewChatLaunch.ACTION_NEW_CHAT, "   ", null)!!.malformed)
        assertTrue(parseNewChatLaunch(NewChatLaunch.ACTION_NEW_CHAT, null, 42)!!.malformed)
        assertTrue(parseNewChatLaunch(NewChatLaunch.ACTION_NEW_CHAT, "x".repeat(5000), null)!!.malformed)
    }

    @Test fun requestsGetIncreasingIdsAndNewerReplacesOlder() {
        val first = ParsedNewChatLaunch("https://a", "neo", false)
        assertTrue(NewChatLaunchRequests.tryRequest(first, nowMs = 10))
        val a = NewChatLaunchRequests.pending.value!!
        assertTrue(NewChatLaunchRequests.tryRequest(ParsedNewChatLaunch(null, "quant", false), nowMs = 20))
        val b = NewChatLaunchRequests.pending.value!!
        assertTrue(b.id > a.id)
        NewChatLaunchRequests.consume(a.id) // stale consume must not drop the newer request
        assertEquals(b, NewChatLaunchRequests.pending.value)
        NewChatLaunchRequests.consume(b.id)
        assertNull(NewChatLaunchRequests.pending.value)
        assertFalse(NewChatLaunchRequests.tryRequest(null))
    }

    @Test fun freshnessWindowIsBounded() {
        assertTrue(isNewChatLaunchFresh(1_000, 1_000, 500))
        assertTrue(isNewChatLaunchFresh(1_000, 1_500, 500))
        assertFalse(isNewChatLaunchFresh(1_000, 1_501, 500))
        assertFalse(isNewChatLaunchFresh(2_000, 1_000, 500))
    }

    // --- Gateway matching -----------------------------------------------------------

    @Test fun gatewayMatchesApiOrRelayUrlIgnoringCaseSchemeFlavourAndPath() {
        for (url in listOf(
            "https://hermes.example.com",
            "HTTPS://Hermes.Example.com/",
            "https://hermes.example.com/some/path?x=1",
            "wss://hermes.example.com",
            "hermes.example.com",
            "hermes.example.com:443",
        )) {
            assertEquals(url, NewChatConnectionResolution.Use("home"), resolveNewChatConnection(url, listOf(home, lab)))
        }
        assertEquals(
            NewChatConnectionResolution.Use("lab"),
            resolveNewChatConnection("http://100.64.0.7:8642", listOf(home, lab)),
        )
        assertEquals(
            NewChatConnectionResolution.Use("lab"),
            resolveNewChatConnection("100.64.0.7:8767", listOf(home, lab)),
        )
    }

    @Test fun gatewayNeverMatchesADifferentHostPortOrScheme() {
        val all = listOf(home, lab)
        for (url in listOf(
            "https://hermes.example.org",
            "https://evil.hermes.example.com",
            "http://hermes.example.com", // downgrade: scheme given and different
            "https://hermes.example.com:8443",
            "http://100.64.0.7:9999",
            "ftp://hermes.example.com",
            "not a url ::",
        )) {
            assertEquals(url, NewChatConnectionResolution.NotFound, resolveNewChatConnection(url, all))
        }
        assertEquals(NewChatConnectionResolution.NotFound, resolveNewChatConnection("https://hermes.example.com", emptyList()))
    }

    @Test fun absentGatewayUsesActiveAndSharedHostIsAmbiguous() {
        assertEquals(NewChatConnectionResolution.UseActive, resolveNewChatConnection(null, listOf(home)))
        val twin = home.copy(id = "twin")
        assertEquals(
            NewChatConnectionResolution.Ambiguous,
            resolveNewChatConnection("https://hermes.example.com", listOf(home, twin)),
        )
    }

    @Test fun connectionCandidateCollectsEveryRouteUrl() {
        val connection = Connection(
            id = "c1",
            label = "Home",
            apiServerUrl = "http://10.0.0.5:8642",
            relayUrl = "ws://10.0.0.5:8767",
            tokenStoreKey = "k",
            dashboardUrl = "https://dash.example.com",
            routeCandidates = listOf(EndpointCandidate(role = "public", api = ApiEndpoint("hermes.example.com", 443, tls = true))),
        )
        val candidate = connection.newChatCandidate()
        assertEquals("c1", candidate.id)
        assertEquals(
            NewChatConnectionResolution.Use("c1"),
            resolveNewChatConnection("https://hermes.example.com", listOf(candidate)),
        )
        assertEquals(
            NewChatConnectionResolution.Use("c1"),
            resolveNewChatConnection("https://dash.example.com", listOf(candidate)),
        )
    }

    // --- Profile resolution ---------------------------------------------------------

    @Test fun profileMatchesByNameCaseInsensitively() {
        assertEquals(NewChatProfileResolution.Unspecified, resolveNewChatProfile(null, roster))
        assertEquals(NewChatProfileResolution.Found(neo), resolveNewChatProfile("Neo", roster))
        assertEquals(NewChatProfileResolution.Missing, resolveNewChatProfile("forge", roster))
        assertEquals(NewChatProfileResolution.Missing, resolveNewChatProfile("neo", emptyList()))
        assertEquals(
            NewChatProfileResolution.Missing,
            resolveNewChatProfile("neo", listOf(neo, neo.copy(name = "NEO"))),
        )
    }

    @Test fun defaultMeansTheDefaultProfileNotTheCurrentPicker() {
        assertEquals(NewChatProfileResolution.Found(default), resolveNewChatProfile("default", roster))
        val flagged = Profile(name = "main", model = "m", isDefault = true)
        assertEquals(NewChatProfileResolution.Found(flagged), resolveNewChatProfile("default", listOf(flagged, neo)))
        assertEquals(NewChatProfileResolution.Missing, resolveNewChatProfile("default", listOf(neo, quant)))
        assertEquals(NewChatProfileResolution.ServerDefault, resolveNewChatProfile("default", emptyList()))
    }

    // --- Decision flow --------------------------------------------------------------

    @Test fun waitsForHydrationBeforeAnythingElse() {
        assertEquals(NewChatLaunchStep.Wait, step(request("https://nope.example"), hydrated = false))
        assertEquals(NewChatLaunchStep.Wait, step(request(profile = "neo"), settled = false))
    }

    @Test fun unspecifiedGatewayAndProfileOpensOnTheCurrentAgent() {
        assertEquals(NewChatLaunchStep.Open(NewChatProfileResolution.Unspecified), step(request()))
    }

    @Test fun switchesConnectionBeforeSelectingProfile() {
        val launch = request("http://100.64.0.7:8642", "neo")
        assertEquals(NewChatLaunchStep.SwitchConnection("lab"), step(launch, active = "home"))
        // Once switched, the profile roster must settle before anything opens.
        assertEquals(NewChatLaunchStep.Wait, step(launch, active = "lab", settled = false))
        assertEquals(
            NewChatLaunchStep.Open(NewChatProfileResolution.Found(neo)),
            step(launch, active = "lab"),
        )
    }

    @Test fun profileOnAlreadyActiveGatewayOpensDirectly() {
        assertEquals(
            NewChatLaunchStep.Open(NewChatProfileResolution.Found(quant)),
            step(request("https://hermes.example.com", "quant")),
        )
        assertEquals(
            NewChatLaunchStep.Open(NewChatProfileResolution.Found(default)),
            step(request(profile = "default")),
        )
    }

    @Test fun unknownGatewayRejectsAndNeverFallsBackToTheActiveOne() {
        assertEquals(
            NewChatLaunchStep.Reject(NewChatRejection.GatewayNotFound),
            step(request("https://missing.example", "neo")),
        )
        assertEquals(
            NewChatLaunchStep.Reject(NewChatRejection.GatewayAmbiguous),
            step(request("https://hermes.example.com"), connections = listOf(home, home.copy(id = "twin"))),
        )
        assertEquals(
            NewChatLaunchStep.Reject(NewChatRejection.GatewayNotFound),
            step(request(), active = null, connections = emptyList()),
        )
    }

    @Test fun unknownProfileWaitsForRosterThenRejectsAndNeverUsesCurrentProfile() {
        val launch = request(profile = "forge")
        assertEquals(NewChatLaunchStep.Wait, step(launch))
        assertEquals(
            NewChatLaunchStep.Reject(NewChatRejection.ProfileNotFound),
            step(launch, expired = true),
        )
        // A roster that has not loaded yet is indistinguishable from a missing profile until expiry.
        assertEquals(NewChatLaunchStep.Wait, step(request(profile = "neo"), roster = emptyList()))
    }

    @Test fun defaultProfileOnRosterlessGatewayFallsBackToServerDefaultOnlyAfterTheWait() {
        val launch = request(profile = "default")
        assertEquals(NewChatLaunchStep.Wait, step(launch, roster = emptyList()))
        assertEquals(
            NewChatLaunchStep.Open(NewChatProfileResolution.ServerDefault),
            step(launch, roster = emptyList(), expired = true),
        )
    }

    @Test fun supervisedModeAndMalformedRequestsAreDeniedBeforeResolution() {
        assertEquals(
            NewChatLaunchStep.Reject(NewChatRejection.Supervised),
            step(request("https://hermes.example.com", "neo"), supervised = true),
        )
        assertEquals(
            NewChatLaunchStep.Reject(NewChatRejection.Malformed),
            step(request(malformed = true)),
        )
        // Hydration still gates the policy read, so a cold start cannot bypass it.
        assertEquals(NewChatLaunchStep.Wait, step(request(), hydrated = false, supervised = true))
    }

    @Test fun profileLockRejectsOtherProfilesButAllowsTheLockedOne() {
        val lockedToQuant: (String?) -> Boolean = { it == "quant" }
        assertEquals(
            NewChatLaunchStep.Reject(NewChatRejection.ProfileLocked),
            step(request(profile = "neo"), allowed = lockedToQuant),
        )
        assertEquals(
            NewChatLaunchStep.Open(NewChatProfileResolution.Found(quant)),
            step(request(profile = "quant"), allowed = lockedToQuant),
        )
    }
}
