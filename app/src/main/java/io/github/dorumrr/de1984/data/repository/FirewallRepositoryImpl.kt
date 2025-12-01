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
    
    override fun getAllRules(): Flow<List<FirewallRule>> {
        return firewallRuleDao.getAllRules().map { entities ->
            entities.toDomain()
        }
    }

    override suspend fun getAllRulesSync(): List<FirewallRule> {
        return firewallRuleDao.getAllRulesSync().toDomain()
    }

    override fun getRuleByPackage(packageName: String): Flow<FirewallRule?> {
        return firewallRuleDao.getRuleByPackage(packageName).map { entity ->
            entity?.toDomain()
        }
    }

    override suspend fun getRuleByPackageSync(packageName: String): FirewallRule? {
        return firewallRuleDao.getRuleByPackageSync(packageName)?.toDomain()
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

    override suspend fun deleteRule(packageName: String) {
        firewallRuleDao.deleteRule(packageName)
        notifyRulesChanged()
    }

    override suspend fun deleteAllRules() {
        firewallRuleDao.deleteAllRules()
        notifyRulesChanged()
    }

    override suspend fun blockAllApps() {
        firewallRuleDao.blockAllApps()
        notifyRulesChanged()
    }

    override suspend fun allowAllApps() {
        firewallRuleDao.allowAllApps()
        notifyRulesChanged()
    }

    override suspend fun updateWifiBlocking(packageName: String, blocked: Boolean) {
        firewallRuleDao.updateWifiBlocking(packageName, blocked)
        notifyRulesChanged()
    }

    override suspend fun updateMobileBlocking(packageName: String, blocked: Boolean) {
        firewallRuleDao.updateMobileBlocking(packageName, blocked)
        notifyRulesChanged()
    }

    override suspend fun updateRoamingBlocking(packageName: String, blocked: Boolean) {
        firewallRuleDao.updateRoamingBlocking(packageName, blocked)
        notifyRulesChanged()
    }

    override suspend fun updateBackgroundBlocking(packageName: String, blocked: Boolean) {
        firewallRuleDao.updateBackgroundBlocking(packageName, blocked)
        notifyRulesChanged()
    }

    override suspend fun updateLanBlocking(packageName: String, blocked: Boolean) {
        firewallRuleDao.updateLanBlocking(packageName, blocked)
        notifyRulesChanged()
    }

    override suspend fun updateAllNetworkBlocking(packageName: String, blocked: Boolean) {
        val startTime = System.currentTimeMillis()
        AppLogger.d(TAG, "🔥 [TIMING] updateAllNetworkBlocking START: pkg=$packageName, blocked=$blocked")
        firewallRuleDao.updateAllNetworkBlocking(packageName, blocked)
        AppLogger.d(TAG, "🔥 [TIMING] DAO update done: +${System.currentTimeMillis() - startTime}ms")
        notifyRulesChanged()
        AppLogger.d(TAG, "🔥 [TIMING] Broadcast sent: +${System.currentTimeMillis() - startTime}ms")
    }

    override suspend fun updateMobileAndRoaming(packageName: String, mobileBlocked: Boolean, roamingBlocked: Boolean) {
        firewallRuleDao.updateMobileAndRoaming(packageName, mobileBlocked, roamingBlocked)
        notifyRulesChanged()
    }
}

