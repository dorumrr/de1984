package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import android.content.pm.PackageManager
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.utils.Constants

/**
 * Smart policy switching use case that handles switching between "Allow All" and "Block All"
 * default firewall policies while respecting user preferences for system-critical packages.
 *
 * When allowCriticalPackageFirewall is ON:
 * - Preserves existing user preferences for critical packages (if they've explicitly configured them)
 * - Defaults critical packages to ALLOW (if no user preference exists) to ensure system stability
 * - Applies normal policy to non-critical packages
 * - Critical packages include SYSTEM_WHITELIST packages AND VPN apps
 *
 * When allowCriticalPackageFirewall is OFF:
 * - Uses standard blockAllApps/allowAllApps (critical packages are protected by backend logic anyway)
 */
class SmartPolicySwitchUseCase(
    private val firewallRepository: FirewallRepository,
    private val context: Context
) {
    companion object {
        private const val TAG = "SmartPolicySwitchUseCase"
    }

    /**
     * Switch to "Block All" policy with smart handling of critical packages.
     */
    suspend fun switchToBlockAll() {
        AppLogger.d(TAG, "switchToBlockAll() called")

        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        AppLogger.d(TAG, "allowCriticalPackageFirewall: $allowCritical")

        if (!allowCritical) {
            // Standard behavior - critical packages are protected by backend logic
            AppLogger.d(TAG, "Using standard blockAllApps (critical packages protected by backends)")
            firewallRepository.blockAllApps()
            return
        }

        // Smart behavior - preserve user preferences for critical packages
        AppLogger.d(TAG, "Using smart policy switching for critical packages")

        // Get all existing rules BEFORE blockAllApps (snapshot)
        val allRules = firewallRepository.getAllRulesSync()
        AppLogger.d(TAG, "Found ${allRules.size} existing rules")

        // Get all critical package names (SYSTEM_WHITELIST + VPN apps)
        val criticalPackages = getCriticalPackageNames()
        AppLogger.d(TAG, "Critical packages: ${criticalPackages.size} (SYSTEM_WHITELIST + VPN apps)")

        // Block all apps (this updates existing rules to blocked)
        firewallRepository.blockAllApps()
        AppLogger.d(TAG, "Blocked all apps (including critical packages)")

        // Now restore critical packages to their previous state
        var preservedCount = 0
        var defaultedCount = 0

        for (packageName in criticalPackages) {
            val existingRule = allRules.find { it.packageName == packageName }

            if (existingRule != null) {
                // User has explicitly configured this critical package - PRESERVE their preference
                AppLogger.d(TAG, "Preserving user preference for critical package: $packageName (wifi=${existingRule.wifiBlocked}, mobile=${existingRule.mobileBlocked})")
                firewallRepository.updateRule(existingRule.copy(updatedAt = System.currentTimeMillis()))
                preservedCount++
            } else {
                // No user preference - DEFAULT to ALLOW for system stability
                // Note: We don't create a rule here because the backend + UI logic will handle allowing it
                AppLogger.d(TAG, "No existing rule for critical package: $packageName (will be allowed by backend)")
                defaultedCount++
            }
        }

        AppLogger.d(TAG, "Smart policy switch complete: preserved=$preservedCount, defaulted=$defaultedCount")
    }

    /**
     * Switch to "Allow All" policy with smart handling of critical packages.
     */
    suspend fun switchToAllowAll() {
        AppLogger.d(TAG, "switchToAllowAll() called")

        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        AppLogger.d(TAG, "allowCriticalPackageFirewall: $allowCritical")

        if (!allowCritical) {
            // Standard behavior - critical packages are protected by backend logic
            AppLogger.d(TAG, "Using standard allowAllApps (critical packages protected by backends)")
            firewallRepository.allowAllApps()
            return
        }

        // Smart behavior - preserve user preferences for critical packages
        AppLogger.d(TAG, "Using smart policy switching for critical packages")

        // Get all existing rules BEFORE allowAllApps (snapshot)
        val allRules = firewallRepository.getAllRulesSync()
        AppLogger.d(TAG, "Found ${allRules.size} existing rules")

        // Get all critical package names (SYSTEM_WHITELIST + VPN apps)
        val criticalPackages = getCriticalPackageNames()
        AppLogger.d(TAG, "Critical packages: ${criticalPackages.size} (SYSTEM_WHITELIST + VPN apps)")

        // Allow all apps (this updates existing rules to allowed)
        firewallRepository.allowAllApps()
        AppLogger.d(TAG, "Allowed all apps (including critical packages)")

        // Now restore critical packages to their previous state
        var preservedCount = 0

        for (packageName in criticalPackages) {
            val existingRule = allRules.find { it.packageName == packageName }

            if (existingRule != null) {
                // User has explicitly configured this critical package - PRESERVE their preference
                AppLogger.d(TAG, "Preserving user preference for critical package: $packageName (wifi=${existingRule.wifiBlocked}, mobile=${existingRule.mobileBlocked})")
                firewallRepository.updateRule(existingRule.copy(updatedAt = System.currentTimeMillis()))
                preservedCount++
            }
            // If no existing rule, the package will be allowed (which is what we want)
        }

        AppLogger.d(TAG, "Smart policy switch complete: preserved=$preservedCount")
    }

    /**
     * Get all critical package names: SYSTEM_WHITELIST + dynamically detected VPN apps
     */
    private fun getCriticalPackageNames(): Set<String> {
        val criticalPackages = mutableSetOf<String>()

        // Add SYSTEM_WHITELIST packages
        criticalPackages.addAll(Constants.Firewall.SYSTEM_WHITELIST)

        // Add VPN apps (detected dynamically)
        try {
            val packageManager = context.packageManager
            val installedPackages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

            for (appInfo in installedPackages) {
                if (hasVpnService(appInfo.packageName)) {
                    criticalPackages.add(appInfo.packageName)
                    AppLogger.d(TAG, "Detected VPN app: ${appInfo.packageName}")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error detecting VPN apps", e)
        }

        return criticalPackages
    }

    /**
     * Check if an app has a VPN service by looking for services with BIND_VPN_SERVICE permission.
     */
    private fun hasVpnService(packageName: String): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SERVICES
            )

            packageInfo.services?.any { serviceInfo ->
                serviceInfo.permission == Constants.Firewall.VPN_SERVICE_PERMISSION
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}

