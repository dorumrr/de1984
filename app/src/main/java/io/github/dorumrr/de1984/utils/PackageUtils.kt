package io.github.dorumrr.de1984.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat

object PackageUtils {
    
    fun getInstalledPackages(
        context: Context,
        includeSystem: Boolean = true,
        includeUser: Boolean = true
    ): List<PackageInfo> {
        return try {
            val packageManager = context.packageManager
            val allPackages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
            
            allPackages.filter { packageInfo ->
                val isSystem = packageInfo.isSystemApp()
                when {
                    isSystem && includeSystem -> true
                    !isSystem && includeUser -> true
                    else -> false
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun getPackageIcon(context: Context, packageName: String, userId: Int = 0): Drawable? {
        return try {
            val packageManager = context.packageManager
            val appInfo = io.github.dorumrr.de1984.data.multiuser.HiddenApiHelper.getApplicationInfoAsUser(
                context, packageName, 0, userId
            )
            if (appInfo != null) {
                packageManager.getApplicationIcon(appInfo)
            } else {
                ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
            }
        } catch (e: Exception) {
            ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
        }
    }
    
    fun getPackageDisplayName(context: Context, packageName: String): String {
        return try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName.formatPackageName()
        }
    }
    
    fun isPackageEnabled(context: Context, packageName: String): Boolean {
        return try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            appInfo.enabled
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    fun getPackageVersion(context: Context, packageName: String): String {
        return try {
            val packageManager = context.packageManager
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "Unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "Unknown"
        }
    }
    
    fun getPackageInstallTime(context: Context, packageName: String): Long {
        return try {
            val packageManager = context.packageManager
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.firstInstallTime
        } catch (e: PackageManager.NameNotFoundException) {
            0L
        }
    }
    
    fun getPackageSize(context: Context, packageName: String): Long {
        return try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            appInfo.sourceDir?.let { 
                java.io.File(it).length() 
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
    
    fun hasNetworkPermissions(context: Context, packageName: String): Boolean {
        return try {
            val packageManager = context.packageManager
            val packageInfo = packageManager.getPackageInfo(
                packageName, 
                PackageManager.GET_PERMISSIONS
            )
            
            packageInfo.requestedPermissions?.any { permission ->
                permission == android.Manifest.permission.INTERNET ||
                permission == android.Manifest.permission.ACCESS_NETWORK_STATE ||
                permission == android.Manifest.permission.ACCESS_WIFI_STATE
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
    
    fun filterPackagesByType(packages: List<PackageInfo>, type: String): List<PackageInfo> {
        return when (type.lowercase()) {
            Constants.Packages.TYPE_SYSTEM -> packages.filter { it.isSystemApp() }
            Constants.Packages.TYPE_USER -> packages.filter { !it.isSystemApp() }
            "all" -> packages
            else -> packages
        }
    }
    
    fun filterPackagesByState(
        context: Context, 
        packages: List<PackageInfo>, 
        enabled: Boolean
    ): List<PackageInfo> {
        return packages.filter { packageInfo ->
            isPackageEnabled(context, packageInfo.packageName) == enabled
        }
    }
    
    fun searchPackages(
        context: Context,
        packages: List<PackageInfo>, 
        query: String
    ): List<PackageInfo> {
        if (query.isBlank()) return packages
        
        val lowerQuery = query.lowercase()
        return packages.filter { packageInfo ->
            val displayName = getPackageDisplayName(context, packageInfo.packageName).lowercase()
            val packageName = packageInfo.packageName.lowercase()
            
            displayName.contains(lowerQuery) || packageName.contains(lowerQuery)
        }
    }
    
    /** Time sorts put the newest first and an unknown time last; ties fall back to name. */
    fun <T> sortPackages(
        packages: List<T>,
        sortBy: SortCriteria,
        name: (T) -> String,
        installTime: (T) -> Long?,
        updateTime: (T) -> Long?,
        configured: (T) -> Boolean = { false },
    ): List<T> {
        val byName = compareBy<T> { name(it).lowercase() }
        fun newestFirst(time: (T) -> Long?) =
            compareBy<T> { time(it) == null }.thenByDescending { time(it) }.then(byName)
        return when (sortBy) {
            SortCriteria.NAME -> packages.sortedWith(byName)
            SortCriteria.INSTALL_TIME -> packages.sortedWith(newestFirst(installTime))
            SortCriteria.UPDATE_TIME -> packages.sortedWith(newestFirst(updateTime))
            SortCriteria.CONFIGURED_FIRST -> packages.sortedWith(compareByDescending<T> { configured(it) }.then(byName))
        }
    }

    enum class SortCriteria {
        NAME,
        INSTALL_TIME,
        UPDATE_TIME,
        CONFIGURED_FIRST
    }
}
