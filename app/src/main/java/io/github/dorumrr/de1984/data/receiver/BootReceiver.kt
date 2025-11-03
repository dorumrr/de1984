package io.github.dorumrr.de1984.data.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.dorumrr.de1984.De1984Application
import io.github.dorumrr.de1984.data.service.FirewallVpnService
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "De1984.BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                restoreFirewallState(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                restoreFirewallState(context)
            }
        }

    }

    private fun restoreFirewallState(context: Context) {
        try {
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val wasEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, Constants.Settings.DEFAULT_FIREWALL_ENABLED)

            if (wasEnabled) {
                Log.d(TAG, "Restoring firewall state (was enabled)")

                // Get FirewallManager from application
                val app = context.applicationContext as? De1984Application
                if (app != null) {
                    val firewallManager = app.dependencies.firewallManager
                    val shizukuManager = app.dependencies.shizukuManager

                    // Use goAsync() to keep receiver alive while coroutine runs
                    val pendingResult = goAsync()

                    // Use coroutine to start firewall asynchronously
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    scope.launch {
                        try {
                            // Wait for Shizuku to be initialized before starting firewall
                            // This is important for ACTION_MY_PACKAGE_REPLACED (app update)
                            // where Shizuku may not be fully initialized yet
                            Log.d(TAG, "Checking Shizuku status before starting firewall...")
                            shizukuManager.checkShizukuStatus()

                            // Small delay to ensure Shizuku is fully ready
                            kotlinx.coroutines.delay(500)

                            Log.d(TAG, "Starting firewall...")
                            val result = firewallManager.startFirewall()
                            result.onSuccess { backendType ->
                                Log.d(TAG, "Firewall restored successfully with backend: $backendType")
                            }.onFailure { error ->
                                Log.e(TAG, "Failed to restore firewall: ${error.message}")
                            }
                        } finally {
                            // Signal that async work is complete
                            pendingResult.finish()
                        }
                    }
                } else {
                    Log.e(TAG, "Failed to get De1984Application instance")

                    // Fallback to VPN service for backward compatibility
                    val serviceIntent = Intent(context, FirewallVpnService::class.java).apply {
                        action = FirewallVpnService.ACTION_START
                    }

                    try {
                        context.startService(serviceIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start firewall service", e)
                    }
                }
            } else {
                Log.d(TAG, "Firewall was not enabled, skipping restore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring firewall state", e)
        }
    }
}

