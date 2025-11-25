package io.github.dorumrr.de1984.data.common

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
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
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager
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
            Manifest.permission.ACCESS_WIFI_STATE
        )

        val basicPermissionsGranted = requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

        return basicPermissionsGranted && hasNotificationPermission()
    }
    

    
    fun hasRootAccess(): Boolean {
        return rootManager.hasRootPermission
    }

    fun hasShizukuAccess(): Boolean {
        return shizukuManager.hasShizukuPermission
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
            hasShizukuAccess = hasShizukuAccess(),
            hasRootAccess = hasRootAccess(),
            canManagePackages = hasShizukuAccess() || hasRootAccess()
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

    /**
     * Check if VPN permission is granted.
     *
     * IMPORTANT: This method calls VpnService.prepare() which can revoke active VPN connections
     * from other apps (like Proton VPN). Only call this when:
     * 1. User is explicitly granting VPN permission (user-initiated action)
     * 2. Firewall is using VPN backend (not privileged backends)
     * 3. No other VPN app is currently active
     *
     * @param currentBackendType The currently active backend type, or null if no backend is active.
     *                           If a privileged backend (IPTABLES, CONNECTIVITY_MANAGER) is active,
     *                           this method will skip the VPN permission check and return false.
     * @param firewallManager Optional FirewallManager instance to check if another VPN is active.
     *                        If provided and another VPN is detected, skips the permission check.
     * @return true if VPN permission is granted, false otherwise
     */
    fun hasVpnPermission(
        currentBackendType: io.github.dorumrr.de1984.domain.firewall.FirewallBackendType? = null,
        firewallManager: io.github.dorumrr.de1984.data.firewall.FirewallManager? = null
    ): Boolean {
        // Skip VPN permission check if a privileged backend is active
        // This prevents killing user's third-party VPN (like Proton VPN) when they're using iptables/CM backend
        if (currentBackendType != null &&
            currentBackendType != io.github.dorumrr.de1984.domain.firewall.FirewallBackendType.VPN) {
            return false
        }

        // Skip VPN permission check if another VPN app is active
        // This prevents killing user's third-party VPN (like Proton VPN) during app initialization
        // or when checking permissions in the Settings screen
        if (firewallManager?.isAnotherVpnActive() == true) {
            android.util.Log.d("PermissionManager", "hasVpnPermission: Another VPN is active - skipping permission check")
            return false
        }

        return try {
            VpnService.prepare(context) == null
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
    val hasShizukuAccess: Boolean,
    val hasRootAccess: Boolean,
    val canManagePackages: Boolean
) {
    val overallCapabilityLevel: CapabilityLevel
        get() = when {
            hasRootAccess -> CapabilityLevel.FULL_ROOT
            hasShizukuAccess -> CapabilityLevel.FULL_SHIZUKU
            hasBasicPermissions -> CapabilityLevel.LIMITED
            else -> CapabilityLevel.MINIMAL
        }
}

enum class CapabilityLevel {
    MINIMAL,
    LIMITED,
    FULL_SHIZUKU,
    FULL_ROOT
}
