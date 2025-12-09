package io.github.dorumrr.de1984

import android.content.Context
import io.github.dorumrr.de1984.utils.AppLogger
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.dorumrr.de1984.data.common.BootProtectionManager
import io.github.dorumrr.de1984.data.common.CaptivePortalManager
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.data.common.PermissionManager
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.database.De1984Database
import io.github.dorumrr.de1984.data.database.dao.FirewallRuleDao
import io.github.dorumrr.de1984.data.datasource.AndroidPackageDataSource
import io.github.dorumrr.de1984.data.datasource.PackageDataSource
import io.github.dorumrr.de1984.data.firewall.FirewallManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * ServiceLocator for managing application dependencies.
 * Replaces Hilt dependency injection with manual DI for better F-Droid reproducible builds.
 */
class De1984Dependencies(private val context: Context) {

    companion object {
        private const val TAG = "De1984Dependencies"

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

    // =============================================================================================
    // Application Scope
    // =============================================================================================

    /**
     * Application-level coroutine scope that lives for the entire application process.
     * Use this for long-running operations that should survive beyond single components.
     * Automatically cancels when the application process is killed by Android.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // =============================================================================================
    // Package Data Change Notifications
    // =============================================================================================

    /**
     * SharedFlow to notify ViewModels when package data changes.
     * This enables cross-screen refresh when packages are enabled/disabled or firewall rules change.
     */
    private val _packageDataChanged = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val packageDataChanged: SharedFlow<Unit> = _packageDataChanged.asSharedFlow()

    /**
     * Notify all observers that package data has changed and they should refresh.
     */
    fun notifyPackageDataChanged() {
        _packageDataChanged.tryEmit(Unit)
    }

    // =============================================================================================
    // Database
    // =============================================================================================

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add lanBlocked column with default value false (0)
            db.execSQL("ALTER TABLE firewall_rules ADD COLUMN lanBlocked INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add userId column for multi-user/work profile support
            // Default 0 = personal profile (existing rules)
            db.execSQL("ALTER TABLE firewall_rules ADD COLUMN userId INTEGER NOT NULL DEFAULT 0")

            // Recreate table with composite primary key (packageName, userId)
            // Room doesn't support changing primary key via ALTER TABLE, so we need to:
            // 1. Create new table with correct schema
            // 2. Copy data from old table
            // 3. Drop old table
            // 4. Rename new table
            db.execSQL("""
                CREATE TABLE firewall_rules_new (
                    packageName TEXT NOT NULL,
                    userId INTEGER NOT NULL DEFAULT 0,
                    uid INTEGER NOT NULL,
                    appName TEXT NOT NULL,
                    wifiBlocked INTEGER NOT NULL DEFAULT 0,
                    mobileBlocked INTEGER NOT NULL DEFAULT 0,
                    blockWhenBackground INTEGER NOT NULL DEFAULT 0,
                    blockWhenRoaming INTEGER NOT NULL DEFAULT 0,
                    lanBlocked INTEGER NOT NULL DEFAULT 0,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    isSystemApp INTEGER NOT NULL DEFAULT 0,
                    hasInternetPermission INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(packageName, userId)
                )
            """.trimIndent())

            db.execSQL("""
                INSERT INTO firewall_rules_new (
                    packageName, userId, uid, appName, wifiBlocked, mobileBlocked,
                    blockWhenBackground, blockWhenRoaming, lanBlocked, enabled,
                    isSystemApp, hasInternetPermission, createdAt, updatedAt
                )
                SELECT
                    packageName, userId, uid, appName, wifiBlocked, mobileBlocked,
                    blockWhenBackground, blockWhenRoaming, lanBlocked, enabled,
                    isSystemApp, hasInternetPermission, createdAt, updatedAt
                FROM firewall_rules
            """.trimIndent())

            db.execSQL("DROP TABLE firewall_rules")
            db.execSQL("ALTER TABLE firewall_rules_new RENAME TO firewall_rules")

            AppLogger.i(TAG, "Database migrated to version 6: Added userId column for multi-user support")
        }
    }

    val database: De1984Database by lazy {
        Room.databaseBuilder(
            context.applicationContext,
            De1984Database::class.java,
            "de1984_database"
        )
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
            .fallbackToDestructiveMigration()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                    AppLogger.w(TAG, "Database destructive migration triggered - firewall rules reset to defaults")
                }
            })
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

    val shizukuManager: ShizukuManager by lazy {
        ShizukuManager(context)
    }

    val errorHandler: ErrorHandler by lazy {
        ErrorHandler()
    }

    val permissionManager: PermissionManager by lazy {
        PermissionManager(context, rootManager, shizukuManager)
    }

    val superuserBannerState: SuperuserBannerState by lazy {
        SuperuserBannerState()
    }

    val captivePortalManager: CaptivePortalManager by lazy {
        CaptivePortalManager(context, rootManager, shizukuManager)
    }

    val bootProtectionManager: BootProtectionManager by lazy {
        BootProtectionManager(context, rootManager, shizukuManager)
    }

    // =============================================================================================
    // Repositories (Singletons)
    // =============================================================================================

    val firewallRepository: FirewallRepository by lazy {
        FirewallRepositoryImpl(firewallRuleDao, context) { notifyPackageDataChanged() }
    }

    // Note: PackageDataSource depends on FirewallRepository (circular dependency)
    // We use lazy initialization to break the cycle
    val packageDataSource: PackageDataSource by lazy {
        AndroidPackageDataSource(context, firewallRepository, shizukuManager)
    }

    val packageRepository: PackageRepository by lazy {
        PackageRepositoryImpl(context, packageDataSource) { notifyPackageDataChanged() }
    }

    val networkPackageRepository: NetworkPackageRepository by lazy {
        NetworkPackageRepositoryImpl(context, packageDataSource)
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

    val firewallManager: FirewallManager by lazy {
        FirewallManager(
            context = context,
            rootManager = rootManager,
            shizukuManager = shizukuManager,
            errorHandler = errorHandler,
            firewallRepository = firewallRepository,
            networkStateMonitor = networkStateMonitor,
            screenStateMonitor = screenStateMonitor
        )
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

    fun provideSmartPolicySwitchUseCase(): SmartPolicySwitchUseCase {
        return SmartPolicySwitchUseCase(firewallRepository, context)
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

    fun provideEnsureSystemRecommendedRulesUseCase(): EnsureSystemRecommendedRulesUseCase {
        return EnsureSystemRecommendedRulesUseCase(context, firewallRepository)
    }
}
