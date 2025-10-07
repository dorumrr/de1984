package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.domain.repository.FirewallRepository

class BlockAllAppsUseCase constructor(
    private val firewallRepository: FirewallRepository
) {
    suspend operator fun invoke() {
        firewallRepository.blockAllApps()
    }
}

