package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import javax.inject.Inject

class UpdateFirewallRuleUseCase @Inject constructor(
    private val firewallRepository: FirewallRepository
) {
    suspend operator fun invoke(rule: FirewallRule) {
        val updatedRule = rule.copy(updatedAt = System.currentTimeMillis())
        firewallRepository.updateRule(updatedRule)
    }
    
    suspend fun insertOrUpdate(rule: FirewallRule) {
        val updatedRule = rule.copy(updatedAt = System.currentTimeMillis())
        firewallRepository.insertRule(updatedRule)
    }
}

