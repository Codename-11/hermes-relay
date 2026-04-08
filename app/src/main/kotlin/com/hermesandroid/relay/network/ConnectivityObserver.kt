package com.hermesandroid.relay.network

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

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Status.Available)
            }
            override fun onLost(network: Network) {
                trySend(Status.Lost)
            }
            override fun onUnavailable() {
                trySend(Status.Unavailable)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)

        // Emit current state
        val activeNetwork = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        val isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        trySend(if (isConnected) Status.Available else Status.Unavailable)

        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
}
