package io.github.dorumrr.de1984

import io.github.dorumrr.de1984.data.common.awaitPrivilegeProbes
import io.github.dorumrr.de1984.data.common.hasPrivilegedAccess
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
import io.github.dorumrr.de1984.data.firewall.IptablesFirewallBackend
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


    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)


    private val _packageDataChanged = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val packageDataChanged: SharedFlow<Unit> = _packageDataChanged.asSharedFlow()

    fun notifyPackageDataChanged() {
        _packageDataChanged.tryEmit(Unit)
    }


    /**
     * v1.0.0 shipped database version 3, and version 4 renamed `blockWhenScreenOff` to
     * `blockWhenBackground`. No 3->4 migration was ever written, so every install from before
     * 2025-11-17 fell through to fallbackToDestructiveMigration on its next update and lost every
     * firewall rule, with nothing but a logcat line to show for it.
     *
     * SQLite on minSdk 26 predates ALTER TABLE ... RENAME COLUMN, so the column is renamed the same
     * way MIGRATION_5_6 changes its primary key: create, copy, drop, rename.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE firewall_rules_new (
                    packageName TEXT NOT NULL,
                    uid INTEGER NOT NULL,
                    appName TEXT NOT NULL,
                    wifiBlocked INTEGER NOT NULL DEFAULT 0,
                    mobileBlocked INTEGER NOT NULL DEFAULT 0,
                    blockWhenBackground INTEGER NOT NULL DEFAULT 0,
                    blockWhenRoaming INTEGER NOT NULL DEFAULT 0,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    isSystemApp INTEGER NOT NULL DEFAULT 0,
                    hasInternetPermission INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(packageName)
                )
            """.trimIndent())

            db.execSQL("""
                INSERT INTO firewall_rules_new (
                    packageName, uid, appName, wifiBlocked, mobileBlocked,
                    blockWhenBackground, blockWhenRoaming, enabled,
                    isSystemApp, hasInternetPermission, createdAt, updatedAt
                )
                SELECT
                    packageName, uid, appName, wifiBlocked, mobileBlocked,
                    blockWhenScreenOff, blockWhenRoaming, enabled,
                    isSystemApp, hasInternetPermission, createdAt, updatedAt
                FROM firewall_rules
            """.trimIndent())

            db.execSQL("DROP TABLE firewall_rules")
            db.execSQL("ALTER TABLE firewall_rules_new RENAME TO firewall_rules")

            AppLogger.i(TAG, "Database migrated to version 4: blockWhenScreenOff renamed to blockWhenBackground")
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE firewall_rules ADD COLUMN lanBlocked INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
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
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
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


    val firewallRepository: FirewallRepository by lazy {
        FirewallRepositoryImpl(firewallRuleDao, context) { notifyPackageDataChanged() }
    }

    // Note: PackageDataSource depends on FirewallRepository (circular dependency)
    // We use lazy initialization to break the cycle
    val packageDataSource: PackageDataSource by lazy {
        AndroidPackageDataSource(context, firewallRepository, shizukuManager)
    }

    val packageRepository: PackageRepository by lazy {
        PackageRepositoryImpl(
            context,
            packageDataSource,
            accessMissing = {
                awaitPrivilegeProbes(rootManager.rootStatus, shizukuManager.shizukuStatus, FirewallManager.PRIVILEGE_ANSWER_TIMEOUT_MS) &&
                    !hasPrivilegedAccess(rootManager.rootStatus.value, shizukuManager.shizukuStatus.value)
            }
        ) { notifyPackageDataChanged() }
    }

    val networkPackageRepository: NetworkPackageRepository by lazy {
        NetworkPackageRepositoryImpl(context, packageDataSource)
    }


    val newAppNotificationManager: NewAppNotificationManager by lazy {
        NewAppNotificationManager(context) { firewallManager.activeBackendType.value }
    }

    val screenStateMonitor: ScreenStateMonitor by lazy {
        ScreenStateMonitor(context)
    }

    val networkStateMonitor: NetworkStateMonitor by lazy {
        NetworkStateMonitor(context)
    }

    /**
     * The one and only iptables backend in this process.
     *
     * It has to be a singleton because it is **stateful about the kernel**: `chainNeedsResync`,
     * `blockedUids` and `blockedLanUids` describe one chain, `de1984_output`, and there is only one
     * of those. FirewallManager and PrivilegedFirewallService used to build one each, and each
     * arrived with `chainNeedsResync` already true - the service via `startInternal()`, the manager
     * simply from the field's initialiser, since the manager never calls `startInternal()` at all.
     * So every start paid for TWO full chain rewrites, measured at 5.9 s + 10.4 s of backend work
     * on a 110-package device, and neither instance could ever see the other had just done it.
     * Sharing one object cut the backend total to 6.2 s.
     *
     * Everything else in this file is a singleton for the same reason; this one was missed.
     */
    val iptablesBackend: IptablesFirewallBackend by lazy {
        IptablesFirewallBackend(context, rootManager, shizukuManager, errorHandler)
    }

    val firewallManager: FirewallManager by lazy {
        FirewallManager(
            context = context,
            iptablesBackend = iptablesBackend,
            rootManager = rootManager,
            shizukuManager = shizukuManager,
            errorHandler = errorHandler,
            firewallRepository = firewallRepository,
            networkStateMonitor = networkStateMonitor,
            screenStateMonitor = screenStateMonitor
        )
    }


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

    fun provideSmartPolicySwitchUseCase(): SmartPolicySwitchUseCase {
        return SmartPolicySwitchUseCase(firewallRepository, context)
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
