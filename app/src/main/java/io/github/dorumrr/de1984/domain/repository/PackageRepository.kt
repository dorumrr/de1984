package io.github.dorumrr.de1984.domain.repository

import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.domain.model.ReinstallBatchResult
import io.github.dorumrr.de1984.domain.model.UninstallBatchResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for package management operations.
 *
 * All methods that operate on a specific package require both packageName AND userId
 * to properly support multi-user/work profile environments.
 */
interface PackageRepository {

    fun getPackages(): Flow<List<Package>>

    fun getPackagesByType(type: PackageType): Flow<List<Package>>

    fun getPackagesByEnabledState(enabled: Boolean): Flow<List<Package>>

    suspend fun getUninstalledSystemPackages(): Result<List<Package>>

    suspend fun setPackageEnabled(packageName: String, userId: Int, enabled: Boolean): Result<Unit>

    suspend fun getPackage(packageName: String, userId: Int): Result<Package>

    suspend fun uninstallPackage(packageName: String, userId: Int): Result<Unit>

    suspend fun uninstallMultiplePackages(packages: List<Pair<String, Int>>): Result<UninstallBatchResult>

    suspend fun reinstallPackage(packageName: String, userId: Int): Result<Unit>

    suspend fun reinstallMultiplePackages(packages: List<Pair<String, Int>>): Result<ReinstallBatchResult>

    suspend fun forceStopPackage(packageName: String, userId: Int): Result<Unit>
}
