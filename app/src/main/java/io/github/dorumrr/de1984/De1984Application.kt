package io.github.dorumrr.de1984

import android.app.Application
import android.content.Context
import android.os.Build
import com.google.android.material.color.DynamicColors
import com.topjohnwu.superuser.Shell
import io.github.dorumrr.de1984.data.firewall.ConnectivityManagerFirewallBackend
import io.github.dorumrr.de1984.data.firewall.IptablesFirewallBackend
import io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class De1984Application : Application() {

    companion object {
        private const val TAG = "De1984Application"

        init {
            // Initialize libsu before any shell operations
            // This must be done in a static block before the Application is created
            Shell.enableVerboseLogging = BuildConfig.DEBUG
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    // 30 second timeout for initial root shell creation
                    // This needs to be long enough for Magisk to show the grant dialog
                    // and for the user to respond. If this times out, libsu caches a
                    // non-root shell and all subsequent checks fail!
                    .setTimeout(30)
            )
        }
    }

    lateinit var dependencies: De1984Dependencies
        private set

    override fun onCreate() {
        super.onCreate()

        // Initialize AppLogger with context for SharedPreferences access
        AppLogger.init(this)
        AppLogger.i(TAG, "Application starting")

        // Initialize HiddenApiBypass for multi-user/work profile support
        // Must be done early, before any hidden API calls
        HiddenApiHelper.initialize()

        // Apply dynamic colors if enabled
        applyDynamicColorsIfEnabled()

        // Initialize dependencies
        dependencies = De1984Dependencies.getInstance(this)

        // Register Shizuku listeners for lifecycle monitoring
        dependencies.shizukuManager.registerListeners()

        // Clean up orphaned firewall rules if app was killed while privileged backends were running
        cleanupOrphanedFirewallRules()

        AppLogger.i(TAG, "Application initialized")
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
                    AppLogger.d(TAG, "Cleaning up orphaned firewall rules (firewall was not enabled)")

                    // Clean up iptables rules
                    try {
                        val iptablesBackend = IptablesFirewallBackend(
                            this@De1984Application,
                            dependencies.rootManager,
                            dependencies.shizukuManager,
                            dependencies.errorHandler
                        )
                        iptablesBackend.stopInternal()
                        AppLogger.d(TAG, "Cleaned up orphaned iptables rules")
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Failed to clean up orphaned iptables rules: ${e.message}")
                    }

                    // Clean up ConnectivityManager rules
                    try {
                        val cmBackend = ConnectivityManagerFirewallBackend(
                            this@De1984Application,
                            dependencies.shizukuManager,
                            dependencies.errorHandler
                        )
                        cmBackend.stopInternal()
                        AppLogger.d(TAG, "Cleaned up orphaned ConnectivityManager rules")
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Failed to clean up orphaned ConnectivityManager rules: ${e.message}")
                    }

                    // NetworkPolicyManager doesn't need cleanup (no persistent state)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to clean up orphaned firewall rules: ${e.message}")
                // Ignore errors - this is best-effort cleanup
            }
        }
    }

    /**
     * Apply dynamic colors if enabled in settings.
     * This must be called before any activities are created.
     */
    private fun applyDynamicColorsIfEnabled() {
        try {
            val prefs = getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val useDynamicColors = prefs.getBoolean(
                Constants.Settings.KEY_USE_DYNAMIC_COLORS,
                Constants.Settings.DEFAULT_USE_DYNAMIC_COLORS
            )

            AppLogger.d(TAG, "applyDynamicColorsIfEnabled: useDynamicColors=$useDynamicColors, SDK=${Build.VERSION.SDK_INT}")

            if (useDynamicColors) {
                // Check if Dynamic Colors is available (Android 12+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    DynamicColors.applyToActivitiesIfAvailable(this)
                    AppLogger.d(TAG, "Dynamic colors enabled and applied (Android 12+)")
                } else {
                    AppLogger.d(TAG, "Dynamic colors enabled but not available (Android < 12)")
                }
            } else {
                AppLogger.d(TAG, "Dynamic colors disabled by user")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to apply dynamic colors: ${e.message}", e)
            // Ignore errors - dynamic colors are optional
        }
    }
}
