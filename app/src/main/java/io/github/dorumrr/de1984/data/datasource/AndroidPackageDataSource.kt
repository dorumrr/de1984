package io.github.dorumrr.de1984.data.datasource

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.model.PackageEntity
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

private data class BlockingState(
    val isNetworkBlocked: Boolean,
    val wifiBlocked: Boolean,
    val mobileBlocked: Boolean,
    val roamingBlocked: Boolean
)

class AndroidPackageDataSource(
    private val context: Context,
    private val firewallRepository: FirewallRepository,
    private val shizukuManager: ShizukuManager
) : PackageDataSource {

    private val packageManager = context.packageManager

    companion object {
        private const val TAG = "AndroidPackageDataSource"
    }
    
    override fun getPackages(): Flow<List<PackageEntity>> = flow {
        val packages = withContext(Dispatchers.IO) {
            try {
                val installedPackages = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

                val firewallRules = firewallRepository.getAllRules().first()
                val rulesByPackage = firewallRules.associateBy { it.packageName }

                val prefs = context.getSharedPreferences("de1984_prefs", Context.MODE_PRIVATE)
                val defaultPolicy = prefs.getString(
                    Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                    Constants.Settings.DEFAULT_FIREWALL_POLICY
                ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY
                val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                installedPackages
                    .filter { !Constants.App.isOwnApp(it.packageName) }
                    .map { appInfo ->
                        val rule = rulesByPackage[appInfo.packageName]

                        val blockingState = if (Constants.Firewall.isSystemCritical(appInfo.packageName)) {
                            // System-critical packages MUST ALWAYS be allowed, regardless of rules or default policy
                            BlockingState(
                                isNetworkBlocked = false,
                                wifiBlocked = false,
                                mobileBlocked = false,
                                roamingBlocked = false
                            )
                        } else if (rule != null && rule.enabled) {
                            // Has explicit rule - use it as-is (absolute blocking state)
                            BlockingState(
                                isNetworkBlocked = rule.wifiBlocked || rule.mobileBlocked,
                                wifiBlocked = rule.wifiBlocked,
                                mobileBlocked = rule.mobileBlocked,
                                roamingBlocked = rule.blockWhenRoaming
                            )
                        } else {
                            // No explicit rule - use default policy
                            BlockingState(
                                isNetworkBlocked = isBlockAllDefault,
                                wifiBlocked = isBlockAllDefault,
                                mobileBlocked = isBlockAllDefault,
                                roamingBlocked = isBlockAllDefault
                            )
                        }

                        PackageEntity(
                            packageName = appInfo.packageName,
                            name = getAppName(appInfo),
                            icon = getAppIconEmoji(appInfo),
                            isEnabled = appInfo.enabled,
                            type = if (isSystemApp(appInfo)) Constants.Packages.TYPE_SYSTEM else Constants.Packages.TYPE_USER,
                            versionName = getVersionName(appInfo.packageName),
                            versionCode = getVersionCode(appInfo.packageName),
                            installTime = getInstallTime(appInfo.packageName),
                            updateTime = getUpdateTime(appInfo.packageName),
                            permissions = getAppPermissions(appInfo.packageName),
                            hasNetworkAccess = hasNetworkPermissions(appInfo.packageName),
                            isNetworkBlocked = blockingState.isNetworkBlocked,
                            wifiBlocked = blockingState.wifiBlocked,
                            mobileBlocked = blockingState.mobileBlocked,
                            roamingBlocked = blockingState.roamingBlocked
                        )
                    }.sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                emptyList()
            }
        }
        emit(packages)
    }.flowOn(Dispatchers.IO)
    
    override suspend fun getPackage(packageName: String): PackageEntity? {
        if (Constants.App.isOwnApp(packageName)) {
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)

                val rule = firewallRepository.getRuleByPackage(packageName).first()

                val prefs = context.getSharedPreferences("de1984_prefs", Context.MODE_PRIVATE)
                val defaultPolicy = prefs.getString(
                    Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                    Constants.Settings.DEFAULT_FIREWALL_POLICY
                ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY
                val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                val blockingState = if (Constants.Firewall.isSystemCritical(packageName)) {
                    // System-critical packages MUST ALWAYS be allowed, regardless of rules or default policy
                    BlockingState(
                        isNetworkBlocked = false,
                        wifiBlocked = false,
                        mobileBlocked = false,
                        roamingBlocked = false
                    )
                } else if (rule != null && rule.enabled) {
                    // Has explicit rule - use it as-is (absolute blocking state)
                    BlockingState(
                        isNetworkBlocked = rule.wifiBlocked || rule.mobileBlocked,
                        wifiBlocked = rule.wifiBlocked,
                        mobileBlocked = rule.mobileBlocked,
                        roamingBlocked = rule.blockWhenRoaming
                    )
                } else {
                    // No explicit rule - use default policy
                    BlockingState(
                        isNetworkBlocked = isBlockAllDefault,
                        wifiBlocked = isBlockAllDefault,
                        mobileBlocked = isBlockAllDefault,
                        roamingBlocked = isBlockAllDefault
                    )
                }

                PackageEntity(
                    packageName = appInfo.packageName,
                    name = getAppName(appInfo),
                    icon = getAppIconEmoji(appInfo),
                    isEnabled = appInfo.enabled,
                    type = if (isSystemApp(appInfo)) Constants.Packages.TYPE_SYSTEM else Constants.Packages.TYPE_USER,
                    versionName = getVersionName(appInfo.packageName),
                    versionCode = getVersionCode(appInfo.packageName),
                    installTime = getInstallTime(appInfo.packageName),
                    updateTime = getUpdateTime(appInfo.packageName),
                    permissions = getAppPermissions(appInfo.packageName),
                    hasNetworkAccess = hasNetworkPermissions(appInfo.packageName),
                    isNetworkBlocked = blockingState.isNetworkBlocked,
                    wifiBlocked = blockingState.wifiBlocked,
                    mobileBlocked = blockingState.mobileBlocked,
                    roamingBlocked = blockingState.roamingBlocked
                )
            } catch (e: Exception) {
                null
            }
        }
    }
    
    override suspend fun setPackageEnabled(packageName: String, enabled: Boolean): Boolean {
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
                        val command = if (enabled) {
                            "pm enable $packageName"
                        } else {
                            "pm disable-user $packageName"
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
                val command = if (enabled) {
                    "pm enable $packageName"
                } else {
                    "pm disable-user $packageName"
                }

                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    return@withContext true
                }
            } catch (e: Exception) {
                // Root shell method failed
            }

            false
        }
    }
    
    override suspend fun uninstallPackage(packageName: String): Boolean {
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
                        val command = "pm uninstall --user 0 $packageName"
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
                val command = "pm uninstall --user 0 $packageName"
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    return@withContext true
                }
            } catch (e: Exception) {
                // Root shell method failed
            }

            false
        }
    }
    
    override suspend fun forceStopPackage(packageName: String): Boolean {
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
                        val command = "am force-stop $packageName"
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
                val command = "am force-stop $packageName"
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val exitCode = process.waitFor()

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
    
    private fun getVersionName(packageName: String): String? {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getVersionCode(packageName: String): Long? {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
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
    
    private fun getInstallTime(packageName: String): Long? {
        return try {
            packageManager.getPackageInfo(packageName, 0).firstInstallTime
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getUpdateTime(packageName: String): Long? {
        return try {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getAppPermissions(packageName: String): List<String> {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            packageInfo.requestedPermissions?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun hasNetworkPermissions(packageName: String): Boolean {
        val permissions = getAppPermissions(packageName)
        return permissions.any { permission ->
            permission == "android.permission.INTERNET" ||
            permission == "android.permission.ACCESS_NETWORK_STATE" ||
            permission == "android.permission.ACCESS_WIFI_STATE"
        }
    }

    override suspend fun setNetworkAccess(packageName: String, allowed: Boolean): Boolean {
        if (Constants.Firewall.isSystemCritical(packageName)) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val existingRule = firewallRepository.getRuleByPackage(packageName).first()
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
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = !allowed,
                        mobileBlocked = !allowed,
                        blockWhenRoaming = !allowed,
                        enabled = true,
                        isSystemApp = isSystemApp(appInfo),
                        hasInternetPermission = hasNetworkPermissions(packageName)
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

    override suspend fun setWifiBlocking(packageName: String, blocked: Boolean): Boolean {
        if (Constants.Firewall.isSystemCritical(packageName)) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val existingRule = firewallRepository.getRuleByPackage(packageName).first()

                if (existingRule != null) {
                    // Use atomic update to prevent race conditions
                    firewallRepository.updateWifiBlocking(packageName, blocked)
                } else {
                    // Create new rule with default policy for other network types
                    val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
                    val defaultPolicy = prefs.getString(
                        Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                        Constants.Settings.DEFAULT_FIREWALL_POLICY
                    )
                    val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                    val rule = FirewallRule(
                        packageName = packageName,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = blocked,
                        mobileBlocked = isBlockAllDefault, // Inherit default policy for mobile
                        enabled = true,
                        isSystemApp = isSystemApp(appInfo),
                        hasInternetPermission = hasNetworkPermissions(packageName)
                    )
                    firewallRepository.insertRule(rule)
                }

                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun setMobileBlocking(packageName: String, blocked: Boolean): Boolean {
        if (Constants.Firewall.isSystemCritical(packageName)) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val existingRule = firewallRepository.getRuleByPackage(packageName).first()

                if (existingRule != null) {
                    // Use atomic update to prevent race conditions
                    firewallRepository.updateMobileBlocking(packageName, blocked)
                } else {
                    // Create new rule with default policy for other network types
                    val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
                    val defaultPolicy = prefs.getString(
                        Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                        Constants.Settings.DEFAULT_FIREWALL_POLICY
                    )
                    val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                    val rule = FirewallRule(
                        packageName = packageName,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = isBlockAllDefault, // Inherit default policy for WiFi
                        mobileBlocked = blocked,
                        enabled = true,
                        isSystemApp = isSystemApp(appInfo),
                        hasInternetPermission = hasNetworkPermissions(packageName)
                    )
                    firewallRepository.insertRule(rule)
                }

                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun setRoamingBlocking(packageName: String, blocked: Boolean): Boolean {
        if (Constants.Firewall.isSystemCritical(packageName)) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val existingRule = firewallRepository.getRuleByPackage(packageName).first()

                if (existingRule != null) {
                    // Use atomic update to prevent race conditions
                    firewallRepository.updateRoamingBlocking(packageName, blocked)
                } else {
                    // Create new rule with default policy for other network types
                    val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
                    val defaultPolicy = prefs.getString(
                        Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                        Constants.Settings.DEFAULT_FIREWALL_POLICY
                    )
                    val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                    val rule = FirewallRule(
                        packageName = packageName,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = isBlockAllDefault, // Inherit default policy for WiFi
                        mobileBlocked = isBlockAllDefault, // Inherit default policy for mobile
                        blockWhenRoaming = blocked,
                        enabled = true,
                        isSystemApp = isSystemApp(appInfo),
                        hasInternetPermission = hasNetworkPermissions(packageName)
                    )
                    firewallRepository.insertRule(rule)
                }

                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun setAllNetworkBlocking(packageName: String, blocked: Boolean): Boolean {
        if (Constants.Firewall.isSystemCritical(packageName)) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val existingRule = firewallRepository.getRuleByPackage(packageName).first()

                if (existingRule != null) {
                    // Use atomic batch update to prevent race conditions
                    firewallRepository.updateAllNetworkBlocking(packageName, blocked)
                } else {
                    // Create new rule with all networks set to the same blocking state
                    val rule = FirewallRule(
                        packageName = packageName,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = blocked,
                        mobileBlocked = blocked,
                        blockWhenRoaming = blocked,
                        enabled = true,
                        isSystemApp = isSystemApp(appInfo),
                        hasInternetPermission = hasNetworkPermissions(packageName)
                    )
                    firewallRepository.insertRule(rule)
                }

                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun setMobileAndRoaming(packageName: String, mobileBlocked: Boolean, roamingBlocked: Boolean): Boolean {
        if (Constants.Firewall.isSystemCritical(packageName)) {
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                val existingRule = firewallRepository.getRuleByPackage(packageName).first()

                if (existingRule != null) {
                    // Use atomic batch update to prevent race conditions
                    firewallRepository.updateMobileAndRoaming(packageName, mobileBlocked, roamingBlocked)
                } else {
                    // Create new rule - inherit default policy for WiFi
                    val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
                    val defaultPolicy = prefs.getString(
                        Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                        Constants.Settings.DEFAULT_FIREWALL_POLICY
                    )
                    val isBlockAllDefault = defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL

                    val rule = FirewallRule(
                        packageName = packageName,
                        uid = appInfo.uid,
                        appName = getAppName(appInfo),
                        wifiBlocked = isBlockAllDefault,
                        mobileBlocked = mobileBlocked,
                        blockWhenRoaming = roamingBlocked,
                        enabled = true,
                        isSystemApp = isSystemApp(appInfo),
                        hasInternetPermission = hasNetworkPermissions(packageName)
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
