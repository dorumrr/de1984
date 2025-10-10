package io.github.dorumrr.de1984.data.common

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import io.github.dorumrr.de1984.utils.Constants

data class PermissionInfo(
    val permission: String,
    val name: String,
    val description: String,
    val isGranted: Boolean,
    val requiresSettings: Boolean = false,
    val settingsAction: String? = null
)

class PermissionManager(
    private val context: Context,
    private val rootManager: RootManager
) {

    companion object {
        private const val PREFS_NAME = "de1984_permissions"
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    fun hasAllRequiredPermissions(): Boolean {
        return hasBasicPermissions()
    }

    fun hasMinimumPermissions(): Boolean {
        return hasBasicPermissions()
    }

    fun getRuntimePermissions(): List<String> {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions
    }

    fun getSpecialPermissions(): List<PermissionInfo> {
        return emptyList()
    }
    
    fun hasBasicPermissions(): Boolean {
        val requiredPermissions = listOf(
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.INTERNET
        )

        val basicPermissionsGranted = requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

        return basicPermissionsGranted && hasNotificationPermission()
    }
    

    
    fun hasRootAccess(): Boolean {
        return rootManager.hasRootPermission
    }
    
    fun hasPackageQueryPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.QUERY_ALL_PACKAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasSystemPermissions(): Boolean {
        return try {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_SECURE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }
    

    
    fun getMissingPermissions(): List<String> {
        val missingPermissions = mutableListOf<String>()

        if (!hasPackageQueryPermission()) {
            missingPermissions.add(Constants.Permissions.QUERY_ALL_PACKAGES_PERMISSION)
        }

        if (!hasNotificationPermission()) {
            missingPermissions.add("Notification Permission")
        }

        return missingPermissions
    }
    
    fun getSystemCapabilities(): SystemCapabilities {
        return SystemCapabilities(
            hasBasicPermissions = hasBasicPermissions(),
            hasPackageQuery = hasPackageQueryPermission(),
            hasRootAccess = hasRootAccess(),
            canManagePackages = hasRootAccess()
        )
    }

    fun isBatteryOptimizationDisabled(): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    fun createBatteryOptimizationIntent(): android.content.Intent? {
        if (isBatteryOptimizationDisabled()) {
            return null
        }

        return try {
            android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${context.packageName}")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun hasRequestedNotificationPermission(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
    }

    fun markNotificationPermissionRequested() {
        prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
    }

    fun resetNotificationPermissionTracking() {
        prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false).apply()
    }
}

data class SystemCapabilities(
    val hasBasicPermissions: Boolean,
    val hasPackageQuery: Boolean,
    val hasRootAccess: Boolean,
    val canManagePackages: Boolean
) {
    val overallCapabilityLevel: CapabilityLevel
        get() = when {
            hasRootAccess -> CapabilityLevel.FULL_ROOT
            hasBasicPermissions -> CapabilityLevel.LIMITED
            else -> CapabilityLevel.MINIMAL
        }
}

enum class CapabilityLevel {
    MINIMAL,
    LIMITED,
    FULL_ROOT
}
