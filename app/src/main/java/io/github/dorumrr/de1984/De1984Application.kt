package io.github.dorumrr.de1984

import android.app.Application
import android.util.Log
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

        // Clean up orphaned iptables rules if app was killed while iptables backend was running
        cleanupOrphanedIptablesRules()
    }

    /**
     * Clean up orphaned iptables rules that may remain if app was killed while iptables backend was running.
     * This prevents apps from remaining blocked after app crash/kill.
     */
    private fun cleanupOrphanedIptablesRules() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val prefs = getSharedPreferences(Constants.Settings.PREFS_NAME, MODE_PRIVATE)
                val wasFirewallEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)

                // Only clean up if firewall was NOT enabled (meaning it shouldn't have rules)
                // If firewall was enabled, BootReceiver will restore it properly
                if (!wasFirewallEnabled) {
                    Log.d(TAG, "Cleaning up orphaned iptables rules (firewall was not enabled)")

                    val iptablesBackend = IptablesFirewallBackend(
                        this@De1984Application,
                        dependencies.rootManager,
                        dependencies.shizukuManager,
                        dependencies.errorHandler
                    )

                    // Try to clean up - ignore errors if no root/Shizuku access
                    iptablesBackend.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up orphaned iptables rules: ${e.message}")
                // Ignore errors - this is best-effort cleanup
            }
        }
    }
}
