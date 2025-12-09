package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.domain.model.ReinstallBatchResult
import io.github.dorumrr.de1984.domain.model.UninstallBatchResult
import io.github.dorumrr.de1984.domain.repository.PackageRepository

/**
 * Use case for managing packages (enable/disable, uninstall, reinstall, force stop).
 *
 * All methods require both packageName AND userId to properly support
 * multi-user/work profile environments.
 */
class ManagePackageUseCase constructor(
    private val packageRepository: PackageRepository
) {

    suspend fun setPackageEnabled(packageName: String, userId: Int = 0, enabled: Boolean): Result<Unit> {
        return packageRepository.setPackageEnabled(packageName, userId, enabled)
    }

    suspend fun uninstallPackage(packageName: String, userId: Int = 0): Result<Unit> {
        return packageRepository.uninstallPackage(packageName, userId)
    }

    suspend fun uninstallMultiplePackages(packages: List<Pair<String, Int>>): Result<UninstallBatchResult> {
        return packageRepository.uninstallMultiplePackages(packages)
    }

    suspend fun reinstallPackage(packageName: String, userId: Int = 0): Result<Unit> {
        return packageRepository.reinstallPackage(packageName, userId)
    }

    suspend fun reinstallMultiplePackages(packages: List<Pair<String, Int>>): Result<ReinstallBatchResult> {
        return packageRepository.reinstallMultiplePackages(packages)
    }

    suspend fun forceStopPackage(packageName: String, userId: Int = 0): Result<Unit> {
        return packageRepository.forceStopPackage(packageName, userId)
    }
}
