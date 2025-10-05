package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import javax.inject.Inject

class AllowAllAppsUseCase @Inject constructor(
    private val firewallRepository: FirewallRepository
) {
    suspend operator fun invoke() {
        firewallRepository.allowAllApps()
    }
}

