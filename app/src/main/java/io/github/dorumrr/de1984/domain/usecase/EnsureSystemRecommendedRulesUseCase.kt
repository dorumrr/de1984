package io.github.dorumrr.de1984.domain.usecase

import android.content.Context
import android.content.pm.PackageManager
import io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.utils.Constants

/**
 * Ensures that all system-recommended apps (SYSTEM_RECOMMENDED_ALLOW) have proper "allow all" rules.
 *
 * This is a one-time sync mechanism to handle:
 * - Existing installations where system-recommended apps don't have rules yet
 * - New additions to SYSTEM_RECOMMENDED_ALLOW (like Google Play Services for Issue #66)
 * - Users upgrading from older versions
 *
 * This use case is safe to call on every app startup because:
 * - It only creates rules for apps that don't have rules yet
 * - It respects existing user configuration (doesn't override existing rules)
 * - It's cheap (only queries installed packages once, filters by SYSTEM_RECOMMENDED_ALLOW)
 *
 * Per FIREWALL.md and Constants.kt:
 * - System-recommended apps get "allow all" rules by default
 * - Users can still manually block these apps if desired
 * - On degoogled devices where some packages don't exist, this has no effect
 */
class EnsureSystemRecommendedRulesUseCase(
    private val context: Context,
    private val firewallRepository: FirewallRepository
) {
    companion object {
        private const val TAG = "EnsureSystemRecommendedRulesUseCase"
    }

    /**
     * Check and create rules for system-recommended apps that don't have rules yet.
     * This is safe to call on every app startup.
     *
     * IMPORTANT: Handles multi-user/work profiles correctly by creating rules for each profile.
     */
    suspend fun invoke() {
        try {
            AppLogger.d(TAG, "Starting system-recommended apps rule sync")

            // Get all user profiles (personal, work, clone, etc.)
            val userProfiles = HiddenApiHelper.getUsers(context)
            AppLogger.d(TAG, "Found ${userProfiles.size} user profiles")

            val allRules = firewallRepository.getAllRulesSync()
            // Use composite key (packageName:userId) for multi-user support
            val existingRuleKeys = allRules.map { rule -> "${rule.packageName}:${rule.userId}" }.toSet()

            var createdCount = 0
            var skippedCount = 0

            // Process each system-recommended package for EACH user profile
            for (profile in userProfiles) {
                val userId = profile.userId

                Constants.Firewall.SYSTEM_RECOMMENDED_ALLOW.forEach { packageName ->
                    val ruleKey = "$packageName:$userId"

                    // Skip if rule already exists for this (package, user) combination
                    if (existingRuleKeys.contains(ruleKey)) {
                        skippedCount++
                        AppLogger.d(TAG, "Rule already exists for $packageName (userId=$userId) - skipping")
                        return@forEach
                    }

                    // Check if package is installed for this specific user
                    val appInfo = try {
                        HiddenApiHelper.getApplicationInfoAsUser(context, packageName, 0, userId)
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Failed to get app info for $packageName (userId=$userId): ${e.message}")
                        skippedCount++
                        return@forEach
                    }

                    // If null, package not installed for this user - skip silently
                    if (appInfo == null) {
                        skippedCount++
                        return@forEach
                    }

                    // Package is installed for this user but has no rule - create "allow all" rule
                    val uid = appInfo.uid
                    val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val appName = try {
                        context.packageManager.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        packageName
                    }

                    val rule = FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = uid,
                        appName = appName,
                        wifiBlocked = false,
                        mobileBlocked = false,
                        blockWhenRoaming = false,
                        enabled = true,
                        isSystemApp = isSystemApp
                    )

                    firewallRepository.insertRule(rule)
                    createdCount++
                    AppLogger.d(TAG, "Created 'allow all' rule for system-recommended app: $packageName (userId=$userId, uid=$uid)")
                }
            }

            AppLogger.i(TAG, "System-recommended apps sync complete: created $createdCount rules, skipped $skippedCount across ${userProfiles.size} profiles")
        } catch (e: Exception) {
            // Don't crash the app if sync fails - this is best-effort
            AppLogger.w(TAG, "Failed to sync system-recommended apps: ${e.message}", e)
        }
    }
}
