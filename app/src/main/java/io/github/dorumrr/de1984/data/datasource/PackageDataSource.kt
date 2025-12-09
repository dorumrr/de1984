package io.github.dorumrr.de1984.data.datasource

import io.github.dorumrr.de1984.data.model.PackageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data source interface for package operations.
 *
 * All methods that operate on a specific package require both packageName AND userId
 * to properly support multi-user/work profile environments.
 */
interface PackageDataSource {
    // =============================================================================================
    // Package enumeration
    // =============================================================================================

    fun getPackages(): Flow<List<PackageEntity>>
    suspend fun getPackage(packageName: String, userId: Int): PackageEntity?
    suspend fun getUninstalledSystemPackages(): List<PackageEntity>

    // =============================================================================================
    // Package management (require userId for multi-user support)
    // =============================================================================================

    suspend fun setPackageEnabled(packageName: String, userId: Int, enabled: Boolean): Boolean
    suspend fun uninstallPackage(packageName: String, userId: Int): Boolean
    suspend fun reinstallPackage(packageName: String, userId: Int): Boolean
    suspend fun forceStopPackage(packageName: String, userId: Int): Boolean

    // =============================================================================================
    // Firewall operations (require userId for multi-user support)
    // =============================================================================================

    suspend fun setNetworkAccess(packageName: String, userId: Int, allowed: Boolean): Boolean
    suspend fun setWifiBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setMobileBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setRoamingBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setBackgroundBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setLanBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setAllNetworkBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setMobileAndRoaming(packageName: String, userId: Int, mobileBlocked: Boolean, roamingBlocked: Boolean): Boolean
}

