package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.domain.repository.NetworkPackageRepository

class ManageNetworkAccessUseCase constructor(
    private val networkPackageRepository: NetworkPackageRepository
) {

    suspend fun isNetworkBlocked(packageName: String, userId: Int = 0): Result<Boolean> {
        return networkPackageRepository.isNetworkBlocked(packageName, userId)
    }

    suspend fun setNetworkAccess(packageName: String, userId: Int = 0, allowed: Boolean, stampDefaultOnUntouched: Boolean = true): Result<Unit> {
        return networkPackageRepository.setNetworkAccess(packageName, userId, allowed, stampDefaultOnUntouched)
    }

    suspend fun setWifiBlocking(packageName: String, userId: Int = 0, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Result<Unit> {
        return networkPackageRepository.setWifiBlocking(packageName, userId, blocked, stampDefaultOnUntouched)
    }

    suspend fun setMobileBlocking(packageName: String, userId: Int = 0, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Result<Unit> {
        return networkPackageRepository.setMobileBlocking(packageName, userId, blocked, stampDefaultOnUntouched)
    }

    suspend fun setRoamingBlocking(packageName: String, userId: Int = 0, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Result<Unit> {
        return networkPackageRepository.setRoamingBlocking(packageName, userId, blocked, stampDefaultOnUntouched)
    }

    suspend fun setBackgroundBlocking(packageName: String, userId: Int = 0, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Result<Unit> {
        return networkPackageRepository.setBackgroundBlocking(packageName, userId, blocked, stampDefaultOnUntouched)
    }

    suspend fun setLanBlocking(packageName: String, userId: Int = 0, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Result<Unit> {
        return networkPackageRepository.setLanBlocking(packageName, userId, blocked, stampDefaultOnUntouched)
    }

    suspend fun setAllNetworkBlocking(packageName: String, userId: Int = 0, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Result<Unit> {
        return networkPackageRepository.setAllNetworkBlocking(packageName, userId, blocked, stampDefaultOnUntouched)
    }

    suspend fun setMobileAndRoaming(packageName: String, userId: Int = 0, mobileBlocked: Boolean, roamingBlocked: Boolean, stampDefaultOnUntouched: Boolean = true): Result<Unit> {
        return networkPackageRepository.setMobileAndRoaming(packageName, userId, mobileBlocked, roamingBlocked, stampDefaultOnUntouched)
    }
}
