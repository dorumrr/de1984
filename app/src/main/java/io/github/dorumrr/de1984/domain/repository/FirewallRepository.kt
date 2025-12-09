package io.github.dorumrr.de1984.domain.repository

import io.github.dorumrr.de1984.domain.model.FirewallRule
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for firewall rules.
 *
 * All methods that operate on a specific package require both packageName AND userId
 * to properly support multi-user/work profile environments.
 */
interface FirewallRepository {

    // =============================================================================================
    // Read operations - All users
    // =============================================================================================

    fun getAllRules(): Flow<List<FirewallRule>>

    suspend fun getAllRulesSync(): List<FirewallRule>

    fun getBlockedRules(): Flow<List<FirewallRule>>

    fun getAllowedRules(): Flow<List<FirewallRule>>

    fun getUserAppRules(): Flow<List<FirewallRule>>

    fun getSystemAppRules(): Flow<List<FirewallRule>>

    fun getBlockedCount(): Flow<Int>

    // =============================================================================================
    // Read operations - By user profile
    // =============================================================================================

    fun getRulesByUserId(userId: Int): Flow<List<FirewallRule>>

    suspend fun getRulesByUserIdSync(userId: Int): List<FirewallRule>

    // =============================================================================================
    // Read operations - By package (require userId for composite key)
    // =============================================================================================

    fun getRuleByPackage(packageName: String, userId: Int): Flow<FirewallRule?>

    suspend fun getRuleByPackageSync(packageName: String, userId: Int): FirewallRule?

    // =============================================================================================
    // Write operations
    // =============================================================================================

    suspend fun insertRule(rule: FirewallRule)

    suspend fun insertRules(rules: List<FirewallRule>)

    suspend fun updateRule(rule: FirewallRule)

    suspend fun deleteRule(packageName: String, userId: Int)

    suspend fun deleteRulesByUserId(userId: Int)

    suspend fun deleteAllRules()

    // =============================================================================================
    // Bulk operations - All users
    // =============================================================================================

    suspend fun blockAllApps()

    suspend fun allowAllApps()

    // =============================================================================================
    // Atomic field updates (require userId for composite key)
    // =============================================================================================

    suspend fun updateWifiBlocking(packageName: String, userId: Int, blocked: Boolean)

    suspend fun updateMobileBlocking(packageName: String, userId: Int, blocked: Boolean)

    suspend fun updateRoamingBlocking(packageName: String, userId: Int, blocked: Boolean)

    suspend fun updateBackgroundBlocking(packageName: String, userId: Int, blocked: Boolean)

    suspend fun updateLanBlocking(packageName: String, userId: Int, blocked: Boolean)

    // Atomic batch update for all network types - prevents race conditions when toggling all networks at once
    suspend fun updateAllNetworkBlocking(packageName: String, userId: Int, blocked: Boolean)

    // Atomic batch update for mobile+roaming dependency - prevents race conditions when enforcing business rule
    suspend fun updateMobileAndRoaming(packageName: String, userId: Int, mobileBlocked: Boolean, roamingBlocked: Boolean)
}

