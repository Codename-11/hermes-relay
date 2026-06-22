package com.hermesandroid.relay.update

import android.app.Activity

/**
 * Flavor-agnostic "is there a newer version?" abstraction.
 *
 * Two implementations exist, one per product flavor, each exporting a
 * [createUpdateAvailabilitySource] factory with the identical signature +
 * package (mirroring `voice/VoiceBridgeIntentFactory`'s
 * `createVoiceBridgeIntentHandler` pattern):
 *
 *  - **googlePlay** — backs onto Google Play's In-App Update API
 *    (`AppUpdateManager`), FLEXIBLE flow. [check] reports
 *    [UpdateStatus.Available] when Play has a newer build; [startUpdate]
 *    kicks off the in-app download dialog; an `InstallStateUpdatedListener`
 *    flips the state to [UpdateStatus.Downloaded] once the APK is staged, at
 *    which point the banner offers "Restart to finish" → [completeUpdate].
 *
 *  - **sideload** — wraps the existing GitHub-releases [UpdateChecker]. There
 *    is no in-app download/install on this track, so [startUpdate] opens the
 *    APK/release URL in the browser and the status never reaches
 *    [UpdateStatus.Downloaded].
 *
 * The shared `UpdateAvailableBanner` + `rememberUpdateAvailability` entry
 * point in the UI layer drive both through this one interface.
 *
 * Threading: [check] suspends and is expected to run its own IO hop
 * internally; callers may invoke it from any dispatcher.
 */
interface UpdateAvailabilitySource {

    /**
     * Probe for an update. Returns the current [UpdateStatus]. Implementations
     * MUST swallow their own transport/availability failures and degrade to
     * [UpdateStatus.UpToDate] (or [UpdateStatus.Unsupported]) rather than
     * throwing — a flaky network or a Play-less device must never crash the
     * caller. A transient error maps to [UpdateStatus.UpToDate] so the banner
     * simply stays hidden until the next check.
     */
    suspend fun check(): UpdateStatus

    /**
     * Begin the update.
     *
     * - googlePlay: launches the Play FLEXIBLE consent + background-download
     *   flow. Needs a foreground [activity] to host Play's dialog. Returns
     *   `true` if the flow was started (or already running), `false` if it
     *   could not be launched (no activity / Play unavailable).
     * - sideload: opens the APK or release page in the browser. [activity]
     *   may be null; returns `true` if an intent was dispatched.
     *
     * Safe to call repeatedly — implementations no-op if a flow is already in
     * flight.
     */
    fun startUpdate(activity: Activity?): Boolean

    /**
     * Finish a FLEXIBLE update that has finished downloading (state is
     * [UpdateStatus.Downloaded]). googlePlay calls `AppUpdateManager.
     * completeUpdate()` which restarts the app to swap in the new APK.
     * sideload is a no-op (its install is handled by the system installer
     * after the browser download).
     */
    fun completeUpdate()

    /**
     * Optional hook for the host to learn about asynchronous status changes
     * that happen *outside* a [check] — specifically the Play FLEXIBLE
     * download completing while the user is in the app. The googlePlay impl
     * pushes [UpdateStatus.Downloaded] (and download progress as
     * [UpdateStatus.Downloading]) here via its install-state listener; the
     * sideload impl never invokes it. Set to null to detach.
     */
    var onStatusChanged: ((UpdateStatus) -> Unit)?

    /**
     * Release any registered listeners / resources. The host calls this from
     * a Compose `DisposableEffect` `onDispose`. Idempotent.
     */
    fun dispose()
}

/**
 * Flavor-agnostic update availability state.
 *
 * `versionLabel` is a human-readable string for the banner ("1.3.0" /
 * "android-v1.3.0"); `versionCode` is the numeric Play versionCode when known
 * (googlePlay) and null on sideload (GitHub releases are tracked by version
 * string, not code). [Available] also carries an opaque [openUrl] the sideload
 * impl uses to route `startUpdate` to the browser; googlePlay leaves it null.
 */
sealed class UpdateStatus {

    /** No newer version, Play/GitHub unreachable-but-degraded, or not yet checked. */
    data object UpToDate : UpdateStatus()

    /** This flavor/device can't surface an update at all (e.g. Play services absent). */
    data object Unsupported : UpdateStatus()

    /** A newer version exists and the user can start the update. */
    data class Available(
        val versionLabel: String,
        val versionCode: Long? = null,
        /** Browser fallback target for sideload (APK asset or release page). Null on Play. */
        val openUrl: String? = null,
    ) : UpdateStatus()

    /**
     * googlePlay FLEXIBLE download in progress. [bytesDownloaded] /
     * [totalBytes] may be 0 before Play reports sizes; the banner shows an
     * indeterminate bar until [totalBytes] is positive.
     */
    data class Downloading(
        val versionLabel: String,
        val versionCode: Long? = null,
        val bytesDownloaded: Long = 0,
        val totalBytes: Long = 0,
    ) : UpdateStatus()

    /**
     * googlePlay FLEXIBLE update finished downloading and is staged; calling
     * [UpdateAvailabilitySource.completeUpdate] restarts the app to install.
     */
    data class Downloaded(
        val versionLabel: String,
        val versionCode: Long? = null,
    ) : UpdateStatus()
}

/**
 * The dismissal-relevant identity of an available update — the value the
 * per-version dismiss preference keys on. Play builds key on the numeric
 * versionCode (monotonic, unambiguous); sideload keys on the version string.
 * A *newer* identity than the dismissed one re-shows the banner (see
 * [UpdateDismissalPreferences]).
 */
val UpdateStatus.dismissKey: String?
    get() = when (this) {
        is UpdateStatus.Available -> versionCode?.toString() ?: versionLabel
        is UpdateStatus.Downloading -> versionCode?.toString() ?: versionLabel
        is UpdateStatus.Downloaded -> versionCode?.toString() ?: versionLabel
        UpdateStatus.UpToDate, UpdateStatus.Unsupported -> null
    }
