package com.hermesandroid.relay.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.data.BuildFlavor
import com.hermesandroid.relay.update.UpdateCheckResult
import com.hermesandroid.relay.update.UpdateChecker
import com.hermesandroid.relay.update.UpdatePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * Orchestrates sideload update checks + UI state.
 *
 * - [uiState] is the raw result of the most recent check (Idle on first
 *   launch, then Checking → one of UpToDate / Available / Error).
 * - [bannerState] is [uiState] filtered through the per-version dismiss
 *   preference, so the top-of-scaffold banner hides itself when the user
 *   has already tapped "Not now" on this version.
 *
 * Auto-check policy: on init, if it's been more than [AUTO_CHECK_INTERVAL_MS]
 * since the last successful check, fire one in the background. Manual
 * "Check for updates" taps always run regardless of interval.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow<UpdateCheckResult>(UpdateCheckResult.Idle)
    val uiState: StateFlow<UpdateCheckResult> = _uiState.asStateFlow()

    val bannerState: StateFlow<UpdateCheckResult> = combine(
        _uiState,
        UpdatePreferences.dismissedVersion(application),
    ) { state, dismissed ->
        if (state is UpdateCheckResult.Available && state.update.latestVersion == dismissed) {
            UpdateCheckResult.UpToDate   // treat as hidden for banner purposes
        } else {
            state
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UpdateCheckResult.Idle,
    )

    init {
        if (BuildFlavor.isSideload) {
            viewModelScope.launch {
                val last = UpdatePreferences.lastCheckAtMs(application).first()
                val overdue = (System.currentTimeMillis() - last) > AUTO_CHECK_INTERVAL_MS
                if (overdue) check()
            }
        }
    }

    /** Fire a check. Safe to call from UI — coroutines + state flow handle it. */
    fun check() {
        if (!BuildFlavor.isSideload) return
        if (_uiState.value is UpdateCheckResult.Checking) return

        viewModelScope.launch {
            _uiState.value = UpdateCheckResult.Checking
            val result = UpdateChecker.check()
            _uiState.value = result
            // Only record check time on a non-error terminal state so a
            // transient failure doesn't suppress the next retry.
            if (result is UpdateCheckResult.UpToDate || result is UpdateCheckResult.Available) {
                UpdatePreferences.markChecked(getApplication())
            }
        }
    }

    /**
     * Hide the banner for the given version until a newer one is released.
     */
    fun dismiss(version: String) {
        viewModelScope.launch {
            UpdatePreferences.dismissVersion(getApplication(), version)
        }
    }

    companion object {
        // 6 hours. App cold-starts more often than that don't need fresh
        // release data; manual "Check" always overrides.
        private const val AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
