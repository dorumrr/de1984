package io.github.dorumrr.de1984

import android.app.Application
import android.content.Context
import android.os.Build
import com.google.android.material.color.DynamicColors
import com.topjohnwu.superuser.Shell
import io.github.dorumrr.de1984.data.firewall.ConnectivityManagerFirewallBackend
import io.github.dorumrr.de1984.data.firewall.IptablesFirewallBackend
import io.github.dorumrr.de1984.data.firewall.NetworkPolicyManagerFirewallBackend
import io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
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

        AppLogger.init(this)
        AppLogger.i(TAG, "Application starting")

        // Initialize HiddenApiBypass for multi-user/work profile support
        // Must be done early, before any hidden API calls
        HiddenApiHelper.initialize()

        applyDynamicColorsIfEnabled()

        dependencies = De1984Dependencies.getInstance(this)

        dependencies.shizukuManager.registerListeners()

        HiddenApiHelper.setShizukuManager(dependencies.shizukuManager)
        HiddenApiHelper.onUsersRemoved = { userIds ->
            dependencies.applicationScope.launch(Dispatchers.IO) {
                userIds.forEach { dependencies.firewallRepository.deleteRulesByUserId(it) }
                // A profile re-created under the same id needs its recommended rules back after the delete.
                dependencies.provideEnsureSystemRecommendedRulesUseCase().invoke()
            }
        }

        ensureSystemRecommendedRules()

        cleanupOrphanedFirewallRules()

        AppLogger.i(TAG, "Application initialized")
    }

    private fun ensureSystemRecommendedRules() {
        dependencies.applicationScope.launch(Dispatchers.IO) {
            try {
                val useCase = dependencies.provideEnsureSystemRecommendedRulesUseCase()
                useCase.invoke()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to sync system-recommended rules: ${e.message}")
            }
        }
    }

    private fun cleanupOrphanedFirewallRules() {
        dependencies.applicationScope.launch(Dispatchers.IO) {
            try {
                val prefs = getSharedPreferences(Constants.Settings.PREFS_NAME, MODE_PRIVATE)
                val wasFirewallEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)

                // Only clean up if firewall was NOT enabled (meaning it shouldn't have rules)
                // If firewall was enabled, BootReceiver will restore it properly
                if (!wasFirewallEnabled) {
                    AppLogger.d(TAG, "Cleaning up orphaned firewall rules (firewall was not enabled)")

                    // Wake Magisk and settle Shizuku BEFORE sweeping. libsu reports NOT_ROOTED until
                    // the app makes its first request, so a sweep that runs ahead of that probes with
                    // no privilege at all: it cannot see the chains, concludes there is nothing to
                    // undo, and reports success over rules that are still dropping traffic. Measured
                    // on hardware - the sweep finished a full second before the first root check.
                    // BootReceiver already does exactly this, for exactly this reason.
                    AppLogger.d(TAG, "Requesting privileges before the sweep so it can actually look")
                    dependencies.rootManager.forceRecheckRootStatus()
                    kotlinx.coroutines.delay(500)
                    dependencies.shizukuManager.checkShizukuStatus()
                    kotlinx.coroutines.delay(500)

                    // Gate re-checked under the start/stop lock: one widget or tile tap cold-starts this process AND
                    // starts the firewall, and a sweep after that start deletes its chains while every control says ACTIVE.
                    val swept = dependencies.firewallManager.sweepWhileStopped {
                        // Collected, not just logged. This sweep is the only retry that happens after a
                        // stop failed and the process died, and the health state it would have restored
                        // is in-memory only - so a device whose rules are still enforcing used to start
                        // every process reporting Healthy, with no badge, no banner and nothing to press.
                        // A cleanup that threw proved nothing, so the sweep cannot count as clean.
                        var sweepIncomplete = false
                        val orphans = mutableListOf<Pair<FirewallBackendType, Throwable>>()

                        try {
                            // The shared instance, not a new one. stopInternal() deletes the kernel
                            // chains AND clears blockedUids / re-arms chainNeedsResync. Done on a
                            // throwaway, the deletion still happens but the live object keeps a
                            // populated blockedUids and chainNeedsResync=false - so its next applyRules
                            // diffs against a chain that no longer exists, finds nothing to add, and
                            // writes no rules at all. Silent total bypass.
                            val iptablesBackend = dependencies.iptablesBackend
                            // stopInternal() can now genuinely fail - it verifies the chains are gone
                            // instead of assuming it. The old unconditional "Cleaned up" line would
                            // report success over chains that are still in the kernel.
                            iptablesBackend.stopInternal()
                                .onSuccess { AppLogger.d(TAG, "Cleaned up orphaned iptables rules") }
                                .onFailure { orphans += FirewallBackendType.IPTABLES to it; AppLogger.w(TAG, "Orphaned iptables cleanup incomplete: ${it.message}") }
                        } catch (e: Exception) {
                            sweepIncomplete = true
                            AppLogger.w(TAG, "Failed to clean up orphaned iptables rules: ${e.message}")
                        }

                        try {
                            val cmBackend = ConnectivityManagerFirewallBackend(
                                this@De1984Application,
                                dependencies.shizukuManager,
                                dependencies.errorHandler
                            )
                            // clearOrphanedPolicies(), not stopInternal(): it returns immediately when
                            // there is no record of ours, so the many users who never run this backend
                            // keep a cold start that issues no shell commands at all. An upgrade from a
                            // build that kept no record is already covered - this sweep only runs when
                            // the firewall was off, and that old build's own stop disabled the chain.
                            cmBackend.clearOrphanedPolicies()
                                .onSuccess { AppLogger.d(TAG, "Cleaned up orphaned ConnectivityManager rules") }
                                .onFailure { orphans += FirewallBackendType.CONNECTIVITY_MANAGER to it; AppLogger.w(TAG, "Orphaned ConnectivityManager denials remain: ${it.message}") }
                        } catch (e: Exception) {
                            sweepIncomplete = true
                            AppLogger.w(TAG, "Failed to clean up orphaned ConnectivityManager rules: ${e.message}")
                        }

                        // Clean up NetworkPolicyManager uid policies
                        // Android persists these in /data/system/netpolicy.xml, so they outlive the
                        // process. This is the only fresh-process sweep in the app, and the reason the
                        // backend mirrors its uid list to SharedPreferences: after a crash, or a stop
                        // that failed because Shizuku was down, this is where the retry happens.
                        try {
                            val npmBackend = NetworkPolicyManagerFirewallBackend(
                                this@De1984Application,
                                dependencies.shizukuManager,
                                dependencies.errorHandler
                            )
                            npmBackend.clearOrphanedPolicies()
                                .onSuccess { AppLogger.d(TAG, "Cleaned up orphaned NetworkPolicyManager policies") }
                                .onFailure { orphans += FirewallBackendType.NETWORK_POLICY_MANAGER to it; AppLogger.w(TAG, "Orphaned NetworkPolicyManager policies remain: ${it.message}") }
                        } catch (e: Exception) {
                            sweepIncomplete = true
                            AppLogger.w(TAG, "Failed to clean up orphaned NetworkPolicyManager policies: ${e.message}")
                        }

                        // Raise the warning for whatever is still enforcing. reportStopFailedFromSweep
                        // publishes the same STUCK badge, banner and notification a failed stop does,
                        // because it is the same situation: apps are blocked and no control in the app
                        // touches the thing blocking them.
                        orphans.forEach { (backend, error) ->
                            dependencies.firewallManager.reportStopFailedFromSweep(backend, error)
                        }
                        if (!sweepIncomplete) dependencies.firewallManager.reportCleanSweep(orphans.map { it.first }.toSet())
                    }
                    if (!swept) {
                        AppLogger.i(TAG, "Firewall is meant to be on or a backend is up - skipping the sweep")
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to clean up orphaned firewall rules: ${e.message}")
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
        }
    }
}
