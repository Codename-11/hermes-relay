package com.hermesandroid.relay.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal subset of the GitHub "releases/latest" payload we care about.
 *
 * Full response is much larger; kotlinx.serialization's default behaviour
 * silently drops unknown keys, so we only declare the fields we read.
 */
@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
internal data class GitHubAsset(
    @SerialName("name") val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    @SerialName("size") val size: Long = 0,
)

/**
 * A parsed, UI-ready representation of an available update.
 *
 * - [apkUrl] is the direct asset URL for the sideload APK matching our
 *   artifact naming pattern `hermes-relay-<version>-sideload-release.apk`.
 *   When null, the release exists but did not publish a sideload APK (very
 *   rare — only happens if CI partially published). UI should fall back to
 *   [releasePageUrl] so the user can pick an asset manually.
 */
data class AvailableUpdate(
    val latestVersion: String,
    val currentVersion: String,
    val releasePageUrl: String,
    val apkUrl: String?,
    val publishedAt: String?,
)

sealed class UpdateCheckResult {
    data object Idle : UpdateCheckResult()
    data object Checking : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data class Available(val update: AvailableUpdate) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}
