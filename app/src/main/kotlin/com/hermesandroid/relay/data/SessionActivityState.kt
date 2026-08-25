package com.hermesandroid.relay.data

/** Live activity surfaced beside a session without conflating it with selection. */
enum class SessionActivityState {
    Starting,
    Working,
    NeedsInput,
    BackgroundWork,
    Checking,
    Unavailable,
}
