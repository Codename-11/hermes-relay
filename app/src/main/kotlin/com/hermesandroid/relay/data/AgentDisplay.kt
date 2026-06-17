package com.hermesandroid.relay.data

/**
 * Shared profile/personality display and request identity helpers.
 *
 * A null profile name is the app's explicit "Server default" state. The
 * relay also advertises the root Hermes config as a synthetic profile named
 * "default"; for request/session identity that row is an alias of server
 * default so it does not split chat, voice, or session scope.
 */
object AgentDisplay {
    const val SERVER_DEFAULT_PROFILE_KEY: String = "__server_default__"
    private val GENERIC_MODEL_ALIASES = setOf(
        "hermes-agent",
        "hermes_agent",
        "hermes agent",
    )

    // Only an EXPLICIT pick drives request/session identity. The advertised
    // "default" profile is an alias for server default, so falling back to it
    // here would split chat, voice, or session scope.
    @Suppress("UNUSED_PARAMETER")
    fun effectiveProfile(
        selectedProfile: Profile?,
        profiles: List<Profile>,
    ): Profile? = selectedProfile

    // Display can use the synthetic default profile's metadata without making
    // it a request/session override. Verbose SOUL summaries are filtered by
    // profileDisplayName below, so this is safe for headers/cards.
    fun effectiveDisplayProfile(
        selectedProfile: Profile?,
        profiles: List<Profile>,
    ): Profile? = selectedProfile ?: profiles.firstOrNull { isServerDefaultAlias(it.name) }

    // The NAME goes in the name slot. Non-default profiles use their profile
    // name first. The synthetic default profile uses its description only when
    // that description looks like a concise human agent name ("Victor"), not a
    // verbose SOUL summary.
    fun profileDisplayName(profile: Profile?): String? {
        if (profile == null) return null
        if (isServerDefaultAlias(profile.name)) {
            return defaultProfileDisplayName(profile)
        }
        return when {
            profile.name.isNotBlank() -> titleCase(profile.name.trim())
            profile.description.isNotBlank() -> profile.description.trim()
            else -> null
        }
    }

    fun defaultProfileDisplayName(profile: Profile?): String? =
        profile
            ?.description
            ?.trim()
            ?.takeIf { it.looksLikeConciseAgentName() }
            ?.let(::titleCase)

    fun agentName(
        profile: Profile?,
        selectedPersonality: String,
        defaultPersonality: String,
        connectionLabel: String?,
        localDisplayAlias: String? = null,
    ): String {
        localDisplayAlias(localDisplayAlias)?.let { return it }
        profileDisplayName(profile)?.let { return it }

        val personalityName = if (
            selectedPersonality == "default" &&
            defaultPersonality.isNotBlank()
        ) {
            defaultPersonality
        } else {
            selectedPersonality
        }

        return when {
            personalityName.isNotBlank() && personalityName != "default" ->
                titleCase(personalityName.trim())
            !connectionLabel.isNullOrBlank() -> connectionLabel.trim()
            else -> "Hermes"
        }
    }

    fun personalityLabel(
        selectedPersonality: String,
        defaultPersonality: String,
    ): String = when {
        selectedPersonality != "default" && selectedPersonality.isNotBlank() ->
            titleCase(selectedPersonality.trim())
        defaultPersonality.isNotBlank() -> titleCase(defaultPersonality.trim())
        else -> "Default"
    }

    fun displayModelName(model: String?): String? =
        model
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeUnless { it.lowercase() in GENERIC_MODEL_ALIASES }

    fun isServerDefaultAlias(profileName: String?): Boolean =
        profileName?.trim()?.equals("default", ignoreCase = true) == true

    fun normalizeSelection(profile: Profile?): Profile? =
        if (isServerDefaultAlias(profile?.name)) null else profile

    fun profileRequestName(profileName: String?): String? =
        profileName
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !isServerDefaultAlias(it) }

    fun profileSessionKey(profileName: String?): String =
        profileRequestName(profileName) ?: SERVER_DEFAULT_PROFILE_KEY

    fun profileContextKey(connectionId: String?, profileName: String?): String =
        "${connectionId.orEmpty()}::${profileSessionKey(profileName)}"

    fun localDisplayAlias(value: String?): String? =
        value
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotEmpty() }

    private fun String.looksLikeConciseAgentName(): Boolean {
        if (isBlank() || length > 40 || contains('\n') || contains('\r')) {
            return false
        }
        if (any { it == '.' || it == ':' || it == ';' }) {
            return false
        }
        return trim().split(Regex("\\s+")).size <= 4
    }

    private fun titleCase(value: String): String =
        value.replaceFirstChar { it.uppercase() }
}
