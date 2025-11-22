package io.github.dorumrr.de1984.data.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import io.github.dorumrr.de1984.domain.model.NetworkType
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class NetworkStateMonitor(
    private val context: Context
) {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    companion object {
        private const val TAG = "NetworkStateMonitor"
    }

    fun observeNetworkType(): Flow<NetworkType> = callbackFlow {
        Log.d(TAG, "📡 Starting network state monitoring")

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val networkType = getCurrentNetworkType()
                Log.d(TAG, "📡 SYSTEM EVENT: Network available - type: $networkType")
                trySend(networkType)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val networkType = getCurrentNetworkType()
                Log.d(TAG, "📡 SYSTEM EVENT: Network capabilities changed - type: $networkType")
                trySend(networkType)
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "📡 SYSTEM EVENT: Network lost - type: NONE")
                trySend(NetworkType.NONE)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)

        val initialType = getCurrentNetworkType()
        Log.d(TAG, "📡 Initial network type: $initialType")
        trySend(initialType)

        awaitClose {
            Log.d(TAG, "📡 Stopping network state monitoring")
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()
    
    fun getCurrentNetworkType(): NetworkType {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkType.NONE
        
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                NetworkType.WIFI
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) {
                    NetworkType.MOBILE
                } else {
                    NetworkType.ROAMING
                }
            }
            else -> NetworkType.NONE
        }
    }
    
    fun isWiFi(): Boolean = getCurrentNetworkType() == NetworkType.WIFI
    
    fun isMobile(): Boolean = getCurrentNetworkType() == NetworkType.MOBILE
    
    fun isRoaming(): Boolean = getCurrentNetworkType() == NetworkType.ROAMING
    
    fun isConnected(): Boolean = getCurrentNetworkType() != NetworkType.NONE
}

