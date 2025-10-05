package io.github.dorumrr.de1984.domain.usecase

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.dorumrr.de1984.data.common.ErrorHandler
import io.github.dorumrr.de1984.domain.model.FirewallRule
import io.github.dorumrr.de1984.domain.repository.FirewallRepository
import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class HandleNewAppInstallUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val firewallRepository: FirewallRepository,
    private val errorHandler: ErrorHandler
) {
    
    companion object {
        private const val TAG = "HandleNewAppInstallUseCase"
    }
    
    suspend fun execute(packageName: String): Result<Unit> {
        return try {
            val packageInfo = validatePackage(packageName)
                ?: return Result.failure(Exception("Package not found or invalid: $packageName"))
            
            if (!hasNetworkPermissions(packageName)) {
                return Result.success(Unit)
            }
            
            val existingRule = firewallRepository.getRuleByPackage(packageName).first()
            if (existingRule != null) {
                return Result.success(Unit)
            }
            
            val defaultRule = createDefaultFirewallRule(packageName, packageInfo)
            firewallRepository.insertRule(defaultRule)
            
            Result.success(Unit)
            
        } catch (e: Exception) {
            val error = errorHandler.handleError(e, "new app install handling")
            Result.failure(error)
        }
    }
    
    private fun validatePackage(packageName: String): android.content.pm.PackageInfo? {
        return try {
            if (packageName.isBlank() || !packageName.contains(".")) {
                return null
            }
            
            if (Constants.App.isOwnApp(packageName)) {
                return null
            }
            
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun hasNetworkPermissions(packageName: String): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
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
    
    private fun createDefaultFirewallRule(packageName: String, packageInfo: android.content.pm.PackageInfo): FirewallRule {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val defaultPolicy = prefs.getString(
            Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
            Constants.Settings.DEFAULT_FIREWALL_POLICY
        )

        val appName = try {
            context.packageManager.getApplicationLabel(packageInfo.applicationInfo).toString()
        } catch (e: Exception) {
            packageName
        }
        val uid = packageInfo.applicationInfo.uid
        val isSystemApp = (packageInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

        return when (defaultPolicy) {
            Constants.Settings.POLICY_BLOCK_ALL -> {
                FirewallRule(
                    packageName = packageName,
                    uid = uid,
                    appName = appName,
                    wifiBlocked = true,
                    mobileBlocked = true,
                    blockWhenRoaming = true,
                    enabled = true,
                    isSystemApp = isSystemApp
                )
            }
            Constants.Settings.POLICY_ALLOW_ALL -> {
                FirewallRule(
                    packageName = packageName,
                    uid = uid,
                    appName = appName,
                    wifiBlocked = false,
                    mobileBlocked = false,
                    blockWhenRoaming = false,
                    enabled = true,
                    isSystemApp = isSystemApp
                )
            }
            else -> {
                FirewallRule(
                    packageName = packageName,
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
}
