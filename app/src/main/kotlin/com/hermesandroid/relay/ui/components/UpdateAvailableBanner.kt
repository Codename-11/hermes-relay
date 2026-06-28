package com.hermesandroid.relay.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hermesandroid.relay.BuildConfig
import com.hermesandroid.relay.update.UpdateAvailabilitySource
import com.hermesandroid.relay.update.UpdateDismissalPreferences
import com.hermesandroid.relay.update.UpdateStatus
import com.hermesandroid.relay.update.createUpdateAvailabilitySource
import com.hermesandroid.relay.update.dismissKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Auto-check interval — both flavors. App cold-starts / resumes more often
 * than this don't need a fresh Play/GitHub round-trip.
 */
private const val AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

/**
 * Debug-only injected status for previewing [UpdateAvailableBanner] from
 * Developer options — the real Play / GitHub sources can't be triggered without
 * an actual new release. Honoured by [rememberUpdateAvailability] ONLY in debug
 * builds, and cleared the moment the previewed banner is actioned or dismissed.
 * Never read in release builds.
 */
object UpdateDebugOverride {
    val flow = MutableStateFlow<UpdateStatus?>(null)

    /** Cycle the preview: off → Available → Downloaded → off. */
    fun cycle() {
        flow.value = when (flow.value) {
            null -> UpdateStatus.Available(versionLabel = "9.9.9", versionCode = 999_999L)
            is UpdateStatus.Available -> UpdateStatus.Downloaded(versionLabel = "9.9.9", versionCode = 999_999L)
            else -> null
        }
    }

    fun clear() {
        flow.value = null
    }
}

/**
 * Handle returned by [rememberUpdateAvailability] for the host
 * (`RelayApp.kt`) to drive the banner. The scaffold renders
 * [UpdateAvailableBanner] when [visibleStatus] is a surfaceable status, and
 * calls [onUpdateClick] / [onDismiss] from the banner's actions.
 *
 * `visibleStatus` is already filtered through the per-version dismiss
 * preference: a dismissed [UpdateStatus.Available] reads as null here, but a
 * [UpdateStatus.Downloaded] (FLEXIBLE finished while in-app) is intentionally
 * NOT suppressible — "restart to finish" should always be offered.
 */
class UpdateAvailabilityHandle internal constructor(
    val visibleStatus: State<UpdateStatus?>,
    /**
     * Primary banner action. For [UpdateStatus.Downloaded] this completes +
     * restarts (Play); otherwise it starts the update (Play FLEXIBLE flow /
     * sideload browser open). The hosting Activity is captured internally by
     * [rememberUpdateAvailability] — the coordinator just calls this.
     */
    val onUpdateClick: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * Lifecycle-bound entry point the coordinator wires once from inside the
 * `RelayApp` composable. Builds the per-flavor [UpdateAvailabilitySource]
 * (googlePlay = Play In-App Update FLEXIBLE; sideload = GitHub releases),
 * throttle-checks on first composition + every ON_RESUME, listens for the
 * async Play DOWNLOADED transition, and exposes a [UpdateAvailabilityHandle].
 *
 * Wiring (host side, NOT done here):
 * ```
 * val update = rememberUpdateAvailability()
 * val status by update.visibleStatus
 * // inside the top overlay Column, alongside ConnectionStatusToast:
 * AnimatedVisibility(visible = status != null && !suppressGlobalChrome && …) {
 *     status?.let { UpdateAvailableBanner(
 *         status = it,
 *         onUpdate = { update.onUpdateClick(activity) },
 *         onDismiss = update.onDismiss,
 *     ) }
 * }
 * ```
 *
 * Place the call near the other `viewModel()` hoists at the top of `RelayApp`;
 * render the banner in the existing floating top-overlay Column so it slides
 * over content without resizing it (same treatment as the connection toast).
 */
@Composable
fun rememberUpdateAvailability(): UpdateAvailabilityHandle {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    // Resolve the hosting Activity for the Play FLEXIBLE consent dialog.
    // Tracked live so a config-change recomposition re-binds the new Activity.
    val activityState = rememberUpdatedState(context.findActivity())

    val source = remember(appContext) { createUpdateAvailabilitySource(appContext) }

    // Raw, unfiltered status from the source (check result + async listener).
    var rawStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.UpToDate) }

    // Per-version dismissal. dismissedKey is observed so a fresh dismiss takes
    // effect immediately; a strictly-newer offer re-shows automatically.
    val dismissedKey by UpdateDismissalPreferences
        .dismissedKey(appContext)
        .collectAsState(initial = null)

    // Debug-only preview override (Developer options → Test harness). Forced to
    // null in release builds so production never surfaces a fake banner.
    val debugOverride by UpdateDebugOverride.flow.collectAsState()
    val debugOverrideState = rememberUpdatedState(if (BuildConfig.DEBUG) debugOverride else null)

    // Visible status = raw, but Available/Downloading suppressed when dismissed.
    // Downloaded is never suppressed (restart prompt must always show).
    // derivedStateOf tracks both snapshot inputs (rawStatus + the collected
    // dismissedKey) so the handle (built once) reads live updates. The
    // dismiss check is a pure function (no I/O), safe inside the derivation.
    val dismissedKeyState = rememberUpdatedState(dismissedKey)
    val visibleStatus = remember {
        derivedStateOf {
            val dbg = debugOverrideState.value
            if (dbg != null) {
                dbg
            } else {
                when (val raw = rawStatus) {
                    UpdateStatus.UpToDate, UpdateStatus.Unsupported -> null
                    is UpdateStatus.Downloaded -> raw
                    is UpdateStatus.Available, is UpdateStatus.Downloading ->
                        if (UpdateDismissalPreferences.isDismissed(raw, dismissedKeyState.value)) {
                            null
                        } else {
                            raw
                        }
                }
            }
        }
    }

    // Async Play listener (DOWNLOADED / DOWNLOADING) feeds rawStatus directly.
    DisposableEffect(source) {
        source.onStatusChanged = { newStatus -> rawStatus = newStatus }
        onDispose { source.dispose() }
    }

    // Throttled check: once on first composition, then on every ON_RESUME. The
    // throttle (maybeCheck) no-ops unless the auto-check interval has elapsed,
    // so the initial check + resume checks don't double-hit Play/GitHub.
    val sourceState = rememberUpdatedState(source)
    LaunchedEffect(source) {
        maybeCheck(appContext, sourceState.value) { rawStatus = it }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { maybeCheck(appContext, sourceState.value) { rawStatus = it } }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember(source) {
        UpdateAvailabilityHandle(
            visibleStatus = visibleStatus,
            onUpdateClick = {
                if (UpdateDebugOverride.flow.value != null) {
                    // Preview mode — the action just dismisses the fake banner.
                    UpdateDebugOverride.clear()
                } else {
                    val current = visibleStatus.value
                    if (current is UpdateStatus.Downloaded) {
                        source.completeUpdate()
                    } else {
                        source.startUpdate(activityState.value)
                    }
                }
            },
            onDismiss = {
                if (UpdateDebugOverride.flow.value != null) {
                    UpdateDebugOverride.clear()
                } else {
                    visibleStatus.value?.dismissKey?.let { key ->
                        scope.launch { UpdateDismissalPreferences.dismiss(appContext, key) }
                    }
                }
            },
        )
    }
}

/** Fire a check iff the throttle window has elapsed; records the time on success. */
private suspend fun maybeCheck(
    context: Context,
    source: UpdateAvailabilitySource,
    onResult: (UpdateStatus) -> Unit,
) {
    val last = UpdateDismissalPreferences.lastCheckAtMs(context).first()
    val overdue = (System.currentTimeMillis() - last) > AUTO_CHECK_INTERVAL_MS
    if (!overdue) return
    val result = source.check()
    onResult(result)
    UpdateDismissalPreferences.markChecked(context)
}

/**
 * Walk up the ContextWrapper chain to the hosting Activity. Needed by the Play
 * FLEXIBLE flow (`startUpdateFlow` hosts its consent dialog on an Activity);
 * `LocalContext.current` inside a ComponentActivity is the activity, but the
 * direct cast can silently fail behind theme/inflater wrappers. Mirrors
 * `BridgeScreen.findActivity()`.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Render a [UpdateStatus] versionLabel for display. The sideload track passes
 * a raw semver string (e.g. "1.3.0") → "v1.3.0"; the Play track passes a
 * generic phrase (e.g. "A new version", since Play exposes only a versionCode)
 * → shown verbatim. Heuristic: prefix "v" only when the label begins with a
 * digit.
 */
private fun displayVersion(label: String): String =
    if (label.firstOrNull()?.isDigit() == true) "v$label" else label

/**
 * Shared, dismissable Material 3 update banner — serves both flavors.
 *
 * Visual treatment matches [ConnectionStatusToast]: an opaque Surface
 * (tinted container composited over the theme surface so content doesn't bleed
 * through), rounded 16dp, shadow elevation, status-bar inset. Render it inside
 * the host's floating top-overlay Box so it slides over content instead of
 * resizing it.
 *
 * Copy + primary action key off [status]:
 *  - [UpdateStatus.Available]   → "Update available" + "Update" (Play flow /
 *    browser) + dismiss (X).
 *  - [UpdateStatus.Downloading] → "Downloading update…" + progress bar, no
 *    action button (Play is working); dismiss still available.
 *  - [UpdateStatus.Downloaded]  → "Update ready — restart" + "Restart"
 *    (completeUpdate). No dismiss — finishing the install is the only sane
 *    next step, and Play has already staged the APK.
 *
 * [UpdateStatus.UpToDate] / [Unsupported] render nothing (caller should gate
 * on a non-null visible status, but this guards defensively).
 */
@Composable
fun UpdateAvailableBanner(
    status: UpdateStatus,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    includeStatusBarPadding: Boolean = true,
) {
    val surface = MaterialTheme.colorScheme.surface
    val containerColor = MaterialTheme.colorScheme.primaryContainer.compositeOver(surface)
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer

    val title: String
    val subtitle: String
    val actionLabel: String?
    val showDismiss: Boolean
    val downloading = status as? UpdateStatus.Downloading

    when (status) {
        is UpdateStatus.Available -> {
            title = "Update available"
            subtitle = "${displayVersion(status.versionLabel)} is ready to install."
            actionLabel = "Update"
            showDismiss = true
        }
        is UpdateStatus.Downloading -> {
            title = "Downloading update…"
            subtitle = displayVersion(status.versionLabel)
            actionLabel = null
            showDismiss = true
        }
        is UpdateStatus.Downloaded -> {
            title = "Update ready — restart"
            subtitle = "${displayVersion(status.versionLabel)} downloaded. Restart to finish."
            actionLabel = "Restart"
            showDismiss = false
        }
        UpdateStatus.UpToDate, UpdateStatus.Unsupported -> return
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 8.dp,
        tonalElevation = 2.dp,
        modifier = modifier
            .then(
                if (includeStatusBarPadding) {
                    Modifier.windowInsetsPadding(WindowInsets.statusBars)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (downloading != null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = contentColor,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.SystemUpdate,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = contentColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.82f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (actionLabel != null) {
                    Button(
                        onClick = onUpdate,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 14.dp,
                            vertical = 4.dp,
                        ),
                    ) {
                        Text(actionLabel)
                    }
                }
                if (showDismiss) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Dismiss",
                            tint = contentColor,
                        )
                    }
                }
            }

            if (downloading != null && downloading.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = {
                        (downloading.bytesDownloaded.toFloat() /
                            downloading.totalBytes.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = contentColor.copy(alpha = 0.76f),
                    trackColor = contentColor.copy(alpha = 0.16f),
                )
            } else if (downloading != null) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = contentColor.copy(alpha = 0.76f),
                    trackColor = contentColor.copy(alpha = 0.16f),
                )
            }
        }
    }
}
