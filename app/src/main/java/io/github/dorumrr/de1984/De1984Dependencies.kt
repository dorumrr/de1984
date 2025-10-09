package io.github.dorumrr.de1984

import android.content.Context
import androidx.room.Room
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.data.common.PermissionManager
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.data.database.De1984Database
import io.github.dorumrr.de1984.data.database.dao.FirewallRuleDao
import io.github.dorumrr.de1984.data.datasource.AndroidPackageDataSource
import io.github.dorumrr.de1984.data.datasource.PackageDataSource
import io.github.dorumrr.de1984.data.monitor.NetworkStateMonitor
import io.github.dorumrr.de1984.data.monitor.ScreenStateMonitor
import io.github.dorumrr.de1984.data.repository.FirewallRepositoryImpl
import io.github.dorumrr.de1984.data.repository.NetworkPackageRepositoryImpl
import io.github.dorumrr.de1984.data.repository.PackageRepositoryImpl
import io.github.dorumrr.de1984.data.service.NewAppNotificationManager
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.domain.repository.NetworkPackageRepository
import io.github.dorumrr.de1984.domain.repository.PackageRepository
import io.github.dorumrr.de1984.domain.usecase.*
import io.github.dorumrr.de1984.ui.common.SuperuserBannerState

/**
 * ServiceLocator for managing application dependencies.
 * Replaces Hilt dependency injection with manual DI for better F-Droid reproducible builds.
 */
class De1984Dependencies(private val context: Context) {

    // =============================================================================================
    // Database
    // =============================================================================================

    val database: De1984Database by lazy {
        Room.databaseBuilder(
            context.applicationContext,
            De1984Database::class.java,
            "de1984_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    val firewallRuleDao: FirewallRuleDao by lazy {
        database.firewallRuleDao()
    }

    // =============================================================================================
    // Managers (Singletons)
    // =============================================================================================

    val rootManager: RootManager by lazy {
        RootManager(context)
    }

    val errorHandler: ErrorHandler by lazy {
        ErrorHandler()
    }

    val permissionManager: PermissionManager by lazy {
        PermissionManager(context, rootManager)
    }

    val superuserBannerState: SuperuserBannerState by lazy {
        SuperuserBannerState()
    }

    // =============================================================================================
    // Repositories (Singletons)
    // =============================================================================================

    val firewallRepository: FirewallRepository by lazy {
        FirewallRepositoryImpl(firewallRuleDao, context)
    }

    // Note: PackageDataSource depends on FirewallRepository (circular dependency)
    // We use lazy initialization to break the cycle
    val packageDataSource: PackageDataSource by lazy {
        AndroidPackageDataSource(context, firewallRepository)
    }

    val packageRepository: PackageRepository by lazy {
        PackageRepositoryImpl(packageDataSource)
    }

    val networkPackageRepository: NetworkPackageRepository by lazy {
        NetworkPackageRepositoryImpl(packageDataSource)
    }

    // =============================================================================================
    // Services (Singletons)
    // =============================================================================================

    val newAppNotificationManager: NewAppNotificationManager by lazy {
        NewAppNotificationManager(context)
    }

    val screenStateMonitor: ScreenStateMonitor by lazy {
        ScreenStateMonitor(context)
    }

    val networkStateMonitor: NetworkStateMonitor by lazy {
        NetworkStateMonitor(context)
    }

    // =============================================================================================
    // Use Cases (Created on demand)
    // =============================================================================================

    fun provideGetNetworkPackagesUseCase(): GetNetworkPackagesUseCase {
        return GetNetworkPackagesUseCase(networkPackageRepository)
    }

    fun provideManageNetworkAccessUseCase(): ManageNetworkAccessUseCase {
        return ManageNetworkAccessUseCase(networkPackageRepository)
    }

    fun provideGetPackagesUseCase(): GetPackagesUseCase {
        return GetPackagesUseCase(packageRepository)
    }

    fun provideManagePackageUseCase(): ManagePackageUseCase {
        return ManagePackageUseCase(packageRepository)
    }

    fun provideAllowAllAppsUseCase(): AllowAllAppsUseCase {
        return AllowAllAppsUseCase(firewallRepository)
    }

    fun provideBlockAllAppsUseCase(): BlockAllAppsUseCase {
        return BlockAllAppsUseCase(firewallRepository)
    }

    fun provideGetBlockedCountUseCase(): GetBlockedCountUseCase {
        return GetBlockedCountUseCase(firewallRepository)
    }

    fun provideGetFirewallRuleByPackageUseCase(): GetFirewallRuleByPackageUseCase {
        return GetFirewallRuleByPackageUseCase(firewallRepository)
    }

    fun provideGetFirewallRulesUseCase(): GetFirewallRulesUseCase {
        return GetFirewallRulesUseCase(firewallRepository)
    }

    fun provideHandleNewAppInstallUseCase(): HandleNewAppInstallUseCase {
        return HandleNewAppInstallUseCase(context, firewallRepository, errorHandler)
    }

    fun provideUpdateFirewallRuleUseCase(): UpdateFirewallRuleUseCase {
        return UpdateFirewallRuleUseCase(firewallRepository)
    }

    companion object {
        @Volatile
        private var INSTANCE: De1984Dependencies? = null

        fun getInstance(context: Context): De1984Dependencies {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: De1984Dependencies(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        fun get(): De1984Dependencies {
            return INSTANCE ?: throw IllegalStateException(
                "De1984Dependencies not initialized. Call getInstance(context) first."
            )
        }
    }
}

