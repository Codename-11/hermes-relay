package com.hermesandroid.relay.data

/**
 * One-tap bundles over voice settings that already exist in the app and relay.
 *
 * Presets intentionally do not own voice identity or routing: engine, audio
 * route, provider, model, voice, enhanced-voice overrides, and background-run
 * concurrency all remain exactly as the user configured them. A preset only
 * coordinates interaction ergonomics, barge-in, Realtime trace/session
 * behavior, and the existing ADR 33 background-delivery controls.
 */
enum class VoiceModePreset(
    val displayName: String,
    val shortLabel: String,
    val description: String,
    internal val localSettings: VoicePresetLocalSettings,
    internal val bargeInUpdate: VoicePresetBargeInUpdate,
    val promotionUpdate: VoicePresetPromotionUpdate,
) {
    HandsFree(
        displayName = "Hands-free",
        shortLabel = "Hands-free",
        description =
            "Continuous listening, exact answers, detailed trace, and low-noise " +
                "spoken progress after 15 seconds. Your barge-in choice is preserved.",
        localSettings = VoicePresetLocalSettings(
            interactionMode = "continuous",
            silenceThresholdMs = 1250L,
            realtimeTraceDetails = true,
            realtimePersistentSession = true,
        ),
        // Barge-in remains an explicit experimental opt-in until echo and
        // self-recording hardening is complete. Never enable it via a preset.
        bargeInUpdate = VoicePresetBargeInUpdate(),
        promotionUpdate = VoicePresetPromotionUpdate(
            enabled = true,
            promoteAfterMs = 6000,
            backgroundDefaultMode = "promote",
            spokenHandoff = true,
            progressSpokenAfterMs = 15000,
            progressRepeatMs = 90000,
            resultDelivery = "speak_verbatim",
        ),
    ),
    LowLatency(
        displayName = "Low latency",
        shortLabel = "Fast",
        description =
            "Tap capture, the shortest supported silence window, a persistent " +
                "session, and a fast visual handoff for long work.",
        localSettings = VoicePresetLocalSettings(
            interactionMode = "tap",
            silenceThresholdMs = 750L,
            realtimeTraceDetails = false,
            realtimePersistentSession = true,
        ),
        bargeInUpdate = VoicePresetBargeInUpdate(enabled = false),
        promotionUpdate = VoicePresetPromotionUpdate(
            enabled = true,
            promoteAfterMs = 2500,
            backgroundDefaultMode = "promote",
            spokenHandoff = false,
            progressSpokenAfterMs = 0,
            resultDelivery = "speak_when_idle",
        ),
    ),
    CarefulTools(
        displayName = "Careful tools",
        shortLabel = "Careful",
        description =
            "Hold-to-talk, uninterrupted foreground tool runs, a detailed trace, and exact result delivery.",
        localSettings = VoicePresetLocalSettings(
            interactionMode = "hold",
            silenceThresholdMs = 1750L,
            realtimeTraceDetails = true,
            realtimePersistentSession = true,
        ),
        bargeInUpdate = VoicePresetBargeInUpdate(enabled = false),
        promotionUpdate = VoicePresetPromotionUpdate(
            enabled = false,
            backgroundDefaultMode = "foreground",
            spokenHandoff = false,
            progressSpokenAfterMs = 0,
            resultDelivery = "speak_verbatim",
        ),
    ),
    QuietVisualOnly(
        displayName = "Quiet / visual-only",
        shortLabel = "Quiet",
        description =
            "Manual capture with visual long-task handoffs and results. Normal short voice replies still speak.",
        localSettings = VoicePresetLocalSettings(
            interactionMode = "tap",
            silenceThresholdMs = 1250L,
            realtimeTraceDetails = true,
            realtimePersistentSession = true,
        ),
        bargeInUpdate = VoicePresetBargeInUpdate(enabled = false),
        promotionUpdate = VoicePresetPromotionUpdate(
            enabled = true,
            promoteAfterMs = 6000,
            backgroundDefaultMode = "promote",
            spokenHandoff = false,
            progressSpokenAfterMs = 0,
            resultDelivery = "visual_only",
        ),
    );

    /** Apply only fields owned by this preset; every other value is preserved. */
    fun applyTo(current: VoiceModePresetState): VoiceModePresetState =
        current.copy(
            voiceSettings = current.voiceSettings.copy(
                interactionMode = localSettings.interactionMode,
                silenceThresholdMs = localSettings.silenceThresholdMs,
                realtimeTraceDetails = localSettings.realtimeTraceDetails,
                realtimePersistentSession = localSettings.realtimePersistentSession,
            ),
            bargeInPreferences = current.bargeInPreferences.copy(
                enabled = bargeInUpdate.enabled ?: current.bargeInPreferences.enabled,
                sensitivity =
                    bargeInUpdate.sensitivity ?: current.bargeInPreferences.sensitivity,
                resumeAfterInterruption = bargeInUpdate.resumeAfterInterruption
                    ?: current.bargeInPreferences.resumeAfterInterruption,
            ),
            promotion = current.promotion?.let(promotionUpdate::applyTo),
        )

    /** A preset is active only when every field it owns still matches. */
    fun matches(current: VoiceModePresetState): Boolean =
        current.promotion != null && applyTo(current) == current
}

/** Snapshot used by the pure preset reducer and active-preset detector. */
data class VoiceModePresetState(
    val voiceSettings: VoiceSettings,
    val bargeInPreferences: BargeInPreferences,
    val promotion: VoicePresetPromotionSettings?,
)

/** Relay promotion values mirrored without introducing a data -> network dependency. */
data class VoicePresetPromotionSettings(
    val enabled: Boolean = true,
    val promoteAfterMs: Int = 6000,
    val backgroundDefaultMode: String = "promote",
    val spokenHandoff: Boolean = true,
    val progressSpokenAfterMs: Int = 0,
    val progressRepeatMs: Int = 90000,
    val resultDelivery: String = "speak_verbatim",
    val maxBackgroundRuns: Int = 1,
)

/** Nullable fields map directly to RelayVoiceClient's partial PATCH contract. */
data class VoicePresetPromotionUpdate(
    val enabled: Boolean? = null,
    val promoteAfterMs: Int? = null,
    val backgroundDefaultMode: String? = null,
    val spokenHandoff: Boolean? = null,
    val progressSpokenAfterMs: Int? = null,
    val progressRepeatMs: Int? = null,
    val resultDelivery: String? = null,
    val maxBackgroundRuns: Int? = null,
) {
    internal fun applyTo(current: VoicePresetPromotionSettings): VoicePresetPromotionSettings =
        current.copy(
            enabled = enabled ?: current.enabled,
            promoteAfterMs = promoteAfterMs ?: current.promoteAfterMs,
            backgroundDefaultMode = backgroundDefaultMode ?: current.backgroundDefaultMode,
            spokenHandoff = spokenHandoff ?: current.spokenHandoff,
            progressSpokenAfterMs = progressSpokenAfterMs ?: current.progressSpokenAfterMs,
            progressRepeatMs = progressRepeatMs ?: current.progressRepeatMs,
            resultDelivery = resultDelivery ?: current.resultDelivery,
            maxBackgroundRuns = maxBackgroundRuns ?: current.maxBackgroundRuns,
        )
}

internal data class VoicePresetLocalSettings(
    val interactionMode: String,
    val silenceThresholdMs: Long,
    val realtimeTraceDetails: Boolean,
    val realtimePersistentSession: Boolean,
)

internal data class VoicePresetBargeInUpdate(
    val enabled: Boolean? = null,
    val sensitivity: BargeInSensitivity? = null,
    val resumeAfterInterruption: Boolean? = null,
)

/** Null means the current manual values are Custom. */
fun detectVoiceModePreset(current: VoiceModePresetState): VoiceModePreset? =
    VoiceModePreset.entries.firstOrNull { it.matches(current) }
