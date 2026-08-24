package com.hermesandroid.relay.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Parent-configured restrictions for the official Android client.
 *
 * This policy deliberately describes a client presentation mode, not a server
 * authorization boundary. The pinned profile is expected to have already been
 * configured with the appropriate server-side tool and content restrictions.
 */
@Serializable
data class SupervisedModePolicy(
    val enabled: Boolean = false,
    val pinnedProfileName: String? = null,
    val capabilities: SupervisedCapabilities = SupervisedCapabilities(),
    val visibility: SupervisedVisibility = SupervisedVisibility(),
    val parentAccess: SupervisedParentAccess = SupervisedParentAccess(),
) {
    /** A saved policy is usable only when it names a concrete Hermes profile. */
    val isConfigured: Boolean
        get() = !pinnedProfileName.isNullOrBlank()

    /** Consumers should use this instead of treating [enabled] alone as sufficient. */
    val isActive: Boolean
        get() = enabled && isConfigured

    internal fun normalized(): SupervisedModePolicy = copy(
        pinnedProfileName = pinnedProfileName?.trim()?.takeIf { it.isNotEmpty() },
        capabilities = capabilities.normalized(),
        parentAccess = parentAccess.normalized(),
    )
}

/** Actions and content types the supervised chat surface may expose. */
@Serializable
data class SupervisedCapabilities(
    val attachments: Boolean = false,
    val voice: Boolean = false,
    val generatedImages: Boolean = true,
    val conversationHistory: Boolean = false,
    val newChat: Boolean = true,
    val cancelResponse: Boolean = true,
    val steerResponse: Boolean = true,
    val retryResponse: Boolean = true,
    val copyResponses: Boolean = true,
    val quoteReplies: Boolean = true,
    val editAndResend: Boolean = false,
    val shareGeneratedImages: Boolean = false,
    val attachmentMaxCount: Int = DEFAULT_ATTACHMENT_MAX_COUNT,
    val attachmentMaxFileMb: Int = DEFAULT_ATTACHMENT_MAX_FILE_MB,
    val attachmentCategories: Set<SupervisedAttachmentCategory> = setOf(
        SupervisedAttachmentCategory.Images,
    ),
) {
    internal fun normalized(): SupervisedCapabilities = copy(
        attachmentMaxCount = attachmentMaxCount.coerceIn(1, MAX_ATTACHMENT_COUNT),
        attachmentMaxFileMb = attachmentMaxFileMb.coerceIn(1, MAX_ATTACHMENT_FILE_MB),
        attachmentCategories = attachmentCategories.ifEmpty {
            setOf(SupervisedAttachmentCategory.Images)
        },
    )

    companion object {
        const val DEFAULT_ATTACHMENT_MAX_COUNT = 4
        const val DEFAULT_ATTACHMENT_MAX_FILE_MB = 10
        const val MAX_ATTACHMENT_COUNT = 10
        const val MAX_ATTACHMENT_FILE_MB = 100
    }
}

@Serializable
enum class SupervisedAttachmentCategory {
    @SerialName("images")
    Images,

    @SerialName("documents")
    Documents,

    @SerialName("audio")
    Audio,

    @SerialName("video")
    Video,
}

/**
 * Controls which metadata and conversation affordances are rendered.
 *
 * [Simple] is the quiet default. [Transparent] is a useful preset for older or
 * technical users, while [Custom] tells the UI to honor every stored toggle.
 */
@Serializable
data class SupervisedVisibility(
    val preset: SupervisedVisibilityPreset = SupervisedVisibilityPreset.Simple,
    val showAgentIdentity: Boolean = true,
    val showModelName: Boolean = false,
    val showProfileName: Boolean = false,
    val showConnectionStatus: Boolean = true,
    val showTechnicalRoute: Boolean = false,
    val showTimestamps: Boolean = true,
    val showToolNames: Boolean = false,
    val showToolDetails: Boolean = false,
    val showWorkingStatus: Boolean = true,
    val showReasoning: Boolean = false,
    val showUsage: Boolean = false,
) {
    /** Resolve presets to the concrete flags consumed by chat presentation. */
    fun resolved(): SupervisedVisibility = when (preset) {
        SupervisedVisibilityPreset.Simple -> SIMPLE
        SupervisedVisibilityPreset.Transparent -> TRANSPARENT
        SupervisedVisibilityPreset.Custom -> this
    }

    companion object {
        val SIMPLE = SupervisedVisibility(preset = SupervisedVisibilityPreset.Simple)

        val TRANSPARENT = SupervisedVisibility(
            preset = SupervisedVisibilityPreset.Transparent,
            showModelName = true,
            showProfileName = true,
            showTechnicalRoute = true,
            showToolNames = true,
            showUsage = true,
        )
    }
}

@Serializable
enum class SupervisedVisibilityPreset {
    @SerialName("simple")
    Simple,

    @SerialName("transparent")
    Transparent,

    @SerialName("custom")
    Custom,
}

/** Device-authentication and automatic relock behavior for parent access. */
@Serializable
data class SupervisedParentAccess(
    /** Reserved for forward-compatible persistence; normalization never permits an auth bypass. */
    val requireDeviceAuthentication: Boolean = true,
    val relockOnBackground: Boolean = true,
    val timeoutMinutes: Int = DEFAULT_TIMEOUT_MINUTES,
) {
    internal fun normalized(): SupervisedParentAccess = copy(
        requireDeviceAuthentication = true,
        timeoutMinutes = timeoutMinutes.coerceIn(MIN_TIMEOUT_MINUTES, MAX_TIMEOUT_MINUTES),
    )

    companion object {
        const val DEFAULT_TIMEOUT_MINUTES = 5
        const val MIN_TIMEOUT_MINUTES = 1
        const val MAX_TIMEOUT_MINUTES = 60
    }
}
