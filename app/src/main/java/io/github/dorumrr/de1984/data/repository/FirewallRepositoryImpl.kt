package io.github.dorumrr.de1984.data.repository

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
    
    override fun getRuleByPackage(packageName: String): Flow<FirewallRule?> {
        return firewallRuleDao.getRuleByPackage(packageName).map { entity ->
            entity?.toDomain()
        }
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
        firewallRuleDao.insertRule(rule.toEntity())
        notifyRulesChanged()
    }

    override suspend fun insertRules(rules: List<FirewallRule>) {
        firewallRuleDao.insertRules(rules.toEntity())
        notifyRulesChanged()
    }

    override suspend fun updateRule(rule: FirewallRule) {
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
}

