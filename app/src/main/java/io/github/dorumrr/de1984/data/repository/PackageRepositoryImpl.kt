package io.github.dorumrr.de1984.data.repository

import android.util.Log
import io.github.dorumrr.de1984.data.datasource.PackageDataSource
import io.github.dorumrr.de1984.data.model.toDomain
import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.domain.repository.PackageRepository
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class PackageRepositoryImpl(
    private val packageDataSource: PackageDataSource
) : PackageRepository {
    
    override fun getPackages(): Flow<List<Package>> {
        return packageDataSource.getPackages()
            .map { entities ->
                entities
                    .filter { !Constants.App.isOwnApp(it.packageName) }
                    .map { it.toDomain() }
            }
    }

    override fun getPackagesByType(type: PackageType): Flow<List<Package>> {
        return getPackages()
            .map { packages ->
                packages.filter { it.type == type }
            }
    }
    
    override fun getPackagesByEnabledState(enabled: Boolean): Flow<List<Package>> {
        return getPackages()
            .map { packages -> packages.filter { it.isEnabled == enabled } }
    }
    
    override suspend fun setPackageEnabled(packageName: String, enabled: Boolean): Result<Unit> {
        return try {
            val success = packageDataSource.setPackageEnabled(packageName, enabled)
            if (success) {
                Result.success(Unit)
            } else {
                val action = if (enabled) "enable" else "disable"
                val errorMessage = "Unable to $action package. Root access required for package management."
                Result.failure(SecurityException(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun getPackage(packageName: String): Result<Package> {
        return try {
            val entity = packageDataSource.getPackage(packageName)
            if (entity != null) {
                Result.success(entity.toDomain())
            } else {
                Result.failure(Exception("Package not found: $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun uninstallPackage(packageName: String): Result<Unit> {
        return try {
            val success = packageDataSource.uninstallPackage(packageName)
            if (success) {
                Result.success(Unit)
            } else {
                val errorMessage = "Unable to uninstall package. Root access required for package management."
                Result.failure(SecurityException(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun forceStopPackage(packageName: String): Result<Unit> {
        return try {
            val success = packageDataSource.forceStopPackage(packageName)
            if (success) {
                Result.success(Unit)
            } else {
                val errorMessage = "Unable to force stop package. Root access required for package management."
                Result.failure(SecurityException(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
