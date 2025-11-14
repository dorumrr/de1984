package io.github.dorumrr.de1984.domain.usecase

import android.util.Log
import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.domain.repository.PackageRepository
import io.github.dorumrr.de1984.presentation.viewmodel.PackageFilterState
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.flow.Flow
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
        val baseFlow = when (filterState.packageType.lowercase()) {
            Constants.Packages.TYPE_USER -> getByType(PackageType.USER)
            Constants.Packages.TYPE_SYSTEM -> getByType(PackageType.SYSTEM)
            Constants.Packages.TYPE_ALL -> invoke()  // Return all packages
            else -> invoke()   // Default to all packages
        }

        return if (filterState.packageState != null) {
            baseFlow.map { packages ->
                when (filterState.packageState.lowercase()) {
                    Constants.Packages.STATE_ENABLED.lowercase() -> packages.filter { it.isEnabled }
                    Constants.Packages.STATE_DISABLED.lowercase() -> packages.filter { !it.isEnabled }
                    else -> packages
                }
            }
        } else {
            baseFlow
        }
    }
}
