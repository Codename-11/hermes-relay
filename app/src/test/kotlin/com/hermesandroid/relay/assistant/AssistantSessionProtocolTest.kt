package com.hermesandroid.relay.assistant

import com.hermesandroid.relay.viewmodel.VoiceState
import com.hermesandroid.relay.viewmodel.VoiceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import android.service.voice.VoiceInteractionSession
import com.hermesandroid.relay.runtime.assistantHeartbeatExpired
import com.hermesandroid.relay.runtime.assistantCanTransmitScreenContext
import com.hermesandroid.relay.runtime.AssistantHeartbeatOwnership
import com.hermesandroid.relay.runtime.assistantHeartbeatShouldCancel
import com.hermesandroid.relay.data.VoiceEngineMode
import com.hermesandroid.relay.viewmodel.voiceSubmissionRejectedState
import com.hermesandroid.relay.viewmodel.voiceSubmissionRetryState
import com.hermesandroid.relay.viewmodel.assistantContextTurnDisposition

class AssistantSessionProtocolTest {
    @Test
    fun assistAction_routesIntoSystemSessionActivation() {
        assertTrue(AssistantSessionProtocol.isAssistAction("android.intent.action.ASSIST"))
        assertFalse(AssistantSessionProtocol.isAssistAction("android.intent.action.MAIN"))
        assertFalse(AssistantSessionProtocol.isAssistAction(null))
    }

    @Test
    fun voiceState_mapsToSystemSessionPresentation() {
        val listening = AssistantSessionProtocol.snapshotFromVoiceState(
            VoiceUiState(
                voiceMode = true,
                state = VoiceState.Listening,
                transcribedText = "Hey Hermes",
            )
        )
        assertEquals(AssistantSessionPhase.Listening, listening.phase)
        assertEquals("Hey Hermes", listening.transcript)

        val speaking = AssistantSessionProtocol.snapshotFromVoiceState(
            VoiceUiState(
                voiceMode = true,
                state = VoiceState.Speaking,
                responseText = "Hello.",
            )
        )
        assertEquals(AssistantSessionPhase.Speaking, speaking.phase)
        assertEquals("Hello.", speaking.response)
    }

    @Test
    fun exitAndError_haveTerminalPresentationStates() {
        val closed = AssistantSessionProtocol.snapshotFromVoiceState(VoiceUiState())
        assertEquals(AssistantSessionPhase.Closed, closed.phase)
        assertNull(closed.error)

        val error = AssistantSessionProtocol.snapshotFromVoiceState(
            VoiceUiState(
                voiceMode = true,
                state = VoiceState.Error,
                error = "Microphone unavailable",
            )
        )
        assertEquals(AssistantSessionPhase.Error, error.phase)
        assertEquals("Microphone unavailable", error.error)
    }

    @Test
    fun idleNoSpeech_isAVisibleRetryNotice() {
        val snapshot = AssistantSessionProtocol.snapshotFromVoiceState(
            VoiceUiState(
                voiceMode = true,
                state = VoiceState.Idle,
                assistantNotice = AssistantSessionNotice.NoSpeech,
            )
        )

        assertEquals(AssistantSessionPhase.Idle, snapshot.phase)
        assertEquals(AssistantSessionNotice.NoSpeech, snapshot.notice)
        assertNull(snapshot.error)
    }

    @Test
    fun lockedPresentation_redactsConversationButKeepsGenericNotice() {
        val presented = assistantSnapshotForPresentation(
            AssistantSessionSnapshot(
                phase = AssistantSessionPhase.Error,
                transcript = "private request",
                response = "private response",
                notice = AssistantSessionNotice.NoSpeech,
                error = "private route detail",
            ),
            locked = true,
        )

        assertNull(presented.transcript)
        assertEquals("", presented.response)
        assertNull(presented.error)
        assertEquals(AssistantSessionNotice.NoSpeech, presented.notice)
    }

    @Test
    fun unlockedPresentation_restoresConversationContent() {
        val snapshot = AssistantSessionSnapshot(
            phase = AssistantSessionPhase.Speaking,
            transcript = "request",
            response = "response",
        )

        assertEquals(snapshot, assistantSnapshotForPresentation(snapshot, locked = false))
    }

    @Test
    fun liveKeyguardState_overridesLaunchFallbackInBothDirections() {
        assertTrue(
            assistantPresentationLocked(
                currentKeyguardLocked = true,
                fallbackLocked = false,
            )
        )
        assertFalse(
            assistantPresentationLocked(
                currentKeyguardLocked = false,
                fallbackLocked = true,
            )
        )
    }

    @Test
    fun statusSnapshots_areFencedToTheCurrentActivation() {
        assertTrue(assistantSnapshotMatchesActivation("activation-b", "activation-b"))
        assertFalse(assistantSnapshotMatchesActivation("activation-b", "activation-a"))
        assertFalse(assistantSnapshotMatchesActivation("activation-b", null))
        assertFalse(assistantSnapshotMatchesActivation(null, "activation-b"))
    }

    @Test
    fun voiceStateRetainsItsOwningActivationAcrossLaterRuntimeChanges() {
        val activationA = VoiceUiState(
            voiceMode = true,
            state = VoiceState.Listening,
            assistantActivationId = "activation-a",
        )
        val currentRuntimeActivation = "activation-b"

        assertEquals("activation-a", activationA.assistantActivationId)
        assertFalse(
            assistantSnapshotMatchesActivation(
                expectedActivationId = currentRuntimeActivation,
                receivedActivationId = activationA.assistantActivationId,
            )
        )
    }

    @Test
    fun terminalVoiceStateRetainsActivationForClosedPublication() {
        val exited = com.hermesandroid.relay.viewmodel.voiceSessionExitState(
            VoiceUiState(
                voiceMode = true,
                state = VoiceState.Speaking,
                assistantActivationId = "activation-a",
            )
        )

        assertFalse(exited.voiceMode)
        assertEquals("activation-a", exited.assistantActivationId)
        assertEquals(
            AssistantSessionPhase.Closed,
            AssistantSessionProtocol.snapshotFromVoiceState(exited).phase,
        )
    }

    @Test
    fun subsequentOrdinaryVoiceEntryClearsPreviousAssistantOwner() {
        val ordinaryEntry = VoiceUiState(
            voiceMode = true,
            state = VoiceState.Idle,
            assistantActivationId = null,
        )

        assertNull(ordinaryEntry.assistantActivationId)
    }

    @Test
    fun persistedSessionMarker_expiresAfterBoundedRecoveryWindow() {
        val now = 2_000_000L
        assertTrue(AssistantSessionPersistence.isFresh(now - 1_000L, now))
        assertFalse(AssistantSessionPersistence.isFresh(0L, now))
        assertFalse(
            AssistantSessionPersistence.isFresh(
                sinceMs = now - (31 * 60 * 1_000L),
                nowMs = now,
            )
        )
    }

    @Test
    fun assistantProtocol_distinguishesHeadlessActivationFromLifecycleStart() {
        assertTrue(
            AssistantSessionProtocol.isActivateAction(
                "com.hermesandroid.relay.assistant.ACTIVATE"
            )
        )
        assertFalse(
            AssistantSessionProtocol.isActivateAction(
                "com.hermesandroid.relay.assistant.START"
            )
        )
    }

    @Test
    fun onlyUnlockedContextSession_requestsAssistAndScreenshot() {
        val unlocked = assistantSessionShowFlags(
            fromKeyguard = false,
            captureScreenContext = true,
        )
        assertTrue(unlocked and VoiceInteractionSession.SHOW_WITH_ASSIST != 0)
        assertTrue(unlocked and VoiceInteractionSession.SHOW_WITH_SCREENSHOT != 0)
        assertEquals(
            0,
            assistantSessionShowFlags(fromKeyguard = true, captureScreenContext = true),
        )
        assertEquals(
            0,
            assistantSessionShowFlags(fromKeyguard = false, captureScreenContext = false),
        )
    }

    @Test
    fun retry_reusesCurrentActivationId() {
        assertEquals("activation-1", assistantRetryActivationId("activation-1"))
        assertNull(assistantRetryActivationId(null))
    }

    @Test
    fun showFailureRecovery_restartsWakeOnlyWhenEnabled() {
        assertEquals(
            AssistantSessionFailureRecovery.RetryWake,
            assistantSessionFailureRecovery(assistantWakeEnabled = true),
        )
        assertEquals(
            AssistantSessionFailureRecovery.Stop,
            assistantSessionFailureRecovery(assistantWakeEnabled = false),
        )
    }

    @Test
    fun pendingFirmwareRequest_waitsForVoicePreferences() {
        assertFalse(assistantPendingRequestCanDrain(serviceReady = false, preferencesLoaded = false))
        assertFalse(assistantPendingRequestCanDrain(serviceReady = true, preferencesLoaded = false))
        assertTrue(assistantPendingRequestCanDrain(serviceReady = true, preferencesLoaded = true))
    }

    @Test
    fun delayedContextRequest_rechecksKeyguardWhenSessionIsShown() {
        var keyguardLocked = false
        val isKeyguardLocked = { keyguardLocked }

        keyguardLocked = true
        val policy = assistantSessionCapturePolicy(
            captureScreenContext = true,
            isKeyguardLocked = isKeyguardLocked,
        )

        assertTrue(policy.fromKeyguard)
        assertFalse(policy.expectScreenContext)
        assertEquals(0, policy.showFlags)
    }

    @Test
    fun heartbeatExpiry_usesMonotonicConservativeGrace() {
        assertFalse(assistantHeartbeatExpired(1_000L, 61_000L, 60_000L))
        assertTrue(assistantHeartbeatExpired(1_000L, 61_001L, 60_000L))
        assertFalse(assistantHeartbeatExpired(0L, 100_000L, 60_000L))
        assertFalse(assistantHeartbeatExpired(10_000L, 9_000L, 60_000L))
    }

    @Test
    fun fullVoiceHandoff_disablesSessionHeartbeatCancellation() {
        fun shouldCancel(ownership: AssistantHeartbeatOwnership) =
            assistantHeartbeatShouldCancel(
                ownership = ownership,
                expectedActivationId = "activation-1",
                currentActivationId = "activation-1",
                expectedGeneration = 3L,
                currentGeneration = 3L,
                observedHeartbeatElapsedMs = 1_000L,
                currentHeartbeatElapsedMs = 1_000L,
                nowElapsedMs = 70_000L,
                graceMs = 60_000L,
            )

        assertTrue(shouldCancel(AssistantHeartbeatOwnership.Session))
        assertFalse(shouldCancel(AssistantHeartbeatOwnership.FullVoice))
    }

    @Test
    fun onlyStandardVoice_claimsScreenContextTransport() {
        assertTrue(assistantCanTransmitScreenContext(VoiceEngineMode.HermesVoiceOutput))
        assertFalse(assistantCanTransmitScreenContext(VoiceEngineMode.RealtimeAgent))
    }

    @Test
    fun rejectedVoiceSubmission_isVisibleAndRetainsVoiceMode() {
        val rejected = voiceSubmissionRejectedState(
            VoiceUiState(voiceMode = true, state = VoiceState.Thinking),
            "Hermes is still handling another turn.",
        )

        assertTrue(rejected.voiceMode)
        assertEquals(VoiceState.Error, rejected.state)
        assertEquals("Hermes is still handling another turn.", rejected.error)
        val retry = voiceSubmissionRetryState(rejected)
        assertEquals(VoiceState.Idle, retry.state)
        assertNull(retry.error)
    }

    @Test
    fun missingFirstLoad_retiresActivationWithoutConsumption() {
        val missing = assistantContextTurnDisposition(
            expectScreenContext = true,
            hasActivation = true,
            stagedContextLoaded = false,
        )
        val loaded = assistantContextTurnDisposition(
            expectScreenContext = true,
            hasActivation = true,
            stagedContextLoaded = true,
        )

        assertTrue(missing.retireForLaterTurns)
        assertFalse(missing.consumeOnTransportAcceptance)
        assertTrue(loaded.retireForLaterTurns)
        assertTrue(loaded.consumeOnTransportAcceptance)
    }

    @Test
    fun manualMicProtocol_onlyAllowsIdleStartAndListeningStop() {
        assertEquals(AssistantMicAction.Start, assistantMicAction(AssistantSessionPhase.Idle))
        assertEquals(AssistantMicAction.Stop, assistantMicAction(AssistantSessionPhase.Listening))
        assertEquals(AssistantMicAction.Disabled, assistantMicAction(AssistantSessionPhase.Thinking))
        assertTrue(
            AssistantSessionProtocol.isStartListeningAction(
                "com.hermesandroid.relay.assistant.START_LISTENING"
            )
        )
        assertTrue(
            AssistantSessionProtocol.isStopListeningAction(
                "com.hermesandroid.relay.assistant.STOP_LISTENING"
            )
        )
        assertTrue(
            AssistantSessionProtocol.isHeartbeatAction(
                "com.hermesandroid.relay.assistant.HEARTBEAT"
            )
        )
        assertTrue(
            AssistantSessionProtocol.isFullVoiceHandoffAction(
                "com.hermesandroid.relay.assistant.FULL_VOICE_HANDOFF"
            )
        )
        assertTrue(
            AssistantSessionProtocol.isRetryVoiceAction(
                "com.hermesandroid.relay.assistant.RETRY_VOICE"
            )
        )
    }

    @Test
    fun ordinarySessionHide_cancelsTheAppOwnedVoiceTurn() {
        assertTrue(
            shouldCancelVoiceWhenSessionUiEnds(AssistantSessionPresentation.Overlay)
        )
    }

    @Test
    fun fullVoiceHandoff_doesNotCancelTheAppOwnedVoiceTurn() {
        assertFalse(
            shouldCancelVoiceWhenSessionUiEnds(AssistantSessionPresentation.FullVoice)
        )
    }

    @Test
    fun inactiveSessionCleanup_isIdempotent() {
        assertFalse(
            shouldCancelVoiceWhenSessionUiEnds(AssistantSessionPresentation.Inactive)
        )
    }

    @Test
    fun closedSnapshot_reconcilesTheAppLifecycleWithoutCancellingVoice() {
        assertTrue(
            AssistantSessionProtocol.shouldFinishLifecycleOnSnapshot(
                AssistantSessionSnapshot(phase = AssistantSessionPhase.Closed)
            )
        )
        assertFalse(
            AssistantSessionProtocol.shouldFinishLifecycleOnSnapshot(
                AssistantSessionSnapshot(phase = AssistantSessionPhase.Speaking)
            )
        )
    }
}
