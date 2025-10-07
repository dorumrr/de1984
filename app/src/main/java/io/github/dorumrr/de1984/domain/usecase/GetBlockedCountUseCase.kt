package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import kotlinx.coroutines.flow.Flow

class GetBlockedCountUseCase constructor(
    private val firewallRepository: FirewallRepository
) {
    operator fun invoke(): Flow<Int> {
        return firewallRepository.getBlockedCount()
    }
}

