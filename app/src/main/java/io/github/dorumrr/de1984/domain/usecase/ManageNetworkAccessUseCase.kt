package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.domain.repository.NetworkPackageRepository

class ManageNetworkAccessUseCase constructor(
    private val networkPackageRepository: NetworkPackageRepository
) {

    suspend fun isNetworkBlocked(packageName: String): Result<Boolean> {
        return networkPackageRepository.isNetworkBlocked(packageName)
    }

    suspend fun setNetworkAccess(packageName: String, allowed: Boolean): Result<Unit> {
        return networkPackageRepository.setNetworkAccess(packageName, allowed)
    }

    suspend fun setWifiBlocking(packageName: String, blocked: Boolean): Result<Unit> {
        return networkPackageRepository.setWifiBlocking(packageName, blocked)
    }

    suspend fun setMobileBlocking(packageName: String, blocked: Boolean): Result<Unit> {
        return networkPackageRepository.setMobileBlocking(packageName, blocked)
    }

    suspend fun setRoamingBlocking(packageName: String, blocked: Boolean): Result<Unit> {
        return networkPackageRepository.setRoamingBlocking(packageName, blocked)
    }
}
