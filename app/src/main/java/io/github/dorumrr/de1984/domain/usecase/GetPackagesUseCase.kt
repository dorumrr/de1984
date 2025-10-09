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
        Log.d("GetPackagesUseCase", ">>> getFilteredByState: type=${filterState.packageType}, state=${filterState.packageState}")

        val baseFlow = when (filterState.packageType.lowercase()) {
            Constants.Packages.TYPE_USER -> getByType(PackageType.USER)
            Constants.Packages.TYPE_SYSTEM -> getByType(PackageType.SYSTEM)
            else -> getByType(PackageType.USER)
        }.onEach { packages ->
            Log.d("GetPackagesUseCase", ">>> baseFlow emitted: ${packages.size} packages")
        }

        return if (filterState.packageState != null) {
            baseFlow.map { packages ->
                val filtered = when (filterState.packageState.lowercase()) {
                    Constants.Packages.STATE_ENABLED.lowercase() -> packages.filter { it.isEnabled }
                    Constants.Packages.STATE_DISABLED.lowercase() -> packages.filter { !it.isEnabled }
                    else -> packages
                }
                Log.d("GetPackagesUseCase", ">>> After state filter: ${filtered.size} packages")
                filtered
            }
        } else {
            baseFlow
        }
    }
}
