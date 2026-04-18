package com.hermesandroid.relay.viewmodel

import android.app.Application
import android.media.audiofx.AcousticEchoCanceler
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesandroid.relay.data.BargeInPreferences
import com.hermesandroid.relay.data.BargeInPreferencesRepository
import com.hermesandroid.relay.data.BargeInSensitivity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * View-model backing the Voice Settings screen.
 *
 * Introduced with the voice-barge-in plan (unit B5). Before this, the screen
 * constructed its [com.hermesandroid.relay.data.VoicePreferencesRepository]
 * directly inside the composable — fine for a handful of scalar prefs, but
 * the barge-in section also needs a once-per-screen
 * [AcousticEchoCanceler.isAvailable] probe to decide whether to show the
 * compatibility warning badge. That probe belongs in a VM so it doesn't
 * re-run on every recomposition.
 *
 * The VM currently owns only barge-in state; the rest of the screen still
 * talks to [com.hermesandroid.relay.data.VoicePreferencesRepository]
 * directly. Future cleanup can migrate those too.
 */
class VoiceSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val bargeInRepo = BargeInPreferencesRepository(application)

    /** Current barge-in preferences — mirrors [BargeInPreferencesRepository.flow]. */
    val bargeInPrefs: StateFlow<BargeInPreferences> = bargeInRepo.flow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BargeInPreferences(),
    )

    /**
     * One-shot probe of [AcousticEchoCanceler.isAvailable] captured at VM
     * construction. The value never changes at runtime on a given device, so
     * we cache it instead of querying on every recomposition. The barge-in
     * section uses this to decide whether to show the "limited echo
     * cancellation" warning badge.
     */
    val aecAvailable: Boolean = AcousticEchoCanceler.isAvailable()

    fun setBargeInEnabled(enabled: Boolean) {
        viewModelScope.launch { bargeInRepo.setEnabled(enabled) }
    }

    fun setBargeInSensitivity(sensitivity: BargeInSensitivity) {
        viewModelScope.launch { bargeInRepo.setSensitivity(sensitivity) }
    }

    fun setResumeAfterInterruption(enabled: Boolean) {
        viewModelScope.launch { bargeInRepo.setResumeAfterInterruption(enabled) }
    }
}
