package io.github.dorumrr.de1984.domain.usecase

import android.util.Log
import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.domain.repository.PackageRepository
import io.github.dorumrr.de1984.presentation.viewmodel.PackageFilterState
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class GetPackagesUseCase constructor(
    private val packageRepository: PackageRepository
) {

    operator fun invoke(): Flow<List<Package>> {
        return packageRepository.getPackages()
    }

    fun getByType(type: PackageType): Flow<List<Package>> {
        return packageRepository.getPackagesByType(type)
    }

    fun getByEnabledState(enabled: Boolean): Flow<List<Package>> {
        return packageRepository.getPackagesByEnabledState(enabled)
    }

    fun getFilteredByState(filterState: PackageFilterState): Flow<List<Package>> {
        // Special case: Uninstalled filter
        if (filterState.packageState?.lowercase() == Constants.Packages.STATE_UNINSTALLED.lowercase()) {
            return flow {
                val result = packageRepository.getUninstalledSystemPackages()
                result.fold(
                    onSuccess = { packages -> emit(packages) },
                    onFailure = { emit(emptyList()) }
                )
            }
        }

        val baseFlow = when (filterState.packageType.lowercase()) {
            Constants.Packages.TYPE_USER -> getByType(PackageType.USER)
            Constants.Packages.TYPE_SYSTEM -> getByType(PackageType.SYSTEM)
            Constants.Packages.TYPE_ALL -> invoke()  // Return all packages
            else -> invoke()   // Default to all packages
        }

        // Apply profile filter (All, Personal, Work, Clone)
        val profileFilteredFlow = baseFlow.map { packages ->
            when (filterState.profileFilter.lowercase()) {
                "personal" -> packages.filter { !it.isWorkProfile && !it.isCloneProfile }
                "work" -> packages.filter { it.isWorkProfile }
                "clone" -> packages.filter { it.isCloneProfile }
                "all" -> packages
                else -> packages
            }
        }

        return if (filterState.packageState != null) {
            profileFilteredFlow.map { packages ->
                when (filterState.packageState.lowercase()) {
                    Constants.Packages.STATE_ENABLED.lowercase() -> packages.filter { it.isEnabled }
                    Constants.Packages.STATE_DISABLED.lowercase() -> packages.filter { !it.isEnabled }
                    else -> packages
                }
            }
        } else {
            profileFilteredFlow
        }
    }
}
