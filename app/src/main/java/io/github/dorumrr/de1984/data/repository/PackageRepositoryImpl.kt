package io.github.dorumrr.de1984.data.repository

import android.content.Context
import android.util.Log
import io.github.dorumrr.de1984.R
import io.github.dorumrr.de1984.data.common.De1984Error
import io.github.dorumrr.de1984.data.datasource.PackageDataSource
import io.github.dorumrr.de1984.data.model.toDomain
import io.github.dorumrr.de1984.domain.model.Package
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
    private val privileged: () -> Boolean,
    private val onDataChanged: (() -> Unit)? = null
) : PackageRepository {
    
    // With root or Shizuku granted, a failure is the command's own: it must not claim missing access or open the access banner.
    private fun actionFailed(noAccessMessage: Int, failedMessage: Int, operation: String): Result<Unit> =
        if (privileged()) {
            Result.failure(De1984Error.SystemAccessFailed(context.getString(failedMessage), operation))
        } else {
            Result.failure(SecurityException(context.getString(noAccessMessage)))
        }

    override fun getPackages(): Flow<List<Package>> {
        return packageDataSource.getPackages()
            .map { entities ->
                entities
                    .filter { !Constants.App.isOwnApp(it.packageName) }
                    .map { it.toDomain() }
            }
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
                onDataChanged?.invoke()
                Result.success(Unit)
            } else if (enabled) {
                actionFailed(R.string.error_unable_to_enable_package, R.string.error_package_enable_failed, "enable")
            } else {
                actionFailed(R.string.error_unable_to_disable_package, R.string.error_package_disable_failed, "disable")
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
                actionFailed(R.string.error_unable_to_uninstall_package, R.string.error_package_uninstall_failed, "uninstall")
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
                actionFailed(R.string.error_unable_to_reinstall_package, R.string.error_package_reinstall_failed, "reinstall")
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
                actionFailed(R.string.error_unable_to_force_stop_package, R.string.error_package_force_stop_failed, "force stop")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
