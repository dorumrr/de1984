package io.github.dorumrr.de1984.data.repository

import io.github.dorumrr.de1984.utils.AppLogger
import android.content.Context
import io.github.dorumrr.de1984.data.database.dao.FirewallRuleDao
import io.github.dorumrr.de1984.data.mapper.toDomain
import io.github.dorumrr.de1984.data.mapper.toEntity
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FirewallRepositoryImpl(
    private val firewallRuleDao: FirewallRuleDao,
    private val context: Context
) : FirewallRepository {

    companion object {
        private const val TAG = "FirewallRepository"
    }

    private fun notifyRulesChanged() {
        val intent = android.content.Intent("io.github.dorumrr.de1984.FIREWALL_RULES_CHANGED")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
    }
    
    // =============================================================================================
    // Read operations - All users
    // =============================================================================================

    override fun getAllRules(): Flow<List<FirewallRule>> {
        return firewallRuleDao.getAllRules().map { entities ->
            entities.toDomain()
        }
    }

    override suspend fun getAllRulesSync(): List<FirewallRule> {
        return firewallRuleDao.getAllRulesSync().toDomain()
    }

    override fun getBlockedRules(): Flow<List<FirewallRule>> {
        return firewallRuleDao.getBlockedRules().map { entities ->
            entities.toDomain()
        }
    }

    override fun getAllowedRules(): Flow<List<FirewallRule>> {
        return firewallRuleDao.getAllowedRules().map { entities ->
            entities.toDomain()
        }
    }

    override fun getUserAppRules(): Flow<List<FirewallRule>> {
        return firewallRuleDao.getUserAppRules().map { entities ->
            entities.toDomain()
        }
    }

    override fun getSystemAppRules(): Flow<List<FirewallRule>> {
        return firewallRuleDao.getSystemAppRules().map { entities ->
            entities.toDomain()
        }
    }

    override fun getBlockedCount(): Flow<Int> {
        return firewallRuleDao.getBlockedCount()
    }

    // =============================================================================================
    // Read operations - By user profile
    // =============================================================================================

    override fun getRulesByUserId(userId: Int): Flow<List<FirewallRule>> {
        return firewallRuleDao.getRulesByUserId(userId).map { entities ->
            entities.toDomain()
        }
    }

    override suspend fun getRulesByUserIdSync(userId: Int): List<FirewallRule> {
        return firewallRuleDao.getRulesByUserIdSync(userId).toDomain()
    }

    // =============================================================================================
    // Read operations - By package (require userId for composite key)
    // =============================================================================================

    override fun getRuleByPackage(packageName: String, userId: Int): Flow<FirewallRule?> {
        return firewallRuleDao.getRuleByPackage(packageName, userId).map { entity ->
            entity?.toDomain()
        }
    }

    override suspend fun getRuleByPackageSync(packageName: String, userId: Int): FirewallRule? {
        return firewallRuleDao.getRuleByPackageSync(packageName, userId)?.toDomain()
    }
    
    override suspend fun insertRule(rule: FirewallRule) {
        // Log Chrome rules for debugging
        if (rule.packageName.contains("chrome", ignoreCase = true)) {
            AppLogger.d(TAG, "insertRule: ${rule.packageName} - wifi=${rule.wifiBlocked}, mobile=${rule.mobileBlocked}, roaming=${rule.blockWhenRoaming}")
            AppLogger.d(TAG, "  Stack trace:", Exception("insertRule called"))
        }
        firewallRuleDao.insertRule(rule.toEntity())
        notifyRulesChanged()
    }

    override suspend fun insertRules(rules: List<FirewallRule>) {
        // Log Chrome rules for debugging
        rules.filter { it.packageName.contains("chrome", ignoreCase = true) }.forEach { rule ->
            AppLogger.d(TAG, "insertRules: ${rule.packageName} - wifi=${rule.wifiBlocked}, mobile=${rule.mobileBlocked}, roaming=${rule.blockWhenRoaming}")
        }
        firewallRuleDao.insertRules(rules.toEntity())
        notifyRulesChanged()
    }

    override suspend fun updateRule(rule: FirewallRule) {
        // Log Chrome rules for debugging
        if (rule.packageName.contains("chrome", ignoreCase = true)) {
            AppLogger.d(TAG, "updateRule: ${rule.packageName} - wifi=${rule.wifiBlocked}, mobile=${rule.mobileBlocked}, roaming=${rule.blockWhenRoaming}")
            AppLogger.d(TAG, "  Stack trace:", Exception("updateRule called"))
        }
        firewallRuleDao.updateRule(rule.toEntity())
        notifyRulesChanged()
    }

    override suspend fun deleteRule(packageName: String, userId: Int) {
        firewallRuleDao.deleteRule(packageName, userId)
        notifyRulesChanged()
    }

    override suspend fun deleteRulesByUserId(userId: Int) {
        firewallRuleDao.deleteRulesByUserId(userId)
        notifyRulesChanged()
    }

    override suspend fun deleteAllRules() {
        firewallRuleDao.deleteAllRules()
        notifyRulesChanged()
    }

    // =============================================================================================
    // Bulk operations - All users
    // =============================================================================================

    override suspend fun blockAllApps() {
        firewallRuleDao.blockAllApps()
        notifyRulesChanged()
    }

    override suspend fun allowAllApps() {
        firewallRuleDao.allowAllApps()
        notifyRulesChanged()
    }

    // =============================================================================================
    // Atomic field updates (require userId for composite key)
    // =============================================================================================

    override suspend fun updateWifiBlocking(packageName: String, userId: Int, blocked: Boolean) {
        firewallRuleDao.updateWifiBlocking(packageName, userId, blocked)
        notifyRulesChanged()
    }

    override suspend fun updateMobileBlocking(packageName: String, userId: Int, blocked: Boolean) {
        firewallRuleDao.updateMobileBlocking(packageName, userId, blocked)
        notifyRulesChanged()
    }

    override suspend fun updateRoamingBlocking(packageName: String, userId: Int, blocked: Boolean) {
        firewallRuleDao.updateRoamingBlocking(packageName, userId, blocked)
        notifyRulesChanged()
    }

    override suspend fun updateBackgroundBlocking(packageName: String, userId: Int, blocked: Boolean) {
        firewallRuleDao.updateBackgroundBlocking(packageName, userId, blocked)
        notifyRulesChanged()
    }

    override suspend fun updateLanBlocking(packageName: String, userId: Int, blocked: Boolean) {
        firewallRuleDao.updateLanBlocking(packageName, userId, blocked)
        notifyRulesChanged()
    }

    override suspend fun updateAllNetworkBlocking(packageName: String, userId: Int, blocked: Boolean) {
        val startTime = System.currentTimeMillis()
        AppLogger.d(TAG, "🔥 [TIMING] updateAllNetworkBlocking START: pkg=$packageName, userId=$userId, blocked=$blocked")
        firewallRuleDao.updateAllNetworkBlocking(packageName, userId, blocked)
        AppLogger.d(TAG, "🔥 [TIMING] DAO update done: +${System.currentTimeMillis() - startTime}ms")
        notifyRulesChanged()
        AppLogger.d(TAG, "🔥 [TIMING] Broadcast sent: +${System.currentTimeMillis() - startTime}ms")
    }

    override suspend fun updateMobileAndRoaming(packageName: String, userId: Int, mobileBlocked: Boolean, roamingBlocked: Boolean) {
        firewallRuleDao.updateMobileAndRoaming(packageName, userId, mobileBlocked, roamingBlocked)
        notifyRulesChanged()
    }
}

