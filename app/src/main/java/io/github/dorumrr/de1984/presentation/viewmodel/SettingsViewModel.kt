package io.github.dorumrr.de1984.presentation.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.dorumrr.de1984.BuildConfig
import io.github.dorumrr.de1984.data.common.PermissionManager
import io.github.dorumrr.de1984.data.common.RootManager
import io.github.dorumrr.de1984.data.common.RootStatus
import io.github.dorumrr.de1984.data.common.ShizukuManager
import io.github.dorumrr.de1984.data.common.ShizukuStatus
import io.github.dorumrr.de1984.data.firewall.FirewallManager
import io.github.dorumrr.de1984.data.service.FirewallVpnService
import io.github.dorumrr.de1984.domain.firewall.FirewallBackendType
import io.github.dorumrr.de1984.domain.firewall.FirewallMode
import io.github.dorumrr.de1984.domain.repository.FirewallRepository

import io.github.dorumrr.de1984.utils.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val context: Context,
    private val permissionManager: PermissionManager,
    private val rootManager: RootManager,
    private val shizukuManager: ShizukuManager,
    private val firewallManager: FirewallManager,
    private val firewallRepository: FirewallRepository
) : ViewModel() {

    companion object {
        private const val TAG = "SettingsViewModel"
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val rootStatus: StateFlow<RootStatus> = rootManager.rootStatus
    val shizukuStatus: StateFlow<ShizukuStatus> = shizukuManager.shizukuStatus
    val activeBackendType: StateFlow<FirewallBackendType?> = firewallManager.activeBackendType


    
    init {
        Log.d(TAG, "=== SettingsViewModel init ===")
        loadSettings()
        loadSystemInfo()
        cleanupOrphanedPreferences()
        Log.d(TAG, "Requesting root permission check...")
        requestRootPermission() // Check root status on initialization
        Log.d(TAG, "Requesting Shizuku permission check...")
        requestShizukuPermission() // Check Shizuku status on initialization
    }


    fun requestRootPermission() {
        Log.d(TAG, "requestRootPermission() called")
        viewModelScope.launch {
            rootManager.checkRootStatus()
        }
    }

    fun hasRequestedRootPermission(): Boolean {
        return rootManager.hasRequestedRootPermission()
    }

    fun markRootPermissionRequested() {
        rootManager.markRootPermissionRequested()
    }

    fun requestShizukuPermission() {
        Log.d(TAG, "requestShizukuPermission() called")
        viewModelScope.launch {
            shizukuManager.checkShizukuStatus()

            // Auto-request Shizuku permission if running but not granted (like we do for root)
            val status = shizukuManager.shizukuStatus.value
            Log.d(TAG, "Shizuku status after check: $status")
            if (status == ShizukuStatus.RUNNING_NO_PERMISSION) {
                Log.d(TAG, "Shizuku is running but no permission - auto-requesting permission")
                shizukuManager.requestShizukuPermission()
            }
        }
    }

    fun grantShizukuPermission() {
        shizukuManager.requestShizukuPermission()
    }
    
    private fun loadSettings() {
        viewModelScope.launch {
            val prefs = context.getSharedPreferences("de1984_prefs", Context.MODE_PRIVATE)

            val firewallModeString = prefs.getString(
                Constants.Settings.KEY_FIREWALL_MODE,
                Constants.Settings.DEFAULT_FIREWALL_MODE
            ) ?: Constants.Settings.DEFAULT_FIREWALL_MODE

            _uiState.value = _uiState.value.copy(
                autoRefresh = prefs.getBoolean("auto_refresh", true),
                showSystemApps = prefs.getBoolean("show_system_apps", false),
                darkTheme = prefs.getBoolean("dark_theme", false),
                refreshInterval = prefs.getInt("refresh_interval", 30),
                showAppIcons = prefs.getBoolean(Constants.Settings.KEY_SHOW_APP_ICONS, Constants.Settings.DEFAULT_SHOW_APP_ICONS),
                defaultFirewallPolicy = prefs.getString(
                    Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY,
                    Constants.Settings.DEFAULT_FIREWALL_POLICY
                ) ?: Constants.Settings.DEFAULT_FIREWALL_POLICY,
                newAppNotifications = prefs.getBoolean(Constants.Settings.KEY_NEW_APP_NOTIFICATIONS, Constants.Settings.DEFAULT_NEW_APP_NOTIFICATIONS),
                firewallMode = FirewallMode.fromString(firewallModeString) ?: FirewallMode.AUTO
            )
        }
    }
    
    private fun loadSystemInfo() {
        viewModelScope.launch {
            val systemCapabilities = permissionManager.getSystemCapabilities()

            val systemInfo = SystemInfo(
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                androidROM = getAndroidROMInfo(),
                hasRoot = false,
                architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"
            )

            _uiState.value = _uiState.value.copy(
                systemInfo = systemInfo,
                appVersion = BuildConfig.VERSION_NAME,
                buildNumber = BuildConfig.VERSION_CODE.toString(),
                hasBasicPermissions = systemCapabilities.hasBasicPermissions,
                hasEnhancedPermissions = true,
                hasAdvancedPermissions = false
            )
        }
    }

    private fun getAndroidROMInfo(): String {
        return try {
            val displayInfo = Build.DISPLAY
            when {
                displayInfo.contains("lineage", ignoreCase = true) -> displayInfo
                displayInfo.contains("pixel", ignoreCase = true) -> "Pixel Experience"
                displayInfo.isNotBlank() -> displayInfo
                else -> "Stock Android"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    private fun saveSetting(key: String, value: Any) {
        val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is String -> editor.putString(key, value)
        }

        editor.apply()
    }
    
    fun setAutoRefresh(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(autoRefresh = enabled)
        saveSetting("auto_refresh", enabled)
    }
    
    fun setShowSystemApps(show: Boolean) {
        _uiState.value = _uiState.value.copy(showSystemApps = show)
        saveSetting("show_system_apps", show)
    }
    
    fun setDarkTheme(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(darkTheme = enabled)
        saveSetting("dark_theme", enabled)
    }

    fun setShowAppIcons(show: Boolean) {
        _uiState.value = _uiState.value.copy(showAppIcons = show)
        saveSetting(Constants.Settings.KEY_SHOW_APP_ICONS, show)
    }

    /**
     * Change the default firewall policy.
     * Rules are preserved and interpreted based on the new policy.
     */
    fun setDefaultFirewallPolicy(newPolicy: String) {
        val oldPolicy = _uiState.value.defaultFirewallPolicy

        // If policy is the same, do nothing
        if (oldPolicy == newPolicy) {
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "Changing default policy to: $newPolicy (rules preserved with inverted semantics)")

                // Update the policy - rules are preserved and interpreted based on new policy
                _uiState.value = _uiState.value.copy(
                    defaultFirewallPolicy = newPolicy
                )
                saveSetting(Constants.Settings.KEY_DEFAULT_FIREWALL_POLICY, newPolicy)

                // Trigger rule re-application if firewall is running
                // This is more efficient than restarting the entire firewall
                if (firewallManager.isActive()) {
                    Log.d(TAG, "Triggering rule re-application for policy change")
                    firewallManager.triggerRuleReapplication()
                }

                Log.d(TAG, "Policy changed successfully, rules preserved")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to change policy", e)
            }
        }
    }

    fun setNewAppNotifications(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(newAppNotifications = enabled)
        saveSetting(Constants.Settings.KEY_NEW_APP_NOTIFICATIONS, enabled)
    }

    fun setFirewallMode(mode: FirewallMode) {
        _uiState.value = _uiState.value.copy(firewallMode = mode)
        firewallManager.setMode(mode)

        // Restart firewall if running to apply new mode
        viewModelScope.launch {
            if (firewallManager.isActive()) {
                restartFirewallIfRunning()
            }
        }
    }

    fun checkIptablesAvailability(callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            val available = firewallManager.isIptablesAvailable()
            callback(available)
        }
    }

    private suspend fun restartFirewallIfRunning() {
        try {
            // Check if firewall is actually running before restarting
            val prefs = context.getSharedPreferences(Constants.Settings.PREFS_NAME, Context.MODE_PRIVATE)
            val isFirewallEnabled = prefs.getBoolean(Constants.Settings.KEY_FIREWALL_ENABLED, false)

            if (!isFirewallEnabled) {
                Log.d(TAG, "Firewall not running, skipping restart")
                return
            }

            Log.d(TAG, "Restarting firewall due to settings change")

            // Stop current firewall (whatever backend is running)
            firewallManager.stopFirewall()

            // Wait a bit for cleanup
            delay(500)

            // Start firewall with new mode (explicitly pass the mode to avoid race conditions)
            val newMode = _uiState.value.firewallMode
            firewallManager.startFirewall(newMode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart firewall", e)
        }
    }

    fun setRefreshInterval(interval: Int) {
        _uiState.value = _uiState.value.copy(refreshInterval = interval)
        saveSetting("refresh_interval", interval)
    }
    

    
    fun showLicenses() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                message = "Open source licenses screen coming soon"
            )
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    /**
     * One-time cleanup of orphaned update-related preferences.
     * This method removes preferences that are no longer used after update system removal.
     */
    private fun cleanupOrphanedPreferences() {
        val prefs = context.getSharedPreferences("de1984_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("auto_check_updates")
            .remove("last_update_check")
            .remove("last_update_check_result")
            .remove("last_update_version")
            .remove("last_update_url")
            .remove("last_update_notes")
            .remove("last_update_error")
            .apply()
    }

    class Factory(
        private val context: Context,
        private val permissionManager: PermissionManager,
        private val rootManager: RootManager,
        private val shizukuManager: ShizukuManager,
        private val firewallManager: FirewallManager,
        private val firewallRepository: FirewallRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                return SettingsViewModel(
                    context,
                    permissionManager,
                    rootManager,
                    shizukuManager,
                    firewallManager,
                    firewallRepository
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

data class SettingsUiState(
    val autoRefresh: Boolean = true,
    val showSystemApps: Boolean = false,
    val darkTheme: Boolean = false,
    val showAppIcons: Boolean = true,
    val defaultFirewallPolicy: String = Constants.Settings.DEFAULT_FIREWALL_POLICY,
    val newAppNotifications: Boolean = Constants.Settings.DEFAULT_NEW_APP_NOTIFICATIONS,
    val firewallMode: FirewallMode = FirewallMode.AUTO,

    val refreshInterval: Int = 30,

    val systemInfo: SystemInfo = SystemInfo(
        deviceModel = "Unknown",
        androidVersion = "Unknown",
        androidROM = "Unknown",
        hasRoot = false,
        architecture = "Unknown"
    ),

    val appVersion: String = BuildConfig.VERSION_NAME,
    val buildNumber: String = BuildConfig.VERSION_CODE.toString(),

    val isLoading: Boolean = false,
    val message: String? = null,

    val hasBasicPermissions: Boolean = true,
    val hasEnhancedPermissions: Boolean = false,
    val hasAdvancedPermissions: Boolean = false
)

data class SystemInfo(
    val deviceModel: String,
    val androidVersion: String,
    val androidROM: String,
    val hasRoot: Boolean,
    val architecture: String
)


