package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.data.DashboardEndpoint
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.RelayEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionWizardDispatchPolicyTest {
    @Test
    fun pairSubmissionStagesLockActionsUntilHandoff() {
        assertFalse(PairSubmissionStage.Idle.inProgress)
        assertTrue(PairSubmissionStage.PreparingGateway.inProgress)
        assertTrue(PairSubmissionStage.OpeningSignIn.inProgress)
        assertTrue(PairSubmissionStage.PairingRelay.inProgress)
    }

    @Test
    fun setupOwnerRemainsStableWhenDraftBecomesActive() {
        val staged = resolveConnectionWizardOwner(null, "draft-id")
        val committed = resolveConnectionWizardOwner(staged, null)

        assertEquals("draft-id", staged)
        assertEquals(staged, committed)
        assertEquals("new-dashboard-connection", resolveConnectionWizardOwner(null, null))
    }

    @Test
    fun dashboardOnlyQrUsesDashboardOwner() {
        val payload = HermesPairingPayload(
            dashboardUrl = "https://agent.example.com/hermes",
        )

        assertEquals(SetupQrDispatch.Dashboard, setupQrDispatch(payload))
    }

    @Test
    fun apiQrKeepsStandardApiOwnerEvenWhenDashboardIsIncluded() {
        val payload = HermesPairingPayload(
            host = "api.example.com",
            tls = true,
            dashboardUrl = "https://agent.example.com/hermes",
        )

        assertEquals(SetupQrDispatch.StandardApi, setupQrDispatch(payload))
    }

    @Test
    fun relayQrKeepsRelayOwnerWithOrWithoutApi() {
        val relay = RelayPairing(
            url = "wss://relay.example.com:8767",
            code = "ABC123",
        )

        assertEquals(
            SetupQrDispatch.Relay,
            setupQrDispatch(HermesPairingPayload(host = "api.example.com", relay = relay)),
        )
        assertEquals(
            SetupQrDispatch.Relay,
            setupQrDispatch(
                HermesPairingPayload(
                    dashboardUrl = "https://agent.example.com/hermes",
                    relay = relay,
                ),
            ),
        )
    }

    @Test
    fun dashboardIngressRelaySignsInBeforePairing() {
        val payload = HermesPairingPayload(
            dashboardUrl = "https://agent.example.com/hermes",
            relay = RelayPairing(
                url = "wss://agent.example.com/hermes/api/plugins/hermes-relay/transport",
                code = "ABC123",
            ),
            endpoints = listOf(
                EndpointCandidate(
                    role = "public",
                    dashboard = DashboardEndpoint("https://agent.example.com/hermes"),
                    relay = RelayEndpoint(
                        "wss://agent.example.com/hermes/api/plugins/hermes-relay/transport",
                    ),
                ),
            ),
        )

        assertEquals(SetupQrDispatch.Relay, setupQrDispatch(payload))
        assertEquals(
            RelayPairStartOrder.DashboardSignInFirst,
            relayPairStartOrder(payload),
        )
    }

    @Test
    fun dashboardIngressUsesSelectedPublicCandidateRatherThanLegacyTopLevelRoute() {
        val grants = mapOf("terminal" to 3_600L)
        val endpoints = listOf(
            EndpointCandidate(
                role = "tailscale",
                priority = 0,
                dashboard = DashboardEndpoint("http://100.71.8.56:9119"),
                relay = RelayEndpoint(
                    "ws://100.71.8.56:9119/api/plugins/hermes-relay/transport",
                    transportHint = "ws",
                ),
            ),
            EndpointCandidate(
                role = "public",
                priority = 1,
                dashboard = DashboardEndpoint("https://hermes.example.com"),
                relay = RelayEndpoint(
                    "wss://hermes.example.com/api/plugins/hermes-relay/transport",
                    transportHint = "wss",
                ),
            ),
        )
        val payload = HermesPairingPayload(
            hermes = 3,
            dashboardUrl = "https://hermes.example.com/",
            relay = RelayPairing(
                url = "ws://100.71.8.56:8767",
                code = "ONE-TIME",
                ttlSeconds = 86_400L,
                grants = grants,
                transportHint = "ws",
            ),
            endpoints = endpoints,
        )

        val resolved = resolvedDashboardIngressPairingPayload(payload)

        assertEquals(RelayPairStartOrder.DashboardSignInFirst, relayPairStartOrder(payload))
        assertEquals(
            "wss://hermes.example.com/api/plugins/hermes-relay/transport",
            resolved?.relay?.url,
        )
        assertEquals("wss", resolved?.relay?.transportHint)
        assertEquals("ONE-TIME", resolved?.relay?.code)
        assertEquals(86_400L, resolved?.relay?.ttlSeconds)
        assertEquals(grants, resolved?.relay?.grants)
        assertEquals(endpoints, resolved?.endpoints)
    }

    @Test
    fun dashboardIngressFailsClosedWithoutExactDashboardCandidate() {
        val payload = HermesPairingPayload(
            dashboardUrl = "https://hermes.example.com",
            relay = RelayPairing(
                url = "ws://100.71.8.56:8767",
                code = "ONE-TIME",
            ),
            endpoints = listOf(
                EndpointCandidate(
                    role = "tailscale",
                    priority = 0,
                    dashboard = DashboardEndpoint("http://100.71.8.56:9119"),
                    relay = RelayEndpoint(
                        "ws://100.71.8.56:9119/api/plugins/hermes-relay/transport",
                    ),
                ),
            ),
        )

        assertEquals(null, resolvedDashboardIngressPairingPayload(payload))
        assertEquals(
            RelayPairStartOrder.InvalidDashboardIngress,
            relayPairStartOrder(payload),
        )
        assertFalse(shouldOfferDashboardSignInAfterRelayPair(payload))
    }

    @Test
    fun directRelayPairsBeforeDashboardSignIn() {
        val payload = HermesPairingPayload(
            dashboardUrl = "https://agent.example.com/hermes",
            relay = RelayPairing(
                url = "wss://agent.example.com:8767",
                code = "ABC123",
            ),
        )

        assertEquals(SetupQrDispatch.Relay, setupQrDispatch(payload))
        assertEquals(RelayPairStartOrder.PairFirst, relayPairStartOrder(payload))
        assertTrue(shouldOfferDashboardSignInAfterRelayPair(payload))
    }

    @Test
    fun relayOnlyPairDoesNotOpenDashboardSignIn() {
        val payload = HermesPairingPayload(
            relay = RelayPairing(
                url = "wss://agent.example.com:8767",
                code = "ABC123",
            ),
        )

        assertFalse(shouldOfferDashboardSignInAfterRelayPair(payload))
    }

    @Test
    fun acceptedQrFingerprintProvesIngressOnlyRoutesWithoutSecrets() {
        val payload = HermesPairingPayload(
            hermes = 3,
            dashboardUrl = "https://hermes.example.com",
            sig = "public-signature",
            relay = RelayPairing(
                url = "wss://hermes.example.com/api/plugins/hermes-relay/transport",
                code = "SECRET",
            ),
            endpoints = listOf(
                EndpointCandidate(
                    role = "https",
                    dashboard = DashboardEndpoint("https://hermes.example.com"),
                    relay = RelayEndpoint("wss://hermes.example.com/api/plugins/hermes-relay/transport"),
                ),
                EndpointCandidate(
                    role = "lan",
                    dashboard = DashboardEndpoint("http://192.168.1.10:9119"),
                    relay = RelayEndpoint("ws://192.168.1.10:9119/api/plugins/hermes-relay/transport"),
                ),
            ),
        )

        val fingerprint = pairingPayloadFingerprint(payload)

        assertEquals(
            "hermes=3 relay=true api=false sig=public-s roles=https,lan " +
                "dashboardPorts=443,9119 relayPorts=443,9119",
            fingerprint,
        )
        assertFalse(fingerprint.contains("SECRET"))
        assertFalse(fingerprint.contains("hermes.example.com"))
    }

    @Test
    fun acceptedQrFingerprintRedactsUntrustedEndpointRole() {
        val payload = HermesPairingPayload(
            hermes = 3,
            endpoints = listOf(
                EndpointCandidate(
                    role = "SECRET\r\nforged=entry",
                    dashboard = DashboardEndpoint("https://hermes.example.com"),
                ),
            ),
        )

        val fingerprint = pairingPayloadFingerprint(payload)

        assertEquals(
            "hermes=3 relay=false api=false sig=none roles=other dashboardPorts=443 relayPorts=null",
            fingerprint,
        )
        assertFalse(fingerprint.contains("SECRET"))
        assertFalse(fingerprint.contains('\n'))
        assertFalse(fingerprint.contains('\r'))
    }

    @Test
    fun startingNewQrScanClearsPriorTransactionAndForcesScannerRemount() {
        val oldPayload = HermesPairingPayload(
            hermes = 3,
            dashboardUrl = "https://old.example.com",
            relay = RelayPairing("wss://old.example.com/transport", "SECRET"),
        )
        val previous = QrScanTransactionState(
            pendingPayload = oldPayload,
            pendingManualCode = "OLD-CODE",
            hasPendingStandardDraft = true,
            hasPendingDashboardDraft = true,
            hasDuplicatePrompt = true,
            hasStandardSuccess = true,
            standardError = "old error",
            hasDashboardProbeResult = true,
            dashboardProbeError = "old probe error",
            dashboardSuggestedHostname = "old.example.com",
            verifyError = "old verify error",
            verifyAttempt = 4,
            standardApiKey = "OLD-KEY",
            manualCode = "OLD-MANUAL",
            ttlSeconds = 123,
            step = WizardStep.Confirm,
            chosenMethod = PairMethod.EnterCode,
            generation = 7,
        )

        val reset = resetQrScanTransaction(previous, WizardStep.Nearby)

        assertEquals(null, reset.pendingPayload)
        assertEquals(null, reset.pendingManualCode)
        assertFalse(reset.hasPendingStandardDraft)
        assertFalse(reset.hasPendingDashboardDraft)
        assertFalse(reset.hasDuplicatePrompt)
        assertFalse(reset.hasStandardSuccess)
        assertEquals(null, reset.standardError)
        assertFalse(reset.hasDashboardProbeResult)
        assertEquals(null, reset.dashboardProbeError)
        assertEquals(null, reset.dashboardSuggestedHostname)
        assertEquals(null, reset.verifyError)
        assertEquals(0, reset.verifyAttempt)
        assertEquals("", reset.standardApiKey)
        assertEquals("", reset.manualCode)
        assertEquals(com.hermesandroid.relay.data.PairingPreferences.DEFAULT_TTL_SECONDS, reset.ttlSeconds)
        assertEquals(WizardStep.Nearby, reset.step)
        assertEquals(PairMethod.Scan, reset.chosenMethod)
        assertEquals(8, reset.generation)
    }

    @Test
    fun wizardMotionRequiresOsAnimationsWithoutTouchExploration() {
        assertTrue(
            shouldAnimateWizardTransitions(
                AccessibleMotionState(osAnimations = true, touchExploration = false),
            ),
        )
        assertFalse(
            shouldAnimateWizardTransitions(
                AccessibleMotionState(osAnimations = false, touchExploration = false),
            ),
        )
        assertFalse(
            shouldAnimateWizardTransitions(
                AccessibleMotionState(osAnimations = true, touchExploration = true),
            ),
        )
    }

    @Test
    fun restoredWizardDropsStepsWhoseSecretOrProbeDraftWasNotSaved() {
        assertEquals(
            WizardStep.DashboardManual,
            restorableWizardStep(
                saved = WizardStep.DashboardFound,
                method = PairMethod.Standard,
                relayScoped = false,
                hasPayload = false,
                verifyAttempt = 0,
                hasDashboardProbe = false,
            ),
        )
        assertEquals(
            WizardStep.Nearby,
            restorableWizardStep(
                saved = WizardStep.Confirm,
                method = PairMethod.Scan,
                relayScoped = false,
                hasPayload = false,
                verifyAttempt = 0,
                hasDashboardProbe = false,
            ),
        )
        assertEquals(
            WizardStep.ManualEntry,
            restorableWizardStep(
                saved = WizardStep.Verify,
                method = PairMethod.EnterCode,
                relayScoped = true,
                hasPayload = false,
                verifyAttempt = 0,
                hasDashboardProbe = false,
            ),
        )
    }
}
