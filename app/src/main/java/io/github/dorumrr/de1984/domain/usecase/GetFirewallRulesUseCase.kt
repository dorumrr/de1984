package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import kotlinx.coroutines.flow.Flow

class GetFirewallRulesUseCase constructor(
    private val firewallRepository: FirewallRepository
) {
    operator fun invoke(): Flow<List<FirewallRule>> {
        return firewallRepository.getAllRules()
    }
    
    fun getBlockedRules(): Flow<List<FirewallRule>> {
        return firewallRepository.getBlockedRules()
    }
    
    fun getAllowedRules(): Flow<List<FirewallRule>> {
        return firewallRepository.getAllowedRules()
    }
    
    fun getUserAppRules(): Flow<List<FirewallRule>> {
        return firewallRepository.getUserAppRules()
    }
    
    fun getSystemAppRules(): Flow<List<FirewallRule>> {
        return firewallRepository.getSystemAppRules()
    }
}

