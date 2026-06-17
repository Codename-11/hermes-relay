package com.hermesandroid.relay.network.shared

import org.junit.Assert.assertSame
import org.junit.Test

class ConnectivityObserverTest {
    @Test
    fun `keeps available when one network is lost but another internet network remains`() {
        val status = statusForInternetAvailability(
            hasAnyInternetNetwork = true,
            fallbackWhenNone = ConnectivityObserver.Status.Lost,
        )

        assertSame(ConnectivityObserver.Status.Available, status)
    }

    @Test
    fun `emits lost when no internet networks remain after network loss`() {
        val status = statusForInternetAvailability(
            hasAnyInternetNetwork = false,
            fallbackWhenNone = ConnectivityObserver.Status.Lost,
        )

        assertSame(ConnectivityObserver.Status.Lost, status)
    }

    @Test
    fun `emits unavailable when initial probe finds no internet networks`() {
        val status = statusForInternetAvailability(
            hasAnyInternetNetwork = false,
            fallbackWhenNone = ConnectivityObserver.Status.Unavailable,
        )

        assertSame(ConnectivityObserver.Status.Unavailable, status)
    }
}
