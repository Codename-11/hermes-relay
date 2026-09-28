package com.hermesandroid.relay.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** An explicit HTTP exception is scoped to a connection and exact origin, never a VPN claim. */
fun dashboardHttpOrigin(address: String): String? {
    val url = address.trim().toHttpUrlOrNull() ?: return null
    if (url.scheme != "http" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.query != null || url.fragment != null
    ) return null
    return url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
}

fun dashboardHttpConsentRequired(address: String): Boolean =
    dashboardHttpOrigin(address) != null && Connection.inferRouteRole(address) == "public"

fun dashboardHttpConsentMatches(address: String, approvedOrigins: Set<String>): Boolean =
    dashboardHttpOrigin(address)?.let { it in approvedOrigins } == true

/** Editing an origin retires its old exception; another address requires its own confirmation. */
fun updatedDashboardHttpConsents(
    previous: Set<String>,
    oldAddress: String?,
    newAddress: String,
    confirmedOrigin: String?,
): Set<String> {
    val oldOrigin = oldAddress?.let(::dashboardHttpOrigin)
    val newOrigin = dashboardHttpOrigin(newAddress)
    val retained = if (oldOrigin != newOrigin) previous - setOfNotNull(oldOrigin) else previous
    return if (newOrigin != null && confirmedOrigin == newOrigin) retained + newOrigin else retained
}
