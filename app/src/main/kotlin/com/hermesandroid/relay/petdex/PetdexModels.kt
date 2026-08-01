package com.hermesandroid.relay.petdex

/** Public Petdex catalog entry. Installing is always an explicit user action. */
data class PetdexPet(
    val slug: String,
    val displayName: String,
    val kind: String,
    val submittedBy: String,
    val spritesheetUrl: String,
    val petJsonUrl: String,
    val zipUrl: String? = null,
) {
    val sourceUrl: String get() = "https://petdex.dev/pets/$slug"
    val installedAvatarId: String get() = "petdex-$slug"
}

sealed interface PetdexInstallResult {
    data class Success(val avatarId: String, val label: String) : PetdexInstallResult
    data class Failure(val reason: String) : PetdexInstallResult
}

internal class PetdexException(message: String, cause: Throwable? = null) : Exception(message, cause)
