package io.github.dorumrr.de1984.data.repository

import android.content.Context
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.datasource.PackageDataSource
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.domain.model.NetworkAccessState
import io.github.dorumrr.de1984.domain.repository.NetworkPackageRepository
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class NetworkPackageRepositoryImpl(
    private val context: Context,
    private val packageDataSource: PackageDataSource
) : NetworkPackageRepository {
    
    override fun getNetworkPackages(): Flow<List<NetworkPackage>> {
        return packageDataSource.getPackages()
            .map { entities ->
                entities
                    // Show ALL apps (including those without network permissions)
                    // This allows users to proactively block apps before they gain internet permission via updates
                    .filter { it.isEnabled }  // Hide disabled apps from firewall screen
                    .map { entity ->
                        entity.toNetworkDomain().copy(
                            isSystemCritical = Constants.Firewall.isSystemCritical(entity.packageName)
                        )
                    }
                    .sortedBy { it.name.lowercase() }
            }
    }
    
    override fun getNetworkPackagesByType(type: PackageType): Flow<List<NetworkPackage>> {
        return getNetworkPackages()
            .map { packages -> packages.filter { it.type == type } }
    }
    
    override fun getNetworkPackagesByAccessState(state: NetworkAccessState): Flow<List<NetworkPackage>> {
        return getNetworkPackages()
            .map { packages ->
                packages.filter {
                    when (state) {
                        NetworkAccessState.ALLOWED -> it.isFullyAllowed
                        NetworkAccessState.BLOCKED -> it.isFullyBlocked
                        NetworkAccessState.PARTIAL -> it.isPartiallyBlocked
                    }
                }
            }
    }
    
    override suspend fun setNetworkAccess(packageName: String, userId: Int, allowed: Boolean): Result<Unit> {
        return try {
            val success = packageDataSource.setNetworkAccess(packageName, userId, allowed)
            if (success) {
                Result.success(Unit)
            } else {
                val errorMessage = if (allowed) {
                    context.getString(R.string.error_unable_to_allow_network)
                } else {
                    context.getString(R.string.error_unable_to_block_network)
                }
                Result.failure(SecurityException(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getNetworkPackage(packageName: String, userId: Int): Result<NetworkPackage> {
        return try {
            val entity = packageDataSource.getPackage(packageName, userId)
            if (entity != null) {
                Result.success(
                    entity.toNetworkDomain().copy(
                        isSystemCritical = Constants.Firewall.isSystemCritical(entity.packageName)
                    )
                )
            } else {
                Result.failure(Exception("Package not found: $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun isNetworkBlocked(packageName: String, userId: Int): Result<Boolean> {
        return try {
            val entity = packageDataSource.getPackage(packageName, userId)
            if (entity != null) {
                Result.success(entity.isNetworkBlocked)
            } else {
                Result.failure(Exception("Package not found: $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override fun getBlockedPackages(): Flow<List<NetworkPackage>> {
        return getNetworkPackagesByAccessState(NetworkAccessState.BLOCKED)
    }
    
    override fun getAllowedPackages(): Flow<List<NetworkPackage>> {
        return getNetworkPackagesByAccessState(NetworkAccessState.ALLOWED)
    }

    override suspend fun setWifiBlocking(packageName: String, userId: Int, blocked: Boolean): Result<Unit> {
        return try {
            val success = packageDataSource.setWifiBlocking(packageName, userId, blocked)
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to set WiFi blocking for $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setMobileBlocking(packageName: String, userId: Int, blocked: Boolean): Result<Unit> {
        return try {
            val success = packageDataSource.setMobileBlocking(packageName, userId, blocked)
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to set Mobile blocking for $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setRoamingBlocking(packageName: String, userId: Int, blocked: Boolean): Result<Unit> {
        return try {
            val success = packageDataSource.setRoamingBlocking(packageName, userId, blocked)
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to set Roaming blocking for $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setBackgroundBlocking(packageName: String, userId: Int, blocked: Boolean): Result<Unit> {
        return try {
            val success = packageDataSource.setBackgroundBlocking(packageName, userId, blocked)
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to set Background blocking for $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setLanBlocking(packageName: String, userId: Int, blocked: Boolean): Result<Unit> {
        return try {
            val success = packageDataSource.setLanBlocking(packageName, userId, blocked)
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to set LAN blocking for $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setAllNetworkBlocking(packageName: String, userId: Int, blocked: Boolean): Result<Unit> {
        return try {
            val success = packageDataSource.setAllNetworkBlocking(packageName, userId, blocked)
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to set all network blocking for $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setMobileAndRoaming(packageName: String, userId: Int, mobileBlocked: Boolean, roamingBlocked: Boolean): Result<Unit> {
        return try {
            val success = packageDataSource.setMobileAndRoaming(packageName, userId, mobileBlocked, roamingBlocked)
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to set mobile and roaming blocking for $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

private fun io.github.dorumrr.de1984.data.model.PackageEntity.toNetworkDomain(): NetworkPackage {
    return NetworkPackage(
        packageName = packageName,
        userId = userId,
        uid = uid,
        name = name,
        icon = icon,
        isEnabled = isEnabled,
        type = when (type) {
            Constants.Packages.TYPE_SYSTEM -> PackageType.SYSTEM
            Constants.Packages.TYPE_USER -> PackageType.USER
            else -> PackageType.USER
        },
        isNetworkBlocked = isNetworkBlocked,
        wifiBlocked = wifiBlocked,
        mobileBlocked = mobileBlocked,
        roamingBlocked = roamingBlocked,
        backgroundBlocked = backgroundBlocked,
        lanBlocked = lanBlocked,
        networkPermissions = permissions,
        versionName = versionName,
        versionCode = versionCode,
        installTime = installTime,
        updateTime = updateTime,
        isVpnApp = isVpnApp,
        isWorkProfile = isWorkProfile,
        isCloneProfile = isCloneProfile
    )
}
