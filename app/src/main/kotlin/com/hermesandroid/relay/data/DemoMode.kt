package com.hermesandroid.relay.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Offline **Demo / Explore mode** state holder.
 *
 * Plain Kotlin (no Android, no network, no coroutines side-effects) so it can
 * be unit-tested on the pure JVM and owned by the Activity-scoped
 * [com.hermesandroid.relay.viewmodel.ConnectionViewModel] without dragging
 * framework dependencies into the demo path. The ViewModel delegates
 * `isDemoMode` to [active] and pushes [transcript] into the real `ChatHandler`
 * so the canned conversation renders through the production chat UI.
 *
 * Lifecycle: [enter] flips [active] true and loads the canned [DemoContent]
 * transcript; [exit] flips it false and clears the transcript. Entering demo
 * must **never** mark onboarding complete or start a connection — the
 * ViewModel's network entry points early-return while [active] is true (see
 * `reconnectIfStale` / `revalidate` / `connectRelay`).
 *
 * @param transcriptFactory source of the demo transcript. Defaults to
 *   [DemoContent.transcript]; overridable in tests.
 */
class DemoMode(
    private val transcriptFactory: () -> List<ChatMessage> = DemoContent::transcript,
) {
    private val _active = MutableStateFlow(false)
    /** True while the offline demo is active. Drives the banner + network gates. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _transcript = MutableStateFlow<List<ChatMessage>>(emptyList())
    /** The canned conversation while [active]; empty otherwise. */
    val transcript: StateFlow<List<ChatMessage>> = _transcript.asStateFlow()

    /** Enter demo: load the canned transcript, then mark active. Idempotent. */
    fun enter() {
        _transcript.value = transcriptFactory()
        _active.value = true
    }

    /** Exit demo: clear active, then drop the transcript. Idempotent. */
    fun exit() {
        _active.value = false
        _transcript.value = emptyList()
    }
}
