package com.hermesandroid.relay.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri

/**
 * === update (sideload flavor): factory ===
 *
 * Backs [UpdateAvailabilitySource] onto the existing GitHub-releases
 * [UpdateChecker]. There is no in-app download/install on the sideload track:
 * [startUpdate] opens the APK asset (or release page) in the browser and the
 * status never advances past [UpdateStatus.Available]. Mirrors
 * `voice/VoiceBridgeIntentFactory`'s flavor-split factory pattern — same
 * function signature + package as the googlePlay flavor.
 */
fun createUpdateAvailabilitySource(context: Context): UpdateAvailabilitySource =
    GitHubUpdateAvailabilitySource(context.applicationContext)

private const val TAG = "SideloadUpdate"

private class GitHubUpdateAvailabilitySource(
    private val appContext: Context,
) : UpdateAvailabilitySource {

    // Sideload reports updates synchronously from [check]; there is no async
    // listener, so this is never invoked. Present for interface parity.
    override var onStatusChanged: ((UpdateStatus) -> Unit)? = null

    /** Resolved on [check] so [startUpdate] can route to the right URL. */
    @Volatile private var pending: UpdateStatus.Available? = null

    override suspend fun check(): UpdateStatus {
        return when (val result = UpdateChecker.check()) {
            is UpdateCheckResult.Available -> {
                val upd = result.update
                val status = UpdateStatus.Available(
                    // Raw version string — doubles as the per-version dismiss
                    // key (versionCode is null on this track), so it must stay
                    // parseable by compareVersions. The banner formats display.
                    versionLabel = upd.latestVersion,
                    versionCode = null, // GitHub releases tracked by version string, not code
                    openUrl = upd.apkUrl ?: upd.releasePageUrl,
                )
                pending = status
                status
            }
            // Errors degrade to UpToDate — the banner just stays hidden, the
            // About-screen "Check for updates" row still surfaces the error.
            UpdateCheckResult.Idle,
            UpdateCheckResult.Checking,
            UpdateCheckResult.UpToDate,
            is UpdateCheckResult.Error -> {
                pending = null
                UpdateStatus.UpToDate
            }
        }
    }

    override fun startUpdate(activity: Activity?): Boolean {
        val target = pending?.openUrl ?: return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, target.toUri())
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            (activity ?: appContext).startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "startUpdate (browser) failed", t)
            false
        }
    }

    /** No staged install on sideload — the system installer handles the APK. */
    override fun completeUpdate() = Unit

    override fun dispose() {
        onStatusChanged = null
        pending = null
    }
}

// === END update (sideload) ===
