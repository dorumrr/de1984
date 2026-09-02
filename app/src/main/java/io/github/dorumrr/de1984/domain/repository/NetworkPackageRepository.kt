package io.github.dorumrr.de1984.domain.repository

import io.github.dorumrr.de1984.domain.model.NetworkPackage
import io.github.dorumrr.de1984.domain.model.PackageType
import io.github.dorumrr.de1984.domain.model.NetworkAccessState
import kotlinx.coroutines.flow.Flow

interface NetworkPackageRepository {

    fun getNetworkPackages(): Flow<List<NetworkPackage>>

    /** See PackageDataSource.invalidateCache. */
    fun invalidatePackageCache()

    fun getNetworkPackagesByType(type: PackageType): Flow<List<NetworkPackage>>

    fun getNetworkPackagesByAccessState(state: NetworkAccessState): Flow<List<NetworkPackage>>

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
    suspend fun setNetworkAccess(packageName: String, userId: Int, allowed: Boolean, stampDefaultOnUntouched: Boolean = true): Result<Unit>

    suspend fun getNetworkPackage(packageName: String, userId: Int): Result<NetworkPackage>

    suspend fun isNetworkBlocked(packageName: String, userId: Int): Result<Boolean>

    fun getBlockedPackages(): Flow<List<NetworkPackage>>

    fun getAllowedPackages(): Flow<List<NetworkPackage>>

    suspend fun setWifiBlocking(packageName: String, userId: Int, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Result<Unit>

    suspend fun setMobileBlocking(packageName: String, userId: Int, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Result<Unit>

    suspend fun setRoamingBlocking(packageName: String, userId: Int, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Result<Unit>

    suspend fun setBackgroundBlocking(packageName: String, userId: Int, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Result<Unit>

    suspend fun setLanBlocking(packageName: String, userId: Int, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Result<Unit>

    suspend fun setAllNetworkBlocking(packageName: String, userId: Int, blocked: Boolean, stampDefaultOnUntouched: Boolean = true): Result<Unit>

    suspend fun setMobileAndRoaming(packageName: String, userId: Int, mobileBlocked: Boolean, roamingBlocked: Boolean, stampDefaultOnUntouched: Boolean = true): Result<Unit>
}
