package io.github.dorumrr.de1984

import android.app.Application
import android.util.Log
import io.github.dorumrr.de1984.data.firewall.ConnectivityManagerFirewallBackend
import io.github.dorumrr.de1984.data.firewall.IptablesFirewallBackend
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class De1984Application : Application() {

    companion object {
        private const val TAG = "De1984Application"
    }

    lateinit var dependencies: De1984Dependencies
        private set

    override fun onCreate() {
        super.onCreate()

        // Initialize dependencies
        dependencies = De1984Dependencies.getInstance(this)

        // Register Shizuku listeners for lifecycle monitoring
        dependencies.shizukuManager.registerListeners()

        // Clean up orphaned firewall rules if app was killed while privileged backends were running
        cleanupOrphanedFirewallRules()
    }

    /**
     * Clean up orphaned firewall rules that may remain if app was killed while privileged backends were running.
     * This prevents apps from remaining blocked after app crash/kill.
     */
    private fun cleanupOrphanedFirewallRules() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val prefs = getSharedPreferences(Constants.Settings.PREFS_NAME, MODE_PRIVATE)
                val wasFirewallEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)

                // Only clean up if firewall was NOT enabled (meaning it shouldn't have rules)
                // If firewall was enabled, BootReceiver will restore it properly
                if (!wasFirewallEnabled) {
                    Log.d(TAG, "Cleaning up orphaned firewall rules (firewall was not enabled)")

                    // Clean up iptables rules
                    try {
                        val iptablesBackend = IptablesFirewallBackend(
                            this@De1984Application,
                            dependencies.rootManager,
                            dependencies.shizukuManager,
                            dependencies.errorHandler
                        )
                        iptablesBackend.stopInternal()
                        Log.d(TAG, "Cleaned up orphaned iptables rules")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to clean up orphaned iptables rules: ${e.message}")
                    }

                    // Clean up ConnectivityManager rules
                    try {
                        val cmBackend = ConnectivityManagerFirewallBackend(
                            this@De1984Application,
                            dependencies.shizukuManager,
                            dependencies.errorHandler
                        )
                        cmBackend.stopInternal()
                        Log.d(TAG, "Cleaned up orphaned ConnectivityManager rules")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to clean up orphaned ConnectivityManager rules: ${e.message}")
                    }

                    // NetworkPolicyManager doesn't need cleanup (no persistent state)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up orphaned firewall rules: ${e.message}")
                // Ignore errors - this is best-effort cleanup
            }
        }
    }
}
