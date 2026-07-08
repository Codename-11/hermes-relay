package com.hermesandroid.relay.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ShareReviewUiState(
    val payload: SharePayload,
    val selectedAction: ShareAction = ShareAction.Save,
    val captureId: String? = null,
    val isCreating: Boolean = true,
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
) {
    val canSubmit: Boolean
        get() = captureId != null && !isCreating && !isSubmitting && successMessage == null
}

class ShareViewModel(
    private val payload: SharePayload,
    private val sender: ShareCaptureContract,
    private val testScope: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope
        get() = testScope ?: viewModelScope

    private val _state = MutableStateFlow(ShareReviewUiState(payload = payload))
    val state: StateFlow<ShareReviewUiState> = _state.asStateFlow()

    init {
        createDurableCapture()
    }

    fun selectAction(action: ShareAction) {
        _state.value = _state.value.copy(selectedAction = action, errorMessage = null)
    }

    fun submitSelectedAction() {
        val current = _state.value
        val captureId = current.captureId ?: return
        if (current.isSubmitting || current.successMessage != null) return
        _state.value = current.copy(isSubmitting = true, errorMessage = null)
        scope.launch {
            sender.actOnCapture(captureId, current.selectedAction)
                .onSuccess { result ->
                    if (result.isTerminalSuccess) {
                        _state.value = _state.value.copy(
                            isSubmitting = false,
                            successMessage = "Capture ${result.status}.",
                            errorMessage = null,
                        )
                    } else {
                        _state.value = _state.value.copy(
                            isSubmitting = false,
                            errorMessage = "Unexpected action status: ${result.status}",
                        )
                    }
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        isSubmitting = false,
                        errorMessage = error.message ?: "Failed to submit action",
                    )
                }
        }
    }

    private fun createDurableCapture() {
        scope.launch {
            sender.createCapture(payload)
                .onSuccess { capture ->
                    _state.value = _state.value.copy(
                        captureId = capture.id,
                        isCreating = false,
                        errorMessage = null,
                    )
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        isCreating = false,
                        errorMessage = error.message ?: "Failed to create capture",
                    )
                }
        }
    }
}
