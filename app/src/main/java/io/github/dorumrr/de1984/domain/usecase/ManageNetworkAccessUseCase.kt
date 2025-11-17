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

    suspend fun setBackgroundBlocking(packageName: String, blocked: Boolean): Result<Unit> {
        return networkPackageRepository.setBackgroundBlocking(packageName, blocked)
    }

    suspend fun setAllNetworkBlocking(packageName: String, blocked: Boolean): Result<Unit> {
        return networkPackageRepository.setAllNetworkBlocking(packageName, blocked)
    }

    suspend fun setMobileAndRoaming(packageName: String, mobileBlocked: Boolean, roamingBlocked: Boolean): Result<Unit> {
        return networkPackageRepository.setMobileAndRoaming(packageName, mobileBlocked, roamingBlocked)
    }
}
