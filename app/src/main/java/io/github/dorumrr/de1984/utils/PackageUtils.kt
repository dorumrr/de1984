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
    
    fun getPackageIcon(context: Context, packageName: String): Drawable? {
        return try {
            val packageManager = context.packageManager
            packageManager.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
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
    
    fun getPackageType(packageInfo: PackageInfo): String {
        return when {
            packageInfo.isSystemApp() -> Constants.Packages.TYPE_SYSTEM
            else -> Constants.Packages.TYPE_USER
        }
    }
    
    fun filterPackagesByType(packages: List<PackageInfo>, type: String): List<PackageInfo> {
        return when (type.lowercase()) {
            "system" -> packages.filter { it.isSystemApp() }
            "user" -> packages.filter { !it.isSystemApp() }
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
    
    fun sortPackages(
        context: Context,
        packages: List<PackageInfo>,
        sortBy: SortCriteria = SortCriteria.NAME
    ): List<PackageInfo> {
        return when (sortBy) {
            SortCriteria.NAME -> packages.sortedBy { 
                getPackageDisplayName(context, it.packageName).lowercase() 
            }
            SortCriteria.PACKAGE_NAME -> packages.sortedBy { 
                it.packageName.lowercase() 
            }
            SortCriteria.INSTALL_TIME -> packages.sortedByDescending { 
                it.firstInstallTime 
            }
            SortCriteria.TYPE -> packages.sortedWith { a, b ->
                val typeA = getPackageType(a)
                val typeB = getPackageType(b)
                typeA.compareTo(typeB)
            }
        }
    }
    
    enum class SortCriteria {
        NAME,
        PACKAGE_NAME,
        INSTALL_TIME,
        TYPE
    }
}
