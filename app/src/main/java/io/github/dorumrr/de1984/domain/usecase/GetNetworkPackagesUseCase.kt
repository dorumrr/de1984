package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.domain.model.NetworkAccessState
import io.github.dorumrr.de1984.domain.model.FirewallFilterState
import io.github.dorumrr.de1984.domain.repository.NetworkPackageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GetNetworkPackagesUseCase constructor(
    private val networkPackageRepository: NetworkPackageRepository
) {

    operator fun invoke(): Flow<List<NetworkPackage>> {
        return networkPackageRepository.getNetworkPackages()
    }
    
    fun getByType(type: PackageType): Flow<List<NetworkPackage>> {
        return networkPackageRepository.getNetworkPackagesByType(type)
    }

    fun getByAccessState(state: NetworkAccessState): Flow<List<NetworkPackage>> {
        return networkPackageRepository.getNetworkPackagesByAccessState(state)
    }

    fun getFilteredByState(filterState: FirewallFilterState): Flow<List<NetworkPackage>> {
        val baseFlow = when (filterState.packageType.lowercase()) {
            "user" -> getByType(PackageType.USER)
            "system" -> getByType(PackageType.SYSTEM)
            else -> getByType(PackageType.USER)
        }

        return if (filterState.networkState != null) {
            baseFlow.map { packages ->
                when (filterState.networkState.lowercase()) {
                    "allowed" -> packages.filter { !it.isNetworkBlocked }
                    "blocked" -> packages.filter { it.isNetworkBlocked }
                    else -> packages
                }
            }
        } else {
            baseFlow
        }
    }
    
    fun getBlocked(): Flow<List<NetworkPackage>> {
        return networkPackageRepository.getBlockedPackages()
    }
    
    fun getAllowed(): Flow<List<NetworkPackage>> {
        return networkPackageRepository.getAllowedPackages()
    }
    
    fun getByTypeAndState(type: PackageType, state: NetworkAccessState): Flow<List<NetworkPackage>> {
        return getByType(type).map { packages ->
            packages.filter {
                when (state) {
                    NetworkAccessState.ALLOWED -> it.isFullyAllowed
                    NetworkAccessState.BLOCKED -> it.isFullyBlocked
                    NetworkAccessState.PARTIAL -> it.isPartiallyBlocked
                }
            }
        }
    }
}
