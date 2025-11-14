package io.github.dorumrr.de1984.domain.repository

import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.domain.model.ReinstallBatchResult
import io.github.dorumrr.de1984.domain.model.UninstallBatchResult
import kotlinx.coroutines.flow.Flow

interface PackageRepository {

    fun getPackages(): Flow<List<Package>>

    fun getPackagesByType(type: PackageType): Flow<List<Package>>

    fun getPackagesByEnabledState(enabled: Boolean): Flow<List<Package>>

    suspend fun getUninstalledSystemPackages(): Result<List<Package>>

    suspend fun setPackageEnabled(packageName: String, enabled: Boolean): Result<Unit>

    suspend fun getPackage(packageName: String): Result<Package>

    suspend fun uninstallPackage(packageName: String): Result<Unit>

    suspend fun uninstallMultiplePackages(packageNames: List<String>): Result<UninstallBatchResult>

    suspend fun reinstallPackage(packageName: String): Result<Unit>

    suspend fun reinstallMultiplePackages(packageNames: List<String>): Result<ReinstallBatchResult>

    suspend fun forceStopPackage(packageName: String): Result<Unit>
}
