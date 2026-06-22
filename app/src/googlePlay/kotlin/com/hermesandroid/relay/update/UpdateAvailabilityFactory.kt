package com.hermesandroid.relay.update

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * === update (googlePlay flavor): factory ===
 *
 * Backs [UpdateAvailabilitySource] onto Google Play's In-App Update API,
 * FLEXIBLE flow. Mirrors `voice/VoiceBridgeIntentFactory`'s flavor-split
 * factory pattern: both flavors export this exact function signature +
 * package, so the UI layer has one static call site and no reflection / no
 * `#if` gating.
 */
fun createUpdateAvailabilitySource(context: Context): UpdateAvailabilitySource =
    PlayUpdateAvailabilitySource(context.applicationContext)

private const val TAG = "PlayUpdate"

/**
 * Google Play FLEXIBLE in-app update source.
 *
 * - [check] queries `AppUpdateManager.appUpdateInfo`. If Play reports
 *   `UPDATE_AVAILABLE` and FLEXIBLE is allowed, returns [UpdateStatus.Available]
 *   (or [UpdateStatus.Downloaded] / [UpdateStatus.Downloading] if a previously
 *   started flexible update is already mid-flight). Anything else →
 *   [UpdateStatus.UpToDate].
 * - [startUpdate] launches Play's FLEXIBLE consent + background download and
 *   registers an [InstallStateUpdatedListener] so DOWNLOADED is reported back
 *   asynchronously via [onStatusChanged].
 * - [completeUpdate] calls `AppUpdateManager.completeUpdate()` which restarts
 *   the app to install the staged APK.
 *
 * Robustness: every Play interaction is wrapped in try/catch. On any failure
 * (no Play services, sideloaded "googlePlay" build on an AOSP device, RESULT
 * errors) it degrades to [UpdateStatus.UpToDate] / [UpdateStatus.Unsupported]
 * — the banner just never shows. Play is never a crash surface.
 */
private class PlayUpdateAvailabilitySource(
    private val appContext: Context,
) : UpdateAvailabilitySource {

    override var onStatusChanged: ((UpdateStatus) -> Unit)? = null

    private val manager: AppUpdateManager? = runCatching {
        AppUpdateManagerFactory.create(appContext)
    }.getOrNull()

    /** Cached label/code from the last [check] so async listener events can label themselves. */
    @Volatile private var lastVersionCode: Long? = null

    private val installListener = InstallStateUpdatedListener { state: InstallState ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADING ->
                onStatusChanged?.invoke(
                    UpdateStatus.Downloading(
                        versionLabel = labelFor(lastVersionCode),
                        versionCode = lastVersionCode,
                        // bytesDownloaded()/totalBytesToDownload() are base
                        // app-update InstallState methods (Long); no ktx import.
                        bytesDownloaded = state.bytesDownloaded(),
                        totalBytes = state.totalBytesToDownload(),
                    )
                )
            InstallStatus.DOWNLOADED ->
                onStatusChanged?.invoke(
                    UpdateStatus.Downloaded(
                        versionLabel = labelFor(lastVersionCode),
                        versionCode = lastVersionCode,
                    )
                )
            else -> Unit // INSTALLING / INSTALLED / FAILED / CANCELED → no banner change
        }
    }

    @Volatile private var listenerRegistered = false

    override suspend fun check(): UpdateStatus {
        val mgr = manager ?: return UpdateStatus.Unsupported
        return try {
            val info = mgr.awaitAppUpdateInfo()
            lastVersionCode = info.availableVersionCode().toLong()
            when {
                // A previously started FLEXIBLE update already finished downloading.
                info.installStatus() == InstallStatus.DOWNLOADED -> {
                    ensureListener(mgr)
                    UpdateStatus.Downloaded(
                        versionLabel = labelFor(lastVersionCode),
                        versionCode = lastVersionCode,
                    )
                }
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS ||
                    info.installStatus() == InstallStatus.DOWNLOADING -> {
                    ensureListener(mgr)
                    UpdateStatus.Downloading(
                        versionLabel = labelFor(lastVersionCode),
                        versionCode = lastVersionCode,
                    )
                }
                info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ->
                    UpdateStatus.Available(
                        versionLabel = labelFor(lastVersionCode),
                        versionCode = lastVersionCode,
                        openUrl = null,
                    )
                else -> UpdateStatus.UpToDate
            }
        } catch (t: Throwable) {
            Log.w(TAG, "appUpdateInfo check failed; treating as up-to-date", t)
            UpdateStatus.UpToDate
        }
    }

    override fun startUpdate(activity: Activity?): Boolean {
        val mgr = manager ?: return false
        if (activity == null) return false
        return try {
            ensureListener(mgr)
            mgr.appUpdateInfo
                .addOnSuccessListener { info: AppUpdateInfo ->
                    val canStart = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                    val resuming = info.updateAvailability() ==
                        UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                    if (canStart || resuming) {
                        runCatching {
                            mgr.startUpdateFlow(
                                info,
                                activity,
                                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                            )
                        }.onFailure { Log.w(TAG, "startUpdateFlow failed", it) }
                    }
                }
                .addOnFailureListener { Log.w(TAG, "startUpdate appUpdateInfo failed", it) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "startUpdate failed", t)
            false
        }
    }

    override fun completeUpdate() {
        val mgr = manager ?: return
        runCatching { mgr.completeUpdate() }
            .onFailure { Log.w(TAG, "completeUpdate failed", it) }
    }

    override fun dispose() {
        val mgr = manager ?: return
        if (listenerRegistered) {
            runCatching { mgr.unregisterListener(installListener) }
            listenerRegistered = false
        }
        onStatusChanged = null
    }

    private fun ensureListener(mgr: AppUpdateManager) {
        if (!listenerRegistered) {
            runCatching { mgr.registerListener(installListener) }
                .onSuccess { listenerRegistered = true }
                .onFailure { Log.w(TAG, "registerListener failed", it) }
        }
    }

    // Play exposes only the numeric versionCode, not a marketing version
    // string, so the banner copy stays generic ("A new version"). The code is
    // still carried on the status for per-version dismissal keying.
    private fun labelFor(@Suppress("UNUSED_PARAMETER") code: Long?): String = "A new version"
}

// === END update (googlePlay) ===

/**
 * `await()` for Play's [AppUpdateInfo] task without pulling in
 * `kotlinx-coroutines-play-services`. Named `await…` (not the ktx
 * `requestAppUpdateInfo`) to avoid any overload ambiguity with the
 * `app-update-ktx` suspend extension. Resumable + cancels cleanly if the
 * coroutine is torn down.
 */
private suspend fun AppUpdateManager.awaitAppUpdateInfo(): AppUpdateInfo =
    suspendCancellableCoroutine { cont ->
        appUpdateInfo
            .addOnSuccessListener { info -> if (cont.isActive) cont.resume(info) }
            .addOnFailureListener { e -> if (cont.isActive) cont.cancel(e) }
    }
