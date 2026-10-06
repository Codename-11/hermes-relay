package com.hermesandroid.relay.network.shared

data class GptLiveStatus(
    val mode: String,
    val available: Boolean,
    val reason: String? = null,
    val model: String = "gpt-live-1",
    val voice: String = "marin",
)

data class GptLiveHistoryMessage(
    val role: String,
    val text: String,
)

data class GptLiveTranscriptFragment(
    val speaker: Speaker,
    val text: String,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
) {
    enum class Speaker { User, Assistant }
}

data class GptLiveCallbacks(
    val onDelegation: (delegationId: String, context: List<GptLiveTranscriptFragment>) -> Unit,
    val onTranscript: (GptLiveTranscriptFragment) -> Unit = {},
    val onSpeakingChanged: (Boolean) -> Unit = {},
    val onError: (message: String, fatal: Boolean) -> Unit = { _, _ -> },
    val onClosed: (reason: String, usageSeconds: Double?) -> Unit = { _, _ -> },
)

interface GptLiveSession {
    val sessionId: String?
    val connected: Boolean
    fun speak(delegationId: String?, content: String)
    fun think(delegationId: String?, content: String)
    fun instruct(content: String)
    fun setMuted(muted: Boolean)
    fun close()
}

interface GptLiveVoiceClient {
    suspend fun status(): Result<GptLiveStatus>

    suspend fun startSession(
        history: List<GptLiveHistoryMessage>,
        callbacks: GptLiveCallbacks,
    ): Result<GptLiveSession>
}
