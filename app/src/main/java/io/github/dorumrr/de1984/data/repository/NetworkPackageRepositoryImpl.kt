package io.github.dorumrr.de1984.data.repository

import io.github.dorumrr.de1984.data.datasource.PackageDataSource
import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.domain.model.NetworkAccessState
import io.github.dorumrr.de1984.domain.repository.NetworkPackageRepository
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class NetworkPackageRepositoryImpl(
    private val packageDataSource: PackageDataSource
) : NetworkPackageRepository {
    
    override fun getNetworkPackages(): Flow<List<NetworkPackage>> {
        return packageDataSource.getPackages()
            .map { entities ->
                entities
                    .filter { it.hasNetworkAccess }
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
    
    override suspend fun setNetworkAccess(packageName: String, allowed: Boolean): Result<Unit> {
        return try {
            val success = packageDataSource.setNetworkAccess(packageName, allowed)
            if (success) {
                Result.success(Unit)
            } else {
                val action = if (allowed) "allow" else "block"
                val errorMessage = "Unable to $action network access. Root access required for firewall operations."
                Result.failure(SecurityException(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getNetworkPackage(packageName: String): Result<NetworkPackage> {
        return try {
            val entity = packageDataSource.getPackage(packageName)
            if (entity != null && entity.hasNetworkAccess) {
                Result.success(
                    entity.toNetworkDomain().copy(
                        isSystemCritical = Constants.Firewall.isSystemCritical(entity.packageName)
                    )
                )
            } else {
                Result.failure(Exception("Network package not found: $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun isNetworkBlocked(packageName: String): Result<Boolean> {
        return try {
            val entity = packageDataSource.getPackage(packageName)
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

    override suspend fun setWifiBlocking(packageName: String, blocked: Boolean): Result<Unit> {
        return try {
            val success = packageDataSource.setWifiBlocking(packageName, blocked)
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to set WiFi blocking for $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setMobileBlocking(packageName: String, blocked: Boolean): Result<Unit> {
        return try {
            val success = packageDataSource.setMobileBlocking(packageName, blocked)
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to set Mobile blocking for $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setRoamingBlocking(packageName: String, blocked: Boolean): Result<Unit> {
        return try {
            val success = packageDataSource.setRoamingBlocking(packageName, blocked)
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to set Roaming blocking for $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setAllNetworkBlocking(packageName: String, blocked: Boolean): Result<Unit> {
        return try {
            val success = packageDataSource.setAllNetworkBlocking(packageName, blocked)
            if (success) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to set all network blocking for $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setMobileAndRoaming(packageName: String, mobileBlocked: Boolean, roamingBlocked: Boolean): Result<Unit> {
        return try {
            val success = packageDataSource.setMobileAndRoaming(packageName, mobileBlocked, roamingBlocked)
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
        networkPermissions = permissions,
        versionName = versionName,
        versionCode = versionCode,
        installTime = installTime,
        updateTime = updateTime,
        isVpnApp = isVpnApp
    )
}
