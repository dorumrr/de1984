package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import kotlinx.coroutines.flow.Flow

class GetFirewallRuleByPackageUseCase constructor(
    private val firewallRepository: FirewallRepository
) {
    operator fun invoke(packageName: String): Flow<FirewallRule?> {
        return firewallRepository.getRuleByPackage(packageName)
    }
}

