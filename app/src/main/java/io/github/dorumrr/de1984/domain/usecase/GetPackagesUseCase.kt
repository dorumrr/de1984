package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.repository.PackageRepository
import kotlinx.coroutines.flow.Flow

class GetPackagesUseCase constructor(
    private val packageRepository: PackageRepository
) {

    operator fun invoke(): Flow<List<Package>> {
        return packageRepository.getPackages()
    }

    // Empty when root or Shizuku cannot read it; the installed scan behind invoke() never holds these apps.
    suspend fun uninstalledSystemPackages(): List<Package> =
        packageRepository.getUninstalledSystemPackages().getOrElse { emptyList() }
}
