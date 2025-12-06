package io.github.dorumrr.de1984.data.repository

import android.content.Context
import android.util.Log
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.datasource.PackageDataSource
import io.github.dorumrr.de1984.data.model.toDomain
import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.domain.model.ReinstallBatchResult
import io.github.dorumrr.de1984.domain.model.UninstallBatchResult
import io.github.dorumrr.de1984.domain.repository.PackageRepository
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

class PackageRepositoryImpl(
    private val context: Context,
    private val packageDataSource: PackageDataSource,
    private val onDataChanged: (() -> Unit)? = null
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

    override suspend fun getUninstalledSystemPackages(): Result<List<Package>> {
        return try {
            val entities = packageDataSource.getUninstalledSystemPackages()
            val packages = entities.map { it.toDomain() }
            Result.success(packages)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setPackageEnabled(packageName: String, userId: Int, enabled: Boolean): Result<Unit> {
        return try {
            val success = packageDataSource.setPackageEnabled(packageName, userId, enabled)
            if (success) {
                // Notify observers that package data changed
                onDataChanged?.invoke()
                Result.success(Unit)
            } else {
                val errorMessage = if (enabled) {
                    context.getString(R.string.error_unable_to_enable_package)
                } else {
                    context.getString(R.string.error_unable_to_disable_package)
                }
                Result.failure(SecurityException(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getPackage(packageName: String, userId: Int): Result<Package> {
        return try {
            val entity = packageDataSource.getPackage(packageName, userId)
            if (entity != null) {
                Result.success(entity.toDomain())
            } else {
                Result.failure(Exception("Package not found: $packageName"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uninstallPackage(packageName: String, userId: Int): Result<Unit> {
        return try {
            val success = packageDataSource.uninstallPackage(packageName, userId)
            if (success) {
                Result.success(Unit)
            } else {
                val errorMessage = context.getString(R.string.error_unable_to_uninstall_package)
                Result.failure(SecurityException(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uninstallMultiplePackages(packages: List<Pair<String, Int>>): Result<UninstallBatchResult> {
        return try {
            val succeeded = mutableListOf<String>()
            val failed = mutableListOf<Pair<String, String>>()

            packages.forEach { (packageName, userId) ->
                val result = uninstallPackage(packageName, userId)
                result.fold(
                    onSuccess = { succeeded.add(packageName) },
                    onFailure = { error -> failed.add(packageName to (error.message ?: context.getString(R.string.error_unknown))) }
                )
            }

            Result.success(UninstallBatchResult(succeeded, failed))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun reinstallPackage(packageName: String, userId: Int): Result<Unit> {
        return try {
            val success = packageDataSource.reinstallPackage(packageName, userId)
            if (success) {
                Result.success(Unit)
            } else {
                val errorMessage = context.getString(R.string.error_unable_to_reinstall_package)
                Result.failure(SecurityException(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun reinstallMultiplePackages(packages: List<Pair<String, Int>>): Result<ReinstallBatchResult> {
        return try {
            val succeeded = mutableListOf<String>()
            val failed = mutableListOf<Pair<String, String>>()

            packages.forEach { (packageName, userId) ->
                val result = reinstallPackage(packageName, userId)
                result.fold(
                    onSuccess = { succeeded.add(packageName) },
                    onFailure = { error -> failed.add(packageName to (error.message ?: context.getString(R.string.error_unknown))) }
                )
            }

            Result.success(ReinstallBatchResult(succeeded, failed))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun forceStopPackage(packageName: String, userId: Int): Result<Unit> {
        return try {
            val success = packageDataSource.forceStopPackage(packageName, userId)
            if (success) {
                Result.success(Unit)
            } else {
                val errorMessage = context.getString(R.string.error_unable_to_force_stop_package)
                Result.failure(SecurityException(errorMessage))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
