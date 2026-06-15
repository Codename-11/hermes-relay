package com.hermesandroid.relay.ui.components

import com.hermesandroid.relay.auth.AuthState
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerFeatureGateTest {

    @Test
    fun fromRelayAuth_unpaired_requiresPairing() {
        assertEquals(
            PowerFeatureGateStatus.RequiresPairing,
            PowerFeatureGateStatus.fromRelayAuth(AuthState.Unpaired),
        )
    }

    @Test
    fun fromRelayAuth_expiredFailure_mapsToPairingExpired() {
        assertEquals(
            PowerFeatureGateStatus.PairingExpired,
            PowerFeatureGateStatus.fromRelayAuth(AuthState.Failed("Your session expired")),
        )
    }

    @Test
    fun fromRelayAuth_tokenFailure_mapsToPairingExpired() {
        assertEquals(
            PowerFeatureGateStatus.PairingExpired,
            PowerFeatureGateStatus.fromRelayAuth(AuthState.Failed("server rejected token")),
        )
    }

    @Test
    fun fromRelayAuth_otherFailure_requiresPairing() {
        assertEquals(
            PowerFeatureGateStatus.RequiresPairing,
            PowerFeatureGateStatus.fromRelayAuth(AuthState.Failed("pairing code was invalid")),
        )
    }
}

