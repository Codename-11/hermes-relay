package com.hermesandroid.relay.data

/** User-facing availability of one Hermes profile from this connection. */
enum class ProfilePresence {
    /** Its dedicated gateway is running, so channels and proactive work can stay reachable. */
    ONLINE,

    /** The host can create/resume profile-bound sessions on demand, but no profile gateway is running. */
    AVAILABLE,

    /** The host/profile cannot currently be reached from this connection. */
    OFFLINE,
}

object ProfilePresenceResolver {
    fun resolve(profile: Profile, hostReachable: Boolean = true): ProfilePresence = when {
        !hostReachable -> ProfilePresence.OFFLINE
        profile.gatewayRunning -> ProfilePresence.ONLINE
        else -> ProfilePresence.AVAILABLE
    }
}
