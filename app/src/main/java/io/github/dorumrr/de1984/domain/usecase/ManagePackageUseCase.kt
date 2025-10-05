package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.domain.repository.PackageRepository
import javax.inject.Inject

class ManagePackageUseCase @Inject constructor(
    private val packageRepository: PackageRepository
) {
    
    suspend fun setPackageEnabled(packageName: String, enabled: Boolean): Result<Unit> {
        return packageRepository.setPackageEnabled(packageName, enabled)
    }
    
    suspend fun uninstallPackage(packageName: String): Result<Unit> {
        return packageRepository.uninstallPackage(packageName)
    }
    
    suspend fun forceStopPackage(packageName: String): Result<Unit> {
        return packageRepository.forceStopPackage(packageName)
    }
}
