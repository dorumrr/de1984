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


    /**
     * [stampDefaultOnUntouched] - whether a FIRST rule created by this call should carry the Block
     * All default onto the networks the call does not name.
     *
     * True preserves what a plain row was showing: with no rule yet it painted every network from
     * the default, so an unblock tap must keep the others blocked or three of them silently open.
     *
     * FALSE for a row whose display came from a NEIGHBOUR in the same uid. That row was showing the
     * neighbour's blocks, not the default, and stamping the default onto it created real blocks the
     * row never displayed and the user never asked for. Such a row's own first rule should claim
     * nothing beyond the tap - the neighbour's blocks keep showing through the union anyway.
     */
    suspend fun setNetworkAccess(packageName: String, userId: Int, allowed: Boolean, stampDefaultOnUntouched: Boolean = true): Boolean
    suspend fun setWifiBlocking(packageName: String, userId: Int, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Boolean
    suspend fun setMobileBlocking(packageName: String, userId: Int, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Boolean
    suspend fun setRoamingBlocking(packageName: String, userId: Int, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Boolean
    suspend fun setBackgroundBlocking(packageName: String, userId: Int, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Boolean
    suspend fun setLanBlocking(packageName: String, userId: Int, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Boolean
    suspend fun setAllNetworkBlocking(packageName: String, userId: Int, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Boolean
    suspend fun setMobileAndRoaming(packageName: String, userId: Int, mobileBlocked: Boolean, roamingBlocked: Boolean, stampDefaultOnUntouched: Boolean = true): Boolean
}

/**
 * The package scan failed. Wraps whatever really went wrong.
 *
 * A type rather than a message test. The screens need to say "could not read the app list" in the
 * user's language, and they were picking that out by catching IllegalStateException - which also
 * catches unrelated ones from file parsing, and misses a SecurityException or RemoteException from
 * the enumeration, letting developer English reach the user. Note kotlinx CancellationException IS
 * an IllegalStateException, which is how badly that test could go wrong.
 */
class PackageScanException(message: String, cause: Throwable? = null) : Exception(message, cause)
