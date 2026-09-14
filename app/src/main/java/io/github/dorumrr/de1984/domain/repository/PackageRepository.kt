package io.github.dorumrr.de1984.domain.repository

import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.ReinstallBatchResult
import io.github.dorumrr.de1984.domain.model.UninstallBatchResult
import kotlinx.coroutines.flow.Flow

interface PackageRepository {

    fun getPackages(): Flow<List<Package>>

    suspend fun getUninstalledSystemPackages(): Result<List<Package>>

    suspend fun setPackageEnabled(packageName: String, userId: Int, enabled: Boolean): Result<Unit>

    suspend fun getPackage(packageName: String, userId: Int): Result<Package>

    suspend fun uninstallPackage(packageName: String, userId: Int): Result<Unit>

    suspend fun uninstallMultiplePackages(packages: List<Pair<String, Int>>): Result<UninstallBatchResult>

    suspend fun reinstallPackage(packageName: String, userId: Int): Result<Unit>

    suspend fun reinstallMultiplePackages(packages: List<Pair<String, Int>>): Result<ReinstallBatchResult>

    suspend fun forceStopPackage(packageName: String, userId: Int): Result<Unit>
}
