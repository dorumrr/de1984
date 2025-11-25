package io.github.dorumrr.de1984.domain.usecase

import android.content.Context
import android.util.Log
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
        Log.d(TAG, "switchToBlockAll() called")

        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        Log.d(TAG, "allowCriticalPackageFirewall: $allowCritical")

        if (!allowCritical) {
            // Standard behavior - critical packages are protected by backend logic
            Log.d(TAG, "Using standard blockAllApps (critical packages protected by backends)")
            firewallRepository.blockAllApps()
            return
        }

        // Smart behavior - preserve user preferences for critical packages
        Log.d(TAG, "Using smart policy switching for critical packages")

        // Get all existing rules
        val allRules = firewallRepository.getAllRulesSync()
        Log.d(TAG, "Found ${allRules.size} existing rules")

        // Get all critical package names
        val criticalPackages = Constants.Firewall.SYSTEM_WHITELIST
        Log.d(TAG, "System whitelist contains ${criticalPackages.size} critical packages")

        // Block all non-critical packages
        firewallRepository.blockAllApps()
        Log.d(TAG, "Blocked all apps (including critical packages)")

        // Now restore critical packages to their previous state or default to ALLOW
        var preservedCount = 0
        var defaultedCount = 0

        for (packageName in criticalPackages) {
            val existingRule = allRules.find { it.packageName == packageName }

            if (existingRule != null) {
                // User has explicitly configured this critical package - PRESERVE their preference
                Log.d(TAG, "Preserving user preference for critical package: $packageName (wifi=${existingRule.wifiBlocked}, mobile=${existingRule.mobileBlocked})")
                firewallRepository.updateRule(existingRule.copy(updatedAt = System.currentTimeMillis()))
                preservedCount++
            } else {
                // No user preference - DEFAULT to ALLOW for system stability
                // Note: We don't create a rule here because the package might not be installed
                // The backend logic will handle allowing it when it's encountered
                Log.d(TAG, "No existing rule for critical package: $packageName (will be allowed by backend)")
                defaultedCount++
            }
        }

        Log.d(TAG, "Smart policy switch complete: preserved=$preservedCount, defaulted=$defaultedCount")
    }

    /**
     * Switch to "Allow All" policy with smart handling of critical packages.
     */
    suspend fun switchToAllowAll() {
        Log.d(TAG, "switchToAllowAll() called")

        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        Log.d(TAG, "allowCriticalPackageFirewall: $allowCritical")

        if (!allowCritical) {
            // Standard behavior - critical packages are protected by backend logic
            Log.d(TAG, "Using standard allowAllApps (critical packages protected by backends)")
            firewallRepository.allowAllApps()
            return
        }

        // Smart behavior - preserve user preferences for critical packages
        Log.d(TAG, "Using smart policy switching for critical packages")

        // Get all existing rules
        val allRules = firewallRepository.getAllRulesSync()
        Log.d(TAG, "Found ${allRules.size} existing rules")

        // Get all critical package names
        val criticalPackages = Constants.Firewall.SYSTEM_WHITELIST
        Log.d(TAG, "System whitelist contains ${criticalPackages.size} critical packages")

        // Allow all non-critical packages
        firewallRepository.allowAllApps()
        Log.d(TAG, "Allowed all apps (including critical packages)")

        // Now restore critical packages to their previous state
        var preservedCount = 0

        for (packageName in criticalPackages) {
            val existingRule = allRules.find { it.packageName == packageName }

            if (existingRule != null) {
                // User has explicitly configured this critical package - PRESERVE their preference
                Log.d(TAG, "Preserving user preference for critical package: $packageName (wifi=${existingRule.wifiBlocked}, mobile=${existingRule.mobileBlocked})")
                firewallRepository.updateRule(existingRule.copy(updatedAt = System.currentTimeMillis()))
                preservedCount++
            }
            // If no existing rule, the package will be allowed (which is what we want)
        }

        Log.d(TAG, "Smart policy switch complete: preserved=$preservedCount")
    }
}

