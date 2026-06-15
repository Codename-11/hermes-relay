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

    // Only an EXPLICIT pick drives the effective profile. We deliberately do
    // NOT fall back to the advertised "default" profile: a dashboard profile's
    // description is a verbose SOUL summary ("Builds and maintains…"), and
    // resolving it here replaced the clean agent name (the personality, e.g.
    // "Victor") with that summary in the header. With no explicit pick, the
    // name comes from the personality. ([profiles] kept for call-site symmetry.)
    @Suppress("UNUSED_PARAMETER")
    fun effectiveProfile(
        selectedProfile: Profile?,
        profiles: List<Profile>,
    ): Profile? = selectedProfile

    // The NAME goes in the name slot. A profile's description is a SOUL summary
    // ("Builds and maintains…"), far too verbose for the agent-name label, so
    // the profile name wins; description is only a last resort when name is blank.
    fun profileDisplayName(profile: Profile?): String? {
        if (profile == null) return null
        return when {
            profile.name.isNotBlank() -> titleCase(profile.name.trim())
            profile.description.isNotBlank() -> profile.description.trim()
            else -> null
        }
    }

    fun agentName(
        profile: Profile?,
        selectedPersonality: String,
        defaultPersonality: String,
        connectionLabel: String?,
    ): String {
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

    private fun titleCase(value: String): String =
        value.replaceFirstChar { it.uppercase() }
}
