package io.github.dorumrr.de1984.data.datasource

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import io.github.dorumrr.de1984.utils.AppLogger
import androidx.core.graphics.drawable.toBitmap
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.model.PackageEntity
import io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.utils.Constants
import io.github.dorumrr.de1984.utils.PackageSafetyLoader
import io.github.dorumrr.de1984.utils.ShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private data class BlockingState(
    val isNetworkBlocked: Boolean,
    val wifiBlocked: Boolean,
    val mobileBlocked: Boolean,
    val roamingBlocked: Boolean,
    val backgroundBlocked: Boolean,
    val lanBlocked: Boolean
)

private data class PackageMetadata(
    val permissions: List<String>,
    val isVpnApp: Boolean,
    val versionName: String?,
    val versionCode: Long?,
    val installTime: Long?,
    val updateTime: Long?,
    val hasNetworkAccess: Boolean
)

class AndroidPackageDataSource(
    private val context: Context,
    private val firewallRepository: FirewallRepository,
    private val shizukuManager: ShizukuManager
) : PackageDataSource {

    private val packageManager = context.packageManager

    private val packagesFlow = MutableSharedFlow<List<PackageEntity>>(replay = 1)
    private val loadMutex = Mutex()
    private var isLoading = false
    private var lastLoadTime = 0L

    /**
     * A rescan was asked for. Survives a scan that is already running - see [invalidateCache].
     */
    private var invalidated = false
    private val CACHE_TTL = 1000L

    companion object {
        private const val TAG = "AndroidPackageDataSource"
    }
    
    override fun getPackages(): Flow<List<PackageEntity>> = packagesFlow
        .onStart {
            val now = System.currentTimeMillis()
            val cacheExpired = (now - lastLoadTime) > CACHE_TTL
            
            // An invalidation WAITS on the mutex instead of being turned away by isLoading.
            //
            // Cancelling a load job unwinds asynchronously, so a replacement collector arrives while
            // isLoading is still true. Skipping there meant the cancelled scan never re-ran, the
            // request stayed set and unconsumed, and the replay cache handed back the very rows the
            // invalidation existed to replace - with nothing left to trigger a retry.
            if (invalidated || (!isLoading && (packagesFlow.replayCache.isEmpty() || cacheExpired))) {
                loadMutex.withLock {
                    if (!isLoading && (invalidated || packagesFlow.replayCache.isEmpty() || (now - lastLoadTime) > CACHE_TTL)) {
                        isLoading = true
                        try {
                            // A failed scan THROWS. It used to return emptyList(), which was emitted
                            // and stamped lastLoadTime, so the user saw "no packages" with no error
                            // and every collector for the next second got that cached empty list.
                            //
                            // Swallowing it into null was worse still: nothing was emitted at all, so
                            // PackagesViewModel's .catch never fired and its spinner ran forever, and
                            // SettingsViewModel's getPackages().first() suspended for good.
                            //
                            // Both callers already handle a thrown error. Letting it out is what
                            // reaches them. lastLoadTime is not stamped, so the next collector retries.
                            // Loops while another invalidation arrives DURING the scan. Without
                            // this, a caller that invalidated while a scan was in flight was simply
                            // skipped by the isLoading guard above, and that scan - which read the
                            // OLD settings - then stamped lastLoadTime and wiped the request. The
                            // rows stayed painted with values the user had already changed, and
                            // nothing on the firewall screen ever rescanned.
                            do {
                                invalidated = false
                                var landed = false
                                try {
                                    val packages = loadPackagesInternal()
                                    lastLoadTime = System.currentTimeMillis()
                                    packagesFlow.emit(packages)
                                    landed = true
                                } finally {
                                    // A scan that threw, or was cancelled when the ViewModel
                                    // cancelled its load job, satisfied nothing. Leave the request
                                    // standing so the next collector honours it - otherwise the
                                    // rows and the policy read off them both stayed at the old
                                    // setting with nothing left to trigger a rescan.
                                    if (!landed) invalidated = true
                                }
                            } while (invalidated)
                        } finally {
                            isLoading = false
                        }
                    }
                }
            }
        }
    
    override fun invalidateCache() {
        // Both, and the flag is what makes this reliable. Zeroing the timestamp alone was not
        // enough: onStart also skips while isLoading is true, and the scan already running would
        // stamp lastLoadTime on the way out and wipe the request. The flag is checked again after
        // every scan, so an invalidation raised mid-scan costs one more pass instead of being lost.
        invalidated = true
        lastLoadTime = 0L
    }

    /** @throws Exception when the scan fails. A failure is not an empty device - see the caller. */
    private suspend fun loadPackagesInternal(): List<PackageEntity> = withContext(Dispatchers.IO) {
        val flowStartTime = System.currentTimeMillis()
        AppLogger.i(TAG, "⏱️ TIMING: getPackages START at $flowStartTime")
        try {
            HiddenApiHelper.clearDisabledPackagesCache()
            HiddenApiHelper.clearInstalledAppsCache()

            val getUsersStart = System.currentTimeMillis()
            val userProfiles = HiddenApiHelper.getUsers(context)
            val getUsersEnd = System.currentTimeMillis()
            AppLogger.i(TAG, "⏱️ TIMING: getUsers took ${getUsersEnd - getUsersStart}ms, returned ${userProfiles.size} profiles")
            AppLogger.d(TAG, "📱 Enumerating packages for ${userProfiles.size} user profiles")

            val rulesStart = System.currentTimeMillis()
            val firewallRules = firewallRepository.getAllRules().first()
            val rulesEnd = System.currentTimeMillis()
            AppLogger.i(TAG, "⏱️ TIMING: getAllRules().first() took ${rulesEnd - rulesStart}ms, returned ${firewallRules.size} rules")
            val rulesByKey = firewallRules.associateBy { "${it.packageName}:${it.userId}" }

            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val defaultPolicy = prefs.getString(
                Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                Constants.Settings.DEFAULT_FIREWALL_POLICY
            ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY
            val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL
            val allowCritical = prefs.getBoolean(
                Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
                Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
            )

            val allPackages = mutableListOf<PackageEntity>()

            AppLogger.i(TAG, "📱 MULTI-USER SUMMARY: ${userProfiles.size} profiles detected:")
            for (profile in userProfiles) {
                AppLogger.i(TAG, "   → userId=${profile.userId}, name=${profile.displayName}, isWork=${profile.isWorkProfile}, isClone=${profile.isCloneProfile}")
            }

            for (profile in userProfiles) {
                val profileStart = System.currentTimeMillis()
                val installedPackages = HiddenApiHelper.getInstalledApplicationsAsUser(
                    context,
                    PackageManager.GET_META_DATA,
                    profile.userId
                )
                val profileEnd = System.currentTimeMillis()

                AppLogger.i(TAG, "⏱️ TIMING: Profile ${profile.userId} (${profile.displayName}): getInstalledApplicationsAsUser took ${profileEnd - profileStart}ms, returned ${installedPackages.size} packages")

                AppLogger.d(TAG, "📦 User ${profile.userId} (${profile.displayName}): ${installedPackages.size} packages")

                if (profile.userId != 0 && installedPackages.isNotEmpty()) {
                    val sampleApps = installedPackages.take(5).map { it.packageName }
                    AppLogger.i(TAG, "📦 MULTI-USER: Sample apps from ${profile.displayName} profile: $sampleApps")
                }

                val chunkSize = 25
                val packageChunks = installedPackages
                    .filter { !Constants.App.isOwnApp(it.packageName) }
                    .chunked(chunkSize)

                for (chunk in packageChunks) {
                    val chunkResults = chunk.map { appInfo ->
                        async {
                            val ruleKey = "${appInfo.packageName}:${profile.userId}"
                            val rule = rulesByKey[ruleKey]

                            // OPTIMIZATION: Single batch call instead of 7 separate calls
                            // This reduces ~1400+ system calls to ~200 for typical device
                            val metadata = getPackageMetadataBatch(appInfo.packageName, profile.userId)

                            val absoluteUid = appInfo.uid

                            if (metadata.isVpnApp) {
                                AppLogger.d(TAG, "🔍 VPN APP DETECTED: ${appInfo.packageName} (user ${profile.userId}), hasRule=${rule != null}")
                            }

                            val isCriticalPackage = Constants.Firewall.isSystemCritical(appInfo.packageName) || metadata.isVpnApp

                            val blockingState = if (isCriticalPackage && !allowCritical) {
                                BlockingState(
                                    isNetworkBlocked = false,
                                    wifiBlocked = false,
                                    mobileBlocked = false,
                                    roamingBlocked = false,
                                    backgroundBlocked = false,
                                    lanBlocked = false
                                )
                            } else if (rule != null && rule.enabled) {
                                BlockingState(
                                    isNetworkBlocked = rule.wifiBlocked || rule.mobileBlocked,
                                    wifiBlocked = rule.wifiBlocked,
                                    mobileBlocked = rule.mobileBlocked,
                                    roamingBlocked = rule.blockWhenRoaming,
                                    backgroundBlocked = rule.blockWhenBackground,
                                    lanBlocked = rule.lanBlocked
                                )
                            } else if (isCriticalPackage && allowCritical) {
                                BlockingState(
                                    isNetworkBlocked = false,
                                    wifiBlocked = false,
                                    mobileBlocked = false,
                                    roamingBlocked = false,
                                    backgroundBlocked = false,
                                    lanBlocked = false
                                )
                            } else {
                                BlockingState(
                                    isNetworkBlocked = isBlockAllDefault,
                                    wifiBlocked = isBlockAllDefault,
                                    mobileBlocked = isBlockAllDefault,
                                    roamingBlocked = isBlockAllDefault,
                                    backgroundBlocked = false,
                                    lanBlocked = isBlockAllDefault
                                )
                            }

                            val criticality = PackageSafetyLoader.getCriticality(context, appInfo.packageName)
                            val category = PackageSafetyLoader.getCategory(context, appInfo.packageName)
                            val affects = PackageSafetyLoader.getAffects(context, appInfo.packageName)

                            PackageEntity(
                                // Recorded, not re-read later: these are the values THIS scan
                                // painted the blocking flags with. See PackageEntity.
                                paintedAllowCritical = allowCritical,
                                paintedBlockAllDefault = isBlockAllDefault,
                                packageName = appInfo.packageName,
                                userId = profile.userId,
                                uid = absoluteUid,
                                name = getAppName(appInfo),
                                icon = getAppIconEmoji(appInfo),
                                isEnabled = appInfo.enabled,
                                type = if (isSystemApp(appInfo)) Constants.Packages.TYPE_SYSTEM else Constants.Packages.TYPE_USER,
                                versionName = metadata.versionName,
                                versionCode = metadata.versionCode,
                                installTime = metadata.installTime,
                                updateTime = metadata.updateTime,
                                permissions = metadata.permissions,
                                hasNetworkAccess = metadata.hasNetworkAccess,
                                isNetworkBlocked = blockingState.isNetworkBlocked,
                                wifiBlocked = blockingState.wifiBlocked,
                                mobileBlocked = blockingState.mobileBlocked,
                                roamingBlocked = blockingState.roamingBlocked,
                                backgroundBlocked = blockingState.backgroundBlocked,
                                lanBlocked = blockingState.lanBlocked,
                                isVpnApp = metadata.isVpnApp,
                                hasExplicitRule = rule?.enabled == true,
                                criticality = criticality,
                                category = category,
                                affects = affects,
                                isWorkProfile = profile.isWorkProfile,
                                isCloneProfile = profile.isCloneProfile
                            )
                        }
                    }.awaitAll()

                    allPackages.addAll(chunkResults)
                }
            }

            val personalCount = allPackages.count { !it.isWorkProfile && !it.isCloneProfile }
            val workCount = allPackages.count { it.isWorkProfile }
            val cloneCount = allPackages.count { it.isCloneProfile }
            AppLogger.i(TAG, "📊 MULTI-USER FINAL: Total ${allPackages.size} packages (Personal: $personalCount, Work: $workCount, Clone: $cloneCount)")

            val workApps = allPackages.filter { it.isWorkProfile }.take(5).map { it.packageName }
            val cloneApps = allPackages.filter { it.isCloneProfile }.take(5).map { it.packageName }
            if (workApps.isNotEmpty()) {
                AppLogger.i(TAG, "📊 MULTI-USER: Work profile apps sample: $workApps")
            }
            if (cloneApps.isNotEmpty()) {
                AppLogger.i(TAG, "📊 MULTI-USER: Clone profile apps sample: $cloneApps")
            }

            val flowEndTime = System.currentTimeMillis()
            AppLogger.i(TAG, "⏱️ TIMING: getPackages COMPLETE - Total time: ${flowEndTime - flowStartTime}ms for ${allPackages.size} packages")

            // An EMPTY scan is a failed scan, not an empty device. Every Android device has
            // packages other than this one, so zero means the enumeration itself did not work -
            // HiddenApiHelper.getUsers came back with no profiles, or getInstalledApplicationsAsUser
            // answered empty because root or Shizuku was not granted yet on a fresh install.
            //
            // Returning it as a success stamped lastLoadTime and emitted the empty list into a
            // replay(1) flow, so the screen showed "no apps" with no error and every filter chip
            // kept showing it - the CACHE_TTL retry never fires because nothing collects again.
            // Only a force-stop cleared it. Throw instead: the caller leaves lastLoadTime alone and
            // the collectors' existing .catch reports it.
            if (allPackages.isEmpty()) {
                throw IllegalStateException(
                    "Package scan returned no packages for ${userProfiles.size} profile(s) - " +
                        "the enumeration failed rather than the device being empty"
                )
            }

            allPackages.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get packages: ${e.message}", e)
            // Rethrown, not swallowed. This also stops a CancellationException from loadJob.cancel()
            // being eaten here, which it was under both of the previous versions.
            throw e
        }
    }
    
    override suspend fun getPackage(packageName: String, userId: Int): PackageEntity? {
        if (Constants.App.isOwnApp(packageName)) {
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val appInfo = HiddenApiHelper.getApplicationInfoAsUser(
                    context, packageName, PackageManager.GET_META_DATA, userId
                ) ?: return@withContext null

                val rule = firewallRepository.getRuleByPackage(packageName, userId).first()
                val permissions = getAppPermissions(packageName, userId)
                val isVpnApp = hasVpnService(packageName, userId)

                val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
                val defaultPolicy = prefs.getString(
                    Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                    Constants.Settings.DEFAULT_FIREWALL_POLICY
                ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY
                val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL
                val allowCritical = prefs.getBoolean(
                    Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
                    Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
                )

                if (isVpnApp) {
                    AppLogger.d(TAG, "🔍 VPN APP DETECTED (getPackage): $packageName, hasRule=${rule != null}, isSystemCritical=${Constants.Firewall.isSystemCritical(packageName)}")
                }

                val isCriticalPackage = Constants.Firewall.isSystemCritical(packageName) || isVpnApp

                val blockingState = if (isCriticalPackage && !allowCritical) {
                    // Setting OFF: Critical packages are FORCED to ALLOW (locked, cannot be changed)
                    AppLogger.d(TAG, "✅ $packageName: Critical package (setting OFF) → FORCE ALLOW")
                    BlockingState(
                        isNetworkBlocked = false,
                        wifiBlocked = false,
                        mobileBlocked = false,
                        roamingBlocked = false,
                        backgroundBlocked = false,
                        lanBlocked = false
                    )
                } else if (rule != null && rule.enabled) {
                    BlockingState(
                        isNetworkBlocked = rule.wifiBlocked || rule.mobileBlocked,
                        wifiBlocked = rule.wifiBlocked,
                        mobileBlocked = rule.mobileBlocked,
                        roamingBlocked = rule.blockWhenRoaming,
                        backgroundBlocked = rule.blockWhenBackground,
                        lanBlocked = rule.lanBlocked
                    )
                } else if (isCriticalPackage && allowCritical) {
                    AppLogger.d(TAG, "✅ $packageName: Critical package (setting ON, no rule) → DEFAULT ALLOW")
                    BlockingState(
                        isNetworkBlocked = false,
                        wifiBlocked = false,
                        mobileBlocked = false,
                        roamingBlocked = false,
                        backgroundBlocked = false,
                        lanBlocked = false
                    )
                } else {
                    BlockingState(
                        isNetworkBlocked = isBlockAllDefault,
                        wifiBlocked = isBlockAllDefault,
                        mobileBlocked = isBlockAllDefault,
                        roamingBlocked = isBlockAllDefault,
                        backgroundBlocked = false,
                        lanBlocked = isBlockAllDefault
                    )
                }

                val criticality = PackageSafetyLoader.getCriticality(context, appInfo.packageName)
                val category = PackageSafetyLoader.getCategory(context, appInfo.packageName)
                val affects = PackageSafetyLoader.getAffects(context, appInfo.packageName)

                val isWorkProfile = userId in 10..99
                val isCloneProfile = userId >= 100

                PackageEntity(
                    // Recorded for the same reason the main scan records them - every protection
                    // test in the UI now trusts these rather than re-reading the preferences.
                    paintedAllowCritical = allowCritical,
                    paintedBlockAllDefault = isBlockAllDefault,
                    packageName = appInfo.packageName,
                    userId = userId,
                    uid = appInfo.uid,
                    name = getAppName(appInfo),
                    icon = getAppIconEmoji(appInfo),
                    isEnabled = appInfo.enabled,
                    type = if (isSystemApp(appInfo)) Constants.Packages.TYPE_SYSTEM else Constants.Packages.TYPE_USER,
                    versionName = getVersionName(appInfo.packageName, userId),
                    versionCode = getVersionCode(appInfo.packageName, userId),
                    installTime = getInstallTime(appInfo.packageName, userId),
                    updateTime = getUpdateTime(appInfo.packageName, userId),
                    permissions = permissions,
                    hasNetworkAccess = hasNetworkPermissions(appInfo.packageName, userId),
                    isNetworkBlocked = blockingState.isNetworkBlocked,
                    wifiBlocked = blockingState.wifiBlocked,
                    mobileBlocked = blockingState.mobileBlocked,
                    roamingBlocked = blockingState.roamingBlocked,
                    backgroundBlocked = blockingState.backgroundBlocked,
                    isVpnApp = isVpnApp,
                    hasExplicitRule = rule?.enabled == true,
                    criticality = criticality,
                    category = category,
                    affects = affects,
                    isWorkProfile = isWorkProfile,
                    isCloneProfile = isCloneProfile
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun setPackageEnabled(packageName: String, userId: Int, enabled: Boolean): Boolean {
        if (Constants.App.isOwnApp(packageName)) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val newState = if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                }

                packageManager.setApplicationEnabledSetting(
                    packageName,
                    newState,
                    PackageManager.DONT_KILL_APP
                )
                return@withContext true
            } catch (e: SecurityException) {
            } catch (e: Exception) {
            }

            if (shizukuManager.isShizukuAvailable()) {
                if (!shizukuManager.hasShizukuPermission) {
                    shizukuManager.requestShizukuPermission()
                    kotlinx.coroutines.delay(500)
                }

                if (shizukuManager.hasShizukuPermission) {
                    val command = if (enabled) {
                        "pm enable --user $userId $packageName"
                    } else {
                        "pm disable-user --user $userId $packageName"
                    }

                    // No catch: executeShellCommand reports every process failure as exitCode -1
                    // rather than throwing. The `catch (e: Exception) {}` that used to sit here
                    // swallowed coroutine cancellation, which must reach the caller.
                    val (exitCode, _) = shizukuManager.executeShellCommand(command)
                    if (exitCode == 0) {
                        HiddenApiHelper.clearDisabledPackagesCache()
                        return@withContext true
                    }
                }
            }

            val rootCommand = if (enabled) {
                "pm enable --user $userId $packageName"
            } else {
                "pm disable-user --user $userId $packageName"
            }

            // ShellRunner bounds the wait, drains both pipes together, and reports a failed start
            // as exitCode -1 instead of throwing - so there is nothing left here to catch. The
            // `catch (e: Exception) {}` this replaces also swallowed coroutine cancellation.
            val rootResult = ShellRunner.run("root: $rootCommand") {
                Runtime.getRuntime().exec(arrayOf("su", "-c", rootCommand))
            }

            if (rootResult.exitCode == 0) {
                HiddenApiHelper.clearDisabledPackagesCache()
                return@withContext true
            }

            false
        }
    }

    override suspend fun getUninstalledSystemPackages(): List<PackageEntity> {
        return withContext(Dispatchers.IO) {
            try {
                val allSystemPackagesOutput = if (shizukuManager.isShizukuAvailable() && shizukuManager.hasShizukuPermission) {
                    val (exitCode, output) = shizukuManager.executeShellCommand("pm list packages -u -s")
                    if (exitCode == 0) output else ""
                } else {
                    // stdout regardless of exit code, which is what this has always used. A
                    // failed run yields an empty string, same as the catch it replaces.
                    ShellRunner.run("root: pm list packages -u -s") {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "pm list packages -u -s"))
                    }.stdout
                }

                val installedSystemPackagesOutput = if (shizukuManager.isShizukuAvailable() && shizukuManager.hasShizukuPermission) {
                    val (exitCode, output) = shizukuManager.executeShellCommand("pm list packages -s")
                    if (exitCode == 0) output else ""
                } else {
                    // stdout regardless of exit code, which is what this has always used. A
                    // failed run yields an empty string, same as the catch it replaces.
                    ShellRunner.run("root: pm list packages -s") {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "pm list packages -s"))
                    }.stdout
                }

                val allSystemPackages = allSystemPackagesOutput.lines()
                    .filter { it.startsWith("package:") }
                    .map { it.removePrefix("package:").trim() }
                    .toSet()

                val installedSystemPackages = installedSystemPackagesOutput.lines()
                    .filter { it.startsWith("package:") }
                    .map { it.removePrefix("package:").trim() }
                    .toSet()

                val uninstalledSystemPackages = allSystemPackages - installedSystemPackages

                // Map to PackageEntity (no need for isSystemPackage() check - already filtered by -s flag)
                // Note: Uninstalled packages default to userId=0 since we can't determine their original user.
                // This is acceptable because:
                // 1. System packages are typically shared across all users
                // 2. The reinstall command works without specifying a user
                uninstalledSystemPackages
                    .filter { !Constants.App.isOwnApp(it) }
                    .map { packageName ->
                        PackageEntity(
                            packageName = packageName,
                            userId = 0,
                            uid = 0,
                            name = packageName,
                            icon = "⚙️",
                            isEnabled = false,
                            type = Constants.Packages.TYPE_SYSTEM,
                            versionName = null,
                            versionCode = null,
                            installTime = null,
                            updateTime = null,
                            permissions = emptyList(),
                            hasNetworkAccess = false,
                            isNetworkBlocked = false,
                            wifiBlocked = false,
                            mobileBlocked = false,
                            roamingBlocked = false,
                            backgroundBlocked = false,
                            isVpnApp = false,
                            hasExplicitRule = false,
                            criticality = null,
                            category = null,
                            affects = emptyList(),
                            isWorkProfile = false,
                            isCloneProfile = false
                        )
                    }
                    .sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to get uninstalled system packages: ${e.message}")
                emptyList()
            }
        }
    }

    override suspend fun uninstallPackage(packageName: String, userId: Int): Boolean {
        if (Constants.App.isOwnApp(packageName)) {
            return false
        }

        return withContext(Dispatchers.IO) {
            if (shizukuManager.isShizukuAvailable()) {
                if (!shizukuManager.hasShizukuPermission) {
                    shizukuManager.requestShizukuPermission()
                    kotlinx.coroutines.delay(500)
                }

                if (shizukuManager.hasShizukuPermission) {
                    val command = "pm uninstall --user $userId $packageName"
                    val (exitCode, _) = shizukuManager.executeShellCommand(command)
                    if (exitCode == 0) {
                        return@withContext true
                    }
                }
            }

            val rootCommand = "pm uninstall --user $userId $packageName"
            val rootResult = ShellRunner.run("root: $rootCommand") {
                Runtime.getRuntime().exec(arrayOf("su", "-c", rootCommand))
            }

            if (rootResult.exitCode == 0) {
                return@withContext true
            }

            false
        }
    }

    override suspend fun reinstallPackage(packageName: String, userId: Int): Boolean {
        if (Constants.App.isOwnApp(packageName)) {
            return false
        }

        return withContext(Dispatchers.IO) {
            if (shizukuManager.isShizukuAvailable()) {
                if (!shizukuManager.hasShizukuPermission) {
                    shizukuManager.requestShizukuPermission()
                    kotlinx.coroutines.delay(500)
                }

                if (shizukuManager.hasShizukuPermission) {
                    val command = "cmd package install-existing --user $userId $packageName"
                    val (exitCode, _) = shizukuManager.executeShellCommand(command)
                    if (exitCode == 0) {
                        return@withContext true
                    }
                }
            }

            val rootCommand = "cmd package install-existing --user $userId $packageName"
            val rootResult = ShellRunner.run("root: $rootCommand") {
                Runtime.getRuntime().exec(arrayOf("su", "-c", rootCommand))
            }

            if (rootResult.exitCode == 0) {
                return@withContext true
            }

            false
        }
    }

    override suspend fun forceStopPackage(packageName: String, userId: Int): Boolean {
        if (Constants.App.isOwnApp(packageName)) {
            return false
        }

        return withContext(Dispatchers.IO) {
            if (shizukuManager.isShizukuAvailable()) {
                if (!shizukuManager.hasShizukuPermission) {
                    shizukuManager.requestShizukuPermission()
                    kotlinx.coroutines.delay(500)
                }

                if (shizukuManager.hasShizukuPermission) {
                    val command = "am force-stop --user $userId $packageName"
                    val (exitCode, _) = shizukuManager.executeShellCommand(command)
                    if (exitCode == 0) {
                        return@withContext true
                    }
                }
            }

            val rootCommand = "am force-stop --user $userId $packageName"
            val rootResult = ShellRunner.run("root: $rootCommand") {
                Runtime.getRuntime().exec(arrayOf("su", "-c", rootCommand))
            }

            if (rootResult.exitCode == 0) {
                return@withContext true
            }

            try {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                activityManager.killBackgroundProcesses(packageName)
            } catch (e: Exception) {
            }

            false
        }
    }
    
    private fun getAppName(appInfo: ApplicationInfo): String {
        return try {
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            appInfo.packageName
        }
    }
    
    private fun getAppIconEmoji(appInfo: ApplicationInfo): String {
        return if (isSystemApp(appInfo)) "⚙️" else "📦"
    }
    
    private fun isSystemApp(appInfo: ApplicationInfo): Boolean {
        return (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    private fun getPackageMetadataBatch(packageName: String, userId: Int): PackageMetadata {
        return try {
            val packageInfo = HiddenApiHelper.getPackageInfoAsUser(
                context,
                packageName,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
                userId
            )

            if (packageInfo == null) {
                return PackageMetadata(
                    permissions = emptyList(),
                    isVpnApp = false,
                    versionName = null,
                    versionCode = null,
                    installTime = null,
                    updateTime = null,
                    hasNetworkAccess = false
                )
            }

            val permissions = packageInfo.requestedPermissions?.toList() ?: emptyList()

            val isVpnApp = packageInfo.services?.any { serviceInfo ->
                serviceInfo.permission == Constants.Firewall.VPN_SERVICE_PERMISSION
            } ?: false

            val hasNetworkAccess = permissions.any { permission ->
                permission == "android.permission.INTERNET" ||
                permission == "android.permission.ACCESS_NETWORK_STATE" ||
                permission == "android.permission.ACCESS_WIFI_STATE"
            }

            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }

            PackageMetadata(
                permissions = permissions,
                isVpnApp = isVpnApp,
                versionName = packageInfo.versionName,
                versionCode = versionCode,
                installTime = packageInfo.firstInstallTime,
                updateTime = packageInfo.lastUpdateTime,
                hasNetworkAccess = hasNetworkAccess
            )
        } catch (e: Exception) {
            AppLogger.d(TAG, "Failed to get metadata for $packageName: ${e.message}")
            PackageMetadata(
                permissions = emptyList(),
                isVpnApp = false,
                versionName = null,
                versionCode = null,
                installTime = null,
                updateTime = null,
                hasNetworkAccess = false
            )
        }
    }
    
    private fun getVersionName(packageName: String, userId: Int = 0): String? {
        return try {
            HiddenApiHelper.getPackageInfoAsUser(context, packageName, 0, userId)?.versionName
        } catch (e: Exception) {
            null
        }
    }

    private fun getVersionCode(packageName: String, userId: Int = 0): Long? {
        return try {
            val packageInfo = HiddenApiHelper.getPackageInfoAsUser(context, packageName, 0, userId)
                ?: return null
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getInstallTime(packageName: String, userId: Int = 0): Long? {
        return try {
            HiddenApiHelper.getPackageInfoAsUser(context, packageName, 0, userId)?.firstInstallTime
        } catch (e: Exception) {
            null
        }
    }

    private fun getUpdateTime(packageName: String, userId: Int = 0): Long? {
        return try {
            HiddenApiHelper.getPackageInfoAsUser(context, packageName, 0, userId)?.lastUpdateTime
        } catch (e: Exception) {
            null
        }
    }

    private fun getAppPermissions(packageName: String, userId: Int = 0): List<String> {
        return try {
            val packageInfo = HiddenApiHelper.getPackageInfoAsUser(context, packageName, PackageManager.GET_PERMISSIONS, userId)
            packageInfo?.requestedPermissions?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Check if an app has a VPN service by looking for services with BIND_VPN_SERVICE permission.
     *
     * VPN apps don't REQUEST the BIND_VPN_SERVICE permission - they DECLARE it on their service.
     * This is a service permission that protects the VPN service from being bound by unauthorized apps.
     *
     * Example from a VPN app's AndroidManifest.xml:
     * <service android:name=".VpnService" android:permission="android.permission.BIND_VPN_SERVICE">
     *     <intent-filter>
     *         <action android:name="android.net.VpnService" />
     *     </intent-filter>
     * </service>
     *
     * userId has NO DEFAULT on purpose. It used to default to 0, and every enforcement call site
     * omitted it - so a VPN app installed only in the work profile was looked up in the personal
     * profile, not found, and treated as an ordinary app. Block All then cut the work profile's
     * VPN. Making it required means the compiler catches the next caller that forgets.
     */
    private fun hasVpnService(packageName: String, userId: Int): Boolean {
        return try {
                // GET_PERMISSIONS is requested but never read. It is here so this shares a cache
                // entry with the getPackagesWithNetworkPermissions sweep, which asks for both.
                // getPackageInfoAsUser keys its cache on "userId:flags:packageName", so GET_SERVICES
                // alone (4) is a different key from GET_PERMISSIONS or GET_SERVICES (4100) and every
                // call was a guaranteed miss - one binder round trip per app, inside a filter over
                // every package. Measured on hardware: 53 hit / 587 miss before, and the whole
                // Block All start took 8.35s.
            val packageInfo = HiddenApiHelper.getPackageInfoAsUser(
                context,
                packageName,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
                userId
            ) ?: return false

            val isVpn = packageInfo.services?.any { serviceInfo ->
                serviceInfo.permission == Constants.Firewall.VPN_SERVICE_PERMISSION
            } ?: false

            if (isVpn) {
                AppLogger.d(TAG, "🔍 hasVpnService($packageName, userId=$userId) = true (found VPN service)")
            }

            isVpn
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ hasVpnService($packageName, userId=$userId) failed", e)
            false
        }
    }

    /**
     * Does this package request any permission the firewall cares about?
     *
     * Reads [Constants.Firewall.NETWORK_PERMISSIONS] rather than a list of its own. It used to check
     * three permissions inline - INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE - while that
     * constant, which the firewall backends use, holds five. An app requesting only
     * CHANGE_WIFI_STATE or CHANGE_NETWORK_STATE was shown here as having no network permission while
     * the firewall was applying a policy to it.
     *
     * Deliberately still one direct `getPackageInfoAsUser` per package, NOT a lookup in
     * HiddenApiHelper's cached whole-profile list. That list is only as good as the enumeration
     * behind it, and the enumeration is flaky: measured twice on 2026-08-25, user 10 returned 0
     * packages from the hidden API before a Shizuku fallback found 216. Answering a per-package
     * question from a whole-profile scan would turn one failed scan into "no network permission"
     * for every app in that profile. A direct call cannot fail that way.
     */
    private fun hasNetworkPermissions(packageName: String, userId: Int = 0): Boolean {
        val permissions = getAppPermissions(packageName, userId)
        return permissions.any { Constants.Firewall.NETWORK_PERMISSIONS.contains(it) }
    }

    override suspend fun setNetworkAccess(packageName: String, userId: Int, allowed: Boolean): Boolean {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        if (Constants.Firewall.isSystemCritical(packageName) && !allowCritical) {
            return false
        }

        if (hasVpnService(packageName, userId) && !allowCritical) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val appInfo = HiddenApiHelper.getApplicationInfoAsUser(
                    context, packageName, PackageManager.GET_META_DATA, userId
                ) ?: return@withContext false
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()
                val rule = if (existingRule != null) {
                    val updated = if (allowed) {
                        existingRule.allowAll()
                    } else {
                        existingRule.blockAll()
                    }
                    updated
                } else {
                    val newRule = FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = !allowed,
                        mobileBlocked = !allowed,
                        blockWhenRoaming = !allowed,
                        lanBlocked = !allowed,
                        enabled = true,
                        isSystemApp = isSystemApp(appInfo),
                        hasInternetPermission = hasNetworkPermissions(packageName, userId)
                    )
                    newRule
                }

                firewallRepository.insertRule(rule)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun setWifiBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        if (Constants.Firewall.isSystemCritical(packageName) && !allowCritical) {
            return false
        }

        if (hasVpnService(packageName, userId) && !allowCritical) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val appInfo = HiddenApiHelper.getApplicationInfoAsUser(
                    context, packageName, PackageManager.GET_META_DATA, userId
                ) ?: return@withContext false
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()

                if (existingRule != null) {
                    firewallRepository.updateWifiBlocking(packageName, userId, blocked)
                } else {
                    val defaultPolicy = prefs.getString(
                        Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                        Constants.Settings.DEFAULT_FIREWALL_POLICY
                    )
                    val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                    // A FIRST rule created by a BLOCK tap must not stamp the Block All default onto the
                    // networks the user did not touch. That only ever happens on a row already showing
                    // Allowed - one Block All is not reaching - so inheriting the default there turned
                    // one tap on one icon into a block on three, with an Undo that lifted only one.
                    // An UNBLOCK tap keeps the old behaviour: the row was showing those as blocked.
                    val rule = FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = blocked,
                        mobileBlocked = isBlockAllDefault && !blocked,
                        blockWhenRoaming = isBlockAllDefault && !blocked,
                        enabled = true,
                        isSystemApp = isSystemApp(appInfo),
                        hasInternetPermission = hasNetworkPermissions(packageName, userId)
                    )
                    firewallRepository.insertRule(rule)
                }

                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun setMobileBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        if (Constants.Firewall.isSystemCritical(packageName) && !allowCritical) {
            return false
        }

        if (hasVpnService(packageName, userId) && !allowCritical) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val appInfo = HiddenApiHelper.getApplicationInfoAsUser(
                    context, packageName, PackageManager.GET_META_DATA, userId
                ) ?: return@withContext false
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()

                if (existingRule != null) {
                    firewallRepository.updateMobileBlocking(packageName, userId, blocked)
                } else {
                    val defaultPolicy = prefs.getString(
                        Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                        Constants.Settings.DEFAULT_FIREWALL_POLICY
                    )
                    val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                    // A FIRST rule created by a BLOCK tap must not stamp the Block All default onto the
                    // networks the user did not touch. That only ever happens on a row already showing
                    // Allowed - one Block All is not reaching - so inheriting the default there turned
                    // one tap on one icon into a block on three, with an Undo that lifted only one.
                    // An UNBLOCK tap keeps the old behaviour: the row was showing those as blocked.
                    val rule = FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = isBlockAllDefault && !blocked,
                        mobileBlocked = blocked,
                        blockWhenRoaming = isBlockAllDefault && !blocked,
                        enabled = true,
                        isSystemApp = isSystemApp(appInfo),
                        hasInternetPermission = hasNetworkPermissions(packageName, userId)
                    )
                    firewallRepository.insertRule(rule)
                }

                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun setRoamingBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        if (Constants.Firewall.isSystemCritical(packageName) && !allowCritical) {
            return false
        }

        if (hasVpnService(packageName, userId) && !allowCritical) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val appInfo = HiddenApiHelper.getApplicationInfoAsUser(
                    context, packageName, PackageManager.GET_META_DATA, userId
                ) ?: return@withContext false
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()

                if (existingRule != null) {
                    firewallRepository.updateRoamingBlocking(packageName, userId, blocked)
                } else {
                    val defaultPolicy = prefs.getString(
                        Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                        Constants.Settings.DEFAULT_FIREWALL_POLICY
                    )
                    val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                    // A FIRST rule created by a BLOCK tap must not stamp the Block All default onto the
                    // networks the user did not touch. That only ever happens on a row already showing
                    // Allowed - one Block All is not reaching - so inheriting the default there turned
                    // one tap on one icon into a block on three, with an Undo that lifted only one.
                    // An UNBLOCK tap keeps the old behaviour: the row was showing those as blocked.
                    val rule = FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = isBlockAllDefault && !blocked,
                        mobileBlocked = isBlockAllDefault && !blocked,
                        blockWhenRoaming = blocked,
                        enabled = true,
                        isSystemApp = isSystemApp(appInfo),
                        hasInternetPermission = hasNetworkPermissions(packageName, userId)
                    )
                    firewallRepository.insertRule(rule)
                }

                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun setBackgroundBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        if (Constants.Firewall.isSystemCritical(packageName) && !allowCritical) {
            return false
        }

        if (hasVpnService(packageName, userId) && !allowCritical) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val appInfo = HiddenApiHelper.getApplicationInfoAsUser(
                    context, packageName, PackageManager.GET_META_DATA, userId
                ) ?: return@withContext false
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()

                if (existingRule != null) {
                    firewallRepository.updateBackgroundBlocking(packageName, userId, blocked)
                } else {
                    val defaultPolicy = prefs.getString(
                        Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                        Constants.Settings.DEFAULT_FIREWALL_POLICY
                    )
                    val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                    // A FIRST rule created by a BLOCK tap must not stamp the Block All default onto the
                    // networks the user did not touch. That only ever happens on a row already showing
                    // Allowed - one Block All is not reaching - so inheriting the default there turned
                    // one tap on one icon into a block on three, with an Undo that lifted only one.
                    // An UNBLOCK tap keeps the old behaviour: the row was showing those as blocked.
                    val rule = FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        // Flat false, not the Block All default. This toggle is only SHOWN when the
                        // row is not fully blocked, so a first rule created through it always comes
                        // from a row displaying its networks as allowed - in either direction.
                        // Inheriting the default here meant that turning "Allow in Background" ON
                        // wrote a full network block.
                        wifiBlocked = false,
                        mobileBlocked = false,
                        blockWhenRoaming = false,
                        blockWhenBackground = blocked,
                        enabled = true,
                        isSystemApp = isSystemApp(appInfo),
                        hasInternetPermission = hasNetworkPermissions(packageName, userId)
                    )
                    firewallRepository.insertRule(rule)
                }

                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun setLanBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        if (Constants.Firewall.isSystemCritical(packageName) && !allowCritical) {
            return false
        }

        if (hasVpnService(packageName, userId) && !allowCritical) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val appInfo = HiddenApiHelper.getApplicationInfoAsUser(
                    context, packageName, PackageManager.GET_META_DATA, userId
                ) ?: return@withContext false
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()

                if (existingRule != null) {
                    firewallRepository.updateLanBlocking(packageName, userId, blocked)
                } else {
                    val defaultPolicy = prefs.getString(
                        Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                        Constants.Settings.DEFAULT_FIREWALL_POLICY
                    ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY
                    val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                    // A FIRST rule created by a BLOCK tap must not stamp the Block All default onto the
                    // networks the user did not touch. That only ever happens on a row already showing
                    // Allowed - one Block All is not reaching - so inheriting the default there turned
                    // one tap on one icon into a block on three, with an Undo that lifted only one.
                    // An UNBLOCK tap keeps the old behaviour: the row was showing those as blocked.
                    val rule = FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = isBlockAllDefault && !blocked,
                        mobileBlocked = isBlockAllDefault && !blocked,
                        blockWhenRoaming = isBlockAllDefault && !blocked,
                        lanBlocked = blocked,
                        enabled = true,
                        isSystemApp = isSystemApp(appInfo),
                        hasInternetPermission = hasNetworkPermissions(packageName, userId)
                    )
                    firewallRepository.insertRule(rule)
                }

                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun setAllNetworkBlocking(packageName: String, userId: Int, blocked: Boolean): Boolean {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        if (Constants.Firewall.isSystemCritical(packageName) && !allowCritical) {
            return false
        }

        if (hasVpnService(packageName, userId) && !allowCritical) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val appInfo = HiddenApiHelper.getApplicationInfoAsUser(
                    context, packageName, PackageManager.GET_META_DATA, userId
                ) ?: return@withContext false
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()

                if (existingRule != null) {
                    firewallRepository.updateAllNetworkBlocking(packageName, userId, blocked)
                } else {
                    // Create new rule with the three internet transports set to the same state.
                    // LAN is left at its default on purpose - see updateAllNetworkBlocking.
                    val rule = FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = blocked,
                        mobileBlocked = blocked,
                        blockWhenRoaming = blocked,
                        enabled = true,
                        isSystemApp = isSystemApp(appInfo),
                        hasInternetPermission = hasNetworkPermissions(packageName, userId)
                    )
                    firewallRepository.insertRule(rule)
                }

                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun setMobileAndRoaming(packageName: String, userId: Int, mobileBlocked: Boolean, roamingBlocked: Boolean): Boolean {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        if (Constants.Firewall.isSystemCritical(packageName) && !allowCritical) {
            return false
        }

        if (hasVpnService(packageName, userId) && !allowCritical) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val appInfo = HiddenApiHelper.getApplicationInfoAsUser(
                    context, packageName, PackageManager.GET_META_DATA, userId
                ) ?: return@withContext false
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()

                if (existingRule != null) {
                    firewallRepository.updateMobileAndRoaming(packageName, userId, mobileBlocked, roamingBlocked)
                } else {
                    val defaultPolicy = prefs.getString(
                        Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                        Constants.Settings.DEFAULT_FIREWALL_POLICY
                    )
                    val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                    // A FIRST rule created by a BLOCK tap must not stamp the Block All default onto the
                    // networks the user did not touch. That only ever happens on a row already showing
                    // Allowed - one Block All is not reaching - so inheriting the default there turned
                    // one tap on one icon into a block on three, with an Undo that lifted only one.
                    // An UNBLOCK tap keeps the old behaviour: the row was showing those as blocked.
                    val rule = FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = isBlockAllDefault && !(mobileBlocked || roamingBlocked),
                        mobileBlocked = mobileBlocked,
                        blockWhenRoaming = roamingBlocked,
                        enabled = true,
                        isSystemApp = isSystemApp(appInfo),
                        hasInternetPermission = hasNetworkPermissions(packageName, userId)
                    )
                    firewallRepository.insertRule(rule)
                }

                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
