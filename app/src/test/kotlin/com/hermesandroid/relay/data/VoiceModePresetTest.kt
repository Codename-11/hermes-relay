package com.hermesandroid.relay.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModePresetTest {

    private val current = VoiceModePresetState(
        voiceSettings = VoiceSettings(
            engineMode = VoiceEngineMode.HermesVoiceOutput.storageValue,
            audioRoute = VoiceAudioRoute.Relay.storageValue,
            interactionMode = "tap",
            silenceThresholdMs = 3000L,
            realtimeTraceDetails = false,
            realtimePersistentSession = false,
            realtimeModel = "custom-realtime-model",
            realtimeVoice = "custom-realtime-voice",
            enhancedVoice = "custom-output-voice",
            enhancedModel = "custom-output-model",
            enhancedAudioTags = true,
            enhancedPersona = "Warm and precise",
            enhancedLanguage = "en-US",
        ),
        bargeInPreferences = BargeInPreferences(
            enabled = true,
            sensitivity = BargeInSensitivity.High,
            resumeAfterInterruption = false,
        ),
        promotion = VoicePresetPromotionSettings(
            enabled = true,
            promoteAfterMs = 42000,
            backgroundDefaultMode = "foreground",
            spokenHandoff = true,
            progressSpokenAfterMs = 32000,
            progressRepeatMs = 123000,
            resultDelivery = "notify_then_speak",
            maxBackgroundRuns = 4,
        ),
    )

    @Test
    fun handsFreeMapsContinuousListeningAndPreservesExperimentalBargeInChoice() {
        val source = current.copy(
            bargeInPreferences = BargeInPreferences(
                enabled = false,
                sensitivity = BargeInSensitivity.High,
                resumeAfterInterruption = false,
            ),
        )
        val target = VoiceModePreset.HandsFree.applyTo(source)

        assertEquals("continuous", target.voiceSettings.interactionMode)
        assertEquals(1250L, target.voiceSettings.silenceThresholdMs)
        assertTrue(target.voiceSettings.realtimeTraceDetails)
        assertTrue(target.voiceSettings.realtimePersistentSession)
        assertFalse(target.bargeInPreferences.enabled)
        assertEquals(BargeInSensitivity.High, target.bargeInPreferences.sensitivity)
        assertFalse(target.bargeInPreferences.resumeAfterInterruption)
        assertEquals(6000, target.promotion?.promoteAfterMs)
        assertTrue(target.promotion?.spokenHandoff == true)
        assertEquals(15000, target.promotion?.progressSpokenAfterMs)
        assertEquals(90000, target.promotion?.progressRepeatMs)
        assertEquals("speak_verbatim", target.promotion?.resultDelivery)
    }

    @Test
    fun lowLatencyMapsShortestSilenceAndFastPromotion() {
        val target = VoiceModePreset.LowLatency.applyTo(current)

        assertEquals("tap", target.voiceSettings.interactionMode)
        assertEquals(750L, target.voiceSettings.silenceThresholdMs)
        assertFalse(target.voiceSettings.realtimeTraceDetails)
        assertTrue(target.voiceSettings.realtimePersistentSession)
        assertFalse(target.bargeInPreferences.enabled)
        assertEquals(2500, target.promotion?.promoteAfterMs)
        assertFalse(target.promotion?.spokenHandoff == true)
        assertEquals(0, target.promotion?.progressSpokenAfterMs)
        assertEquals("speak_when_idle", target.promotion?.resultDelivery)
    }

    @Test
    fun carefulToolsKeepsRunsForegroundAndResultsExact() {
        val target = VoiceModePreset.CarefulTools.applyTo(current)

        assertEquals("hold", target.voiceSettings.interactionMode)
        assertEquals(1750L, target.voiceSettings.silenceThresholdMs)
        assertTrue(target.voiceSettings.realtimeTraceDetails)
        assertFalse(target.bargeInPreferences.enabled)
        assertFalse(target.promotion?.enabled == true)
        assertEquals("foreground", target.promotion?.backgroundDefaultMode)
        assertEquals("speak_verbatim", target.promotion?.resultDelivery)
    }

    @Test
    fun quietVisualOnlyLeavesShortReplyBehaviorExplicitlyOutOfScope() {
        val target = VoiceModePreset.QuietVisualOnly.applyTo(current)

        assertEquals("tap", target.voiceSettings.interactionMode)
        assertEquals(1250L, target.voiceSettings.silenceThresholdMs)
        assertTrue(target.voiceSettings.realtimeTraceDetails)
        assertFalse(target.bargeInPreferences.enabled)
        assertTrue(target.promotion?.enabled == true)
        assertFalse(target.promotion?.spokenHandoff == true)
        assertEquals(0, target.promotion?.progressSpokenAfterMs)
        assertEquals("visual_only", target.promotion?.resultDelivery)
    }

    @Test
    fun detectorRecognizesEveryFullyAppliedPreset() {
        VoiceModePreset.entries.forEach { preset ->
            assertEquals(preset, detectVoiceModePreset(preset.applyTo(current)))
        }
    }

    @Test
    fun manualDivergenceReportsCustom() {
        val handsFree = VoiceModePreset.HandsFree.applyTo(current)
        val diverged = handsFree.copy(
            voiceSettings = handsFree.voiceSettings.copy(interactionMode = "tap"),
        )

        assertNull(detectVoiceModePreset(diverged))
    }

    @Test
    fun missingPromotionSnapshotNeverClaimsAnActivePreset() {
        val noPromotion = current.copy(promotion = null)

        assertNull(detectVoiceModePreset(VoiceModePreset.HandsFree.applyTo(noPromotion)))
    }

    @Test
    fun presetsPreserveVoiceIdentityRoutingAndConcurrency() {
        VoiceModePreset.entries.forEach { preset ->
            val target = preset.applyTo(current)

            assertEquals(current.voiceSettings.engineMode, target.voiceSettings.engineMode)
            assertEquals(current.voiceSettings.audioRoute, target.voiceSettings.audioRoute)
            assertEquals(current.voiceSettings.realtimeModel, target.voiceSettings.realtimeModel)
            assertEquals(current.voiceSettings.realtimeVoice, target.voiceSettings.realtimeVoice)
            assertEquals(current.voiceSettings.enhancedVoice, target.voiceSettings.enhancedVoice)
            assertEquals(current.voiceSettings.enhancedModel, target.voiceSettings.enhancedModel)
            assertEquals(
                current.voiceSettings.enhancedAudioTags,
                target.voiceSettings.enhancedAudioTags,
            )
            assertEquals(current.voiceSettings.enhancedPersona, target.voiceSettings.enhancedPersona)
            assertEquals(current.voiceSettings.enhancedLanguage, target.voiceSettings.enhancedLanguage)
            assertEquals(
                current.promotion?.maxBackgroundRuns,
                target.promotion?.maxBackgroundRuns,
            )
        }
    }

    @Test
    fun disabledBargeInPresetsPreserveHiddenSensitivityPreferences() {
        listOf(
            VoiceModePreset.LowLatency,
            VoiceModePreset.CarefulTools,
            VoiceModePreset.QuietVisualOnly,
        ).forEach { preset ->
            val target = preset.applyTo(current)

            assertEquals(BargeInSensitivity.High, target.bargeInPreferences.sensitivity)
            assertFalse(target.bargeInPreferences.resumeAfterInterruption)
        }
    }
}
