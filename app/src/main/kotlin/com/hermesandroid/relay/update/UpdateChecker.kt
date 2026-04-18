package com.hermesandroid.relay.update

import com.hermesandroid.relay.BuildConfig
import com.hermesandroid.relay.data.BuildFlavor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Sideload-only update checker.
 *
 * Fetches the latest GitHub release for this repo and returns an
 * [UpdateCheckResult]. Callers on `googlePlay` builds should treat this
 * as a no-op — the Play Store handles update delivery and the repo's
 * GitHub Releases may expose different artifacts.
 *
 * Network + JSON runs on [Dispatchers.IO].
 *
 * Why a bespoke OkHttpClient instead of the relay/API shared ones:
 * - No bearer, no cert-pin — this is plain HTTPS to api.github.com.
 * - Short timeouts (5s/5s/5s) — GitHub's releases endpoint is fast; we
 *   don't want to sit on app startup waiting for a flaky connection.
 */
object UpdateChecker {
    private const val REPO_OWNER = "Codename-11"
    private const val REPO_NAME  = "hermes-relay"
    private const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    private const val USER_AGENT = "hermes-relay-android"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Fetch the latest release and compare with the running build.
     *
     * On `googlePlay` flavour this always returns [UpdateCheckResult.UpToDate]
     * because the Play Store track owns update delivery — we don't want the
     * sideload banner showing up on Play installs.
     */
    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!BuildFlavor.isSideload) {
            return@withContext UpdateCheckResult.UpToDate
        }

        val request = Request.Builder()
            .url(RELEASES_LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "$USER_AGENT/${BuildConfig.VERSION_NAME}")
            .build()

        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext UpdateCheckResult.Error(
                        "GitHub returned HTTP ${resp.code}"
                    )
                }
                val body = resp.body?.string()
                    ?: return@withContext UpdateCheckResult.Error("Empty response body")
                val release = json.decodeFromString<GitHubRelease>(body)

                val latest = release.tagName
                val current = BuildConfig.VERSION_NAME
                val cmp = compareVersions(current, latest)
                if (cmp >= 0) {
                    return@withContext UpdateCheckResult.UpToDate
                }

                val apkAsset = findSideloadApkAsset(release.assets)
                return@withContext UpdateCheckResult.Available(
                    AvailableUpdate(
                        latestVersion = latest.removePrefix("v"),
                        currentVersion = current,
                        releasePageUrl = release.htmlUrl,
                        apkUrl = apkAsset?.browserDownloadUrl,
                        publishedAt = release.publishedAt,
                    )
                )
            }
        } catch (t: Throwable) {
            return@withContext UpdateCheckResult.Error(
                t.message ?: t.javaClass.simpleName
            )
        }
    }

    /**
     * Find the sideload APK asset. Our release artifacts are named
     * `hermes-relay-<version>-sideload-release.apk` via `archivesName` in
     * `app/build.gradle.kts` — see RELEASE.md.
     */
    private fun findSideloadApkAsset(assets: List<GitHubAsset>): GitHubAsset? {
        return assets.firstOrNull { asset ->
            val n = asset.name.lowercase()
            n.endsWith(".apk") && n.contains("sideload")
        }
    }
}
