package com.hermesandroid.relay.data

import com.hermesandroid.relay.BuildConfig

/** Immutable provenance embedded into side-by-side review and RC builds. */
object CandidateBuild {
    val isCandidate: Boolean get() = BuildConfig.CANDIDATE_BUILD
    val kind: String get() = BuildConfig.CANDIDATE_KIND.ifBlank { "review" }
    val label: String get() = BuildConfig.CANDIDATE_LABEL.ifBlank { "Local review" }
    val sourceRef: String get() = BuildConfig.CANDIDATE_SOURCE_REF.ifBlank { "local" }
    val sourceSha: String get() = BuildConfig.CANDIDATE_SOURCE_SHA.ifBlank { "unknown" }
    val shortSha: String get() = sourceSha.take(12)

    val heading: String
        get() = when (kind.lowercase()) {
            "rc", "release-candidate" -> "RELEASE CANDIDATE"
            else -> "REVIEW CANDIDATE"
        }

    val provenance: String
        get() = listOf(label, shortSha)
            .filter { it.isNotBlank() && it != "unknown" }
            .joinToString(" · ")
}
