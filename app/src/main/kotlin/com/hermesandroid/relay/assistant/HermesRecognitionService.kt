package com.hermesandroid.relay.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Platform-required recognition component for the Hermes voice interactor.
 *
 * Assistant sessions deliberately use the existing Hermes transcription
 * pipeline so wake detection, session capture, and active voice never compete
 * for the microphone. Direct SpeechRecognizer clients are therefore rejected
 * instead of opening a second recorder.
 */
class HermesRecognitionService : RecognitionService() {
    override fun onStartListening(
        recognizerIntent: Intent,
        listener: Callback,
    ) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit
}
