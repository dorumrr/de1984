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
 * - Granular network type-specific rules (WiFi/Mobile/Roaming)
 * - Screen state-specific rules
 * - Automatically reconfigures when network type changes
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
            val intent = Intent(context, FirewallVpnService::class.java).apply {
                action = FirewallVpnService.ACTION_START
            }
            context.startService(intent)

            Log.d(TAG, "VPN firewall started")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN firewall", e)
            Result.failure(e)
        }
    }
    
    override suspend fun stop(): Result<Unit> {
        return try {
            // Send stop intent to FirewallVpnService
            val intent = Intent(context, FirewallVpnService::class.java).apply {
                action = FirewallVpnService.ACTION_STOP
            }
            context.startService(intent)

            Log.d(TAG, "VPN stop intent sent, waiting for service to stop...")

            // Wait for service to actually stop by polling isActive()
            // The SharedPreferences flag is updated immediately (in-memory) when stopVpn() is called,
            // so this should be very fast (usually 1-2 checks, ~10-50ms)
            val startTime = System.currentTimeMillis()
            val timeout = 2000L
            var attempts = 0

            while (isActive()) {
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= timeout) {
                    Log.w(TAG, "VPN service still active after ${elapsed}ms (timeout). Continuing anyway.")
                    break
                }
                attempts++
                kotlinx.coroutines.delay(50)  // Check every 50ms
            }

            val totalTime = System.currentTimeMillis() - startTime
            if (attempts > 0) {
                Log.d(TAG, "VPN service stopped after ${totalTime}ms ($attempts checks)")
            } else {
                Log.d(TAG, "VPN service already stopped (${totalTime}ms)")
            }

            // Additional small delay to ensure VPN interface is fully closed
            // ParcelFileDescriptor.close() might take 100-500ms even after service stops
            kotlinx.coroutines.delay(200)

            Log.d(TAG, "VPN firewall stopped")
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
        return Result.success(Unit)
    }
    
    override fun isActive(): Boolean {
        return try {
            val prefs = context.getSharedPreferences(
                io.github.dorumrr.de1984.utils.Constants.Settings.PREFS_NAME,
                Context.MODE_PRIVATE
            )

            // Check if service is running
            val isServiceRunning = prefs.getBoolean(
                io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_SERVICE_RUNNING,
                false
            )

            if (!isServiceRunning) {
                return false
            }

            // Check if VPN interface is active
            val isInterfaceActive = prefs.getBoolean(
                io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_INTERFACE_ACTIVE,
                false
            )

            // Verify service is actually alive
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (activityManager != null) {
                @Suppress("DEPRECATION")
                val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
                val serviceClassName = "io.github.dorumrr.de1984.data.service.FirewallVpnService"
                val isServiceActuallyRunning = runningServices.any { service ->
                    service.service.className == serviceClassName
                }

                if (!isServiceActuallyRunning) {
                    Log.w(TAG, "Service not actually running. Clearing flags.")
                    prefs.edit()
                        .putBoolean(io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_SERVICE_RUNNING, false)
                        .putBoolean(io.github.dorumrr.de1984.utils.Constants.Settings.KEY_VPN_INTERFACE_ACTIVE, false)
                        .apply()
                    return false
                }

                return isInterfaceActive
            }

            // Fallback: trust SharedPreferences
            return isServiceRunning && isInterfaceActive
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

    override fun supportsGranularControl(): Boolean = true  // Supports WiFi/Mobile/Roaming granular control
}

