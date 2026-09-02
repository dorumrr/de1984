package io.github.dorumrr.de1984.data.datasource

import io.github.dorumrr.de1984.data.model.PackageEntity
import kotlinx.coroutines.flow.Flow

interface PackageDataSource {

    fun getPackages(): Flow<List<PackageEntity>>

    /**
     * Drop the short-lived scan cache so the NEXT collection really rescans.
     *
     * The rows carry more than the package list: the blocking flags are PAINTED at scan time from
     * the firewall rules and from "Allow Firewall Critical Packages". When that setting moves, the
     * cached rows describe the old setting, and a plain re-collect inside the cache window replays
     * them - leaving the screen claiming one thing while the backends enforce another.
     */
    fun invalidateCache()
    suspend fun getPackage(packageName: String, userId: Int): PackageEntity?
    suspend fun getUninstalledSystemPackages(): List<PackageEntity>


    suspend fun setPackageEnabled(packageName: String, userId: Int, enabled: Boolean): Boolean
    suspend fun uninstallPackage(packageName: String, userId: Int): Boolean
    suspend fun reinstallPackage(packageName: String, userId: Int): Boolean
    suspend fun forceStopPackage(packageName: String, userId: Int): Boolean


    suspend fun setNetworkAccess(packageName: String, userId: Int, allowed: Boolean): Boolean
    suspend fun setWifiBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setMobileBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setRoamingBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setBackgroundBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setLanBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setAllNetworkBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean
    suspend fun setMobileAndRoaming(packageName: String, userId: Int, mobileBlocked: Boolean, roamingBlocked: Boolean): Boolean
}

