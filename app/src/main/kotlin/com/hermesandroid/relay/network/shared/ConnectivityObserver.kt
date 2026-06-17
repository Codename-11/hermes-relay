package com.hermesandroid.relay.network.shared

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class ConnectivityObserver(private val context: Context) {

    sealed class Status {
        data object Available : Status()
        data object Unavailable : Status()
        data object Lost : Status()
    }

    fun observe(): Flow<Status> = callbackFlow {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        if (connectivityManager == null) {
            trySend(Status.Unavailable)
            awaitClose { }
            return@callbackFlow
        }

        @Suppress("DEPRECATION")
        fun hasAnyInternetNetwork(): Boolean =
            connectivityManager.allNetworks.any { network ->
                hasInternetCapability(connectivityManager.getNetworkCapabilities(network))
            }

        fun sendCurrentStatus(fallbackWhenNone: Status) {
            trySend(statusForInternetAvailability(hasAnyInternetNetwork(), fallbackWhenNone))
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                sendCurrentStatus(Status.Available)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                sendCurrentStatus(
                    if (hasInternetCapability(networkCapabilities)) {
                        Status.Available
                    } else {
                        Status.Lost
                    }
                )
            }

            override fun onLost(network: Network) {
                sendCurrentStatus(Status.Lost)
            }

            override fun onUnavailable() {
                sendCurrentStatus(Status.Unavailable)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)

        // Emit current state
        sendCurrentStatus(Status.Unavailable)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
}

internal fun hasInternetCapability(caps: NetworkCapabilities?): Boolean =
    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

internal fun statusForInternetAvailability(
    hasAnyInternetNetwork: Boolean,
    fallbackWhenNone: ConnectivityObserver.Status,
): ConnectivityObserver.Status =
    if (hasAnyInternetNetwork) ConnectivityObserver.Status.Available else fallbackWhenNone
