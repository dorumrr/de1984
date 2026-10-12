package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import android.content.pm.PackageManager
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.utils.Constants

class SmartPolicySwitchUseCase(
    private val firewallRepository: FirewallRepository,
    private val context: Context
) {
    companion object {
        private const val TAG = "SmartPolicySwitchUseCase"
    }

    suspend fun switchToBlockAll() {
        AppLogger.d(TAG, "switchToBlockAll() called")

        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        AppLogger.d(TAG, "allowCriticalPackageFirewall: $allowCritical")

        if (!allowCritical) {
            AppLogger.d(TAG, "Using standard blockAllApps (critical packages protected by backends)")
            firewallRepository.blockAllApps()
            return
        }

        AppLogger.d(TAG, "Using smart policy switching for critical packages")

        val allRules = firewallRepository.getAllRulesSync()
        AppLogger.d(TAG, "Found ${allRules.size} existing rules")

        val criticalPackages = getCriticalPackageNames()
        AppLogger.d(TAG, "Critical packages: ${criticalPackages.size} (SYSTEM_WHITELIST + VPN apps)")

        firewallRepository.blockAllApps()
        AppLogger.d(TAG, "Blocked all apps (including critical packages)")

        var preservedCount = 0
        var defaultedCount = 0

        for (packageName in criticalPackages) {
            // Every profile's copy, not just the first. `find` preserved one rule for a critical
            // package present in both the personal and the work profile and left the other at
            // whatever the bulk policy write had just set.
            val existingRules = allRules.filter { it.packageName == packageName }

            if (existingRules.isNotEmpty()) {
                for (existingRule in existingRules) {
                    // User has explicitly configured this critical package - PRESERVE their preference
                    AppLogger.d(TAG, "Preserving user preference for critical package: $packageName (userId=${existingRule.userId}, wifi=${existingRule.wifiBlocked}, mobile=${existingRule.mobileBlocked})")
                    firewallRepository.updateRule(existingRule.copy(updatedAt = System.currentTimeMillis()))
                    preservedCount++
                }
            } else {
                // No user preference - DEFAULT to ALLOW for system stability
                // Note: We don't create a rule here because the backend + UI logic will handle allowing it
                AppLogger.d(TAG, "No existing rule for critical package: $packageName (will be allowed by backend)")
                defaultedCount++
            }
        }

        AppLogger.d(TAG, "Smart policy switch complete: preserved=$preservedCount, defaulted=$defaultedCount")
    }

    suspend fun switchToAllowAll() {
        AppLogger.d(TAG, "switchToAllowAll() called")

        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        AppLogger.d(TAG, "allowCriticalPackageFirewall: $allowCritical")

        if (!allowCritical) {
            AppLogger.d(TAG, "Using standard allowAllApps (critical packages protected by backends)")
            firewallRepository.allowAllApps()
            return
        }

        AppLogger.d(TAG, "Using smart policy switching for critical packages")

        val allRules = firewallRepository.getAllRulesSync()
        AppLogger.d(TAG, "Found ${allRules.size} existing rules")

        val criticalPackages = getCriticalPackageNames()
        AppLogger.d(TAG, "Critical packages: ${criticalPackages.size} (SYSTEM_WHITELIST + VPN apps)")

        firewallRepository.allowAllApps()
        AppLogger.d(TAG, "Allowed all apps (including critical packages)")

        var preservedCount = 0

        for (packageName in criticalPackages) {
            // Every profile's copy, not just the first. `find` preserved one rule for a critical
            // package present in both the personal and the work profile and left the other at
            // whatever the bulk policy write had just set.
            for (existingRule in allRules.filter { it.packageName == packageName }) {
                // User has explicitly configured this critical package - PRESERVE their preference
                AppLogger.d(TAG, "Preserving user preference for critical package: $packageName (wifi=${existingRule.wifiBlocked}, mobile=${existingRule.mobileBlocked})")
                firewallRepository.updateRule(existingRule.copy(updatedAt = System.currentTimeMillis()))
                preservedCount++
            }
        }

        AppLogger.d(TAG, "Smart policy switch complete: preserved=$preservedCount")
    }

    private fun getCriticalPackageNames(): Set<String> {
        val criticalPackages = mutableSetOf<String>()

        criticalPackages.addAll(Constants.Firewall.systemWhitelist())

        try {
            val userProfiles = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getUsers(context)
            val installedPackages = userProfiles.flatMap { profile ->
                io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getInstalledApplicationsAsUser(
                    context, PackageManager.GET_META_DATA, profile.userId
                ).map { appInfo -> appInfo to profile.userId }
            }

            for ((appInfo, userId) in installedPackages) {
                if (hasVpnService(appInfo.packageName, userId)) {
                    criticalPackages.add(appInfo.packageName)
                    AppLogger.d(TAG, "Detected VPN app: ${appInfo.packageName} (userId=$userId)")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error detecting VPN apps", e)
        }

        return criticalPackages
    }

    /**
     * Check if an app has a VPN service by looking for services with BIND_VPN_SERVICE permission.
     *
     * userId has NO DEFAULT on purpose. It used to default to 0, and every enforcement call site
     * omitted it - so a VPN app installed only in the work profile was looked up in the personal
     * profile, not found, and treated as an ordinary app. Block All then cut the work profile's
     * VPN. Making it required means the compiler catches the next caller that forgets.
     */
    private fun hasVpnService(packageName: String, userId: Int): Boolean {
        return try {
                // GET_PERMISSIONS is requested but never read - it makes this share a cache entry
                // with the getPackagesWithNetworkPermissions sweep, which asks for both.
                // getPackageInfoAsUser keys on "userId:flags:packageName", so GET_SERVICES alone was
                // always a different key and always a miss.
            val packageInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getPackageInfoAsUser(
                context,
                packageName,
                io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.PACKAGE_DETAIL_FLAGS,
                userId
            ) ?: return false

            packageInfo.services?.any { serviceInfo ->
                serviceInfo.permission == Constants.Firewall.VPN_SERVICE_PERMISSION
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}

