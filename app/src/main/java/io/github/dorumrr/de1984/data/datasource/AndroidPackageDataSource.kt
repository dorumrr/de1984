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

/**
 * Cached package metadata retrieved in a single system call.
 * This eliminates redundant calls to getPackageInfoAsUser() for each field.
 */
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

    // SharedFlow to deduplicate concurrent getPackages() calls
    private val packagesFlow = MutableSharedFlow<List<PackageEntity>>(replay = 1)
    private val loadMutex = Mutex()
    private var isLoading = false
    private var lastLoadTime = 0L
    private val CACHE_TTL = 1000L // 1 second cache to prevent rapid successive loads

    companion object {
        private const val TAG = "AndroidPackageDataSource"
    }
    
    override fun getPackages(): Flow<List<PackageEntity>> = packagesFlow
        .onStart {
            // Only load if not already loading or cache expired
            val now = System.currentTimeMillis()
            val cacheExpired = (now - lastLoadTime) > CACHE_TTL
            
            if (!isLoading && (packagesFlow.replayCache.isEmpty() || cacheExpired)) {
                loadMutex.withLock {
                    // Double-check inside lock
                    if (!isLoading && (packagesFlow.replayCache.isEmpty() || (now - lastLoadTime) > CACHE_TTL)) {
                        isLoading = true
                        try {
                            val packages = loadPackagesInternal()
                            lastLoadTime = System.currentTimeMillis()
                            packagesFlow.emit(packages)
                        } finally {
                            isLoading = false
                        }
                    }
                }
            }
        }
    
    private suspend fun loadPackagesInternal(): List<PackageEntity> = withContext(Dispatchers.IO) {
        val flowStartTime = System.currentTimeMillis()
        AppLogger.i(TAG, "⏱️ TIMING: getPackages START at $flowStartTime")
        try {
            // Get all user profiles (personal, work, clone, etc.)
            val getUsersStart = System.currentTimeMillis()
            val userProfiles = HiddenApiHelper.getUsers(context)
            val getUsersEnd = System.currentTimeMillis()
                AppLogger.i(TAG, "⏱️ TIMING: getUsers took ${getUsersEnd - getUsersStart}ms, returned ${userProfiles.size} profiles")
                AppLogger.d(TAG, "📱 Enumerating packages for ${userProfiles.size} user profiles")

                val rulesStart = System.currentTimeMillis()
                val firewallRules = firewallRepository.getAllRules().first()
                val rulesEnd = System.currentTimeMillis()
                AppLogger.i(TAG, "⏱️ TIMING: getAllRules().first() took ${rulesEnd - rulesStart}ms, returned ${firewallRules.size} rules")
                // Key by (packageName, userId) for multi-user support
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

                // Collect packages from all user profiles
                val allPackages = mutableListOf<PackageEntity>()

                // Log profile summary for debugging
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

                    // Log first few apps from each non-personal profile for debugging
                    if (profile.userId != 0 && installedPackages.isNotEmpty()) {
                        val sampleApps = installedPackages.take(5).map { it.packageName }
                        AppLogger.i(TAG, "📦 MULTI-USER: Sample apps from ${profile.displayName} profile: $sampleApps")
                    }

                    // OPTIMIZATION: Process packages in parallel chunks for better performance
                    // Using chunked processing to balance parallelism with memory usage
                    val chunkSize = 25 // Process 25 packages concurrently
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

                                // Calculate absolute UID for multi-user support
                                val absoluteUid = appInfo.uid

                                // Debug logging for VPN apps
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

                                // Load safety data for this package (already cached after first load)
                                val criticality = PackageSafetyLoader.getCriticality(context, appInfo.packageName)
                                val category = PackageSafetyLoader.getCategory(context, appInfo.packageName)
                                val affects = PackageSafetyLoader.getAffects(context, appInfo.packageName)

                                PackageEntity(
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

                // Log final summary for debugging
                val personalCount = allPackages.count { !it.isWorkProfile && !it.isCloneProfile }
                val workCount = allPackages.count { it.isWorkProfile }
                val cloneCount = allPackages.count { it.isCloneProfile }
                AppLogger.i(TAG, "📊 MULTI-USER FINAL: Total ${allPackages.size} packages (Personal: $personalCount, Work: $workCount, Clone: $cloneCount)")

                // Log some work/clone profile apps for verification
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

            allPackages.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get packages: ${e.message}", e)
            emptyList()
        }
    }
    
    override suspend fun getPackage(packageName: String, userId: Int): PackageEntity? {
        if (Constants.App.isOwnApp(packageName)) {
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                // Use HiddenApiHelper to get app info for the correct user profile
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

                // Debug logging for VPN apps
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
                    // Has explicit rule - use it as-is (absolute blocking state)
                    BlockingState(
                        isNetworkBlocked = rule.wifiBlocked || rule.mobileBlocked,
                        wifiBlocked = rule.wifiBlocked,
                        mobileBlocked = rule.mobileBlocked,
                        roamingBlocked = rule.blockWhenRoaming,
                        backgroundBlocked = rule.blockWhenBackground,
                        lanBlocked = rule.lanBlocked
                    )
                } else if (isCriticalPackage && allowCritical) {
                    // Setting ON + No explicit rule: Critical packages default to ALLOW
                    // User can manually change them, but they're not affected by Block All / Allow All
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
                    // No explicit rule - use default policy (only for non-critical packages)
                    BlockingState(
                        isNetworkBlocked = isBlockAllDefault,
                        wifiBlocked = isBlockAllDefault,
                        mobileBlocked = isBlockAllDefault,
                        roamingBlocked = isBlockAllDefault,
                        backgroundBlocked = false,  // Conservative: OFF by default
                        lanBlocked = isBlockAllDefault  // LAN blocking follows default policy
                    )
                }

                // Load safety data for this package
                val criticality = PackageSafetyLoader.getCriticality(context, appInfo.packageName)
                val category = PackageSafetyLoader.getCategory(context, appInfo.packageName)
                val affects = PackageSafetyLoader.getAffects(context, appInfo.packageName)

                // Determine work/clone profile status based on userId
                val isWorkProfile = userId in 10..99
                val isCloneProfile = userId >= 100

                PackageEntity(
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
                // PackageManager method failed (expected)
            } catch (e: Exception) {
                // PackageManager method failed
            }

            // Try Shizuku
            if (shizukuManager.isShizukuAvailable()) {
                // Request permission if not granted yet
                if (!shizukuManager.hasShizukuPermission) {
                    shizukuManager.requestShizukuPermission()
                    // Wait a bit for permission dialog
                    kotlinx.coroutines.delay(500)
                }

                if (shizukuManager.hasShizukuPermission) {
                    try {
                        // Use --user flag for multi-user/work profile support
                        val command = if (enabled) {
                            "pm enable --user $userId $packageName"
                        } else {
                            "pm disable-user --user $userId $packageName"
                        }

                        val (exitCode, _) = shizukuManager.executeShellCommand(command)
                        if (exitCode == 0) {
                            return@withContext true
                        }
                    } catch (e: Exception) {
                        // Shizuku method failed
                    }
                }
            }

            // Try root shell
            try {
                // Use --user flag for multi-user/work profile support
                val command = if (enabled) {
                    "pm enable --user $userId $packageName"
                } else {
                    "pm disable-user --user $userId $packageName"
                }

                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

                // Drain both streams to prevent blocking
                process.inputStream.bufferedReader().use { it.readText() }
                process.errorStream.bufferedReader().use { it.readText() }

                val exitCode = process.waitFor()
                process.destroy()

                if (exitCode == 0) {
                    return@withContext true
                }
            } catch (e: Exception) {
                // Root shell method failed
            }

            false
        }
    }

    override suspend fun getUninstalledSystemPackages(): List<PackageEntity> {
        return withContext(Dispatchers.IO) {
            try {
                // Get all system packages (including uninstalled) using -s flag
                val allSystemPackagesOutput = if (shizukuManager.isShizukuAvailable() && shizukuManager.hasShizukuPermission) {
                    val (exitCode, output) = shizukuManager.executeShellCommand("pm list packages -u -s")
                    if (exitCode == 0) output else ""
                } else {
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm list packages -u -s"))
                        val output = process.inputStream.bufferedReader().use { it.readText() }
                        process.errorStream.bufferedReader().use { it.readText() } // Drain error stream
                        process.waitFor()
                        process.destroy()
                        output
                    } catch (e: Exception) {
                        ""
                    }
                }

                // Get currently installed system packages
                val installedSystemPackagesOutput = if (shizukuManager.isShizukuAvailable() && shizukuManager.hasShizukuPermission) {
                    val (exitCode, output) = shizukuManager.executeShellCommand("pm list packages -s")
                    if (exitCode == 0) output else ""
                } else {
                    try {
                        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm list packages -s"))
                        val output = process.inputStream.bufferedReader().use { it.readText() }
                        process.errorStream.bufferedReader().use { it.readText() } // Drain error stream
                        process.waitFor()
                        process.destroy()
                        output
                    } catch (e: Exception) {
                        ""
                    }
                }

                // Parse package names
                val allSystemPackages = allSystemPackagesOutput.lines()
                    .filter { it.startsWith("package:") }
                    .map { it.removePrefix("package:").trim() }
                    .toSet()

                val installedSystemPackages = installedSystemPackagesOutput.lines()
                    .filter { it.startsWith("package:") }
                    .map { it.removePrefix("package:").trim() }
                    .toSet()

                // Find uninstalled system packages (difference between the two sets)
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
                            userId = 0,  // Default to personal profile
                            uid = 0,     // UID unknown for uninstalled packages
                            name = packageName, // Use package name as display name
                            icon = "⚙️", // System app icon
                            isEnabled = false, // Uninstalled packages are disabled
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
            // Try Shizuku
            if (shizukuManager.isShizukuAvailable()) {
                // Request permission if not granted yet
                if (!shizukuManager.hasShizukuPermission) {
                    shizukuManager.requestShizukuPermission()
                    // Wait a bit for permission dialog
                    kotlinx.coroutines.delay(500)
                }

                if (shizukuManager.hasShizukuPermission) {
                    try {
                        val command = "pm uninstall --user $userId $packageName"
                        val (exitCode, _) = shizukuManager.executeShellCommand(command)
                        if (exitCode == 0) {
                            return@withContext true
                        }
                    } catch (e: Exception) {
                        // Shizuku method failed
                    }
                }
            }

            // Try root shell
            try {
                val command = "pm uninstall --user $userId $packageName"
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

                // Drain both streams to prevent blocking
                process.inputStream.bufferedReader().use { it.readText() }
                process.errorStream.bufferedReader().use { it.readText() }

                val exitCode = process.waitFor()
                process.destroy()

                if (exitCode == 0) {
                    return@withContext true
                }
            } catch (e: Exception) {
                // Root shell method failed
            }

            false
        }
    }

    override suspend fun reinstallPackage(packageName: String, userId: Int): Boolean {
        if (Constants.App.isOwnApp(packageName)) {
            return false
        }

        return withContext(Dispatchers.IO) {
            // Try Shizuku
            if (shizukuManager.isShizukuAvailable()) {
                // Request permission if not granted yet
                if (!shizukuManager.hasShizukuPermission) {
                    shizukuManager.requestShizukuPermission()
                    // Wait a bit for permission dialog
                    kotlinx.coroutines.delay(500)
                }

                if (shizukuManager.hasShizukuPermission) {
                    try {
                        // Use --user flag for multi-user/work profile support
                        val command = "cmd package install-existing --user $userId $packageName"
                        val (exitCode, _) = shizukuManager.executeShellCommand(command)
                        if (exitCode == 0) {
                            return@withContext true
                        }
                    } catch (e: Exception) {
                        // Shizuku method failed
                    }
                }
            }

            // Try root shell
            try {
                // Use --user flag for multi-user/work profile support
                val command = "cmd package install-existing --user $userId $packageName"
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

                // Drain both streams to prevent blocking
                process.inputStream.bufferedReader().use { it.readText() }
                process.errorStream.bufferedReader().use { it.readText() }

                val exitCode = process.waitFor()
                process.destroy()

                if (exitCode == 0) {
                    return@withContext true
                }
            } catch (e: Exception) {
                // Root shell method failed
            }

            false
        }
    }

    override suspend fun forceStopPackage(packageName: String, userId: Int): Boolean {
        if (Constants.App.isOwnApp(packageName)) {
            return false
        }

        return withContext(Dispatchers.IO) {
            // Try Shizuku
            if (shizukuManager.isShizukuAvailable()) {
                // Request permission if not granted yet
                if (!shizukuManager.hasShizukuPermission) {
                    shizukuManager.requestShizukuPermission()
                    // Wait a bit for permission dialog
                    kotlinx.coroutines.delay(500)
                }

                if (shizukuManager.hasShizukuPermission) {
                    try {
                        // Use --user flag for multi-user/work profile support
                        val command = "am force-stop --user $userId $packageName"
                        val (exitCode, _) = shizukuManager.executeShellCommand(command)
                        if (exitCode == 0) {
                            return@withContext true
                        }
                    } catch (e: Exception) {
                        // Shizuku method failed
                    }
                }
            }

            // Try root shell
            try {
                // Use --user flag for multi-user/work profile support
                val command = "am force-stop --user $userId $packageName"
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

                // Drain both streams to prevent blocking
                process.inputStream.bufferedReader().use { it.readText() }
                process.errorStream.bufferedReader().use { it.readText() }

                val exitCode = process.waitFor()
                process.destroy()

                if (exitCode == 0) {
                    return@withContext true
                }
            } catch (e: Exception) {
                // Root shell method failed
            }

            // Try ActivityManager (limited effectiveness)
            try {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                activityManager.killBackgroundProcesses(packageName)
            } catch (e: Exception) {
                // ActivityManager method failed
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

    /**
     * Get all package metadata in a SINGLE system call instead of 7 separate calls.
     * This is a major performance optimization - reduces ~1400+ calls to ~200 calls
     * for a typical device with 200 packages.
     *
     * Combined flags: GET_PERMISSIONS | GET_SERVICES
     * - GET_PERMISSIONS: for requestedPermissions (used for hasNetworkAccess)
     * - GET_SERVICES: for services (used for VPN detection)
     */
    private fun getPackageMetadataBatch(packageName: String, userId: Int): PackageMetadata {
        return try {
            // Single call with combined flags to get all needed info
            val packageInfo = HiddenApiHelper.getPackageInfoAsUser(
                context,
                packageName,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
                userId
            )

            if (packageInfo == null) {
                // Return default metadata if package info unavailable
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

            // Extract all data from the single PackageInfo result
            val permissions = packageInfo.requestedPermissions?.toList() ?: emptyList()

            // Check for VPN service
            val isVpnApp = packageInfo.services?.any { serviceInfo ->
                serviceInfo.permission == Constants.Firewall.VPN_SERVICE_PERMISSION
            } ?: false

            // Check for network permissions
            val hasNetworkAccess = permissions.any { permission ->
                permission == "android.permission.INTERNET" ||
                permission == "android.permission.ACCESS_NETWORK_STATE" ||
                permission == "android.permission.ACCESS_WIFI_STATE"
            }

            // Version info
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
     */
    private fun hasVpnService(packageName: String, userId: Int = 0): Boolean {
        return try {
            val packageInfo = HiddenApiHelper.getPackageInfoAsUser(
                context,
                packageName,
                PackageManager.GET_SERVICES,
                userId
            ) ?: return false

            // Check if any service has BIND_VPN_SERVICE permission
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

    private fun hasNetworkPermissions(packageName: String, userId: Int = 0): Boolean {
        val permissions = getAppPermissions(packageName, userId)
        return permissions.any { permission ->
            permission == "android.permission.INTERNET" ||
            permission == "android.permission.ACCESS_NETWORK_STATE" ||
            permission == "android.permission.ACCESS_WIFI_STATE"
        }
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
                // Use HiddenApiHelper to get app info for the correct user profile
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
                // Use HiddenApiHelper to get app info for the correct user profile
                val appInfo = HiddenApiHelper.getApplicationInfoAsUser(
                    context, packageName, PackageManager.GET_META_DATA, userId
                ) ?: return@withContext false
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()

                if (existingRule != null) {
                    // Use atomic update to prevent race conditions
                    firewallRepository.updateWifiBlocking(packageName, userId, blocked)
                } else {
                    // Create new rule with default policy for other network types
                    val defaultPolicy = prefs.getString(
                        Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                        Constants.Settings.DEFAULT_FIREWALL_POLICY
                    )
                    val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                    val rule = FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = blocked,
                        mobileBlocked = isBlockAllDefault, // Inherit default policy for mobile
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
                // Use HiddenApiHelper to get app info for the correct user profile
                val appInfo = HiddenApiHelper.getApplicationInfoAsUser(
                    context, packageName, PackageManager.GET_META_DATA, userId
                ) ?: return@withContext false
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()

                if (existingRule != null) {
                    // Use atomic update to prevent race conditions
                    firewallRepository.updateMobileBlocking(packageName, userId, blocked)
                } else {
                    // Create new rule with default policy for other network types
                    val defaultPolicy = prefs.getString(
                        Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                        Constants.Settings.DEFAULT_FIREWALL_POLICY
                    )
                    val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                    val rule = FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = isBlockAllDefault, // Inherit default policy for WiFi
                        mobileBlocked = blocked,
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
                // Use HiddenApiHelper to get app info for the correct user profile
                val appInfo = HiddenApiHelper.getApplicationInfoAsUser(
                    context, packageName, PackageManager.GET_META_DATA, userId
                ) ?: return@withContext false
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()

                if (existingRule != null) {
                    // Use atomic update to prevent race conditions
                    firewallRepository.updateRoamingBlocking(packageName, userId, blocked)
                } else {
                    // Create new rule with default policy for other network types
                    val defaultPolicy = prefs.getString(
                        Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                        Constants.Settings.DEFAULT_FIREWALL_POLICY
                    )
                    val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                    val rule = FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = isBlockAllDefault, // Inherit default policy for WiFi
                        mobileBlocked = isBlockAllDefault, // Inherit default policy for mobile
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
                // Use HiddenApiHelper to get app info for the correct user profile
                val appInfo = HiddenApiHelper.getApplicationInfoAsUser(
                    context, packageName, PackageManager.GET_META_DATA, userId
                ) ?: return@withContext false
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()

                if (existingRule != null) {
                    // Use atomic update to prevent race conditions
                    firewallRepository.updateBackgroundBlocking(packageName, userId, blocked)
                } else {
                    // Create new rule with default policy for other network types
                    val defaultPolicy = prefs.getString(
                        Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                        Constants.Settings.DEFAULT_FIREWALL_POLICY
                    )
                    val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                    val rule = FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = isBlockAllDefault,
                        mobileBlocked = isBlockAllDefault,
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
                // Use HiddenApiHelper to get app info for the correct user profile
                val appInfo = HiddenApiHelper.getApplicationInfoAsUser(
                    context, packageName, PackageManager.GET_META_DATA, userId
                ) ?: return@withContext false
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()

                if (existingRule != null) {
                    // Use atomic update to prevent race conditions
                    firewallRepository.updateLanBlocking(packageName, userId, blocked)
                } else {
                    // Create new rule with default policy for other network types
                    val defaultPolicy = prefs.getString(
                        Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                        Constants.Settings.DEFAULT_FIREWALL_POLICY
                    ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY
                    val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                    val rule = FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = isBlockAllDefault,
                        mobileBlocked = isBlockAllDefault,
                        blockWhenRoaming = isBlockAllDefault,
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
                // Use HiddenApiHelper to get app info for the correct user profile
                val appInfo = HiddenApiHelper.getApplicationInfoAsUser(
                    context, packageName, PackageManager.GET_META_DATA, userId
                ) ?: return@withContext false
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()

                if (existingRule != null) {
                    // Use atomic batch update to prevent race conditions
                    firewallRepository.updateAllNetworkBlocking(packageName, userId, blocked)
                } else {
                    // Create new rule with all networks set to the same blocking state
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
                // Use HiddenApiHelper to get app info for the correct user profile
                val appInfo = HiddenApiHelper.getApplicationInfoAsUser(
                    context, packageName, PackageManager.GET_META_DATA, userId
                ) ?: return@withContext false
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()

                if (existingRule != null) {
                    // Use atomic batch update to prevent race conditions
                    firewallRepository.updateMobileAndRoaming(packageName, userId, mobileBlocked, roamingBlocked)
                } else {
                    // Create new rule - inherit default policy for WiFi
                    val defaultPolicy = prefs.getString(
                        Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                        Constants.Settings.DEFAULT_FIREWALL_POLICY
                    )
                    val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                    val rule = FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = isBlockAllDefault,
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
