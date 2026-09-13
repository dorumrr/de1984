package io.github.dorumrr.de1984.domain.usecase

import android.content.Context
import android.content.pm.PackageManager
import io.github.dorumrr.de1984.utils.AppLogger
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.flow.first

class HandleNewAppInstallUseCase constructor(
    private val context: Context,
    private val firewallRepository: FirewallRepository,
    private val errorHandler: ErrorHandler
) {
    
    companion object {
        private const val TAG = "HandleNewAppInstallUseCase"

        /**
         * Return [rule] pointed at the app as it is installed right now, or [rule] itself if
         * nothing changed or the package is not installed for that user.
         *
         * A reinstall keeps the package name and hands the app a NEW uid. IptablesFirewallBackend
         * and NetworkPolicyManagerFirewallBackend both group rules by uid, so a stale uid matches
         * no installed app: the rule is enforced against nothing, and under Block All the app falls
         * through to the default and is blocked with no way back. Restoring a backup is the one
         * path that writes rules without coming through here, which is issue #81.
         *
         * Pass [appInfo] when the caller already has it; the lookup can go through a shell command
         * for a non-zero userId.
         */
        fun withCurrentIdentity(
            context: Context,
            rule: FirewallRule,
            appInfo: android.content.pm.ApplicationInfo? = null
        ): FirewallRule {
            val info = appInfo ?: try {
                io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getPackageInfoAsUser(
                    context, rule.packageName, 0, rule.userId
                )?.applicationInfo
            } catch (e: Exception) {
                null
            } ?: return rule

            val currentName = try {
                context.packageManager.getApplicationLabel(info).toString()
            } catch (e: Exception) {
                rule.appName
            }

            if (rule.uid == info.uid && rule.appName == currentName) return rule

            return rule.copy(
                uid = info.uid,
                appName = currentName,
                updatedAt = System.currentTimeMillis()
            )
        }
    }
    
    suspend fun execute(packageName: String, uid: Int? = null): Result<Unit> {
        return try {
            val userId = uid?.let { it / 100000 } ?: Constants.Firewall.ownUserId()

            val packageInfo = validatePackage(packageName, userId)
                ?: return Result.failure(Exception("Package not found or invalid: $packageName"))

            // Check if app was installed before De1984 to prevent notification spam
            // after clearing app data. Apps that existed before De1984 should not
            // trigger "new app" notifications.
            val de1984InstallTime = try {
                context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
            } catch (e: Exception) {
                0L
            }
            
            val isPreExistingApp = packageInfo.firstInstallTime < de1984InstallTime
            
            if (isPreExistingApp) {
                AppLogger.d(TAG, "Pre-existing app (installed before De1984): $packageName")
                if (!hasNetworkPermissions(packageName, userId)) {
                    return Result.failure(Exception("Pre-existing app without network permissions"))
                }
                
                val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()
                if (existingRule == null) {
                    val defaultRule = createDefaultFirewallRule(packageName, packageInfo, userId)
                    if (defaultRule != null) {
                        firewallRepository.insertRule(defaultRule)
                        AppLogger.d(TAG, "Created rule for pre-existing app: $packageName")
                    }
                } else {
                    refreshRuleIdentity(existingRule, packageInfo)
                }
                return Result.failure(Exception("Pre-existing app - notification skipped"))
            }

            if (!hasNetworkPermissions(packageName, userId)) {
                return Result.success(Unit)
            }

            val existingRule = firewallRepository.getRuleByPackage(packageName, userId).first()
            if (existingRule != null) {
                // The old rule is kept on purpose - the user configured it - but its UID must be
                // re-read. Android hands a reinstalled app a NEW uid, and the privileged backends
                // block by uid: a stale one matches nothing, so the app showed "Blocked" in the UI
                // while its traffic flowed. The label is refreshed for the same reason.
                refreshRuleIdentity(existingRule, packageInfo)
                return Result.success(Unit)
            }

            val defaultRule = createDefaultFirewallRule(packageName, packageInfo, userId)
                ?: return Result.failure(Exception("Failed to create firewall rule: applicationInfo is null"))
            firewallRepository.insertRule(defaultRule)

            Result.success(Unit)

        } catch (e: Exception) {
            val error = errorHandler.handleError(e, "new app install handling")
            Result.failure(error)
        }
    }

    private fun validatePackage(packageName: String, userId: Int = 0): android.content.pm.PackageInfo? {
        return try {
            if (packageName.isBlank() || !packageName.contains(".")) {
                return null
            }

            if (Constants.App.isOwnApp(packageName)) {
                return null
            }

            io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getPackageInfoAsUser(
                context, packageName, PackageManager.GET_PERMISSIONS, userId
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun hasNetworkPermissions(packageName: String, userId: Int = 0): Boolean {
        return try {
            val packageInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getPackageInfoAsUser(
                context, packageName, PackageManager.GET_PERMISSIONS, userId
            ) ?: return false
            val permissions = packageInfo.requestedPermissions ?: return false

            val networkPermissions = setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.ACCESS_WIFI_STATE"
            )

            permissions.any { it in networkPermissions }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Persist [withCurrentIdentity] for an existing rule. Writes only when something actually
     * changed, to avoid waking every rule observer on boot.
     */
    private suspend fun refreshRuleIdentity(
        existingRule: FirewallRule,
        packageInfo: android.content.pm.PackageInfo
    ) {
        val appInfo = packageInfo.applicationInfo ?: return
        val refreshed = withCurrentIdentity(context, existingRule, appInfo)
        if (refreshed === existingRule) return

        AppLogger.d(
            TAG,
            "Refreshing rule identity for ${existingRule.packageName}: " +
                "uid ${existingRule.uid} -> ${refreshed.uid}, " +
                "name '${existingRule.appName}' -> '${refreshed.appName}'"
        )
        firewallRepository.updateRule(refreshed)
    }

    private fun createDefaultFirewallRule(packageName: String, packageInfo: android.content.pm.PackageInfo, userId: Int): FirewallRule? {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val defaultPolicy = prefs.getString(
            Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
            Constants.Settings.DEFAULT_FIREWALL_POLICY
        )
        val allowCritical = prefs.getBoolean(
            Constants.Settings.KEY_ALLOW_CRITICAL_FIREWALL,
            Constants.Settings.DEFAULT_ALLOW_CRITICAL_FIREWALL
        )

        val appInfo = packageInfo.applicationInfo ?: return null
        val appName = try {
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
        val uid = appInfo.uid
        val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

        val isSystemCritical = Constants.Firewall.isSystemCritical(packageName)
        val isVpnApp = hasVpnService(packageName, uid / 100000)
        val isCriticalPackage = isSystemCritical || isVpnApp

        val isRecommendedAllow = Constants.Firewall.isSystemRecommendedAllow(packageName)

        return when {
            isCriticalPackage -> {
                if (!allowCritical) {
                    AppLogger.d(TAG, "Creating 'allow all' rule for critical package (protection ON): $packageName (userId=$userId)")
                    FirewallRule(
                        packageName = packageName,
                        userId = userId,
                        uid = uid,
                        appName = appName,
                        wifiBlocked = false,
                        mobileBlocked = false,
                        blockWhenRoaming = false,
                        enabled = true,
                        isSystemApp = isSystemApp
                    )
                } else {
                    // Setting ON: Don't create a rule - critical package will default to ALLOW
                    // User can manually change it if they want (critical packages are immune to bulk operations)
                    AppLogger.d(TAG, "Skipping rule creation for critical package (protection OFF, user can manually configure): $packageName (userId=$userId)")
                    null
                }
            }
            isRecommendedAllow -> {
                FirewallRule(
                    packageName = packageName,
                    userId = userId,
                    uid = uid,
                    appName = appName,
                    wifiBlocked = false,
                    mobileBlocked = false,
                    blockWhenRoaming = false,
                    enabled = true,
                    isSystemApp = isSystemApp
                )
            }
            defaultPolicy == Constants.Settings.POLICY_BLOCK_ALL -> {
                FirewallRule(
                    packageName = packageName,
                    userId = userId,
                    uid = uid,
                    appName = appName,
                    wifiBlocked = true,
                    mobileBlocked = true,
                    blockWhenRoaming = true,
                    lanBlocked = true,
                    enabled = true,
                    isSystemApp = isSystemApp
                )
            }
            else -> {
                FirewallRule(
                    packageName = packageName,
                    userId = userId,
                    uid = uid,
                    appName = appName,
                    wifiBlocked = false,
                    mobileBlocked = false,
                    blockWhenRoaming = false,
                    enabled = true,
                    isSystemApp = isSystemApp
                )
            }
        }
    }

    /**
     * Check if an app has a VPN service by looking for services with BIND_VPN_SERVICE permission.
     *
     * VPN apps don't REQUEST the BIND_VPN_SERVICE permission - they DECLARE it on their service.
     * This is a service permission that protects the VPN service from being bound by unauthorized apps.
     *
     * userId has NO DEFAULT on purpose. It used to default to 0, and every enforcement call site
     * omitted it - so a VPN app installed only in the work profile was looked up in the personal
     * profile, not found, and treated as an ordinary app. Block All then cut the work profile's
     * VPN. Making it required means the compiler catches the next caller that forgets.
     */
    private fun hasVpnService(packageName: String, userId: Int): Boolean {
        return try {
                // GET_PERMISSIONS is requested but never read - it makes this share a cache entry
                // with the getPackagesWithNetworkPermissions sweep, which asks for both.
                // getPackageInfoAsUser keys on "userId:flags:packageName", so GET_SERVICES alone was
                // always a different key and always a miss.
            val packageInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getPackageInfoAsUser(
                context,
                packageName,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
                userId
            ) ?: return false

            packageInfo.services?.any { serviceInfo ->
                serviceInfo.permission == Constants.Firewall.VPN_SERVICE_PERMISSION
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
