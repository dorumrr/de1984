package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import kotlinx.coroutines.flow.Flow

/**
 * Use case to get a firewall rule for a specific package.
 *
 * @param packageName The package name of the app
 * @param userId Android user profile ID (0 = personal, 10+ = work/clone profiles)
 */
class GetFirewallRuleByPackageUseCase constructor(
    private val firewallRepository: FirewallRepository
) {
    operator fun invoke(packageName: String, userId: Int = 0): Flow<FirewallRule?> {
        return firewallRepository.getRuleByPackage(packageName, userId)
    }
}

