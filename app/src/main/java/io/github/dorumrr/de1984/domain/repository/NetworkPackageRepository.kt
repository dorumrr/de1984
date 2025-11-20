package io.github.dorumrr.de1984.domain.repository

import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.domain.model.NetworkAccessState
import kotlinx.coroutines.flow.Flow

interface NetworkPackageRepository {
    
    fun getNetworkPackages(): Flow<List<NetworkPackage>>
    
    fun getNetworkPackagesByType(type: PackageType): Flow<List<NetworkPackage>>
    
    fun getNetworkPackagesByAccessState(state: NetworkAccessState): Flow<List<NetworkPackage>>
    
    suspend fun setNetworkAccess(packageName: String, allowed: Boolean): Result<Unit>
    
    suspend fun getNetworkPackage(packageName: String): Result<NetworkPackage>
    
    suspend fun isNetworkBlocked(packageName: String): Result<Boolean>
    
    fun getBlockedPackages(): Flow<List<NetworkPackage>>
    
    fun getAllowedPackages(): Flow<List<NetworkPackage>>

    suspend fun setWifiBlocking(packageName: String, blocked: Boolean): Result<Unit>

    suspend fun setMobileBlocking(packageName: String, blocked: Boolean): Result<Unit>

    suspend fun setRoamingBlocking(packageName: String, blocked: Boolean): Result<Unit>

    suspend fun setBackgroundBlocking(packageName: String, blocked: Boolean): Result<Unit>

    suspend fun setLanBlocking(packageName: String, blocked: Boolean): Result<Unit>

    suspend fun setAllNetworkBlocking(packageName: String, blocked: Boolean): Result<Unit>

    suspend fun setMobileAndRoaming(packageName: String, mobileBlocked: Boolean, roamingBlocked: Boolean): Result<Unit>
}
