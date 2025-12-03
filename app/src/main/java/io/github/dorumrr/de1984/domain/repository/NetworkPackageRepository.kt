package io.github.dorumrr.de1984.domain.repository

import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.domain.model.NetworkAccessState
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for network/firewall package operations.
 *
 * All methods that operate on a specific package require both packageName AND userId
 * to properly support multi-user/work profile environments.
 */
interface NetworkPackageRepository {

    fun getNetworkPackages(): Flow<List<NetworkPackage>>

    fun getNetworkPackagesByType(type: PackageType): Flow<List<NetworkPackage>>

    fun getNetworkPackagesByAccessState(state: NetworkAccessState): Flow<List<NetworkPackage>>

    suspend fun setNetworkAccess(packageName: String, userId: Int, allowed: Boolean): Result<Unit>

    suspend fun getNetworkPackage(packageName: String, userId: Int): Result<NetworkPackage>

    suspend fun isNetworkBlocked(packageName: String, userId: Int): Result<Boolean>

    fun getBlockedPackages(): Flow<List<NetworkPackage>>

    fun getAllowedPackages(): Flow<List<NetworkPackage>>

    suspend fun setWifiBlocking(packageName: String, userId: Int, blocked: Boolean): Result<Unit>

    suspend fun setMobileBlocking(packageName: String, userId: Int, blocked: Boolean): Result<Unit>

    suspend fun setRoamingBlocking(packageName: String, userId: Int, blocked: Boolean): Result<Unit>

    suspend fun setBackgroundBlocking(packageName: String, userId: Int, blocked: Boolean): Result<Unit>

    suspend fun setLanBlocking(packageName: String, userId: Int, blocked: Boolean): Result<Unit>

    suspend fun setAllNetworkBlocking(packageName: String, userId: Int, blocked: Boolean): Result<Unit>

    suspend fun setMobileAndRoaming(packageName: String, userId: Int, mobileBlocked: Boolean, roamingBlocked: Boolean): Result<Unit>
}
