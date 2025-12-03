package io.github.dorumrr.de1984.domain.usecase

import io.github.dorumrr.de1984.domain.repository.NetworkPackageRepository

/**
 * Use case for managing network access for packages.
 *
 * All methods require both packageName AND userId to properly support
 * multi-user/work profile environments.
 */
class ManageNetworkAccessUseCase constructor(
    private val networkPackageRepository: NetworkPackageRepository
) {

    suspend fun isNetworkBlocked(packageName: String, userId: Int = 0): Result<Boolean> {
        return networkPackageRepository.isNetworkBlocked(packageName, userId)
    }

    suspend fun setNetworkAccess(packageName: String, userId: Int = 0, allowed: Boolean): Result<Unit> {
        return networkPackageRepository.setNetworkAccess(packageName, userId, allowed)
    }

    suspend fun setWifiBlocking(packageName: String, userId: Int = 0, blocked: Boolean): Result<Unit> {
        return networkPackageRepository.setWifiBlocking(packageName, userId, blocked)
    }

    suspend fun setMobileBlocking(packageName: String, userId: Int = 0, blocked: Boolean): Result<Unit> {
        return networkPackageRepository.setMobileBlocking(packageName, userId, blocked)
    }

    suspend fun setRoamingBlocking(packageName: String, userId: Int = 0, blocked: Boolean): Result<Unit> {
        return networkPackageRepository.setRoamingBlocking(packageName, userId, blocked)
    }

    suspend fun setBackgroundBlocking(packageName: String, userId: Int = 0, blocked: Boolean): Result<Unit> {
        return networkPackageRepository.setBackgroundBlocking(packageName, userId, blocked)
    }

    suspend fun setLanBlocking(packageName: String, userId: Int = 0, blocked: Boolean): Result<Unit> {
        return networkPackageRepository.setLanBlocking(packageName, userId, blocked)
    }

    suspend fun setAllNetworkBlocking(packageName: String, userId: Int = 0, blocked: Boolean): Result<Unit> {
        return networkPackageRepository.setAllNetworkBlocking(packageName, userId, blocked)
    }

    suspend fun setMobileAndRoaming(packageName: String, userId: Int = 0, mobileBlocked: Boolean, roamingBlocked: Boolean): Result<Unit> {
        return networkPackageRepository.setMobileAndRoaming(packageName, userId, mobileBlocked, roamingBlocked)
    }
}
