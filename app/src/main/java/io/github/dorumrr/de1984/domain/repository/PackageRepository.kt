package io.github.dorumrr.de1984.domain.repository

import io.github.dorumrr.de1984.domain.model.Package
import io.github.dorumrr.de1984.domain.model.PackageType
import kotlinx.coroutines.flow.Flow

interface PackageRepository {
    
    fun getPackages(): Flow<List<Package>>
    
    fun getPackagesByType(type: PackageType): Flow<List<Package>>
    
    fun getPackagesByEnabledState(enabled: Boolean): Flow<List<Package>>
    
    suspend fun setPackageEnabled(packageName: String, enabled: Boolean): Result<Unit>
    
    suspend fun getPackage(packageName: String): Result<Package>
    
    suspend fun uninstallPackage(packageName: String): Result<Unit>
    
    suspend fun forceStopPackage(packageName: String): Result<Unit>
}
