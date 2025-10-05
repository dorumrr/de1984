package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetFirewallRuleByPackageUseCase @Inject constructor(
    private val firewallRepository: FirewallRepository
) {
    operator fun invoke(packageName: String): Flow<FirewallRule?> {
        return firewallRepository.getRuleByPackage(packageName)
    }
}

