package io.github.dorumrr.de1984.data.datasource

import io.github.dorumrr.de1984.data.model.PackageEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

interface PackageDataSource {
    fun getPackages(): Flow<List<PackageEntity>>
    suspend fun getPackage(packageName: String): PackageEntity?
    suspend fun setPackageEnabled(packageName: String, enabled: Boolean): Boolean
    suspend fun uninstallPackage(packageName: String): Boolean
    suspend fun forceStopPackage(packageName: String): Boolean

    suspend fun setNetworkAccess(packageName: String, allowed: Boolean): Boolean
    suspend fun setWifiBlocking(packageName: String, blocked: Boolean): Boolean
    suspend fun setMobileBlocking(packageName: String, blocked: Boolean): Boolean
    suspend fun setRoamingBlocking(packageName: String, blocked: Boolean): Boolean
}

