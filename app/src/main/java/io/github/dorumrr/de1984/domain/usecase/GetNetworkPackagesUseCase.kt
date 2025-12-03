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
            .map { packages -> packages.map { it.enforceRoamingDependency() } }
    }

    /**
     * Enforce business rule: Roaming requires Mobile to be enabled
     * If mobile is blocked, roaming must also be blocked
     */
    private fun NetworkPackage.enforceRoamingDependency(): NetworkPackage {
        return if (mobileBlocked && !roamingBlocked) {
            // Invalid state: mobile blocked but roaming allowed
            // Fix by blocking roaming too
            copy(roamingBlocked = true)
        } else {
            this
        }
    }
    
    fun getByType(type: PackageType): Flow<List<NetworkPackage>> {
        return networkPackageRepository.getNetworkPackagesByType(type)
            .map { packages -> packages.map { it.enforceRoamingDependency() } }
    }

    fun getByAccessState(state: NetworkAccessState): Flow<List<NetworkPackage>> {
        return networkPackageRepository.getNetworkPackagesByAccessState(state)
            .map { packages -> packages.map { it.enforceRoamingDependency() } }
    }

    fun getFilteredByState(filterState: FirewallFilterState): Flow<List<NetworkPackage>> {
        // Step 1: Apply package type filter (All, User, or System)
        val baseFlow = when (filterState.packageType.lowercase()) {
            "user" -> getByType(PackageType.USER)
            "system" -> getByType(PackageType.SYSTEM)
            "all" -> invoke()  // Return all packages
            else -> invoke()   // Default to all packages
        }

        // Step 2: Apply profile filter (All, Personal, Work, Clone)
        val profileFilteredFlow = baseFlow.map { packages ->
            when (filterState.profileFilter.lowercase()) {
                "personal" -> packages.filter { !it.isWorkProfile && !it.isCloneProfile }
                "work" -> packages.filter { it.isWorkProfile }
                "clone" -> packages.filter { it.isCloneProfile }
                "all" -> packages
                else -> packages
            }
        }

        // Step 3: Apply network state filter (Allowed or Blocked) if selected
        val stateFilteredFlow = if (filterState.networkState != null) {
            profileFilteredFlow.map { packages ->
                when (filterState.networkState.lowercase()) {
                    // "Allowed" filter: Show apps that are allowed on ANY network
                    // This includes fully allowed apps AND partially blocked apps (allowed on some networks)
                    "allowed" -> packages.filter { pkg ->
                        !pkg.wifiBlocked || !pkg.mobileBlocked || !pkg.roamingBlocked
                    }
                    // "Blocked" filter: Show apps that are blocked on ANY network
                    // This includes fully blocked apps AND partially blocked apps (blocked on some networks)
                    "blocked" -> packages.filter { pkg ->
                        pkg.wifiBlocked || pkg.mobileBlocked || pkg.roamingBlocked
                    }
                    else -> packages
                }
            }
        } else {
            profileFilteredFlow
        }

        // Step 4: Apply Internet Only permission filter if enabled
        return if (filterState.internetOnly) {
            stateFilteredFlow.map { packages ->
                packages.filter { pkg -> pkg.hasInternetPermission }
            }
        } else {
            stateFilteredFlow
        }
    }
    
    fun getBlocked(): Flow<List<NetworkPackage>> {
        return networkPackageRepository.getBlockedPackages()
            .map { packages -> packages.map { it.enforceRoamingDependency() } }
    }

    fun getAllowed(): Flow<List<NetworkPackage>> {
        return networkPackageRepository.getAllowedPackages()
            .map { packages -> packages.map { it.enforceRoamingDependency() } }
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
