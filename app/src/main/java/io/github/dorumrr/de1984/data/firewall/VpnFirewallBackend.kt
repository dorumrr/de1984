package io.github.dorumrr.de1984.data.firewall

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import io.github.dorumrr.de1984.data.service.FirewallVpnService
import io.github.dorumrr.de1984.domain.firewall.FirewallBackend
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.model.NetworkType

/**
 * VPN-based firewall backend.
 * 
 * Wraps the existing FirewallVpnService implementation.
 * Uses Android VpnService API with inverted logic (addAllowedApplication to block).
 * 
 * Features:
 * - No root required
 * - Per-app blocking
 * - Network type-specific rules (WiFi/Mobile/Roaming)
 * - Screen state-specific rules
 * 
 * Limitations:
 * - Occupies VPN slot (cannot use real VPN alongside)
 * - Requires VPN permission from user
 */
class VpnFirewallBackend(
    private val context: Context
) : FirewallBackend {
    
    companion object {
        private const val TAG = "VpnFirewall"
    }
    
    override suspend fun start(): Result<Unit> {
        return try {
            Log.d(TAG, "Starting VPN firewall backend")
            
            val intent = Intent(context, FirewallVpnService::class.java).apply {
                action = FirewallVpnService.ACTION_START
            }
            context.startService(intent)
            
            Log.d(TAG, "VPN firewall backend start requested")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN firewall", e)
            Result.failure(e)
        }
    }
    
    override suspend fun stop(): Result<Unit> {
        return try {
            Log.d(TAG, "Stopping VPN firewall backend")
            
            val intent = Intent(context, FirewallVpnService::class.java).apply {
                action = FirewallVpnService.ACTION_STOP
            }
            context.startService(intent)
            
            Log.d(TAG, "VPN firewall backend stop requested")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VPN firewall", e)
            Result.failure(e)
        }
    }
    
    override suspend fun applyRules(
        rules: List<FirewallRule>,
        networkType: NetworkType,
        screenOn: Boolean
    ): Result<Unit> {
        // VPN service handles rule application automatically via:
        // 1. Listening to FIREWALL_RULES_CHANGED broadcast
        // 2. Monitoring network type changes via NetworkStateMonitor
        // 3. Monitoring screen state changes via ScreenStateMonitor
        // 
        // No explicit action needed here - the service is reactive
        Log.d(TAG, "VPN backend applies rules automatically via service")
        return Result.success(Unit)
    }
    
    override fun isActive(): Boolean {
        // Check if OUR VPN service is running by checking SharedPreferences flag
        // (FirewallVpnService updates this when starting/stopping)
        return try {
            val prefs = context.getSharedPreferences(
                io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
                Context.MODE_PRIVATE
            )
            val isServiceRunning = prefs.getBoolean(
                io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_SERVICE_RUNNING,
                false
            )

            // Also verify that a VPN is actually active (double-check)
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val hasVpnConnection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = connectivityManager.activeNetwork
                val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo?.type == ConnectivityManager.TYPE_VPN
            }

            // Both conditions must be true: service flag set AND VPN connection active
            isServiceRunning && hasVpnConnection
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check if VPN is active", e)
            false
        }
    }
    
    override fun getType(): FirewallBackendType = FirewallBackendType.VPN
    
    override suspend fun checkAvailability(): Result<Unit> {
        // VPN is always available on Android (no special requirements)
        // User just needs to grant VPN permission when starting
        return Result.success(Unit)
    }
}

