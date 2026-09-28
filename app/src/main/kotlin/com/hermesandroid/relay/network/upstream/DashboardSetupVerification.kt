package com.hermesandroid.relay.network.upstream

import java.io.IOException

data class DashboardSetupVerification(
    val status: DashboardStatus,
    val session: DashboardAuthSession,
    val ticketAvailable: Boolean,
) {
    val authenticated: Boolean get() = session.authenticated && ticketAvailable
}

/** Public status is discovery, not proof that a phone can use protected Dashboard routes. */
suspend fun DashboardApiClient.verifySetup(): DashboardSetupVerification {
    val status = getStatus().getOrThrow()
    var session = currentSession().getOrThrow()
    val ticketAvailable = if (session.authenticated) {
        val ticket = requestWsTicket()
        val failure = ticket.exceptionOrNull()
        if (failure?.isDashboardSignInRequiredFailure() == true) {
            session = session.copy(authenticated = false)
            false
        } else {
            ticket.getOrThrow()
            true
        }
    } else false
    if (!status.authRequired && !session.authenticated) {
        throw DashboardLocalAuthenticationRequiredException()
    }
    return DashboardSetupVerification(status, session, ticketAvailable)
}

class DashboardLocalAuthenticationRequiredException : IOException(
    "Hermes is reachable, but protected requests are not authorized. If the Dashboard is forwarded " +
        "from loopback, check its bind address, authentication provider, and dashboard.public_url " +
        "on the host. A 401 alone does not identify the cause.",
)
